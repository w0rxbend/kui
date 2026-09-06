package kui.config

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import cats.data.NonEmptyList

import kui.kernel.ConnectName

/** One Kafka Connect cluster attached to one Kafka cluster, as an operator configured it.
  *
  * A cluster has a *list* of these where it has at most one Schema Registry, and that difference is not
  * decoration: an organisation routinely runs one Connect cluster for sources and another for sinks against
  * the same brokers, and the screens address a connector as (Connect cluster, connector name) rather than by
  * URL — which is exactly what `ConnectName` was shipped for. A single-valued key here would have forced the
  * second Connect cluster to be a second Kafka cluster entry, duplicating every broker fact to say one new
  * address.
  *
  * A cluster with no Connect cluster configured is the ordinary case, not a broken deployment: the connect
  * service reports `not_configured` and ADR-032's rule hides the drawer row, rather than showing a row that
  * can never fill.
  *
  * @param name
  *   what this Connect cluster is called in a URL, in an RBAC rule and on a screen. Defaulted from the list
  *   index rather than required, because the deployment with exactly one Connect cluster — which is most of
  *   them — should not have to invent a name to say one address
  * @param urls
  *   the workers' addresses in preference order. Every worker in a Connect cluster serves the same REST API
  *   and forwards what it cannot answer itself, so a second address is genuine failover rather than a second
  *   system, and `libs/http`'s failover is what makes it useful
  * @param callTimeout
  *   the whole-call budget, retries included. Short on purpose, for the reason `SchemaRegistrySettings`
  *   gives: a screen that waits a minute for a struggling upstream has taken the outage on rather than
  *   reported it
  */
final case class ConnectClusterSettings(
    name: ConnectName,
    urls: NonEmptyList[SafeUrl],
    auth: UpstreamAuthConfig = UpstreamAuthConfig.Anonymous,
    callTimeout: FiniteDuration = ConnectClusterSettings.DefaultCallTimeout
) {

  /** Name, addresses and mechanism, never credentials.
    *
    * The generated `toString` of a case class prints every field, and `auth` holds a password. It is a
    * `Secret` and would redact itself, but a field added later might not, and this value is printed by
    * `ClusterConfig`'s own diagnostic.
    */
  override def toString: String =
    s"ConnectClusterSettings('${name.value}', ${urls.toList.map(_.value).mkString(", ")}, " +
      s"${auth.describe}, $callTimeout)"
}

object ConnectClusterSettings {

  val DefaultCallTimeout: FiniteDuration = 10.seconds

  /** The bounds `kui.clusters.<n>.connect.<m>.callTimeout` is held to, and the same ones the registry uses.
    * An unbounded timeout is a way for an operator to make KUI worse without being told: a ten-minute budget
    * turns one wedged Connect worker into a bulkhead full of waiting requests.
    */
  val MinCallTimeout: FiniteDuration = 1.second
  val MaxCallTimeout: FiniteDuration = 60.seconds

  /** What the entry at `index` is called when the operator did not say.
    *
    * Derived from the index rather than from the URL's host, because the index is stable and already dense —
    * the loader refuses a gap in the list — while a host name changes the first time the deployment moves,
    * and this name appears in URLs and RBAC rules that must survive that move.
    */
  def defaultName(index: Int): ConnectName = ConnectName.unsafe(s"connect-$index")

  given CanEqual[ConnectClusterSettings, ConnectClusterSettings] = CanEqual.derived
}
