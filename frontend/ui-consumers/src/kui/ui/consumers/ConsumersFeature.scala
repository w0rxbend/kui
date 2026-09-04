package kui.ui.consumers

import com.raquo.laminar.api.L.*
import com.raquo.waypoint.Route
import org.scalajs.dom

import kui.kernel.{ClusterId, GroupId}
import kui.ui.consumers.detail.GroupDetailPage
import kui.ui.consumers.list.GroupListPage
import kui.ui.kernel.api.{ApiClient, Bootstrap}
import kui.ui.kernel.feature.*
import kui.ui.kernel.prefs.Timezone
import kui.ui.kernel.state.AuthState

/** The consumer-groups microfrontend.
  *
  * ## The one rule about this class
  *
  * It is constructed only through `js.dynamicImport`, in `kui.ui.shell.FeatureRegistryImpl`, and nothing in
  * the shell may name this type in a signature, a `val` or anywhere else. That import is a *split border* to
  * the Scala.js linker: everything reachable only through it is emitted as a separate JavaScript module the
  * browser fetches on demand. One static reference from shell code and the linker puts the whole feature in
  * `main.js`, where every user downloads it on first paint — including users whose deployment has no consumer
  * service at all. Nothing about the source looks different when that happens, which is why
  * `checkBundleShape` asserts the shape of the linked output instead of trusting a review.
  *
  * The feature's *static* half — its nav entry, route patterns and `history.state` codec — lives in
  * `ConsumersRoutes` and is named from the shell normally, because all of it has to be known before this
  * class is downloaded (ADR-012 amendment 2).
  *
  * ## Why it builds its own client and state
  *
  * The constructor takes nothing, because a dynamic import cannot pass arguments. So the feature reaches for
  * the kernel's own singletons — the bootstrap block the gateway injected and the session — exactly as the
  * shell does, and creates its own per-instance `ConsumersQueries` (PLAN §21: feature state is a class
  * holding `Var`s, never a global).
  *
  * ## No capability signal is held here
  *
  * These screens are read-only, so there is no action to gate, and the shell already draws the banner and the
  * fallback panel from the same store. A second subscription would be a second opinion about one state on one
  * screen. `ClustersFeature` and `TopicsFeature` made the same choice; `docs/frontend/features.md` records it
  * as the pattern.
  */
final class ConsumersFeature extends KuiFeature {

  private val queries = new ConsumersQueries(ConsumersFeature.api)

  /** Which of this feature's pages is on screen.
    *
    * The shell calls `render` with the page its router decoded, and a row click inside the list sets this
    * directly. Both paths end here, so the element below is built once and never rebuilt — which is what
    * keeps a sort order, a scroll position and a search box across a navigation.
    */
  private val current: Var[Page] = Var(ConsumersRoutes.landing)

  private lazy val root: HtmlElement =
    // Keyed on *which screen*, not on the whole page, so that a change that only alters what is inside a
    // screen does not throw away the scroll position and whatever the user was typing.
    div(child <-- current.signal.map(screenOf).distinct.map(build))

  private def screenOf(page: Page): Screen =
    page match {
      case ConsumersPageId.Detail(clusterId, groupId) => Screen.Detail(clusterId, groupId)
      case ConsumersPageId.List(clusterId) => Screen.Listing(clusterId)
      case _ => Screen.Listing("")
    }

  private def build(screen: Screen): HtmlElement =
    screen match {
      case Screen.Listing(clusterId) => listing(ClusterId.from(clusterId).toOption)
      case Screen.Detail(clusterId, groupId) => detail(ClusterId.from(clusterId).toOption, groupId)
    }

  /** The group list for one cluster.
    *
    * A cluster id the URL held but that will not parse renders the page's own empty container rather than
    * failing: the id came from something a user typed or a bookmark kept, and a blank screen with no
    * explanation is the worst possible answer to it.
    */
  private def listing(cluster: Option[ClusterId]): HtmlElement =
    cluster match {
      case Some(id) =>
        GroupListPage(
          cluster = id,
          queries = queries,
          navigate = (clusterId, groupId) => goTo(ConsumersPageId.Detail(clusterId.value, groupId)),
          hrefFor = (clusterId, groupId) => hrefOf(ConsumersPageId.Detail(clusterId.value, groupId)),
          zone = Timezone.choice.signal
        )
      case None => div(cls := ConsumersCss.Page, dataAttr("testid") := "page-consumers-list")
    }

  /** One group.
    *
    * A cluster id or a group id the URL held but that will not parse falls back to the list, which is where
    * the user can see what does exist — rather than to a blank page, which tells them nothing.
    */
  private def detail(cluster: Option[ClusterId], group: String): HtmlElement =
    (cluster, GroupId.from(group).toOption) match {
      case (Some(clusterId), Some(groupId)) =>
        GroupDetailPage(
          cluster = clusterId,
          group = groupId,
          queries = queries,
          backHref = hrefOf(ConsumersPageId.List(clusterId.value)),
          zone = Timezone.choice.signal
        )
      case (Some(clusterId), None) => listing(Some(clusterId))
      case _ => listing(None)
    }

  /** Moves to a group without reloading the application.
    *
    * A row is a real `<a>` with a real `href`, so copying it, bookmarking it and opening it in a new tab all
    * work; an ordinary click is intercepted here, the URL is pushed, and the page swaps. The shell's router
    * owns the browser's history for its own pages and this feature has no reference to it — TD-020 records
    * the navigation port that is still owed.
    */
  def goTo(page: ConsumersPageId): Unit = {
    dom.window.history.pushState((), "", hrefOf(page))
    current.set(page)
  }

  def hrefOf(page: ConsumersPageId): String =
    ConsumersRoutes
      .routes(ConsumersFeature.uiPrefix)
      .flatMap(route => route.relativeUrlForPage(page))
      .headOption
      .getOrElse(ConsumersFeature.uiPrefix)

  def id: FeatureId = FeatureId.Consumers

  def nav: NavEntry = ConsumersRoutes.nav

  /** The same patterns the shell already registered, under the prefix it registered them with. */
  def routes: List[Route[? <: Page, ?]] = ConsumersRoutes.routes(ConsumersFeature.uiPrefix)

  def render(page: Page): HtmlElement = {
    current.set(page)
    root
  }

  def unavailableView(reason: UnavailableReason, retry: Observer[Unit]): HtmlElement =
    p(cls := ConsumersCss.Fallback, dataAttr("testid") := "consumers-unavailable", Messages.UnavailableView)

  /** None yet. The topic page's Consumers tab is a panel contributed into the kernel's `topic.tabs` slot, and
    * it is fed by the gateway's topic-overview aggregation rather than by this feature's own client — that
    * seam is GRP-035's work, and contributing an empty panel before it exists would put a tab on the topic
    * page with nothing behind it.
    */
  override def panels: List[PanelContribution] = Nil

  /** The caches, so the screens this feature builds can read them. */
  private[consumers] def state: ConsumersQueries = queries
}

/** Which screen is on show, with everything that only changes what is *inside* it stripped out. */
private enum Screen {
  case Listing(clusterId: String)
  case Detail(clusterId: String, groupId: String)
}

private object Screen {
  given CanEqual[Screen, Screen] = CanEqual.derived
}

object ConsumersFeature {

  private lazy val bootstrap: Bootstrap = Bootstrap.read()

  /** Built from the same bootstrap block the shell reads, so the feature calls the same origin under the same
    * deployment prefix without being told.
    */
  private lazy val api: ApiClient = ApiClient.make(Bootstrap.gatewayRoot(bootstrap), AuthState.current)

  private lazy val uiPrefix: String = s"${bootstrap.basePath.stripSuffix("/")}/ui"
}
