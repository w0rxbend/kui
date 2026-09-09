package kui.alerts.application

import kui.kernel.ClusterId
import kui.kernel.error.KuiError

/** One cluster, as this service needs to see it: a name for a screen and a flag for a refusal.
  *
  * No connection material. The adapter that opens admin clients holds that, and keeping it out of the type
  * the application layer sees is what keeps a password out of a use case, an audit record and a log line by
  * construction rather than by review.
  */
final case class ClusterProfileView(cluster: ClusterId, displayName: String, readOnly: Boolean)

object ClusterProfileView {
  given CanEqual[ClusterProfileView, ClusterProfileView] = CanEqual.derived
}

/** How this service learns which clusters exist and what it may do to them (ADR-036, ADR-046).
  *
  * There is no `changes` here, unlike the consumer service's port. This service holds no per-cluster snapshot
  * keyed on a profile: the event store is keyed on a `ClusterId` and a cluster that disappears from the
  * configuration takes its events with it at the next restart, which is the only moment a statically
  * configured list can change. A stream with nothing to publish is a seam somebody later mistakes for one.
  */
trait ClusterProfileSource[F[_]] {

  def profileOf(cluster: ClusterId): F[Either[KuiError, ClusterProfileView]]

  /** Every cluster this service is serving, in id order. The capability report is built from it, which is why
    * a cluster with nothing wrong is still in it: a cluster missing from the report reads as a service that
    * has never heard of it, which the browser draws as a service being down.
    */
  def all: F[List[ClusterProfileView]]
}
