package kui.ui.clusters

import com.raquo.laminar.api.L.*
import com.raquo.waypoint.Route

import kui.ui.kernel.api.{ApiClient, Bootstrap}
import kui.ui.kernel.feature.*
import kui.ui.kernel.state.{AuthState, CapabilityStore}

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

  /** This feature's health, for gating the Ping button while the page is open. */
  private val capability = CapabilityStore.featureState(FeatureId.Clusters, None, Val(true))

  // One element for the life of the page, so navigating away and back keeps the results, the typed
  // message and the scroll position (ADR-011 §3.3).
  private lazy val page: HtmlElement = ClustersPage(queries, capability)

  def id: FeatureId = FeatureId.Clusters

  def nav: NavEntry = ClustersRoutes.nav

  /** The same patterns the shell already registered, under the prefix it registered them with.
    *
    * The shell uses `ClustersRoutes` directly and never calls this, but `KuiFeature` requires it and a loaded
    * feature that could not name its own URLs would be a strange thing to ship.
    */
  def routes: List[Route[? <: Page, ?]] = ClustersRoutes.routes(ClustersFeature.uiPrefix)

  def render(page: Page): HtmlElement = this.page

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
