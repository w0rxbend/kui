package kui.ui.shell.nav

import kui.kernel.ClusterId

/** Picks the cluster for a user who has no choice to make.
  *
  * ## The problem
  *
  * Every cluster-scoped destination — Topics, Consumers, Messages — has a cluster id in its URL, so
  * `Navigation` leaves those entries out of the sidebar until a cluster is chosen. That rule is right: an
  * entry whose link would be `/ui/clusters//topics` is a dead link, and a dead link in a sidebar is worse
  * than no link.
  *
  * It is also unfriendly in the deployment most people start with. Somebody who runs the quickstart has
  * exactly one cluster, has never been asked to choose anything, and sees a sidebar with no Topics and no
  * Consumers in it. There is nothing on screen saying that opening the cluster switcher and picking the only
  * entry in it is what makes the rest of the application appear.
  *
  * ## The rule
  *
  * When the registry knows exactly one cluster and nobody has chosen one, that cluster is chosen. With two or
  * more it stays unchosen, because then it really is a choice and guessing it would put an operator on a
  * cluster they did not pick — the failure the switcher's colour tags exist to prevent.
  *
  * An existing choice is never overridden, including one restored from `localStorage` or set from a URL. This
  * only ever fills in a blank.
  *
  * It is a function of two values rather than a subscription so that the rule can be read and tested on its
  * own; `ClusterSwitcher` is what runs it against the live signals.
  */
object SoleCluster {

  /** The cluster to select, or `None` to leave the selection as it is. */
  def choice(entries: List[ClusterEntry], chosen: Option[ClusterId]): Option[ClusterId] =
    (entries, chosen) match {
      case (only :: Nil, None) => Some(only.clusterId)
      case _ => None
    }
}
