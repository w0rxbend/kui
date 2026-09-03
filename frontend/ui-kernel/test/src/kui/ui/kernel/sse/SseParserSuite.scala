package kui.ui.kernel.sse

import munit.{FunSuite, ScalaCheckSuite}
import org.scalacheck.Prop.forAll
import org.scalacheck.Gen

/** The exact bytes `libs/http` writes, so client and server are provably compatible.
  *
  * Task HTTP-004 pins this document byte for byte on the server side. Reproducing it here rather than
  * describing it means the two halves cannot drift: a change to the server's format that nobody mirrored
  * fails this suite.
  */
object GoldenStream {

  val bytes: String =
    "event: heartbeat\ndata: {}\n\n" +
      "event: done\nid: eyJ2IjoxfQ.abc\ndata: {\"reason\":\"exhausted\",\"cursor\":\"eyJ2IjoxfQ.abc\"}\n\n"
}

class SseParserSuite extends FunSuite {

  /** Feeds a whole document at once. */
  private def parse(document: String): List[RawSseEvent] =
    SseParser.feed(ParserState.empty, document)._2

  test("parsesTheGoldenWireFormatFromHttp004") {
    assertEquals(
      parse(GoldenStream.bytes),
      List(
        RawSseEvent("heartbeat", "{}", None),
        RawSseEvent(
          "done",
          """{"reason":"exhausted","cursor":"eyJ2IjoxfQ.abc"}""",
          Some("eyJ2IjoxfQ.abc")
        )
      )
    )
  }

  test("handlesMultiLineDataAndComments") {
    val document =
      ": this is a keep-alive comment\nevent: row\ndata: first\ndata: second\ndata: third\n\n"
    assertEquals(parse(document), List(RawSseEvent("row", "first\nsecond\nthird", None)))
  }

  test("ignoresUnknownFields") {
    // A stream may grow a field. An older browser must skip it, not fail: that is what lets the server
    // add one without a coordinated release.
    val document = "event: row\nsomethingNew: 42\ndata: payload\n\n"
    assertEquals(parse(document), List(RawSseEvent("row", "payload", None)))
  }

  test("handlesCrlfAndLf") {
    val lf = parse("event: row\ndata: payload\n\n")
    val crlf = parse("event: row\r\ndata: payload\r\n\r\n")
    // A bare `\r` at the very end of the input is held back, not dispatched: it might yet turn out to
    // be the first half of a `\r\n`. So the carriage-return-only document needs one more character
    // before its last event can be delivered, which is exactly what a real stream provides.
    val (state, none) = SseParser.feed(ParserState.empty, "event: row\rdata: payload\r\r")
    assertEquals(none, Nil)
    val cr = SseParser.feed(state, "event: next\r")._2

    assertEquals(crlf, lf)
    assertEquals(cr, lf)
  }

  test("emitsNothingForAnIncompleteTrailingEvent") {
    // The blank line is what ends an event. Emitting on the last `data:` line would deliver half a
    // JSON document to the decoder and report a corrupt payload for a stream that was fine.
    val (state, events) = SseParser.feed(ParserState.empty, "event: row\ndata: {\"half\":")
    assertEquals(events, Nil)

    val (_, completed) = SseParser.feed(state, " true}\n\n")
    assertEquals(completed, List(RawSseEvent("row", """{"half": true}""", None)))
  }

  test("anEventWithNoNameIsCalledMessage") {
    assertEquals(parse("data: bare\n\n"), List(RawSseEvent(SseParser.DefaultEventName, "bare", None)))
  }

  test("aBlockWithNoDataEmitsNothingButDoesNotLeakItsNameIntoTheNextEvent") {
    assertEquals(
      parse("event: ping\n\nevent: row\ndata: payload\n\n"),
      List(RawSseEvent("row", "payload", None))
    )
  }

  test("theStreamIdPersistsUntilTheServerSendsANewOne") {
    // `id` is a property of the stream, not of one event: it is the resume cursor (ADR-026), and
    // resetting it per event would make a reconnect restart from the beginning.
    assertEquals(
      parse("id: one\ndata: a\n\ndata: b\n\nid: two\ndata: c\n\n"),
      List(
        RawSseEvent("message", "a", Some("one")),
        RawSseEvent("message", "b", Some("one")),
        RawSseEvent("message", "c", Some("two"))
      )
    )
  }

  test("exactlyOneSpaceIsStrippedAfterTheColon") {
    // The format strips one space and no more. Eating the rest would change the bytes the JSON
    // decoder sees, which matters for a payload a server chose to indent.
    assertEquals(parse("data:  two spaces\n\n"), List(RawSseEvent("message", " two spaces", None)))
    assertEquals(parse("data:none\n\n"), List(RawSseEvent("message", "none", None)))
    assertEquals(parse("data\n\n"), List(RawSseEvent("message", "", None)))
  }

  test("aRetryLineIsRecordedAndDoesNotProduceAnEvent") {
    val (state, events) = SseParser.feed(ParserState.empty, "retry: 4000\n")
    assertEquals(events, Nil)
    assertEquals(state.retry, Some(4000))
  }

  test("aCarriageReturnAtTheEndOfAChunkWaitsForItsPartner") {
    // The one genuinely dangerous split: `\r` alone ends a line, but `\r\n` is one terminator. Acting
    // on the `\r` before its partner arrives invents a blank line, which in this format means
    // "dispatch" — a phantom event.
    val (state, first) = SseParser.feed(ParserState.empty, "data: payload\r")
    assertEquals(first, Nil)
    val (_, second) = SseParser.feed(state, "\n\r\n")
    assertEquals(second, List(RawSseEvent("message", "payload", None)))
  }
}

class SseParserChunkingSuite extends ScalaCheckSuite {

  /** Every way of cutting a string into consecutive pieces. */
  private def chunkings(document: String): Gen[List[String]] =
    Gen
      .listOf(Gen.choose(1, 8))
      .map { sizes =>
        val (chunks, rest) = sizes.foldLeft((List.empty[String], document)) { case ((taken, left), size) =>
          if left.isEmpty then (taken, left) else (taken :+ left.take(size), left.drop(size))
        }
        if rest.isEmpty then chunks else chunks :+ rest
      }

  property("handlesEventsSplitAcrossChunkBoundaries") {
    forAll(chunkings(GoldenStream.bytes)) { chunks =>
      val (_, events) = chunks.foldLeft((ParserState.empty, List.empty[RawSseEvent])) {
        case ((state, collected), chunk) =>
          val (next, ready) = SseParser.feed(state, chunk)
          (next, collected ++ ready)
      }
      events == SseParser.feed(ParserState.empty, GoldenStream.bytes)._2
    }
  }
}
