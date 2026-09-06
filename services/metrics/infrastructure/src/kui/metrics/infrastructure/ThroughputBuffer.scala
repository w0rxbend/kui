package kui.metrics.infrastructure

import java.time.Instant

import scala.concurrent.duration.FiniteDuration

import cats.effect.kernel.Sync
import cats.syntax.all.*

import kui.cache.{CacheMetrics, SeriesWindowCell}
import kui.kernel.ClusterId
import kui.kernel.error.KuiError
import kui.metrics.domain.{MetricsSourcePort, ThroughputRange, ThroughputSample, ThroughputSeries}

/** What one cluster's scrapes are kept in, and the thing the endpoint actually reads.
  *
  * ==Why the port is the buffer and not the exporter==
  *
  * `MetricsSourcePort.throughput` asks for a whole range — twenty-four hours at a five-minute step — and an
  * exporter can only answer for *now*. Wiring the endpoint straight to the exporter would give a chart with
  * one point in it, and would put a network call on the path of every repaint. The port a use case holds is
  * therefore this buffer: the scrape loop fills it in the background, and a request is answered from memory
  * without touching the exporter at all.
  *
  * ==The rule this type exists to keep==
  *
  * **A range always answers `bucketCount` buckets.** A window holding three samples answers 288 buckets of
  * which three carry values, not three buckets — because the axis is a property of the range and not of what
  * was sampled. Answering only the buckets it holds would draw a quiet hour on a narrower axis than a busy
  * one, and an operator comparing two clusters would be comparing two different pictures. The fold that
  * guarantees it is the domain's `ThroughputSeries.over`, used here rather than reimplemented, so that a
  * second source added later cannot disagree with this one about what a gap is.
  *
  * ==What a failed scrape does to it: nothing==
  *
  * The loop records only what it read. A scrape that fails writes nothing, so the honest answer to "the
  * exporter died ten minutes ago" is the last ten minutes of data with a gap on the end — never an empty
  * chart. What does remove samples is age, and only age: `SeriesWindowCell.read` evicts against the `Instant`
  * it is given, which is the retention `kui.metrics.retention` configures.
  */
trait ThroughputBuffer[F[_]] extends MetricsSourcePort[F] {

  /** Files one scrape. The sample carries its own instant, which is the one it is bucketed under. */
  def record(sample: ThroughputSample): F[Unit]
}

object ThroughputBuffer {

  /** The `cache` metric attribute for these windows: one short stable string per *kind* of series, never a
    * per-cluster value — the cluster is already its own attribute, and a label whose cardinality grows with
    * the number of clusters is how a metrics backend runs out of memory.
    */
  val Name: String = "metrics.throughput"

  /** @param step
    *   the bucket width the samples are filed under, which is `kui.metrics.scrapeInterval`: two scrapes in
    *   one step are one reading, so a clock that drifts by a second does not produce two points a second
    *   apart
    * @param retention
    *   how far back samples are kept, from `kui.metrics.retention`. A range longer than this answers its full
    *   axis with the older end absent, which is the honest picture of a process that has not been running for
    *   thirty days. It is widened to one `step` when it is shorter than one: the two keys are bounded
    *   independently — the loader accepts `scrapeInterval: 1h` beside `retention: 1m` — and a window shorter
    *   than its own bucket can hold nothing at all. `SeriesWindow.empty` refuses that pair outright, which
    *   inside a composition root is a process that will not start over configuration the loader accepted;
    *   widening is the honest reading of "keep a minute of hourly samples"
    * @param maxSamples
    *   `kui.metrics.maxSamplesPerSeries`, the ceiling that holds when the other two multiply out to more
    *   resident memory than the operator who typed them asked for
    * @param startedAt
    *   when this window began collecting. It is what lets the series say "collecting" rather than answer a
    *   day's question from four minutes of data
    */
  def create[F[_]: Sync](
      cluster: ClusterId,
      step: FiniteDuration,
      retention: FiniteDuration,
      maxSamples: Int,
      startedAt: Instant,
      metrics: CacheMetrics[F]
  ): F[ThroughputBuffer[F]] =
    SeriesWindowCell
      .create[F, ThroughputSample](Name, cluster, step, retention.max(step), maxSamples, startedAt, metrics)
      .map(cell => new Impl[F](cell))

  final private class Impl[F[_]: Sync](cell: SeriesWindowCell[F, ThroughputSample])
      extends ThroughputBuffer[F] {

    def record(sample: ThroughputSample): F[Unit] = cell.record(sample.at, sample)

    def throughput(
        range: ThroughputRange,
        endingAt: Instant
    ): F[Either[KuiError, ThroughputSeries]] =
      // `read` evicts against `endingAt` before answering, so a window nobody has fed for an hour cannot
      // hand out hour-old samples merely because nothing called `record` to prune them.
      //
      // `SeriesWindowCell.bucketsOver` is deliberately not used, and the difference matters: it refuses
      // with `None` until the window has been *collecting* for the whole period, which is the right rule
      // for a percentage folded over a day and the wrong one for an axis. A chart's honest answer after
      // four minutes of uptime is a full day of buckets with four minutes of them filled in; refusing
      // would leave the screen with nothing to draw and nothing to say.
      cell
        .read(endingAt)
        .map(window => ThroughputSeries.over(range, endingAt, window.samples.map(_.value).toList).asRight)
  }
}
