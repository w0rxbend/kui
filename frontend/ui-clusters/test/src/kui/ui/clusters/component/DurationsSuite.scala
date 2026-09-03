package kui.ui.clusters.component

import munit.FunSuite

class DurationsSuite extends FunSuite {

  test("theLargestWholeUnitThatLosesNoPrecision") {
    val cases = List(
      "0" -> "0 ms",
      "1" -> "1 ms",
      "999" -> "999 ms",
      "1000" -> "1 s",
      "1500" -> "1500 ms",
      "60000" -> "1 min",
      "3600000" -> "1 h",
      "86400000" -> "1 d",
      "604800000" -> "7 d"
    )
    cases.foreach((raw, expected) => assertEquals(Durations.fromMillis(raw), Some(expected), raw))
  }

  test("minusOneIsWhatKafkaCallsUnlimited") {
    assertEquals(Durations.fromMillis("-1"), Some(Durations.Unlimited))
  }

  test("aValueThatIsNotANumberIsNotADuration") {
    // The caller falls through to showing it verbatim. Formatting "none" as `0 ms` would invent a fact
    // about the broker.
    assertEquals(Durations.fromMillis("none"), None)
    assertEquals(Durations.fromMillis(""), None)
  }

  test("surroundingSpaceIsIgnoredRatherThanFatal") {
    assertEquals(Durations.fromMillis("  1000 "), Some("1 s"))
  }
}
