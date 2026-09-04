package kui.consumer.application

import fs2.Stream

import kui.consumer.domain.ClusterProfileView
import kui.kernel.ClusterId
import kui.kernel.error.KuiError

/** How this service learns which clusters exist and what it may do to them (ADR-036, ADR-043, ADR-046).
  *
  * It lives in the application layer rather than the domain, even though the task sketch puts it beside the
  * other ports: `changes` is an fs2 `Stream`, and rule A1 keeps the domain to `libs/kernel` and cats-core.
  * The domain states the rule that a read-only cluster refuses mutations; it does not need a stream in order
  * to state it.
  *
  * `profileOf` never fails while a last-known profile is held. A cluster service that is restarting must
  * degrade this one, not stop it.
  */
trait ClusterProfileSource[F[_]] {

  def profileOf(cluster: ClusterId): F[Either[KuiError, ClusterProfileView]]

  /** Every cluster this service is currently serving. The snapshot registry keys its cells on this. */
  def all: F[List[ClusterProfileView]]

  /** Cluster ids whose profile has changed, so that anything keyed on one can be dropped and rebuilt. */
  def changes: Stream[F, ClusterId]
}
