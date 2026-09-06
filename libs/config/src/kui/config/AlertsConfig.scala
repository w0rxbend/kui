package kui.config

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** The numbers M8's rules compare against, and nothing about the rules themselves.
  *
  * Every value here answers a question of local policy that no default can answer for everybody: a log
  * directory at 80% is a quiet afternoon in one deployment and a page in another, and a consumer group that
  * has been rebalancing for five minutes is either stuck or is a large group doing what large groups do. The
  * *rule* — which fact is read, what severity is opened, what the event says — belongs to the alerts service
  * and is deliberately not modelled here: a rule expressed in YAML is a small programming language, and
  * `libs/config` sits below every service and must not grow one.
  *
  * @param offlinePartitions
  *   the count at or above which offline partitions are worth an event. One, because a single offline
  *   partition is data nobody can read and there is no such thing as an acceptable number of them; it is a
  *   key rather than a constant because a cluster mid-migration can be knowingly noisy for an hour
  * @param underReplicatedPartitions
  *   the count at or above which under-replication is worth an event. One, for the same reason, and tuned up
  *   by the operator of a cluster that reassigns partitions on a schedule
  * @param rebalanceDuration
  *   how long a consumer group may stay in a rebalancing state before it is called stuck. Five minutes: long
  *   enough that an ordinary rebalance of a large group finishes first, short enough that a group wedged
  *   behind a dead member is noticed within the hour
  * @param diskUsedWarningPercent
  *   the share of a log directory's capacity at which it is worth mentioning
  * @param diskUsedCriticalPercent
  *   the share at which it is worth waking somebody. Held above the warning by the loader, because a
  *   critical bound below the warning would mean the warning never fires on the way past it
  */
final case class AlertThresholds(
    offlinePartitions: Int,
    underReplicatedPartitions: Int,
    rebalanceDuration: FiniteDuration,
    diskUsedWarningPercent: Int,
    diskUsedCriticalPercent: Int
)

object AlertThresholds {

  val DefaultOfflinePartitions: Int = 1
  val DefaultUnderReplicatedPartitions: Int = 1
  val DefaultRebalanceDuration: FiniteDuration = 5.minutes
  val DefaultDiskUsedWarningPercent: Int = 80
  val DefaultDiskUsedCriticalPercent: Int = 90

  /** A partition count is a count, so its floor is one: a threshold of zero would open an event on every
    * healthy cluster for ever, which is the fastest known way to make an alerts feed unreadable.
    */
  val MinPartitionCount: Int = 1
  val MaxPartitionCount: Int = 1000000

  val MinRebalanceDuration: FiniteDuration = 10.seconds
  val MaxRebalanceDuration: FiniteDuration = 1.hour

  /** A percentage, and both ends are excluded on purpose: 0% fires always and 100% fires only once the disk
    * is already full, and neither is a threshold anybody meant to type.
    */
  val MinPercent: Int = 1
  val MaxPercent: Int = 99

  val Default: AlertThresholds = AlertThresholds(
    offlinePartitions = DefaultOfflinePartitions,
    underReplicatedPartitions = DefaultUnderReplicatedPartitions,
    rebalanceDuration = DefaultRebalanceDuration,
    diskUsedWarningPercent = DefaultDiskUsedWarningPercent,
    diskUsedCriticalPercent = DefaultDiskUsedCriticalPercent
  )

  given CanEqual[AlertThresholds, AlertThresholds] = CanEqual.derived
}

/** The alerts service's own dials: the `kui.alerts.*` slice.
  *
  * Like `kui.metrics`, the section is here before the service is, because the shape of the section is what
  * three later milestones would otherwise each invent differently. Unlike `kui.metrics` it has no on/off
  * switch: every rule M8 writes reads a fact this product already measures, so alerting is on for every
  * deployment and the only question is where the lines are drawn.
  *
  * @param retention
  *   how long an event is kept after it opens, resolved or not. Seven days, because the feed's own reading —
  *   a relative age, an open count, "one is the usual suspect" — is about this week and not about this year,
  *   and because an event store with no upper bound is a slow leak with a calendar
  * @param evaluationInterval
  *   how often the rules are evaluated against the current facts. A minute, which is twice the cluster
  *   service's own snapshot cadence: evaluating faster than the facts change produces the same answer twice
  *   and costs a scrape
  * @param thresholds
  *   the numbers themselves
  */
final case class AlertsConfig(
    retention: FiniteDuration,
    evaluationInterval: FiniteDuration,
    thresholds: AlertThresholds
)

object AlertsConfig {

  val DefaultRetention: FiniteDuration = 7.days
  val DefaultEvaluationInterval: FiniteDuration = 60.seconds

  /** Below an hour the feed forgets an incident before the person paged for it has finished reading; above
    * ninety days it is an audit log, which is a different product with different storage.
    */
  val MinRetention: FiniteDuration = 1.hour
  val MaxRetention: FiniteDuration = 90.days

  val MinEvaluationInterval: FiniteDuration = 5.seconds
  val MaxEvaluationInterval: FiniteDuration = 1.hour

  /** What a process gets when nothing under `kui.alerts` is configured. Every field here is also the default
    * used per key, so configuring one threshold never resets another.
    */
  val Default: AlertsConfig = AlertsConfig(
    retention = DefaultRetention,
    evaluationInterval = DefaultEvaluationInterval,
    thresholds = AlertThresholds.Default
  )

  given CanEqual[AlertsConfig, AlertsConfig] = CanEqual.derived
}
