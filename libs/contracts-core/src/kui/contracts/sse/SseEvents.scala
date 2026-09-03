package kui.contracts.sse

import io.circe.syntax.*
import io.circe.{Codec, Decoder, Encoder, HCursor, Json}
import sttp.tapir.Schema

import kui.contracts.ErrorEnvelope

/** The event names every KUI stream uses (ADR-035).
  *
  * They are constants rather than an enum because they are used as `addEventListener` arguments in the
  * browser and as literal `event:` lines on the server, and both sides need the same string without a
  * conversion step. A stream may add data events of its own — `message`, `row`, `progress` — but every stream
  * reuses these.
  */
object SseEventName {
  val Phase: String = "phase"
  val Done: String = "done"
  val Error: String = "error"
  val Heartbeat: String = "heartbeat"
  val Capabilities: String = "capabilities"

  /** The names shared by every stream, for a test that asserts a stream emits nothing else. */
  val shared: List[String] = List(Phase, Done, Error, Heartbeat)
}

/** Why a stream ended.
  *
  * A client needs the difference: `exhausted` means there is no more data and asking again is pointless,
  * `limit` and `budget` mean there is more but this request stopped, and `cancelled` means the client itself
  * stopped it. Rendering all four as "finished" is how a user concludes a topic has 500 messages when it has
  * a million.
  */
enum DoneReason {
  case Limit, Exhausted, Budget, Cancelled

  def wire: String = this match {
    case Limit => "limit"
    case Exhausted => "exhausted"
    case Budget => "budget"
    case Cancelled => "cancelled"
  }
}

object DoneReason {

  def fromWire(raw: String): Option[DoneReason] = values.find(_.wire == raw)

  given Codec[DoneReason] = Codec.from(
    Decoder[String].emap(raw => fromWire(raw).toRight(s"'$raw' is not a done reason")),
    Encoder[String].contramap(_.wire)
  )

  given Schema[DoneReason] = Schema.string[DoneReason].description("limit, exhausted, budget or cancelled")

  given CanEqual[DoneReason, DoneReason] = CanEqual.derived
}

/** The terminal event of a successful stream.
  *
  * `cursor` is the opaque continuation token of ADR-026 when there is more to fetch, and `null` when there is
  * not — which is also how a client knows whether a "load more" control makes sense.
  */
final case class DoneEvent(reason: DoneReason, cursor: Option[String])

object DoneEvent {

  given Codec[DoneEvent] = Codec.from(
    (cursor: HCursor) =>
      for {
        reason <- cursor.get[DoneReason]("reason")
        token <- cursor.get[Option[String]]("cursor")
      } yield DoneEvent(reason, token),
    (event: DoneEvent) => Json.obj("reason" -> event.reason.asJson, "cursor" -> event.cursor.asJson)
  )

  given Schema[DoneEvent] = Schema.derived[DoneEvent]

  given CanEqual[DoneEvent, DoneEvent] = CanEqual.derived
}

/** The keep-alive, sent every 15 seconds while a stream is idle.
  *
  * It carries nothing and encodes as `{}`. Its whole job is to stop a proxy — or the browser's own
  * `EventSource` — from concluding that a quiet stream is a dead one. An empty object rather than an empty
  * payload because every other event's `data` is JSON, and a client that parses uniformly is a client with
  * one code path.
  */
final case class HeartbeatEvent()

object HeartbeatEvent {

  given Codec[HeartbeatEvent] =
    Codec.from(Decoder.const(HeartbeatEvent()), Encoder.instance(_ => Json.obj()))

  given Schema[HeartbeatEvent] = Schema.derived[HeartbeatEvent]

  given CanEqual[HeartbeatEvent, HeartbeatEvent] = CanEqual.derived
}

/** The terminal event of a failed stream.
  *
  * It is the ordinary error envelope, deliberately: a failure that happens after the response headers have
  * been sent is the same failure it would have been a moment earlier, and a client should not need a second
  * error shape to handle it (ADR-035, ADR-034).
  */
type ErrorEvent = ErrorEnvelope
