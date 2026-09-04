package kui.serde.confluent

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

import scala.util.control.NonFatal

import org.apache.avro.Schema
import org.apache.avro.generic.{GenericDatumReader, GenericDatumWriter}
import org.apache.avro.io.{DecoderFactory, EncoderFactory}

/** Avro binary to JSON text and back, against a schema the registry supplied.
  *
  * Pure functions over a parsed schema and some bytes. No effect, no registry, no cache: everything about
  * *which* schema applies is decided by the caller, so this file is a codec and can be tested as one.
  *
  * ==Why Apache Avro and not Confluent's serializer==
  *
  * `io.confluent:kafka-avro-serializer` would do this too, and what it adds on top of the Avro library is a
  * registry client and a schema cache. KUI has to own both of those anyway — the client so that the registry
  * gets `libs/http`'s circuit breaker and failover rather than a second, invisible retry loop, and the cache
  * so that it is one of ADR-016's two primitives and reports the same metrics as every other cache in the
  * product. What is left is `GenericDatumReader`, which is Avro's, is Apache-2.0, and is on Maven Central.
  *
  * ==On the JSON this produces==
  *
  * Avro's own JSON encoding, not a hand-rolled one. It is verbose in exactly one place: a value of a union
  * type is written as `{"string": "hello"}` rather than as `"hello"`, because a union is genuinely ambiguous
  * without the branch name and Avro will not guess. That is the same text every other Avro tool prints, which
  * matters more than brevity — an operator comparing KUI's output with `avro-tools` or with Kafbat should see
  * the same characters.
  */
object AvroPayload {

  /** Parses schema text into the form the codec needs.
    *
    * Separate from decoding because parsing is the expensive half and the result is reusable: one schema
    * covers every record a producer wrote with it, and a page of five hundred records must parse it once. The
    * caller holds the parsed schemas; this function only makes them.
    */
  def parse(definition: String): Either[String, Schema] =
    try Right(new Schema.Parser().parse(definition))
    catch {
      case NonFatal(failure) =>
        Left(s"the registry's Avro schema could not be parsed: ${message(failure)}")
    }

  /** Avro binary to Avro JSON.
    *
    * The writer's schema is used as the reader's schema as well. That is correct for the registry case and
    * only for it: the bytes carry the id of the schema they were written with, so the exact writer schema is
    * always available, and schema *resolution* — reading old bytes through a newer reader schema — is a
    * producer/consumer concern that a viewer has no reason to impose.
    */
  // scalafix:off DisableSyntax.null
  //
  // Avro's reader and writer factories take a *reuse* argument: an object to decode into, or an encoder to
  // recycle, so that a hot loop allocates nothing. `null` is how that API spells "allocate a fresh one",
  // and there is no overload that omits it. Wrapping it in an `Option` would not remove the `null` - it
  // would only move it one line further from the call that needs it - so the ban is lifted for exactly
  // these four arguments and turned back on immediately below.
  def decode(schema: Schema, body: Array[Byte]): Either[String, String] =
    try {
      val reader = new GenericDatumReader[Any](schema)
      val decoder = DecoderFactory.get().binaryDecoder(body, null)
      val datum = reader.read(null, decoder)
      val out = new ByteArrayOutputStream()
      val encoder = EncoderFactory.get().jsonEncoder(schema, out)
      val writer = new GenericDatumWriter[Any](schema)
      writer.write(datum, encoder)
      encoder.flush()
      Right(out.toString(StandardCharsets.UTF_8))
    } catch {
      case NonFatal(failure) =>
        Left(
          "these bytes do not match the schema the record says wrote them " +
            s"(schema id in the record, Avro's complaint: ${message(failure)})"
        )
    }

  /** Avro JSON to Avro binary, for the produce form.
    *
    * The input is Avro's JSON encoding, which is what [[decode]] emits — so "copy a record, change a field,
    * send it" works, and that round trip is the single most common thing anyone does with a produce form.
    */
  def encode(schema: Schema, json: String): Either[String, Array[Byte]] =
    try {
      val reader = new GenericDatumReader[Any](schema)
      val decoder = DecoderFactory.get().jsonDecoder(schema, json)
      val datum = reader.read(null, decoder)
      val out = new ByteArrayOutputStream()
      val encoder = EncoderFactory.get().binaryEncoder(out, null)
      val writer = new GenericDatumWriter[Any](schema)
      writer.write(datum, encoder)
      encoder.flush()
      Right(out.toByteArray)
    } catch {
      case NonFatal(failure) =>
        Left(
          "this does not match the topic's Avro schema. Note that Avro's JSON encoding names the branch " +
            "of a union explicitly - a nullable string is `{\"string\": \"hello\"}` or `null`, not " +
            s"`\"hello\"`. Avro's complaint: ${message(failure)}"
        )
    }

  // scalafix:on DisableSyntax.null

  /** An exception's message, or its class when it has none.
    *
    * Never the stack trace and never the payload: `DeserializeFailure.cause` is display text and follows
    * `KuiError`'s rule, and an Avro failure's message can already be long.
    */
  private def message(failure: Throwable): String =
    Option(failure.getMessage).filter(_.nonEmpty).getOrElse(failure.getClass.getSimpleName)
}
