package kui.topic.application

import kui.topic.domain.{Aggregate, PartitionView}

/** The arithmetic every screen in the milestone depends on, named where the use cases can find it.
  *
  * Each function forwards to `kui.topic.domain.Aggregate`, which is where the rules actually live. That is
  * not indirection for its own sake: the refusal rule below is a domain invariant, and it has to be
  * unavoidable for a caller that builds a `TopicSummary` inside the domain as well as for one that calls a
  * use case. Two entry points to one implementation is the shape that keeps both true; two implementations
  * would be a rule implemented once and checked twice.
  *
  * The rule: a per-topic message count computed from a partial set of partitions is **wrong** rather than
  * merely incomplete (`libs/kafka/PORT-INVARIANTS.md` §1), so an aggregate over a set with a hole in it
  * refuses. Size refuses the same way and independently, because a cluster that reports offsets and will not
  * report log directories can still be given a count.
  */
object PartitionAggregates {

  /** `None` if **any** partition's count is missing. */
  def messageCount(partitions: List[PartitionView]): Option[Long] = Aggregate.messageCount(partitions)

  /** `None` if any partition's size is missing — the same refusal, for the same reason. */
  def sizeBytes(partitions: List[PartitionView]): Option[Long] = Aggregate.sizeBytes(partitions)

  /** Lagging replicas, summed over every partition. Replicas, not partitions. */
  def outOfSyncReplicas(partitions: List[PartitionView]): Int = Aggregate.outOfSyncReplicas(partitions)

  def offlinePartitions(partitions: List[PartitionView]): Int = Aggregate.offlinePartitions(partitions)

  def replicationFactor(partitions: List[PartitionView]): Option[Int] =
    Aggregate.replicationFactor(partitions)
}
