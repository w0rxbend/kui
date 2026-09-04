package kui.topic.domain

import org.scalacheck.Prop.forAll

import kui.kernel.TopicName
import kui.testkit.KuiSuite

/** The row, and the refusal that is the whole point of it. */
final class TopicSummarySuite extends KuiSuite {

  import TopicGenerators.*

  private val name: TopicName = TopicName.unsafe("orders")

  test("messageCountIsNoneWhenAnyPartitionIsMissing") {
    val readable = validPartition(0, Some(1), List(1), List(1), earliest = Some(0L), latest = Some(10L))
    val offline = validPartition(1, None, List(1, 2), Nil)

    assertEquals(TopicSummary.of(name, isInternal = false, List(readable)).messageCount, Some(10L))
    assertEquals(
      TopicSummary.of(name, isInternal = false, List(readable, offline)).messageCount,
      None,
      "a count summed over a partial set of partitions is wrong, not incomplete"
    )
  }

  test("theOfflineCountExplainsTheMissingMessageCount") {
    val summary = TopicSummary.of(
      name,
      isInternal = false,
      List(
        validPartition(0, Some(1), List(1), List(1), earliest = Some(0L), latest = Some(10L)),
        validPartition(1, None, List(1, 2), Nil)
      )
    )

    assertEquals(summary.messageCount, None)
    assertEquals(summary.offlinePartitions, 1)
    assert(summary.hasOfflinePartitions)
  }

  test("sizeRefusesIndependentlyOfCount") {
    val counted = validPartition(0, Some(1), List(1), List(1), earliest = Some(0L), latest = Some(5L), size = None)
    val summary = TopicSummary.of(name, isInternal = false, List(counted))

    assertEquals(summary.messageCount, Some(5L))
    assertEquals(summary.sizeBytes, None)
  }

  property("underReplicatedIsDerivedNotStored") {
    forAll(partitions) { parts =>
      val summary = TopicSummary.of(name, isInternal = false, parts)

      assertEquals(summary.outOfSyncReplicas, parts.map(_.outOfSyncReplicas).sum)
      assertEquals(summary.isUnderReplicated, summary.outOfSyncReplicas > 0)
    }
  }

  test("replicationFactorIsNoneForNoPartitions") {
    assertEquals(TopicSummary.of(name, isInternal = false, Nil).replicationFactor, None)
    assertEquals(TopicSummary.of(name, isInternal = false, Nil).messageCount, Some(0L))
  }

  test("internalIsWhateverItWasConstructedWith") {
    // The union rule — Kafka's own flag, or a configured name prefix — belongs to the application layer
    // (DEVPLAN §10 D3). The domain applies no prefix rule of its own, so that there is exactly one place in
    // the product where that rule can be got wrong.
    val underscored = TopicName.unsafe("__kui_config")

    assert(!TopicSummary.of(underscored, isInternal = false, Nil).isInternal)
    assert(TopicSummary.of(TopicName.unsafe("orders"), isInternal = true, Nil).isInternal)
  }
}
