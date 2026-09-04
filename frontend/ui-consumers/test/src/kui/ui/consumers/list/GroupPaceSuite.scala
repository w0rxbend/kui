package kui.ui.consumers.list

import munit.FunSuite

import kui.ui.consumers.Numbers

/** How a rate is written, which is the only thing the pace column decides for itself.
  *
  * The column had no rendering rules to test until now because it had no column: the consumer service has
  * computed `pace` since M4 and no screen ever read it. The rules below are the ones that make a rate worth
  * showing rather than merely present.
  */
final class GroupPaceSuite extends FunSuite {

  test("aRateIsRoundedToOneDecimal") {
    // Three decimals of a figure sampled over a thirty-second interval are noise from where in the
    // interval the two samples landed, and printing them invites a comparison of two numbers that do not
    // differ.
    assertEquals(Numbers.rate(1234.5678), s"1${Numbers.GroupSeparator}234.6")
    assertEquals(Numbers.rate(12.04), "12.0")
  }

  test("aVerySmallRateIsNotRoundedDownToZero") {
    // "Slow" and "stuck" are the two answers this column exists to separate, and `0.0` says the wrong one.
    assertEquals(Numbers.rate(0.02), "< 0.1")
    assertEquals(Numbers.rate(-0.02), "> -0.1")
  }

  test("zeroIsZero") {
    assertEquals(Numbers.rate(0.0), "0")
  }

  test("aNegativeRateKeepsItsSign") {
    // Committed offsets moving backwards, which is what somebody else's offset reset looks like from here.
    // Clamping it would hide the one event this number is most useful for noticing.
    assertEquals(Numbers.rate(-4500.0), s"-4${Numbers.GroupSeparator}500.0")
  }

  test("aLargeRateIsGroupedLikeEveryOtherFigureOnTheScreen") {
    assertEquals(Numbers.rate(1234567.0), s"1${Numbers.GroupSeparator}234${Numbers.GroupSeparator}567.0")
  }
}
