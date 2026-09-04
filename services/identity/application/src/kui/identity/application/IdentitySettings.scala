package kui.identity.application

import kui.identity.domain.AuthMode
import kui.security.rbac.RbacPolicy

/** One external identity provider, as far as anything outside the OIDC adapter needs to know.
  *
  * It has a label and nothing else. In particular it has no client secret, no issuer URL and no token
  * endpoint: `research/scala/security-research.md` records that one of the reference products leaks its
  * connection settings through its own configuration endpoint, and the way not to repeat that is for the type
  * that answers a browser to be incapable of carrying them.
  */
final case class ProviderSummary(label: String)

object ProviderSummary {
  given CanEqual[ProviderSummary, ProviderSummary] = CanEqual.derived
}

/** What this deployment's authentication looks like from outside.
  *
  * This is the answer to "what should the login screen show", and it is the one part of the identity service
  * that an unauthenticated caller is allowed to see — which is why it is a type of its own rather than a
  * projection of the configuration. Everything in it is already public: which mode is on, and what to write
  * on the button.
  *
  * @param rbacEnabled
  *   whether any role is configured at all. The browser uses it to tell "you may do everything because this
  *   deployment has no authorization" apart from "you may do everything because your role says so" — the two
  *   look identical in a permission list and are very different facts to show an operator.
  */
final case class AuthSettings(mode: AuthMode, provider: Option[ProviderSummary], rbacEnabled: Boolean)

object AuthSettings {

  /** What every KUI deployment has answered until now, and what an unconfigured one still answers. */
  val Disabled: AuthSettings = AuthSettings(AuthMode.Disabled, None, rbacEnabled = false)

  given CanEqual[AuthSettings, AuthSettings] = CanEqual.derived
}

/** Everything the identity service's use cases read out of the deployment's configuration.
  *
  * One value rather than four constructor parameters repeated in five use cases, and narrow rather than the
  * whole `KuiConfig`: a use case that takes exactly what it uses cannot quietly grow a dependency on a
  * section belonging to another service.
  *
  * @param policy
  *   the deployment's roles. `RbacPolicy.Disabled` when none are configured, which is not an empty answer —
  *   it allows everything, because a deployment that configured no roles did not ask for authorization.
  */
final case class IdentityConfig(mode: AuthMode, provider: Option[ProviderSummary], policy: RbacPolicy) {

  def settings: AuthSettings = AuthSettings(mode, provider, policy.enabled)
}

object IdentityConfig {

  /** Authentication off, no roles: the quickstart, the demonstration environment, and the default. */
  val Disabled: IdentityConfig = IdentityConfig(AuthMode.Disabled, None, RbacPolicy.Disabled)

  given CanEqual[IdentityConfig, IdentityConfig] = CanEqual.derived
}
