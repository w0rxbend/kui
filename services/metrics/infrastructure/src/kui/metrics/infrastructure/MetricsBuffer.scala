package kui.metrics.infrastructure

import java.time.Instant

import scala.concurrent.duration.FiniteDuration

import cats.effect.kernel.Sync
import cats.syntax.all.*

import kui.cache.{CacheMetrics, SeriesWindowCell}
import kui.kernel.ClusterId
import kui.kernel.error.{ErrorCode, InfrastructureError, KuiError}
import kui.metrics.domain.*

/** What one cluster's scrapes are kept in, and the thing every endpoint actually reads.
  *
  * ==Why the port is the buffer and not the exporter==
  *
  * `MetricsSourcePort.throughput` asks for a whole range — twenty-four hours at a five-minute step — and an
  * exporter can only answer for *now*. Wiring the endpoint straight to the exporter would give a chart with
  * one point in it, and would put a network call on the path of every repaint. The port a use case holds is
  * therefore this buffer: the scrape loop fills it in the background, and a request is answered from memory
  * without touching the exporter at all.
  *
  * ==One window, five answers==
  *
  * A scrape produces one [[BrokerSample]] and one instant, so one window holds everything five cards are
  * drawn from. Five windows would cost five times the memory to hold five copies of one timestamp, and would
  * let two cards on one screen disagree about when "now" was.
  *
  * ==The rule this type exists to keep==
  *
  * **A range always answers `bucketCount` buckets.** A window holding three samples answers 288 buckets of
  * which three carry values, not three buckets — because the axis is a property of the range and not of what
  * was sampled. Answering only the buckets it holds would draw a quiet hour on a narrower axis than a busy
  * one, and an operator comparing two clusters would be comparing two different pictures. The fold that
  * guarantees it is the domain's, used here rather than reimplemented, so that a second source added later
  * cannot disagree with this one about what a gap is.
  *
  * ==A gap and a family that was never served are different refusals==
  *
  * A bucket nobody sampled is a `null` inside a series that answers `ok`: KUI was not looking, and the chart
  * breaks its bar. A *family* the exporter does not publish at all is a `Left`, which becomes an
  * `unavailable` section carrying the sentence — because no amount of waiting will fill it and what has to
  * change is the exporter's whitelist. Rendering the second as the first would leave an operator watching an
  * empty axis for a card that was never going to draw.
  *
  * ==What a failed scrape does to it: nothing==
  *
  * The loop records only what it read. A scrape that fails writes nothing, so the honest answer to "the
  * exporter died ten minutes ago" is the last ten minutes of data with a gap on the end — never an empty
  * chart. What does remove samples is age, and only age: `SeriesWindowCell.read` evicts against the `Instant`
  * it is given, which is the retention `kui.metrics.retention` configures.
  */
trait MetricsBuffer[F[_]] extends MetricsSourcePort[F] {

  /** Files one scrape. The sample carries its own instant, which is the one it is bucketed under. */
  def record(sample: BrokerSample): F[Unit]
}

object MetricsBuffer {

  /** The `cache` metric attribute for these windows: one short stable string per *kind* of series, never a
    * per-cluster value — the cluster is already its own attribute, and a label whose cardinality grows with
    * the number of clusters is how a metrics backend runs out of memory.
    */
  val Name: String = "metrics.broker"

  /** @param step
    *   the bucket width the samples are filed under, which is `kui.metrics.scrapeInterval`: two scrapes in
    *   one step are one reading, so a clock that drifts by a second does not produce two points a second
    *   apart
    * @param retention
    *   how far back samples are kept, from `kui.metrics.retention`. A range longer than this answers its full
    *   axis with the older end absent, which is the honest picture of a process that has not been running for
    *   thirty days.
    *
    * It is passed through exactly as the operator wrote it and is **not** widened to one `step`. The two keys
    * are not bounded independently: `KuiConfigSource.checkMetricsRules` refuses `retention < scrapeInterval`
    * at load, by name, before any process reaches this method, so the pair that would need widening cannot be
    * written. A silent widening here would make a configuration the loader refuses behave as though it had
    * been accepted, which is worse than the `SeriesWindow.empty` refusal it was hiding — that refusal is now
    * the second half of one invariant rather than a second opinion about it.
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
  ): F[MetricsBuffer[F]] =
    SeriesWindowCell
      .create[F, BrokerSample](Name, cluster, step, retention, maxSamples, startedAt, metrics)
      .map(cell => new Impl[F](cell))

  /** The sentence a card shows when KUI has never managed to read this cluster's exporter.
    *
    * Separate from "the family is not whitelisted" because the action is different: this one clears itself
    * when the exporter answers, and the operator's move is to wait or to look at the exporter.
    */
  private[infrastructure] val NothingScrapedYet: String =
    "KUI holds no reading of this cluster's metrics exporter: either no scrape has succeeded since this " +
      "process started, or everything it did read is older than kui.metrics.retention"

  /** The sentence for a family the exporter answers without publishing. */
  private[infrastructure] def notServed(family: String, samples: Int): String =
    s"the metrics exporter answered and $samples reading(s) of it carry no $family; this build reads it " +
      "from the JMX families named in deployment/metrics/kafka-jmx-exporter.yml, so widen the exporter's " +
      "whitelist rather than waiting"

  private def refusal(why: String): KuiError =
    InfrastructureError.Remote(ErrorCode.UpstreamUnavailable, why, Nil)

  final private class Impl[F[_]: Sync](cell: SeriesWindowCell[F, BrokerSample]) extends MetricsBuffer[F] {

    def record(sample: BrokerSample): F[Unit] = cell.record(sample.at, sample)

    def throughput(range: ThroughputRange, endingAt: Instant): F[Either[KuiError, ThroughputSeries]] =
      // `read` evicts against `endingAt` before answering, so a window nobody has fed for an hour cannot
      // hand out hour-old samples merely because nothing called `record` to prune them.
      //
      // `SeriesWindowCell.bucketsOver` is deliberately not used, and the difference matters: it refuses
      // with `None` until the window has been *collecting* for the whole period, which is the right rule
      // for a percentage folded over a day and the wrong one for an axis. A chart's honest answer after
      // four minutes of uptime is a full day of buckets with four minutes of them filled in; refusing
      // would leave the screen with nothing to draw and nothing to say.
      //
      // Throughput never refuses on an absent family, unlike the four endpoints below. It is the family
      // every exporter this product ships publishes and the one `deployment/compose/smoke.sh` asserts is
      // `ok`; a window with no samples in it is a KUI that has just started, which is a full axis of gaps.
      cell
        .read(endingAt)
        .map(window => ThroughputSeries.over(range, endingAt, window.samples.map(_.value.throughput).toList))
        .map(_.asRight)

    def latency(range: ThroughputRange, endingAt: Instant): F[Either[KuiError, LatencySeries]] =
      cell.read(endingAt).map { window =>
        val samples = window.samples.map(_.value.latency).toList

        // Scraped, repeatedly, and not one reading carried a percentile: the exporter is up and its
        // whitelist has no `RequestMetrics` rule in it. An axis of gaps here would be indistinguishable
        // from a KUI that started a minute ago, and only one of those two ever fills in.
        if samples.nonEmpty && samples.forall(_.isEmpty) then
          refusal(notServed("request-latency percentile", samples.size)).asLeft
        else LatencySeries.over(range, endingAt, samples).asRight
      }

    def requestHandlers(asOf: Instant): F[Either[KuiError, RequestHandlerReading]] =
      newest(asOf, "request-handler idle ratio or purgatory depth")(sample =>
        Option.when(!sample.handlers.isEmpty)(sample.handlers)
      )

    def producers(count: Int, asOf: Instant): F[Either[KuiError, TopProducers]] =
      // `Some(Nil)` is an answer and not a refusal: the exporter published the family and no line carrying
      // a topic. Nothing here decides *why* — a quiet cluster and a ruleset with no per-topic rule are
      // indistinguishable in an exposition — so the empty list travels and the card's sentence names both.
      newest(asOf, "per-topic bytes-in rate")(_.producers.map(TopProducers.of(_, count)))

    def recordSize(asOf: Instant): F[Either[KuiError, RecordSizeReading]] =
      newest(asOf, "bytes-in and records-in rate to divide")(sample =>
        Option.when(!sample.recordSize.isEmpty)(sample.recordSize)
      )

    /** The newest reading still inside the retention window, or the reason there is none.
      *
      * Two refusals and not one, because they need different actions from whoever reads the card: nothing
      * scraped yet clears itself, and a family that is not whitelisted never will.
      */
    private def newest[A](asOf: Instant, family: String)(
        pick: BrokerSample => Option[A]
    ): F[Either[KuiError, A]] =
      cell.read(asOf).map { window =>
        window.latest.map(_.value) match {
          case None => refusal(NothingScrapedYet).asLeft
          case Some(sample) => pick(sample).toRight(refusal(notServed(family, window.size)))
        }
      }
  }
}
