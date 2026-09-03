package kui.contracts

import java.time.Instant

import io.circe.parser.decode
import io.circe.syntax.*
import munit.FunSuite

import kui.kernel.CorrelationId
import kui.kernel.error.*

/** The error envelope, pinned to committed sample documents.
  *
  * A round-trip test alone would let the shape change freely as long as both halves changed
  * together, which is exactly what a contract must not allow: the other side of the wire is a
  * browser, or somebody's script, that was not recompiled. The golden documents are the contract, and
  * changing one is a deliberate act that shows up in a diff.
  */
final class ErrorEnvelopeSuite extends FunSuite {

  private val at            = Instant.parse("2026-09-03T10:11:12Z")
  private val correlationId = CorrelationId.unsafe("3b1fa9c2e4d54f0b")
  private val prettyJson    = io.circe.Printer.spaces2

  private val validation = ApplicationError.Invalid(
    "Request is not valid",
    List(FieldError.of("partitions", "must be > 0"))
  )

  private val upstream = InfrastructureError.Unreachable("schema-registry", "connection refused")

  test("a validation failure encodes to the golden document, byte for byte") {
    assertEquals(
      prettyJson.print(ErrorEnvelope.of(validation, correlationId, at).asJson),
      GoldenDocuments.errorEnvelopeValidation
    )
  }

  test("an upstream failure encodes to the golden document, byte for byte") {
    assertEquals(
      prettyJson.print(ErrorEnvelope.of(upstream, correlationId, at).asJson),
      GoldenDocuments.errorEnvelopeUpstream
    )
  }

  test("both envelope documents decode") {
    List(
      "error-envelope-validation.json" -> GoldenDocuments.errorEnvelopeValidation,
      "error-envelope-upstream.json"   -> GoldenDocuments.errorEnvelopeUpstream
    ).foreach { document =>
      assert(decode[ErrorEnvelope](document._2).isRight, s"${document._1} did not decode")
    }
  }

  test("an envelope survives a round trip through its own codec") {
    val envelope = ErrorEnvelope.of(validation, correlationId, at)
    assertEquals(decode[ErrorEnvelope](envelope.asJson.noSpaces), Right(envelope))
  }

  test("a field a newer service added is ignored rather than fatal") {
    val withExtra = """{"code":"KUI-VALIDATION","message":"m","details":[],
                      |"correlationId":"c","timestamp":"2026-09-03T10:11:12.000Z",
                      |"retryable":false,"hint":"try fewer partitions"}""".stripMargin

    assertEquals(decode[ErrorEnvelope](withExtra).map(_.code), Right("KUI-VALIDATION"))
  }

  test("a code this client has never heard of still decodes, so an older browser keeps working") {
    val newer = """{"code":"KUI-SOMETHING-NEWER","message":"m","details":[],
                  |"correlationId":"c","timestamp":"2026-09-03T10:11:12.000Z","retryable":true}""".stripMargin

    val envelope = decode[ErrorEnvelope](newer)
    assertEquals(envelope.map(_.code), Right("KUI-SOMETHING-NEWER"))
    assertEquals(envelope.toOption.flatMap(ErrorEnvelope.codeOf), None)
  }

  test("details is always an array, never null and never missing") {
    val encoded = ErrorEnvelope.of(upstream, correlationId, at).asJson
    assertEquals(encoded.hcursor.get[List[ErrorDetail]]("details"), Right(Nil))
    assert(encoded.hcursor.downField("details").focus.exists(_.isArray))
  }

  test("statusOf covers every error code, with the status ADR-034 assigns") {
    val expected: Map[ErrorCode, Int] = ErrorCode.values.map(code => code -> code.httpStatus).toMap

    ErrorCode.values.foreach { code =>
      val error = ApplicationError.NotFound("Thing", "id", code)
      assertEquals(ErrorEnvelope.statusOf(error), expected(code), clue = code.wire)
      assert(ErrorEnvelope.statusOf(error) >= 400, clue = code.wire)
    }
  }

  test("the envelope's retryable flag comes from the code, not from the caller") {
    assertEquals(ErrorEnvelope.of(upstream, correlationId, at).retryable, true)
    assertEquals(ErrorEnvelope.of(validation, correlationId, at).retryable, false)
  }

  test("an upstream's response body never reaches the envelope's message") {
    val leaky = InfrastructureError.Unreachable(
      "schema-registry",
      "401 from https://kui:hunter2@registry.internal/subjects"
    )
    val envelope = ErrorEnvelope.of(leaky, correlationId, at)

    assert(!envelope.message.contains("hunter2"), envelope.message)
    assert(!envelope.asJson.noSpaces.contains("hunter2"), envelope.asJson.noSpaces)
  }

  test("timestamps are RFC 3339 in UTC with exactly three fractional digits") {
    val cases = List(
      Instant.parse("2026-09-03T10:11:12Z")         -> "2026-09-03T10:11:12.000Z",
      Instant.parse("2026-09-03T10:11:12.5Z")       -> "2026-09-03T10:11:12.500Z",
      Instant.parse("2026-09-03T10:11:12.123456Z")  -> "2026-09-03T10:11:12.123Z"
    )

    cases.foreach { row =>
      assertEquals(ErrorEnvelope.formatTimestamp(row._1), row._2)
      assertEquals(
        ErrorEnvelope.of(validation, correlationId, row._1).asJson.hcursor.get[String]("timestamp"),
        Right(row._2)
      )
    }
  }

  test("a timestamp written with a different precision still decodes") {
    val document =
      GoldenDocuments.errorEnvelopeUpstream.replace("10:11:12.000Z", "10:11:12Z")

    assertEquals(
      decode[ErrorEnvelope](document).map(_.timestamp),
      Right(Instant.parse("2026-09-03T10:11:12Z"))
    )
  }
}
