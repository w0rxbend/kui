package kui.topic.infrastructure

import cats.Applicative
import cats.effect.kernel.Ref
import cats.syntax.all.*

import kui.config.ClusterConfig
import kui.kernel.ClusterId
import kui.kernel.cluster.ClusterConnection
import kui.topic.domain.{ClusterProfiles, ClusterRef}

/** The topic domain's `ClusterProfiles` port, answered from this process's own configuration.
  *
  * ==Why this exists rather than the HTTP profile client==
  *
  * ADR-046 says a Kafka-facing service learns about clusters by asking the cluster service over HTTP, and
  * `services/cluster/client` is that consumer. That hop is what makes the *distributed* deployment work,
  * where the topic service is a separate container that has no configuration file of its own.
  *
  * The all-in-one deployment (ADR-005) is a single process that has already loaded `kui.clusters[]` in order
  * to wire the cluster service. Making it call itself over a socket to read a list it is holding in memory
  * would add a listener, a timeout and a failure mode to a lookup that cannot fail. So all-in-one uses this,
  * and the distributed shape will use `HttpClusterProfiles` when the topic service gets its own `Main`. Both
  * satisfy the same port, which is the point of the port.
  *
  * ==What it carries that the domain cannot see==
  *
  * A `ClusterRef` deliberately has no connection material (see `ClusterProfiles`' own comment). The adapter
  * that talks to Kafka does need it, so this class holds both: the refs it hands the domain, and the
  * [[connectionFor]] lookup that only `infrastructure` can see.
  */
final class ConfiguredClusterProfiles[F[_]: Applicative] private (
    clusters: List[ClusterConfig],
    handlers: Ref[F, Vector[Set[ClusterId] => F[Unit]]]
) extends ClusterProfiles[F] {

  private val refs: List[ClusterRef] =
    clusters.map(cluster => ClusterRef(cluster.id, cluster.name, cluster.readOnly)).sorted

  private val connections: Map[ClusterId, ClusterConnection] =
    clusters.map(cluster => cluster.id -> cluster.connection).toMap

  def all: F[List[ClusterRef]] = refs.pure[F]

  def get(id: ClusterId): F[Option[ClusterRef]] = refs.find(_.id == id).pure[F]

  /** Registers a handler and returns its deregistration.
    *
    * The handler is never called. A statically configured set of clusters changes when the process is
    * restarted with a different file, and a restart replaces the handler along with everything else. This is
    * an honest implementation of the port rather than a stub: the port promises to notify on change, and
    * there is no change to notify about. When the metadata store can edit profiles at run time (M5), the
    * implementation behind that promise becomes the HTTP client, which does have changes to report.
    */
  def onChange(handler: Set[ClusterId] => F[Unit]): F[F[Unit]] =
    handlers.update(_ :+ handler).as(handlers.update(_.filterNot(_ eq handler)))

  /** The connection material for a cluster, for the adapter that opens Kafka clients.
    *
    * `None` means the cluster is not configured, which every caller must turn into
    * `TopicError.ClusterNotFound` rather than into an empty result — the difference between "KUI has never
    * heard of this cluster" and "this cluster has no topics".
    */
  def connectionFor(id: ClusterId): Option[ClusterConnection] = connections.get(id)

  /** Every configured cluster's id, for the component that has to build one snapshot per cluster. */
  def ids: List[ClusterId] = refs.map(_.id)
}

object ConfiguredClusterProfiles {

  def of[F[_]: {Applicative, cats.effect.kernel.Sync}](
      clusters: List[ClusterConfig]
  ): F[ConfiguredClusterProfiles[F]] =
    Ref
      .of[F, Vector[Set[ClusterId] => F[Unit]]](Vector.empty)
      .map(new ConfiguredClusterProfiles[F](clusters, _))
}
