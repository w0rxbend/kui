package kui.cluster.domain

/** The verdict of a cheap, bounded connection attempt. */
enum Connectivity {

  /** A connection was opened and KUI authenticated. */
  case Reachable

  /** The cluster answered but rejected KUI's credentials. Not retryable: the configuration must change first.
    */
  case AuthenticationFailed(detail: String)

  /** No answer within the probe's own bound. */
  case Unreachable(detail: String)

  def isReachable: Boolean = this match {
    case Reachable => true
    case AuthenticationFailed(_) | Unreachable(_) => false
  }
}

object Connectivity {
  given CanEqual[Connectivity, Connectivity] = CanEqual.derived
}

/** "Can KUI talk to this cluster right now?" — bounded, read-only, and cheaper than a topology refresh.
  *
  * It is a separate port from `ClusterAdmin` because the two answer different questions for different callers
  * and fail differently. `describeCluster` is on the read path: it runs on a thirty-second loop, it may take
  * the full admin timeout, and its failure means "serve the previous snapshot as stale". A probe is on the
  * decision path — the capability report, and eventually a wizard's "test connection" button — and needs a
  * fast, bounded yes/no that distinguishes *cannot reach* from *reached but refused*, with no topology
  * attached. Folding them together would make the probe pay for a full describe, or make the refresh inherit
  * the probe's short timeout.
  *
  * `detail` is display text and must never contain a host, a URL with credentials or a JAAS string; the
  * adapter is responsible for that and its suite asserts it.
  */
trait ConnectivityProbe[F[_]] {
  def probe(profile: ClusterProfile): F[Connectivity]
}
