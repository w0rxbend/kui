package kui.cluster.application

import cats.effect.kernel.Temporal
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger

import kui.cache.{Snapshot, SnapshotStatus}
import kui.cluster.domain.ClusterRef
import kui.kernel.ClusterId
import kui.kernel.error.{ApplicationError, KuiError}

/** Reading cluster topology, and asking for it to be re-read.
  *
  * Every method is a memory read plus, at most, a `Ref` update. Nothing here calls a broker, which is what
  * makes the dashboard's response time a function of the gateway's fan-out rather than of the slowest
  * configured cluster.
  */
trait ClusterTopologyUseCase[F[_]] {

  /** One cluster.
    *
    * `Left(ApplicationError.NotFound)` for an id that is not configured — a 404, not a 500. A configured but
    * unreachable cluster is a `Right` whose freshness is `Unavailable`. That distinction is the milestone's
    * dashboard criterion in one line.
    */
  def view(id: ClusterId): F[Either[KuiError, TopologyView]]

  /** Every configured cluster, in registry order, each with its own freshness. Never fails and never
    * partially fails: an unreachable cluster contributes an `Unavailable` view.
    */
  def viewAll: F[List[TopologyView]]

  /** Triggers a refresh and returns as soon as it has been *requested*, not when it completes, so the
    * endpoint can answer 202. Idempotent under concurrency: twenty presses produce one admin call.
    */
  def forceRefresh(id: ClusterId): F[Either[KuiError, Unit]]
}

object ClusterTopologyUseCase {

  val Operation: String = "kui.cluster.topology"

  /** Maps a cell's snapshot onto the staleness contract.
    *
    * Pure and public so that the four-row table is asserted directly rather than through four effectful
    * scenarios. The row that matters is the third: data present and the upstream failing is `Stale`, which is
    * what keeps a page rendering — greyed and timestamped — while a cluster is down.
    */
  def freshnessOf[A](snapshot: Snapshot[A]): SnapshotFreshness =
    (snapshot.value, snapshot.status, snapshot.scrapedAt) match {
      case (Some(_), SnapshotStatus.Offline(error, since), Some(at)) =>
        SnapshotFreshness.Stale(at, error, since)
      case (_, SnapshotStatus.Offline(error, since), _) =>
        SnapshotFreshness.Unavailable(error, since)
      case (Some(_), _, Some(at)) => SnapshotFreshness.Fresh(at)
      case _ => SnapshotFreshness.Loading
    }

  def make[F[_]: Temporal](
      registry: ClusterRegistry[F],
      snapshots: ClusterSnapshots[F],
      logger: StructuredLogger[F]
  ): ClusterTopologyUseCase[F] =
    new ClusterTopologyUseCase[F] {

      private val context: Map[String, String] =
        Map("service.name" -> ClusterService.Id.value, "operation" -> Operation)

      def view(id: ClusterId): F[Either[KuiError, TopologyView]] =
        registry.resolve(id).flatMap {
          case Left(error) => error.asLeft[TopologyView].pure[F]
          case Right(profile) => viewOf(profile.ref).map(_.asRight[KuiError])
        }

      def viewAll: F[List[TopologyView]] =
        registry.refs.flatMap(_.traverse(viewOf))

      def forceRefresh(id: ClusterId): F[Either[KuiError, Unit]] =
        registry.resolve(id).flatMap {
          case Left(error) => error.asLeft[Unit].pure[F]
          case Right(profile) =>
            snapshots.requestRefresh(profile.id).flatMap { started =>
              if started then
                logger
                  .debug(context ++ Map("cluster.id" -> profile.id.value))("a refresh was requested")
                  .as(().asRight[KuiError])
              else {
                // The cluster resolves but has no cell: the registry changed between the two reads,
                // or the cells have not caught up yet. A 404 would be wrong — the cluster exists —
                // and so would a silent success, because nothing was actually refreshed.
                val notReady: KuiError =
                  ApplicationError.InvalidState(
                    s"cluster '${profile.id.value}' is not ready to be refreshed yet"
                  )

                notReady.asLeft[Unit].pure[F]
              }
            }
        }

      /** A configured cluster with no cell yet is `Loading`, not missing: the cells are created from the
        * registry a moment after it resolves, and a dashboard row that vanished for that moment would
        * flicker.
        */
      private def viewOf(ref: ClusterRef): F[TopologyView] =
        snapshots.topologyOf(ref.id).flatMap {
          case None => TopologyView(ref, None, SnapshotFreshness.Loading).pure[F]
          case Some(cell) =>
            cell.get.map(snapshot => TopologyView(ref, snapshot.value, freshnessOf(snapshot)))
        }
    }
}
