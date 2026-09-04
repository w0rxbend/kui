package kui.gateway.api

import java.nio.charset.StandardCharsets

import cats.effect.kernel.{Async, Ref}
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
            .onFinalize(queue.offer(None))

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
    * `event: error`. A chunk can end in the middle of a line, so the tail of each chunk is carried into the
    * next one. Only complete lines are examined, which is why a chunk boundary cannot hide a terminal event
    * and cannot invent one either.
    */
  final private class TerminalWatch[F[_]: Async](carry: Ref[F, Vector[Byte]], seen: Ref[F, Boolean]) {

    def observe(chunk: Chunk[Byte]): F[Unit] =
      carry
        .modify { partial =>
          // Split on the newline byte, not on a decoded string. `0x0A` cannot occur inside a multi-byte
          // UTF-8 sequence, so this is exact even when a chunk boundary falls in the middle of a character
          // — and only complete lines are ever decoded.
          val buffer = partial ++ chunk.toVector
          val pieces = split(buffer)
          (pieces.last, pieces.init)
        }
        .flatMap(complete => seen.update(_ || complete.exists(isTerminalLine)))

    def sawTerminal: F[Boolean] = seen.get

    /** Every line in the buffer, with the trailing element being the incomplete one (possibly empty). */
    private def split(buffer: Vector[Byte]): Vector[Vector[Byte]] = {
      val (lines, rest) = buffer.foldLeft((Vector.empty[Vector[Byte]], Vector.empty[Byte])) {
        case ((done, current), Newline) => (done :+ current, Vector.empty)
        case ((done, current), byte) => (done, current :+ byte)
      }
      lines :+ rest
    }

    private def isTerminalLine(line: Vector[Byte]): Boolean = {
      val text = new String(line.toArray, StandardCharsets.UTF_8).stripSuffix("\r")
      text.startsWith("event:") && {
        val name = text.drop("event:".length).trim
        name == SseEventName.Done || name == SseEventName.Error
      }
    }
  }

  private val Newline: Byte = '\n'.toByte

  private object TerminalWatch {

    def apply[F[_]: Async]: F[TerminalWatch[F]] =
      (Ref.of[F, Vector[Byte]](Vector.empty), Ref.of[F, Boolean](false)).mapN(new TerminalWatch[F](_, _))
  }
}
