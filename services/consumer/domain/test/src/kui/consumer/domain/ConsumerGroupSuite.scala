package kui.consumer.domain

import kui.consumer.domain.fixtures.GroupFixtures
import kui.consumer.domain.fixtures.GroupFixtures.*
import kui.kernel.group.GroupState
import munit.FunSuite

/** The aggregate's own rules: what a summary says, when a group is moving, and when its offsets may be
  * changed.
  */
final class ConsumerGroupSuite extends FunSuite {

  test("a summary counts members, topics and partitions from the group itself") {
    val summary = stableGroup.summary

    assertEquals(summary.memberCount, 1)
    assertEquals(summary.topicCount, 1)
    assertEquals(summary.partitionCount, 2)
    assertEquals(summary.state, GroupState.Stable)
  }

  test("a summary's total lag sums only the partitions that have one") {
    // 100 - 90 and 100 - 100.
    assertEquals(stableGroup.summary.totalLag, Some(10L))
  }

  test("a group that has never committed has no total lag, not a total of zero") {
    assertEquals(neverCommittedGroup.summary.totalLag, None)
    assertEquals(neverCommittedGroup.lagTotal.excluded, 2)
    assertEquals(neverCommittedGroup.lagTotal.counted, 0)
  }

  test("a summary cannot know a pace from one observation") {
    assertEquals(stableGroup.summary.pace, None)
  }

  test("a group is rebalancing when the broker says so, or when a member is not where it is being sent") {
    assert(rebalancingGroup.isRebalancing)
    assert(GroupFixtures.group(state = GroupState.PreparingRebalance).isRebalancing)
    assert(!stableGroup.isRebalancing)
  }

  test("offsets may be changed only when the state permits it and there are no members") {
    assert(emptyGroup.permitsOffsetChange)
    assert(!stableGroup.permitsOffsetChange)
    // The case the two reference products disagree about: Empty, but a member has joined.
    assert(!GroupFixtures.group(state = GroupState.Empty, members = List(member("m-1", Set(0)))).permitsOffsetChange)
    assert(GroupFixtures.group(state = GroupState.Dead).permitsOffsetChange)
  }

  test("a refusal says which half failed, in words an operator can act on") {
    assert(emptyGroup.offsetChangeRefusal.isEmpty)
    assert(stableGroup.offsetChangeRefusal.exists(_.contains("stop its consumers")))
    assert(
      GroupFixtures
        .group(state = GroupState.PreparingRebalance)
        .offsetChangeRefusal
        .exists(_.contains("PREPARING_REBALANCE"))
    )
  }

  test("a topic subscription totals its own partitions and says how many it left out") {
    val subscription = TopicSubscription(Orders, List(state(0, Some(90L)), state(1, None)))

    assertEquals(subscription.totalLag, Some(10L))
    assertEquals(subscription.excludedPartitions, 1)
  }

  test("completeness distinguishes an empty answer from a refused question") {
    assert(GroupCompleteness.Complete.isComplete)
    assert(!GroupCompleteness.Complete.withoutMembers.isComplete)
    assert(!GroupCompleteness.Complete.excluding(partition(3), "no leader").isComplete)
  }
}
