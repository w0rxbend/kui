package kui.metrics.domain

import java.time.Instant

import munit.FunSuite

/** The latency fold, and the one place it is deliberately not the throughput fold.
  *
  * The axis rules are shared through `Bucketing` and are asserted for throughput one file over. What is
  * asserted here is the rule that is only true of a percentile: several scrapes inside one step are folded by
  * taking the **worst**, because the mean of four p99s is a p99 of nothing.
  */
final class LatencySeriesSuite extends FunSuite {

  private val noon = Instant.parse("2026-09-06T12:00:00Z")
  private val range = ThroughputRange.Last24Hours

  private def sample(at: Instant, produce: Double, fetch: Double): LatencySample =
    LatencySample(at, Some(produce), Some(fetch))

  test("several samples in one step fold to the worst percentile, not to their mean") {
    // Three scrapes inside one five-minute bucket. A mean would draw 20 ms and hide the 50 ms spike
    // inside a quiet five minutes, which is exactly the reading the card is drawn for. There is also no
    // such thing as the mean of three p99s: it is a number with no relationship to any request.
    val at = noon.minusSeconds(600)
    val series = LatencySeries.over(
      range,
      noon,
      List(sample(at, 5.0, 1.0), sample(at.plusSeconds(30), 50.0, 2.0), sample(at.plusSeconds(60), 5.0, 3.0))
    )

    val measured = series.buckets.filter(!_.isAbsent)
    assertEquals(measured.size, 1)
    assertEquals(measured.head.produceP99Millis, Some(50.0))
    assertEquals(measured.head.fetchP99Millis, Some(3.0))
  }

  test("a window with three samples answers bucketCount buckets of which three carry values") {
    val series = LatencySeries.over(
      range,
      noon,
      List(1800L, 1200L, 600L).map(ago => sample(noon.minusSeconds(ago), 9.0, 502.0))
    )

    assertEquals(series.buckets.size, range.bucketCount)
    assertEquals(series.buckets.count(!_.isAbsent), 3)
  }

  test("each percentile is folded over the samples that carried it") {
    // An exporter that started publishing the fetch percentile halfway through a step must not make the
    // produce figure read as absent, and must not make the first half read as measured.
    val at = noon.minusSeconds(600)
    val series = LatencySeries.over(
      range,
      noon,
      List(LatencySample(at, Some(4.0), None), LatencySample(at.plusSeconds(30), None, Some(9.0)))
    )

    val measured = series.buckets.filter(!_.isAbsent)
    assertEquals(measured.map(_.produceP99Millis), List(Some(4.0)))
    assertEquals(measured.map(_.fetchP99Millis), List(Some(9.0)))
  }

  test("a bucket nothing landed in is absent and a measured zero is not") {
    val series = LatencySeries.over(range, noon, List(sample(noon.minusSeconds(60), 0.0, 0.0)))

    assertEquals(series.buckets.count(!_.isAbsent), 1)
    assertEquals(series.buckets.filter(!_.isAbsent).head.produceP99Millis, Some(0.0))
    assert(series.buckets.head.isAbsent)
  }

  test("an absent series still draws a full axis for every range") {
    // A window with nothing in it has to draw an empty *day*, not an empty box.
    ThroughputRange.All.foreach { each =>
      val series = LatencySeries.absent(each, noon)

      assertEquals(series.buckets.size, each.bucketCount, each.wire)
      assert(series.buckets.forall(_.isAbsent))
      assertEquals(series.from.plusSeconds(each.window.toSeconds), series.to)
    }
  }

  test("a sample outside the window is not folded into its edge bucket") {
    // The failure this rules out is a chart whose left-most bar carries every reading older than the
    // window — a spike from last week drawn as though it happened at midnight.
    val series = LatencySeries.over(range, noon, List(sample(noon.minusSeconds(2 * 86400), 999.0, 999.0)))

    assert(series.buckets.forall(_.isAbsent), series.buckets.filter(!_.isAbsent).toString)
  }
}
