package kui.ui.shell.feature

import java.time.Instant

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.scalajs.dom

import kui.contracts.capability.ReasonCode
import kui.ui.shell.Messages

/** The four things ADR-032 puts on the fallback panel, and the two things the retry must not do.
  *
  * The clock is a parameter, so "two minutes ago" is a fact about the panel and not about how long the
  * suite took to run.
  */
class FallbackPanelSuite extends FunSuite {

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

  private val downSince = Instant.parse("2026-09-03T09:00:00Z")
  private val checkedAt = Instant.parse("2026-09-03T09:02:30Z")

  private def panel(
      retry: Observer[Unit] = Observer.empty[Unit],
      whatStillWorks: Signal[List[String]] = Val(Nil),
      retryInFlight: Signal[Boolean] = Val(false),
      retryError: Signal[Option[String]] = Val(None),
      since: Option[Instant] = Some(downSince)
  ): HtmlElement =
    FeatureFallbackPanel(
      featureLabel = "Clusters",
      reason = ReasonCode.UpstreamUnavailable,
      message = "The cluster service is not responding.",
      since = since,
      retry = retry,
      whatStillWorks = whatStillWorks,
      retryInFlight = retryInFlight,
      retryError = retryError,
      now = () => checkedAt
    )

  private def textOf(root: dom.Element, testId: String): String =
    Option(root.querySelector(s"[data-testid='$testId']"))
      .map(_.textContent)
      .getOrElse(fail(s"no element with data-testid='$testId' in ${root.outerHTML}"))

  private def click(element: dom.Element): Unit =
    element.dispatchEvent(new dom.MouseEvent("click", new dom.MouseEventInit { bubbles = true })): Unit

  test("showsTheReasonTheMessageAndBothTimeFormats") {
    mounted(panel()) { root =>
      // The gateway's own message wins over the reason code's generic sentence, because it is the one
      // that mentions the actual upstream.
      assert(textOf(root, "fallback-reason").contains("The cluster service is not responding."))

      val since = textOf(root, "fallback-since")
      // Relative, for "is this new?", and absolute, for the ticket and the deploy log. Both, because
      // neither answers the other's question.
      assert(since.contains("2 minutes ago"), since)
      assert(since.contains("2026-09-03T09:00:00Z"), since)

      assert(root.textContent.contains(Messages.unavailableTitle("Clusters")))
    }
  }

  test("aPanelWithNoSinceOmitsTheLineRatherThanInventingATime") {
    mounted(panel(since = None)) { root =>
      assertEquals(root.querySelector("[data-testid='fallback-since']"), null)
    }
  }

  test("retryCallsProbeAndShowsProgress") {
    var probes = 0
    val inFlight = Var(false)

    mounted(panel(retry = Observer[Unit](_ => probes += 1), retryInFlight = inFlight.signal)) { root =>
      val button = root.querySelector("[data-testid='fallback-retry']")
      assert(button.textContent.contains(Messages.RetryNow))

      click(button)
      assertEquals(probes, 1)

      inFlight.set(true)
      assert(button.textContent.contains(Messages.Retrying))
      // A busy button must not accept a second press: a user watching a slow service would otherwise
      // queue up a dozen probes against an upstream that is already struggling.
      assertEquals(button.getAttribute("aria-busy"), "true")
      click(button)
      assertEquals(probes, 1)
    }
  }

  test("aFailedProbeShowsAnInlineErrorAndNotAToastStorm") {
    val error = Var(Option.empty[String])

    mounted(panel(retryError = error.signal)) { root =>
      assertEquals(root.querySelector("[data-testid='fallback-retry-error']"), null)

      error.set(Some("the gateway could not be reached"))
      val shown = textOf(root, "fallback-retry-error")
      assert(shown.contains("the gateway could not be reached"), shown)
      // Next to the button that caused it, so that pressing retry ten times leaves one message and
      // not ten notifications.
      assertEquals(
        root.querySelector("[data-testid='fallback-retry-error']").getAttribute("role"),
        "alert"
      )
    }
  }

  test("whatStillWorksListsOnlyReadyFeatures") {
    // The list is built by the shell from the *other* features' states; the panel renders whatever it
    // is given, and says so plainly when it is given nothing.
    val working = Var(List("Topics", "Consumers"))

    mounted(panel(whatStillWorks = working.signal)) { root =>
      val stillWorks = textOf(root, "fallback-still-works")
      assert(stillWorks.contains("Topics"), stillWorks)
      assert(stillWorks.contains("Consumers"), stillWorks)

      working.set(Nil)
      assert(textOf(root, "fallback-still-works").contains(Messages.NothingElseWorks))
    }
  }

  test("relativeTimeIsCoarseEnoughNotToChangeWhileItIsRead") {
    val base = Instant.parse("2026-09-03T09:00:00Z")
    assertEquals(FeatureFallbackPanel.relative(base, base.plusSeconds(30)), "less than a minute ago")
    assertEquals(FeatureFallbackPanel.relative(base, base.plusSeconds(60)), "1 minute ago")
    assertEquals(FeatureFallbackPanel.relative(base, base.plusSeconds(3 * 3600)), "3 hours ago")
    assertEquals(FeatureFallbackPanel.relative(base, base.plusSeconds(50 * 3600)), "2 days ago")
    // A clock skew between the browser and the gateway must not produce "-1 minutes ago".
    assertEquals(FeatureFallbackPanel.relative(base, base.minusSeconds(90)), "less than a minute ago")
  }
}
