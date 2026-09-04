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
  *   what a person reads: the name the cluster's operator wrote in the configuration, which the owning
  *   service reports on its capability document and the gateway carries on every capability entry. A cluster
  *   whose service has not named it falls back to the id, which degrades rather than breaking.
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
  def of(
      states: Map[CapabilityKey, CapabilityState],
      names: Map[CapabilityKey, String] = Map.empty
  ): List[ClusterEntry] =
    states.toList
      .flatMap((key, state) => key.cluster.map(cluster => (cluster, key, state)))
      .groupBy((cluster, _, _) => cluster)
      .toList
      .flatMap { (cluster, entries) =>
        val worst =
          entries.map((_, _, state) => FeatureState.derive(Some(state), permitted = true)).minBy(severity)
        // The first name any of this cluster's services reported, in a stable order so that two services
        // naming one cluster differently cannot make the label flicker. The id is the fallback: showing
        // `prod-eu-1` is a degradation, showing nothing would be a blank row.
        val label = entries
          .sortBy((_, key, _) => key.service.value)
          .flatMap((_, key, _) => names.get(key))
          .headOption
          .getOrElse(cluster.value)

        Option.when(!isNotConfigured(worst))(ClusterEntry(cluster, label, worst))
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
