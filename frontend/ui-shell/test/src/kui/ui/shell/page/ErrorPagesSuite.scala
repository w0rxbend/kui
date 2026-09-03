package kui.ui.shell.page

import scala.scalajs.js

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.*
import com.raquo.waypoint.Router
import munit.FunSuite
import org.scalajs.dom

import kui.ui.kernel.feature.Page
import kui.ui.shell.{ShellConnectivity, ShellRouter}

/** The three states a user will meet, and what each of them must contain.
  *
  * DOM assertions under jsdom: structure, attributes and text. Nothing here asserts geometry, which jsdom
  * only approximates.
  */
class ErrorPagesSuite extends FunSuite {

  private val owner = new ManualOwner

  override def afterAll(): Unit = owner.killSubscriptions()

  private def router: Router[Page] =
    ShellRouter.make("", Nil, "http://localhost:8080/ui/", "http://localhost:8080")(using owner)

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

  private def textOf(root: dom.Element, testId: String): String =
    Option(root.querySelector(s"[data-testid='$testId']"))
      .map(_.textContent)
      .getOrElse(fail(s"no element with data-testid='$testId' in ${root.outerHTML}"))

  // ---- 404 -------------------------------------------------------------------------------------

  test("notFoundShowsTheAttemptedUrl") {
    // Often the answer in itself: a truncated paste and a stale bookmark look identical until you
    // can see what was actually asked for.
    mounted(NotFoundPage(Val("/ui/topics/orders/typo"), router)) { root =>
      assert(textOf(root, "not-found-url").contains("/ui/topics/orders/typo"))
    }
  }

  test("notFoundKeepsTheNavigationUsable") {
    // Asserted on the assembled shell rather than the page alone, because "the nav stays usable" is
    // a fact about the application and not about this element.
    val shell = kui.ui.shell.Shell.app(
      kui.ui.kernel.api.Bootstrap.Fallback,
      "http://localhost:8080/ui/nope",
      "http://localhost:8080"
    )
    mounted(shell) { root =>
      assert(root.querySelector("[data-testid='page-not-found']") != null)
      assert(root.querySelector("[data-testid='nav-home']") != null)
      assert(root.querySelector("[data-testid='nav-settings']") != null)
    }
  }

  // ---- 403 -------------------------------------------------------------------------------------

  test("forbiddenNamesTheResourceAndDoesNotLeakWhetherItExists") {
    // The rule this page is built around. If the message differed for a resource that exists and one
    // that does not, a user who may not know which topics exist could learn the whole list by trying
    // names and watching which sentence comes back.
    val existing = mounted(ForbiddenPage(Val("this topic"), router))(root =>
      root.querySelector("[data-testid='forbidden-empty']").textContent
    )
    val absent = mounted(ForbiddenPage(Val("this topic"), router))(root =>
      root.querySelector("[data-testid='forbidden-empty']").textContent
    )
    assertEquals(existing, absent)

    // And the subject line is a category, never an identifier.
    mounted(ForbiddenPage(Val("the schema registry"), router)) { root =>
      assertEquals(
        textOf(root, "forbidden-subject"),
        "You do not have permission to view the schema registry."
      )
    }
  }

  test("forbiddenShowsASupportContactWhenTheDeploymentConfiguresOneAndNoPlaceholderWhenItDoesNot") {
    mounted(ForbiddenPage(Val("this topic"), router, Val(Some("platform@example.com")))) { root =>
      assert(textOf(root, "forbidden-contact").contains("platform@example.com"))
    }
    // Empty by default. A placeholder nobody can act on is worse than no line at all.
    mounted(ForbiddenPage(Val("this topic"), router)) { root =>
      assertEquals(root.querySelector("[data-testid='forbidden-contact']"), null)
    }
    mounted(ForbiddenPage(Val("this topic"), router, Val(Some("")))) { root =>
      assertEquals(root.querySelector("[data-testid='forbidden-contact']"), null)
    }
  }

  test("bothPagesHaveALevelOneHeadingAndALinkHome") {
    // Screen-reader users navigate by heading; a page whose main message is not a heading has to be
    // read linearly to be found. And the link is a real `<a>` as well as a button, because "open in
    // a new tab" and "copy link address" are things people do and a button supports neither.
    List(NotFoundPage(Val("/ui/nope"), router), ForbiddenPage(Val("this topic"), router)).foreach { page =>
      mounted(page) { root =>
        assertEquals(root.querySelectorAll("h1").length, 1)
        val links = root.querySelectorAll("a[href]")
        assert(links.length >= 1, "an error page needs a real link home")
        assert(
          (0 until links.length).exists(index => links(index).getAttribute("href").contains("/ui")),
          "the link home must point into the application"
        )
      }
    }
  }

  // ---- the full-screen state -------------------------------------------------------------------

  private def lost(seconds: Int): ShellConnectivity =
    ShellConnectivity.Lost(new js.Date(1_700_000_000_000.0), new js.Date(1_700_000_000_000.0), seconds)

  test("theFullScreenStateRendersWithNoApiDataAtAll") {
    // Its degraded behaviour is itself: by definition nothing answered, so anything it needed from
    // the server would be a thing it could not have.
    mounted(GatewayUnreachable(Val(lost(8)), Observer.empty)) { root =>
      assertEquals(root.querySelectorAll("h1").length, 1)
      assert(root.textContent.contains("cannot reach the server"))
      assert(root.querySelector("[data-testid='unreachable-retry']") != null)
    }
  }

  test("theCountdownIsOnScreenAndReadsAsASentence") {
    val state = Var[ShellConnectivity](lost(8))
    mounted(GatewayUnreachable(state.signal, Observer.empty)) { root =>
      assertEquals(textOf(root, "unreachable-countdown"), "Trying again in 8 seconds.")
      state.set(lost(1))
      // Singular, because "in 1 seconds" is the kind of detail that makes a product feel unfinished
      // at exactly the moment the user is already unhappy with it.
      assertEquals(textOf(root, "unreachable-countdown"), "Trying again in 1 second.")
    }
  }

  test("theCountdownIsAPoliteLiveRegionSoItDoesNotInterruptContinuously") {
    mounted(GatewayUnreachable(Val(lost(8)), Observer.empty)) { root =>
      val countdown = root.querySelector("[data-testid='unreachable-countdown']")
      assertEquals(countdown.getAttribute("aria-live"), "polite")
    }
  }

  test("itAnnouncesItselfAsAModalAlertBecauseNothingBehindItIsUsable") {
    mounted(GatewayUnreachable(Val(lost(8)), Observer.empty)) { root =>
      assertEquals(root.getAttribute("role"), "alertdialog")
      assertEquals(root.getAttribute("aria-modal"), "true")
      assert(Option(root.getAttribute("aria-labelledby")).exists(_.nonEmpty))
    }
  }

  test("theLastContactTimeIsShownAsAClockTimeAndNotAsARelativeOne") {
    // A relative time has to be recomputed to stay true, and one that has silently stopped updating
    // is a lie. An absolute one is right for ever.
    mounted(GatewayUnreachable(Val(lost(8)), Observer.empty)) { root =>
      val line = textOf(root, "unreachable-last-contact")
      assert(line.startsWith("Last contact with the server:"), line)
      assert(!line.contains("ago"), line)
    }
  }

  test("tryAgainCallsBackRatherThanReloading") {
    var attempts = 0
    mounted(GatewayUnreachable(Val(lost(8)), Observer[Unit](_ => attempts += 1))) { root =>
      val retry = root.querySelector("[data-testid='unreachable-retry']")
      retry.dispatchEvent(new dom.MouseEvent("click", new dom.MouseEventInit { bubbles = true })): Unit
      assertEquals(attempts, 1)
    }
  }
}
