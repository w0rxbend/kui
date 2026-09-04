package kui.consumer.api

import java.time.Instant

import cats.Parallel
import cats.effect.kernel.{Async, Clock, Sync}
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.metrics.Counter
import sttp.apispec.openapi.OpenAPI
import sttp.model.StatusCode
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.Interceptor
import sttp.tapir.{extractFromRequest, statusCode, AnyEndpoint, Endpoint, EndpointInput}

import kui.consumer.application.*
import kui.consumer.contract.{ConsumerEndpoints, ConsumerMutationEndpoints}
import kui.contracts.ErrorEnvelope
import kui.contracts.capability.ServiceCapabilities
import kui.http.ErrorInterceptor
import kui.http.health.{HealthEndpoints, ReadinessCheck}
import kui.http.principal.{PrincipalInterceptor, PrincipalVerification, RequestContext}
import kui.kernel.ServiceId
import kui.kernel.error.KuiError
import kui.observability.{Correlation, KuiInterceptors, Telemetry}
import kui.security.{Principal, PrincipalCodec, RequestDigest, RequestDigests, SignedPrincipal}

/** Everything `kui-consumer-service` serves, and the one place a typed failure becomes an HTTP response.
  *
  * It is the topic service's `api` module with a different set of routes, deliberately: the five things an
  * `api` module does are the same in every service, and a second shape for them would be a second place for
  * authentication, error mapping and the OpenAPI document to drift.
  *
  *   - it binds the endpoints `services/consumer/contract` publishes to the use cases in
  *     `services/consumer/application`, with no path written out here (ADR-003);
  *   - it verifies the gateway's signed principal before any use case runs (ADR-020);
  *   - it maps a `KuiError` to the one error envelope and the one status, through `ErrorEnvelope.statusOf`
  *     (ADR-034);
  *   - it maps application types to contract types ([[ConsumerMapping]], ADR-033);
  *   - it starts nothing. `services/consumer/app` is the only module in this service allowed to.
  */
object ConsumerApi {

  /** The process's name, as it appears in `service.name` on every log line, span and metric. */
  val ServiceName: String = "kui-consumer"

  /** This service's identity in a signed principal's `aud` claim and in a capability document.
    *
    * It has to equal the id the gateway is configured with — `kui.gateway.services.consumer` — or every call
    * from the gateway is refused with a 401 that names no cause the caller can see.
    */
  val Id: ServiceId = ServiceId.unsafe("consumer")

  // -----------------------------------------------------------------------------------------------
  // Routes
  // -----------------------------------------------------------------------------------------------

  /** The routes, in the order the router tries them.
    *
    * Health first, because nothing else can match its paths and a probe should travel the shortest route
    * through the router. Then the four reads, then the four mutation routes.
    */
  def routes[F[_]: {Async, Parallel}](
      list: GroupListUseCase[F],
      detail: GroupDetailUseCase[F],
      lag: LagPollUseCase[F],
      forTopic: GroupsForTopicUseCase[F],
      snapshots: GroupSnapshots[F],
      reset: OffsetResetUseCase[F],
      deleteGroup: DeleteGroupUseCase[F],
      deleteOffsets: DeleteOffsetsUseCase[F],
      readiness: List[ReadinessCheck[F]],
      capabilities: ConsumerCapabilities[F],
      principals: PrincipalCodec[F],
      rejections: Counter[F, Long],
      logger: StructuredLogger[F]
  ): List[ServerEndpoint[Any, F]] = {
    val secured = Securing[F](principals, rejections, logger)

    HealthEndpoints.make[F](readiness, capabilityDocument[F](capabilities, logger)) ++
      ConsumerRoutes[F](list, detail, lag, forTopic, snapshots, secured) ++
      ConsumerMutationRoutes[F](reset, deleteGroup, deleteOffsets, snapshots, secured)
  }

  /** The cross-cutting chain, outermost first, in the order `libs/http`'s server wants it and the topic
    * service already uses: principal, then instrumentation, then error translation innermost.
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
    * Two things are added to every published endpoint and neither changes what the contract says: a
    * [[RequestContext]] security input, which consumes nothing from the wire and appears in no generated
    * document; and a status code on the error output, decided from the failure itself by
    * `ErrorEnvelope.statusOf` at the one point where the `KuiError` is still in hand.
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

    /** The same, for an endpoint that carries a request body.
      *
      * ==Why this exists and why it verifies later than [[apply]] does==
      *
      * ADR-020 binds a signed principal to one call by hashing the method, the path **and the body**. The
      * gateway signs with the body it is about to send; a service must therefore hash the same bytes, or
      * every bodied call is refused as `request_mismatch` — which is exactly what happened the first time the
      * reset wizard was called end to end against a real cluster.
      *
      * Tapir runs security logic *before* it decodes the body, and a `ServerRequest` does not expose the raw
      * bytes, so a bodied endpoint cannot compute that hash where [[apply]] verifies. This variant therefore
      * verifies inside the endpoint's own logic, where the decoded input is in hand, and reconstructs the
      * bytes by re-encoding it with the same codec the gateway encoded it with. That reconstruction is exact
      * rather than approximate: the gateway does not forward the caller's bytes, it re-encodes the decoded
      * value through this very contract (`SttpServiceClient`), and both sides print with circe's
      * `Printer.noSpaces` over a hand-written field order.
      *
      * The cost is real and worth stating: the body of an unauthenticated request is decoded before the token
      * is checked, which [[apply]] avoids. It is bounded — a few hundred bytes of JSON through a codec that
      * refuses anything it does not recognise — and it buys back the binding that stops a token minted for
      * one reset from being replayed with a different one, which is the property that actually matters on a
      * destructive endpoint. The alternative considered and rejected was for the gateway to sign the request
      * line alone for bodied calls: that is a one-line change, it is invisible in every existing test because
      * every endpoint shipped so far has an empty body, and it would quietly drop the body binding for every
      * service at once.
      */
    def withBody[I, O](endpoint: Endpoint[SignedPrincipal, I, ErrorEnvelope, O, Any])(
        bodyOf: I => Array[Byte]
    )(logic: Principal => I => F[Either[KuiError, O]]): ServerEndpoint[Any, F] =
      endpoint
        .securityIn(requestContext)
        .errorOut(statusCode)
        // Carries the token through unverified. Nothing downstream can forget to check it: the only
        // thing that can be done with the pair below is `verified`, which either yields a `Principal`
        // or renders the refusal.
        .serverSecurityLogic[(SignedPrincipal, RequestContext), F] { case (token, ctx) =>
          (token, ctx).asRight[Failure].pure[F]
        }
        .serverLogic { case (token, ctx) =>
          input =>
            val bound = ctx.copy(
              digest = RequestDigests.of(ctx.digest.method, ctx.digest.path, bodyOf(input))
            )

            PrincipalVerification.verify[F](principals, Id, logger, rejections)(token, bound).flatMap {
              case Left(error) => failure[F](error, bound).map(_.asLeft[O])
              case Right(principal) =>
                logic(principal)(input).flatMap {
                  case Right(value) => value.asRight[Failure].pure[F]
                  case Left(error) => failure[F](error, bound).map(_.asLeft[O])
                }
            }
        }
  }

  /** Reads what verification and error reporting need off the request, once.
    *
    * The digest is built from the request *line* only, exactly as the topic service's is. This service does
    * carry request bodies — the reset plan and the apply — and ADR-020's binding for those is the method and
    * path; a body hash would have to be agreed with the gateway's signer, which signs the request line. When
    * the two are extended to cover bodies, this is the function that changes.
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
    * A failure inside the report is logged and becomes a document reporting no clusters, rather than a 500:
    * the gateway's registry needs an answer most exactly when things are wrong.
    */
  def capabilityDocument[F[_]: Sync](
      capabilities: ConsumerCapabilities[F],
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

  /** Every endpoint this service publishes, health included. */
  def documented[F[_]]: List[AnyEndpoint] =
    ConsumerEndpoints.all ++ ConsumerMutationEndpoints.all ++
      List(HealthEndpoints.live, HealthEndpoints.ready, HealthEndpoints.capabilities)

  def openApi[F[_]]: OpenAPI = OpenAPIDocsInterpreter().toOpenAPI(documented[F], Title, Version)

  val Title: String = "KUI Consumer service"

  /** The document's version, which is the *contract's* version and not the build's. */
  val Version: String = "1.0.0"

  /** The instant a document is stamped with, when one has to be. Fixed, for the same reason as the version.
    */
  val GeneratedAt: Instant = Instant.EPOCH
}
