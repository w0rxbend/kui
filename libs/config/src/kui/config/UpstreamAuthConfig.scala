package kui.config

import java.util.Locale

import kui.kernel.Secret

/** How KUI proves to one HTTP dependency of a cluster — a Kafka Connect cluster, a ksqlDB cluster — that it
  * is allowed to ask.
  *
  * Distinct cases and not a bag of optional fields, for the reason [[RegistryAuthConfig]] gives at length:
  * "basic or OAuth, never both" (ADR-014) is a rule that a record holding a username *and* a client secret
  * cannot express, and whichever of the two silently lost would be the one an operator changed when the other
  * expired.
  *
  * It is a second enum rather than a rename of [[RegistryAuthConfig]] because that type is named in
  * `services/schema`, which this slice does not own. The older type supports the legacy none/basic/OAuth
  * subset; this shared type also carries static bearer credentials for integrations that explicitly allow
  * them. Existing products retain their own allow-list at decode time.
  */
enum UpstreamAuthConfig {
  case Anonymous
  case Basic(username: String, password: Secret[String])
  case Bearer(token: Secret[String])
  case OAuth(
      tokenEndpoint: SafeUrl,
      clientId: String,
      clientSecret: Secret[String],
      scope: Option[String]
  )

  /** What an operator's configuration said, for a log line or a diagnostic. Never a credential. */
  def describe: String = this match {
    case Anonymous => "anonymous"
    case Basic(username, _) => s"basic (user '$username')"
    case Bearer(_) => "bearer token"
    case OAuth(endpoint, clientId, _, _) => s"oauth client credentials (client '$clientId' at $endpoint)"
  }
}

object UpstreamAuthConfig {

  enum Mechanism {
    case None
    case Basic
    case Bearer
    case OAuth

    def wireName: String = this match {
      case Mechanism.None => "none"
      case Mechanism.Basic => "basic"
      case Mechanism.Bearer => "bearer"
      case Mechanism.OAuth => "oauth"
    }
  }

  object Mechanism {
    val All: Set[Mechanism] = Set(None, Basic, Bearer, OAuth)
    val LegacyHttp: Set[Mechanism] = Set(None, Basic, OAuth)
  }

  /** The spellings an `auth.type` key accepts. */
  def fromWire(raw: String): Option[Mechanism] = {
    val normalized = raw.trim.toLowerCase(Locale.ROOT)
    Mechanism.All.find(_.wireName == normalized)
  }

  /** Everything the decoder reads under one `auth` block, so a caller can register the keys with the
    * unknown-key check.
    *
    * Returned rather than written down twice, exactly as `ClusterSecurityConfig.keysUnder` is: a key that is
    * decoded but not registered becomes an "unknown configuration key" error on a perfectly valid file, and
    * that is a startup failure caused by nothing but a list somebody forgot to extend.
    */
  def keysUnder(prefix: String): List[String] =
    List("type", "username", "password", "token", "tokenEndpoint", "clientId", "clientSecret", "scope")
      .map(leaf => s"$prefix.$leaf")

  given CanEqual[UpstreamAuthConfig, UpstreamAuthConfig] = CanEqual.derived
}
