package kui.message.api

import cats.Parallel
import cats.effect.kernel.{Async, Sync}
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.metrics.Counter
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.Interceptor

import kui.contracts.capability.{CapabilityState, ClusterCapability, ServiceCapabilities}
import kui.http.ErrorInterceptor
import kui.http.health.{HealthEndpoints, ReadinessCheck}
import kui.http.principal.{PrincipalInterceptor, RbacGuard, RequestContext, SecuredRoutes}
import kui.kernel.{ClusterId, ServiceId}
import kui.message.application.produce.{ProduceUseCase, ResendUseCase}
import kui.message.application.purge.PurgeUseCase
import kui.message.application.{BrowseUseCase, FilterUseCase, TrackUseCase}
import kui.observability.{KuiInterceptors, Telemetry}
import kui.security.PrincipalCodec

/** Everything `kui-message-service` serves, and the one place a typed failure becomes an HTTP response.
  *
  * It is the topic service's `api` module with one difference, and the difference is the milestone: this
  * service's primary answer is a *stream*. That changes exactly two things and nothing else.
  *
  *   - A failure before the stream opens is an ordinary HTTP error response, with a status from
  *     `ErrorEnvelope.statusOf` like every other failure in KUI. A failure *after* it has opened cannot be —
  *     the status line has already gone — so it becomes the stream's terminal `error` event carrying the same
  *     envelope (ADR-034, ADR-035). One envelope, two places it can appear.
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
      filters: FilterUseCase[F],
      track: TrackUseCase[F],
      produce: ProduceUseCase[F],
      resend: ResendUseCase[F],
      purge: PurgeUseCase[F],
      clusters: List[ClusterId],
      readiness: List[ReadinessCheck[F]],
      principals: PrincipalCodec[F],
      rejections: Counter[F, Long],
      logger: StructuredLogger[F],
      telemetry: Telemetry[F],
      guard: RbacGuard[F]
  ): List[ServerEndpoint[Fs2Streams[F], F]] = {
    val secured = Securing[F](principals, rejections, logger, guard)

    HealthEndpoints.make[F](readiness, capabilityDocument[F](clusters)) ++
      MessageRoutes[F](browse, principals, rejections, logger, telemetry, guard) ++
      // The writes, and the purge plan that precedes the one destructive one. They are
      // `ServerEndpoint[Any, F]` — no stream capability — and widen into this list because Tapir's
      // capability parameter is contravariant: a route that needs nothing of the interpreter runs on
      // one that offers streaming.
      MessageMutationRoutes[F](produce, resend, purge, secured) ++
      // Registering and testing a filter. They are neither browse nor mutation: they change nothing on
      // the cluster and open no Kafka client, which is why they are a third list rather than an addition
      // to either of the other two.
      FilterRoutes[F](filters, secured) ++
      // Following one business event across several topics. It reads Kafka, like a browse, and answers all
      // at once, like a mutation — which is why it is neither of the two lists above.
      TrackRoutes[F](track, secured)
  }

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

  /** Verification and failure handling, added to every route in one place.
    *
    * The mechanism is `kui.http.principal.SecuredRoutes`, shared by every service, and this service is the
    * reason it is shared. Two of its routes carry a request body — publishing a record and resending a range
    * — and ADR-020 Amendment 1 says how a token is bound to those: by hashing the body the gateway signed,
    * reconstructed from the decoded input through the same contract codec. The consumer service met that wall
    * first and answered it locally; a second local answer here would have been the second half of exactly the
    * drift this project keeps paying for, so the answer moved into `libs/http` and both services call it.
    *
    * `stream` on that class is what this service's browse endpoint uses: a streaming endpoint carries no
    * body, so its digest is the request line, and its logic is handed the request context because a failure
    * after the status line has gone is rendered into the stream rather than into a response (ADR-035).
    */
  type Securing[F[_]] = SecuredRoutes[F]

  def Securing[F[_]: Async](
      principals: PrincipalCodec[F],
      rejections: Counter[F, Long],
      logger: StructuredLogger[F],
      guard: RbacGuard[F]
  ): SecuredRoutes[F] = new SecuredRoutes[F](principals, Id, rejections, logger, guard)

  private[api] type Failure = SecuredRoutes.Failure

  private[api] def failure[F[_]: Sync](error: kui.kernel.error.KuiError, ctx: RequestContext): F[Failure] =
    SecuredRoutes.failure[F](error, ctx)

  /** What `GET /capabilities` answers.
    *
    * The message service holds no snapshot and no background scrape: it opens a consumer when somebody
    * browses and closes it again afterwards. So there is nothing it can usefully say about a cluster's health
    * *before* being asked to read one, and claiming a cluster is unhealthy on the strength of no evidence
    * would dim a sidebar entry that works. Every configured cluster is therefore reported available, and a
    * cluster that turns out to be unreachable says so on the stream that tried to read it.
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
