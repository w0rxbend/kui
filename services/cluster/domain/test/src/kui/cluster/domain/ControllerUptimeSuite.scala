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
    // 359 of 360 samples is 99.7222…%, and the card prints `99.72 %`. Rounding here rather than in the
    // browser is what keeps a CSV export and the screen agreeing about the same number.
    //
    // 360 is not a decoration: it is every bucket the shipped 6 h window at a 1 min step retains, so this
    // is the highest reading below a hundred that any deployment of KUI can produce. The example this
    // suite used to assert — 5,999 of 6,000 samples, printing `99.98` — was sixteen times what the window
    // holds and named a figure the service cannot emit.
    val samples = Vector.fill(359)(true) :+ false
    val measured = ControllerUptime.of(window, coverage = window, observations = Some(samples))

    assertEquals(measured.percent, Some(99.72d))
  }

  test("aSparseWindowIsIndistinguishableFromAFullOne") {
    // The consequence of `aGapIsNotACountedAbsence`, written down so that closing it is deliberate. Two
    // successful scrapes over a full six hours and 360 of them produce the same three fields, and
    // `ControllerUptimeDto` carries nothing that separates them — so a client told "100 % over the last 6h"
    // cannot tell a healthy cluster from one KUI reached twice. Delete this case when the document carries
    // an observation count; do not weaken it, and do not fill the gap with a `false` per missed scrape,
    // which is the thing the fold refuses to do on purpose.
    val sparse = ControllerUptime.of(window, coverage = window, observations = Some(Vector(true, true)))
    val full = ControllerUptime.of(window, coverage = window, observations = Some(Vector.fill(360)(true)))

    assertEquals(sparse, full)
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
