package kui.config

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** The consumer service's own dials: the `kui.consumers.*` slice.
  *
  * It holds one key today, and it exists as a section rather than as a constant in the service's composition
  * root for the reason the root itself wrote down before this file existed: describing every consumer group
  * on a cluster and describing its topics are different costs against different broker paths, so one knob for
  * both would mean tuning the cheaper scrape by the expensive one. `kui.topics.refreshInterval` is therefore
  * not reused, and the consumer interval is not a constant either — a cluster with four thousand groups and a
  * cluster with four are not the same deployment, and the operator of the first one is the person who has to
  * be able to turn it down.
  *
  * @param refreshInterval
  *   how often each cluster's consumer groups are described in the background. Thirty seconds, because
  *   describing every group on a cluster is the most expensive read the consumer service makes and the
  *   numbers it produces — lag, pace — are only meaningful over an interval. There is no TTL: a snapshot
  *   older than this is shown and marked stale, never withheld
  */
final case class ConsumersConfig(refreshInterval: FiniteDuration)

object ConsumersConfig {

  val DefaultRefreshInterval: FiniteDuration = 30.seconds

  /** The bounds. Below five seconds the scrape never finishes before the next one starts on any cluster worth
    * scraping; above an hour the lag column is a historical record rather than a monitoring signal.
    */
  val MinRefreshInterval: FiniteDuration = 5.seconds
  val MaxRefreshInterval: FiniteDuration = 1.hour

  /** What a process gets when nothing under `kui.consumers` is configured. */
  val Default: ConsumersConfig = ConsumersConfig(DefaultRefreshInterval)

  given CanEqual[ConsumersConfig, ConsumersConfig] = CanEqual.derived
}
