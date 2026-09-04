package kui.security.audit

import java.time.Instant

import cats.Applicative

import kui.security.Principal

/** Something somebody did to *KUI's own* access control, rather than to a cluster.
  *
  * ==Why this is a second record and not a second audit trail==
  *
  * [[MutationRecord]] answers "what changed on this cluster, and who did it". Every field it has is about a
  * cluster: a `ClusterId` that is not optional, and a [[MutationKind]] whose cases are produce, purge, delete
  * topic, reset offsets. A sign-in has none of those, and bending it to fit — an invented cluster id, a
  * `MutationKind.Login` beside `MutationKind.Purge` — would make the type meaningless for both.
  *
  * So there are two record shapes and **one trail**. They share [[AuditPrincipal]], so "who" is rendered
  * identically; they share [[MutationOutcome]], so "how it ended" uses one vocabulary; and
  * `LoggingAuthAuditSink` writes the same field names `LoggingAuditSink` does, so a single query over the
  * trail answers "everything user X did today" across both. The Kafka `__kui_audit` topic (AD-001) carries
  * both for the same reason.
  *
  * ==What is never in one of these==
  *
  * No password, no token, no session id, no authorization code, and no reason for a rejected login. The first
  * four are credentials, and an audit trail is read by more people than a credential store is. The fifth is
  * subtler: "no such user" and "wrong password" in a log that an operator can search is an account
  * enumeration oracle for anyone who can read the log. The *service's* own debug log may say which it was;
  * the audit trail says `refused`.
  *
  * @param subject
  *   the login name that was attempted. It is here even for a refusal, because "somebody tried to sign in as
  *   `root` four hundred times" is the single most useful line an authentication trail can produce. It is
  *   what the caller typed, so it is not necessarily an account that exists.
  * @param principal
  *   who KUI decided the caller was, once it decided. `Principal.Anonymous` for a refused attempt, which is
  *   the honest record: nobody was authenticated.
  * @param detail
  *   anything else worth keeping, as short strings — the provider a sign-in went through, the number of roles
  *   resolved. Never a credential.
  */
final case class AuthenticationRecord(
    at: Instant,
    event: AuthenticationEvent,
    subject: String,
    principal: Principal,
    outcome: MutationOutcome,
    detail: Map[String, String]
)

object AuthenticationRecord {

  /** A refusal, with nobody authenticated and no reason attached. */
  def refused(
      at: Instant,
      event: AuthenticationEvent,
      subject: String,
      detail: Map[String, String] = Map.empty
  ): AuthenticationRecord =
    AuthenticationRecord(at, event, subject, Principal.Anonymous, MutationOutcome.Refused, detail)

  /** Something that worked, by the principal it produced. */
  def succeeded(
      at: Instant,
      event: AuthenticationEvent,
      principal: Principal,
      detail: Map[String, String] = Map.empty
  ): AuthenticationRecord =
    AuthenticationRecord(
      at,
      event,
      principal.name.value,
      principal,
      MutationOutcome.Succeeded,
      detail
    )

  given CanEqual[AuthenticationRecord, AuthenticationRecord] = CanEqual.derived
}

/** What was attempted. The `operation` string is the same field an audit reader filters [[MutationRecord]]
  * by, so `audit.operation` has one namespace across both record shapes.
  */
enum AuthenticationEvent(val operation: String) {

  /** A username and password were presented. */
  case Login extends AuthenticationEvent("auth.login")

  /** A sign-in through an external identity provider completed, or failed to. */
  case OidcCallback extends AuthenticationEvent("auth.oidc.callback")

  /** A session was ended deliberately. */
  case Logout extends AuthenticationEvent("auth.logout")

  /** A password was changed — including the forced change that a first sign-in requires. */
  case PasswordChange extends AuthenticationEvent("auth.password.change")

  /** A request was refused because the principal held no permission for it.
    *
    * It is an authentication-trail event rather than a mutation one because there is no mutation: nothing
    * happened to the cluster, and the interesting fact is about the person.
    */
  case AccessDenied extends AuthenticationEvent("auth.access.denied")
}

object AuthenticationEvent {
  given CanEqual[AuthenticationEvent, AuthenticationEvent] = CanEqual.derived
}

/** Where authentication records go.
  *
  * Separate from [[AuditSink]] rather than a method added to it, because the services that write mutation
  * records have no authentication events to write and the identity service has no mutations: a single
  * interface would make every implementation carry a method it does not mean. They are two ports into one
  * trail, which is the relationship the module layout already expresses.
  *
  * Like [[AuditSink]], `record` must not fail the operation it describes. A sign-in that failed because the
  * audit log was full is worse than a sign-in nobody wrote down.
  */
trait AuthAuditSink[F[_]] {
  def record(entry: AuthenticationRecord): F[Unit]
}

object AuthAuditSink {

  /** For tests and for a deployment that has deliberately turned auditing off. Never the default. */
  def noop[F[_]: Applicative]: AuthAuditSink[F] = new AuthAuditSink[F] {
    def record(entry: AuthenticationRecord): F[Unit] = Applicative[F].unit
  }
}
