package kui.serde.confluent

import munit.FunSuite

/** Avro binary in, Avro JSON out, and back again. */
final class AvroPayloadSuite extends FunSuite {

  private val definition: String =
    """{
      |  "type": "record",
      |  "name": "OrderPlaced",
      |  "fields": [
      |    {"name": "id", "type": "string"},
      |    {"name": "quantity", "type": "int"},
      |    {"name": "note", "type": ["null", "string"], "default": null}
      |  ]
      |}""".stripMargin

  private val schema: org.apache.avro.Schema =
    AvroPayload.parse(definition).fold(why => fail(why), identity)

  test("a record encodes and decodes back to the same JSON") {
    val json = """{"id":"o-1","quantity":3,"note":{"string":"gift"}}"""
    val bytes = AvroPayload.encode(schema, json).fold(why => fail(why), identity)
    assertEquals(AvroPayload.decode(schema, bytes), Right(json))
  }

  test("a null branch of a union round trips as null") {
    val json = """{"id":"o-2","quantity":0,"note":null}"""
    val bytes = AvroPayload.encode(schema, json).fold(why => fail(why), identity)
    assertEquals(AvroPayload.decode(schema, bytes), Right(json))
  }

  test("bytes that are not this schema's fail with a sentence rather than an exception") {
    val decoded = AvroPayload.decode(schema, Array[Byte](1, 2, 3, 4, 5, 6, 7, 8))
    assert(decoded.isLeft, decoded)
    assert(decoded.left.exists(_.contains("do not match the schema")), decoded)
  }

  test("a union written the way a person would write it is refused, and the message explains the shape") {
    // `"gift"` rather than `{"string": "gift"}`: the single most common mistake at a produce form, and the
    // one place where Avro's JSON encoding surprises everybody the first time.
    val refused = AvroPayload.encode(schema, """{"id":"o-3","quantity":1,"note":"gift"}""")
    assert(refused.left.exists(_.contains("names the branch of a union")), refused)
  }

  test("schema text that is not a schema fails at parse time, not at decode time") {
    assert(AvroPayload.parse("not a schema").left.exists(_.contains("could not be parsed")))
  }
}
