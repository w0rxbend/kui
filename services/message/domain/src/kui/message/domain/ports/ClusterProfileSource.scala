package kui.message.domain.ports

import java.time.Instant

import kui.kernel.ClusterId
import kui.kernel.error.KuiError

/** Everything the message domain knows about a cluster, and deliberately nothing more.
  *
  * The cluster service's own `ClusterProfile` carries the connection: bootstrap servers, a security mechanism
  * and credentials. None of that reaches this layer. The domain's questions are "does this cluster exist",
  * "what is it called" and "may I change it", and a type that answered those while also carrying a password
  * would put the password in every log line that printed a request.
  *
  * @param readOnly
  *   ADR-047's per-cluster half. Every mutation checks it **before any Kafka client is touched** — before
  *   serialisation, before a producer is created, before an offset is read
  * @param fetchedAt
  *   when this view of the cluster was obtained. It is here rather than hidden in the adapter because the
  *   answer may be served from a cache while the cluster service is down, and a screen that shows a
  *   three-minute-old view should be able to say so
  * @param stale
  *   whether this is the last known answer rather than a fresh one. Browsing continues on a stale profile —
  *   failing a browse because a *different* service restarted would make the two services one — and the
  *   capability report says `Degraded` while it does
  */
final case class BrowseCluster(
    id: ClusterId,
    name: String,
    readOnly: Boolean,
    fetchedAt: Instant,
    stale: Boolean
)

object BrowseCluster {
  given CanEqual[BrowseCluster, BrowseCluster] = CanEqual.derived
}

/** Where the message service learns about a cluster.
  *
  * The implementation adapts the shared `services/cluster/client` (ADR-046) and adds a last-known cache. It
  * is a port here because the domain must be able to state "a mutation on a read-only cluster is refused"
  * without knowing that clusters arrive over HTTP from another process.
  */
trait ClusterProfileSource[F[_]] {

  /** `Left(KUI-CLUSTER-NOT-FOUND)` for a cluster this deployment does not have, and
    * `Left(KUI-UPSTREAM-UNAVAILABLE)` only when there is neither an upstream nor a cached answer — a cold
    * start during an outage. Every other case answers, possibly with `stale = true`.
    */
  def cluster(id: ClusterId): F[Either[KuiError, BrowseCluster]]
}
