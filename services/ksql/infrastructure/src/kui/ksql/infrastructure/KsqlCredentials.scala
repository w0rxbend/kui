package kui.ksql.infrastructure

import java.time.Instant

import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}

import cats.effect.kernel.{Async, Clock, Ref, Resource}
import cats.syntax.all.*
import io.circe.parser
import org.typelevel.log4cats.StructuredLogger
import sttp.client4.*
import sttp.model.{StatusCode, Uri}

import kui.config.UpstreamAuthConfig
import kui.kernel.Secret
import kui.kernel.error.{ErrorCode, InfrastructureError, KuiError}

/** How each request to a ksqlDB server proves who KUI is.
  *
  * An effect and not a plain `Request => Request`, because one of the three mechanisms has to go and fetch
  * something: an OAuth bearer token is obtained from an issuer, expires, and has to be obtained again. Making
  * every mechanism effectful keeps the call site identical for all three, so `KsqlHttp` contains no branch on
  * how it authenticates.
  *
  * ==This is the third copy, and that is a debt this file names rather than hides==
  *
  * `RegistryCredentials` in `services/schema` and `ConnectCredentials` in `services/connect` are the same
  * three mechanisms and very nearly the same code. `ConnectCredentials`' own header says the count "stops at
  * two" and that the shared type they should be built on is `UpstreamAuthConfig`, which `libs/config` already
  * declares, with the collapse happening "in `libs/http`". This wave's partition gives `libs/http` to an
  * adversarial packet and this service to this one, so the collapse cannot happen here: ADR-041 rule A11
  * forbids one service reaching into another service's private layers, and a `services/ksql` that imported
  * either of the other two would be exactly the edge that rule exists to refuse.
  *
  * So there are three, the exact edit that makes them one is written down in ADR-055 §9 and reported through
  * `needsOutsideOwnership`, and the interface here is deliberately identical to the other two so that the
  * move is a rename rather than a rewrite.
  *
  * A failure to *obtain* a credential is a `KuiError` rather than an exception, and it is deliberately
  * distinct from the server refusing one: "KUI could not get a token from your identity provider" and "your
  * ksqlDB rejected KUI's token" send an operator to two different systems.
  */
trait KsqlCredentials[F[_]] {
  def authenticate(request: Request[String]): F[Either[KuiError, Request[String]]]

  /** The same decision for a request whose response is a stream.
    *
    * A second method rather than one polymorphic in the response type, because sttp's `StreamRequest` and
    * `Request` are different types with no common supertype that carries `auth`. The header is computed once
    * by [[headerFor]] and applied here, so the two paths cannot authenticate differently.
    */
  def authenticateStream[T, S](request: StreamRequest[T, S]): F[Either[KuiError, StreamRequest[T, S]]]
}

object KsqlCredentials {

  /** The upstream name the token endpoint is known by in errors and metrics. A name, never a URL. */
  val TokenUpstreamName: String = "ksqldb-oauth"

  /** How long before a token's stated expiry KUI fetches a new one.
    *
    * A token that expires in flight produces a 401 that looks exactly like a misconfigured credential, and an
    * operator would then go and check a client secret that was never wrong. Thirty seconds is comfortably
    * more than a ksqlDB call's own budget, so a token handed to a request outlives that request — with the
    * one stated exception that a **push query** routinely outlives its token, which ADR-055 §9 records: the
    * authorization header is applied when the query is opened and ksqlDB does not re-check it, so a stream
    * held open for an hour is held open on a credential that expired.
    */
  val RefreshMargin: FiniteDuration = 30.seconds

  /** The shortest lifetime KUI will believe.
    *
    * An issuer that answers `expires_in: 1` — or `0`, which some do when they mean "does not expire" — would
    * otherwise make KUI fetch a token per request and turn its own authentication into a denial of service
    * against the issuer.
    */
  val MinimumLifetime: FiniteDuration = 1.minute

  /** Nothing to prove, which is what a ksqlDB on a private network ordinarily wants. */
  def anonymous[F[_]: Async]: KsqlCredentials[F] = fromHeader[F](Async[F].pure(Right(None)))

  /** HTTP basic. The password is unwrapped exactly here, at the moment it becomes a header value, and is held
    * as a `Secret` everywhere else so that no log line, error or diagnostic can print it.
    */
  def basic[F[_]: Async](username: String, password: Secret[String]): KsqlCredentials[F] = {
    val encoded = java.util.Base64.getEncoder
      .encodeToString(s"$username:${password.value}".getBytes(java.nio.charset.StandardCharsets.UTF_8))

    fromHeader[F](Async[F].pure(Right(Some(s"Basic $encoded"))))
  }

  /** OAuth 2.0 client credentials, with the token cached until shortly before it expires. */
  def oauth[F[_]: Async](
      config: UpstreamAuthConfig.OAuth,
      backend: Backend[F],
      logger: StructuredLogger[F]
  ): Resource[F, KsqlCredentials[F]] =
    Resource
      .eval(Ref.of[F, Option[CachedToken]](None))
      .map(cache => fromHeader[F](new OAuthHeader[F](config, backend, cache, logger).value))

  /** A bearer token and the instant KUI stops using it. */
  final case class CachedToken(token: Secret[String], usableUntil: Instant)

  /** The credentials one cluster's configuration asks for.
    *
    * A `Resource` because the OAuth case holds a cached token for the life of the process.
    *
    * @param tokenBackend
    *   the transport for the token endpoint, which the caller builds as its own upstream — see `KsqlWiring`
    *   for why it must not be the server's. `None` is legal for the two mechanisms that need no issuer; an
    *   OAuth configuration with no backend is a wiring mistake, and it produces credentials that refuse every
    *   call with that sentence rather than a `NoSuchElementException` at the first request.
    */
  def fromConfig[F[_]: Async](
      auth: UpstreamAuthConfig,
      tokenBackend: Option[Backend[F]],
      logger: StructuredLogger[F]
  ): Resource[F, KsqlCredentials[F]] =
    auth match {
      case UpstreamAuthConfig.Anonymous => Resource.pure(anonymous[F])
      case UpstreamAuthConfig.Basic(username, password) => Resource.pure(basic[F](username, password))
      case oauthConfig: UpstreamAuthConfig.OAuth =>
        tokenBackend match {
          case Some(backend) => oauth[F](oauthConfig, backend, logger)
          case None => Resource.pure(misconfigured[F])
        }
    }

  /** Credentials that cannot be obtained, as a value.
    *
    * It fails the request rather than the process: a ksqlDB KUI cannot authenticate to must show one
    * unavailable section, exactly like a ksqlDB that is down, and never stop a service that is also serving
    * three other clusters.
    */
  private def misconfigured[F[_]: Async]: KsqlCredentials[F] =
    fromHeader[F](
      Async[F].pure(
        Left(
          InfrastructureError.Remote(
            ErrorCode.UpstreamAuth,
            s"$TokenUpstreamName: this ksqlDB is configured for OAuth and no token endpoint client was " +
              "built for it",
            Nil
          )
        )
      )
    )

  /** One `Authorization` header value — or none, or a reason there is none — applied to both request shapes.
    *
    * This is the seam that stops the plain calls and the push query authenticating differently, which would
    * be a live defect rather than a tidiness one: a push query that opened without the header would be
    * refused by a secured ksqlDB and the screen would show an empty stream.
    */
  private def fromHeader[F[_]: Async](header: F[Either[KuiError, Option[String]]]): KsqlCredentials[F] =
    new KsqlCredentials[F] {

      def authenticate(request: Request[String]): F[Either[KuiError, Request[String]]] =
        header.map(_.map(value => value.fold(request)(request.header("Authorization", _))))

      def authenticateStream[T, S](
          request: StreamRequest[T, S]
      ): F[Either[KuiError, StreamRequest[T, S]]] =
        header.map(_.map(value => value.fold(request)(request.header("Authorization", _))))
    }

  /** `Bearer …`, fetched and cached. */
  final private class OAuthHeader[F[_]: Async](
      config: UpstreamAuthConfig.OAuth,
      backend: Backend[F],
      cache: Ref[F, Option[CachedToken]],
      logger: StructuredLogger[F]
  ) {

    def value: F[Either[KuiError, Option[String]]] = token.map(_.map(t => Some(s"Bearer ${t.value}")))

    /** The cached token while it is still comfortably valid, otherwise a fresh one.
      *
      * Two requests arriving at an expiry can both fetch, and that is accepted rather than serialised behind
      * a lock: a duplicate token request is cheap and harmless, whereas a lock held across an HTTP call to an
      * identity provider that has stopped answering would block every ksqlDB request behind it — turning a
      * slow issuer into a hung screen, which is the failure this whole service is shaped to avoid.
      */
    private def token: F[Either[KuiError, Secret[String]]] =
      for {
        now <- Clock[F].realTimeInstant
        cached <- cache.get
        result <- cached match {
          case Some(entry) if entry.usableUntil.isAfter(now) =>
            Async[F].pure(entry.token.asRight[KuiError])
          case _ =>
            fetch(now)
              .flatTap {
                case Right(entry) => cache.set(Some(entry))
                case Left(_) => Async[F].unit
              }
              .map(_.map(_.token))
        }
      } yield result

    private def fetch(now: Instant): F[Either[KuiError, CachedToken]] = {
      val form = Map("grant_type" -> "client_credentials") ++ config.scope.map("scope" -> _).toMap

      basicRequest
        .post(endpoint)
        // The client id and secret travel in the `Authorization` header rather than in the form body. Both
        // are allowed by RFC 6749; the header is the one issuers agree on, and it is the one that keeps the
        // secret out of a proxy's access log, where a form body routinely ends up.
        .auth
        .basic(config.clientId, config.clientSecret.value)
        .body(form)
        .response(asStringAlways)
        .send(backend)
        .map { response =>
          if response.code.isSuccess then parseToken(response.body, now)
          else if response.code == StatusCode.Unauthorized || response.code == StatusCode.Forbidden then
            Left(InfrastructureError.AuthFailed(TokenUpstreamName))
          else Left(InfrastructureError.Upstream(TokenUpstreamName, response.code.code))
        }
        .recover {
          // The body of a failed token request is never echoed: it is the one response in KUI most likely
          // to quote the credential that was sent.
          case failure: Exception =>
            Left(
              InfrastructureError.Unreachable(
                TokenUpstreamName,
                s"${failure.getClass.getSimpleName}: ${Option(failure.getMessage).getOrElse("no detail")}"
              )
            )
        }
        .flatTap {
          case Left(error) =>
            logger.warn(
              s"the ksqlDB OAuth token could not be obtained from ${config.tokenEndpoint.value}: " +
                error.message
            )
          case Right(_) => Async[F].unit
        }
    }

    private val endpoint: Uri =
      Uri.parse(config.tokenEndpoint.value).getOrElse(uri"http://oauth.invalid")

    /** `{"access_token": "...", "expires_in": 3600}`, and nothing else believed.
      *
      * An issuer that omits `expires_in` gets [[MinimumLifetime]] rather than "forever": a token cached
      * forever becomes a 401 that outlives every restart, and one re-fetched a minute later costs nothing.
      */
    private def parseToken(body: String, now: Instant): Either[KuiError, CachedToken] =
      parser.parse(body).left.map(_ => malformed("it is not JSON")).flatMap { json =>
        val cursor = json.hcursor
        cursor
          .get[String]("access_token")
          .left
          .map(_ => malformed("it has no 'access_token' field"))
          .flatMap(raw =>
            if raw.trim.isEmpty then Left(malformed("its 'access_token' is empty"))
            else {
              val lifetime = cursor
                .get[Long]("expires_in")
                .toOption
                .map(_.seconds)
                .filter(_ > MinimumLifetime)
                .getOrElse(MinimumLifetime)

              Right(
                CachedToken(
                  Secret(raw),
                  now.plusMillis((lifetime - RefreshMargin).max(MinimumLifetime / 2).toMillis)
                )
              )
            }
          )
      }

    /** The issuer answered, and the answer was not one KUI can use.
      *
      * The text is written here from the shape of the response and contains nothing the issuer sent, which is
      * what makes it safe to show: a token response's body is the last thing that should reach a screen.
      */
    private def malformed(why: String): KuiError =
      InfrastructureError.Remote(
        ErrorCode.UpstreamAuth,
        s"the OAuth token endpoint's answer could not be understood: $why",
        Nil
      )
  }
}
