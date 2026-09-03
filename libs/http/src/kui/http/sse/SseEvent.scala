package kui.http.sse

import java.nio.charset.StandardCharsets

import io.circe.Json

import kui.contracts.ErrorEnvelope
import kui.contracts.sse.{DoneReason, SseEventName}

/** One frame of a server-sent-events stream, before it becomes bytes.
  *
  * @param name
  *   the event name, from `SseEventName` in `contracts-core`. Named events are what let the browser route
  *   with `addEventListener` instead of switching on a field inside the payload (ADR-035).
  * @param data
  *   the JSON payload
  * @param id
  *   the signed cursor, when the stream has one (ADR-026). It reaches the browser as the SSE `id:` field,
  *   which is what `Last-Event-ID` sends back on a reconnect.
  */
final case class SseEvent(name: String, data: Json, id: Option[String] = None) {

  /** Whether this event ends the stream. Exactly one of these may be sent (ADR-035). */
  def isTerminal: Boolean = name == SseEventName.Done || name == SseEventName.Error

  def withId(cursor: String): SseEvent = copy(id = Some(cursor))
}

object SseEvent {

  /** The stream finished on purpose, and why. */
  def done(reason: DoneReason, cursor: Option[String]): SseEvent =
    SseEvent(
      SseEventName.Done,
      Json.obj(
        "reason" -> Json.fromString(reason.wire),
        "cursor" -> cursor.fold(Json.Null)(Json.fromString)
      ),
      cursor
    )

  /** The stream failed after it had started.
    *
    * It carries the same envelope an ordinary HTTP failure would (ADR-034), so the browser renders a
    * mid-stream failure with the code it already knows. A failure *before* the stream starts is an ordinary
    * HTTP error response and never reaches here.
    */
  def error(envelope: ErrorEnvelope): SseEvent =
    SseEvent(SseEventName.Error, ErrorEnvelope.given_Codec_ErrorEnvelope.apply(envelope))

  /** Sent while the stream is idle, to keep proxies and `EventSource` from giving up on it.
    *
    * An empty object rather than a comment: a comment is invisible to `EventSource`'s listeners, so a browser
    * could not tell "the connection is alive" from "nothing has happened yet", and the frontend's own parser
    * would have nothing to swallow.
    */
  val heartbeat: SseEvent = SseEvent(SseEventName.Heartbeat, Json.obj())

  /** A domain event: whatever the stream is actually about. */
  def data(name: String, payload: Json, id: Option[String] = None): SseEvent =
    SseEvent(name, payload, id)

  /** The bytes of one frame, exactly as they go on the wire.
    *
    * The field order — `event:`, then `id:` when present, then `data:` last, then a blank line — is a
    * contract, not a preference: `frontend/ui-kernel`'s parser is tested against these exact bytes, and
    * `SseSuite` asserts them here, so the two halves cannot drift apart.
    *
    * `data` is split on newlines and written as one `data:` line each, which is what the SSE format requires
    * and what the browser rejoins with a newline. Compact JSON never contains one, so in practice this is a
    * single line; it matters for a caller that hands over pretty-printed JSON.
    */
  def render(event: SseEvent): String = {
    val payload = event.data.noSpaces
    val dataLines = payload.split('\n').toList match {
      case Nil => List("data: ")
      case lines => lines.map(line => s"data: $line")
    }

    val head = s"event: ${event.name}" :: event.id.map(cursor => s"id: $cursor").toList

    (head ++ dataLines).mkString("", "\n", "\n\n")
  }

  /** UTF-8, with no byte-order mark: the SSE specification fixes the encoding, and a BOM would be delivered
    * to the browser as part of the first field name.
    */
  def bytes(event: SseEvent): Array[Byte] = render(event).getBytes(StandardCharsets.UTF_8)

  given CanEqual[SseEvent, SseEvent] = CanEqual.derived
}
