package kui.config

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import cats.data.NonEmptyList

import kui.kernel.Secret

/** How KUI proves to one cluster's Schema Registry that it is allowed to ask.
  *
  * Three cases and not a bag of optional fields, because "basic or OAuth, never both" (ADR-014) is a rule
  * that a record with a username *and* a client secret cannot express: whichever of the two silently lost
  * would be the one an operator changed when the other expired. A discriminated type makes the choice visible
  * in the configuration file and impossible to get half right in the code.
  *
  *   - `Anonymous` — the registry is open, which is the common case inside a private network and is what the
  *     quickstart runs.
  *   - `Basic` — HTTP basic authentication, which is what Confluent Cloud and most self-hosted registries
  *     behind a reverse proxy use.
  *   - `OAuth` — the OAuth 2.0 *client credentials* grant: KUI posts its client id and secret to a token
  *     endpoint, gets a bearer token back and sends that token to the registry. There is no user in this
  *     flow; the credential belongs to KUI as a machine, which is why nothing here is per-request.
  */
enum RegistryAuthConfig {
  case Anonymous
  case Basic(username: String, password: Secret[String])
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
    case OAuth(endpoint, clientId, _, _) => s"oauth client credentials (client '$clientId' at $endpoint)"
  }
}

object RegistryAuthConfig {

  /** The spellings `kui.clusters.<n>.schemaRegistry.auth.type` accepts. */
  def fromWire(raw: String): Option[String] =
    raw.trim.toLowerCase match {
      case value @ ("none" | "basic" | "oauth") => Some(value)
      case _ => None
    }

  given CanEqual[RegistryAuthConfig, RegistryAuthConfig] = CanEqual.derived
}

/** One cluster's Schema Registry, as an operator configured it.
  *
  * This whole value is optional on a `ClusterConfig`, and that is the point. A deployment with no registry is
  * not a broken deployment: it is the ordinary case for a cluster whose producers agree on a format without
  * one, and the schema service reports `not_configured` for such a cluster rather than a red panel nobody can
  * clear (ADR-032).
  *
  * @param urls
  *   the registry's addresses in preference order. More than one is a registry cluster, and `libs/http`'s
  *   failover is what makes the second address useful.
  * @param callTimeout
  *   the whole-call budget, retries included. It is short on purpose: the registry is routinely the least
  *   reliable component in a Kafka deployment, and a screen that waits a minute for it is a screen that has
  *   taken the outage on rather than reported it.
  */
final case class SchemaRegistrySettings(
    urls: NonEmptyList[SafeUrl],
    auth: RegistryAuthConfig = RegistryAuthConfig.Anonymous,
    callTimeout: FiniteDuration = SchemaRegistrySettings.DefaultCallTimeout
) {

  /** Addresses and mechanism, never credentials.
    *
    * The generated `toString` of a case class prints every field, and `auth` holds a password. It is a
    * `Secret` and would redact itself, but a field added later might not, and this value is printed by
    * `ClusterConfig`'s own diagnostic.
    */
  override def toString: String =
    s"SchemaRegistrySettings(${urls.toList.map(_.value).mkString(", ")}, ${auth.describe}, $callTimeout)"
}

object SchemaRegistrySettings {

  val DefaultCallTimeout: FiniteDuration = 10.seconds

  /** The bounds `kui.clusters.<n>.schemaRegistry.callTimeout` is held to.
    *
    * An unbounded timeout is a way for an operator to make KUI worse without being told: a ten-minute budget
    * turns one slow registry into a bulkhead full of waiting requests, which is the failure the bulkhead
    * exists to prevent.
    */
  val MinCallTimeout: FiniteDuration = 1.second
  val MaxCallTimeout: FiniteDuration = 60.seconds

  given CanEqual[SchemaRegistrySettings, SchemaRegistrySettings] = CanEqual.derived
}
