package kui.cluster.api

import java.time.Instant

import cats.Parallel
import cats.effect.kernel.{Async, Clock, Sync}
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.metrics.Counter
import sttp.apispec.openapi.OpenAPI
import sttp.capabilities.fs2.Fs2Streams
import sttp.model.StatusCode
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.Interceptor
import sttp.tapir.{extractFromRequest, statusCode, AnyEndpoint, Endpoint, EndpointInput}

import kui.cluster.application.{
  BrokerDetailUseCase,
  CapabilityReportUseCase,
  ClusterRegistry,
  ClusterService,
  ClusterTopologyUseCase,
  ClusterWriteUseCase
}
import kui.cluster.contract.{ClusterEndpoints, ClusterWriteEndpoints, ProfileEndpoints}
import kui.contracts.ErrorEnvelope
import kui.contracts.capability.ServiceCapabilities
import kui.http.ErrorInterceptor
import kui.http.health.{HealthEndpoints, ReadinessCheck}
import kui.kernel.error.KuiError
import kui.observability.{Correlation, KuiInterceptors, Telemetry}
import kui.security.{Principal, PrincipalCodec, RequestDigest, SignedPrincipal}

/** Everything `kui-cluster-service` serves, and the one place a typed failure becomes an HTTP response.
  *
  * This module is the template the other ten services copy, so what it does and does not do is worth stating:
  *
  *   - it binds the endpoints `services/cluster/contract` publishes to the use cases in
  *     `services/cluster/application`, and no path is written out here — a hand-written path is a path that
  *     drifts from the contract the gateway and the browser were generated from (ADR-003);
  *   - it verifies the gateway's signed principal before any use case runs (ADR-020);
  *   - it maps `KuiError` to the one error envelope and the one status (ADR-034);
  *   - it maps application types to contract types ([[CapabilityMapping]], [[ClusterMapping]], ADR-033);
  *   - it starts nothing. There is no port, no thread and no `IO` in this file: `services/cluster/app` is the
  *     only module allowed either (ADR-002, ADR-010).
  */
object ClusterApi {

  /** The process's name, as it appears in `service.name` on every log line, span and metric. */
  val ServiceName: String = "kui-cluster"

  /** The service's identity in a signed principal's `aud` claim and in a capability document. */
  export ClusterService.Id

  // -----------------------------------------------------------------------------------------------
  // Routes
  // -----------------------------------------------------------------------------------------------

  /** The routes, in the order the router tries them.
    *
    * @param registry
    *   the single resolution point for a cluster id: static configuration overlaid by the metadata store.
    * @param topology
    *   the cluster snapshots. Every call on it is a memory read, which is what keeps the dashboard's response
    *   time a function of this service rather than of the slowest configured cluster.
    * @param brokers
    *   the broker detail use cases. The list comes from the snapshot; configuration and log directories are
    *   read live, because a disk that failed three seconds ago is why the page was opened.
    * @param write
    *   the one write M1 ships: registering or replacing a cluster. It has no user interface and is not
    *   proxied by the gateway; the permission it requires is what keeps it out of a browser's reach.
    * @param capabilities
    *   what this service can currently do, recomputed per request rather than cached — the gateway polls it
    *   precisely to learn when the answer changes.
    * @param readiness
    *   what this service checks before saying it can serve.
    * @param principals
    *   the codec that verifies the gateway's signature. It is never optional: a service trusts a signed
    *   principal and nothing else, whatever the network says (ADR-020).
    * @param rejections
    *   the `kui.principal.rejected{reason}` counter.
    */
  def routes[F[_]: {Async, Parallel}](
      registry: ClusterRegistry[F],
      topology: ClusterTopologyUseCase[F],
      brokers: BrokerDetailUseCase[F],
      write: ClusterWriteUseCase[F],
      capabilities: CapabilityReportUseCase[F],
      readiness: List[ReadinessCheck[F]],
      principals: PrincipalCodec[F],
      rejections: Counter[F, Long],
      telemetry: Telemetry[F],
      logger: StructuredLogger[F]
  ): List[ServerEndpoint[Fs2Streams[F], F]] =
    // The health endpoints come first because nothing else can match their paths and a probe should
    // travel the shortest route through the router. They are `ServerEndpoint[Any, F]` — "needs no
    // capability from the server" — and `ServerEndpoint` is contravariant in that parameter, so they
    // fit into a list typed on `Fs2Streams` that the streaming endpoints need.
    HealthEndpoints.make[F](readiness, capabilityDocument[F](capabilities, logger)) ++
      ClusterRoutes[F](registry, topology, brokers, principals, rejections, logger) ++
      ClusterWriteRoutes[F](write, principals, rejections, logger) ++
      ProfileRoutes[F](registry, principals, rejections, telemetry, logger)

  /** The cross-cutting chain, outermost first, exactly as `libs/http`'s server wants it.
    *
    * The order is the gateway's minus the edge concerns, and each position is load bearing:
    *
    *   1. [[PrincipalInterceptor]] first, so that a missing principal header becomes the same 401 as a forged
    *      one before the shared handler can call it a 400;
    *   1. `KuiInterceptors` next: correlation, then tracing, then metrics, so the id exists before anything
    *      records it and a failing request is still inside a span and still records its duration;
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
    * Two things are added to every published endpoint here and neither changes what the contract says:
    *
    *   - a [[RequestContext]] security input, which reads the method, the path and the correlation id off the
    *     request. It is a server-side extractor: it consumes nothing from the wire and appears in no
    *     generated document or client.
    *   - a status code on the error output. The contract fixes the error *body* for every KUI endpoint, and
    *     the status is decided from the failure itself by `ErrorEnvelope.statusOf` — the single
    *     code-to-status table in the system — at the one point where the `KuiError` is still in hand.
    *
    * It is a class rather than six copies of the same eight lines because those eight lines are where a route
    * could accidentally be served unverified, and a route that forgot them would look exactly like one that
    * did not.
    */
  final class Securing[F[_]: Async](
      principals: PrincipalCodec[F],
      rejections: Counter[F, Long],
      logger: StructuredLogger[F]
  ) {

    def apply[I, O](endpoint: Endpoint[SignedPrincipal, I, ErrorEnvelope, O, Any])(
        logic: Principal => I => F[Either[KuiError, O]]
    ): ServerEndpoint[Any, F] =
      endpoint
        .securityIn(requestContext)
        .errorOut(statusCode)
        .serverSecurityLogic[(Principal, RequestContext), F] { case (token, ctx) =>
          PrincipalVerification
            .secured[F, Failure](principals, Id, logger, rejections)(failure[F])(token, ctx)
        }
        .serverLogic { case (principal, ctx) =>
          input =>
            logic(principal)(input).flatMap {
              case Right(value) => value.asRight[Failure].pure[F]
              case Left(error) => failure[F](error, ctx).map(_.asLeft[O])
            }
        }
  }

  /** The same, for a route whose output is a stream. Tapir's `ServerEndpoint` is invariant in its capability
    * parameter once a streaming body is involved, so the two cannot be one method.
    */
  final class SecuringStream[F[_]: Async](
      principals: PrincipalCodec[F],
      rejections: Counter[F, Long],
      logger: StructuredLogger[F]
  ) {

    def apply[I, O](endpoint: Endpoint[SignedPrincipal, I, ErrorEnvelope, O, Fs2Streams[F]])(
        logic: Principal => I => F[Either[KuiError, O]]
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
            logic(principal)(input).flatMap {
              case Right(value) => value.asRight[Failure].pure[F]
              case Left(error) => failure[F](error, ctx).map(_.asLeft[O])
            }
        }
  }

  /** Reads what verification and error reporting need off the request, once.
    *
    * The digest is built from the request *line* only. Every `/internal/v1` endpoint in M0 is a `GET` with no
    * body, and ADR-020 binds such a call — and every streaming one — to the method and path alone. The first
    * endpoint that carries a body has to hash it, and this is the function that will say so.
    */
  private[api] val requestContext: EndpointInput[RequestContext] =
    extractFromRequest[RequestContext](request =>
      RequestContext(
        RequestDigest.ofRequestLine(request.method.method, request.uri.path.mkString("/", "/", "")),
        request.header(Correlation.HeaderName).flatMap(Correlation.accept)
      )
    )

  /** The two halves of an error response: the body the contract fixes and the status the code decides. */
  private[api] type Failure = (ErrorEnvelope, StatusCode)

  private[api] def failure[F[_]: Sync](error: KuiError, ctx: RequestContext): F[Failure] =
    for {
      correlationId <- ctx.correlationId.fold(Correlation.newRandom[F])(_.pure[F])
      now <- Clock[F].realTimeInstant
    } yield (
      ErrorEnvelope.of(error, correlationId, now),
      StatusCode(ErrorEnvelope.statusOf(error))
    )

  // -----------------------------------------------------------------------------------------------
  // Capabilities
  // -----------------------------------------------------------------------------------------------

  /** What `GET /capabilities` answers, including when this service is having a bad day.
    *
    * The endpoint must answer even when the service cannot do its job, because the gateway's registry needs
    * the report most exactly when things are wrong: a `/capabilities` that failed would tell the browser
    * nothing at all, and the sidebar would have to guess. So a failure inside the use case is logged and
    * becomes a document reporting no working clusters, rather than a 500.
    */
  def capabilityDocument[F[_]: Sync](
      capabilities: CapabilityReportUseCase[F],
      logger: StructuredLogger[F]
  ): F[ServiceCapabilities] =
    capabilities.report
      .map(CapabilityMapping.toWire)
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
    * The health and capability endpoints are not in `ClusterEndpoints.all` — they are identical in all eleven
    * services and come from `libs/http` — but they are absolutely part of what this service serves, and the
    * gateway merges this document rather than the contract file (ADR-003, GW-007).
    */
  def documented[F[_]]: List[AnyEndpoint] =
    ClusterEndpoints.all ++ ClusterWriteEndpoints.all ++ ProfileEndpoints.all ++
      ClusterStreamEndpoint.endpoints[F] ++
      List(HealthEndpoints.live, HealthEndpoints.ready, HealthEndpoints.capabilities)

  /** The service's OpenAPI document. */
  def openApi[F[_]]: OpenAPI =
    OpenAPIDocsInterpreter().toOpenAPI(documented[F], Title, Version)

  val Title: String = "KUI Cluster Registry service"

  /** The document's version, which is the *contract's* version and not the build's.
    *
    * A build number here would make every commit a change to the committed document and every diff noise. The
    * number changes when the endpoints do, which is the question a reader of an OpenAPI document is asking.
    */
  val Version: String = "1.0.0"

  /** The instant a document is stamped with, when one has to be. Fixed, for the same reason as the version: a
    * generated file that changes every time it is generated cannot be committed and checked.
    */
  val GeneratedAt: Instant = Instant.EPOCH
}
