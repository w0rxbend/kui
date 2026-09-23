package kui.schema.infrastructure

import cats.effect.kernel.{Async, Resource}
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger
import sttp.client4.{Backend, Request}

import kui.config.{RegistryAuthConfig, UpstreamAuthConfig}
import kui.http.upstream.UpstreamCredentials
import kui.kernel.Secret
import kui.kernel.error.{InfrastructureError, KuiError}

/** How each request to a registry proves who KUI is.
  *
  * Registry-specific configuration is adapted once; the shared HTTP credential source owns token transport,
  * strict parsing, refresh coalescing, cancellation, and safe failure classification.
  */
trait RegistryCredentials[F[_]] {
  def authenticate(request: Request[String]): F[Either[KuiError, Request[String]]]
}

object RegistryCredentials {

  /** The upstream name the token endpoint is known by in errors and metrics. A name, never a URL. */
  val TokenUpstreamName: String = "schema-registry-oauth"

  def anonymous[F[_]: Async]: RegistryCredentials[F] =
    fromShared(UpstreamCredentials.anonymous[F])

  def basic[F[_]: Async](username: String, password: Secret[String]): RegistryCredentials[F] =
    fromShared(UpstreamCredentials.basic[F](username, password))

  def oauth[F[_]: Async](
      config: RegistryAuthConfig.OAuth,
      backend: Backend[F],
      logger: StructuredLogger[F]
  ): Resource[F, RegistryCredentials[F]] =
    UpstreamCredentials
      .withBackend(toShared(config), backend)
      .map(shared => fromOAuth(shared, logger))

  def fromConfig[F[_]: Async](
      auth: RegistryAuthConfig,
      tokenBackend: Option[Backend[F]],
      logger: StructuredLogger[F]
  ): Resource[F, RegistryCredentials[F]] =
    auth match {
      case RegistryAuthConfig.Anonymous => Resource.pure(anonymous[F])
      case RegistryAuthConfig.Basic(username, password) => Resource.pure(basic[F](username, password))
      case oauthConfig: RegistryAuthConfig.OAuth =>
        tokenBackend.fold(Resource.pure(misconfigured[F]))(oauth(oauthConfig, _, logger))
    }

  private def toShared(config: RegistryAuthConfig.OAuth): UpstreamAuthConfig.OAuth =
    UpstreamAuthConfig.OAuth(
      config.tokenEndpoint,
      config.clientId,
      config.clientSecret,
      config.scope
    )

  private def fromShared[F[_]: Async](shared: UpstreamCredentials[F]): RegistryCredentials[F] =
    request =>
      shared.authenticate(request).map(_.leftMap(UpstreamCredentials.toKuiError(TokenUpstreamName, _)))

  private def fromOAuth[F[_]: Async](
      shared: UpstreamCredentials[F],
      logger: StructuredLogger[F]
  ): RegistryCredentials[F] =
    request =>
      shared
        .authenticate(request)
        .map(_.leftMap(UpstreamCredentials.toKuiError(TokenUpstreamName, _)))
        .flatTap {
          case Left(error) =>
            logger.warn(s"the schema registry OAuth token could not be obtained: ${error.message}")
          case Right(_) => Async[F].unit
        }

  private def misconfigured[F[_]: Async]: RegistryCredentials[F] =
    _ =>
      Async[F].pure(
        Left(
          InfrastructureError.Unreachable(
            TokenUpstreamName,
            "this cluster is configured for OAuth and no token endpoint client was built"
          )
        )
      )
}
