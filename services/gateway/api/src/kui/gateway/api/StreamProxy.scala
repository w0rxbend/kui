package kui.gateway.api

import cats.effect.kernel.{Async, Ref, Resource}
import cats.effect.std.Queue
import cats.syntax.all.*
import fs2.{Chunk, Stream}

import kui.contracts.ErrorEnvelope
import kui.contracts.sse.SseEventName
import kui.http.sse.SseEvent

/** Re-streams an upstream server-sent-events body to the browser.
  *
  * The gateway is the only process a browser talks to, so every stream a service produces has to travel one
  * more hop. This object is that hop, and it does as little as possible on purpose: it moves bytes through a
  * bounded queue and it notices whether the stream ended properly. It never parses an event in order to
  * re-encode it — a gateway that decoded and re-serialised the envelope would be a second place where the
  * envelope is defined, and the two would eventually disagree about a field name with no test able to see it.
  *
  * ==Three rules that are each easy to get wrong, and each a named test==
  *
  *   1. **The per-upstream request timeout applies to the response headers, not to the body.** A tail is
  *      supposed to stay open for hours. A thirty-second call timeout applied to the body would kill live
  *      mode every thirty seconds, and it would look to the user like a Kafka problem rather than a gateway
  *      one. `UpstreamClient` bounds the call that *obtains* the response; nothing here bounds the body, and
  *      `StreamProxySuite.doesNotApplyTheRequestTimeoutToTheBody` runs five minutes of virtual time through
  *      it to say so.
  *   2. **The queue between upstream and downstream is bounded and blocks.** It never drops. Dropping here
  *      would lose records the service has already counted as delivered and reported in its `consumed` event,
  *      and no client could detect the discrepancy: the numbers would simply be wrong. Blocking instead
  *      pushes backpressure up to the Kafka consumer, which is where it belongs.
  *   3. **A stream that dies without a terminal event gets one.** The reference product's failure mode is a
  *      connection that simply stops: the browser cannot tell a finished search from a broken one, so it
  *      shows whatever it has and says nothing. ADR-035 gives every stream a terminal `done` or `error`, and
  *      [[withTerminalEvent]] is what makes that true even when the upstream process was killed mid-body.
  */
object StreamProxy {

  /** How many upstream chunks may sit in the hand-off queue.
    *
    * Chunks, not bytes: fs2 hands the body over in whatever sizes the transport produced, so this bounds the
    * *number* of outstanding pieces rather than the memory exactly. It is small deliberately. The queue is
    * there to decouple the two hops by a little, not to buffer a stream — a large queue would mean the
    * gateway holding records the browser has not asked for and cannot be told about.
    */
  val DefaultQueueSize: Int = 64

  /** Moves an upstream body downstream through a bounded, blocking queue.
    *
    * Cancellation runs the other way: when the browser goes away the downstream stream is cancelled, fs2
    * cancels the producer running under `concurrently`, and that cancellation reaches the upstream request —
    * which reaches the service, which closes its Kafka consumer. That chain is the milestone's third exit
    * criterion, and this is its middle link.
    *
    * An upstream failure is raised here rather than swallowed, so that the caller can decide what the client
    * is told. [[withTerminalEvent]] is that decision for an SSE body.
    *
    * @param queueSize
    *   values below one are treated as one. A queue of zero would be a stream that never moves, and failing
    *   the whole request over a misconfigured buffer size would be a worse answer than the smallest one that
    *   works.
    */
  def relay[F[_]: Async](upstream: Stream[F, Byte], queueSize: Int = DefaultQueueSize): Stream[F, Byte] =
    Stream
      .eval(Queue.bounded[F, Option[Chunk[Byte]]](math.max(1, queueSize)))
      .flatMap { queue =>
        val producer =
          upstream.chunks
            .evalMap(chunk => queue.offer(Some(chunk)))
            .onFinalizeCase {
              // A normal upstream completion still has a live consumer, so waiting for room preserves every
              // queued chunk before the termination marker. If the producer is being cancelled, the consumer
              // has already stopped: a blocking offer into a full queue would make that cancellation hang.
              case Resource.ExitCase.Succeeded => queue.offer(None)
              case Resource.ExitCase.Canceled | Resource.ExitCase.Errored(_) => Async[F].unit
            }

        // `concurrently` is what gives all three rules at once: the producer is cancelled when the consumer
        // finishes or is cancelled, and a producer failure is raised into the consumer rather than leaving
        // it waiting on a queue nobody will fill again.
        Stream.fromQueueNoneTerminatedChunk(queue).concurrently(producer)
      }

  /** Relays a body and guarantees it ends with one of ADR-035's terminal events.
    *
    * If the upstream sent its own `done` or `error`, nothing is added: the gateway does not synthesise a
    * terminal event for a stream that already has one, and it does not replace the upstream's `error` with
    * one of its own, because the upstream knows what went wrong and the gateway does not.
    *
    * If the upstream ended — or failed — without a terminal event, the envelope given here is appended as an
    * `error` event. That is the behaviour this milestone exists to beat: a truncated connection with no
    * explanation, which the browser can only render as "the search finished, apparently".
    *
    * The bytes the upstream sent are forwarded unchanged. The detection below reads them as it passes them
    * on; it never rebuilds them.
    */
  def withTerminalEvent[F[_]: Async](
      upstream: Stream[F, Byte],
      onMissingTerminal: => ErrorEnvelope,
      queueSize: Int = DefaultQueueSize
  ): Stream[F, Byte] =
    Stream.eval(TerminalWatch[F]).flatMap { watch =>
      val body = relay(upstream, queueSize).chunks.evalTap(watch.observe).flatMap(Stream.chunk)

      // `handleErrorWith` catches the upstream failure that `relay` raised, and the append after it covers
      // the quieter case: an upstream that closed the connection cleanly having said nothing terminal.
      body.handleErrorWith(_ => Stream.empty) ++ Stream
        .eval(watch.sawTerminal)
        .flatMap {
          case true => Stream.empty
          case false => Stream.emits(SseEvent.bytes(SseEvent.error(onMissingTerminal)))
        }
    }

  /** Whether a terminal event has gone past, worked out from the bytes without decoding them.
    *
    * SSE frames are line-oriented, and a terminal frame is identified by one line: `event: done` or
    * `event: error`. A chunk can end in the middle of a line, so the parser position is carried into the next
    * one. Only complete lines are accepted, which is why a chunk boundary cannot hide a terminal event and
    * cannot invent one either. Bytes from `data:` lines are not retained: once a line cannot be a terminal
    * event, one constant-size discard state is enough until its newline arrives.
    *
    * ==Visible to this package's tests, and why it has to be==
    *
    * `private[api]` rather than `private`, and the one word is load-bearing. The carry is *usually*
    * unreachable from [[withTerminalEvent]]: everything above goes through [[relay]], whose bounded queue
    * re-chunks the body before [[observe]] ever sees it, so a split a caller made upstream usually does not
    * survive to here. *Usually* is the corrected word — this paragraph said "cannot", and published as a
    * measurement that with the carry deleted (`(Vector.empty, pieces.init)`) the whole suite stayed green
    * including `aTerminalEventSplitAcrossChunkBoundariesIsStillSeen`, which feeds `chunkLimit(1)`. That is
    * not what the mutation does. W11-A2 ran it four consecutive times on 2026-09-12: the case was **red on
    * run 2** and green on runs 1, 3 and 4. [[relay]] drains its `Queue.bounded` from a second fibre, so how
    * many source chunks are coalesced into one dequeued chunk is a scheduling outcome rather than a property
    * of this code, and a gate that fires one run in four reads as a flake. So that case holds the end-to-end
    * property only — one terminal event out, none appended — and is not a gate on the carry;
    * `StreamProxySuite` carries the four-run table beside it and says so. Its direct [[observe]] cases are
    * the deterministic gates on parser state crossing chunk boundaries.
    */
  final private[api] class TerminalWatch[F[_]: Async] private (
      line: Ref[F, TerminalWatch.LineState],
      seen: Ref[F, Boolean]
  ) {

    def observe(chunk: Chunk[Byte]): F[Unit] =
      line.modify(current => TerminalWatch.scan(current, chunk)).flatMap(found => seen.update(_ || found))

    def sawTerminal: F[Boolean] = seen.get

    /** Payload bytes retained solely for terminal detection. Package-visible so the bounded-memory rule can
      * be asserted directly instead of inferred from a timing-sensitive heap measurement.
      */
    private[api] def retainedPayloadBytes: F[Int] = line.get.map(_.retainedPayloadBytes)
  }

  private val Newline: Byte = '\n'.toByte

  private[api] object TerminalWatch {

    /** Constant-size progress through the only two lines the watcher cares about.
      *
      * A Kafka value lives on an SSE `data:` line and can be megabytes long. Keeping that line until its
      * newline both retained the whole payload and re-scanned the growing prefix for every network chunk.
      * Once a line stops being a possible `event: done` or `event: error`, [[LineState.Discard]] remembers
      * that single fact until the newline; payload bytes themselves are never retained.
      */
    private enum LineState {
      case Prefix(matched: Int)
      case LeadingWhitespace
      case Name(target: String, matched: Int)
      case TrailingWhitespace
      case Discard

      def isTerminal: Boolean = this match {
        case TrailingWhitespace => true
        case _ => false
      }

      /** No state contains bytes from the body; each case is a fixed-size parser position. */
      def retainedPayloadBytes: Int = 0
    }

    private object LineState {
      val Start: LineState = LineState.Prefix(0)
    }

    /** Scans one transport chunk once and returns the next line state plus whether it completed a terminal
      * event line. `0x0a` cannot occur inside a multi-byte UTF-8 sequence, so byte-level line detection is
      * exact even when the transport splits a character across chunks.
      */
    private def scan(initial: LineState, chunk: Chunk[Byte]): (LineState, Boolean) = {
      var current = initial
      var terminal = false
      var index = 0

      while index < chunk.size do {
        val byte = chunk(index)
        if byte == Newline then {
          terminal ||= current.isTerminal
          current = LineState.Start
        } else current = advance(current, byte)
        index += 1
      }

      (current, terminal)
    }

    private def advance(current: LineState, byte: Byte): LineState =
      current match {
        case LineState.Prefix(matched) =>
          if byte != EventPrefix.charAt(matched).toByte then LineState.Discard
          else if matched + 1 == EventPrefix.length then LineState.LeadingWhitespace
          else LineState.Prefix(matched + 1)

        case LineState.LeadingWhitespace =>
          if isTrimWhitespace(byte) then LineState.LeadingWhitespace
          else if byte == SseEventName.Done.charAt(0).toByte then
            LineState.Name(SseEventName.Done, matched = 1)
          else if byte == SseEventName.Error.charAt(0).toByte then
            LineState.Name(SseEventName.Error, matched = 1)
          else LineState.Discard

        case LineState.Name(target, matched) =>
          if byte != target.charAt(matched).toByte then LineState.Discard
          else if matched + 1 == target.length then LineState.TrailingWhitespace
          else LineState.Name(target, matched + 1)

        case LineState.TrailingWhitespace =>
          if isTrimWhitespace(byte) then LineState.TrailingWhitespace else LineState.Discard

        case LineState.Discard => LineState.Discard
      }

    /** `String.trim`, used by the former implementation, removes characters through U+0020. Terminal names
      * and the field prefix are ASCII, so applying the same rule to their UTF-8 bytes preserves detection.
      */
    private def isTrimWhitespace(byte: Byte): Boolean = (byte & 0xff) <= 0x20

    private val EventPrefix: String = "event:"

    def apply[F[_]: Async]: F[TerminalWatch[F]] =
      (Ref.of[F, LineState](LineState.Start), Ref.of[F, Boolean](false)).mapN(new TerminalWatch[F](_, _))
  }
}
