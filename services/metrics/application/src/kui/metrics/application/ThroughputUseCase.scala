package kui.metrics.application

import java.time.Instant

import cats.Monad
import cats.effect.kernel.Clock
import cats.syntax.all.*

import kui.kernel.ClusterId
import kui.kernel.error.KuiError
import kui.metrics.domain.{ThroughputRange, ThroughputSeries}

/** What came back when KUI went to measure something.
  *
  * Three cases and not an `Either`, because the two ways of having no number are not the same fact and the
  * screens draw them differently (ADR-032): a deployment that configured no source is showing the
  * `NotMeasured` sentence *by design*, and an exporter that stopped answering is showing a failure worth
  * retrying. Collapsing them would put a permanently red panel in front of every operator who never asked for
  * metrics, which is how people learn to ignore red panels.
  *
  * The `api` layer is what turns these into the `Section` a browser reads. This layer must not know the wire
  * exists (rule A3), which is why the instant travels as a field rather than as a `fetchedAt` on a DTO.
  */
enum MetricsReading[+A] {

  /** A real reading, and the moment it was taken. */
  case Measured[A](value: A, at: Instant) extends MetricsReading[A]

  /** There is nothing here to measure, and this is the sentence saying why. Not a failure. */
  case NotMeasured(explanation: String) extends MetricsReading[Nothing]

  /** There is a source and it did not answer. `at` is when KUI last tried. */
  case Unreadable(failure: KuiError, at: Instant) extends MetricsReading[Nothing]
}

object MetricsReading {
  given [A] => CanEqual[MetricsReading[A], MetricsReading[A]] = CanEqual.derived
}

/** One cluster's throughput over a range.
  *
  * The only endpoint this service has, and the shape every later one copies: resolve the cluster, decide
  * whether there is anything to ask, ask it, and answer honestly in all three cases without ever raising.
  *
  * ==Nothing here is cached==
  *
  * The samples behind a series are the collector's business and it is the collector that keeps a window of
  * them (M7, over `libs/cache`'s `SeriesWindow`). This use case holds no state at all, so a second browser
  * tab cannot see a different chart from the first one, and a restart loses no answer this layer owed
  * anybody.
  */
trait ThroughputUseCase[F[_]] {

  /** `Left` means the request was wrong — no such cluster — and is the one case that becomes an HTTP error.
    * Everything else is a `Right` carrying a reading, because a dashboard asking five services for five
    * things must not lose four of them to the fifth.
    */
  def throughput(
      cluster: ClusterId,
      range: ThroughputRange
  ): F[Either[KuiError, MetricsReading[ThroughputSeries]]]
}

object ThroughputUseCase {

  def make[F[_]: {Monad, Clock}](sources: ClusterSources[F]): ThroughputUseCase[F] =
    new ThroughputUseCase[F] {

      def throughput(
          cluster: ClusterId,
          range: ThroughputRange
      ): F[Either[KuiError, MetricsReading[ThroughputSeries]]] =
        sources.profile(cluster).flatMap {
          case None =>
            SourceAccess.unknownCluster(cluster).asLeft[MetricsReading[ThroughputSeries]].pure[F]

          case Some(profile) =>
            sources.source(cluster).flatMap {
              // No collector to ask. Which of the two sentences applies is decided by what the operator
              // configured, not by what this build happens to have implemented — see `SourceAccess`.
              case None =>
                val explanation =
                  if profile.hasSource then SourceAccess.noCollector(cluster)
                  else SourceAccess.noSource(cluster)
                (MetricsReading.NotMeasured(explanation): MetricsReading[ThroughputSeries])
                  .asRight[KuiError]
                  .pure[F]

              case Some(port) =>
                Clock[F].realTimeInstant.flatMap { now =>
                  port.throughput(range, now).map { answer =>
                    val reading: MetricsReading[ThroughputSeries] = answer match {
                      case Right(series) => MetricsReading.Measured(series, now)
                      case Left(failure) => MetricsReading.Unreadable(failure, now)
                    }
                    reading.asRight[KuiError]
                  }
                }
            }
        }
    }
}
