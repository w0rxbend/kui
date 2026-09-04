package kui.message.api

import cats.effect.kernel.{Async, Clock}
import cats.syntax.all.*
import fs2.Stream
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.metrics.Counter
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.server.ServerEndpoint

import kui.http.sse.{Sse, SseConfig, SseEvent}
import kui.kernel.browse.PollBudget
import kui.kernel.error.KuiError
import kui.message.application.{BrowseEvent, BrowseUseCase}
import kui.message.contract.{BrowseStreamParams, MessageEndpoints}
import kui.message.domain.{BrowseLimits, BrowseRequest}
import kui.observability.{Correlation, Telemetry}
import kui.security.PrincipalCodec

/** The browse endpoint, bound to the browse use case.
  *
  * ==Where a failure goes==
  *
  * Two places, and which one is not a detail. A request the domain refuses — a live browse anchored to an
  * offset, an empty partition subset — never opens a stream at all: it is a 400 with the field named, which
  * is what lets the browser underline the control the user got wrong. Everything after that is inside a
  * stream whose status line has already been sent, so it becomes the terminal `error` event carrying the
  * same envelope (ADR-035).
  *
  * ==Cancellation==
  *
  * Nothing here has to arrange it, and that is the point worth writing down. The stream this route returns
  * is the use case's, over the record source's, over a `Resource`-held Kafka consumer. When the browser goes
  * away Tapir cancels the response stream, fs2 runs the finalisers, and the consumer is closed. Adding an
  * idle timeout or a manual abort here would be a second mechanism doing the same job less reliably.
  */
object MessageRoutes {

  /** How much one browse may consume before it stops of its own accord.
    *
    * Deliberately not `PollBudget.Conservative`: this is the product's answer rather than a test's, and it
    * is generous in bytes and time because a filtered scan over a real topic legitimately reads a great deal
    * to match a little. The record ceiling is `BrowseLimits.Default.max`, so the budget can never be the
    * thing that stops a browse the caller's own `limit` would have ended.
    */
  val DefaultBudget: PollBudget =
    PollBudget.unsafe(
      maxRecords = BrowseLimits.Default.max * 100,
      maxBytes = 64L * 1024L * 1024L,
      deadline = scala.concurrent.duration.FiniteDuration(60, "seconds")
    )

  /** The stream's name in `kui.stream.active` and `kui.stream.events`. */
  val StreamName: String = "message.browse"

  def apply[F[_]: Async](
      browse: BrowseUseCase[F],
      principals: PrincipalCodec[F],
      rejections: Counter[F, Long],
      logger: StructuredLogger[F],
      telemetry: Telemetry[F],
      limits: BrowseLimits = BrowseLimits.Default,
      budget: PollBudget = DefaultBudget,
      sse: SseConfig = SseConfig.default
  ): List[ServerEndpoint[Fs2Streams[F], F]] = {
    val secured = MessageApi.Securing[F](principals, rejections, logger)

    List(
      secured.stream(MessageEndpoints.browseStream[F]) { _ => ctx => params =>
        requestOf(params, limits) match {
          case Left(error) => error.asLeft[Stream[F, Byte]].pure[F]
          case Right(request) =>
            ctx.correlationId
              .fold(Correlation.newRandom[F])(_.pure[F])
              .map { correlationId =>
                Sse
                  .encode(
                    Sse.stream(
                      Sse.withErrorEvent(events(browse, request, budget, correlationId), correlationId),
                      sse,
                      StreamName,
                      telemetry,
                      logger
                    )
                  )
                  .asRight[KuiError]
              }
        }
      }
    )
  }

  /** The use case's events as frames, with the one event that needs a clock given one. */
  private def events[F[_]: Async](
      browse: BrowseUseCase[F],
      request: BrowseRequest,
      budget: PollBudget,
      correlationId: kui.kernel.CorrelationId
  ): Stream[F, SseEvent] =
    browse
      .browse(request, budget)
      .evalMap {
        case BrowseEvent.Failed(error) =>
          Clock[F].realTimeInstant.map(now => Some(MessageMapping.failed(error, correlationId, now)))
        case other => MessageMapping.event(other).pure[F]
      }
      .unNone

  /** The query parameters, validated by the domain.
    *
    * The validation is `BrowseRequest.of` and not a check written here, because the page endpoint will need
    * the same rules and two validations of one rule is how they stop agreeing.
    */
  private def requestOf(
      params: BrowseStreamParams,
      limits: BrowseLimits
  ): Either[KuiError, BrowseRequest] =
    BrowseRequest.of(
      cluster = params.cluster,
      topic = params.topic,
      // No seek given means "the newest records", which is what a person opening a topic wants to see
      // first. It is decided here rather than in the codec so that the choice is visible to a reader of
      // the endpoint instead of buried in a parser.
      seek = params.seek.getOrElse(kui.kernel.browse.SeekMode.Latest),
      direction = params.direction,
      partitions = params.partitions.map(_.toSortedSet.toSet),
      limit = params.limit,
      isolation = params.isolation,
      keySerde = params.keySerde,
      valueSerde = params.valueSerde,
      stringFilter = params.stringFilter,
      filter = None,
      live = params.live.getOrElse(false),
      limits = limits
    )
}
