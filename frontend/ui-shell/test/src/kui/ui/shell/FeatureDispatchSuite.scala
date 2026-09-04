package kui.ui.shell

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.*
import com.raquo.waypoint.*
import io.circe.{HCursor, Json}
import munit.FunSuite

import kui.ui.kernel.component.Icon
import kui.ui.kernel.feature.{FeatureId, FeatureRoutes, NavEntry, Page}

/** Which page each feature is asked to draw.
  *
  * A feature's screens live in a module of their own, downloaded on demand, and the shell hands the loaded
  * module a signal saying which page to render. The question this suite settles is what goes into that
  * signal: every page in the application, or only the ones this feature's own routes can produce.
  *
  * It has to be the second. Driving the running demo in a browser produced an address ending in `/topics`
  * with the message browser on the screen, stuck on "Loading Messages…" — a feature drawing a page belonging
  * to another feature. Nothing defines what a feature does with a page it has never heard of, so the answer
  * is not to define it but to make it unreachable.
  */
class FeatureDispatchSuite extends FunSuite {

  private given owner: ManualOwner = new ManualOwner

  private val uiPrefix = "/ui"

  private final case class ListPage(clusterId: String) extends Page

  private final case class BrowsePage(clusterId: String, topic: String) extends Page

  /** A registration that names no feature class, which is all the dispatch ever needs. */
  private final class StubRoutes(val id: FeatureId, label: String, make: String => List[Route[? <: Page, ?]])
      extends FeatureRoutes {
    val landing: Page = ListPage("")
    val nav: NavEntry = NavEntry(id, label, () => Icon.dot, order = 100, requiresCluster = true)
    def routes(prefix: String): List[Route[? <: Page, ?]] = make(prefix)
    def encodePage(page: Page): Option[Json] = None
    def decodePage(tag: String, cursor: HCursor): Option[Page] = None
  }

  private val topics = new StubRoutes(
    FeatureId.Topics,
    "Topics",
    prefix =>
      List(
        Route[ListPage, String](
          encode = _.clusterId,
          decode = ListPage(_),
          pattern = root / "clusters" / segment[String] / "topics" / endOfSegments,
          basePath = prefix
        )
      )
  )

  private val messages = new StubRoutes(
    FeatureId.Messages,
    "Messages",
    prefix =>
      List(
        Route[BrowsePage, (String, String)](
          encode = page => (page.clusterId, page.topic),
          decode = (cluster, topic) => BrowsePage(cluster, topic),
          pattern = root / "clusters" / segment[String] / "topics" / segment[String] / "messages" /
            endOfSegments,
          basePath = prefix
        )
      )
  )

  private def routerOn(url: String): Router[Page] =
    ShellRouter.make(
      basePath = "",
      featureRoutes = List(topics, messages).flatMap(_.routes(uiPrefix)),
      initialUrl = url,
      origin = "http://localhost:8080"
    )

  test("aFeatureIsNeverAskedToDrawAPageBelongingToAnotherFeature") {
    // Start on a message browser, then navigate to the topic list. The messages feature's element is
    // built once and kept, so it is still subscribed while the shell swaps it off the screen; if it is
    // handed the topic-list page it draws a message browser at an address that says "topics", which is
    // precisely what was seen in the browser.
    val router = routerOn("http://localhost:8080/ui/clusters/secured/topics/audit.log.raw/messages")

    val seenByMessages = Var(List.empty[Page])
    Shell
      .ownPagesOf(messages, router, uiPrefix)
      .foreach(page => seenByMessages.update(_ :+ page)): Unit

    assertEquals(seenByMessages.now(), List(BrowsePage("secured", "audit.log.raw")))

    router.pushState(ListPage("secured"))

    assertEquals(
      seenByMessages.now(),
      List(BrowsePage("secured", "audit.log.raw")),
      "the messages feature was handed a page that is not one of its own"
    )
  }

  test("aFeatureSeesEveryPageOfItsOwnAndOnlyWhenItChanges") {
    val router = routerOn("http://localhost:8080/ui/clusters/secured/topics")

    val seenByMessages = Var(List.empty[Page])
    Shell
      .ownPagesOf(messages, router, uiPrefix)
      .foreach(page => seenByMessages.update(_ :+ page)): Unit

    // Before the messages feature has ever been the destination it holds the current page, which is
    // not one of its own. That is harmless: the shell only ever renders this gate for a page the
    // feature owns, and the alternative -- an empty placeholder page -- is one more shape every
    // feature would have to handle.
    assertEquals(seenByMessages.now(), List(ListPage("secured")))

    router.pushState(BrowsePage("secured", "orders"))
    router.pushState(ListPage("secured"))
    router.pushState(BrowsePage("secured", "payments"))

    assertEquals(
      seenByMessages.now(),
      List(ListPage("secured"), BrowsePage("secured", "orders"), BrowsePage("secured", "payments"))
    )
  }
}
