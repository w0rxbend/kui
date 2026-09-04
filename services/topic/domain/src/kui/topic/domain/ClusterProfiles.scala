package kui.topic.domain

import kui.kernel.ClusterId

/** A cluster's identity as a topic screen shows it.
  *
  * @param readOnly
  *   whether this deployment may change anything on the cluster. Nothing in M2 mutates, so nothing reads it
  *   yet; it is carried because the flag belongs to the cluster's identity rather than to the mutation that
  *   consults it, and a screen that renders a disabled action bar needs it before M5 does
  */
final case class ClusterRef(id: ClusterId, name: String, readOnly: Boolean)

object ClusterRef {
  given Ordering[ClusterRef] = Ordering.by((ref: ClusterRef) => ref.id.value)
  given CanEqual[ClusterRef, ClusterRef] = CanEqual.derived
}

/** Which clusters this KUI knows about, from the topic service's point of view.
  *
  * ==What it deliberately cannot tell you==
  *
  * It carries no connection material: no bootstrap addresses, no security mechanism, no credentials. The
  * adapter behind it holds all of that, because it is what builds the Kafka clients. A use case that could
  * see a password is a use case that could log one, and the cheapest way to guarantee it never does is for
  * the type it is written against not to have the field.
  */
trait ClusterProfiles[F[_]] {

  /** Every configured cluster, in id order. Empty is a legitimate answer and means either that nothing is
    * configured or that this service has never managed to reach the cluster service — the two are told apart
    * by the profile client's health, which the capability report folds in, not here.
    */
  def all: F[List[ClusterRef]]

  def get(id: ClusterId): F[Option[ClusterRef]]

  /** Registers a handler for the set of clusters changing, and returns the effect that deregisters it.
    *
    * A callback and not an `fs2.Stream`, per ADR-041 Amendment 3: a domain that imports a concrete runtime
    * type can no longer be read, tested or moved without that runtime. The handler is given the *whole* new
    * set rather than a delta, because the consumer's job — retaining exactly the snapshots whose clusters
    * still exist — is stated over a set and reconstructing one from deltas is how a removal gets missed.
    */
  def onChange(handler: Set[ClusterId] => F[Unit]): F[F[Unit]]
}
