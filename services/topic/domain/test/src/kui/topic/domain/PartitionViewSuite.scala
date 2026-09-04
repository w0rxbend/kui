package kui.topic.domain

import org.scalacheck.Prop.forAll

import kui.kernel.ValidationError
import kui.testkit.KuiSuite

/** The partition, and the four states a real broker produces that look like bugs and are not — plus the two
  * that really are bugs and are refused here rather than rendered.
  */
final class PartitionViewSuite extends KuiSuite {

  import TopicGenerators.*

  test("aLeaderlessPartitionHasNoLeaderAndNoCount") {
    val offline = validPartition(id = 0, leader = None, replicas = List(1, 2), inSync = List())

    assert(offline.isLeaderless)
    assertEquals(offline.leader, None)
    assertEquals(offline.messageCount, None)
    assert(offline.replicas.forall(!_.isLeader))
  }

  test("aLeaderlessPartitionMayNotCarryOffsets") {
    val invented =
      partitionOf(id = 0, leader = None, replicas = List(1), inSync = Nil, earliest = Some(0L), latest = Some(9L))

    assert(invented.isLeft, "offsets on a leaderless partition must be refused, not rendered")
  }

  property("isrIsASubsetOfReplicas") {
    forAll(brokerId) { extra =>
      val replicas = List(1, 2)
      val result = partitionOf(
        id = 0,
        leader = Some(1),
        replicas = replicas,
        inSync = replicas :+ extra.value
      )

      if replicas.contains(extra.value) then assert(result.isRight)
      else
        assertEquals(
          result.left.toOption.map(_.fieldName),
          Some("partition[0]"),
          "an in-sync set larger than the replica set renders as '3 of 2 in sync' and is a broker bug"
        )
    }
  }

  property("exactlyOneReplicaIsLeaderWhenThereIsALeader") {
    forAll(partition(0)) { view =>
      view.leader match {
        case Some(id) =>
          assertEquals(view.replicas.count(_.isLeader), 1)
          assertEquals(view.replicas.find(_.isLeader).map(_.broker), Some(id))
        case None => assertEquals(view.replicas.count(_.isLeader), 0)
      }
    }
  }

  test("aLeaderThatIsNotAReplicaIsRefused") {
    val result = partitionOf(id = 0, leader = Some(7), replicas = List(1, 2), inSync = List(1))

    assert(result.isLeft)
  }

  property("messageCountIsNeverNegative") {
    forAll(partition(0)) { view =>
      assert(view.messageCount.forall(_ >= 0L))
    }
  }

  test("anEmptyPartitionCountsZeroRatherThanNothing") {
    val empty = validPartition(0, Some(1), List(1), List(1), earliest = Some(42L), latest = Some(42L))

    assertEquals(empty.messageCount, Some(0L))
  }

  test("offsetsRunningBackwardsAreRefused") {
    val result = partitionOf(0, Some(1), List(1), List(1), earliest = Some(9L), latest = Some(4L))

    assert(result.exists(_ => false) || result.isLeft)
    assert(result.left.toOption.exists(_.isInstanceOf[ValidationError.Invariant]))
  }

  test("aDuplicatedReplicaIsRefused") {
    assert(partitionOf(0, Some(1), List(1, 1), List(1)).isLeft)
  }

  test("outOfSyncCountsReplicasNotPartitions") {
    val lagging = validPartition(0, Some(1), List(1, 2, 3), List(1))

    assertEquals(lagging.outOfSyncReplicas, 2)
    assertEquals(lagging.inSyncReplicas.map(_.broker.value), List(1))
  }
}
