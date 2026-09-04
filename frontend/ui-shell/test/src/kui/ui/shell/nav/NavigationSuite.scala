package kui.ui.shell.nav

import java.time.Instant

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.*
import com.raquo.waypoint.*
import io.circe.{HCursor, Json}
import munit.FunSuite
import org.scalajs.dom

import kui.contracts.capability.{DegradedReason, ReasonCode}
import kui.kernel.ClusterId
import kui.ui.kernel.feature.{FeatureId, FeatureRoutes, NavEntry, Page}
import kui.ui.kernel.component.Icon
import kui.ui.kernel.state.FeatureState
import kui.ui.shell.layout.Sidebar
import kui.ui.shell.{Messages, ShellCss, ShellRouter}

/** One assertion per ADR-032 rendering rule, plus the two properties that are about *change* rather
  * than about one state: that an entry does not move, and that changing one entry does not disturb
  * the others.
  *
  * These are DOM assertions under jsdom, so they check structure, attributes and text — never geometry,
  * which jsdom only approximates. The dimming and the amber dot are therefore asserted as class names,
  * which is the honest thing to assert: the colours themselves are checked by `ContrastSuite`.
  */
class NavigationSuite extends FunSuite {

  private given owner: ManualOwner = new ManualOwner

  private def mounted[A](element: HtmlElement)(check: dom.Element => A): A = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container): Unit
    val rendered = render(container, element)
    try check(element.ref)
    finally {
      rendered.unmount(): Unit
      dom.document.body.removeChild(container): Unit
    }
  }

  /** A feature registration that names no feature class, which is all the navigation ever needs. */
  private final class StubRoutes(
      val id: FeatureId,
      label: String,
      sortOrder: Int,
      requiresCluster: Boolean = false,
      inSidebar: Boolean = true
  ) extends FeatureRoutes {
    val landing: Page = StubPage
    val nav: NavEntry =
      NavEntry(id, label, () => Icon.dot, order = sortOrder, requiresCluster = requiresCluster, sidebar = inSidebar)
    override def landingFor(cluster: kui.kernel.ClusterId): Page = ClusterPage(cluster.value)
    def routes(uiPrefix: String): List[Route[? <: Page, ?]] =
      List(
        Route.static(StubPage, root / "stub" / endOfSegments, uiPrefix),
        Route[ClusterPage, String](
          encode = _.clusterId,
          decode = ClusterPage(_),
          pattern = root / "clusters" / segment[String] / "stub" / endOfSegments,
          basePath = uiPrefix
        )
      )
    def encodePage(page: Page): Option[Json] = None
    def decodePage(tag: String, cursor: HCursor): Option[Page] = None
  }

  private case object StubPage extends Page

  private final case class ClusterPage(clusterId: String) extends Page

  private val clusters = new StubRoutes(FeatureId.Clusters, "Clusters", 100)

  /** A router that already knows every feature's patterns, which is how the real shell builds it: the
    * patterns are static data and are registered before anything is downloaded (ADR-012 amendment 2).
    * Without that the sidebar could not build an `href` for an entry whose module is not loaded — which
    * is every entry, on first paint.
    */
  private def routerFor(states: List[(FeatureRoutes, Signal[FeatureState])]): Router[Page] =
    ShellRouter.make(
      "",
      states.flatMap((registration, _) => registration.routes("/ui")),
      "http://localhost:8080/ui/",
      "http://localhost:8080"
    )

  private def sidebarFor(states: (FeatureRoutes, Signal[FeatureState])*)(
      hideForbidden: Boolean = false,
      cluster: Option[ClusterId] = None
  ): HtmlElement =
    Sidebar(
      routerFor(states.toList),
      Navigation.items(states.toList, Val(cluster), hideForbidden = hideForbidden),
      "/ui"
    )

  private def entryOf(root: dom.Element, testId: String): Option[dom.Element] =
    Option(root.querySelector(s"[data-testid='$testId']"))

  private def clustersEntry(root: dom.Element): dom.Element =
    entryOf(root, "nav-clusters").getOrElse(fail(s"no Clusters entry in ${root.outerHTML}"))

  private val degradedReason =
    DegradedReason(ReasonCode.UpstreamTimeout, "The broker is slow.", None, Some(900L))

  test("notConfiguredHidesTheEntry") {
    // Not a failure: this deployment simply has no such upstream, and rendering it as broken sends
    // every operator hunting for an outage that does not exist.
    mounted(sidebarFor(clusters -> Val(FeatureState.NotConfigured))()) { root =>
      assertEquals(entryOf(root, "nav-clusters"), None)
      // …and the shell's own entries are untouched.
      assert(entryOf(root, "nav-home").isDefined)
    }
  }

  test("forbiddenShowsTheEntryDisabledWithAPermissionTooltip") {
    mounted(sidebarFor(clusters -> Val(FeatureState.Forbidden))()) { root =>
      val entry = clustersEntry(root)
      assertEquals(entry.getAttribute("aria-disabled"), "true")
      // No `href` at all: a disabled link is still followable by keyboard in some browsers, and
      // following it would produce a page the user may not see.
      assertEquals(Option(entry.getAttribute("href")), None)
      assert(entry.classList.contains(ShellCss.SidebarLinkDisabled))
      assertEquals(
        entry.querySelector("[role='tooltip']").textContent,
        Messages.notPermitted("Clusters")
      )
    }
  }

  test("hideForbiddenHidesForbiddenEntries") {
    mounted(sidebarFor(clusters -> Val(FeatureState.Forbidden))(hideForbidden = true)) { root =>
      assertEquals(entryOf(root, "nav-clusters"), None)
    }
  }

  test("unavailableShowsTheEntryDimmedAndStillClickable") {
    // The amendment ADR-032 made to the original plan, and the reason the ADR exists: a dead link has
    // nowhere to put the reason, the "since", the retry or "what still works".
    val state = FeatureState.Unavailable(
      ReasonCode.UpstreamUnavailable,
      "The cluster service is not responding.",
      Some(Instant.parse("2026-09-03T09:00:00Z"))
    )

    mounted(sidebarFor(clusters -> Val(state))()) { root =>
      val entry = clustersEntry(root)
      assert(entry.classList.contains(ShellCss.SidebarLinkDimmed))
      assertEquals(Option(entry.getAttribute("aria-disabled")), None)
      assert(
        Option(entry.getAttribute("href")).exists(_.nonEmpty),
        s"a dimmed entry must still be a real link, got ${entry.outerHTML}"
      )
      assertEquals(
        entry.querySelector("[role='tooltip']").textContent,
        "The cluster service is not responding."
      )
    }
  }

  test("degradedShowsAnAmberDotAndTheReasonAsATooltip") {
    mounted(sidebarFor(clusters -> Val(FeatureState.Degraded(degradedReason)))()) { root =>
      val entry = clustersEntry(root)
      assert(entry.querySelector(s".${ShellCss.SidebarLinkDot}") != null, "expected the degraded dot")
      assert(!entry.classList.contains(ShellCss.SidebarLinkDimmed))
      assertEquals(entry.querySelector("[role='tooltip']").textContent, "The broker is slow.")
    }
  }

  test("readyShowsTheEntryNormally") {
    mounted(sidebarFor(clusters -> Val(FeatureState.Ready))()) { root =>
      val entry = clustersEntry(root)
      assert(!entry.classList.contains(ShellCss.SidebarLinkDimmed))
      assert(!entry.classList.contains(ShellCss.SidebarLinkDisabled))
      assertEquals(entry.querySelector(s".${ShellCss.SidebarLinkDot}"), null)
      // The tooltip element exists for `aria-describedby` to point at, but it is hidden and empty.
      assertEquals(entry.querySelector("[role='tooltip']").getAttribute("hidden"), "")
      assertEquals(entry.getAttribute("aria-describedby"), "")
    }
  }

  test("orderFollowsNavEntryOrderAndIsStableAcrossStateChanges") {
    // An entry that jumps when its service goes down is one the user clicks by mistake: they aim at
    // the position their muscle memory learned and something else has moved into it.
    val early = new StubRoutes(FeatureId.Clusters, "Clusters", 100)
    val state = Var[FeatureState](FeatureState.Ready)

    mounted(sidebarFor(early -> state.signal)()) { root =>
      def order(): List[String] =
        root
          .querySelectorAll("[data-testid^='nav-']")
          .toList
          .map(_.getAttribute("data-testid"))

      val before = order()
      assertEquals(before, List("nav-home", "nav-clusters", "nav-gallery", "nav-settings"))

      state.set(FeatureState.Degraded(degradedReason))
      assertEquals(order(), before)

      state.set(
        FeatureState.Unavailable(ReasonCode.CircuitOpen, "Paused after repeated failures.", None)
      )
      assertEquals(order(), before)
    }
  }

  test("aStateChangeRerendersOnlyTheAffectedItem") {
    // `children <-- items.map(...)` would rebuild every entry whenever any capability changed, which
    // drops keyboard focus and makes one service's outage rewrite the whole sidebar. `split` keeps the
    // elements, so the *same DOM node* is still there afterwards with only its classes changed.
    val state = Var[FeatureState](FeatureState.Ready)

    mounted(sidebarFor(clusters -> state.signal)()) { root =>
      val home = entryOf(root, "nav-home").get
      val entry = clustersEntry(root)

      state.set(FeatureState.Degraded(degradedReason))

      assert(entryOf(root, "nav-home").get eq home, "an unrelated entry must not be rebuilt")
      assert(clustersEntry(root) eq entry, "the affected entry is updated in place, not replaced")
      assert(entry.querySelector(s".${ShellCss.SidebarLinkDot}") != null)
    }
  }

  // --- Cluster-scoped entries -------------------------------------------------------------------
  //
  // A feature whose URL names a cluster cannot state its own sidebar destination: `landing` is a
  // constant evaluated before anybody has chosen anything, so it holds a placeholder cluster id of "".
  // That placeholder used to be handed to the sidebar unchanged, and an empty path segment collapses —
  // `/ui/clusters//stub` is `/ui/clusters/stub` — so the link matched no route at all. Topics, Messages
  // and Consumers were every one of them a dead link in the running product. These three tests are
  // what stop that returning.

  private val scoped = new StubRoutes(FeatureId.Topics, "Topics", 200, requiresCluster = true)

  private val hiddenFromSidebar =
    new StubRoutes(FeatureId.Messages, "Messages", 250, requiresCluster = true, inSidebar = false)

  test("aClusterScopedEntryIsLeftOutUntilAClusterIsChosen") {
    mounted(sidebarFor(scoped -> Val(FeatureState.Ready))(cluster = None)) { root =>
      assertEquals(entryOf(root, "nav-topics"), None)
    }
  }

  test("aClusterScopedEntryLinksToTheChosenCluster") {
    val chosen = ClusterId.from("prod").toOption
    mounted(sidebarFor(scoped -> Val(FeatureState.Ready))(cluster = chosen)) { root =>
      val href = entryOf(root, "nav-topics").flatMap(e => Option(e.getAttribute("href"))).getOrElse("")
      assert(href.endsWith("/ui/clusters/prod/stub"), s"the entry linked to '$href'")
    }
  }

  test("aClusterScopedEntryFollowsTheClusterWhenTheSwitcherChangesIt") {
    // Found by driving a real browser: after choosing another cluster in the switcher, a sidebar
    // entry still carried the address of the cluster that had just been left, so clicking it
    // navigated to a page the user had not asked for.
    //
    // The cause was the keying of the entries. An entry's `href` and click handler are settled once,
    // from the first value the element is given -- correct for whether it is a link at all, which is
    // a permission decision that does not change while the page is open, and wrong for where it
    // goes, which changes with the chosen cluster. Keyed on the entry's test id alone, the element
    // was reused across a cluster change and kept the stale destination.
    val chosen = Var(ClusterId.from("prod").toOption)
    val sidebar =
      Sidebar(
        routerFor(List(scoped -> Val(FeatureState.Ready))),
        Navigation.items(List(scoped -> Val(FeatureState.Ready)), chosen.signal),
        "/ui"
      )

    mounted(sidebar) { root =>
      def destination: String =
        entryOf(root, "nav-topics").flatMap(entry => Option(entry.getAttribute("href"))).getOrElse("")

      assert(destination.endsWith("/ui/clusters/prod/stub"), s"before the switch it linked to '$destination'")

      chosen.set(ClusterId.from("secured").toOption)

      assert(
        destination.endsWith("/ui/clusters/secured/stub"),
        s"after the switch it still linked to '$destination'"
      )
    }
  }

  test("aFeatureThatIsNotASidebarDestinationIsNotDrawn") {
    val chosen = ClusterId.from("prod").toOption
    mounted(sidebarFor(hiddenFromSidebar -> Val(FeatureState.Ready))(cluster = chosen)) { root =>
      assertEquals(entryOf(root, "nav-messages"), None)
    }
  }
}
