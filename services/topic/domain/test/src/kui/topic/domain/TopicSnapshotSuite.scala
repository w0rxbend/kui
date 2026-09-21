package kui.topic.domain

import java.time.Instant

import org.scalacheck.Prop.forAll

import kui.kernel.search.SearchMode
import kui.kernel.{BrokerId, PartitionId, TopicName}
import kui.testkit.KuiSuite

/** The snapshot, and the one thing that must never happen to it: rows and index disagreeing. */
final class TopicSnapshotSuite extends KuiSuite {

  import TopicGenerators.*

  private val at: Instant = Instant.parse("2026-09-04T10:00:00Z")

  property("byNameIsConsistentWithTopics") {
    forAll(snapshot) { snap =>
      assertEquals(snap.byName.size, snap.topics.size)
      assert(snap.topics.forall(row => snap.get(row.name).contains(row)))
    }
  }

  property("theIndexContainsExactlyTheTopicNames") {
    forAll(snapshot) { snap =>
      // A blank query matches every indexed name, so this is the assertion that catches an index built over a
      // different list from the rows — the failure that would make search silently return topics the list
      // cannot show, or hide topics it can.
      val indexed = snap.index.search("", SearchMode.Plain).toSet

      assertEquals(indexed, snap.topics.map(_.name.value).toSet)
      assertEquals(snap.index.size, snap.size)
    }
  }

  test("anIncompleteTopicIsStillCounted") {
    val rows = Vector(
      TopicSummary.of(TopicName.unsafe("orders"), isInternal = false, Nil),
      TopicSummary.of(TopicName.unsafe("payments"), isInternal = false, Nil)
    )
    val snap =
      TopicSnapshot.of(rows, at, Map(TopicName.unsafe("payments") -> "KUI is not authorized to read this"))

    assertEquals(snap.size, 2, "`incomplete` explains a row, it does not remove it")
    assertEquals(snap.incompleteCount, 1)
    assert(snap.get(TopicName.unsafe("payments")).isDefined)
  }

  test("buildOrderIsTheIndexTieBreak") {
    val rows = Vector("zeta", "alpha", "mid").map(n => TopicSummary.of(TopicName.unsafe(n), false, Nil))

    assertEquals(TopicSnapshot.of(rows, at).index.search("", SearchMode.Plain), List("zeta", "alpha", "mid"))
  }

  test("theFirstSnapshotReportsNoRateAtAll") {
    // `None` and not zero on every row: nothing has been differenced yet. A zero here is the reading an
    // operator gets in the first minute after a restart, and it says the cluster is idle.
    val first = TopicSnapshot.of(Vector(rowWithOffsets("orders", 1_000L)), at)

    assertEquals(first.topics.map(_.produceRate), Vector(None))
  }

  test("aSecondSnapshotReportsTheDifferenceOverTheInterval") {
    // 400 records over 10 seconds is 40 a second. The absolute figure — 1 400 — is what a fold that forgot
    // to subtract would report, and it is a plausible-looking number on a screen that says "MSG/S".
    val first = TopicSnapshot.of(Vector(rowWithOffsets("orders", 1_000L)), at)
    val second =
      TopicSnapshot.of(Vector(rowWithOffsets("orders", 1_400L)), at.plusSeconds(10L), previous = Some(first))

    assertEquals(second.topics.map(_.produceRate), Vector(Some(40.0d)))
  }

  test("aTopicTheEarlierSnapshotNeverHeldHasNoRate") {
    // Created since the last scrape: there is no earlier observation of it, so it takes the same refusal a
    // first scrape does rather than being differenced against zero.
    val first = TopicSnapshot.of(Vector(rowWithOffsets("orders", 1_000L)), at)
    val second = TopicSnapshot.of(
      Vector(rowWithOffsets("orders", 1_400L), rowWithOffsets("sessions", 90L)),
      at.plusSeconds(10L),
      previous = Some(first)
    )

    assertEquals(second.byName(TopicName.unsafe("orders")).produceRate, Some(40.0d))
    assertEquals(second.byName(TopicName.unsafe("sessions")).produceRate, None)
  }

  test("theNamesIndexHoldsTheTopicsTheScrapeCouldNotDescribeToo") {
    // A topic KUI may see and may not describe has no list row and still exists. The drawer's tree is built
    // from this list, and a tree that omitted it would tell an operator the topic is gone.
    val snap = TopicSnapshot.of(
      Vector(TopicSummary.of(TopicName.unsafe("orders"), isInternal = false, Nil)),
      at,
      Map(TopicName.unsafe("audit") -> "KUI is not authorized to read this")
    )

    assertEquals(snap.names.map(_.value), Vector("audit", "orders"))
    assertEquals(snap.size, 1)
  }

  test("anEmptySnapshotIsAValueNotAnAbsence") {
    assertEquals(TopicSnapshot.empty(at).size, 0)
    assertEquals(TopicSnapshot.empty(at).scrapedAt, at)
  }

  /** One topic of one led partition whose log has been written to `latest`. */
  private def rowWithOffsets(name: String, latest: Long): TopicSummary =
    TopicSummary.of(
      TopicName.unsafe(name),
      isInternal = false,
      partitions = List(
        PartitionView
          .from(
            partition = PartitionId.unsafe(0),
            leader = Some(BrokerId.unsafe(1)),
            replicas = List(BrokerId.unsafe(1)),
            inSync = List(BrokerId.unsafe(1)),
            earliestOffset = Some(0L),
            latestOffset = Some(latest),
            sizeBytes = Some(1024L)
          )
          .fold(error => fail(s"the fixture partition should be valid: ${error.message}"), identity)
      )
    )
}
