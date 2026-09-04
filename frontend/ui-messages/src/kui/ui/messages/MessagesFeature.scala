package kui.ui.messages

import com.raquo.laminar.api.L.*
import com.raquo.waypoint.Route
import org.scalajs.dom

import kui.kernel.{ClusterId, TopicName}
import kui.ui.kernel.api.Bootstrap
import kui.ui.kernel.feature.*
import kui.ui.kernel.prefs.Timezone
import kui.ui.messages.browse.BrowseSession

/** The message-browsing microfrontend.
  *
  * ## The one rule about this class
  *
  * It is constructed only through `js.dynamicImport`, in `kui.ui.shell.FeatureRegistryImpl`, and nothing in
  * the shell may name this type in a signature, a `val` or anywhere else. That import is a *split border* to
  * the Scala.js linker: everything reachable only through it is emitted as a separate JavaScript module the
  * browser fetches on demand. One static reference from shell code and the linker puts the whole feature in
  * `main.js`, where every user downloads it on first paint — including users whose deployment has no message
  * service at all. Nothing about the source looks different when that happens, which is why
  * `checkBundleShape` asserts the shape of the linked output instead of trusting a review.
  *
  * The feature's *static* half — its nav entry, route pattern and `history.state` codec — lives in
  * `MessagesRoutes` and is named from the shell normally, because all of it has to be known before this class
  * is downloaded (ADR-012 amendment 2).
  *
  * ## Why a session per topic
  *
  * A browse is a live Kafka consumer, not a cached document, so this feature holds `BrowseSession`s rather
  * than a `QueryCache`. One per topic, created on first visit: walking from a topic to another and back must
  * not silently start a second stream against the first, and it must not hand the second topic's screen the
  * first topic's records. Every session is closed when this feature's element unmounts, and each page closes
  * its own on the way out, so no consumer outlives the screen that opened it.
  */
final class MessagesFeature extends KuiFeature {

  /** The running browses, keyed by cluster and topic.
    *
    * A `Var` rather than a bare mutable map so that nothing here is global: two tabs are two instances of
    * this feature and each has its own (PLAN §21).
    */
  private val sessions: Var[Map[(String, String), BrowseSession]] = Var(Map.empty)

  private val current: Var[Page] = Var(MessagesRoutes.landing)

  private lazy val root: HtmlElement =
    div(
      child <-- current.signal.map(screenOf).distinct.map(build),
      // Every consumer this feature ever opened, closed when the feature leaves the screen.
      onUnmountCallback(_ => sessions.now().values.foreach(_.stop()))
    )

  private def screenOf(page: Page): (String, String) =
    page match {
      case MessagesPageId.Browse(clusterId, topic) => (clusterId, topic)
      case _ => ("", "")
    }

  /** One topic's records.
    *
    * A cluster id or a topic name the URL held but that will not parse renders the page's own empty container
    * rather than failing: both came from something a user typed or a bookmark kept, and a blank screen with
    * no explanation is the worst possible answer to it.
    */
  private def build(screen: (String, String)): HtmlElement = {
    val (clusterId, topicName) = screen

    (ClusterId.from(clusterId).toOption, TopicName.from(topicName).toOption) match {
      case (Some(cluster), Some(topic)) =>
        MessagesPage(
          topic = topic,
          zone = Timezone.choice.signal,
          session = sessionFor(cluster, topic)
        )
      case _ => div(cls := MessagesCss.Page, dataAttr("testid") := "page-messages")
    }
  }

  private def sessionFor(cluster: ClusterId, topic: TopicName): BrowseSession = {
    val key = (cluster.value, topic.value)

    sessions.now().get(key) match {
      case Some(existing) => existing
      case None =>
        val session = new BrowseSession(MessagesFeature.apiRoot, cluster, topic)
        sessions.update(_.updated(key, session))
        session
    }
  }

  /** Moves to another topic's records without reloading the application. */
  def goTo(page: MessagesPageId): Unit = {
    dom.window.history.pushState((), "", hrefOf(page))
    current.set(page)
  }

  def hrefOf(page: MessagesPageId): String =
    MessagesRoutes
      .routes(MessagesFeature.uiPrefix)
      .flatMap(route => route.relativeUrlForPage(page))
      .headOption
      .getOrElse(MessagesFeature.uiPrefix)

  def id: FeatureId = FeatureId.Messages

  def nav: NavEntry = MessagesRoutes.nav

  def routes: List[Route[? <: Page, ?]] = MessagesRoutes.routes(MessagesFeature.uiPrefix)

  def render(page: Page): HtmlElement = {
    current.set(page)
    root
  }

  def unavailableView(reason: UnavailableReason, retry: Observer[Unit]): HtmlElement =
    p(cls := MessagesCss.Fallback, dataAttr("testid") := "messages-unavailable", Messages.UnavailableView)

  /** None yet. The topic page's Messages tab is a panel contributed into the kernel's slot, and contributing
    * one before that host exists would put a tab on the topic page with nothing behind it; until then the
    * screen is reached by its URL, which is a real address a link can carry.
    */
  override def panels: List[PanelContribution] = Nil
}

object MessagesFeature {

  private lazy val bootstrap: Bootstrap = Bootstrap.read()

  /** The deployment's own root — origin and base path, with no `/api/v1` on it.
    *
    * `gatewayRoot` strips the API prefix off the bootstrap block, because every endpoint value already
    * carries `/api/v1` and a client based at `apiBase` would ask for it twice. A browse builds its URL by
    * hand rather than from an endpoint value (its response is a stream, which cannot cross-compile), so it
    * adds the prefix itself from `PublicApi` and needs the root without one.
    */
  private lazy val apiRoot: String =
    Bootstrap.absoluteApiBase(Bootstrap.gatewayRoot(bootstrap), dom.window.location.origin)

  private lazy val uiPrefix: String = s"${bootstrap.basePath.stripSuffix("/")}/ui"
}
