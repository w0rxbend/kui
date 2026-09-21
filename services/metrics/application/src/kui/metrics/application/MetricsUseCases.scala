package kui.metrics.application

import java.time.Instant

import scala.concurrent.duration.FiniteDuration

import cats.Monad
import cats.effect.kernel.Clock
import cats.syntax.all.*

import kui.kernel.ClusterId
import kui.kernel.error.KuiError
import kui.metrics.domain.*

/** What came back when KUI went to measure something.
  *
  * Four cases and not an `Either`, because the two ways of having no number are not the same fact and the
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

  /** A real reading that is older than one scrape interval, and the instant it was actually taken.
    *
    * The fourth case, and it is the one a gauge needs. `Measured` is a claim about *now*; the buffer holds
    * samples for `kui.metrics.retention` and hands out the newest it still has, so an exporter that stopped
    * answering an hour ago leaves an hour-old idle ratio behind it. Drawn as `ok` that is last-known-good
    * data presented as current, which is the defect the brokers screen was repaired for; drawn as
    * `Unreadable` it would throw away a true number and offer a Retry for a card that has something to show.
    * `stale` is the section that says both — here is the reading, here is when it was taken (ADR-052).
    *
    * The three range answers never produce it: a series already draws the silence as gaps on its own axis.
    */
  case Stale[A](value: A, at: Instant) extends MetricsReading[A]
}

object MetricsReading {
  given [A] => CanEqual[MetricsReading[A], MetricsReading[A]] = CanEqual.derived
}

/** The five questions this service answers, one per card on the Traffic screen.
  *
  * Every one of them has the same shape and that is deliberate: resolve the cluster, decide whether there is
  * anything to ask, ask it, and answer honestly in all three cases without ever raising. Written once, in
  * [[MetricsUseCases.make]]'s `ask`, so that five endpoints cannot spell "this cluster has no source" five
  * ways — which is how one screen ends up saying `KUI-CLUSTER-NOT-FOUND` and the next one says nothing is
  * configured, for one request.
  *
  * ==Nothing here is cached==
  *
  * The samples behind every answer are the collector's business and it is the collector that keeps a window
  * of them (over `libs/cache`'s `SeriesWindow`). This layer holds no state at all, so a second browser tab
  * cannot see a different chart from the first one, and a restart loses no answer this layer owed anybody.
  *
  * ==One method per card, not one document==
  *
  * A dashboard asking for five things must not lose four of them to the fifth. Each method refuses on its own
  * and each refusal becomes one `Section`, which is what makes one dead metric family cost one card
  * (ADR-052).
  */
trait MetricsUseCases[F[_]] {

  /** `Left` means the request was wrong — no such cluster — and is the one case that becomes an HTTP error.
    * Everything else is a `Right` carrying a reading, because a dashboard asking five services for five
    * things must not lose four of them to the fifth.
    */
  def throughput(
      cluster: ClusterId,
      range: ThroughputRange
  ): F[Either[KuiError, MetricsReading[ThroughputSeries]]]

  /** The p99 of produce and consumer-fetch request time over the same range vocabulary. */
  def latency(cluster: ClusterId, range: ThroughputRange): F[Either[KuiError, MetricsReading[LatencySeries]]]

  /** The broker's idle ratios and purgatory depths, as of the most recent scrape. */
  def requestHandlers(cluster: ClusterId): F[Either[KuiError, MetricsReading[RequestHandlerReading]]]

  /** The busiest topics by bytes in. Topics and not `client.id`s — see ADR-052. */
  def producers(cluster: ClusterId, count: Int): F[Either[KuiError, MetricsReading[TopProducers]]]

  /** The mean size of a record, and the two rates it was divided from. Never a distribution — see ADR-052. */
  def recordSize(cluster: ClusterId): F[Either[KuiError, MetricsReading[RecordSizeReading]]]
}

object MetricsUseCases {

  /** @param sources
    *   which clusters exist and which of them have a collector behind them
    * @param staleAfter
    *   how old the newest scrape may be before a point-in-time reading answers `stale` rather than `ok`. It
    *   is `kui.metrics.scrapeInterval`, because that is the smallest gap an observer can see: a reading a
    *   whole interval old means the scrape that should have replaced it is late or did not happen. The
    *   threshold is deliberately tight and the cost of being tight is a caption rather than a wrong number —
    *   a card that says "this is the last answer KUI received" one interval early has still shown the true
    *   figure and the true instant, where the reverse mistake shows an hour-old gauge as current.
    */
  def make[F[_]: {Monad, Clock}](
      sources: ClusterSources[F],
      staleAfter: FiniteDuration
  ): MetricsUseCases[F] =
    new MetricsUseCases[F] {

      def throughput(
          cluster: ClusterId,
          range: ThroughputRange
      ): F[Either[KuiError, MetricsReading[ThroughputSeries]]] =
        ask(cluster)((port, now) => port.throughput(range, now))

      def latency(
          cluster: ClusterId,
          range: ThroughputRange
      ): F[Either[KuiError, MetricsReading[LatencySeries]]] =
        ask(cluster)((port, now) => port.latency(range, now))

      def requestHandlers(
          cluster: ClusterId
      ): F[Either[KuiError, MetricsReading[RequestHandlerReading]]] =
        current(cluster)((port, now) => port.requestHandlers(now))

      def producers(
          cluster: ClusterId,
          count: Int
      ): F[Either[KuiError, MetricsReading[TopProducers]]] =
        current(cluster)((port, now) => port.producers(count, now))

      def recordSize(cluster: ClusterId): F[Either[KuiError, MetricsReading[RecordSizeReading]]] =
        current(cluster)((port, now) => port.recordSize(now))

      /** The three-way answer, once.
        *
        * The two ways of having no number are not the same fact and the screens draw them differently: a
        * deployment that configured no source keeps its written sentence, and an exporter that stopped
        * answering offers a retry. Collapsing them would put a permanently red panel in front of every
        * operator who never asked for metrics, which is how people learn to ignore red panels.
        */
      private def ask[A](cluster: ClusterId)(
          of: (MetricsSourcePort[F], Instant) => F[Either[KuiError, A]]
      ): F[Either[KuiError, MetricsReading[A]]] =
        answer(cluster)(of)((value, now) => MetricsReading.Measured(value, now))

      /** The same four-way answer for a reading that is a claim about *now*.
        *
        * A series carries its own axis and says "KUI was not looking" by drawing a gap. A gauge cannot: it
        * has one number and no way to show that the number is an hour old. So the three point-in-time reads
        * come back stamped with the scrape they were taken from ([[kui.metrics.domain.Observed]]), and a
        * reading older than `staleAfter` becomes `Stale` — the same figure, its real instant, and a section
        * the browser draws with the sentence rather than as a current reading.
        */
      private def current[A](cluster: ClusterId)(
          of: (MetricsSourcePort[F], Instant) => F[Either[KuiError, Observed[A]]]
      ): F[Either[KuiError, MetricsReading[A]]] =
        answer(cluster)(of) { (observed, now) =>
          // Strictly after, so a reading exactly one interval old is still current: the boundary belongs to
          // the scrape that is due at it, and `isAfter` is the comparison a fixed-clock suite can pin.
          if now.isAfter(observed.at.plusNanos(staleAfter.toNanos)) then
            MetricsReading.Stale(observed.value, observed.at)
          else MetricsReading.Measured(observed.value, observed.at)
        }

      private def answer[A, B](cluster: ClusterId)(
          of: (MetricsSourcePort[F], Instant) => F[Either[KuiError, A]]
      )(measured: (A, Instant) => MetricsReading[B]): F[Either[KuiError, MetricsReading[B]]] =
        sources.profile(cluster).flatMap {
          case None => SourceAccess.unknownCluster(cluster).asLeft[MetricsReading[B]].pure[F]

          case Some(profile) =>
            sources.source(cluster).flatMap {
              // No collector to ask. Which sentence applies is decided by what the operator configured and
              // by what the adapter could make of it, never by this layer — see `SourceAccess.explain`.
              case None =>
                (MetricsReading.NotMeasured(SourceAccess.explain(profile)): MetricsReading[B])
                  .asRight[KuiError]
                  .pure[F]

              case Some(port) =>
                Clock[F].realTimeInstant.flatMap { now =>
                  of(port, now).map { got =>
                    val reading: MetricsReading[B] = got match {
                      case Right(value) => measured(value, now)
                      case Left(failure) => MetricsReading.Unreadable(failure, now)
                    }
                    reading.asRight[KuiError]
                  }
                }
            }
        }
    }
}
