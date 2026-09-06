package kui.config

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import cats.data.NonEmptyList

/** One cluster's ksqlDB, as an operator configured it.
  *
  * Optional in full, and at most one per cluster: unlike Kafka Connect, a second ksqlDB cluster over the
  * same brokers shares the same command topic and is the same logical service, so a list here would model a
  * deployment that does not exist.
  *
  * @param urls
  *   the servers' addresses in preference order. A ksqlDB cluster's servers all accept statements and
  *   forward what they must, so a second address is failover rather than a second system
  * @param callTimeout
  *   the budget for a request that answers and finishes: listing objects, describing a stream, running a
  *   pull query
  * @param streamTimeout
  *   the budget for a *push* query, which by definition does not finish. It is a separate key rather than a
  *   larger `callTimeout` because the two are different promises: a listing that takes thirty seconds is
  *   broken, and a push query that runs for thirty minutes is working exactly as asked. One key for both
  *   would mean choosing which of those two lies to tell
  */
final case class KsqlSettings(
    urls: NonEmptyList[SafeUrl],
    auth: UpstreamAuthConfig = UpstreamAuthConfig.Anonymous,
    callTimeout: FiniteDuration = KsqlSettings.DefaultCallTimeout,
    streamTimeout: FiniteDuration = KsqlSettings.DefaultStreamTimeout
) {

  /** Addresses and mechanism, never credentials. Same reason as every other settings type here: a field
    * added later that is not a `Secret` would otherwise leak into `ClusterConfig`'s diagnostic.
    */
  override def toString: String =
    s"KsqlSettings(${urls.toList.map(_.value).mkString(", ")}, ${auth.describe}, " +
      s"$callTimeout, stream $streamTimeout)"
}

object KsqlSettings {

  val DefaultCallTimeout: FiniteDuration = 10.seconds
  val DefaultStreamTimeout: FiniteDuration = 5.minutes

  val MinCallTimeout: FiniteDuration = 1.second
  val MaxCallTimeout: FiniteDuration = 60.seconds

  /** A push query may run for a long time and may not run for ever: an hour is the point past which a
    * forgotten browser tab is holding a server-side query open rather than watching one.
    */
  val MinStreamTimeout: FiniteDuration = 1.second
  val MaxStreamTimeout: FiniteDuration = 1.hour

  given CanEqual[KsqlSettings, KsqlSettings] = CanEqual.derived
}
