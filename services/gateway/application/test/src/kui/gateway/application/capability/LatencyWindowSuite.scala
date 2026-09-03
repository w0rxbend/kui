package kui.gateway.application.capability

import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}

import org.scalacheck.{Gen, Prop}

import kui.testkit.KuiSuite

/** That "this service is answering slowly" is measured rather than guessed. */
final class LatencyWindowSuite extends KuiSuite {

  private def windowOf(samples: List[FiniteDuration], size: Int = 50): LatencyWindow =
    samples.foldLeft(LatencyWindow.empty(size))(_.record(_))

  test("p95IsNoneBeforeAnySample") {
    // "Not measured" must not read as "fast", or a service nobody could reach would look healthy.
    assertEquals(LatencyWindow.empty().p95, None)
  }

  test("p95OfAKnownDistribution") {
    // Ninety-nine fast calls and one slow one. The mean would be about 100 ms and would say nothing;
    // the p95 says 100 ms, and the p99 finds the outlier.
    val samples = List.fill(99)(10.millis) ++ List(8.seconds)
    val window = windowOf(samples, size = 100)

    assertEquals(window.p95, Some(10.millis))
    assertEquals(window.percentile(100), Some(8.seconds))
  }

  test("p95OfAUniformDistribution") {
    val window = windowOf((1 to 100).map(_.millis).toList, size = 100)
    assertEquals(window.p95, Some(95.millis))
  }

  test("aSingleSampleIsItsOwnP95") {
    assertEquals(windowOf(List(42.millis)).p95, Some(42.millis))
  }

  test("theWindowForgetsWhatIsNoLongerRecent") {
    // A service that was slow and is now fast must stop being reported as slow, or the sidebar would
    // carry a warning about an incident that ended.
    val window = windowOf(List.fill(10)(9.seconds) ++ List.fill(50)(5.millis))
    assertEquals(window.p95, Some(5.millis))
  }

  private val samples: Gen[List[FiniteDuration]] =
    Gen.listOf(Gen.chooseNum(0L, 30000L).map(_.millis))

  property("theWindowNeverExceedsItsSize") {
    Prop.forAll(samples, Gen.chooseNum(1, 20)) { (values, size) =>
      windowOf(values, size).count <= size
    }
  }

  property("p95IsAlwaysASampleThatWasActuallyObserved") {
    // Nearest-rank, not interpolated: an operator reading "p95 = 240ms" should be able to find a call
    // that took 240 ms, rather than a number no request ever produced.
    Prop.forAll(samples) { values =>
      val window = windowOf(values)
      window.p95.forall(window.values.contains)
    }
  }

  property("p95IsAtLeastTheMedianAndAtMostTheSlowest") {
    Prop.forAll(samples.suchThat(_.nonEmpty)) { values =>
      val window = windowOf(values)
      (window.p95, window.percentile(50)) match {
        case (Some(p95), Some(median)) => p95 >= median && p95 <= window.values.max
        case _ => false
      }
    }
  }
}
