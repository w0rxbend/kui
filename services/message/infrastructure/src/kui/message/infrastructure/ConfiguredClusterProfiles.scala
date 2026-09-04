package kui.message.infrastructure

import cats.effect.kernel.Clock
import cats.syntax.all.*

import kui.config.ClusterConfig
import kui.kernel.ClusterId
import kui.kernel.cluster.ClusterConnection
import kui.kernel.error.{ApplicationError, ErrorCode, KuiError}
import kui.message.domain.ports.{BrowseCluster, ClusterProfileSource}

/** The message domain's `ClusterProfileSource`, answered from this process's own configuration.
  *
  * The topic service has the identical adapter and for the identical reason (ADR-046): the distributed
  * deployment learns about clusters by asking the cluster service over HTTP, and the all-in-one deployment
  * has already loaded `kui.clusters[]` in order to wire that service. Making one process call itself over a
  * socket to read a list it is holding in memory would add a listener, a timeout and a failure mode to a
  * lookup that cannot fail.
  *
  * The answer is never stale here, because there is no upstream to be out of date with: `stale` is `false`
  * and `fetchedAt` is now. When the message service gets its own `Main` and the HTTP profile client behind
  * it, those two fields start carrying the cache's age, and nothing above this line changes.
  *
  * ==What it carries that the domain cannot see==
  *
  * A [[BrowseCluster]] deliberately holds no connection material, so that a password cannot reach a log line
  * that prints a request. The adapter that opens Kafka consumers does need it, so this class holds both: the
  * view the domain gets, and the [[connectionFor]] lookup only `infrastructure` can see.
  */
final class ConfiguredClusterProfiles[F[_]: {Clock, cats.Monad}] private (clusters: List[ClusterConfig])
    extends ClusterProfileSource[F] {

  private val byId: Map[ClusterId, ClusterConfig] = clusters.map(cluster => cluster.id -> cluster).toMap

  def cluster(id: ClusterId): F[Either[KuiError, BrowseCluster]] =
    byId.get(id) match {
      case None =>
        // Never an empty answer. "KUI has never heard of this cluster" and "this cluster has no
        // records" are different sentences with different remedies, and a browse that returned an
        // empty page for a typo would send the user looking at Kafka instead of at their URL.
        ApplicationError
          .NotFound("cluster", id.value, ErrorCode.ClusterNotFound)
          .asLeft[BrowseCluster]
          .pure[F]

      case Some(config) =>
        Clock[F].realTimeInstant.map(now =>
          BrowseCluster(
            id = config.id,
            name = config.name,
            readOnly = config.readOnly,
            fetchedAt = now,
            stale = false
          ).asRight[KuiError]
        )
    }

  /** The connection material for a cluster, for the adapter that opens Kafka clients. `None` is the same "not
    * configured" the domain sees as `KUI-CLUSTER-NOT-FOUND`.
    */
  def connectionFor(id: ClusterId): Option[ClusterConnection] = byId.get(id).map(_.connection)

  /** Every configured cluster's id, for whatever has to build one of something per cluster. */
  def ids: List[ClusterId] = clusters.map(_.id)
}

object ConfiguredClusterProfiles {

  def of[F[_]: {Clock, cats.Monad}](clusters: List[ClusterConfig]): ConfiguredClusterProfiles[F] =
    new ConfiguredClusterProfiles[F](clusters)
}
