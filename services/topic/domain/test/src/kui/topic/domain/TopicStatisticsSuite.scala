package kui.topic.domain

import java.time.Instant

import kui.kernel.{BrokerId, PartitionId, TopicName}
import kui.testkit.KuiSuite

/** The cluster-wide totals, and which of them each kind of gap takes away.
  *
  * Two cases carry the whole design: a topic the scrape could not describe leaves the *count* intact and
  * removes both sums, and a topic whose size nobody could read removes only the size. If either of those
  * collapsed into the other, a screen would either understate the cluster or refuse a figure it has.
  */
final class TopicStatisticsSuite extends KuiSuite {

  private val at: Instant = Instant.parse("2026-09-04T10:00:00Z")

  private def row(name: String, partitions: Int, size: Option[Long]): TopicSummary =
    TopicSummary.of(
      TopicName.unsafe(name),
      isInternal = false,
      partitions = (0 until partitions).toList.map(id => partition(id, size))
    )

  test("aCompleteScrapeAnswersAllThree") {
    val snapshot = TopicSnapshot.of(Vector(row("orders", 12, Some(1024L)), row("payments", 3, Some(64L))), at)

    assertEquals(
      TopicStatistics.of(snapshot),
      TopicStatistics(
        topicCount = 2,
        partitionCount = Some(15L),
        sizeBytes = Some(12_480L),
        incompleteTopics = 0
      )
    )
  }

  test("aTopicTheScrapeCouldNotDescribeIsCountedAndRemovesBothSums") {
    // It was listed, so KUI knows it exists and the count says so. Nothing knows its partitions or its
    // bytes, so a sum over the topics that *did* answer would be a number that looks measured and is too
    // small — which is the shape of understatement an operator acts on without noticing.
    val snapshot = TopicSnapshot.of(
      Vector(row("orders", 12, Some(1024L))),
      at,
      Map(TopicName.unsafe("audit") -> "KUI may not describe this topic")
    )

    val statistics = TopicStatistics.of(snapshot)

    assertEquals(statistics.topicCount, 2)
    assertEquals(statistics.incompleteTopics, 1)
    assertEquals(statistics.partitionCount, None)
    assertEquals(statistics.sizeBytes, None)
  }

  test("aSizeNobodyCouldReadCostsTheSizeTotalAndNothingElse") {
    // The independent refusal. A cluster that answers `describeTopics` and refuses `describeLogDirs` shows
    // a partition total beside an em dash for storage, and losing the figure it did give would have no
    // upside at all.
    val snapshot = TopicSnapshot.of(Vector(row("orders", 12, Some(1024L)), row("payments", 3, None)), at)

    val statistics = TopicStatistics.of(snapshot)

    assertEquals(statistics.topicCount, 2)
    assertEquals(statistics.partitionCount, Some(15L))
    assertEquals(statistics.sizeBytes, None)
  }

  test("anEmptyClusterIsZeroAndNotAbsent") {
    // Zero is the right answer here and only here: the scrape completed and found nothing. The refusals
    // above are what stop this zero from being confused with them.
    assertEquals(
      TopicStatistics.of(TopicSnapshot.empty(at)),
      TopicStatistics(topicCount = 0, partitionCount = Some(0L), sizeBytes = Some(0L), incompleteTopics = 0)
    )
  }

  test("theCountIsTheNamesIndexesOwnCount") {
    // One definition of "how many topics are there", used by the statistics document and by the drawer's
    // names index, so the tree's rows cannot add up to something the statistics region contradicts.
    val snapshot = TopicSnapshot.of(
      Vector(row("orders", 1, Some(1L)), row("payments", 1, Some(1L))),
      at,
      Map(TopicName.unsafe("audit") -> "unreadable")
    )

    assertEquals(TopicStatistics.of(snapshot).topicCount, snapshot.names.size)
    assertEquals(snapshot.names.map(_.value), Vector("audit", "orders", "payments"))
  }

  private def partition(id: Int, size: Option[Long]): PartitionView =
    PartitionView
      .from(
        partition = PartitionId.unsafe(id),
        leader = Some(BrokerId.unsafe(1)),
        replicas = List(BrokerId.unsafe(1)),
        inSync = List(BrokerId.unsafe(1)),
        earliestOffset = Some(0L),
        latestOffset = Some(100L),
        sizeBytes = size
      )
      .fold(error => fail(s"the fixture partition should be valid: ${error.message}"), identity)
}
