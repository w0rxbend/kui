package kui.ksql.infrastructure

import cats.effect.kernel.{Async, Resource}
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger
import sttp.client4.{Backend, Request, StreamRequest}

import kui.config.UpstreamAuthConfig
import kui.http.upstream.UpstreamCredentials
import kui.kernel.Secret
import kui.kernel.error.{ErrorCode, InfrastructureError, KuiError}

/** How each request to a ksqlDB server proves who KUI is.
  *
  * Both ordinary and streaming requests use the same shared credential source, so push queries cannot drift
  * from the authentication behavior of short-lived ksqlDB calls.
  */
trait KsqlCredentials[F[_]] {
  def authenticate(request: Request[String]): F[Either[KuiError, Request[String]]]

  def authenticateStream[T, S](request: StreamRequest[T, S]): F[Either[KuiError, StreamRequest[T, S]]]
}

object KsqlCredentials {

  /** The upstream name the token endpoint is known by in errors and metrics. A name, never a URL. */
  val TokenUpstreamName: String = "ksqldb-oauth"

  def anonymous[F[_]: Async]: KsqlCredentials[F] =
    fromShared(UpstreamCredentials.anonymous[F])

  def basic[F[_]: Async](username: String, password: Secret[String]): KsqlCredentials[F] =
    fromShared(UpstreamCredentials.basic[F](username, password))

  def oauth[F[_]: Async](
      config: UpstreamAuthConfig.OAuth,
      backend: Backend[F],
      logger: StructuredLogger[F]
  ): Resource[F, KsqlCredentials[F]] =
    UpstreamCredentials
      .withBackend(config, backend)
      .map(shared => fromOAuth(shared, logger))

  def fromConfig[F[_]: Async](
      auth: UpstreamAuthConfig,
      tokenBackend: Option[Backend[F]],
      logger: StructuredLogger[F]
  ): Resource[F, KsqlCredentials[F]] =
    auth match {
      case UpstreamAuthConfig.Anonymous => Resource.pure(anonymous[F])
      case UpstreamAuthConfig.Basic(username, password) => Resource.pure(basic[F](username, password))
      case UpstreamAuthConfig.Bearer(_) => Resource.pure(unsupportedBearer[F])
      case oauthConfig: UpstreamAuthConfig.OAuth =>
        tokenBackend.fold(Resource.pure(misconfigured[F]))(oauth(oauthConfig, _, logger))
    }

  private def fromShared[F[_]: Async](shared: UpstreamCredentials[F]): KsqlCredentials[F] =
    new KsqlCredentials[F] {
      def authenticate(request: Request[String]): F[Either[KuiError, Request[String]]] =
        shared.authenticate(request).map(_.leftMap(UpstreamCredentials.toKuiError(TokenUpstreamName, _)))

      def authenticateStream[T, S](
          request: StreamRequest[T, S]
      ): F[Either[KuiError, StreamRequest[T, S]]] =
        shared
          .authenticateStream(request)
          .map(_.leftMap(UpstreamCredentials.toKuiError(TokenUpstreamName, _)))
    }

  private def fromOAuth[F[_]: Async](
      shared: UpstreamCredentials[F],
      logger: StructuredLogger[F]
  ): KsqlCredentials[F] = {
    val base = fromShared(shared)
    new KsqlCredentials[F] {
      def authenticate(request: Request[String]): F[Either[KuiError, Request[String]]] =
        logFailures(base.authenticate(request), logger)

      def authenticateStream[T, S](
          request: StreamRequest[T, S]
      ): F[Either[KuiError, StreamRequest[T, S]]] =
        logFailures(base.authenticateStream(request), logger)
    }
  }

  private def logFailures[F[_]: Async, A](
      result: F[Either[KuiError, A]],
      logger: StructuredLogger[F]
  ): F[Either[KuiError, A]] =
    result.flatTap {
      case Left(error) => logger.warn(s"the ksqlDB OAuth token could not be obtained: ${error.message}")
      case Right(_) => Async[F].unit
    }

  private def misconfigured[F[_]: Async]: KsqlCredentials[F] =
    failed(
      InfrastructureError.Remote(
        ErrorCode.UpstreamAuth,
        s"$TokenUpstreamName: this ksqlDB is configured for OAuth and no token endpoint client was built for it",
        Nil
      )
    )

  private def unsupportedBearer[F[_]: Async]: KsqlCredentials[F] =
    failed(
      InfrastructureError.Remote(
        ErrorCode.UpstreamAuth,
        "ksqldb: bearer authentication is not supported",
        Nil
      )
    )

  private def failed[F[_]: Async](error: KuiError): KsqlCredentials[F] =
    new KsqlCredentials[F] {
      def authenticate(request: Request[String]): F[Either[KuiError, Request[String]]] =
        Async[F].pure(Left(error))

      def authenticateStream[T, S](
          request: StreamRequest[T, S]
      ): F[Either[KuiError, StreamRequest[T, S]]] =
        Async[F].pure(Left(error))
    }
}
