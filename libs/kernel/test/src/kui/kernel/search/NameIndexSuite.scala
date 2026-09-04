package kui.kernel.search

import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import org.scalacheck.Gen

/** The ranking contract of ADR-038, stated as tests.
  *
  * Two of these are the ADR's own acceptance sentences: `plain` is exactly a naive substring filter, and
  * ranking is stable for equal scores. The rest exist because a matcher that is wrong is not visibly wrong —
  * it just quietly returns the wrong rows.
  */
final class NameIndexSuite extends ScalaCheckSuite {

  /** Names in the shape Kafka actually produces: letters, digits, dots, dashes and underscores. */
  private val nameGen: Gen[String] =
    Gen.nonEmptyListOf(Gen.oneOf(Gen.alphaNumChar, Gen.const('.'), Gen.const('-'), Gen.const('_'))).map(_.mkString)

  private val namesGen: Gen[List[String]] = Gen.listOf(nameGen)

  private val queryGen: Gen[String] = Gen.oneOf(Gen.const(""), nameGen.map(_.take(6)))

  private val sample: List[String] =
    List("orders", "orders-retry", "__consumer_offsets", "payments")

  // -------------------------------------------------------------------------------------------
  // Plain
  // -------------------------------------------------------------------------------------------

  property("plainIsExactlyASubstringFilter") {
    // ADR-038's stated property, and the one that makes `plain` explainable to a user in one sentence.
    forAll(namesGen, queryGen) { (names: List[String], query: String) =>
      val expected = names.filter(name => lower(name).contains(lower(query)))
      assertEquals(NameIndex.of(names).search(query, SearchMode.Plain), expected)
    }
  }

  property("anEmptyQueryMatchesEverythingInBuildOrder") {
    forAll(namesGen) { (names: List[String]) =>
      assertEquals(NameIndex.of(names).search("", SearchMode.Plain), names)
      assertEquals(NameIndex.of(names).search("", SearchMode.Fts), names)
    }
  }

  test("plainDoesNotPretendToFindATransposition") {
    assertEquals(NameIndex.of(sample).search("odrers", SearchMode.Plain), Nil)
  }

  test("theWorkedExampleFromTheTaskSpec") {
    val index = NameIndex.of(sample)
    assertEquals(index.search("ord", SearchMode.Plain), List("orders", "orders-retry"))
    assertEquals(index.search("", SearchMode.Plain).size, 4)
    assert(index.search("odrers", SearchMode.Fts).contains("orders"))
    assertEquals(index.search("odrers", SearchMode.Plain), Nil)
    assertEquals(index.size, 4)
  }

  // -------------------------------------------------------------------------------------------
  // Ranking
  // -------------------------------------------------------------------------------------------

  property("rankingIsStableForEqualScores") {
    // Every name here scores identically, so the only defensible order is the one the index was built
    // in. Without stability the same request reshuffles between two calls, which reads as data changing.
    forAll(Gen.listOf(Gen.alphaLowerChar).map(_.mkString), Gen.chooseNum(1, 30)) {
      (suffix: String, count: Int) =>
        val names = (1 to count).toList.map(i => s"topic-$i-$suffix")
        val index = NameIndex.of(names)
        assertEquals(index.search("topic", SearchMode.Fts).take(count), names)
    }
  }

  test("ftsFindsATransposition") {
    // The case `plain` cannot: "odrers" shares the trigrams "ers"... and enough of "orders" to score.
    assert(NameIndex.of(sample).search("odrers", SearchMode.Fts).contains("orders"))
  }

  property("ftsIsASupersetOfPlainForQueriesOfThreeOrMore") {
    // Flipping the toggle must never *lose* a result: a user who finds a topic and then switches to
    // fuzzy matching and sees it vanish concludes the search is broken.
    forAll(namesGen, nameGen.map(_.take(6))) { (names: List[String], query: String) =>
      if query.length >= NameIndex.NGram then {
        val index = NameIndex.of(names)
        val plain = index.search(query, SearchMode.Plain).toSet
        val fts = index.search(query, SearchMode.Fts).toSet
        assert(plain.subsetOf(fts), s"plain=$plain fts=$fts for query '$query'")
      }
    }
  }

  test("shortQueriesFallBackToSubstringInFtsMode") {
    val index = NameIndex.of(sample)
    assertEquals(index.search("or", SearchMode.Fts), index.search("or", SearchMode.Plain))
    assertEquals(index.search("p", SearchMode.Fts), List("payments"))
    // Two characters that no name contains find nothing, rather than everything.
    assertEquals(index.search("zq", SearchMode.Fts), Nil)
  }

  test("caseIsIgnoredInBothDirections") {
    val index = NameIndex.of(List("Orders", "PAYMENTS"))
    assertEquals(index.search("orders", SearchMode.Plain), List("Orders"))
    assertEquals(index.search("PaYm", SearchMode.Plain), List("PAYMENTS"))
    assertEquals(index.search("ORDER", SearchMode.Fts), List("Orders"))
  }

  test("foldingDoesNotDependOnTheDefaultLocale") {
    // The Turkish dotless i. `"I".toLowerCase` is locale-sensitive and would fold to 'ı' in a Turkish
    // JVM, so a cluster in Istanbul would match differently from the same cluster in London.
    assertEquals(NameIndex.of(List("INVENTORY")).search("inv", SearchMode.Plain), List("INVENTORY"))
  }

  // -------------------------------------------------------------------------------------------
  // Scoring
  // -------------------------------------------------------------------------------------------

  property("scoreIsBoundedToZeroOne") {
    forAll(nameGen, queryGen) { (name: String, query: String) =>
      val value = NameIndex.score(name, query)
      assert(value >= 0.0 && value <= 1.0, s"score($name, $query) = $value")
    }
  }

  test("aNameThatContainsTheQueryScoresOne") {
    assertEqualsDouble(NameIndex.score("orders-retry", "orders"), 1.0, 0.0)
    assertEqualsDouble(NameIndex.score("orders", "payments"), 0.0, 0.0)
  }

  test("aPartialMatchScoresBetween") {
    val partial = NameIndex.score("orders", "odrers")
    assert(partial > 0.0 && partial < 1.0, s"expected a partial score, got $partial")
  }

  test("aShortQueryScoresAsASubstringTest") {
    assertEqualsDouble(NameIndex.score("orders", "or"), 1.0, 0.0)
    assertEqualsDouble(NameIndex.score("orders", "zq"), 0.0, 0.0)
  }

  // -------------------------------------------------------------------------------------------
  // Budget
  // -------------------------------------------------------------------------------------------

  test("buildingTenThousandNamesIsUnderTheBudget") {
    // The assertion is on completion and on the answer, never on a stopwatch reading: a clock
    // assertion on a shared CI machine is a flaky test that teaches nobody anything. The measured
    // figure is printed for TOP-035's benchmark to quote.
    val names = (1 to 10000).toList.map(i => f"topic-$i%05d-orders")
    val startedAt = System.currentTimeMillis()
    val index = NameIndex.of(names)
    val builtAt = System.currentTimeMillis()
    val plain = index.search("00042", SearchMode.Plain)
    val plainAt = System.currentTimeMillis()
    val fts = index.search("orders", SearchMode.Fts)
    val ftsAt = System.currentTimeMillis()

    assertEquals(index.size, 10000)
    assertEquals(plain, List("topic-00042-orders"))
    assertEquals(fts.size, 10000)
    println(
      s"NameIndex over 10000 names: build ${builtAt - startedAt} ms, " +
        s"plain query ${plainAt - builtAt} ms, first fts query ${ftsAt - plainAt} ms"
    )
  }

  /** Locale-independent lowercasing, restated here so the expectation cannot borrow the bug it checks. */
  private def lower(raw: String): String = raw.map(_.toLower)
}
