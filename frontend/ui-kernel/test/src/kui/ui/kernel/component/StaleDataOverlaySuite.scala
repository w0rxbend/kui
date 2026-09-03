package kui.ui.kernel.component

import java.time.Instant

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.scalajs.dom

import kui.ui.kernel.css.KernelCss

class StaleDataOverlaySuite extends FunSuite with Mounted {

  private val fetched = Instant.parse("2026-09-03T12:00:00Z")
  private val now = () => Instant.parse("2026-09-03T12:08:00Z")

  /** A region with the two things a real screen puts under the overlay: a table row and an action. */
  private def content(): HtmlElement =
    div(
      dataAttr("testid") := "content",
      span("3 brokers"),
      button(tpe := "button", "Refresh"),
      a(href := "/clusters/local/brokers/1", "broker 1")
    )

  private def overlay(stale: Var[Option[StaleReason]]): HtmlElement =
    StaleDataOverlay(
      content = content(),
      stale = stale.signal,
      fetchedAt = Val(Some(fetched)),
      zone = Val("UTC"),
      now = now,
      testId = Some("brokers")
    )

  test("freshContentHasNoBadgeAndNoAriaBusy") {
    mounted(overlay(Var(None))) { root =>
      // Present and explicitly `false` rather than absent: that is what ARIA says "this region is
      // not busy" looks like, and a screen reader that has already seen the region needs to be told
      // it went back to normal.
      assertEquals(attributeOf(root, "aria-busy"), Some("false"))
      assert(!root.classList.contains(KernelCss.StaleActive))
      assertEquals(Option(root.querySelector(s".${KernelCss.StaleBadge}")), None)
    }
  }

  test("staleContentKeepsEveryChildInTheDom") {
    val stale = Var(Option.empty[StaleReason])
    mounted(overlay(stale)) { root =>
      val region = byTestId(root, "content")
      val before = (0 until region.childNodes.length).map(region.childNodes(_)).toList
      stale.set(Some(StaleReason.unavailable("connection refused")))
      val after = (0 until region.childNodes.length).map(region.childNodes(_)).toList
      assertEquals(after.length, before.length)
      before.zip(after).foreach((was, is) => assert(was eq is, "a child node was replaced"))
    }
  }

  test("staleContentIsMarkedBusyAndInert") {
    val stale = Var(Option.empty[StaleReason])
    mounted(overlay(stale)) { root =>
      stale.set(Some(StaleReason.degraded("upstream slow")))
      assertEquals(attributeOf(root, "aria-busy"), Some("true"))
      assert(root.classList.contains(KernelCss.StaleActive))

      val controls = root.querySelectorAll("button, a")
      assert(controls.length >= 2)
      (0 until controls.length).foreach { index =>
        val control = controls(index).asInstanceOf[dom.Element]
        assertEquals(attributeOf(control, "aria-disabled"), Some("true"))
        assert(control.hasAttribute("disabled"))
        assertEquals(attributeOf(control, "tabindex"), Some("-1"))
      }

      stale.set(None)
      val restored = root.querySelectorAll("button, a")
      (0 until restored.length).foreach { index =>
        val control = restored(index).asInstanceOf[dom.Element]
        assertEquals(attributeOf(control, "aria-disabled"), None)
        assert(!control.hasAttribute("disabled"))
        assertEquals(attributeOf(control, "tabindex"), None)
      }
    }
  }

  test("theBadgeNamesTheStateAndTheReasonVerbatim") {
    mounted(overlay(Var(Some(StaleReason.unavailable("connection refused"))))) { root =>
      val badge = byTestId(root, "brokers-stale-badge")
      assert(badge.textContent.contains("Unavailable"), badge.textContent)
      assert(badge.textContent.contains("connection refused"), badge.textContent)
    }
  }

  test("theBadgeShowsRelativeTimeWithTheAbsoluteTimeAsTitle") {
    mounted(overlay(Var(Some(StaleReason.lastRequestFailed("timed out"))))) { root =>
      val badge = byTestId(root, "brokers-stale-badge")
      assert(badge.textContent.contains("Last updated 8 minutes ago"), badge.textContent)
      assertEquals(attributeOf(badge, "title"), Some("2026-09-03 12:00:00 UTC+00:00"))
    }
  }

  test("neverFetchedRendersNeverRefreshed") {
    val element = StaleDataOverlay(
      content = content(),
      stale = Val(Some(StaleReason.unavailable("no route to host"))),
      fetchedAt = Val(None),
      zone = Val("UTC"),
      now = now,
      testId = Some("brokers")
    )
    mounted(element) { root =>
      val badge = byTestId(root, "brokers-stale-badge")
      assert(badge.textContent.contains("Never refreshed"), badge.textContent)
      assert(!badge.textContent.contains("Last updated"), badge.textContent)
      assertEquals(attributeOf(badge, "title"), None)
    }
  }

  test("theBadgeIsAnnouncedOnce") {
    mounted(overlay(Var(Some(StaleReason.degraded("partial results"))))) { root =>
      val badge = byTestId(root, "brokers-stale-badge")
      assertEquals(attributeOf(badge, "role"), Some("status"))
      assertEquals(attributeOf(badge, "aria-live"), Some("polite"))
    }
  }
}
