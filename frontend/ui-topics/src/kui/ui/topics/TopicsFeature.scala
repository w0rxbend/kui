package kui.ui.topics

import com.raquo.laminar.api.L.*
import com.raquo.waypoint.Route
import org.scalajs.dom

import kui.kernel.{ClusterId, TopicName}
import kui.ui.kernel.api.{ApiClient, Bootstrap}
import kui.ui.kernel.feature.*
import kui.ui.kernel.prefs.{Favourites, Timezone}
import kui.ui.kernel.state.AuthState
import kui.ui.topics.detail.TopicDetailPage
import kui.ui.topics.list.TopicListPage

/** The topics microfrontend.
  *
  * ## The one rule about this class
  *
  * It is constructed only through `js.dynamicImport`, in `kui.ui.shell.FeatureRegistryImpl`, and nothing in
  * the shell may name this type in a signature, a `val` or anywhere else. That import is a *split border* to
  * the Scala.js linker: everything reachable only through it is emitted as a separate JavaScript module the
  * browser fetches on demand. One static reference from shell code and the linker puts the whole feature in
  * `main.js`, where every user downloads it on first paint — including users whose deployment has no topic
  * service at all. Nothing about the source looks different when that happens, which is why
  * `checkBundleShape` asserts the shape of the linked output instead of trusting a review.
  *
  * The feature's *static* half — its nav entry, route patterns and `history.state` codec — lives in
  * `TopicsRoutes` and is named from the shell normally, because all of it has to be known before this class
  * is downloaded (ADR-012 amendment 2).
  *
  * ## Why it builds its own client and state
  *
  * The constructor takes nothing, because a dynamic import cannot pass arguments. So the feature reaches for
  * the kernel's own singletons — the bootstrap block the gateway injected and the session — exactly as the
  * shell does, and creates its own per-instance `TopicsQueries` (PLAN §21: feature state is a class holding
  * `Var`s, never a global).
  *
  * ## No capability signal is held here
  *
  * M2's topic screens are read-only, so there is no action to gate, and the shell already draws the banner
  * and the fallback panel from the same store. A second subscription would be a second opinion about one
  * state on one screen. `ClustersFeature` made the same choice and recorded it as a deviation; it is now the
  * pattern, and `docs/frontend/features.md` says so.
  */
final class TopicsFeature extends KuiFeature {

  private val queries = new TopicsQueries(TopicsFeature.api)

  /** Starred topics, per cluster, in `localStorage`. Namespaced so that this feature's favourites cannot
    * collide with another feature's (PLAN §21).
    */
  private val favourites = new Favourites("kui.topics.favourites")

  /** Which of this feature's pages is on screen.
    *
    * The shell calls `render` with the page its router decoded, and a row click inside the list sets this
    * directly. Both paths end here, so the element below is built once and never rebuilt — which is what
    * keeps a sort order, a scroll position and a search box across a navigation.
    */
  private val current: Var[Page] = Var(TopicsRoutes.landing)

  private lazy val root: HtmlElement =
    // Keyed on *which screen*, not on the whole page: switching a topic's tab changes the page but not the
    // screen, and rebuilding the element for it would throw away the scroll position and whatever the user
    // was typing. `distinct` is what keeps that from happening.
    div(child <-- current.signal.map(screenOf).distinct.map(build))

  /** Which screen a page belongs to, ignoring anything that only changes what is *inside* it. */
  private def screenOf(page: Page): Screen =
    page match {
      case TopicsPageId.Detail(clusterId, topic, _) => Screen.Detail(clusterId, topic)
      case TopicsPageId.List(clusterId) => Screen.Listing(clusterId)
      case _ => Screen.Listing("")
    }

  private def build(screen: Screen): HtmlElement =
    screen match {
      case Screen.Listing(clusterId) => listing(ClusterId.from(clusterId).toOption)
      case Screen.Detail(clusterId, topic) => detail(ClusterId.from(clusterId).toOption, topic)
    }

  /** The topic list for one cluster.
    *
    * A cluster id the URL held but that will not parse renders the page's own empty container rather than
    * failing: the id came from something a user typed or a bookmark kept, and a blank screen with no
    * explanation is the worst possible answer to it.
    */
  private def listing(cluster: Option[ClusterId]): HtmlElement =
    cluster match {
      case Some(id) =>
        TopicListPage(
          cluster = id,
          queries = queries,
          favourites = favourites,
          navigate = (clusterId, topic) => goTo(TopicsPageId.Detail(clusterId.value, topic)),
          hrefFor = (clusterId, topic) => hrefOf(TopicsPageId.Detail(clusterId.value, topic)),
          zone = Timezone.choice.signal
        )
      case None => div(cls := TopicsCss.Page, dataAttr("testid") := "page-topics-list")
    }

  /** One topic.
    *
    * A cluster id or a topic name the URL held but that will not parse falls back to the list, which is where
    * the user can see what does exist — rather than to a blank page, which tells them nothing.
    */
  private def detail(cluster: Option[ClusterId], topic: String): HtmlElement =
    (cluster, TopicName.from(topic).toOption) match {
      case (Some(clusterId), Some(topicName)) =>
        TopicDetailPage(
          cluster = clusterId,
          topic = topicName,
          // Read from the route, so the URL is the one source of truth for which tab is open.
          tab = current.signal.map {
            case TopicsPageId.Detail(_, _, chosen) => chosen
            case _ => TopicTab.Default
          },
          queries = queries,
          onTab = chosen => goTo(TopicsPageId.Detail(clusterId.value, topicName.value, chosen)),
          zone = Timezone.choice.signal,
          backHref = hrefOf(TopicsPageId.List(clusterId.value)),
          // The features that are *loaded*. M4's Consumers tab appears here by registration, and a feature
          // that has not been downloaded contributes no tab and is not fetched to find out.
          features = FeatureRegistry.loaded
        )
      case (Some(clusterId), None) => listing(Some(clusterId))
      case _ => listing(None)
    }

  /** Moves to a topic without reloading the application.
    *
    * A row is a real `<a>` with a real `href`, so copying it, bookmarking it and opening it in a new tab all
    * work; an ordinary click is intercepted here, the URL is pushed, and the page swaps. The shell's router
    * owns the browser's history for its own pages and this feature has no reference to it — TD-020 records
    * the navigation port that is still owed.
    */
  def goTo(page: TopicsPageId): Unit = {
    dom.window.history.pushState((), "", hrefOf(page))
    current.set(page)
  }

  def hrefOf(page: TopicsPageId): String =
    TopicsRoutes
      .routes(TopicsFeature.uiPrefix)
      .flatMap(route => route.relativeUrlForPage(page))
      .headOption
      .getOrElse(TopicsFeature.uiPrefix)

  def id: FeatureId = FeatureId.Topics

  def nav: NavEntry = TopicsRoutes.nav

  /** The same patterns the shell already registered, under the prefix it registered them with.
    *
    * The shell uses `TopicsRoutes` directly and never calls this, but `KuiFeature` requires it and a loaded
    * feature that could not name its own URLs would be a strange thing to ship.
    */
  def routes: List[Route[? <: Page, ?]] = TopicsRoutes.routes(TopicsFeature.uiPrefix)

  def render(page: Page): HtmlElement = {
    current.set(page)
    root
  }

  def unavailableView(reason: UnavailableReason, retry: Observer[Unit]): HtmlElement =
    p(cls := TopicsCss.Fallback, dataAttr("testid") := "topics-unavailable", Messages.UnavailableView)

  /** None. This feature *hosts* panels — M4's consumer groups register into the topic page's tab slot — and
    * contributes none of its own.
    */
  override def panels: List[PanelContribution] = Nil

  /** The caches, so the screens this feature builds can read them. */
  private[topics] def state: TopicsQueries = queries
}

/** Which screen is on show, with everything that only changes what is *inside* it stripped out. */
private enum Screen {
  case Listing(clusterId: String)
  case Detail(clusterId: String, topic: String)
}

private object Screen {
  given CanEqual[Screen, Screen] = CanEqual.derived
}

object TopicsFeature {

  private lazy val bootstrap: Bootstrap = Bootstrap.read()

  /** Built from the same bootstrap block the shell reads, so the feature calls the same origin under the same
    * deployment prefix without being told.
    */
  private lazy val api: ApiClient = ApiClient.make(Bootstrap.gatewayRoot(bootstrap), AuthState.current)

  private lazy val uiPrefix: String = s"${bootstrap.basePath.stripSuffix("/")}/ui"
}
