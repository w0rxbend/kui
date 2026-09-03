package kui.cluster.application

import java.time.Instant

import kui.cluster.domain.{ClusterRef, ClusterTopology}

/** How old the data in a view is, and why it is not newer.
  *
  * The API layer maps these four cases onto the response envelope's section states; the browser renders each
  * differently. They are an application type and not a wire type on purpose — the application layer never
  * sees the wire.
  */
enum SnapshotFreshness {

  /** No refresh has completed yet. Not an error: it is what the first two seconds of a process look like, and
    * rendering it as a failure makes every deployment look broken at rollout.
    */
  case Loading

  case Fresh(scrapedAt: Instant)

  /** Data is present and the last refresh failed. `since` is when the failures started and is sticky across a
    * changing reason: a user asks "how long has this been broken", not "how long has it been broken in this
    * particular way".
    */
  case Stale(scrapedAt: Instant, reason: String, since: Instant)

  /** Nothing has ever been fetched successfully and the last attempt failed. */
  case Unavailable(reason: String, since: Instant)

  /** True only for `Fresh`. The one derived question every caller asks. */
  def isCurrent: Boolean = this match {
    case Fresh(_) => true
    case Loading | Stale(_, _, _) | Unavailable(_, _) => false
  }

  /** When the data was produced, for the "as of" label. `None` when there is no data. */
  def scrapedAtOption: Option[Instant] = this match {
    case Fresh(at) => Some(at)
    case Stale(at, _, _) => Some(at)
    case Loading | Unavailable(_, _) => None
  }
}

object SnapshotFreshness {
  given CanEqual[SnapshotFreshness, SnapshotFreshness] = CanEqual.derived
}

/** One cluster's topology as a reader sees it: what is known, and how well it is known.
  *
  * `cluster` is a `ClusterRef` and not a `ClusterProfile`, so a view can be logged whole and so the API layer
  * cannot reach a secret from a value it is about to serialise.
  */
final case class TopologyView(
    cluster: ClusterRef,
    topology: Option[ClusterTopology],
    freshness: SnapshotFreshness
) {
  def isRenderable: Boolean = topology.isDefined
}

object TopologyView {
  given CanEqual[TopologyView, TopologyView] = CanEqual.derived
}
