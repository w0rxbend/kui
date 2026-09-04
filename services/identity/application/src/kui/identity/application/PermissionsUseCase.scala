package kui.identity.application

import cats.Applicative
import cats.syntax.all.*

import kui.kernel.error.KuiError
import kui.security.Principal
import kui.security.rbac.{ClusterPermission, Rbac}

/** Everything a principal may do, expanded, across every cluster (RB-003).
  *
  * ==Why this is a use case at all, given that it is one function call==
  *
  * Because it is the *only* place the answer is computed. Four microfrontends gate the same write control; if
  * each asked a different service, or if the gateway computed one answer and a service another, they would
  * disagree, and the one that is wrong is the one the user sees — a button that is offered and then refused.
  * `Rbac.grants` is the shared function and this is its single caller inside the product's authentication
  * story, so an operator's roles reach a browser through exactly one path.
  *
  * ==Why the answer for an unconfigured deployment is a wildcard and not an empty list==
  *
  * An empty list means "you may do nothing". Answering that in a deployment which has asked for no
  * authorization would hide every write control in the quickstart. `Rbac.grants` already gets this right; it
  * is repeated here because it is the single most consequential line in the file it is not written in.
  *
  * ==What holding this answer grants==
  *
  * Nothing. It says what the *server* will allow, so the interface can hide what it would refuse. Every
  * service re-decides from the signed principal on every request (ADR-020, ADR-021): a check that only ran in
  * the browser is not a check.
  */
final class PermissionsUseCase[F[_]: Applicative](config: IdentityConfig) {

  def apply(principal: Principal): F[Either[KuiError, List[ClusterPermission]]] =
    Rbac.grants(config.policy, principal).asRight[KuiError].pure[F]
}

/** What the login screen needs to know before anybody has signed in (AU-001).
  *
  * It is deliberately the *only* thing this service will tell an unauthenticated caller, and it is
  * deliberately a narrow type: `research/scala/security-research.md` records a reference product leaking its
  * Kafka credentials through its own configuration endpoint, and the defence that actually works is for the
  * value that answers this question to be unable to carry a credential in the first place. [[AuthSettings]]
  * holds a mode, a label and a boolean.
  */
final class SettingsUseCase[F[_]: Applicative](config: IdentityConfig) {

  def apply(): F[Either[KuiError, AuthSettings]] =
    config.settings.asRight[KuiError].pure[F]
}
