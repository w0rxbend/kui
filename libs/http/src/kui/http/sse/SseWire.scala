package kui.http.sse

import fs2.{Pipe, Stream}
import io.circe.Json
import io.circe.parser.parse as parseJson

/** The inverse of `kui.http.sse.SseEvent.render`: raw `text/event-stream` bytes back into events.
  *
  * The gateway needs this because it is the one process that both reads and writes SSE. A service renders
  * events; the gateway parses them so that it can re-frame them with its own correlation id and count them on
  * its own metrics, and then renders them again for the browser. Going through the typed value rather than
  * copying bytes through is what lets `Sse.stream` apply the heartbeat and the terminal-event rule (ADR-035)
  * to a proxied stream exactly as it does to a locally produced one.
  *
  * ==Why it lives here==
  *
  * It began in `services/gateway/api`, with a note saying that `libs/http` renders and that nothing else in
  * KUI reads SSE on the JVM, so a shared parser would be a public API for one caller — and that if a second
  * JVM consumer ever appeared, moving the file here was the whole change. `services/cluster/client` is that
  * second consumer (ADR-046): it subscribes to the cluster service's change stream, and a second parser
  * written beside it would be the same wire format understood in two places. So the file moved, unchanged.
  *
  * ==What it deliberately does not implement==
  *
  * The `retry:` field (the gateway does not reconnect upstream; the browser reconnects to the gateway) and
  * comment lines beginning with `:` other than skipping them. Both are in the SSE specification and neither
  * is used by any KUI producer, so implementing them would be untested code.
  */
object SseWire {

  private val DefaultEventName: String = "message"

  /** Splits the byte stream into events on the blank line that terminates each one. */
  def parse[F[_]]: Pipe[F, Byte, SseEvent] =
    bytes =>
      bytes
        .through(fs2.text.utf8.decode)
        .through(fs2.text.lines)
        .through(frames)
        .map(parseFrame)
        .unNone

  /** Groups lines into frames: everything up to, but not including, a blank line. */
  private def frames[F[_]]: Pipe[F, String, List[String]] =
    lines =>
      lines
        .split(_.isEmpty)
        .map(_.toList)
        .filter(_.exists(_.nonEmpty))

  /** One frame's field lines into an event, or `None` when the frame carries no `data:` at all. */
  def parseFrame(lines: List[String]): Option[SseEvent] = {
    val fields = lines.filterNot(_.startsWith(":")).flatMap(field)
    val data = fields.collect { case ("data", value) => value }

    Option.when(data.nonEmpty) {
      SseEvent(
        name = fields.collectFirst { case ("event", value) => value }.getOrElse(DefaultEventName),
        data = payload(data.mkString("\n")),
        id = fields.collectFirst { case ("id", value) => value }
      )
    }
  }

  /** `name: value`, with the single optional space after the colon removed, per the SSE grammar. */
  private def field(line: String): Option[(String, String)] =
    line.indexOf(':') match {
      case -1 => Some((line, ""))
      case at =>
        val value = line.substring(at + 1)
        Some((line.substring(0, at), if value.startsWith(" ") then value.drop(1) else value))
    }

  /** A `data:` payload that is not JSON is kept as a JSON string rather than dropped: an event that cannot be
    * parsed is still an event that happened, and losing it silently would be worse than forwarding it in a
    * shape the browser can at least see.
    */
  private def payload(raw: String): Json = parseJson(raw).getOrElse(Json.fromString(raw))
}
