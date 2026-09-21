package kui.message.infrastructure

import java.time.Instant

import scala.concurrent.duration.DurationInt

import cats.effect.{IO, Resource}

import kui.cache.CacheMetrics
import kui.filter.{CelFilterEngine, FilterLimits, FilterMetrics, MessageFilterPort}
import kui.kernel.serde.{PayloadKind, SerdeName}
import kui.kernel.{ClusterId, Offset, PartitionId}
import kui.message.domain.ports.FilterVerdict
import kui.message.domain.{Decoded, DecodedRecord, FilterRef, RenderedHeader, TimestampType}
import kui.serde.confluent.{AvroPayload, JsonSchemaPayload, ProtoFile, ProtoSchema, ProtobufPayload}
import kui.testkit.KuiIOSuite

/** Proves, against real decoded bytes rather than a hand-written JSON literal, that a smart filter's
  * nested-field, array-index and array-predicate expressions — the JSONPath-equivalent patterns ADR-017
  * documents for CEL — reach Avro, Protobuf and JSON Schema values exactly as they reach a plain-JSON one.
  *
  * `CelFilterEngineSuite` and `CelFilterSourceSuite` establish that a `record.value` built from a JSON string
  * filters correctly; `AvroPayloadSuite` and `ProtobufPayloadSuite` establish that decoding produces the
  * right JSON text. Neither closes the loop between them. This suite does: it decodes real Avro bytes with
  * `AvroPayload`, real Protobuf bytes with `ProtobufPayload`, and a real JSON Schema payload with
  * `JsonSchemaPayload`, hands each result to `CelFilterSource` — the one adapter that joins a `DecodedRecord`
  * to the CEL engine — and asserts the filter's verdict.
  */
final class CelSerdeFilterSuite extends KuiIOSuite {

  private val cluster: ClusterId = ClusterId.unsafe("local")

  /** The production deadline is ten milliseconds; the first CEL evaluation in a JVM running the whole build
    * in parallel pays for class loading and would intermittently answer `Timeout` instead of the verdict
    * under test.
    */
  private val generous: FilterLimits = FilterLimits.default.copy(evaluationDeadline = 30.seconds)

  private def engine: Resource[IO, MessageFilterPort[IO]] =
    CelFilterEngine.resource[IO](cluster, generous, FilterMetrics.noop[IO], CacheMetrics.noop[IO])

  private def source: Resource[IO, CelFilterSource[IO]] =
    engine.map(port => new CelFilterSource[IO](Map(cluster -> port)))

  private def recordOf(decodedValue: String, serde: String): DecodedRecord =
    DecodedRecord(
      partition = PartitionId.unsafe(0),
      offset = Offset.unsafe(1L),
      timestamp = Instant.ofEpochMilli(1_700_000_000_000L),
      timestampType = TimestampType.CreateTime,
      key = Decoded("order-1", PayloadKind.Text, SerdeName.unsafe("String"), Map.empty),
      value = Decoded(decodedValue, PayloadKind.Json, SerdeName.unsafe(serde), Map.empty),
      headers = List.empty[RenderedHeader],
      keySize = 7,
      valueSize = decodedValue.length,
      headersSize = 0,
      decodeErrors = Nil
    )

  /** Registers and compiles an expression the way a browse does, and answers whether it matched — failing the
    * test outright on a compile or runtime error, since every case in this suite expects a clean match or
    * non-match against a well-formed decoded record.
    */
  private def matches(expression: String, against: DecodedRecord): IO[Boolean] =
    source.use { filters =>
      for {
        id <- filters.register(cluster, expression).map(_.getOrElse(fail(s"must compile: $expression")))
        ref = FilterRef.of(id, Some(expression)).getOrElse(fail("the id must be well formed"))
        compiled <- filters.compile(cluster, ref).map(_.getOrElse(fail(s"must compile: $expression")))
        verdict <- compiled.test(against)
      } yield verdict match {
        case FilterVerdict.Matched => true
        case FilterVerdict.DidNotMatch => false
        case FilterVerdict.Failed(reason) => fail(s"'$expression' failed against the record: $reason")
      }
    }

  // ------------------------------------------------------------------ Avro

  private val avroSchema: org.apache.avro.Schema = AvroPayload
    .parse(
      """{
        |  "type": "record",
        |  "name": "Order",
        |  "namespace": "shop",
        |  "fields": [
        |    {"name": "customer", "type": {
        |      "type": "record", "name": "Customer",
        |      "fields": [{"name": "address", "type": {
        |        "type": "record", "name": "Address",
        |        "fields": [{"name": "city", "type": "string"}]
        |      }}]
        |    }},
        |    {"name": "items", "type": {"type": "array", "items": {
        |      "type": "record", "name": "Item",
        |      "fields": [{"name": "sku", "type": "string"}, {"name": "price", "type": "double"}]
        |    }}}
        |  ]
        |}""".stripMargin
    )
    .fold(why => fail(why), identity)

  private def avroRecord(): DecodedRecord = {
    val json =
      """{"customer":{"address":{"city":"Kraków"}},""" +
        """"items":[{"sku":"A1","price":9.99},{"sku":"B2","price":150.0}]}"""
    val bytes = AvroPayload.encode(avroSchema, json).fold(why => fail(why), identity)
    val decoded = AvroPayload.decode(avroSchema, bytes).fold(why => fail(why), identity)
    recordOf(decoded, "Avro")
  }

  test("Avro: a nested field path filters a real decoded record") {
    matches("record.value.customer.address.city == 'Kraków'", avroRecord()).assertEquals(true)
  }

  test("Avro: an array index reaches a real decoded array element") {
    matches("record.value.items[0].sku == 'A1'", avroRecord()).assertEquals(true) >>
      matches("record.value.items[1].sku == 'A1'", avroRecord()).assertEquals(false)
  }

  test("Avro: exists() is CEL's array predicate, the JSONPath equivalent of $.items[?(@.price>100)]") {
    matches("record.value.items.exists(i, i.price > 100)", avroRecord()).assertEquals(true) >>
      matches("record.value.items.exists(i, i.price > 1000)", avroRecord()).assertEquals(false)
  }

  // ------------------------------------------------------------------ Protobuf

  private val protoFile: ProtoFile = ProtoSchema
    .parse(
      """syntax = "proto3";
        |package shop.orders;
        |
        |message Order {
        |  Customer customer = 1;
        |  repeated Item items = 2;
        |
        |  message Customer {
        |    Address address = 1;
        |  }
        |  message Address {
        |    string city = 1;
        |  }
        |  message Item {
        |    string sku = 1;
        |    double price = 2;
        |  }
        |}
        |""".stripMargin
    )
    .fold(why => fail(why), identity)

  private def protoRecord(): DecodedRecord = {
    val address = Encoder().string(1, "Kraków").result()
    val customer = Encoder().message(1, address).result()
    val firstItem = Encoder().string(1, "A1").double(2, 9.99).result()
    val secondItem = Encoder().string(1, "B2").double(2, 150.0).result()
    val order = Encoder().message(1, customer).message(2, firstItem).message(2, secondItem).result()
    // The Confluent framing byte for a schema with a single top-level message: an empty message-index path.
    val framed = Array[Byte](0) ++ order
    val decoded = ProtobufPayload.decode(protoFile, framed).fold(why => fail(why), identity)
    recordOf(decoded, "Protobuf")
  }

  test("Protobuf: a nested message field path filters a real decoded record") {
    matches("record.value.customer.address.city == 'Kraków'", protoRecord()).assertEquals(true)
  }

  test("Protobuf: an array index reaches a real decoded repeated-message element") {
    matches("record.value.items[0].sku == 'A1'", protoRecord()).assertEquals(true) >>
      matches("record.value.items[1].sku == 'A1'", protoRecord()).assertEquals(false)
  }

  test("Protobuf: exists() reaches a real decoded repeated field") {
    matches("record.value.items.exists(i, i.price > 100)", protoRecord()).assertEquals(true) >>
      matches("record.value.items.exists(i, i.price > 1000)", protoRecord()).assertEquals(false)
  }

  /** A minimal Protobuf encoder for this suite's own fixtures, independent of `ProtobufPayloadSuite`'s: the
    * same reasoning applies as there — a decoder checked only against its own encoder proves nothing about
    * the decoder, but this suite's business is the decode-then-filter join rather than the decoder itself
    * (which `ProtobufPayloadSuite` already checks against `protoc`'s own bytes), so it only needs the small
    * set of wire primitives this schema uses.
    */
  final private class Encoder {
    private val out = new java.io.ByteArrayOutputStream()

    private def writeVarint(value: Long): Unit = {
      var remaining = value
      var continue = true
      while continue do {
        val chunk = (remaining & 0x7fL).toInt
        remaining = remaining >>> 7
        if remaining == 0L then {
          out.write(chunk)
          continue = false
        } else out.write(chunk | 0x80)
      }
    }

    private def tag(number: Int, wireType: Int): Unit = writeVarint((number.toLong << 3) | wireType.toLong)

    private def bytesField(number: Int, value: Array[Byte]): Encoder = {
      tag(number, 2)
      writeVarint(value.length.toLong)
      out.write(value)
      this
    }

    def string(number: Int, value: String): Encoder = bytesField(number, value.getBytes("UTF-8"))

    def message(number: Int, body: Array[Byte]): Encoder = bytesField(number, body)

    def double(number: Int, value: Double): Encoder = {
      tag(number, 1)
      val bits = java.lang.Double.doubleToLongBits(value)
      (0 until 8).foreach(index => out.write(((bits >>> (8 * index)) & 0xffL).toInt))
      this
    }

    def result(): Array[Byte] = out.toByteArray
  }

  // ------------------------------------------------------------------ JSON Schema

  private val jsonSchema = JsonSchemaPayload
    .parse(
      """{
        |  "$schema": "https://json-schema.org/draft/2020-12/schema",
        |  "type": "object",
        |  "properties": {
        |    "customer": {"type": "object", "properties": {
        |      "address": {"type": "object", "properties": {"city": {"type": "string"}}}
        |    }},
        |    "items": {"type": "array", "items": {"type": "object", "properties": {
        |      "sku": {"type": "string"}, "price": {"type": "number"}
        |    }}}
        |  }
        |}""".stripMargin
    )
    .fold(why => fail(why), identity)

  private def jsonSchemaRecord(): DecodedRecord = {
    val json =
      """{"customer":{"address":{"city":"Kraków"}},""" +
        """"items":[{"sku":"A1","price":9.99},{"sku":"B2","price":150.0}]}"""
    val bytes = JsonSchemaPayload.encode(jsonSchema, json).fold(why => fail(why), identity)
    val decoded = JsonSchemaPayload.decode(bytes).fold(why => fail(why), identity)
    recordOf(decoded, "JsonSchema")
  }

  test("JSON Schema: a nested field path filters a real validated-and-decoded record") {
    matches("record.value.customer.address.city == 'Kraków'", jsonSchemaRecord()).assertEquals(true)
  }

  test("JSON Schema: an array index and exists() both reach a real decoded array") {
    matches("record.value.items[0].sku == 'A1'", jsonSchemaRecord()).assertEquals(true) >>
      matches("record.value.items.exists(i, i.price > 100)", jsonSchemaRecord()).assertEquals(true) >>
      matches("record.value.items.exists(i, i.price > 1000)", jsonSchemaRecord()).assertEquals(false)
  }
}
