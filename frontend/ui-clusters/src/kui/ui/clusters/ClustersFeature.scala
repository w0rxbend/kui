package kui.ui.clusters

import com.raquo.laminar.api.L.*
import com.raquo.waypoint.Route
import org.scalajs.dom

import kui.kernel.ClusterId
import kui.ui.clusters.brokers.BrokersPage
import kui.ui.clusters.dashboard.DashboardPage
import kui.ui.kernel.api.{ApiClient, Bootstrap}
import kui.ui.kernel.feature.*
import kui.ui.kernel.prefs.Timezone
import kui.ui.kernel.state.AuthState

/** The clusters microfrontend.
  *
  * ## The one rule about this class
  *
  * It is constructed only through `js.dynamicImport`, in `kui.ui.shell.FeatureRegistryImpl`, and nothing in
  * the shell may name this type in a signature, a `val` or anywhere else. That import is a *split border* to
  * the Scala.js linker: everything reachable only through it is emitted as a separate JavaScript module the
  * browser fetches on demand. One static reference from shell code and the linker puts the whole feature in
  * `main.js`, where every user downloads it on first paint — including users whose deployment has no cluster
  * service at all. Nothing about the source looks different when that happens, which is why
  * `checkBundleShape` asserts the shape of the linked output instead of trusting a review.
  *
  * The feature's *static* half — its nav entry, route patterns and `history.state` codec — lives in
  * `ClustersRoutes` and is named from the shell normally, because all of it has to be known before this class
  * is downloaded (ADR-012 amendment 2).
  *
  * ## Why it builds its own client and state
  *
  * The constructor takes nothing, because a dynamic import cannot pass arguments. So the feature reaches for
  * the kernel's own singletons — the bootstrap block the gateway injected, the session, the capability store
  * — exactly as the shell does, and creates its own per-instance `ClustersQueries` (PLAN §21: feature state
  * is a class holding `Var`s, never a global).
  */
final class ClustersFeature extends KuiFeature {

  private val api: ApiClient = ClustersFeature.api

  private val queries = new ClustersQueries(api)

  // No capability signal is held here. M1's cluster screens are read-only, so there is no action to gate,
  // and the shell already draws the banner and the fallback panel from the same store. Holding a second
  // subscription would be a second opinion about the same state on the same screen.

  /** Which of this feature's pages is on screen.
    *
    * The shell calls `render` with the page its router decoded, and a row click inside the dashboard sets
    * this directly. Both paths end here, so the element below is built once and never rebuilt — which is what
    * keeps a sort order, a scroll position and a toggle across a navigation.
    */
  private val current: Var[Page] = Var(ClustersRoutes.landing)

  private lazy val root: HtmlElement =
    div(
      child <-- current.signal.map {
        case ClustersPageId.Brokers(clusterId) =>
          brokers(ClusterId.from(clusterId).toOption)
        case _ => dashboard
      }
    )

  private lazy val dashboard: HtmlElement =
    DashboardPage(
      queries = queries,
      navigate = goToBrokers,
      hrefFor = id => hrefOf(ClustersPageId.Brokers(id.value)),
      zone = Timezone.choice.signal
    )

  /** The brokers page for one cluster, or the dashboard when the URL held something that is not a cluster id.
    *
    * A malformed id in a hand-typed URL lands the user back on the list rather than on a blank page: the
    * dashboard is where they can see which ids exist, which is what they needed.
    */
  private def brokers(cluster: Option[ClusterId]): HtmlElement =
    cluster.fold(dashboard)(id => BrokersPage(Val(id), queries, backHref = hrefOf(ClustersPageId.Overview)))

  /** Moves to a cluster's brokers without reloading the application.
    *
    * The row is a real `<a>` with a real `href`, so copying it, bookmarking it and opening it in a new tab
    * all work; an ordinary click is intercepted here, the URL is pushed, and the page swaps. The shell's
    * router owns the browser's history for its own pages and this feature has no reference to it — see this
    * task's recorded deviation, which owes a proper navigation port to the shell.
    */
  private def goToBrokers(cluster: ClusterId): Unit = {
    val page = ClustersPageId.Brokers(cluster.value)
    dom.window.history.pushState((), "", hrefOf(page))
    current.set(page)
  }

  private def hrefOf(page: ClustersPageId): String =
    ClustersRoutes
      .routes(ClustersFeature.uiPrefix)
      .flatMap(route => route.relativeUrlForPage(page))
      .headOption
      .getOrElse(ClustersFeature.uiPrefix)

  def id: FeatureId = FeatureId.Clusters

  def nav: NavEntry = ClustersRoutes.nav

  /** The same patterns the shell already registered, under the prefix it registered them with.
    *
    * The shell uses `ClustersRoutes` directly and never calls this, but `KuiFeature` requires it and a loaded
    * feature that could not name its own URLs would be a strange thing to ship.
    */
  def routes: List[Route[? <: Page, ?]] = ClustersRoutes.routes(ClustersFeature.uiPrefix)

  def render(page: Page): HtmlElement = {
    current.set(page)
    root
  }

  /** This feature's own half of the shell's fallback panel.
    *
    * Only the feature can write the sentence that matters here — what a user can still do while the cluster
    * service is down. The reason, the "since", the retry and the list of other working features are the
    * shell's, and it draws them around this.
    */
  def unavailableView(reason: UnavailableReason, retry: Observer[Unit]): HtmlElement =
    p(cls := ClustersCss.Fallback, dataAttr("testid") := "clusters-unavailable", Messages.UnavailableView)

  /** None. The `FeaturePanel` mechanism has its own unit test in the kernel; a real cross-feature panel
    * arrives in M4, when there is a second feature to contribute one to.
    */
  override def panels: List[PanelContribution] = Nil
}

object ClustersFeature {

  private lazy val bootstrap: Bootstrap = Bootstrap.read()

  /** Built from the same bootstrap block the shell reads, so the feature calls the same origin under the same
    * deployment prefix without being told.
    */
  private lazy val api: ApiClient = ApiClient.make(Bootstrap.gatewayRoot(bootstrap), AuthState.current)

  private lazy val uiPrefix: String = s"${bootstrap.basePath.stripSuffix("/")}/ui"
}
