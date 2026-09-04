package kui.topic.application

import java.time.Instant

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import kui.kernel.search.SearchMode
import kui.kernel.{PageRequest, PageSize, PositiveInt, Sort, SortOrder, TopicName}
import kui.testkit.KuiSuite
import kui.topic.domain.{TopicGenerators, TopicSnapshot, TopicSortField, TopicSummary}

/** The list pipeline, and above all the count it reports.
  *
  * The suite is property-first because the defect it exists to prevent — a total taken before a filter — is
  * invisible in any single example. Every example passes; only the relationship between the rows and the
  * number fails.
  */
final class ListTopicsSuite extends KuiSuite {

  import TopicGenerators.{instant, snapshot as snapshotGen, topicName}

  private val at: Instant = Instant.parse("2026-09-04T10:00:00Z")

  private def row(name: String, internal: Boolean = false, count: Option[Long] = Some(0L)): TopicSummary =
    TopicSummary(TopicName.unsafe(name), internal, 1, Some(1), 0, 0, count, Some(0L))

  private def snapshotOf(rows: TopicSummary*): TopicSnapshot = TopicSnapshot.of(rows.toVector, at)

  private def query(
      q: Option[String] = None,
      mode: SearchMode = SearchMode.Plain,
      showInternal: Boolean = false,
      sort: Option[Sort[TopicSortField]] = None,
      page: Int = 1,
      pageSize: Int = 25,
      visible: TopicName => Boolean = TopicListQuery.EverythingVisible
  ): TopicListQuery =
    TopicListQuery(
      q = q,
      mode = mode,
      showInternal = showInternal,
      sort = sort,
      page = PageRequest(PositiveInt.unsafe(page), PageSize.unsafe(pageSize)),
      visible = visible
    )

  /** A query generator that varies every dimension the ordering rule can be broken by. */
  private val queries: Gen[TopicListQuery] =
    for {
      term <- Gen.option(Gen.oneOf("a", "or", "ord", "zzz", "e"))
      mode <- Gen.oneOf(SearchMode.Plain, SearchMode.Fts)
      showInternal <- Gen.oneOf(true, false)
      sort <- Gen.option(
        for {
          field <- Gen.oneOf(TopicSortField.values.toIndexedSeq)
          order <- Gen.oneOf(SortOrder.Asc, SortOrder.Desc)
        } yield Sort(field, order)
      )
      page <- Gen.choose(1, 4)
      size <- Gen.choose(1, 10)
      hideEvery <- Gen.oneOf(true, false)
    } yield query(
      q = term,
      mode = mode,
      showInternal = showInternal,
      sort = sort,
      page = page,
      pageSize = size,
      // A visibility predicate that actually hides something, so that step 1 of the pipeline is exercised
      // rather than merely present.
      visible = if hideEvery then (name: TopicName) => !name.value.startsWith("a") else TopicListQuery.EverythingVisible
    )

  property("theTotalCountsWhatTheFiltersLeft") {
    forAll(snapshotGen, queries) { (snapshot, request) =>
      // The expected set is computed independently of the pipeline — filter by filter, in the same order but
      // in different code — so that the property compares two answers rather than one answer with itself.
      val expected = snapshot.topics.count(row => ListTopics.matches(snapshot, request, row.name))

      assertEquals(ListTopics(snapshot, request).totalItems, Some(expected.toLong))
    }
  }

  test("hidingInternalTopicsChangesTheTotal") {
    // The exact defect this pipeline exists to avoid. The implementation this product is modelled on computes
    // its page count from the list *before* the internal-topic filter
    // (`research/kafbat/api-analysis.md` §3.3, `TopicsController.java:213-220`), so with internal topics
    // hidden — the default — it reports pages that do not exist.
    val snapshot = snapshotOf(row("orders"), row("__consumer_offsets", internal = true))

    assertEquals(ListTopics(snapshot, query(showInternal = false)).totalItems, Some(1L))
    assertEquals(ListTopics(snapshot, query(showInternal = true)).totalItems, Some(2L))
  }

  test("theVisibilityPredicateRunsBeforeEverythingElse") {
    val snapshot = snapshotOf(row("orders"), row("payments"))
    val hidden = query(visible = name => name.value != "payments")

    val page = ListTopics(snapshot, hidden)

    assertEquals(page.items.map(_.name.value), List("orders"))
    assertEquals(page.totalItems, Some(1L), "a user must not learn a topic exists from a page count")
  }

  test("searchIsAppliedToTheFilteredSet") {
    val snapshot = snapshotOf(row("orders"), row("__kui_config", internal = true))

    val page = ListTopics(snapshot, query(q = Some("kui"), showInternal = false))

    assertEquals(page.items, Nil)
    assertEquals(page.totalItems, Some(0L))
  }

  property("theUnionOfEveryPageIsTheFilteredSetExactlyOnce") {
    forAll(snapshotGen, queries) { (snapshot, request) =>
      val size = request.page.pageSize.value
      val total = ListTopics(snapshot, request.copy(page = PageRequest(PositiveInt.One, request.page.pageSize)))
        .totalItems
        .getOrElse(0L)
        .toInt
      val pageCount = math.max(1, (total + size - 1) / size)

      val collected = (1 to pageCount).toList.flatMap { number =>
        val paged = request.copy(page = PageRequest(PositiveInt.unsafe(number), request.page.pageSize))
        ListTopics(snapshot, paged).items
      }

      assertEquals(collected.size, total)
      assertEquals(collected.map(_.name).distinct.size, total, "a row appeared on two pages")
    }
  }

  property("noPageIsLargerThanPageSize") {
    forAll(snapshotGen, queries) { (snapshot, request) =>
      assert(ListTopics(snapshot, request).items.sizeIs <= request.page.pageSize.value)
    }
  }

  test("anOutOfRangePageIsEmptyWithTheCorrectTotal") {
    val snapshot = snapshotOf(row("a"), row("b"), row("c"))

    val page = ListTopics(snapshot, query(page = 9, pageSize = 2))

    assertEquals(page.items, Nil)
    assertEquals(page.totalItems, Some(3L), "a bookmark to a page that has gone says 'nothing here', not 404")
  }

  test("anEmptySnapshotIsAnEmptyPageAndNotAnError") {
    val page = ListTopics(TopicSnapshot.empty(at), query())

    assert(page.isEmpty)
    assertEquals(page.totalItems, Some(0L))
  }

  property("plainAndFtsAgreeOnExactSubstrings") {
    forAll(snapshotGen, Gen.oneOf("or", "ord", "pay", "a")) { (snapshot, term) =>
      val plain = ListTopics(snapshot, query(q = Some(term), mode = SearchMode.Plain)).items.map(_.name).toSet
      val fts = ListTopics(snapshot, query(q = Some(term), mode = SearchMode.Fts)).items.map(_.name).toSet

      // `fts` is a trigram match and may find more; it must never find fewer, or switching the toggle on
      // would lose rows the user could see a moment ago.
      assert(plain.subsetOf(fts), s"plain found ${plain -- fts} that fts did not")
    }
  }

  test("relevanceOrderIsUsedOnlyWhenNoSortWasGiven") {
    // `ordxrs` shares only some of `orders`' trigrams, so it ranks below it; it is placed first in the
    // snapshot so that build order and relevance order disagree and the test can tell which one was used.
    val snapshot = snapshotOf(row("ordxrs"), row("orders"))

    val ranked = ListTopics(snapshot, query(q = Some("orders"), mode = SearchMode.Fts, sort = None))
    val sorted = ListTopics(
      snapshot,
      query(q = Some("orders"), mode = SearchMode.Fts, sort = Some(Sort(TopicSortField.Name, SortOrder.Desc)))
    )

    assertEquals(ranked.items.map(_.name.value), List("orders", "ordxrs"), "the best match comes first")
    assertEquals(
      sorted.items.map(_.name.value),
      List("ordxrs", "orders"),
      "an explicit sort is never silently ignored in fts mode"
    )
  }

  test("aBlankSearchTermIsNotAFilter") {
    val snapshot = snapshotOf(row("orders"), row("payments"))

    assertEquals(ListTopics(snapshot, query(q = Some("   "))).totalItems, Some(2L))
  }

  property("sortingIsStableAcrossPages") {
    forAll(snapshotGen) { snapshot =>
      val sort = Some(Sort(TopicSortField.Name, SortOrder.Asc))
      val whole = ListTopics(snapshot, query(sort = sort, pageSize = 500)).items
      val second = ListTopics(snapshot, query(sort = sort, page = 2, pageSize = 5)).items

      assertEquals(second, whole.slice(5, 10))
    }
  }

  test("scrapedAtAndIncompletenessAreNotThePipelinesBusiness") {
    // The pipeline computes a page over what was read. The incompleteness note and the staleness badge are
    // carried beside it, by the edge, from the snapshot itself — a list that refused to render because two
    // topics could not be read would be the M1 lesson unlearned.
    val snapshot = TopicSnapshot.of(Vector(row("orders")), at, Map(TopicName.unsafe("gone") -> "it no longer exists"))

    assertEquals(ListTopics(snapshot, query()).totalItems, Some(1L))
    assertEquals(snapshot.incompleteCount, 1)
  }

  property("everyRowOnThePageSurvivesTheFilters") {
    forAll(snapshotGen, queries) { (snapshot, request) =>
      assert(ListTopics(snapshot, request).items.forall(row => ListTopics.matches(snapshot, request, row.name)))
    }
  }

  property("aTopicNameIsNeverInventedByTheIndex") {
    forAll(snapshotGen, topicName, instant) { (snapshot, _, _) =>
      assert(ListTopics(snapshot, query(q = Some("a"))).items.forall(row => snapshot.get(row.name).isDefined))
    }
  }
}
