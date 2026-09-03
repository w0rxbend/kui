package kui.cluster.contract.dto

import java.time.Instant

import io.circe.syntax.*
import io.circe.{Codec, HCursor, Json}
import sttp.tapir.Schema

import kui.contracts.ErrorEnvelope.given

/** What `GET /internal/v1/ping` answers with.
  *
  * It is a wire type, not a domain type: `at` is an instant that will be rendered as an RFC 3339 string, and
  * `service` is a plain `String` rather than a `ServiceId` because it is being reported, not used to look
  * anything up. The domain's `Ping` has no `service` field and never will — which is the point of having two
  * types: `services/cluster/api` maps one to the other (ADR-033), and neither layer is dragged along when the
  * other changes.
  */
final case class PingResponse(message: String, at: Instant, service: String)

object PingResponse {

  /** Written out rather than derived (ADR-007).
    *
    * A derived codec is a codec nobody reads, and the JSON it produces changes silently when a field is
    * renamed. This one is three lines longer and puts the wire format in the diff, where a reviewer sees it.
    * The `Instant` codec comes from `ErrorEnvelope`, which is where KUI fixes timestamps to RFC 3339 in UTC
    * with exactly three fractional digits — without that, the same instant would serialise differently
    * depending on its precision and this DTO's golden file would pass or fail depending on the machine.
    */
  given Codec[PingResponse] = Codec.from(
    (cursor: HCursor) =>
      for {
        message <- cursor.get[String]("message")
        at <- cursor.get[Instant]("at")
        service <- cursor.get[String]("service")
      } yield PingResponse(message, at, service),
    (response: PingResponse) =>
      Json.obj(
        "message" -> response.message.asJson,
        "at" -> response.at.asJson,
        "service" -> response.service.asJson
      )
  )

  given Schema[PingResponse] = Schema
    .derived[PingResponse]
    .description("The echoed message, the instant the service saw it, and which service answered")

  given CanEqual[PingResponse, PingResponse] = CanEqual.derived
}
