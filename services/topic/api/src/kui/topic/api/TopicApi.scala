package kui.topic.api

import java.time.Instant

import cats.Parallel
import cats.effect.kernel.{Async, Sync}
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.metrics.Counter
import sttp.apispec.openapi.OpenAPI
import sttp.tapir.AnyEndpoint
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.Interceptor

import kui.contracts.capability.ServiceCapabilities
import kui.http.ErrorInterceptor
import kui.http.health.{HealthEndpoints, ReadinessCheck}
import kui.http.principal.{PrincipalInterceptor, RequestContext, SecuredRoutes}
import kui.kernel.ServiceId
import kui.kernel.error.KuiError
import kui.observability.{KuiInterceptors, Telemetry}
import kui.security.PrincipalCodec
import kui.topic.application.{TopicCapabilityUseCase, TopicConfigUseCase, TopicDetailUseCase, TopicSnapshots}
import kui.topic.contract.TopicEndpoints

/** Everything `kui-topic-service` serves, and the one place a typed failure becomes an HTTP response.
  *
  * It follows the cluster service's `api` module, which is the template every service copies, and it does the
  * same five things and no others:
  *
  *   - it binds the endpoints `services/topic/contract` publishes to the use cases in
  *     `services/topic/application`, with no path written out here (ADR-003);
  *   - it verifies the gateway's signed principal before any use case runs (ADR-020);
  *   - it maps `TopicError` to the one error envelope and the one status (ADR-034);
  *   - it maps application types to contract types ([[TopicMapping]], ADR-033);
  *   - it starts nothing. There is no port, no thread and no `IO` in this module: `services/topic/app` is the
  *     only one allowed either (ADR-002, ADR-010).
  *
  * The verification machinery itself is `libs/http`'s, not a copy of the cluster service's. Rule A11 forbids
  * one service seeing another's `api` module, so the alternative to hoisting it was four services each with
  * their own authentication check.
  */
object TopicApi {

  /** The process's name, as it appears in `service.name` on every log line, span and metric. */
  val ServiceName: String = "kui-topic"

  /** This service's identity in a signed principal's `aud` claim and in a capability document.
    *
    * It has to equal the id the gateway is configured with — `kui.gateway.services.topic` — or every call
    * from the gateway is refused with a 401 that names no cause the caller can see. The gateway's own
    * `ServiceContractsSuite` pins the same literal from the other side; the two are the only places it is
    * written, and there is no module that can see both to hold them equal.
    */
  val Id: ServiceId = ServiceId.unsafe("topic")

  // -----------------------------------------------------------------------------------------------
  // Routes
  // -----------------------------------------------------------------------------------------------

  /** The routes, in the order the router tries them.
    *
    * @param snapshots
    *   the per-cluster topic snapshots. Every list request is a memory read of one of these, which is what
    *   keeps a ten-thousand-topic list a function of this process rather than of the cluster's admin latency.
    * @param detail
    *   one topic, read live where it can be and from the snapshot where it cannot.
    * @param config
    *   the Settings tab, read live: a setting changed a moment ago is why the tab was opened.
    * @param capabilities
    *   what this service can currently do per cluster, recomputed per request — the gateway polls it
    *   precisely to learn when the answer changes.
    * @param readiness
    *   what this service checks before saying it can serve.
    * @param principals
    *   the codec that verifies the gateway's signature. Never optional: a service trusts a signed principal
    *   and nothing else, whatever the network says (ADR-020).
    * @param rejections
    *   the `kui.principal.rejected{reason}` counter.
    */
  def routes[F[_]: {Async, Parallel}](
      snapshots: TopicSnapshots[F],
      detail: TopicDetailUseCase[F],
      config: TopicConfigUseCase[F],
      capabilities: TopicCapabilityUseCase[F],
      readiness: List[ReadinessCheck[F]],
      principals: PrincipalCodec[F],
      rejections: Counter[F, Long],
      logger: StructuredLogger[F]
  ): List[ServerEndpoint[Any, F]] =
    // The health endpoints come first because nothing else can match their paths and a probe should travel
    // the shortest route through the router.
    HealthEndpoints.make[F](readiness, capabilityDocument[F](capabilities, logger)) ++
      TopicRoutes[F](snapshots, detail, config, principals, rejections, logger)

  /** The cross-cutting chain, outermost first, exactly as `libs/http`'s server wants it.
    *
    * The order is load bearing and it is the cluster service's:
    *
    *   1. [[PrincipalInterceptor]] first, so a missing principal header becomes the same 401 as a forged one
    *      before the shared decode-failure handler can call it a 400;
    *   1. `KuiInterceptors` next — correlation, tracing, metrics — so the id exists before anything records
    *      it and a failing request is still inside a span and still records its duration;
    *   1. `ErrorInterceptor` last, so it is innermost and sees a failure closest to where it happened.
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

  // -----------------------------------------------------------------------------------------------
  // Binding an endpoint to a use case
  // -----------------------------------------------------------------------------------------------

  /** Everything a route needs in order to be verified and to fail correctly, bundled once.
    *
    * The mechanism is `kui.http.principal.SecuredRoutes`, shared by every service so that there is one answer
    * to "how is a call from the gateway checked" rather than one per `api` module — see that class for why
    * ADR-020 Amendment 1 made sharing it necessary rather than merely tidy. The name stays so that this
    * service's routes read as they always have.
    */
  type Securing[F[_]] = SecuredRoutes[F]

  def Securing[F[_]: Async](
      principals: PrincipalCodec[F],
      rejections: Counter[F, Long],
      logger: StructuredLogger[F]
  ): SecuredRoutes[F] = new SecuredRoutes[F](principals, Id, rejections, logger)

  /** The two halves of an error response: the body the contract fixes and the status the code decides. */
  private[api] type Failure = SecuredRoutes.Failure

  private[api] def failure[F[_]: Sync](error: KuiError, ctx: RequestContext): F[Failure] =
    SecuredRoutes.failure[F](error, ctx)

  // -----------------------------------------------------------------------------------------------
  // Capabilities
  // -----------------------------------------------------------------------------------------------

  /** What `GET /capabilities` answers, including when this service is having a bad day.
    *
    * It must answer even when the service cannot do its job, because the gateway's registry needs the report
    * most exactly when things are wrong: a `/capabilities` that failed would tell the browser nothing at all
    * and the sidebar would have to guess. So a failure inside the use case is logged and becomes a document
    * reporting no working clusters, rather than a 500.
    */
  def capabilityDocument[F[_]: Sync](
      capabilities: TopicCapabilityUseCase[F],
      logger: StructuredLogger[F]
  ): F[ServiceCapabilities] =
    capabilities.report
      .map(TopicCapabilityMapping.toWire)
      .handleErrorWith(error =>
        logger
          .error(error)("the capability report failed; answering that nothing is available")
          .as(ServiceCapabilities(Id, Map.empty))
      )

  // -----------------------------------------------------------------------------------------------
  // OpenAPI
  // -----------------------------------------------------------------------------------------------

  /** Every endpoint this service publishes, health included.
    *
    * The health and capability endpoints are not in `TopicEndpoints.all` — they are identical in every
    * service and come from `libs/http` — but they are absolutely part of what this service serves, and the
    * gateway merges this document rather than the contract file (ADR-003).
    */
  def documented[F[_]]: List[AnyEndpoint] =
    TopicEndpoints.all ++
      List(HealthEndpoints.live, HealthEndpoints.ready, HealthEndpoints.capabilities)

  def openApi[F[_]]: OpenAPI = OpenAPIDocsInterpreter().toOpenAPI(documented[F], Title, Version)

  val Title: String = "KUI Topic service"

  /** The document's version, which is the *contract's* version and not the build's. A build number here would
    * make every commit a change to the committed document and every diff noise.
    */
  val Version: String = "1.0.0"

  /** The instant a document is stamped with, when one has to be. Fixed, for the same reason as the version: a
    * generated file that changes every time it is generated cannot be committed and checked.
    */
  val GeneratedAt: Instant = Instant.EPOCH
}
