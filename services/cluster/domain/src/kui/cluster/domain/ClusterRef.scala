package kui.cluster.domain

import kui.kernel.ClusterId

/** A cluster's identity and its human label, and nothing that could leak.
  *
  * It exists so that nothing passes a whole `ClusterProfile` — with its bootstrap string and its secrets —
  * into a logger, a map key or a list row by habit. Every value in this context that describes a *finding*
  * about a cluster holds one of these rather than a profile, which turns "no secret reaches a response body"
  * from a code review into a property of the types.
  */
final case class ClusterRef(id: ClusterId, displayName: String)

object ClusterRef {
  given Ordering[ClusterRef] = Ordering.by(r => (r.displayName, r.id.value))
  given CanEqual[ClusterRef, ClusterRef] = CanEqual.derived
}
