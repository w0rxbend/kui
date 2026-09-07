package kui.http.sse

import io.circe.Json
import munit.FunSuite

/** Parsing `text/event-stream` back into events, which two JVM consumers depend on.
  *
  * The gateway parses a service's stream so it can re-frame it with its own correlation id, and
  * `services/cluster/client` parses the cluster service's change stream. Nothing in this module asserted the
  * parser: dropping `filterNot(_.startsWith(":"))` from `parseFrame` — so a heartbeat comment becomes a field
  * — left every module green, and `Sse.stream` writes a comment line as its heartbeat, so the shape this
  * misparses is one that arrives every few seconds on every stream in the product.
  */
final class SseWireSuite extends FunSuite {

  test("a comment line is skipped rather than read as a field") {
    // `:` with no name is the SSE grammar's comment, and it is what a heartbeat looks like on the wire.
    // Parsed as a field it becomes the name `""`, which is not a field this parser knows — but a comment
    // spelled `:event: ping` would become an `event` field and rename somebody else's event.
    val frame = List(":", ":event: heartbeat", "event: cluster.changed", "data: {\"id\":\"local\"}")

    assertEquals(
      SseWire.parseFrame(frame),
      Some(SseEvent("cluster.changed", Json.obj("id" -> Json.fromString("local")), None))
    )
  }

  test("a frame that is only a comment is not an event at all") {
    assertEquals(SseWire.parseFrame(List(":", ":keep-alive")), None)
  }

  test("a frame with no data is not an event, whatever else it carries") {
    assertEquals(SseWire.parseFrame(List("event: cluster.changed", "id: 7")), None)
  }

  test("the single optional space after the colon is removed and no more") {
    val parsed = SseWire.parseFrame(List("event:  spaced", "data:  x", "id: 7"))

    assertEquals(parsed.map(_.name), Some(" spaced"))
    assertEquals(parsed.flatMap(_.id), Some("7"))
    assertEquals(parsed.map(_.data), Some(Json.fromString(" x")))
  }

  test("a data payload that is not JSON survives as a JSON string rather than being dropped") {
    // The file argues this at length: an event that cannot be parsed is still an event that happened.
    assertEquals(
      SseWire.parseFrame(List("data: not json at all")),
      Some(SseEvent("message", Json.fromString("not json at all"), None))
    )
  }

  test("a frame with no event name is `message`, which is the SSE default and not an invention") {
    assertEquals(SseWire.parseFrame(List("data: 1")).map(_.name), Some("message"))
  }
}
