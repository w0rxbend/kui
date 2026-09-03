package kui.http.sse

import java.nio.charset.StandardCharsets

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import cats.effect.kernel.{Clock, Resource, Sync, Temporal}
import cats.effect.std.Queue
import cats.syntax.all.*
import fs2.{Pull, Stream}
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.{Counter, UpDownCounter}
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.{streamTextBody, CodecFormat, StreamBodyIO}

import kui.contracts.ErrorEnvelope
import kui.kernel.CorrelationId
import kui.kernel.error.ErrorCode
import kui.observability.{MetricNames, Telemetry}

/** How a stream behaves, everywhere.
  *
  * @param heartbeatInterval
  *   how long the stream may be silent before it says something. Fifteen seconds because that is comfortably
  *   inside the idle timeout of every proxy KUI is likely to sit behind, and inside the browser's own
  *   patience.
  * @param bufferSize
  *   how many events may be waiting for a slow reader before the oldest start being dropped
  * @param rateLimit
  *   events per second, when the stream would otherwise produce faster than a person can read. Unused in M0;
  *   message tailing sets it in M3.
  */
final case class SseConfig(
    heartbeatInterval: FiniteDuration = 15.seconds,
    bufferSize: Int = 256,
    rateLimit: Option[Int] = None
)

object SseConfig {
  val default: SseConfig = SseConfig()

  given CanEqual[SseConfig, SseConfig] = CanEqual.derived
}

/** Turns a stream of domain events into one that behaves the way every KUI stream must.
  *
  * Four promises, and each is a failure that has happened to somebody before:
  *
  *   - **it says why it ended.** A stream that just stops leaves the browser unable to tell "there is no more
  *     data" from "the connection broke", so it either shows nothing or reconnects forever. Exactly one
  *     `done` or `error` event ends every stream (ADR-035).
  *   - **it survives an idle proxy.** A stream with nothing to say for a minute looks dead to the reverse
  *     proxy in front of it, which closes it. A heartbeat every fifteen idle seconds keeps it open, and none
  *     is sent while events are flowing.
  *   - **it stops consuming resources the moment the browser goes away.** The cancellation chain is browser
  *     abort → gateway stream cancelled → service fiber cancelled → consumer closed. The spike measured the
  *     first link at 8 ms on this server (`docs/spikes/M0-netty-sse.md`), which is why no idle-timeout guard
  *     is needed here.
  *   - **a slow reader cannot exhaust the server.** The buffer is bounded and overflow drops the oldest event
  *     with a counter, never buffers without limit (PLAN §28).
  */
object Sse {

  /** Everything above, applied to `source`.
    *
    * The order of the layers matters, and one part of it is subtle. Rate limiting and the terminal rule apply
    * to the source, before buffering, because the queue hands events on one at a time and the terminal rule
    * reads whole chunks — after the queue it could never see that a caller emitted two terminal events
    * together. Buffering then absorbs a slow reader, and heartbeats come last so that an idle stream still
    * produces them and the terminal event is never held up behind one.
    *
    * Dropping the oldest on overflow is what makes this safe in that order: the terminal event is the newest
    * thing in the queue, so it is never the one thrown away.
    *
    * @param streamName
    *   the stream's name in `kui.stream.active` and `kui.stream.events`, e.g. `capabilities`
    */
  def stream[F[_]: Temporal](
      source: Stream[F, SseEvent],
      config: SseConfig,
      streamName: String,
      telemetry: Telemetry[F],
      logger: StructuredLogger[F]
  ): Stream[F, SseEvent] =
    Stream.eval(Instruments.make[F](telemetry, streamName)).flatMap { instruments =>
      Stream
        .resource(instruments.active)
        .flatMap(_ =>
          buffered(
            rateLimited(source, config).through(atMostOneTerminal(streamName, logger)),
            config,
            instruments
          )
            .through(heartbeats(config.heartbeatInterval))
            .evalTap(instruments.emitted)
        )
    }

  /** Turns a failed effect into a terminal `error` event instead of a broken connection.
    *
    * Without it, a failure halfway through arrives at the browser as a truncated response — the connection
    * simply closes — and `EventSource` reconnects, re-runs the query and fails again. With it the browser is
    * told what went wrong and whether retrying is worth anything.
    *
    * The message is the fixed `Internal error` and the cause goes nowhere near the wire, for the same reason
    * it does not in an ordinary error response (ADR-034): a stack trace tells a reader the framework
    * versions, the package layout and often the file paths.
    */
  def withErrorEvent[F[_]: Sync](
      source: Stream[F, SseEvent],
      correlationId: CorrelationId
  ): Stream[F, SseEvent] =
    source.handleErrorWith { _ =>
      Stream.eval(Clock[F].realTimeInstant).map { now =>
        SseEvent.error(
          ErrorEnvelope(
            code = ErrorCode.Internal.wire,
            message = "Internal error",
            details = Nil,
            correlationId = correlationId.value,
            timestamp = now,
            retryable = ErrorCode.Internal.retryable
          )
        )
      }
    }

  /** The frames, as bytes, ready for a Tapir stream body. */
  def encode[F[_]](events: Stream[F, SseEvent]): Stream[F, Byte] =
    events.map(SseEvent.render).through(fs2.text.utf8.encode)

  /** The Tapir output an SSE endpoint declares.
    *
    * Tapir gives the generic fs2 text-event-stream body and leaves the framing to the caller
    * (`docs/spikes/M0-netty-sse.md`), which is what [[encode]] does. The charset is fixed to UTF-8 because
    * the SSE specification fixes it.
    */
  def body[F[_]]: StreamBodyIO[Stream[F, Byte], Stream[F, Byte], Fs2Streams[F]] = {
    val streams: Fs2Streams[F] = Fs2Streams[F]
    streamTextBody(streams)(CodecFormat.TextEventStream(), Some(StandardCharsets.UTF_8))
  }

  // ---------------------------------------------------------------------------------------------

  /** Emits a heartbeat after `every` of silence, and none while events are flowing.
    *
    * `pull.timed` is the primitive that makes "idle" mean what it should: the timeout is re-armed on every
    * chunk, so a busy stream never reaches it. A heartbeat every fifteen seconds regardless would be harmless
    * but wrong — it would tell a reader the connection is idle when it is not, and it would triple the frame
    * count on a busy stream for no reason.
    */
  def heartbeats[F[_]: Temporal](every: FiniteDuration)(source: Stream[F, SseEvent]): Stream[F, SseEvent] =
    source.pull.timed { timed =>
      def go(current: fs2.Pull.Timed[F, SseEvent]): Pull[F, SseEvent, Unit] =
        current.timeout(every) >> current.uncons.flatMap {
          case None => Pull.done
          case Some((Right(chunk), next)) => Pull.output(chunk) >> go(next)
          case Some((Left(_), next)) => Pull.output1(SseEvent.heartbeat) >> go(next)
        }

      go(timed)
    }.stream

  /** Emits everything up to and including the first terminal event, and nothing after it.
    *
    * A source that emits two is a bug in the caller, so the extra is dropped and logged rather than
    * forwarded: a client that received `done` and then more data would have no rule to follow.
    */
  def atMostOneTerminal[F[_]: Temporal](streamName: String, logger: StructuredLogger[F])(
      source: Stream[F, SseEvent]
  ): Stream[F, SseEvent] = {
    def go(remaining: Stream[F, SseEvent]): Pull[F, SseEvent, Unit] =
      remaining.pull.uncons.flatMap {
        case None => Pull.done

        case Some((chunk, rest)) =>
          chunk.toList.indexWhere(_.isTerminal) match {
            case -1 => Pull.output(chunk) >> go(rest)

            case at =>
              // Emit up to and including the terminal event, then stop pulling entirely: the
              // source is cancelled rather than drained, so a caller whose stream keeps producing
              // after it said `done` cannot spin here forever.
              val discarded = chunk.size - (at + 1)

              Pull.output(chunk.take(at + 1)) >> Pull
                .eval(
                  Temporal[F].whenA(discarded > 0)(
                    logger.warn(
                      Map(
                        MetricNames.Attr.Stream -> streamName,
                        "dropped" -> discarded.toString
                      )
                    )(
                      s"the $streamName stream produced $discarded event(s) after its terminal " +
                        "event; they were dropped, because ADR-035 promises exactly one"
                    )
                  )
                )
                .void
          }
      }

    go(source).stream
  }

  private def rateLimited[F[_]: Temporal](
      source: Stream[F, SseEvent],
      config: SseConfig
  ): Stream[F, SseEvent] =
    config.rateLimit.filter(_ > 0) match {
      case None => source
      case Some(perSecond) => source.metered(1.second / perSecond.toLong)
    }

  /** A bounded hand-off between the producer and the writer, dropping the oldest on overflow.
    *
    * Dropping rather than blocking is PLAN §28's rule, and the reason is what unbounded buffering does
    * instead: a browser tab that stopped reading holds a growing buffer on the server for as long as the
    * connection is open, and a hundred such tabs is an out-of-memory error. Dropping the *oldest* is right
    * for a live view — the newest state is the one worth showing — and the drop is counted rather than
    * silent, because a stream that is dropping events is telling you something about its consumer.
    */
  private def buffered[F[_]: Temporal](
      source: Stream[F, SseEvent],
      config: SseConfig,
      instruments: Instruments[F]
  ): Stream[F, SseEvent] =
    Stream.eval(Queue.bounded[F, Option[SseEvent]](math.max(1, config.bufferSize))).flatMap { queue =>
      val producer = source
        .evalMap { event =>
          queue.tryOffer(Some(event)).flatMap {
            case true => Temporal[F].unit
            case false => queue.tryTake *> instruments.dropped *> queue.offer(Some(event))
          }
        }
        .onFinalize(queue.offer(None))

      Stream.fromQueueNoneTerminated(queue).concurrently(producer)
    }

  /** The two stream metrics of `ARCHITECTURE.md` §13, wired once so every stream in every milestone is
    * measured without extra code.
    */
  final private class Instruments[F[_]: Temporal](
      streamName: String,
      openStreams: UpDownCounter[F, Long],
      events: Counter[F, Long]
  ) {

    private val stream = Attribute(MetricNames.Attr.Stream, streamName)

    /** Held for as long as the stream is open.
      *
      * The decrement is a resource finaliser, so a leaked stream shows up as a gauge that never returns to
      * zero — which is exactly what makes it a leak detector rather than a statistic.
      */
    val active: Resource[F, Unit] =
      Resource.make(openStreams.inc(stream))(_ => openStreams.dec(stream))

    def emitted(event: SseEvent): F[Unit] =
      events.inc(stream, Attribute(MetricNames.Attr.Event, event.name))

    val dropped: F[Unit] =
      events.inc(stream, Attribute(MetricNames.Attr.Event, DroppedEvent))
  }

  private object Instruments {
    def make[F[_]: Temporal](telemetry: Telemetry[F], streamName: String): F[Instruments[F]] =
      for {
        meter <- telemetry.meter("kui.stream")
        open <- meter
          .upDownCounter[Long](MetricNames.StreamActive)
          .withDescription("Streams currently open. A gauge that never returns to zero is a leak.")
          .create
        events <- meter
          .counter[Long](MetricNames.StreamEvents)
          .withDescription("Events pushed down an open stream, by event name")
          .create
      } yield new Instruments[F](streamName, open, events)
  }

  /** The `event` label an overflow is counted under.
    *
    * A dropped event is reported through `kui.stream.events` rather than a metric of its own, so that "how
    * many events did this stream produce" and "how many did it have to throw away" are two values of one
    * series and can be graphed against each other.
    */
  val DroppedEvent: String = "dropped"
}
