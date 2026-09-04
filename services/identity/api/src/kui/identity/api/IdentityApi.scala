package kui.identity.api

import java.time.Instant

import cats.Parallel
import cats.effect.kernel.Async
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
import kui.http.principal.{PrincipalInterceptor, SecuredRoutes}
import kui.identity.application.*
import kui.identity.contract.IdentityEndpoints
import kui.kernel.ServiceId
import kui.observability.{KuiInterceptors, Telemetry}
import kui.security.PrincipalCodec

/** Everything `kui-identity-service` serves.
  *
  * It is the consumer service's `api` module with a different set of routes, deliberately: the five things an
  * `api` module does are the same in every service, and a second shape for them would be a second place for
  * authentication, error mapping and the OpenAPI document to drift.
  *
  * ==What makes this service different from the others==
  *
  * It has no cluster. Every other service is a set of operations against Kafka clusters, and its capability
  * document says which ones it can reach today; this one holds the deployment's accounts and roles, both of
  * which are the same whichever clusters exist. Its capability document therefore reports no clusters, which
  * is a true statement rather than an empty one — and the gateway still polls its readiness, so an identity
  * service that is down is visible as a degraded capability rather than as logins that hang.
  */
object IdentityApi {

  /** The process's name, as it appears in `service.name` on every log line, span and metric. */
  val ServiceName: String = "kui-identity"

  /** This service's identity in a signed principal's `aud` claim.
    *
    * It has to equal the id the gateway is configured with — `kui.gateway.services.identity` — or every call
    * from the gateway is refused with a 401 that names no cause the caller can see.
    */
  val Id: ServiceId = ServiceId.unsafe("identity")

  def routes[F[_]: {Async, Parallel}](
      settings: SettingsUseCase[F],
      login: LoginUseCase[F],
      changePassword: ChangePasswordUseCase[F],
      permissions: PermissionsUseCase[F],
      oidc: OidcLoginUseCase[F],
      readiness: List[ReadinessCheck[F]],
      principals: PrincipalCodec[F],
      rejections: Counter[F, Long],
      logger: StructuredLogger[F]
  ): List[ServerEndpoint[Any, F]] = {
    val secured = Securing[F](principals, rejections, logger)

    HealthEndpoints.make[F](readiness, capabilityDocument[F]) ++
      IdentityRoutes[F](settings, login, changePassword, permissions, oidc, secured)
  }

  /** The cross-cutting chain, outermost first: principal, then instrumentation, then error translation. */
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

  type Securing[F[_]] = SecuredRoutes[F]

  def Securing[F[_]: Async](
      principals: PrincipalCodec[F],
      rejections: Counter[F, Long],
      logger: StructuredLogger[F]
  ): SecuredRoutes[F] = new SecuredRoutes[F](principals, Id, rejections, logger)

  /** What `GET /capabilities` answers: this service, and no clusters.
    *
    * It cannot fail, because there is nothing in it to fail — no broker to ask, no snapshot to be stale. That
    * is worth saying out loud beside the other services' versions, which all have a `handleErrorWith` for the
    * day their cluster is unreachable.
    */
  def capabilityDocument[F[_]: cats.Applicative]: F[ServiceCapabilities] =
    ServiceCapabilities(Id, Map.empty).pure[F]

  /** Every endpoint this service publishes, health included. */
  def documented[F[_]]: List[AnyEndpoint] =
    IdentityEndpoints.all ++
      List(HealthEndpoints.live, HealthEndpoints.ready, HealthEndpoints.capabilities)

  def openApi[F[_]]: OpenAPI = OpenAPIDocsInterpreter().toOpenAPI(documented[F], Title, Version)

  val Title: String = "KUI Identity service"

  /** The document's version, which is the *contract's* version and not the build's. */
  val Version: String = "1.0.0"

  /** The instant a document is stamped with, when one has to be. Fixed, for the same reason as the version.
    */
  val GeneratedAt: Instant = Instant.EPOCH
}
