package kui.ksql.api

import cats.Parallel
import cats.effect.kernel.{Async, Clock, Sync}
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.metrics.Counter
import sttp.apispec.openapi.OpenAPI
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.AnyEndpoint
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.Interceptor

import kui.contracts.ErrorEnvelope
import kui.contracts.capability.ServiceCapabilities
import kui.http.ErrorInterceptor
import kui.http.health.{HealthEndpoints, ReadinessCheck}
import kui.http.principal.{PrincipalInterceptor, RbacGuard, SecuredRoutes}
import kui.http.sse.{SseConfig, SseEvent}
import kui.kernel.error.KuiError
import kui.kernel.{CorrelationId, ServiceId}
import kui.ksql.application.{KsqlService, KsqlUseCases}
import kui.ksql.contract.{KsqlEndpoints, KsqlStreamEndpoint}
import kui.observability.{KuiInterceptors, Telemetry}
import kui.security.PrincipalCodec

/** Everything `kui-ksql-service` serves, and the one place a typed failure becomes an HTTP response.
  *
  * It is the alerts service's `api` module with three JSON routes in place of two, deliberately: the five
  * things an `api` module does are the same in every service, and a second shape for them would be a second
  * place for authentication, error mapping and the OpenAPI document to drift.
  *
  *   - it binds the endpoints `services/ksql/contract` publishes to the use cases in
  *     `services/ksql/application`, with no path written out here (ADR-003);
  *   - it verifies the gateway's signed principal before any use case runs (ADR-020), and checks the
  *     permission the endpoint declares before the use case is even called (ADR-021);
  *   - it maps a `KuiError` to the one error envelope and the one status (ADR-034);
  *   - it maps application types to contract types ([[KsqlMapping]], ADR-033);
  *   - it starts nothing. `services/ksql/app` is the only module in this service allowed to.
  */
object KsqlApi {

  /** The process's name, as it appears in `service.name` on every log line, span and metric. */
  val ServiceName: String = "kui-ksql"

  /** This service's identity in a signed principal's `aud` claim and in a capability document. It is
    * `KsqlService.Id`, declared in the application layer because the audit line carries it too.
    */
  val Id: ServiceId = KsqlService.Id

  /** The push query's name in `kui.stream.active` and `kui.stream.events`.
    *
    * One name for the metric, so that "how many push queries are open" is one series an operator can graph
    * and a gauge that never returns to zero is a leak they can see.
    */
  val StreamName: String = "ksql"

  // -----------------------------------------------------------------------------------------------
  // Routes
  // -----------------------------------------------------------------------------------------------

  /** Everything this service serves, in the order the router tries them.
    *
    * Health first, because nothing else can match its paths and a probe should travel the shortest route
    * through the router. One list rather than two, for the cluster service's reason: a composition root that
    * had to remember to serve a second list is a composition root that one day does not, and the endpoint
    * that goes missing is the one nothing else calls — which is exactly how `services/alerts` shipped a
    * stream nobody could reach.
    *
    * `ServerEndpoint[Fs2Streams[F], F]` throughout: `ServerEndpoint` is contravariant in that parameter, so
    * the JSON routes fit into the list the push query needs.
    */
  def routes[F[_]: {Async, Parallel}](
      ksql: KsqlUseCases[F],
      readiness: List[ReadinessCheck[F]],
      capabilities: KsqlCapabilities[F],
      principals: PrincipalCodec[F],
      rejections: Counter[F, Long],
      logger: StructuredLogger[F],
      guard: RbacGuard[F],
      telemetry: Telemetry[F],
      sse: SseConfig = SseConfig.default
  ): List[ServerEndpoint[Fs2Streams[F], F]] = {
    val secured = Securing[F](principals, rejections, logger, guard)

    HealthEndpoints.make[F](readiness, capabilityDocument[F](capabilities, logger)) ++
      KsqlRoutes[F](ksql, secured) ++
      KsqlRoutes.stream[F](ksql, secured, telemetry, logger, sse)
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

  /** One failure, as ADR-035's terminal `error` frame.
    *
    * The same envelope an ordinary HTTP failure would carry (ADR-034), so a browser renders a mid-stream
    * failure with the code it already knows rather than needing a second error shape for the one case where
    * the status line has already gone.
    */
  def errorEvent[F[_]: Sync](error: KuiError, correlationId: CorrelationId): F[SseEvent] =
    Clock[F].realTimeInstant.map(now => SseEvent.error(ErrorEnvelope.of(error, correlationId, now)))

  // -----------------------------------------------------------------------------------------------
  // Capabilities
  // -----------------------------------------------------------------------------------------------

  /** What `GET /capabilities` answers, including when this service is having a bad day.
    *
    * A failure inside the report is logged and becomes a document reporting no clusters, rather than a 500:
    * the gateway's registry needs an answer most exactly when things are wrong.
    */
  def capabilityDocument[F[_]: Sync](
      capabilities: KsqlCapabilities[F],
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

  /** Every endpoint this service publishes: the three from the cross-compiled contract, the push query that
    * cannot be cross-compiled, and the three health probes.
    *
    * The stream is here explicitly because `KsqlEndpoints.all` cannot hold it — a stream body needs `fs2`,
    * which the browser's half of the contract does not link — and an endpoint left out of this list is an
    * endpoint the merged document does not describe. `KsqlContractSuite`'s "everything served is published"
    * is what makes that assertable rather than descriptive.
    */
  def documented[F[_]]: List[AnyEndpoint] =
    KsqlEndpoints.all ++
      KsqlStreamEndpoint.endpoints[F] ++
      List(HealthEndpoints.live, HealthEndpoints.ready, HealthEndpoints.capabilities)

  def openApi[F[_]]: OpenAPI = OpenAPIDocsInterpreter().toOpenAPI(documented[F], Title, Version)

  val Title: String = "KUI ksqlDB service"

  /** The document's version, which is the *contract's* version and not the build's. */
  val Version: String = "1.0.0"
}
