package kui.topic.application

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import kui.kernel.{Sort, SortOrder, TopicName}
import kui.testkit.KuiSuite
import kui.topic.domain.{TopicSortField, TopicSummary}

/** The orderings, and the two rules that apply to all of them. */
final class TopicOrderingSuite extends KuiSuite {

  private def row(
      name: String,
      partitions: Int = 1,
      replicationFactor: Option[Int] = Some(1),
      outOfSync: Int = 0,
      count: Option[Long] = Some(0L),
      size: Option[Long] = Some(0L)
  ): TopicSummary =
    TopicSummary(
      name = TopicName.unsafe(name),
      isInternal = false,
      partitionCount = partitions,
      replicationFactor = replicationFactor,
      outOfSyncReplicas = outOfSync,
      offlinePartitions = 0,
      messageCount = count,
      sizeBytes = size
    )

  private def sortedBy(field: TopicSortField, order: SortOrder)(rows: List[TopicSummary]): List[String] =
    rows.sorted(using TopicOrdering.of(Sort(field, order))).map(_.name.value)

  test("missingValuesSortLastAscending") {
    val rows = List(row("b", count = None), row("a", count = Some(5L)), row("c", count = Some(1L)))

    assertEquals(sortedBy(TopicSortField.MessageCount, SortOrder.Asc)(rows), List("c", "a", "b"))
  }

  test("missingValuesSortLastDescending") {
    // The rule that is easy to get right in one direction and wrong in the other: a naive `reverse` of the
    // ascending order would float the rows with no data to the top, which is a screenful of em dashes above
    // the rows the user actually asked to see.
    val rows = List(row("b", count = None), row("a", count = Some(5L)), row("c", count = Some(1L)))

    assertEquals(sortedBy(TopicSortField.MessageCount, SortOrder.Desc)(rows), List("a", "c", "b"))
  }

  test("missingSizesAndMissingReplicationFactorsSortLastToo") {
    val bySize = List(row("b", size = None), row("a", size = Some(10L)))
    val byFactor = List(row("b", replicationFactor = None), row("a", replicationFactor = Some(3)))

    assertEquals(sortedBy(TopicSortField.Size, SortOrder.Desc)(bySize), List("a", "b"))
    assertEquals(sortedBy(TopicSortField.ReplicationFactor, SortOrder.Desc)(byFactor), List("a", "b"))
  }

  property("theTiebreakIsTheName") {
    val names = Gen.listOfN(6, Gen.identifier).map(_.distinct)

    forAll(names) { values =>
      // Every row has the same size, so only the tiebreak decides. Shuffling the input must not change the
      // output: without a tiebreak two equal rows swap places between two identical requests, and page 2 of
      // a "stable" list would show a row that also appeared on page 1.
      val rows = values.map(name => row(name, size = Some(7L)))
      val ordering = TopicOrdering.of(Sort(TopicSortField.Size, SortOrder.Desc))

      assertEquals(rows.sorted(using ordering).map(_.name.value), values.sorted)
      assertEquals(rows.reverse.sorted(using ordering).map(_.name.value), values.sorted)
    }
  }

  test("everySortFieldHasAnOrdering") {
    // An exhaustive match over the enum, so that adding a field fails to compile here rather than silently
    // sorting by nothing.
    val rows = List(row("b"), row("a"))

    TopicSortField.values.foreach { field =>
      val ascending = rows.sorted(using TopicOrdering.of(Sort(field, SortOrder.Asc))).map(_.name.value)
      assertEquals(ascending, List("a", "b"), s"$field")
    }
  }

  test("sortingByNameHonoursTheDirection") {
    val rows = List(row("a"), row("b"))

    assertEquals(sortedBy(TopicSortField.Name, SortOrder.Desc)(rows), List("b", "a"))
  }
}
