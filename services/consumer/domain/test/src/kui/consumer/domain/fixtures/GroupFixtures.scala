package kui.consumer.domain
package fixtures

import java.time.Instant

import kui.kernel.group.{GroupProtocol, GroupState}
import kui.kernel.{BrokerId, GroupId, Offset, PartitionId, TopicName, TopicPartition}

/** The groups every suite in this context builds on.
  *
  * They live in the domain's test module rather than in `libs/testkit` because rule A5 forbids a library
  * depending on a service, and a generator of `ConsumerGroup` is exactly that. The application's suites
  * depend on this module for them.
  */
object GroupFixtures {

  val At: Instant = Instant.parse("2026-01-01T12:00:00Z")

  val Orders: TopicName = TopicName.unsafe("orders")

  def partition(n: Int): TopicPartition = TopicPartition(Orders, PartitionId.unsafe(n))

  def state(
      n: Int,
      committed: Option[Long],
      begin: Option[Long] = Some(0L),
      end: Option[Long] = Some(100L),
      memberId: Option[String] = None
  ): PartitionState = {
    val committedOffset = committed.map(Offset.unsafe)
    val beginOffset = begin.map(Offset.unsafe)
    val endOffset = end.map(Offset.unsafe)

    PartitionState(
      partition = PartitionId.unsafe(n),
      committed = committedOffset,
      begin = beginOffset,
      end = endOffset,
      memberId = memberId,
      host = memberId.map(_ => "10.0.0.7"),
      lag = LagMath.lagOf(committedOffset, beginOffset, endOffset)
    )
  }

  def member(id: String, holds: Set[Int], moving: Option[Set[Int]] = None): GroupMember =
    GroupMember(
      memberId = id,
      groupInstanceId = None,
      clientId = "client-1",
      host = "10.0.0.7",
      partitions = holds.map(partition),
      targetPartitions = moving.map(_.map(partition))
    )

  def group(
      id: String = "orders-consumer",
      state: GroupState = GroupState.Stable,
      members: List[GroupMember] = Nil,
      partitions: List[PartitionState] = Nil,
      completeness: GroupCompleteness = GroupCompleteness.Complete
  ): ConsumerGroup =
    ConsumerGroup(
      groupId = GroupId.unsafe(id),
      state = state,
      protocol = GroupProtocol.Classic,
      isSimple = false,
      partitionAssignor = "range",
      members = members,
      coordinator = Some(GroupCoordinatorRef(BrokerId.unsafe(1), "broker-1", 9092)),
      subscriptions = if partitions.isEmpty then Nil else List(TopicSubscription(Orders, partitions)),
      completeness = completeness,
      observedAt = At
    )

  /** The four shapes the milestone keeps coming back to. */
  val stableGroup: ConsumerGroup =
    group(
      state = GroupState.Stable,
      members = List(member("m-1", Set(0, 1))),
      partitions = List(state(0, Some(90L), memberId = Some("m-1")), state(1, Some(100L), memberId = Some("m-1")))
    )

  val emptyGroup: ConsumerGroup =
    group(id = "audit", state = GroupState.Empty, partitions = List(state(0, Some(10L))))

  val neverCommittedGroup: ConsumerGroup =
    group(id = "fresh", state = GroupState.Empty, partitions = List(state(0, None), state(1, None)))

  val rebalancingGroup: ConsumerGroup =
    group(
      id = "moving",
      state = GroupState.Stable,
      members = List(member("m-1", Set(0), moving = Some(Set(0, 1)))),
      partitions = List(state(0, Some(50L), memberId = Some("m-1")))
    )
}
