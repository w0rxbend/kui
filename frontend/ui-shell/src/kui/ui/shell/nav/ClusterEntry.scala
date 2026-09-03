package kui.ui.shell.nav

import kui.contracts.capability.{CapabilityKey, CapabilityState}
import kui.kernel.ClusterId
import kui.ui.kernel.state.FeatureState

/** One cluster as the switcher shows it: identity and health, from the same place the sidebar gets them.
  *
  * ## Why this reads the capability registry and not the cluster service
  *
  * Three reasons, and together they are the whole design of the switcher.
  *
  * The shell must not hold cluster *data*. It sees the clusters feature only through its static route
  * declarations, and everything else goes through a dynamic import so the linker can keep the feature out of
  * the bundle every user downloads. A shell that fetched cluster DTOs would put the cluster contract's
  * decoders in that first bundle, including for deployments that have no cluster service at all.
  *
  * The status dot is a *health* question, and health lives in the registry by construction. Reading it from
  * anywhere else would give the switcher and the sidebar two different opinions about the same cluster.
  *
  * And the registry's stream is already open, already pushes changes and already debounces transitions, so
  * live status costs no new connection.
  *
  * @param displayName
  *   what a person reads. The registry carries no display name yet, so this falls back to the id — it
  *   degrades rather than breaking, and the gap is owed by the contract rather than patched with a second
  *   request from here.
  */
final case class ClusterEntry(clusterId: ClusterId, displayName: String, state: FeatureState)

object ClusterEntry {

  given CanEqual[ClusterEntry, ClusterEntry] = CanEqual.derived

  /** Every cluster the registry knows, sorted by display name, with `NotConfigured` clusters dropped.
    *
    * Dropped and not dimmed, which is the same rule the sidebar applies to a feature: this deployment has no
    * such thing, and showing it invites a click that can never work.
    *
    * One cluster usually has several entries — one per service that is scoped to it — and they are folded to
    * the *worst* of them. A cluster whose topic service is fine and whose cluster service is unreachable is
    * not a healthy cluster, and a dot that reported the best of its services would be reassuring and wrong.
    */
  def of(states: Map[CapabilityKey, CapabilityState]): List[ClusterEntry] =
    states.toList
      .flatMap((key, state) => key.cluster.map(_ -> state))
      .groupBy((cluster, _) => cluster)
      .toList
      .flatMap { (cluster, entries) =>
        val worst =
          entries.map((_, state) => FeatureState.derive(Some(state), permitted = true)).minBy(severity)
        Option.when(!isNotConfigured(worst))(ClusterEntry(cluster, cluster.value, worst))
      }
      .sortBy(entry => (entry.displayName.toLowerCase, entry.clusterId.value))

  /** Worst first, so `minBy` picks the state a person most needs to know about. */
  private def severity(state: FeatureState): Int =
    state match {
      case FeatureState.Unavailable(_, _, _) => 0
      case FeatureState.Forbidden => 1
      case FeatureState.Degraded(_) => 2
      case FeatureState.Ready => 3
      // Last, so a cluster with one unconfigured service and one working one is not dropped from the list.
      case FeatureState.NotConfigured => 4
    }

  private def isNotConfigured(state: FeatureState): Boolean =
    state match {
      case FeatureState.NotConfigured => true
      case _ => false
    }
}
