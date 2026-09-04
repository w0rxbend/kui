package kui.consumer.domain

import kui.kernel.{BrokerId, TopicName, TopicPartition}

/** Which broker coordinates a group.
  *
  * The domain's own shape rather than `libs/kafka`'s: rule A1 forbids the domain any knowledge of the Kafka
  * client, and the two types being separate is what lets the port change without the aggregate changing.
  */
final case class GroupCoordinatorRef(id: BrokerId, host: String, port: Int)

object GroupCoordinatorRef {
  given CanEqual[GroupCoordinatorRef, GroupCoordinatorRef] = CanEqual.derived
}

/** What one member holds, and where the coordinator is moving it to.
  *
  * `targetPartitions` is `Some` only when the target differs from what the member holds now, which is the
  * definition of "this member is mid-rebalance" and is what the detail page's stale badge keys on (DC-H10). A
  * settled KIP-848 member reports a target on every describe, equal to its assignment; treating that as a
  * rebalance would put a stale badge on every healthy group.
  */
final case class GroupMember(
    memberId: String,
    groupInstanceId: Option[String],
    clientId: String,
    host: String,
    partitions: Set[TopicPartition],
    targetPartitions: Option[Set[TopicPartition]]
) {

  def isRebalancing: Boolean = targetPartitions.exists(_ != partitions)

  def topics: Set[TopicName] = partitions.map(_.topic)
}

object GroupMember {
  given CanEqual[GroupMember, GroupMember] = CanEqual.derived
  given Ordering[GroupMember] = Ordering.by(_.memberId)
}
