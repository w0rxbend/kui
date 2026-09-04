package kui.topic.application

import org.scalacheck.Prop.forAll

import kui.testkit.KuiSuite
import kui.topic.domain.{PartitionView, TopicGenerators}

/** The arithmetic, and the refusal that is the point of it. */
final class PartitionAggregatesSuite extends KuiSuite {

  import TopicGenerators.*

  private def readable(id: Int, from: Long, to: Long, size: Option[Long] = Some(10L)): PartitionView =
    validPartition(id, Some(1), List(1, 2), List(1, 2), earliest = Some(from), latest = Some(to), size = size)

  private def offline(id: Int): PartitionView = validPartition(id, None, List(1, 2), Nil)

  property("messageCountIsNoneIfAnyPartitionIsMissing") {
    forAll(partitions) { parts =>
      val expected = if parts.exists(_.messageCount.isEmpty) then None else Some(parts.flatMap(_.messageCount).sum)

      assertEquals(PartitionAggregates.messageCount(parts), expected)
    }
  }

  property("messageCountSumsWhenEveryPartitionIsPresent") {
    forAll(partitions) { parts =>
      val complete = parts.filter(_.messageCount.isDefined)

      assertEquals(PartitionAggregates.messageCount(complete), Some(complete.flatMap(_.messageCount).sum))
    }
  }

  test("anEmptyPartitionContributesZeroNotNothing") {
    // `min == max` is a count of zero. Confusing it with an absent count is how a screen tells an operator a
    // topic is empty when in fact KUI could not read it.
    assertEquals(PartitionAggregates.messageCount(List(readable(0, 42L, 42L))), Some(0L))
    assertEquals(PartitionAggregates.messageCount(List(readable(0, 42L, 42L), readable(1, 0L, 5L))), Some(5L))
  }

  test("sizeRefusesIndependentlyOfCount") {
    val parts = List(readable(0, 0L, 5L, size = None), readable(1, 0L, 3L, size = Some(9L)))

    assertEquals(PartitionAggregates.messageCount(parts), Some(8L))
    assertEquals(PartitionAggregates.sizeBytes(parts), None)
  }

  test("outOfSyncCountsReplicasNotPartitions") {
    // The reference product's own column is "out of sync replicas". Counting partitions instead is an easy
    // mistake and an invisible one: on a three-replica topic the two numbers differ by a factor of two.
    val lagging = validPartition(0, Some(1), List(1, 2, 3), List(1))
    val healthy = validPartition(1, Some(1), List(1, 2, 3), List(1, 2, 3))

    assertEquals(PartitionAggregates.outOfSyncReplicas(List(lagging, healthy)), 2)
  }

  test("offlinePartitionsCountsLeaderlessOnes") {
    assertEquals(PartitionAggregates.offlinePartitions(List(offline(0), readable(1, 0L, 1L), offline(2))), 2)
  }

  test("replicationFactorIsNoneForNoPartitions") {
    assertEquals(PartitionAggregates.replicationFactor(Nil), None)
    assertEquals(PartitionAggregates.replicationFactor(List(readable(0, 0L, 1L))), Some(2))
  }

  property("theUseCaseFacadeAgreesWithTheDomain") {
    // Two entry points, one implementation. If they ever diverge, the refusal rule is being enforced in one
    // place and merely intended in the other.
    forAll(partitions) { parts =>
      assertEquals(PartitionAggregates.messageCount(parts), kui.topic.domain.Aggregate.messageCount(parts))
      assertEquals(PartitionAggregates.sizeBytes(parts), kui.topic.domain.Aggregate.sizeBytes(parts))
    }
  }
}
