package kui.consumer.infrastructure

import cats.Applicative
import cats.syntax.all.*
import fs2.Stream

import kui.config.ClusterConfig
import kui.consumer.application.ClusterProfileSource
import kui.consumer.domain.ClusterProfileView
import kui.kernel.ClusterId
import kui.kernel.cluster.ClusterConnection
import kui.kernel.error.{ApplicationError, ErrorCode, KuiError}

/** The consumer service's `ClusterProfileSource`, answered from this process's own configuration.
  *
  * ==Why this exists beside [[ClusterProfileSourceAdapter]]==
  *
  * ADR-046 says a Kafka-facing service learns about clusters by asking the cluster service over HTTP, and
  * `services/cluster/client` is that consumer — [[ClusterProfileSourceAdapter]] wraps it. That hop is what
  * makes the *distributed* deployment work, where this service is a separate container with no configuration
  * file of its own.
  *
  * The all-in-one deployment (ADR-005) is a single process that has already loaded `kui.clusters[]` in order
  * to wire the cluster service. Making it call itself over a socket to read a list it is holding in memory
  * would add a listener, a timeout and a failure mode to a lookup that cannot fail. So all-in-one uses this
  * and the distributed shape uses the adapter. Both satisfy the same port, which is the point of the port,
  * and the topic service made exactly this choice for exactly this reason (`ConfiguredClusterProfiles`).
  *
  * ==What it carries that the application layer cannot see==
  *
  * A `ClusterProfileView` has the id, the display name and `readOnly`, and no connection material. The
  * adapter that opens Kafka clients does need it, so this class holds both: the views it hands the
  * application layer, and the [[connectionFor]] lookup that only `infrastructure` can see. That is what keeps
  * a password out of a use case, an audit record and a log line by construction rather than by review.
  */
final class ConfiguredProfileSource[F[_]: Applicative](clusters: List[ClusterConfig])
    extends ClusterProfileSource[F] {

  private val views: List[ClusterProfileView] =
    clusters
      .map(cluster => ClusterProfileView(cluster.id, cluster.name, cluster.readOnly))
      .sortBy(_.cluster.value)

  private val connections: Map[ClusterId, ClusterConnection] =
    clusters.map(cluster => cluster.id -> cluster.connection).toMap

  def profileOf(cluster: ClusterId): F[Either[KuiError, ClusterProfileView]] =
    views
      .find(_.cluster == cluster)
      .toRight(
        ApplicationError.NotFound("cluster", cluster.value, ErrorCode.ClusterNotFound): KuiError
      )
      .pure[F]

  def all: F[List[ClusterProfileView]] = views.pure[F]

  /** Never emits.
    *
    * A statically configured set of clusters changes when the process is restarted with a different file, and
    * a restart replaces every subscriber along with everything else. This is an honest implementation of the
    * port rather than a stub: the port promises to report changes, and there are none to report. When the
    * metadata store can edit profiles at run time (M5), the implementation behind that promise becomes
    * [[ClusterProfileSourceAdapter]], which does have changes to report.
    */
  def changes: Stream[F, ClusterId] = Stream.empty

  /** The connection material for one cluster, for the module that builds Kafka clients.
    *
    * `None` means the cluster is not configured, which every caller must turn into `KUI-CLUSTER-NOT-FOUND`
    * rather than into an empty result: "KUI has never heard of this cluster" and "this cluster has no
    * consumer groups" are different screens.
    */
  def connectionFor(cluster: ClusterId): Option[ClusterConnection] = connections.get(cluster)
}
