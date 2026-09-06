package kui.metrics.domain

import java.time.Instant

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** How far back a throughput question reaches, and at what resolution.
  *
  * Three ranges and not an arbitrary `from`/`to` pair, because the resolution has to be chosen with the
  * window: a thirty-day chart drawn at the five-minute step of a one-day chart is 8,640 points behind 900
  * pixels, and the browser would be downsampling a series KUI already had to hold in memory. Pairing each
  * window with its own step here is what keeps the bucket count roughly constant across all three.
  *
  * The `wire` string is the query-string spelling and is written out per case rather than derived from the
  * case name, for the reason `Resource.wire` is: renaming a case must never silently change what `?range=7d`
  * means to a browser that has already shipped.
  */
enum ThroughputRange(val wire: String, val window: FiniteDuration, val step: FiniteDuration) {

  case Last24Hours extends ThroughputRange("24h", 24.hours, 5.minutes)
  case Last7Days extends ThroughputRange("7d", 7.days, 1.hour)
  case Last30Days extends ThroughputRange("30d", 30.days, 6.hours)

  /** How many buckets a full series of this range holds. Constant per range, whatever was sampled: a range
    * that returned fewer buckets when the cluster was quiet would draw a shorter axis for a quiet hour.
    */
  def bucketCount: Int = (window.toSeconds / step.toSeconds).toInt
}

object ThroughputRange {

  val All: List[ThroughputRange] = List(Last24Hours, Last7Days, Last30Days)

  /** The default a caller who names no range gets. Stated once, here, so the contract's default and any later
    * caller's fallback cannot be two different windows labelled with one word.
    */
  val Default: ThroughputRange = Last24Hours

  /** Parses the query-string spelling. `None` for anything else, deliberately: falling back to the default
    * would answer a request for `?range=90d` with 24 hours of data under a label the caller chose, which is
    * the one failure a chart cannot show its reader.
    */
  def fromWire(raw: String): Option[ThroughputRange] = {
    val normalised = raw.trim.toLowerCase
    All.find(_.wire == normalised)
  }

  given CanEqual[ThroughputRange, ThroughputRange] = CanEqual.derived
}

/** One reading of a cluster's throughput, at the moment the source was scraped.
  *
  * Rates rather than counters. A broker publishes monotonic totals and turning two totals into a rate needs
  * both samples and the gap between them, which is the adapter's arithmetic (ADR-050); by the time a value is
  * a `ThroughputSample` that arithmetic has happened, and a consumer of this type can never accidentally
  * chart a counter that resets when a broker restarts.
  *
  * ==Each rate is separately optional, because an exporter publishes each of them separately==
  *
  * A JMX exporter is configured with a whitelist, and a deployment that publishes `BytesInPerSec` and
  * `BytesOutPerSec` without `MessagesInPerSec` is an ordinary configuration rather than a broken one. Making
  * the three rates one all-or-nothing tuple would mean refusing the whole card — the two rates the Traffic
  * screen actually draws included — because a third one nobody asked for was absent. `None` here means the
  * same thing it means one type down: *not measured*, never zero.
  */
final case class ThroughputSample(
    at: Instant,
    bytesInPerSecond: Option[Double],
    bytesOutPerSecond: Option[Double],
    recordsPerSecond: Option[Double]
) {

  /** True when the scrape produced no rate at all. A source answering these is a source answering nothing,
    * and the adapter refuses rather than filing an empty sample that would read as a measured gap.
    */
  def isEmpty: Boolean =
    bytesInPerSecond.isEmpty && bytesOutPerSecond.isEmpty && recordsPerSecond.isEmpty
}

object ThroughputSample {
  given CanEqual[ThroughputSample, ThroughputSample] = CanEqual.derived
}

/** One step of the time axis, and what was measured over it — or nothing, when nothing was.
  *
  * Every rate is an `Option` and every `None` means **not measured**. That is the single rule this whole type
  * exists to carry: a bucket KUI did not sample is a gap in the chart, and rendering it as `0` is a claim
  * that the cluster was idle. An operator looking at an idle-looking hour goes and asks why their producers
  * stopped; the honest answer was that KUI was restarting.
  */
final case class ThroughputBucket(
    startingAt: Instant,
    bytesInPerSecond: Option[Double],
    bytesOutPerSecond: Option[Double],
    recordsPerSecond: Option[Double]
) {

  /** True when nothing at all was measured over this step. A bucket of measured zeroes is not absent. */
  def isAbsent: Boolean =
    bytesInPerSecond.isEmpty && bytesOutPerSecond.isEmpty && recordsPerSecond.isEmpty
}

object ThroughputBucket {

  /** The bucket for a step nothing was sampled in. */
  def absentAt(startingAt: Instant): ThroughputBucket = ThroughputBucket(startingAt, None, None, None)

  given CanEqual[ThroughputBucket, ThroughputBucket] = CanEqual.derived
}

/** A cluster's throughput over one range, bucket by bucket, ending at a stated instant.
  *
  * `from` and `to` are carried rather than left for the reader to derive from the buckets, because a series
  * whose every bucket is absent still has to draw an axis: without them a chart with no data would have no
  * width and would render as an empty box rather than as an empty *day*.
  */
final case class ThroughputSeries(
    range: ThroughputRange,
    from: Instant,
    to: Instant,
    buckets: List[ThroughputBucket]
)

object ThroughputSeries {

  given CanEqual[ThroughputSeries, ThroughputSeries] = CanEqual.derived

  /** Folds raw samples into the range's buckets, ending at `endingAt`.
    *
    * This is the domain rule of the whole service, and it is stated here rather than in whichever adapter M7
    * writes so that a JMX source and a Prometheus source cannot disagree about what a gap is.
    *
    * Three things it guarantees, each of which has a wrong answer that reaches a screen:
    *
    *   - a bucket no sample fell into is **absent**, never zero;
    *   - a bucket whose samples were all zero is `Some(0.0)`, which is a measured quiet cluster and a
    *     different fact from the one above;
    *   - the boundaries are floored to the step against the epoch, so two requests a minute apart return the
    *     same buckets. Anchoring them to "now" instead would shift every boundary between two polls and make
    *     a live chart shiver even while the cluster's traffic was constant.
    *
    * Samples inside one bucket are averaged. A bucket is a step of wall-clock time and the value drawn for it
    * is the rate that held over it; taking the last sample instead would let one spike at 12:04 stand for the
    * whole of 12:00–12:05.
    */
  def over(range: ThroughputRange, endingAt: Instant, samples: List[ThroughputSample]): ThroughputSeries = {
    val stepSeconds = range.step.toSeconds
    val to = floorTo(endingAt, stepSeconds).plusSeconds(stepSeconds)
    val from = to.minusSeconds(range.window.toSeconds)

    val byBucket: Map[Instant, List[ThroughputSample]] =
      samples
        .filter(sample => !sample.at.isBefore(from) && sample.at.isBefore(to))
        .groupBy(sample => floorTo(sample.at, stepSeconds))

    val buckets = boundaries(from, to, stepSeconds).map { start =>
      // Each rate is folded over the samples that carried *it*, not over the samples in the bucket. An
      // exporter that started publishing `MessagesInPerSec` halfway through an hour must not make the
      // bytes it published all hour read as absent, and must not make the first half read as measured.
      val inBucket = byBucket.getOrElse(start, Nil)

      ThroughputBucket(
        startingAt = start,
        bytesInPerSecond = mean(inBucket.flatMap(_.bytesInPerSecond)),
        bytesOutPerSecond = mean(inBucket.flatMap(_.bytesOutPerSecond)),
        recordsPerSecond = mean(inBucket.flatMap(_.recordsPerSecond))
      )
    }

    ThroughputSeries(range, from, to, buckets)
  }

  /** The full axis with nothing on it: every bucket present, every rate absent.
    *
    * The rendering for a cluster KUI can reach but has never managed to sample. It is a *measured* answer —
    * "we looked and have nothing for this window" — and it is deliberately not the same value as no series at
    * all, which is what a cluster with no source configured produces.
    */
  def absent(range: ThroughputRange, endingAt: Instant): ThroughputSeries =
    over(range, endingAt, Nil)

  private def boundaries(from: Instant, to: Instant, stepSeconds: Long): List[Instant] =
    Iterator
      .iterate(from)(_.plusSeconds(stepSeconds))
      .takeWhile(_.isBefore(to))
      .toList

  /** The start of the step `at` falls in, measured from the epoch rather than from the request. */
  private def floorTo(at: Instant, stepSeconds: Long): Instant =
    Instant.ofEpochSecond(Math.floorDiv(at.getEpochSecond, stepSeconds) * stepSeconds)

  /** `None` for a rate nothing in the bucket measured — which is the one arithmetic mistake this file exists
    * to prevent, since `0.0 / 0` is `NaN` and a `NaN` serialises to `null` by a route nobody chose.
    */
  private def mean(values: List[Double]): Option[Double] =
    Option.when(values.nonEmpty)(values.sum / values.size)
}
