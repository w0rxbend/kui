package kui.gateway.api

import java.nio.charset.StandardCharsets
import java.time.Instant

import scala.concurrent.duration.DurationInt

import cats.effect.testkit.TestControl
import cats.effect.{IO, Ref}
import fs2.{Chunk, Stream}
import io.circe.Json
import munit.CatsEffectSuite

import kui.contracts.ErrorEnvelope
import kui.contracts.sse.{DoneReason, SseEventName}
import kui.http.sse.{SseEvent, SseWire}

/** That the gateway's hop is transparent, bounded, cancellable and never silent.
  *
  * Each test here is one of the four ways this hop can be wrong, and every one of them is invisible from
  * either end alone: the service's own suite sees a correct stream, the browser's sees a correct parser, and
  * the defect lives in the middle.
  */
final class StreamProxySuite extends CatsEffectSuite {

  private val envelope = ErrorEnvelope(
    code = "KUI-UPSTREAM-UNAVAILABLE",
    message = "the message service stopped sending",
    details = Nil,
    correlationId = "3b1fa9c2e4d54f0b",
    timestamp = Instant.parse("2026-09-03T10:11:12Z"),
    retryable = true
  )

  private def bytesOf(event: SseEvent): Chunk[Byte] = Chunk.array(SseEvent.bytes(event))

  private def messageEvent(offset: Int): SseEvent =
    SseEvent.data("message", Json.obj("offset" -> Json.fromInt(offset)))

  private def render(events: List[SseEvent]): Stream[IO, Byte] =
    Stream.emits(events).flatMap(event => Stream.chunk(bytesOf(event)))

  private def textOf(stream: Stream[IO, Byte]): IO[String] =
    stream.compile.to(Array).map(new String(_, StandardCharsets.UTF_8))

  test("bytesAreForwardedUnchanged") {
    // The gateway is not allowed to reformat. The frontend's parser is tested against the exact bytes the
    // service writes, so anything this hop normalises — a field order, a trailing newline — breaks a client
    // that both other suites say is correct.
    val events = List(
      SseEvent.data("phase", Json.obj("name" -> Json.fromString("Consumer created"))),
      messageEvent(41284),
      SseEvent.heartbeat,
      SseEvent.done(DoneReason.Exhausted, Some("cursor-1"))
    )
    val original = render(events)

    for {
      before <- textOf(original)
      after <- textOf(StreamProxy.relay(original))
    } yield assertEquals(after, before)
  }

  test("bytesAreForwardedUnchangedAcrossArbitraryChunkBoundaries") {
    // A transport chops a body wherever it likes, including in the middle of a field name. Re-chunking must
    // not change what comes out.
    val events = (0 until 50).toList.map(messageEvent)
    val whole = SseEvent.render(events.head) // shape check below uses the full rendering

    val original = render(events)
    val chopped = original.chunkLimit(3).flatMap(Stream.chunk)

    for {
      before <- textOf(original)
      after <- textOf(StreamProxy.relay(chopped, queueSize = 2))
    } yield {
      assertEquals(after, before)
      assert(before.startsWith(whole), before.take(80))
    }
  }

  test("doesNotApplyTheRequestTimeoutToTheBody") {
    // R-8. Five minutes of virtual time through a proxy whose upstream request timeout is thirty seconds.
    // The timeout belongs to the call that obtained the response, and nothing here re-applies it to the body:
    // a tail that died every thirty seconds would look like a Kafka fault and be debugged as one.
    val upstream = Stream
      .awakeEvery[IO](1.second)
      .zipWithIndex
      .map((_, index) => index.toInt)
      .map(messageEvent)
      .flatMap(event => Stream.chunk(bytesOf(event)))

    val counted = StreamProxy
      .relay(upstream)
      .through(SseWire.parse)
      .take(300)
      .compile
      .count

    TestControl.executeEmbed(counted).map(assertEquals(_, 300L))
  }

  test("cancellingTheDownstreamCancelsTheUpstream") {
    // The middle link of the milestone's third exit criterion: browser abort, gateway cancellation, upstream
    // cancellation, and — at the far end — a closed Kafka consumer.
    for {
      cancelled <- Ref[IO].of(false)
      upstream = Stream
        .awakeEvery[IO](10.millis)
        .map(_ => messageEvent(0))
        .flatMap(event => Stream.chunk(bytesOf(event)))
        .onFinalize(cancelled.set(true))
      _ <- StreamProxy.relay(upstream).through(SseWire.parse).take(1).compile.drain
      // `concurrently` cancels the producer as the consumer finishes, so the finaliser may land a moment
      // later; waiting on the value rather than asserting immediately is what stops this being a flake.
      _ <- cancelled.get.iterateUntil(identity).timeout(5.seconds)
      seen <- cancelled.get
    } yield assert(seen)
  }

  test("backpressuresRatherThanDropping") {
    // Dropping here would lose records the service has already counted as delivered in its `consumed` event,
    // and no client could detect the discrepancy. A consumer slower than the producer must therefore slow the
    // producer down, not lose its output.
    val events = (0 until 500).toList.map(messageEvent)
    val slow = StreamProxy
      .relay(render(events), queueSize = 4)
      .through(SseWire.parse)
      .evalMap(event => IO.sleep(1.milli).as(event))

    TestControl
      .executeEmbed(slow.compile.toList)
      .map { relayed =>
        assertEquals(relayed.length, events.length)
        assertEquals(relayed.map(_.data), events.map(_.data))
      }
  }

  test("boundedQueueMemoryIsIndependentOfStreamLength") {
    // A million events through a queue of 64. If the queue were unbounded — or if `relay` accumulated
    // anywhere — this is the test that would not finish.
    val many = Stream
      .range(0, 1000000)
      .map(messageEvent)
      .flatMap(event => Stream.chunk(bytesOf(event)))
      .covary[IO]

    StreamProxy
      .relay(many, queueSize = 64)
      .through(SseWire.parse)
      .compile
      .count
      .map(assertEquals(_, 1000000L))
  }

  test("aQueueSizeBelowOneStillMoves") {
    // A misconfigured buffer size must not be a stream that never delivers anything.
    textOf(StreamProxy.relay(render(List(messageEvent(1))), queueSize = 0))
      .map(text => assert(text.contains("\"offset\":1"), text))
  }

  test("anUpstreamThatEndsWithoutATerminalEventGetsOne") {
    // The reference product's failure mode, and the one this milestone exists to replace: a connection that
    // simply stops. The browser cannot tell that from a finished search, so it shows what it has and says
    // nothing at all.
    val truncated = render(List(messageEvent(1), messageEvent(2)))

    StreamProxy
      .withTerminalEvent(truncated, envelope)
      .through(SseWire.parse)
      .compile
      .toList
      .map { events =>
        assertEquals(events.map(_.name), List("message", "message", SseEventName.Error))
        assert(events.last.data.noSpaces.contains("KUI-UPSTREAM-UNAVAILABLE"), events.last.data.noSpaces)
      }
  }

  test("anUpstreamThatFailsMidBodyKeepsTheBytesItAlreadySentAndGainsAnErrorEvent") {
    // ADR-032's stale-data rule: what arrived stands. Discarding a half page because the last poll failed is
    // the behaviour the research records as a defect.
    val failing = render(List(messageEvent(1))) ++ Stream.raiseError[IO](new RuntimeException("upstream died"))

    StreamProxy
      .withTerminalEvent(failing, envelope)
      .through(SseWire.parse)
      .compile
      .toList
      .map { events =>
        assertEquals(events.map(_.name), List("message", SseEventName.Error))
        assert(events.head.data.noSpaces.contains("\"offset\":1"), events.head.data.noSpaces)
      }
  }

  test("theGatewayDoesNotSynthesiseATerminalEventForAStreamThatHasOne") {
    // Neither a second `done` nor an `error` of the gateway's own over the upstream's. The upstream knows
    // what happened; the gateway does not, and a duplicated terminal event breaks ADR-035's "exactly one".
    val complete = render(List(messageEvent(1), SseEvent.done(DoneReason.Exhausted, None)))

    StreamProxy
      .withTerminalEvent(complete, envelope)
      .through(SseWire.parse)
      .compile
      .toList
      .map(events => assertEquals(events.map(_.name), List("message", SseEventName.Done)))
  }

  test("anUpstreamErrorEventIsForwardedAndNotReplaced") {
    val failed = render(List(SseEvent.error(envelope.copy(code = "KUI-KAFKA-TIMEOUT"))))

    StreamProxy
      .withTerminalEvent(failed, envelope)
      .through(SseWire.parse)
      .compile
      .toList
      .map { events =>
        assertEquals(events.map(_.name), List(SseEventName.Error))
        assert(events.head.data.noSpaces.contains("KUI-KAFKA-TIMEOUT"), events.head.data.noSpaces)
      }
  }

  test("aTerminalEventSplitAcrossChunkBoundariesIsStillSeen") {
    // The detection reads bytes as they pass. A chunk boundary inside `event: done` must not hide it — which
    // would append a second terminal event and break the browser's "exactly one" assumption.
    val complete = render(List(messageEvent(1), SseEvent.done(DoneReason.Limit, Some("cursor-9"))))
      .chunkLimit(1)
      .flatMap(Stream.chunk)

    StreamProxy
      .withTerminalEvent(complete, envelope)
      .through(SseWire.parse)
      .compile
      .toList
      .map(events => assertEquals(events.map(_.name), List("message", SseEventName.Done)))
  }
}
