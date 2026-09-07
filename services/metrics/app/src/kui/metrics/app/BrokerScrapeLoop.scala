package kui.metrics.app

import scala.concurrent.duration.FiniteDuration

import cats.effect.kernel.{Resource, Temporal}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger

import kui.kernel.ClusterId
import kui.kernel.error.KuiError
import kui.metrics.infrastructure.{BrokerScrape, MetricsBuffer}

/** One fibre per configured cluster, reading its exporter on a cadence and filing what came back.
  *
  * ==Why a loop and not a scrape per request==
  *
  * A chart is a history and a request is a moment. Nothing but a process that keeps asking can answer "the
  * last twenty-four hours", and putting the exporter on the request path would also make every repaint of the
  * dashboard a network call to a component KUI does not control.
  *
  * ==What a failed pass does, and does not do==
  *
  * It is logged and dropped. Three things it deliberately does not do:
  *
  *   - **it does not retry inside the pass.** The address already has ADR-037's retry, breaker and bulkhead
  *     in front of it, and a second retry loop here would turn one slow exporter into a pile of overlapping
  *     scrapes — the failure `kui.topics.scrapeTimeout` exists to prevent one service over;
  *   - **it does not clear the buffer.** The honest answer to "the exporter died ten minutes ago" is the last
  *     ten minutes of data with a gap on the end, not an empty chart. Only age removes a sample;
  *   - **it does not stop.** A fibre that exited on a failure would leave a cluster silently unmeasured for
  *     as long as the process ran, which is the worst of the three possible answers because nothing on any
  *     screen would say so.
  *
  * ==Why the first pass is immediate==
  *
  * A process that slept for its interval first would answer every request in its first `scrapeInterval` with
  * an empty axis, and the operator watching a fresh deployment is the one most likely to be looking. The
  * scrape is bounded by `MetricsSourceSettings.callTimeout` inside the upstream client, so an exporter that
  * never answers delays this fibre and nothing else — start-up included, because the loop runs in the
  * background from the moment it is allocated.
  */
object BrokerScrapeLoop {

  /** Starts the fibre and hands back its lifetime. It is cancelled when the composition root's `Resource`
    * closes, which is what stops a scrape in flight from outliving the process it belongs to.
    */
  def resource[F[_]: Temporal](
      cluster: ClusterId,
      scrape: BrokerScrape[F],
      buffer: MetricsBuffer[F],
      interval: FiniteDuration,
      logger: StructuredLogger[F]
  ): Resource[F, Unit] =
    pass[F](cluster, scrape, buffer, logger)
      .andWait(interval)
      .foreverM[Unit]
      .background
      .void

  /** One scrape, filed or explained.
    *
    * @return
    *   unit either way. The caller is a loop and there is nothing it could do differently with a failure that
    *   this has not already done with it.
    */
  def pass[F[_]: Temporal](
      cluster: ClusterId,
      scrape: BrokerScrape[F],
      buffer: MetricsBuffer[F],
      logger: StructuredLogger[F]
  ): F[Unit] =
    Temporal[F].realTimeInstant
      .flatMap(scrape.sample)
      .flatMap {
        case Right(sample) => buffer.record(sample)
        case Left(failure) => logFailure[F](cluster, failure, logger)
      }
      .handleErrorWith(error =>
        // A port that raised rather than answering `Left` is a bug in an adapter, and it must not be able
        // to end the fibre: the cluster would go unmeasured for the life of the process with nothing on any
        // screen saying so. Logged as an error precisely because it should be impossible.
        logger.error(error)(
          s"the metrics scrape for cluster ${cluster.value} raised instead of answering; " +
            "the previous samples are kept and the next pass runs as scheduled"
        )
      )

  private def logFailure[F[_]](
      cluster: ClusterId,
      failure: KuiError,
      logger: StructuredLogger[F]
  ): F[Unit] =
    logger.warn(
      Map("cluster" -> cluster.value, "error.code" -> failure.code.wire)
    )(
      s"the metrics exporter for cluster ${cluster.value} could not be read: ${failure.message}; " +
        "the samples already collected are kept and the throughput card shows them with a gap on the end"
    )
}
