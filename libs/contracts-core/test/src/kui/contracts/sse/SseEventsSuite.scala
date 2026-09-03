package kui.contracts.sse

import java.time.Instant

import io.circe.parser.decode
import io.circe.syntax.*
import munit.FunSuite

import kui.contracts.{ErrorEnvelope, GoldenDocuments}
import kui.kernel.CorrelationId
import kui.kernel.error.InfrastructureError

/** The events every KUI stream shares (ADR-035).
  *
  * The names are asserted as literals because they are used on both sides of the wire as strings:
  * `addEventListener("done", ...)` in the browser, `event: done` on the server. Nothing converts
  * them, so nothing would catch a rename.
  */
final class SseEventsSuite extends FunSuite {

  private val prettyJson = io.circe.Printer.spaces2

  test("the shared event names are the ones ADR-035 fixes") {
    assertEquals(SseEventName.Phase, "phase")
    assertEquals(SseEventName.Done, "done")
    assertEquals(SseEventName.Error, "error")
    assertEquals(SseEventName.Heartbeat, "heartbeat")
    assertEquals(SseEventName.Capabilities, "capabilities")
  }

  test("a done event encodes to the golden document and round-trips") {
    val done = DoneEvent(DoneReason.Limit, Some("eyJ2IjoxfQ.c2ln"))
    assertEquals(prettyJson.print(done.asJson), GoldenDocuments.sseDone)
    assertEquals(decode[DoneEvent](GoldenDocuments.sseDone), Right(done))
  }

  test("a done event with nothing more to fetch carries a null cursor, not a missing field") {
    val done = DoneEvent(DoneReason.Exhausted, None)
    assertEquals(done.asJson.noSpaces, """{"reason":"exhausted","cursor":null}""")
    assertEquals(decode[DoneEvent](done.asJson.noSpaces), Right(done))
  }

  test("done reasons are lowercase, and all four are distinguishable") {
    assertEquals(DoneReason.values.map(_.wire).toList, List("limit", "exhausted", "budget", "cancelled"))
    DoneReason.values.foreach(reason => assertEquals(DoneReason.fromWire(reason.wire), Some(reason)))
    assertEquals(DoneReason.fromWire("LIMIT"), None)
  }

  test("a heartbeat is an empty object, so a client parses every event the same way") {
    assertEquals(HeartbeatEvent().asJson.noSpaces, "{}")
    assertEquals(decode[HeartbeatEvent]("{}"), Right(HeartbeatEvent()))
  }

  test("the error event is the ordinary error envelope, not a second error shape") {
    val event: ErrorEvent = ErrorEnvelope.of(
      InfrastructureError.Unreachable("schema-registry", "connection refused"),
      CorrelationId.unsafe("3b1fa9c2e4d54f0b"),
      Instant.parse("2026-09-03T10:11:12Z")
    )

    assertEquals(prettyJson.print(event.asJson), GoldenDocuments.sseError)
    assertEquals(decode[ErrorEnvelope](GoldenDocuments.sseError), Right(event))
    assertEquals(event.retryable, true)
  }
}
