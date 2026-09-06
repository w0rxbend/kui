package kui.cluster.domain

import scala.concurrent.duration.*

/** The window's refusals, which are the only interesting thing about it: the arithmetic is a mean of
  * booleans, and the failure mode is answering when there is nothing to answer from.
  */
final class ControllerUptimeSuite extends munit.FunSuite {

  private val window: FiniteDuration = 6.hours

  test("aWindowThatHasNotBeenCollectingLongEnoughRefusesAndStillStatesItsLength") {
    // `None` from the window means "I have not been running for six hours". The length still travels,
    // because a client that cannot say what it is collecting *towards* has nothing to render but an
    // empty ring.
    val measured = ControllerUptime.of(window, coverage = 41.minutes, observations = None)

    assertEquals(measured.percent, None)
    assertEquals(measured.window, window)
    assertEquals(measured.coverage, 41.minutes)
  }

  test("aFullWindowWithNoObservationsRefusesRatherThanDividingByZero") {
    // The shape `SeriesWindowCell` produces for a cluster KUI has been unable to reach for the whole
    // window: it has been collecting for six hours and every bucket is a gap. A percentage over no
    // observations is not a percentage, however well-shaped the vector is.
    val measured = ControllerUptime.of(window, coverage = window, observations = Some(Vector.empty))

    assertEquals(measured.percent, None)
    assertEquals(measured.coverage, window)
  }

  test("aFullWindowOfPresentControllersIsAHundredPercent") {
    val measured =
      ControllerUptime.of(window, coverage = window, observations = Some(Vector.fill(360)(true)))

    assertEquals(measured.percent, Some(100.0d))
  }

  test("aSingleMissedControllerRoundsToTwoDecimals") {
    // 5,999 of 6,000 samples is 99.98333…%, and the card prints `99.98 %`. Rounding here rather than in
    // the browser is what keeps a CSV export and the screen agreeing about the same number.
    val samples = Vector.fill(5999)(true) :+ false
    val measured = ControllerUptime.of(window, coverage = window, observations = Some(samples))

    assertEquals(measured.percent, Some(99.98d))
  }

  test("aGapIsNotACountedAbsence") {
    // The rule the caller depends on: a scrape KUI could not make records nothing, so it never reaches
    // this fold. Two observations, one of them without a controller, is 50 % — and the fifty-eight
    // minutes nobody sampled do not drag it down, because they are not here.
    val measured =
      ControllerUptime.of(window, coverage = window, observations = Some(Vector(true, false)))

    assertEquals(measured.percent, Some(50.0d))
  }
}
