package kui.ui.shell

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.scalajs.dom

import kui.ui.kernel.api.Bootstrap
import kui.ui.kernel.theme.ThemeChoice
import kui.ui.shell.layout.Layout
import kui.ui.shell.nav.Navigation
import kui.ui.shell.page.GalleryPage

/** The frame: what is on screen around the page, and in what order.
  *
  * These are DOM assertions under jsdom, so they check structure, attributes and text — never geometry, which
  * jsdom only approximates.
  */
class ShellLayoutSuite extends FunSuite {

  private def mounted[A](element: HtmlElement)(check: dom.Element => A): A = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container): Unit
    val root = render(container, element)
    try check(element.ref)
    finally {
      root.unmount(): Unit
      dom.document.body.removeChild(container): Unit
    }
  }

  private def app(url: String): HtmlElement =
    Shell.app(Bootstrap("", "/api/v1", "1.2.3"), url, "http://localhost:8080")

  test("theSkipLinkIsTheFirstFocusableElement") {
    // A keyboard user's first Tab must land on "skip to content". Otherwise every page begins with a
    // walk through the whole navigation, on every page, every time.
    mounted(app("http://localhost:8080/ui/")) { root =>
      val focusable = root.querySelectorAll("a[href], button, input, select, [tabindex]")
      assert(focusable.length > 1, "the shell should have several focusable elements")
      assertEquals(focusable(0).textContent, "Skip to content")
      assertEquals(focusable(0).getAttribute("href"), s"#${Layout.ContentId}")
    }
  }

  test("theSkipLinkTargetIsFocusableButNotInTheTabOrder") {
    mounted(app("http://localhost:8080/ui/")) { root =>
      val content = root.querySelector(s"#${Layout.ContentId}")
      assert(content != null, "the content region must exist for the skip link to target")
      // `-1` makes it focusable by the link without adding a stop of its own to the tab order.
      assertEquals(content.getAttribute("tabindex"), "-1")
      assertEquals(content.tagName.toLowerCase, "main")
    }
  }

  test("theShellRendersAHeaderANavigationAndAContentRegion") {
    mounted(app("http://localhost:8080/ui/")) { root =>
      assert(root.querySelector("header") != null)
      assert(root.querySelector("nav[aria-label='Main']") != null)
      assert(root.querySelector("main") != null)
      assert(root.querySelector("[data-testid='page-home']") != null)
    }
  }

  test("theHeaderShowsTheBuildTheServerReported") {
    mounted(app("http://localhost:8080/ui/")) { root =>
      assertEquals(root.querySelector("[data-testid='build-version']").textContent, "1.2.3")
    }
  }

  test("aMissingBootstrapBlockLeavesTheShellWorkingWithAPlaceholderVersion") {
    // The degraded behaviour the task asks for: nothing answered, so the version is whatever
    // `Bootstrap` falls back to, and every other part of the shell is unaffected.
    val element = Shell.app(Bootstrap.Fallback, "http://localhost:8080/ui/", "http://localhost:8080")
    mounted(element) { root =>
      assertEquals(root.querySelector("[data-testid='build-version']").textContent, "dev")
      assert(root.querySelector("nav[aria-label='Main']") != null)
    }
  }

  test("theCurrentNavigationEntryIsMarkedForScreenReadersAndNotOnlyByColour") {
    mounted(app("http://localhost:8080/ui/settings")) { root =>
      val settings = root.querySelector("[data-testid='nav-settings']")
      val home = root.querySelector("[data-testid='nav-home']")
      assertEquals(settings.getAttribute("aria-current"), "page")
      assertEquals(home.getAttribute("aria-current"), "")
    }
  }

  test("everyNavigationEntryHasARealHrefSoItCanBeOpenedInANewTab") {
    mounted(app("http://localhost:8080/ui/")) { root =>
      Navigation.shellItems.foreach { item =>
        val link = root.querySelector(s"[data-testid='${item.testId}']")
        assert(link != null, s"${item.testId} is missing from the sidebar")
        // Waypoint's `navigateTo` binder writes the absolute URL over the relative one the
        // sidebar sets, which is what makes "open in a new tab" and "copy link address" work.
        assert(
          Option(link.getAttribute("href")).exists(_.contains("/ui")),
          s"${item.testId} href was ${link.getAttribute("href")}"
        )
      }
    }
  }

  test("anUnknownAddressRendersTheNotFoundPageWithTheNavigationIntact") {
    mounted(app("http://localhost:8080/ui/nope")) { root =>
      assert(root.querySelector("[data-testid='page-not-found']") != null)
      // The nav staying usable is the whole difference between a 404 page and a dead end.
      assert(root.querySelector("[data-testid='nav-home']") != null)
    }
  }

  test("theSettingsPageOffersTheThreeThemeChoicesAndTheBuild") {
    mounted(app("http://localhost:8080/ui/settings")) { root =>
      assert(root.querySelector("[data-testid='page-settings']") != null)
      val options = root.querySelectorAll("[data-testid='settings-theme'] option")
      // Three real choices plus the placeholder `Select` renders for "nothing chosen".
      assert(options.length >= ThemeChoice.values.length, s"got ${options.length} options")
      assertEquals(root.querySelector("[data-testid='settings-build']").textContent, "1.2.3")
    }
  }

  test("theGalleryRendersEverySection") {
    // The gallery is how a change to a primitive is reviewed. A section that silently stops
    // rendering would make the review pass by showing less, which is the one failure a gallery must
    // not have.
    mounted(GalleryPage()) { root =>
      List(
        "gallery-buttons",
        "gallery-tags",
        "gallery-inputs",
        "gallery-feedback",
        "gallery-table",
        "gallery-overlays",
        "gallery-icons"
      ).foreach(section => assert(root.querySelector(s"[data-testid='$section']") != null, section))
    }
  }

  test("theShellCanBeStartedTwiceWithoutTheRoutersOwnerLeakingState") {
    // Two independent applications on one page is not a supported deployment, but building the
    // element twice in one suite is how these tests work, and a router that wrote to shared state
    // would make the second one see the first one's page.
    given owner: ManualOwner = new ManualOwner
    val first = ShellRouter.make("", Nil, "http://localhost:8080/ui/settings", "http://localhost:8080")
    val second = ShellRouter.make("", Nil, "http://localhost:8080/ui/gallery", "http://localhost:8080")

    assertEquals(first.currentPageSignal.observe(using owner).now(), ShellPage.Settings)
    assertEquals(second.currentPageSignal.observe(using owner).now(), ShellPage.Gallery)
    owner.killSubscriptions()
  }
}
