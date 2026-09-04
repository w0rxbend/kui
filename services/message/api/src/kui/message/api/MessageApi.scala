package kui.message.api

import cats.Parallel
import cats.effect.kernel.{Async, Clock, Sync}
import cats.syntax.all.*
import fs2.Stream
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.metrics.Counter
import sttp.capabilities.fs2.Fs2Streams
import sttp.model.StatusCode
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.Interceptor
import sttp.tapir.{extractFromRequest, statusCode, Endpoint, EndpointInput}

import kui.contracts.ErrorEnvelope
import kui.contracts.capability.{CapabilityState, ClusterCapability, ServiceCapabilities}
import kui.http.ErrorInterceptor
import kui.http.health.{HealthEndpoints, ReadinessCheck}
import kui.http.principal.{PrincipalInterceptor, PrincipalVerification, RequestContext}
import kui.kernel.{ClusterId, ServiceId}
import kui.message.application.BrowseUseCase
import kui.observability.{Correlation, KuiInterceptors, Telemetry}
import kui.security.{Principal, PrincipalCodec, RequestDigest, SignedPrincipal}

/** Everything `kui-message-service` serves, and the one place a typed failure becomes an HTTP response.
  *
  * It is the topic service's `api` module with one difference, and the difference is the milestone: this
  * service's primary answer is a *stream*. That changes exactly two things and nothing else.
  *
  *   - A failure before the stream opens is an ordinary HTTP error response, with a status from
  *     `ErrorEnvelope.statusOf` like every other failure in KUI. A failure *after* it has opened cannot be —
  *     the status line has already gone — so it becomes the stream's terminal `error` event carrying the
  *     same envelope (ADR-034, ADR-035). One envelope, two places it can appear.
  *   - The endpoint's capability is `Fs2Streams[F]` rather than `Any`, which is why [[Securing]] has a
  *     `stream` method of its own rather than reusing the request/response one.
  *
  * Everything else — the signed principal, the interceptor order, the fact that this module starts nothing —
  * is the shape every service copies.
  */
object MessageApi {

  /** The process's name, as it appears in `service.name` on every log line, span and metric. */
  val ServiceName: String = "kui-message"

  /** This service's identity in a signed principal's `aud` claim and in a capability document. It has to
    * equal the id the gateway is configured with, or every call from the gateway is refused with a 401.
    */
  val Id: ServiceId = ServiceId.unsafe("message")

  /** The routes, in the order the router tries them. */
  def routes[F[_]: {Async, Parallel}](
      browse: BrowseUseCase[F],
      clusters: List[ClusterId],
      readiness: List[ReadinessCheck[F]],
      principals: PrincipalCodec[F],
      rejections: Counter[F, Long],
      logger: StructuredLogger[F],
      telemetry: Telemetry[F]
  ): List[ServerEndpoint[Fs2Streams[F], F]] =
    HealthEndpoints
      .make[F](readiness, capabilityDocument[F](clusters)) ++ MessageRoutes[F](browse, principals, rejections, logger, telemetry)

  /** The cross-cutting chain, outermost first, in the order the cluster service fixed and every service
    * copies: principal, then instrumentation, then the error envelope innermost.
    */
  def interceptors[F[_]: Async](
      telemetry: Telemetry[F],
      rejections: Counter[F, Long],
      logger: StructuredLogger[F]
  ): F[List[Interceptor[F]]] =
    KuiInterceptors
      .serverInterceptors[F](telemetry, ServiceName)
      .map(instrumentation =>
        PrincipalInterceptor.interceptor[F](logger, rejections) ::
          instrumentation ++ ErrorInterceptor.interceptors[F](logger)
      )

  /** Verification and failure handling, added to every route in one place. */
  final class Securing[F[_]: Async](
      principals: PrincipalCodec[F],
      rejections: Counter[F, Long],
      logger: StructuredLogger[F]
  ) {

    /** Binds a streaming endpoint.
      *
      * The logic returns `Either` because a browse has two kinds of failure and they must not look alike. A
      * request that could never have worked — a live browse anchored to an offset, a partition subset that
      * names none — fails *before* the stream opens, as an ordinary 400 the browser can show beside the
      * field that caused it. Everything that goes wrong once records are flowing becomes the stream's
      * terminal `error` event instead, because by then the status line has already been sent (ADR-035).
      */
    def stream[I](
        endpoint: Endpoint[SignedPrincipal, I, ErrorEnvelope, Stream[F, Byte], Fs2Streams[F]]
    )(
        logic: Principal => RequestContext => I => F[Either[kui.kernel.error.KuiError, Stream[F, Byte]]]
    ): ServerEndpoint[Fs2Streams[F], F] =
      endpoint
        .securityIn(requestContext)
        .errorOut(statusCode)
        .serverSecurityLogic[(Principal, RequestContext), F] { case (token, ctx) =>
          PrincipalVerification
            .secured[F, Failure](principals, Id, logger, rejections)(failure[F])(token, ctx)
        }
        .serverLogic { case (principal, ctx) =>
          input =>
            logic(principal)(ctx)(input).flatMap {
              case Right(stream) => stream.asRight[Failure].pure[F]
              case Left(error) => failure[F](error, ctx).map(_.asLeft[Stream[F, Byte]])
            }
        }
  }

  /** Reads what verification and error reporting need off the request, once.
    *
    * The digest covers the request line only. Every endpoint this service serves today is a `GET` with no
    * body; the first one that carries a body has to hash it, and this is the function that will say so.
    */
  private[api] val requestContext: EndpointInput[RequestContext] =
    extractFromRequest[RequestContext](request =>
      RequestContext(
        RequestDigest.ofRequestLine(request.method.method, request.uri.path.mkString("/", "/", "")),
        request.header(Correlation.HeaderName).flatMap(Correlation.accept)
      )
    )

  private[api] type Failure = (ErrorEnvelope, StatusCode)

  private[api] def failure[F[_]: Sync](error: kui.kernel.error.KuiError, ctx: RequestContext): F[Failure] =
    for {
      correlationId <- ctx.correlationId.fold(Correlation.newRandom[F])(_.pure[F])
      now <- Clock[F].realTimeInstant
    } yield (ErrorEnvelope.of(error, correlationId, now), StatusCode(ErrorEnvelope.statusOf(error)))

  /** What `GET /capabilities` answers.
    *
    * The message service holds no snapshot and no background scrape: it opens a consumer when somebody
    * browses and closes it again afterwards. So there is nothing it can usefully say about a cluster's
    * health *before* being asked to read one, and claiming a cluster is unhealthy on the strength of no
    * evidence would dim a sidebar entry that works. Every configured cluster is therefore reported
    * available, and a cluster that turns out to be unreachable says so on the stream that tried to read it.
    */
  def capabilityDocument[F[_]: Sync](clusters: List[ClusterId]): F[ServiceCapabilities] =
    ServiceCapabilities(
      service = Id,
      clusters = clusters.map(id => id -> available).toMap
    ).pure[F]

  private val available: ClusterCapability =
    ClusterCapability(
      configured = true,
      features = Nil,
      status = CapabilityState.Available.status,
      name = None,
      reason = None
    )
}
