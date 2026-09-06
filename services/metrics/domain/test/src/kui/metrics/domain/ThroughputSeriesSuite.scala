package kui.metrics.domain

import java.time.Instant

import munit.FunSuite

/** The bucket fold, which is the one place in this service where a wrong answer reaches a screen without
  * anything failing.
  *
  * Every case here is a picture somebody would otherwise believe: a gap drawn as a quiet hour, a measured
  * quiet hour drawn as a gap, an axis that shifts between two polls, or a spike that stands for five minutes
  * because it happened to be the last sample in them.
  */
final class ThroughputSeriesSuite extends FunSuite {

  private val step = ThroughputRange.Last24Hours.step.toSeconds

  /** An instant on a bucket boundary, so a test that means "the start of a bucket" says so. */
  private def at(secondsFromEpoch: Long): Instant = Instant.ofEpochSecond(secondsFromEpoch)

  private def sample(at: Instant, bytesIn: Double): ThroughputSample =
    ThroughputSample(at, bytesIn, bytesIn * 2, bytesIn / 10)

  test("a bucket nothing was sampled in is absent, not zero") {
    val endingAt = at(step * 100)
    val series = ThroughputSeries.over(ThroughputRange.Last24Hours, endingAt, Nil)

    assertEquals(series.buckets.size, ThroughputRange.Last24Hours.bucketCount)
    assert(series.buckets.forall(_.isAbsent))
    assert(series.buckets.forall(_.bytesInPerSecond.isEmpty))
  }

  test("a bucket whose samples were zero is a measured zero, which is a different fact") {
    val endingAt = at(step * 100)
    val series = ThroughputSeries.over(
      ThroughputRange.Last24Hours,
      endingAt,
      List(sample(at(step * 100), 0.0))
    )

    val last = series.buckets.last
    assertEquals(last.bytesInPerSecond, Some(0.0))
    // The distinction the whole type exists for: this bucket is *not* absent, and a chart must draw a bar
    // of height zero here rather than a break in the line.
    assert(!last.isAbsent)
  }

  test("the boundaries are floored to the step, so two polls a minute apart agree") {
    val onBoundary = ThroughputSeries.over(ThroughputRange.Last24Hours, at(step * 100), Nil)
    val aMinuteLater = ThroughputSeries.over(ThroughputRange.Last24Hours, at(step * 100 + 60), Nil)

    assertEquals(onBoundary.from, aMinuteLater.from)
    assertEquals(onBoundary.to, aMinuteLater.to)
    assertEquals(onBoundary.buckets.map(_.startingAt), aMinuteLater.buckets.map(_.startingAt))
  }

  test("samples in one bucket are averaged, so one spike does not stand for the whole step") {
    val bucketStart = at(step * 100)
    val series = ThroughputSeries.over(
      ThroughputRange.Last24Hours,
      bucketStart,
      List(sample(bucketStart, 10.0), sample(bucketStart.plusSeconds(60), 30.0))
    )

    assertEquals(series.buckets.last.bytesInPerSecond, Some(20.0))
  }

  test("a sample older than the window is dropped rather than folded into the first bucket") {
    val endingAt = at(step * 1000)
    val tooOld = endingAt.minusSeconds(ThroughputRange.Last24Hours.window.toSeconds + step)
    val series = ThroughputSeries.over(ThroughputRange.Last24Hours, endingAt, List(sample(tooOld, 99.0)))

    assert(series.buckets.forall(_.isAbsent))
  }

  test("the axis covers exactly the range, whatever was sampled") {
    val series = ThroughputSeries.absent(ThroughputRange.Last7Days, at(step * 1000))

    assertEquals(
      series.to.getEpochSecond - series.from.getEpochSecond,
      ThroughputRange.Last7Days.window.toSeconds
    )
    assertEquals(series.buckets.size, ThroughputRange.Last7Days.bucketCount)
  }

  test("an unrecognised range is refused, never defaulted to the shortest window") {
    assertEquals(ThroughputRange.fromWire("24h"), Some(ThroughputRange.Last24Hours))
    assertEquals(ThroughputRange.fromWire(" 7D "), Some(ThroughputRange.Last7Days))
    // The failure this rules out: a chart the caller labelled "90 days", drawn from one day of samples.
    assertEquals(ThroughputRange.fromWire("90d"), None)
  }

  test("every range's step divides its window, so the bucket count is exact") {
    ThroughputRange.All.foreach { range =>
      assertEquals(
        range.window.toSeconds % range.step.toSeconds,
        0L,
        s"${range.wire}'s step does not divide its window"
      )
      assert(range.bucketCount > 0)
    }
  }
}
