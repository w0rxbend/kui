package kui.topic.application

import kui.cache.SnapshotCell
import kui.kernel.ClusterId
import kui.topic.domain.TopicSnapshot

/** One topic snapshot per cluster, refreshed in the background, released when the cluster goes away.
  *
  * Opening the topic list of a cluster with ten thousand topics must cost no admin call: the answer is a
  * scrape that already happened, stamped with when it happened, kept while the cluster is unreachable, and
  * refreshable on demand by a button that returns immediately rather than blocking on a cluster that is
  * already not answering.
  */
trait TopicSnapshots[F[_]] {

  /** The snapshot cell for a cluster, starting its scrape if this is the first ask.
    *
    * `None` when the cluster is not configured, which is a 404 at the edge and **not** an empty list: an
    * empty list of topics reads as "this cluster has no topics", which is a different and much more alarming
    * statement than "KUI has never heard of this cluster".
    */
  def of(cluster: ClusterId): F[Option[SnapshotCell[F, TopicSnapshot]]]

  /** Starts a refresh and returns immediately; `false` when the cluster is unknown. */
  def requestRefresh(cluster: ClusterId): F[Boolean]
}
