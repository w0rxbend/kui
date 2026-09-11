package kui.schema.contract

import io.circe.parser.parse
import io.circe.syntax.*
import munit.FunSuite
import sttp.tapir.{Schema as TapirSchema, SchemaType}

import kui.kernel.Subject
import kui.schema.contract.dto.{
  CompatibilityCheckRequest,
  RegisterSchemaRequest,
  SchemaReferenceDto,
  SetCompatibilityRequest
}

/** The two write bodies of the schema service, and the one place their decoder and their document meet.
  *
  * `services.schema.contract.jvm.test` resolved as a test target and contained **no source file at all**
  * until this one, which is how the same class of defect stayed in the tree for two milestones: a module that
  * reports zero cases is a green gate over an unmeasured file, and `scripts/run-tests.sh` names it out loud
  * on every run without failing.
  *
  * What is asserted here is the pair of facts nothing else could see. Both request codecs default
  * `schemaType` to `AVRO` when it is absent — the registry's own convention, and therefore what a client that
  * copied a registry payload will send — while Tapir derives every non-`Option` field as *required*, so the
  * published document demanded a field the server does not need. Two artefacts generated from one file,
  * disagreeing about the same field.
  *
  * Cross-compiled, so both the JVM and the browser half of this contract run it (ADR-003).
  */
final class SchemaRequestDtosSuite extends FunSuite {

  private val reference = SchemaReferenceDto("Address", Subject.unsafe("address-value"), 2)

  test("a registration decodes the shape its own encoder writes, field for field") {
    // House rule 12's shape: the input is the encoder's own output rather than a literal typed by hand.
    // Two hand-written literals on two sides of a wire is exactly how M7 shipped two cards that draw
    // nothing, both sides green against their own idea of the document.
    val original = RegisterSchemaRequest("PROTOBUF", """syntax = "proto3";""", List(reference))
    val decoded = parse(original.asJson.noSpaces).flatMap(_.as[RegisterSchemaRequest])

    assertEquals(decoded, Right(original))
  }

  test("a compatibility check decodes the shape its own encoder writes, field for field") {
    val original = CompatibilityCheckRequest("JSON", """{"type":"object"}""", List(reference))
    val decoded = parse(original.asJson.noSpaces).flatMap(_.as[CompatibilityCheckRequest])

    assertEquals(decoded, Right(original))
  }

  test("a body that names no schemaType is Avro, and a body that names no references has none") {
    // The registry's convention, and the reason it is worth keeping: a caller who copied a payload out
    // of Confluent's own API sends neither field, and refusing that body would make KUI stricter than
    // the system it is a window onto.
    val body = """{"definition":"{\"type\":\"record\"}"}"""

    assertEquals(
      parse(body).flatMap(_.as[RegisterSchemaRequest]),
      Right(RegisterSchemaRequest("AVRO", """{"type":"record"}""", Nil))
    )
    assertEquals(
      parse(body).flatMap(_.as[CompatibilityCheckRequest]),
      Right(CompatibilityCheckRequest("AVRO", """{"type":"record"}""", Nil))
    )
  }

  test("a body that names no definition is refused, because there is nothing to register") {
    // The one field with no sensible default. It is also the field a refusal points at, so a decoder
    // that invented an empty definition would turn a caller's mistake into a registry error.
    assert(parse("""{"schemaType":"AVRO"}""").flatMap(_.as[RegisterSchemaRequest]).isLeft)
    assert(parse("""{"schemaType":"AVRO"}""").flatMap(_.as[CompatibilityCheckRequest]).isLeft)
  }

  test("the published document does not demand a field the decoder defaults") {
    // The rule this module exists to hold: `schemaType` is optional in the schema *because* it is
    // optional in the codec above. Derived from a non-`Option` field, Tapir marks it required, the
    // generated browser types make it mandatory, and a client that sends the registry's own payload is
    // refused by its own type checker for a body this server accepts.
    assertEquals(fieldIsOptional[RegisterSchemaRequest]("schemaType"), true)
    assertEquals(fieldIsOptional[CompatibilityCheckRequest]("schemaType"), true)

    // And the field that has no default stays required, so "optional" is a decision per field rather
    // than a switch somebody flipped on the type.
    assertEquals(fieldIsOptional[RegisterSchemaRequest]("definition"), false)
    assertEquals(fieldIsOptional[CompatibilityCheckRequest]("definition"), false)
    assertEquals(fieldIsOptional[SetCompatibilityRequest]("level"), false)
  }

  /** Whether the derived document marks this field optional — the property `required` is rendered from. */
  private def fieldIsOptional[A](name: String)(using schema: TapirSchema[A]): Boolean =
    schema.schemaType match {
      case product: SchemaType.SProduct[?] =>
        product.fields
          .find(_.name.name == name)
          .map(_.schema.isOptional)
          .getOrElse(fail(s"$name is not a field of this schema"))
      case other => fail(s"expected a product schema, got $other")
    }
}
