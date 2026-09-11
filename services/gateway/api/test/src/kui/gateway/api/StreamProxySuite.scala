package kui.gateway.api

import java.nio.charset.StandardCharsets
import java.time.Instant

import scala.concurrent.duration.DurationInt

import cats.effect.testkit.TestControl
import cats.effect.{Deferred, IO, Ref}
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

  /** MUnit's default is 30 seconds, and one test here pushes a million events through the relay. On an idle
    * machine that takes about nine seconds; in a full `__.test` run it shares the CPU with a dozen
    * Testcontainers suites starting brokers, and it has been seen to exceed thirty. The timeout is a safety
    * net against a stream that never finishes, not an assertion about speed, so widening it costs nothing: a
    * relay that really did accumulate would never finish at any timeout.
    */
  override def munitIOTimeout: scala.concurrent.duration.Duration = 3.minutes

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

  test("cancellingWithAFullQueueDoesNotWaitForAConsumerThatHasStopped") {
    // Hold the consumer after its first byte. That lets the producer fill the one-slot queue with the second
    // chunk and block while offering the third. Once the consumer is released, `take(1)` cancels the relay.
    // Its producer finaliser must not try to enqueue a termination marker into that still-full queue: nobody
    // is left to remove it, so such an offer makes cancellation itself hang.
    for {
      releaseConsumer <- Deferred[IO, Unit]
      thirdChunkReached <- Deferred[IO, Unit]
      upstream =
        Stream.chunk(Chunk.singleton(1.toByte)) ++
          Stream.chunk(Chunk.singleton(2.toByte)) ++
          Stream.eval(thirdChunkReached.complete(())).drain ++
          Stream.chunk(Chunk.singleton(3.toByte)) ++
          Stream.never[IO]
      relay <- StreamProxy
        .relay(upstream, queueSize = 1)
        .evalTap(_ => releaseConsumer.get)
        .take(1)
        .compile
        .drain
        .start
      _ <- thirdChunkReached.get.timeout(5.seconds)
      _ <- releaseConsumer.complete(())
      _ <- relay.joinWithNever.timeout(5.seconds)
    } yield assert(true)
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
    val failing =
      render(List(messageEvent(1))) ++ Stream.raiseError[IO](new RuntimeException("upstream died"))

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

  test("aCrLfTerminatedUpstreamsOwnTerminalEventIsRecognised") {
    // W9-A1. Every fixture in this file is built by `SseEvent.bytes`, which ends its lines with a bare
    // `\n`, so nothing here had ever put a CR LF stream through the detector — and the event-stream
    // format allows one. A CR LF upstream's frames parse correctly on both sides of this hop
    // (`SseWire` splits with `fs2.text.lines`, which treats CR LF as one break), so if the detector alone
    // missed the terminal, the gateway would append an `error` after a `done` the upstream really sent and
    // a stream that ended perfectly would reach the browser as one that broke.
    //
    // **This case is a regression guard and not a closed mutation, and the distinction is stated rather
    // than implied.** Deleting `.stripSuffix("\r")` from `isTerminalLine` leaves it green, because the
    // `trim` on the next line already removes the carriage return: the strip is provably redundant today
    // and the mutation is an equivalent one. What this holds is the *property*, against the next person
    // who replaces that `trim` with an exact comparison.
    val crlf =
      "event: message\r\ndata: {}\r\n\r\n" +
        "event: done\r\ndata: {\"reason\":\"exhausted\"}\r\n\r\n"

    textOf(
      StreamProxy.withTerminalEvent(
        Stream.emits(crlf.getBytes(StandardCharsets.UTF_8).toList).covary[IO],
        envelope
      )
    ).map { body =>
      assertEquals(body, crlf, "the upstream bytes must be forwarded unchanged")
      assert(
        !body.contains(envelope.code),
        s"the gateway appended its own error after an upstream that had already said done: $body"
      )
    }
  }

  test("aTerminalEventSplitAcrossChunkBoundariesIsStillSeen") {
    // The detection reads bytes as they pass. A chunk boundary inside `event: done` must not hide it — which
    // would append a second terminal event and break the browser's "exactly one" assumption.
    //
    // **This case does not reach the carry, and that is stated rather than implied.** It feeds `chunkLimit(1)`
    // into `withTerminalEvent`, and `relay`'s bounded queue puts the pieces back together before `observe`
    // runs: measured, the watch sees whole frames here however finely the source is chopped. What this holds
    // is the end-to-end property — one terminal event out, no second one appended — for a source that
    // produces tiny chunks. The two cases below hold the carry itself, at the level the split survives to.
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

  /* ---------------------------------------------------------------------------------------------------- *
   * The carry, driven at the level the bytes actually arrive in.
   *
   * W10-06. `TerminalWatch` holds two rules and both were held by nothing: the tail of a chunk is carried
   * into the next one, and an incomplete trailing line is *not* examined. Deleting either left all fourteen
   * cases above green, because every one of them goes through `relay` and `relay` re-chunks. So these drive
   * `observe` directly — which is what `private[api]` on the class is for — and each names the mutation it
   * refuses.
   * ---------------------------------------------------------------------------------------------------- */

  private def chunkOf(text: String): Chunk[Byte] = Chunk.array(text.getBytes(StandardCharsets.UTF_8))

  test("theTailOfAChunkIsCarriedIntoTheNextOne") {
    // The mutation: `(Vector.empty, pieces.init)` instead of `(pieces.last, pieces.init)`. A terminal event
    // whose bytes arrive either side of a chunk boundary is then never seen, the gateway appends an `error`
    // after a `done` the upstream really sent, and a stream that ended perfectly reaches the browser as one
    // that broke — the exact defect `withTerminalEvent` exists to prevent, caused by the thing that prevents
    // it. Three pieces rather than two, so that a carry which survives one boundary and is dropped at the
    // next does not pass.
    for {
      watch <- StreamProxy.TerminalWatch[IO]
      _ <- watch.observe(chunkOf("event: message\ndata: {}\n\nev"))
      afterFirst <- watch.sawTerminal
      _ <- watch.observe(chunkOf("ent"))
      afterSecond <- watch.sawTerminal
      _ <- watch.observe(chunkOf(": done\ndata: {\"reason\":\"exhausted\"}\n\n"))
      afterThird <- watch.sawTerminal
    } yield {
      assert(!afterFirst, "a `message` event was read as a terminal one")
      assert(!afterSecond, "a terminal event was announced before its line was complete")
      assert(afterThird, "a terminal event split across two chunk boundaries was not seen")
    }
  }

  test("anIncompleteTrailingLineIsNotReadAsACompleteOne") {
    // The mutation: `(pieces.last, pieces)` instead of `(pieces.last, pieces.init)`, which examines the
    // unterminated tail as though a newline had arrived. It invents terminal events: an upstream that dies
    // in the middle of writing `event: done-ish` — or `event: done` with the newline still to come and the
    // connection cut — would be recorded as having said `done`, and `withTerminalEvent` would then stay
    // silent about a stream that really was truncated.
    for {
      watch <- StreamProxy.TerminalWatch[IO]
      _ <- watch.observe(chunkOf("event: done"))
      unterminated <- watch.sawTerminal
      _ <- watch.observe(chunkOf("-ish\ndata: {}\n\n"))
      completed <- watch.sawTerminal
      _ <- watch.observe(chunkOf("event: error\ndata: {}\n\n"))
      terminal <- watch.sawTerminal
    } yield {
      assert(!unterminated, "an unterminated line was read as a complete one")
      assert(!completed, "`event: done-ish` was read as `event: done`")
      // Not a vacuous pass: a watch that answered `false` to everything would satisfy both lines above.
      assert(terminal, "a complete `event: error` line was not seen")
    }
  }

  test("theTerminalLatchIsNotClearedByTheBytesThatFollowIt") {
    // The mutation: `seen.update(_ => complete.exists(isTerminalLine))` instead of `seen.update(_ || ...)`.
    // `seen` is a latch and the disjunction is the whole of it. Drop it and every chunk that carries no
    // terminal line clears the flag, so any byte after the terminal frame — a `:` keepalive comment, a final
    // flush, a stray newline — makes `withTerminalEvent` append an `event: error` after a stream that ended
    // cleanly. That is verbatim the defect this mechanism exists to prevent, produced by the mechanism
    // itself, and the tail it needs is the ordinary shape of an SSE body rather than an exotic one.
    //
    // The three cases above cannot see it: each of them observes its terminal chunk last, so a latch that
    // merely remembers the most recent chunk answers exactly as a latch does.
    for {
      watch <- StreamProxy.TerminalWatch[IO]
      _ <- watch.observe(chunkOf("event: done\ndata: {\"reason\":\"exhausted\"}\n\n"))
      afterTerminal <- watch.sawTerminal
      _ <- watch.observe(chunkOf(": keepalive\n\n"))
      afterKeepalive <- watch.sawTerminal
      _ <- watch.observe(chunkOf("\n"))
      afterFlush <- watch.sawTerminal
    } yield {
      assert(afterTerminal, "a complete `event: done` line was not seen")
      assert(afterKeepalive, "a keepalive comment after the terminal event cleared the latch")
      assert(afterFlush, "a trailing newline after the terminal event cleared the latch")
    }
  }
}
