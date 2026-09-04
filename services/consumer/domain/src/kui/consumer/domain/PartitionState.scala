package kui.consumer.domain

import kui.kernel.group.LagAnomaly
import kui.kernel.{Offset, PartitionId}

/** How far behind a partition is, or why that question has no answer.
  *
  * `value` and `anomalies` are independent. A partition can have a computable lag *and* an anomaly —
  * `CommittedBeforeStart` is exactly that: the lag is real, and the consumer will still not resume from where
  * it committed because those records are gone. And a partition can have neither, which is the ordinary case.
  */
final case class PartitionLag(value: Option[Long], anomalies: Set[LagAnomaly]) {
  def isDefined: Boolean = value.isDefined
  def has(anomaly: LagAnomaly): Boolean = anomalies.contains(anomaly)
}

object PartitionLag {

  val Unknown: PartitionLag = PartitionLag(None, Set.empty)

  /** The group has never committed here. Not "zero behind": it has not read this partition at all. */
  val noCommit: PartitionLag = PartitionLag(None, Set(LagAnomaly.NoCommit))

  /** The end offset could not be read. Subtracting from an unknown is not zero. */
  val noLeader: PartitionLag = PartitionLag(None, Set(LagAnomaly.NoLeader))

  given CanEqual[PartitionLag, PartitionLag] = CanEqual.derived
}

/** One partition of one group, as far as this context is concerned.
  *
  * All three offset fields are `Option` because all three can genuinely be missing, and each absence means
  * something different: never committed, log start unreadable, no leader. Collapsing any of them to a default
  * is how a wrong number reaches a capacity decision.
  */
final case class PartitionState(
    partition: PartitionId,
    committed: Option[Offset],
    begin: Option[Offset],
    end: Option[Offset],
    memberId: Option[String],
    host: Option[String],
    lag: PartitionLag
)

object PartitionState {
  given CanEqual[PartitionState, PartitionState] = CanEqual.derived
  given Ordering[PartitionState] = Ordering.by(_.partition.value)
}
