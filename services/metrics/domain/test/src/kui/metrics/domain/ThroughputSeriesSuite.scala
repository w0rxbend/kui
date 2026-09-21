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
    ThroughputSample(at, Some(bytesIn), Some(bytesIn * 2), Some(bytesIn / 10))

  /** A scrape that carried the two byte rates and no record rate — an exporter whitelist that named
    * `BytesInPerSec` and `BytesOutPerSec` and nothing else, which is what a deployment usually configures.
    */
  private def bytesOnly(at: Instant, bytesIn: Double): ThroughputSample =
    ThroughputSample(at, Some(bytesIn), Some(bytesIn * 2), None)

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

  test("a rate the exporter never published is absent while the rates it did publish are measured") {
    // The failure this rules out is the opposite of the usual one: refusing the whole bucket because a
    // third rate nobody asked for was missing. The Traffic screen draws bytes in and bytes out, and an
    // exporter whitelist that publishes only those two is an ordinary configuration.
    val bucketStart = at(step * 100)
    val series =
      ThroughputSeries.over(ThroughputRange.Last24Hours, bucketStart, List(bytesOnly(bucketStart, 10.0)))

    val last = series.buckets.last
    assertEquals(last.bytesInPerSecond, Some(10.0))
    assertEquals(last.bytesOutPerSecond, Some(20.0))
    assertEquals(last.recordsPerSecond, None)
    assert(!last.isAbsent)
  }

  test("a rate is averaged over the samples that carried it, not over the samples in the bucket") {
    // An exporter that starts publishing a family halfway through a step must not have its first reading
    // halved by the samples that predate it. `10.0` and nothing is `10.0`, never `5.0`.
    val bucketStart = at(step * 100)
    val series = ThroughputSeries.over(
      ThroughputRange.Last24Hours,
      bucketStart,
      List(bytesOnly(bucketStart, 4.0), sample(bucketStart.plusSeconds(60), 10.0))
    )

    assertEquals(series.buckets.last.recordsPerSecond, Some(1.0))
    assertEquals(series.buckets.last.bytesInPerSecond, Some(7.0))
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
