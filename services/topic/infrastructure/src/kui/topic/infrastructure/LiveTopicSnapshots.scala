package kui.topic.infrastructure

import java.time.Instant

import scala.concurrent.duration.FiniteDuration

import cats.effect.kernel.{Clock, Concurrent, Resource, Temporal}
import cats.effect.std.Supervisor
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger

import kui.cache.{CacheMetrics, SnapshotCell, SnapshotLoadFailure}
import kui.kernel.ClusterId
import kui.kernel.error.InfrastructureError
import kui.topic.application.TopicSnapshots
import kui.topic.domain.{TopicAdmin, TopicError, TopicSnapshot}

/** One `SnapshotCell` per configured cluster, each scraping in the background.
  *
  * ==Why the cells are built up front rather than on demand==
  *
  * `TopicSnapshots.of` returns an effect, so a registry that created a cell on first ask would be legal. It
  * would also mean the first person to open the topics screen after a restart waits for a full scrape, and
  * that a cell created inside a request would outlive the request that made it with nothing owning its fiber.
  * Building them all inside the composition root's `Resource` puts every background fiber under one lifetime
  * that ends when the process does, and means the first screen is served from a scrape that already happened
  * — which is the whole promise `SnapshotCell` exists to make.
  *
  * The cost is that a KUI configured with a cluster nobody looks at still scrapes it. That is deliberate: the
  * capability report has to say whether each cluster is reachable *before* anyone asks, or the sidebar cannot
  * grey out the one that is down.
  *
  * ==What `of` returning `None` means==
  *
  * The cluster is not configured. It is a 404 at the edge and never an empty list — an empty topic list reads
  * as "this cluster has no topics", which is a different and much more alarming statement than "KUI has never
  * heard of this cluster".
  */
object LiveTopicSnapshots {

  /** The `cache` metric attribute and the log context for these cells. One short stable string per *kind* of
    * snapshot, never a per-cluster value: the cluster is already its own attribute, and a metric label whose
    * cardinality grows with the number of clusters is how a metrics backend runs out of memory.
    */
  val Name: String = "topic.list"

  def resource[F[_]: Temporal](
      clusters: List[ClusterId],
      admin: TopicAdmin[F],
      interval: FiniteDuration,
      metrics: CacheMetrics[F],
      logger: StructuredLogger[F]
  ): Resource[F, TopicSnapshots[F]] =
    clusters
      .traverse(cluster =>
        SnapshotCell
          .resource[F, TopicSnapshot](Name, cluster, interval, metrics, Some(logger))(
            scrape[F](cluster, admin)
          )
          .map(cluster -> _)
      )
      .mproduct(_ => Supervisor[F])
      .map((cells, supervisor) => make[F](cells.toMap, supervisor))

  /** The registry over cells that already exist, so a test can hand in `SnapshotCell.constant`.
    *
    * @param supervisor
    *   where a forced refresh runs. It belongs to the composition root, so a refresh in flight when the
    *   process shuts down is cancelled rather than left holding a Kafka admin client.
    */
  def make[F[_]: Concurrent](
      cells: Map[ClusterId, SnapshotCell[F, TopicSnapshot]],
      supervisor: Supervisor[F]
  ): TopicSnapshots[F] =
    new TopicSnapshots[F] {
      def of(cluster: ClusterId): F[Option[SnapshotCell[F, TopicSnapshot]]] = cells.get(cluster).pure[F]

      /** Starts a refresh and returns immediately.
        *
        * `refresh` on a cell blocks until the scrape it joined finishes, and this method's whole contract is
        * that it does not — the refresh button must answer at once rather than hanging for as long as an
        * unreachable cluster takes to time out. The refresh therefore has to run somewhere else, and the
        * cell's own `refresh` is idempotent under concurrency, so five presses of the button are one request
        * to the broker no matter how many fibers ask.
        */
      def requestRefresh(cluster: ClusterId): F[Boolean] =
        cells.get(cluster).traverse(cell => supervisor.supervise(cell.refresh)).map(_.isDefined)
    }

  /** One scrape, as the `load` a cell runs.
    *
    * A `TopicError` becomes a `SnapshotLoadFailure`, which is how a cell that can only fail with a
    * `Throwable` keeps the `KuiError` the screen has to show. The cell catches it, keeps the previous
    * snapshot in place, and moves only the status to `Offline` — which is the behaviour a user sees as "the
    * page still shows what KUI last saw, greyed out and stamped with when it was seen".
    */
  def scrape[F[_]: {Temporal, Clock}](cluster: ClusterId, admin: TopicAdmin[F]): F[TopicSnapshot] =
    for {
      result <- admin.scrape(cluster)
      now <- Clock[F].realTimeInstant
      snapshot <- result match {
        case Right(scraped) =>
          TopicSnapshot.of(scraped.topics.toVector, now, scraped.incomplete).pure[F]
        case Left(failure) =>
          Temporal[F].raiseError[TopicSnapshot](SnapshotLoadFailure(asKuiError(failure)))
      }
    } yield snapshot

  /** A `TopicError` as the `KuiError` a snapshot's `Offline` status carries.
    *
    * Only the two failure cases can occur here: a scrape is not about one topic, so `NotFound` cannot happen,
    * and the cluster was resolved before the cell was created, so `ClusterNotFound` cannot either. They are
    * still mapped rather than left to a partial match, because a partial match in a background fiber fails
    * silently and the screen would simply stop updating.
    */
  def asKuiError(failure: TopicError): kui.kernel.error.KuiError =
    failure match {
      case TopicError.Forbidden(detail) =>
        kui.kernel.error.ApplicationError.Forbidden(s"KUI is not authorized: $detail")
      case other => InfrastructureError.Unreachable("kafka", other.message)
    }

  /** An empty snapshot, for a cluster that has been configured and never successfully scraped. */
  def empty(at: Instant): TopicSnapshot = TopicSnapshot.empty(at)
}
