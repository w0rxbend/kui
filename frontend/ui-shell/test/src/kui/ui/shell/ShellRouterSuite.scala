package kui.ui.shell

import scala.collection.mutable

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.*
import com.raquo.waypoint.*
import munit.FunSuite
import org.scalajs.dom

import kui.ui.kernel.feature.Page

/** A page a feature would own, declared here so the suite can prove a deep link to one resolves before the
  * feature exists. In the real application this type lives in the feature's module and the shell never names
  * it — which is exactly why the *route* has to be registrable without it.
  */
final case class PretendFeaturePage(topic: String) extends Page

class ShellRouterSuite extends FunSuite {

  private val origin = "http://localhost:8080"

  private def router(initialUrl: String, extra: List[Route[? <: Page, ?]] = Nil)(using Owner): Router[Page] =
    ShellRouter.make(basePath = "", featureRoutes = extra, initialUrl = initialUrl, origin = origin)

  /** The kind of static route description a feature's registry entry carries (ADR-012 amendment 2). */
  private val pretendFeatureRoute: Route[PretendFeaturePage, String] =
    Route[PretendFeaturePage, String](
      encode = page => page.topic,
      decode = topic => PretendFeaturePage(topic),
      pattern = root / "topics" / segment[String] / endOfSegments,
      basePath = "/ui"
    )

  test("everyRoutableShellPageHasAUrlAndTheUrlParsesBackToThePage") {
    given owner: ManualOwner = new ManualOwner
    val subject = router(s"$origin/ui/")

    // `NotFound` is excluded on purpose: it has no route of its own. It is what the *fallback*
    // produces, and giving it a catch-all pattern would swallow every mistyped address before the
    // fallback ever ran, which is the same behaviour with a much worse failure mode when a feature's
    // pattern is added later.
    val routable: List[Page] =
      List(ShellPage.Home, ShellPage.Settings, ShellPage.Gallery, ShellPage.Forbidden("topics"))

    routable.foreach { page =>
      val url = subject.relativeUrlForPage(page)
      assertEquals(subject.pageForRelativeUrl(url), Some(page), s"$page -> $url did not parse back")
    }
    owner.killSubscriptions()
  }

  test("theShellPagesLiveWhereTheGatewayServesTheApplication") {
    given owner: ManualOwner = new ManualOwner
    val subject = router(s"$origin/ui/")

    assertEquals(subject.relativeUrlForPage(ShellPage.Home), "/ui/")
    assertEquals(subject.relativeUrlForPage(ShellPage.Settings), "/ui/settings")
    assertEquals(subject.relativeUrlForPage(ShellPage.Gallery), "/ui/gallery")
    owner.killSubscriptions()
  }

  test("aBasePathFromTheBootstrapBlockIsHonoured") {
    given owner: ManualOwner = new ManualOwner
    // A deployment mounted at `https://tools.example.com/kafka/` — every URL gains the prefix, and
    // no constant in the code can know it, which is why it comes from the injected bootstrap block.
    val subject = ShellRouter.make("/kafka", Nil, s"$origin/kafka/ui/settings", origin)

    assertEquals(subject.relativeUrlForPage(ShellPage.Settings), "/kafka/ui/settings")
    assertEquals(subject.currentPageSignal.observe(using owner).now(), ShellPage.Settings)
    owner.killSubscriptions()
  }

  test("anUnknownUrlYieldsNotFoundPageCarryingWhatWasAttempted") {
    given owner: ManualOwner = new ManualOwner
    val subject = router(s"$origin/ui/nope")

    subject.currentPageSignal.observe(using owner).now() match {
      case ShellPage.NotFound(url) => assert(url.contains("/ui/nope"), url)
      case other => fail(s"expected NotFound, got $other")
    }
    owner.killSubscriptions()
  }

  test("aSubPathOfARealPageIsStillNotFound") {
    given owner: ManualOwner = new ManualOwner
    // This is what `endOfSegments` buys. Without it `/settings` matches `/settings/anything` and a
    // mistyped sub-path silently renders the settings page.
    val subject = router(s"$origin/ui/settings/typo")

    subject.currentPageSignal.observe(using owner).now() match {
      case ShellPage.NotFound(_) => ()
      case other => fail(s"expected NotFound for a sub-path, got $other")
    }
    owner.killSubscriptions()
  }

  test("aDeepLinkToAFeatureRouteResolvesBeforeTheFeatureIsLoaded") {
    given owner: ManualOwner = new ManualOwner
    // Nothing has been imported: only the route *pattern* is registered, which is data. Without
    // this, the first address the router saw would be one it could not match, and a bookmarked link
    // would 404 on a page that exists (ADR-012 amendment 2).
    val subject = router(s"$origin/ui/topics/orders", extra = List(pretendFeatureRoute))

    assertEquals(subject.currentPageSignal.observe(using owner).now(), PretendFeaturePage("orders"))
    assertEquals(subject.relativeUrlForPage(PretendFeaturePage("orders")), "/ui/topics/orders")
    owner.killSubscriptions()
  }

  test("aFeatureRouteCannotShadowAShellAddress") {
    given owner: ManualOwner = new ManualOwner
    val greedy: Route[PretendFeaturePage, String] = Route[PretendFeaturePage, String](
      encode = page => page.topic,
      decode = topic => PretendFeaturePage(topic),
      pattern = root / segment[String] / endOfSegments,
      basePath = "/ui"
    )
    val subject = router(s"$origin/ui/settings", extra = List(greedy))

    assertEquals(subject.currentPageSignal.observe(using owner).now(), ShellPage.Settings)
    owner.killSubscriptions()
  }

  test("pageSwitchingCreatesEachPageElementOncePerPageInstance") {
    given owner: ManualOwner = new ManualOwner
    val page = Var[Page](ShellPage.Home)
    val built = mutable.ListBuffer.empty[String]

    // The idiom the shell uses. `collectStatic` takes its view by name and re-evaluates it on every
    // emission of that page, so the memoisation has to be here; without it, re-emitting the page
    // already on screen would silently replace it and throw away its scroll position.
    lazy val home = {
      built.append("home"): Unit
      div("home")
    }
    lazy val settings = {
      built.append("settings"): Unit
      div("settings")
    }

    val content = SplitRender[Page, HtmlElement](page.signal)
      .collectStatic(ShellPage.Home)(home)
      .collectStatic(ShellPage.Settings)(settings)
      .collect[Page] { _ =>
        built.append("other"): Unit
        div("other")
      }
      .signal

    val observed = content.observe(using owner)
    assertEquals(built.toList, List("home"))

    // Re-emitting the same page must not rebuild.
    page.set(ShellPage.Home)
    assertEquals(built.toList, List("home"))

    // Navigating away and back reuses the element rather than building a second one.
    page.set(ShellPage.Settings)
    page.set(ShellPage.Home)
    assertEquals(built.toList, List("home", "settings"))
    assertEquals(observed.now().ref.textContent, "home")
    owner.killSubscriptions()
  }

  test("aThrowingPageRendersTheFallbackAndTheRestOfTheShellStillWorks") {
    val element = ErrorReporting.renderSafely(() => throw new RuntimeException("boom"))

    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container): Unit
    val mounted = render(container, element)
    try {
      assert(
        element.ref.textContent.contains("could not be shown"),
        s"expected the fallback panel, got: ${element.ref.textContent}"
      )
      // The detail is shown, but the page it replaced is gone rather than half-drawn — a blank
      // content area with no explanation is the failure this exists to prevent.
      assert(element.ref.textContent.contains("boom"))
    } finally {
      mounted.unmount(): Unit
      dom.document.body.removeChild(container): Unit
    }
  }

  // --- A cluster in the URL --------------------------------------------------------------------

  test("aClusterInTheUrlOverridesTheStoredSelection") {
    // A link is usually pasted by a colleague, and the recipient has to see what the sender saw.
    assertEquals(
      ShellRouter.clusterInUrl("https://kui.example/ui/clusters/prod-eu/brokers", "/ui").map(_.value),
      Some("prod-eu")
    )
    assertEquals(
      ShellRouter.clusterInUrl("https://kui.example/ui/clusters/prod-eu", "/ui").map(_.value),
      Some("prod-eu")
    )
    // Under a deployment prefix, too: the shell knows where its own prefix ends.
    assertEquals(
      ShellRouter.clusterInUrl("https://kui.example/kafka/ui/clusters/local/brokers/1", "/kafka/ui").map(_.value),
      Some("local")
    )
  }

  test("aUrlThatNamesNoClusterLeavesTheStoredSelectionAlone") {
    assertEquals(ShellRouter.clusterInUrl("https://kui.example/ui/settings", "/ui"), None)
    assertEquals(ShellRouter.clusterInUrl("https://kui.example/ui/clusters", "/ui"), None)
    // Not a slug, so not a cluster id: an unparseable value is "no cluster named here" rather than a
    // selection that nothing downstream can use.
    assertEquals(ShellRouter.clusterInUrl("https://kui.example/ui/clusters/NOT A SLUG/brokers", "/ui"), None)
  }

  test("aQueryStringOrFragmentDoesNotConfuseTheClusterInTheUrl") {
    assertEquals(
      ShellRouter.clusterInUrl("https://kui.example/ui/clusters/local/brokers?tab=x#top", "/ui").map(_.value),
      Some("local")
    )
  }
}
