package kui.alerts.domain

import java.time.Instant

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import kui.kernel.error.{InfrastructureError, KuiError}
import kui.kernel.{BrokerId, GroupId}

/** The values every alerts suite starts from, written out rather than derived.
  *
  * The limits are the same five numbers `kui.config.AlertThresholds.Default` carries, **typed here as
  * literals**. That duplication is on purpose and it is the point: a suite that read the defaults from
  * `libs/config` would pass whatever those defaults became, so "five minutes" would stop being a fact this
  * repository asserts anywhere. `AlertsWiringSuite` is where the two are held together, once.
  */
object AlertFixtures {

  val now: Instant = Instant.parse("2026-09-07T12:00:00Z")

  val offlineThreshold: Int = 1
  val underReplicatedThreshold: Int = 1
  val rebalanceThreshold: FiniteDuration = 5.minutes
  val diskWarningPercent: Int = 80
  val diskCriticalPercent: Int = 90

  val limits: AlertLimits = AlertLimits(
    offlinePartitions = offlineThreshold,
    underReplicatedPartitions = underReplicatedThreshold,
    rebalanceDuration = rebalanceThreshold,
    diskUsedWarningPercent = diskWarningPercent,
    diskUsedCriticalPercent = diskCriticalPercent
  )

  val broker: BrokerId = BrokerId.unsafe(1)

  val payments: GroupId = GroupId.unsafe("payments-consumer")

  /** A cluster with nothing wrong: every fact readable, every number under its threshold. */
  val healthy: ClusterFacts = ClusterFacts(
    partitions = FactReading.Read(PartitionFacts(offline = 0, underReplicated = 0, complete = true)),
    rebalancingGroups = FactReading.Read(Set.empty),
    logDirectories = FactReading.Read(Nil)
  )

  /** A refusal to stand in for "the broker did not answer this call". */
  val refused: KuiError =
    InfrastructureError.Unreachable("kafka-admin", "the broker refused the request")

  def partitions(offline: Int, underReplicated: Int, complete: Boolean = true): ClusterFacts =
    healthy.copy(partitions = FactReading.Read(PartitionFacts(offline, underReplicated, complete)))

  def rebalancing(groups: GroupId*): ClusterFacts =
    healthy.copy(rebalancingGroups = FactReading.Read(groups.toSet))

  def directories(dirs: LogDirectoryFact*): ClusterFacts =
    healthy.copy(logDirectories = FactReading.Read(dirs.toList))

  def directory(path: String, usedPercent: Option[Int]): LogDirectoryFact =
    LogDirectoryFact(broker, path, usedPercent)

  /** One pass over `facts` with nothing already open and nothing remembered. */
  def firstPass(facts: ClusterFacts, at: Instant = now, using: AlertLimits = limits): Evaluation =
    AlertRules.evaluate(at, using, facts, Nil, AlertRuleState.empty)
}
