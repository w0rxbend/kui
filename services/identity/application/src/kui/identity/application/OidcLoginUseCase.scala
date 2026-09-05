package kui.identity.application

import cats.effect.kernel.{Clock, Sync}
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger

import kui.identity.domain.{AuthMode, Identity}
import kui.kernel.Secret
import kui.kernel.error.{ApplicationError, KuiError}
import kui.security.audit.{AuthAuditSink, AuthenticationEvent, AuthenticationRecord}
import kui.security.rbac.{IdentityAttributes, Provider, Rbac, SubjectKind}
import kui.security.{Principal, PrincipalKind}

/** What this process has to remember between sending a browser to a provider and getting it back.
  *
  * @param nonce
  *   a value put into the authorization request and required to come back inside the ID token. It is what
  *   binds the token to *this* request: a valid, correctly signed ID token for the right audience, replayed
  *   from another session, carries the wrong nonce and is refused.
  * @param codeVerifier
  *   the PKCE secret (RFC 7636). Only its hash went to the provider, so an authorization code intercepted on
  *   the way back cannot be exchanged by whoever intercepted it — they do not have this.
  */
final case class PendingLogin(nonce: String, codeVerifier: Secret[String])

object PendingLogin {
  given CanEqual[PendingLogin, PendingLogin] = CanEqual.derived
}

/** The relying-party half of OpenID Connect, as a port.
  *
  * Discovery, the authorization URL, the code exchange and ID-token validation all live behind it, in
  * `infrastructure`, because every one of them is HTTP and cryptography. What is left in the application
  * layer is the part that is actually about *KUI*: which browser is in which flow, and what a verified
  * identity means for this deployment's roles.
  */
trait OidcProviderPort[F[_]] {

  /** Where to send the browser, and what to remember while it is away.
    *
    * @param state
    *   the opaque value this service issued. It goes into the authorization request and has to come back
    *   unchanged; the provider never interprets it.
    */
  def start(state: String): F[Either[KuiError, (String, PendingLogin)]]

  /** Turns the code the browser came back with into a verified identity, or says why it could not. */
  def complete(code: String, pending: PendingLogin): F[Either[KuiError, Identity]]
}

/** Signing in through an external provider (AU-001's OIDC mode, AU-003's group source).
  *
  * ==The two halves, and why the state token is the whole security story==
  *
  * [[start]] issues a single-use `state`, remembers a nonce and a PKCE verifier against it, and answers with
  * the provider's authorization URL. [[complete]] redeems that `state`, and only then exchanges the code.
  *
  * A callback whose `state` this process never issued — or issued and already redeemed, or issued six minutes
  * ago — is refused before any HTTP call is made. That single check is what stops an attacker's authorization
  * code being delivered to a signed-in operator's browser and quietly logging them in as somebody else, which
  * is the classic OAuth login-CSRF. It is also why the state must be single-use: a replayable state is a
  * replayable login.
  *
  * ==What is never in the answer==
  *
  * The access token, the ID token, the refresh token and the client secret all stay inside this process. The
  * browser gets a session cookie from the gateway and nothing else. A product that hands its browser the
  * provider's tokens has made every XSS a full account takeover at the identity provider, not merely at KUI.
  */
final class OidcLoginUseCase[F[_]: Sync](
    config: IdentityConfig,
    provider: OidcProviderPort[F],
    pending: SingleUseTokens[F, PendingLogin],
    audit: AuthAuditSink[F],
    logger: StructuredLogger[F]
) {

  /** The address to send the browser to, with the `state` already remembered. */
  def start(): F[Either[KuiError, OidcRedirect]] =
    if config.mode != AuthMode.Oidc then Sync[F].pure(unsupported.asLeft[OidcRedirect])
    else
      for {
        now <- Clock[F].realTimeInstant
        // The token store mints the state, so the value that identifies the flow and the value that is
        // unguessable are one value rather than two things to keep in step. Nothing is stored against it
        // until the adapter has chosen the nonce and the PKCE verifier, so a failed `start` leaves no
        // entry behind at all.
        state <- pending.mint
        started <- provider.start(state.value)
        result <- started match {
          case Left(error) => error.asLeft[OidcRedirect].pure[F]
          case Right((url, waiting)) =>
            pending.remember(state, waiting, now).as(OidcRedirect(url, state).asRight[KuiError])
        }
      } yield result

  /** The browser came back. */
  def complete(code: String, state: Secret[String]): F[Either[KuiError, Principal]] =
    if config.mode != AuthMode.Oidc then Sync[F].pure(unsupported.asLeft[Principal])
    else
      for {
        now <- Clock[F].realTimeInstant
        claimed <- pending.redeem(state, now)
        result <- claimed match {
          case None =>
            logger.warn("an OIDC callback arrived with a state this process did not issue") *>
              audit
                .record(AuthenticationRecord.refused(now, AuthenticationEvent.OidcCallback, "unknown"))
                .as(OidcLoginUseCase.Refusal.asLeft[Principal])

          case Some(waiting) =>
            provider.complete(code, waiting).flatMap {
              case Left(error) =>
                audit
                  .record(AuthenticationRecord.refused(now, AuthenticationEvent.OidcCallback, "unknown"))
                  .as(error.asLeft[Principal])
              case Right(identity) =>
                val principal = principalFor(identity)
                audit
                  .record(
                    AuthenticationRecord.succeeded(
                      now,
                      AuthenticationEvent.OidcCallback,
                      principal,
                      Map("roles" -> principal.roles.size.toString)
                    )
                  )
                  .as(principal.asRight[KuiError])
            }
        }
      } yield result

  private val unsupported: KuiError =
    ApplicationError.Unsupported(s"signing in through a provider when kui.auth.type is '${config.mode.wire}'")

  /** The provider's claims, attributed to [[Provider.Oauth]] — the generic OIDC extractor of ADR-021.
    *
    * A per-vendor extractor (GitHub organisations, Google's `hd`) is RB-002 and is not here: this milestone
    * reads the username claim and the groups claim an operator names, which is what every compliant provider
    * can be configured to emit.
    */
  private def principalFor(identity: Identity): Principal = {
    val attributes = Map(
      Provider.Oauth -> IdentityAttributes(
        Map(
          SubjectKind.User -> Set(identity.name.value),
          SubjectKind.Group -> identity.groups,
          // A generic provider's group claim is Kafbat's `role` subject kind as well as its `group` one,
          // because the two reference deployments spell the same list differently and a role file should
          // not have to know which.
          SubjectKind.Role -> identity.groups
        )
      )
    )

    Principal(identity.name, Rbac.resolveRoles(config.policy, attributes), PrincipalKind.Session)
  }
}

/** Where to send the browser, and the state that must come back with it. */
final case class OidcRedirect(authorizationUrl: String, state: Secret[String])

object OidcRedirect {
  given CanEqual[OidcRedirect, OidcRedirect] = CanEqual.derived
}

object OidcLoginUseCase {

  /** What a callback KUI cannot account for is told. It says nothing about which check failed. */
  val Refusal: KuiError =
    ApplicationError.Unauthenticated("that sign-in could not be completed; start again")
}
