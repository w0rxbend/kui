package kui.kernel.search

import munit.FunSuite

final class SearchModeSuite extends FunSuite {

  test("wireSpellingsAreStable") {
    // A table rather than a round trip: a round trip would still pass if both halves were renamed
    // together, and the thing under test is the spelling a bookmarked URL already contains.
    assertEquals(SearchMode.Plain.wire, "plain")
    assertEquals(SearchMode.Fts.wire, "fts")
    assertEquals(SearchMode.values.toList.map(_.wire), List("plain", "fts"))
  }

  test("theDefaultIsPlain") {
    assertEquals(SearchMode.Default, SearchMode.Plain)
  }

  test("fromWireRejectsAnythingElse") {
    assertEquals(SearchMode.fromWire("plain"), Some(SearchMode.Plain))
    assertEquals(SearchMode.fromWire("fts"), Some(SearchMode.Fts))
    // Never a default. An unknown mode becomes a 400 at the edge, rather than a page of results
    // produced by a rule nobody asked for.
    assertEquals(SearchMode.fromWire("FTS"), None)
    assertEquals(SearchMode.fromWire("fuzzy"), None)
    assertEquals(SearchMode.fromWire(""), None)
  }

  test("everyModeRoundTripsThroughItsWireName") {
    SearchMode.values.foreach(mode => assertEquals(SearchMode.fromWire(mode.wire), Some(mode)))
  }
}
