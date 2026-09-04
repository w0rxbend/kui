package kui.identity.domain

/** How this deployment expects people to prove who they are.
  *
  * It mirrors `kui.auth.type` and is deliberately not the same type: `kui.config.AuthType` is what a
  * configuration file parses into, and a `domain` module may not depend on the configuration loader any more
  * than on a Kafka client (ADR-041 A1). The composition root maps one to the other, which is three lines and
  * buys the whole layering rule.
  *
  * The `wire` strings are equal to the configuration's on purpose: the browser is told which mode it is in,
  * and an operator debugging a login should see the same word in the file, in the log and in the response.
  */
enum AuthMode(val wire: String) {

  /** Nobody signs in. Every request is anonymous, and this is the default. */
  case Disabled extends AuthMode("disabled")

  /** A username and password, checked against the accounts this deployment configured. */
  case Form extends AuthMode("form")

  /** OpenID Connect: the browser is sent to a provider and comes back with an authorization code. */
  case Oidc extends AuthMode("oidc")
}

object AuthMode {

  def fromWire(raw: String): Option[AuthMode] = values.find(_.wire == raw.trim.toLowerCase)

  given CanEqual[AuthMode, AuthMode] = CanEqual.derived
}
