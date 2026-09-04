package kui.topic.domain

import java.time.Instant

import org.scalacheck.Prop.forAll

import kui.kernel.TopicName
import kui.kernel.search.SearchMode
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
    val snap = TopicSnapshot.of(rows, at, Map(TopicName.unsafe("payments") -> "KUI is not authorized to read this"))

    assertEquals(snap.size, 2, "`incomplete` explains a row, it does not remove it")
    assertEquals(snap.incompleteCount, 1)
    assert(snap.get(TopicName.unsafe("payments")).isDefined)
  }

  test("buildOrderIsTheIndexTieBreak") {
    val rows = Vector("zeta", "alpha", "mid").map(n => TopicSummary.of(TopicName.unsafe(n), false, Nil))

    assertEquals(TopicSnapshot.of(rows, at).index.search("", SearchMode.Plain), List("zeta", "alpha", "mid"))
  }

  test("anEmptySnapshotIsAValueNotAnAbsence") {
    assertEquals(TopicSnapshot.empty(at).size, 0)
    assertEquals(TopicSnapshot.empty(at).scrapedAt, at)
  }
}
