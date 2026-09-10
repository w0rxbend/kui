package kui.alerts.api

import cats.Parallel
import cats.effect.kernel.{Async, Sync}
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.metrics.Counter
import sttp.apispec.openapi.OpenAPI
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.AnyEndpoint
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.Interceptor

import kui.alerts.application.{AlertStore, AlertUseCases, AlertsService}
import kui.alerts.contract.{AlertsEndpoints, AlertsStreamEndpoint}
import kui.contracts.capability.ServiceCapabilities
import kui.http.ErrorInterceptor
import kui.http.health.{HealthEndpoints, ReadinessCheck}
import kui.http.principal.{PrincipalInterceptor, RbacGuard, SecuredRoutes}
import kui.http.sse.SseConfig
import kui.kernel.ServiceId
import kui.observability.{KuiInterceptors, Telemetry}
import kui.security.PrincipalCodec

/** Everything `kui-alerts-service` serves, and the one place a typed failure becomes an HTTP response.
  *
  * It is the metrics service's `api` module with one write and one stream added, deliberately: the five
  * things an `api` module does are the same in every service, and a second shape for them would be a second
  * place for authentication, error mapping and the OpenAPI document to drift.
  *
  *   - it binds the endpoints `services/alerts/contract` publishes to the use cases in
  *     `services/alerts/application`, with no path written out here (ADR-003);
  *   - it verifies the gateway's signed principal before any use case runs (ADR-020), and checks the
  *     permission the endpoint declares before the use case is even called (ADR-021);
  *   - it maps a `KuiError` to the one error envelope and the one status (ADR-034);
  *   - it maps application types to contract types ([[AlertsMapping]], ADR-033);
  *   - it starts nothing. `services/alerts/app` is the only module in this service allowed to.
  */
object AlertsApi {

  /** The process's name, as it appears in `service.name` on every log line, span and metric. */
  val ServiceName: String = "kui-alerts"

  /** This service's identity in a signed principal's `aud` claim and in a capability document. It is
    * `AlertsService.Id`, declared in the application layer because the audit line carries it too.
    */
  val Id: ServiceId = AlertsService.Id

  // -----------------------------------------------------------------------------------------------
  // Routes
  // -----------------------------------------------------------------------------------------------

  /** Everything this service serves, in the order the router tries them.
    *
    * Health first, because nothing else can match its paths and a probe should travel the shortest route
    * through the router. They are `ServerEndpoint[Any, F]` — "needs no capability from the server" — and
    * `ServerEndpoint` is contravariant in that parameter, so they fit into a list typed on `Fs2Streams` that
    * the change stream needs. One list rather than two, for the cluster service's reason: a composition root
    * that had to remember to serve a second list is a composition root that one day does not, and the
    * endpoint that goes missing is the one nothing else calls.
    */
  def routes[F[_]: {Async, Parallel}](
      alerts: AlertUseCases[F],
      store: AlertStore[F],
      readiness: List[ReadinessCheck[F]],
      capabilities: AlertsCapabilities[F],
      principals: PrincipalCodec[F],
      rejections: Counter[F, Long],
      telemetry: Telemetry[F],
      logger: StructuredLogger[F],
      guard: RbacGuard[F],
      sse: SseConfig = SseConfig.default
  ): List[ServerEndpoint[Fs2Streams[F], F]] = {
    val secured = Securing[F](principals, rejections, logger, guard)

    HealthEndpoints.make[F](readiness, capabilityDocument[F](capabilities, logger)) ++
      AlertsRoutes[F](alerts, secured) ++
      AlertsRoutes.stream[F](store, secured, telemetry, logger, sse)
  }

  /** The cross-cutting chain, outermost first, in the order `libs/http`'s server wants it: principal, then
    * instrumentation, then error translation innermost.
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

  /** Everything a route needs in order to be verified, authorized and to fail correctly, bundled once. The
    * mechanism is `kui.http.principal.SecuredRoutes`, shared by every service (ADR-020 Amendment 1).
    */
  type Securing[F[_]] = SecuredRoutes[F]

  def Securing[F[_]: Async](
      principals: PrincipalCodec[F],
      rejections: Counter[F, Long],
      logger: StructuredLogger[F],
      guard: RbacGuard[F]
  ): SecuredRoutes[F] = new SecuredRoutes[F](principals, Id, rejections, logger, guard)

  // -----------------------------------------------------------------------------------------------
  // Capabilities
  // -----------------------------------------------------------------------------------------------

  /** What `GET /capabilities` answers, including when this service is having a bad day.
    *
    * A failure inside the report is logged and becomes a document reporting no clusters, rather than a 500:
    * the gateway's registry needs an answer most exactly when things are wrong.
    */
  def capabilityDocument[F[_]: Sync](
      capabilities: AlertsCapabilities[F],
      logger: StructuredLogger[F]
  ): F[ServiceCapabilities] =
    capabilities.report
      .map(clusters => ServiceCapabilities(Id, clusters))
      .handleErrorWith(error =>
        logger
          .error(error)("the capability report failed; answering that nothing is available")
          .as(ServiceCapabilities(Id, Map.empty))
      )

  // -----------------------------------------------------------------------------------------------
  // OpenAPI
  // -----------------------------------------------------------------------------------------------

  /** Every endpoint this service publishes: the two from the cross-compiled contract, the stream that cannot
    * live there, and the three health probes.
    */
  def documented[F[_]]: List[AnyEndpoint] =
    AlertsEndpoints.all ++
      AlertsStreamEndpoint.endpoints[F] ++
      List(HealthEndpoints.live, HealthEndpoints.ready, HealthEndpoints.capabilities)

  def openApi[F[_]]: OpenAPI = OpenAPIDocsInterpreter().toOpenAPI(documented[F], Title, Version)

  val Title: String = "KUI Alerts service"

  /** The document's version, which is the *contract's* version and not the build's. */
  val Version: String = "1.0.0"
}
