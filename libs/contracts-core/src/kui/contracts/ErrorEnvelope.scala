package kui.contracts

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}

import io.circe.syntax.*
import io.circe.{Codec, Decoder, Encoder, HCursor, Json}
import sttp.tapir.Schema

import kui.kernel.CorrelationId
import kui.kernel.error.{ErrorCode, FieldError, KuiError}

/** One field of a request and what it must satisfy, on the wire.
  *
  * It mirrors `kui.kernel.error.FieldError` rather than reusing it, because the kernel holds no codec and no
  * schema (ADR-007: wire forms live here and only here). The mapping between the two is one function in each
  * direction, and it is in this file so both halves are read together.
  */
final case class ErrorDetail(field: Option[String], restrictions: List[String])

object ErrorDetail {

  given Codec[ErrorDetail] = Codec.from(
    (cursor: HCursor) =>
      for {
        field <- cursor.get[Option[String]]("field")
        restrictions <- cursor.getOrElse[List[String]]("restrictions")(Nil)
      } yield ErrorDetail(field, restrictions),
    (detail: ErrorDetail) =>
      Json.obj("field" -> detail.field.asJson, "restrictions" -> detail.restrictions.asJson)
  )

  given Schema[ErrorDetail] = Schema
    .derived[ErrorDetail]
    .description("One request field and every rule it breaks")

  def of(error: FieldError): ErrorDetail = ErrorDetail(error.field, error.restrictions)

  given CanEqual[ErrorDetail, ErrorDetail] = CanEqual.derived
}

/** The one shape every KUI failure takes, in every service and in every stream (ADR-034).
  *
  * A client that learns to handle the errors of one endpoint handles the errors of all of them. The browser
  * switches on `code`, which is a stable string; `message` is display text and nothing more; `correlationId`
  * is what ties the response a user is looking at to the log lines a maintainer is looking at; `retryable`
  * answers "is it worth trying again" without the client having to keep a table of which codes are transient.
  *
  * `code` is a `String` rather than the `ErrorCode` enum on purpose. A browser built against an older KUI
  * must be able to decode a response from a newer one that has invented a code, and fall back to rendering
  * the message.
  */
final case class ErrorEnvelope(
    code: String,
    message: String,
    details: List[ErrorDetail],
    correlationId: String,
    timestamp: Instant,
    retryable: Boolean
)

object ErrorEnvelope {

  /** RFC 3339 in UTC with milliseconds — `2026-09-03T10:11:12.000Z`.
    *
    * Fixed to three fractional digits deliberately: `Instant.toString` drops trailing zeros, so the same
    * instant would serialise differently depending on its precision, and a golden file would pass or fail
    * depending on which machine produced it.
    */
  private val Rfc3339Millis: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

  def formatTimestamp(at: Instant): String = Rfc3339Millis.format(at)

  /** Reads the timestamp back. `Instant.parse` accepts any RFC 3339 precision, so an envelope written by
    * something stricter or looser than KUI still decodes.
    */
  private val instantDecoder: Decoder[Instant] =
    Decoder[String].emap(raw =>
      scala.util.Try(Instant.parse(raw)).toEither.left.map(_ => s"'$raw' is not an RFC 3339 instant")
    )

  private val instantEncoder: Encoder[Instant] = Encoder[String].contramap(formatTimestamp)

  given Codec[Instant] = Codec.from(instantDecoder, instantEncoder)

  /** Written out rather than derived (ADR-007). Two details are contract, not taste: `details` is always
    * present and is `[]` when empty, never `null` and never absent; and an unknown field is ignored, so a
    * service may add one without breaking every client at once.
    */
  given Codec[ErrorEnvelope] = Codec.from(
    (cursor: HCursor) =>
      for {
        code <- cursor.get[String]("code")
        message <- cursor.get[String]("message")
        details <- cursor.getOrElse[List[ErrorDetail]]("details")(Nil)
        correlationId <- cursor.get[String]("correlationId")
        timestamp <- cursor.get[Instant]("timestamp")
        retryable <- cursor.getOrElse[Boolean]("retryable")(false)
      } yield ErrorEnvelope(code, message, details, correlationId, timestamp, retryable),
    (envelope: ErrorEnvelope) =>
      Json.obj(
        "code" -> envelope.code.asJson,
        "message" -> envelope.message.asJson,
        "details" -> envelope.details.asJson,
        "correlationId" -> envelope.correlationId.asJson,
        "timestamp" -> envelope.timestamp.asJson,
        "retryable" -> envelope.retryable.asJson
      )
  )

  given Schema[Instant] = Schema.string[Instant].description("RFC 3339 timestamp in UTC")

  given Schema[ErrorEnvelope] = Schema
    .derived[ErrorEnvelope]
    .description("The error shape every KUI endpoint returns (ADR-034)")

  /** Renders a failure for the wire.
    *
    * It takes the correlation id and the instant rather than reaching for them, because a pure function is
    * one a test can pin to an exact document — which is what the golden files do. `libs/http`'s interceptor
    * supplies both from the request context.
    */
  def of(error: KuiError, correlationId: CorrelationId, at: Instant): ErrorEnvelope =
    ErrorEnvelope(
      code = error.code.wire,
      message = error.message,
      details = error.details.map(ErrorDetail.of),
      correlationId = correlationId.value,
      timestamp = at,
      retryable = error.code.retryable
    )

  /** The HTTP status this failure is served with. One mapping, used by every `api` module and by the gateway,
    * so that the same failure cannot arrive as a 500 from one service and a 409 from another.
    */
  def statusOf(error: KuiError): Int = error.code.httpStatus

  given CanEqual[ErrorEnvelope, ErrorEnvelope] = CanEqual.derived

  /** Convenience for a client: the code as the enum, when it recognises it. */
  def codeOf(envelope: ErrorEnvelope): Option[ErrorCode] = ErrorCode.fromWire(envelope.code)
}
