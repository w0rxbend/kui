package kui.connect.infrastructure

import cats.effect.kernel.{Async, Resource}
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger
import sttp.client4.{Backend, Request}

import kui.config.UpstreamAuthConfig
import kui.http.upstream.UpstreamCredentials
import kui.kernel.Secret
import kui.kernel.error.{ErrorCode, InfrastructureError, KuiError}

/** How each request to a Connect worker proves who KUI is.
  *
  * The service-facing interface stays deliberately small while the shared HTTP implementation owns header
  * replacement, OAuth response validation, bounded reads, timeouts, refresh coalescing, and cancellation.
  */
trait ConnectCredentials[F[_]] {
  def authenticate(request: Request[String]): F[Either[KuiError, Request[String]]]
}

object ConnectCredentials {

  /** The upstream name the token endpoint is known by in errors and metrics. A name, never a URL. */
  val TokenUpstreamName: String = "kafka-connect-oauth"

  def anonymous[F[_]: Async]: ConnectCredentials[F] =
    fromShared(UpstreamCredentials.anonymous[F])

  def basic[F[_]: Async](username: String, password: Secret[String]): ConnectCredentials[F] =
    fromShared(UpstreamCredentials.basic[F](username, password))

  def oauth[F[_]: Async](
      config: UpstreamAuthConfig.OAuth,
      backend: Backend[F],
      logger: StructuredLogger[F]
  ): Resource[F, ConnectCredentials[F]] =
    UpstreamCredentials
      .withBackend(config, backend)
      .map(shared => fromOAuth(shared, logger))

  def fromConfig[F[_]: Async](
      auth: UpstreamAuthConfig,
      tokenBackend: Option[Backend[F]],
      logger: StructuredLogger[F]
  ): Resource[F, ConnectCredentials[F]] =
    auth match {
      case UpstreamAuthConfig.Anonymous => Resource.pure(anonymous[F])
      case UpstreamAuthConfig.Basic(username, password) => Resource.pure(basic[F](username, password))
      case UpstreamAuthConfig.Bearer(_) => Resource.pure(unsupportedBearer[F])
      case oauthConfig: UpstreamAuthConfig.OAuth =>
        tokenBackend.fold(Resource.pure(misconfigured[F]))(oauth(oauthConfig, _, logger))
    }

  private def fromShared[F[_]: Async](shared: UpstreamCredentials[F]): ConnectCredentials[F] =
    request =>
      shared.authenticate(request).map(_.leftMap(UpstreamCredentials.toKuiError(TokenUpstreamName, _)))

  private def fromOAuth[F[_]: Async](
      shared: UpstreamCredentials[F],
      logger: StructuredLogger[F]
  ): ConnectCredentials[F] =
    request =>
      shared
        .authenticate(request)
        .map(_.leftMap(UpstreamCredentials.toKuiError(TokenUpstreamName, _)))
        .flatTap {
          case Left(error) =>
            logger.warn(s"the Kafka Connect OAuth token could not be obtained: ${error.message}")
          case Right(_) => Async[F].unit
        }

  private def misconfigured[F[_]: Async]: ConnectCredentials[F] =
    _ =>
      Async[F].pure(
        Left(
          InfrastructureError.Remote(
            ErrorCode.UpstreamAuth,
            s"$TokenUpstreamName: this Connect cluster is configured for OAuth and no token endpoint " +
              "client was built for it",
            Nil
          )
        )
      )

  private def unsupportedBearer[F[_]: Async]: ConnectCredentials[F] =
    _ =>
      Async[F].pure(
        Left(
          InfrastructureError.Remote(
            ErrorCode.UpstreamAuth,
            "kafka-connect: bearer authentication is not supported",
            Nil
          )
        )
      )
}
