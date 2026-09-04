package kui.identity.application

import java.time.Instant

import cats.effect.kernel.{Clock, Sync}
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger

import kui.identity.domain.*
import kui.kernel.error.{ApplicationError, KuiError}
import kui.kernel.{Secret, UserName}
import kui.security.audit.{AuthAuditSink, AuthenticationEvent, AuthenticationRecord}
import kui.security.rbac.{IdentityAttributes, Provider, Rbac, SubjectKind}
import kui.security.{Principal, PrincipalKind}

/** What a completed sign-in produces, before anything has been written into a session.
  *
  * The session itself is the gateway's (ADR-019): this service decides *who* somebody is and the gateway
  * decides how long the browser gets to keep saying so. Splitting it that way is what keeps one session
  * mechanism in the product rather than two.
  */
enum LoginResult {

  /** Signed in, with roles already resolved. */
  case SignedIn(principal: Principal)

  /** The password was right and this account may not have a session until it has a new one.
    *
    * @param challenge
    *   presented to [[ChangePasswordUseCase]]. It is a `Secret`, and it is the only thing the caller is
    *   given: no principal, no roles, no permissions.
    */
  case MustChangePassword(challenge: Secret[String])
}

object LoginResult {
  given CanEqual[LoginResult, LoginResult] = CanEqual.derived
}

/** Signing in with a username and a password (AU-001's form mode, AU-002's accounts).
  *
  * ==What it does, in order==
  *
  *   1. refuses outright if this deployment is not in form mode — a login endpoint that worked while
  *      `kui.auth.type` said `disabled` would be a second way in that nobody configured;
  *   1. looks the account up, and verifies the password against the stored hash;
  *   1. resolves the person's roles **once**, here, from the deployment's policy (ADR-021). They travel in
  *      the session and then in the signed principal, and no service ever asks an identity provider anything;
  *   1. writes one audit record, whichever way it went.
  *
  * ==Why a failure takes as long as a success==
  *
  * When the account does not exist, the password is still hashed — against a throwaway hash of the same
  * shape. Without that, a login for a name that exists takes the ~100ms a key derivation function costs and a
  * login for one that does not returns immediately, and anybody with a stopwatch can enumerate the account
  * list. It is the same reasoning as the constant-time comparison inside `verify`, one level up.
  *
  * ==What the caller is told about a failure==
  *
  * One sentence, the same one for both reasons. The distinction is in this process's log and nowhere else.
  */
final class LoginUseCase[F[_]: Sync](
    config: IdentityConfig,
    users: UserDirectory[F],
    hasher: PasswordHasher[F],
    challenges: SingleUseTokens[F, UserName],
    audit: AuthAuditSink[F],
    logger: StructuredLogger[F]
) {

  /** A hash nobody's password matches, used to spend the same time on an unknown account as on a known one.
    *
    * Its parameters are the current algorithm's, so the work done is the work a real verification would do.
    * The salt and key are constants and that is harmless: nothing is ever compared *successfully* against it.
    */
  private val DecoyHash: PasswordHash =
    PasswordHash(
      PasswordAlgorithm.Current,
      PasswordAlgorithm.Current.defaultIterations,
      "a2V5c3RyZXRjaGluZw",
      "bm9ib2R5J3NwYXNzd29yZA"
    )

  def apply(credentials: Credentials): F[Either[KuiError, LoginResult]] =
    if config.mode != AuthMode.Form then
      Sync[F]
        .pure(
          ApplicationError
            .Unsupported(s"signing in with a password when kui.auth.type is '${config.mode.wire}'")
            .asLeft[LoginResult]
        )
    else
      for {
        now <- Clock[F].realTimeInstant
        outcome <- attempt(credentials, now)
        result <- record(credentials.username, outcome, now)
      } yield result

  // -----------------------------------------------------------------------------------------------

  private def attempt(credentials: Credentials, now: Instant): F[LoginOutcome] =
    users.find(credentials.username).flatMap {
      case None =>
        // The decoy verification is not optional and must not be optimised away: see the class comment.
        hasher
          .verify(credentials.password, DecoyHash)
          .flatMap(_ => rejected(credentials.username, RejectionReason.NoSuchUser))

      case Some(account) =>
        hasher.verify(credentials.password, account.hash).flatMap {
          case false => rejected(credentials.username, RejectionReason.WrongPassword)
          case true if account.mustChangePassword =>
            challenges
              .issue(account.name, now)
              .map(LoginOutcome.PasswordChangeRequired(account.name, _))
          case true =>
            Sync[F].pure(LoginOutcome.Authenticated(Identity(account.name, account.groups)))
        }
    }

  private def rejected(username: String, reason: RejectionReason): F[LoginOutcome] =
    logger
      .info(Map("auth.subject" -> username, "auth.reason" -> reason.label))(
        "a sign-in was refused"
      )
      .as(LoginOutcome.Rejected)

  /** Writes the audit record and turns the outcome into what the caller gets.
    *
    * One place, so that no path out of this use case can forget to leave a trace — which is the property
    * AD-001 needs and the one an audit trail assembled from four call sites never quite has.
    */
  private def record(
      username: String,
      outcome: LoginOutcome,
      now: Instant
  ): F[Either[KuiError, LoginResult]] =
    outcome match {
      case LoginOutcome.Authenticated(identity) =>
        val principal = principalFor(identity)
        audit
          .record(
            AuthenticationRecord.succeeded(
              now,
              AuthenticationEvent.Login,
              principal,
              Map("roles" -> principal.roles.size.toString)
            )
          )
          .as(LoginResult.SignedIn(principal).asRight[KuiError])

      case LoginOutcome.PasswordChangeRequired(name, challenge) =>
        audit
          .record(
            AuthenticationRecord(
              now,
              AuthenticationEvent.Login,
              name.value,
              Principal.Anonymous,
              kui.security.audit.MutationOutcome.Refused,
              Map("password_change_required" -> "true")
            )
          )
          .as(LoginResult.MustChangePassword(challenge).asRight[KuiError])

      case LoginOutcome.Rejected =>
        audit
          .record(AuthenticationRecord.refused(now, AuthenticationEvent.Login, username))
          .as(LoginUseCase.Refusal.asLeft[LoginResult])
    }

  /** The identity, with the deployment's roles applied to it.
    *
    * A form account's groups are attributed to [[Provider.Form]] and to nothing else: "the group `platform`
    * from KUI's own account list" and "the group `platform` from a directory server" are different grants,
    * and a role file that means one must not silently accept the other.
    */
  private def principalFor(identity: Identity): Principal = {
    val attributes = Map(
      Provider.Form -> IdentityAttributes(
        Map(
          SubjectKind.User -> Set(identity.name.value),
          SubjectKind.Group -> identity.groups
        )
      )
    )

    Principal(identity.name, Rbac.resolveRoles(config.policy, attributes), PrincipalKind.Session)
  }
}

object LoginUseCase {

  /** The one sentence a rejected sign-in gets, whatever was actually wrong with it. */
  val Refusal: KuiError =
    ApplicationError.Unauthenticated("that username and password are not right")
}
