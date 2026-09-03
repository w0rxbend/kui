package kui.ui.shell.page

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.scalajs.dom

import kui.ui.kernel.prefs.RefreshRate
import kui.ui.kernel.theme.{AccentChoice, DensityChoice, ThemeChoice}

/** The settings page, driven with the suite's own preferences and no `localStorage`. */
final class SettingsPageSuite extends FunSuite {

  /** Mounts an element into a real document for one test, and always unmounts it again.
    *
    * A Laminar binding only becomes active once its element is in the document, so a suite that
    * inspected an unmounted element would be inspecting a page with none of its dynamic attributes
    * applied. The shell's other suites carry the same helper; it is repeated rather than shared
    * because the kernel's test module is not on this module's test classpath.
    */
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

  private def byTestId(root: dom.Element, testId: String): dom.Element =
    Option(root.querySelector(s"[data-testid='$testId']"))
      .getOrElse(fail(s"no element with data-testid='$testId' in ${root.outerHTML}"))

  private def attributeOf(element: dom.Element, name: String): Option[String] =
    Option(element.getAttribute(name))

  private def dispatch(element: dom.Element, event: dom.Event): Unit = element.dispatchEvent(event): Unit

  private def keyDown(element: dom.Element, pressed: String): Unit =
    dispatch(element, new dom.KeyboardEvent("keydown", new dom.KeyboardEventInit { key = pressed; bubbles = true }))


  private val zones = List(
    "UTC" -> "UTC+00:00 UTC",
    "Europe/Warsaw" -> "UTC+02:00 Europe/Warsaw",
    "Asia/Tokyo" -> "UTC+09:00 Asia/Tokyo"
  )

  final private class Preferences {
    val theme: Var[ThemeChoice] = Var(ThemeChoice.Auto)
    val accent: Var[AccentChoice] = Var(AccentChoice.Blue)
    val density: Var[DensityChoice] = Var(DensityChoice.Comfortable)
    val timezone: Var[String] = Var("UTC")
    val refreshRate: Var[RefreshRate] = Var(RefreshRate.Off)

    def page(build: String = "1.2.3"): HtmlElement =
      SettingsPage(theme, accent, density, timezone, zones, refreshRate, Val(build))

    /** Everything except the one named, so a test can assert that nothing else moved. */
    def others(except: String): List[(String, Any)] =
      List(
        "theme" -> theme.now(),
        "accent" -> accent.now(),
        "density" -> density.now(),
        "timezone" -> timezone.now(),
        "refreshRate" -> refreshRate.now()
      ).filterNot(_._1 == except)
  }

  private def selectAt(root: dom.Element, testId: String): dom.html.Select =
    byTestId(root, testId) match {
      case found: dom.html.Select => found
      case other => fail(s"expected a select at $testId, found ${other.tagName}")
    }

  /** Picks the option whose visible text is `label`, the way a user does. */
  private def choose(root: dom.Element, testId: String, label: String): Unit = {
    val control = selectAt(root, testId)
    val options = control.options
    val position = (0 until options.length)
      .find(index => options(index).textContent == label)
      .getOrElse(fail(s"no option labelled '$label' in $testId"))
    control.value = options(position).value
    dispatch(control, new dom.Event("change", new dom.EventInit { bubbles = true }))
  }

  private def labelTextFor(root: dom.Element, control: dom.Element): String = {
    val id = control.id
    Option(root.querySelector(s"label[for='$id']"))
      .map(_.textContent)
      .getOrElse(fail(s"no label points at '$id'"))
  }

  test("everyPreferenceHasALabelledControlAndTheCurrentValueSelected") {
    val prefs = new Preferences
    prefs.theme.set(ThemeChoice.Dark)
    prefs.accent.set(AccentChoice.Teal)
    prefs.density.set(DensityChoice.Compact)
    prefs.timezone.set("Asia/Tokyo")
    prefs.refreshRate.set(RefreshRate.Every1m)

    mounted(prefs.page()) { root =>
      List("settings-theme", "settings-accent", "settings-density", "settings-refresh-rate", "settings-timezone")
        .foreach(testId => assert(labelTextFor(root, byTestId(root, testId)).nonEmpty, testId))

      assertEquals(selectedLabel(root, "settings-theme"), "dark")
      assertEquals(selectedLabel(root, "settings-accent"), "Teal")
      assertEquals(selectedLabel(root, "settings-density"), "Compact")
      assertEquals(selectedLabel(root, "settings-refresh-rate"), "Every minute")

      byTestId(root, "settings-timezone") match {
        case field: dom.html.Input => assertEquals(field.value, "UTC+09:00 Asia/Tokyo")
        case other => fail(s"expected an input, found ${other.tagName}")
      }
    }
  }

  private def selectedLabel(root: dom.Element, testId: String): String = {
    val control = selectAt(root, testId)
    control.options(control.selectedIndex).textContent
  }

  test("changingAControlWritesToItsVarAndNothingElse") {
    List(
      ("theme", "settings-theme", "light", () => ()),
      ("accent", "settings-accent", "Amber", () => ()),
      ("density", "settings-density", "Compact", () => ()),
      ("refreshRate", "settings-refresh-rate", "Every 5 minutes", () => ())
    ).foreach { (name, testId, label, _) =>
      val prefs = new Preferences
      val before = prefs.others(name)
      mounted(prefs.page()) { root =>
        choose(root, testId, label)
        // A settings page that writes two preferences from one control is a bug that surfaces much
        // later, as a mystery, in a screenshot.
        assertEquals(prefs.others(name), before, s"changing $name moved something else")
      }
    }
  }

  test("changingTheTimezoneWritesOnlyTheTimezone") {
    val prefs = new Preferences
    val before = prefs.others("timezone")
    mounted(prefs.page()) { root =>
      val field = byTestId(root, "settings-timezone")
      keyDown(field, "ArrowDown")
      keyDown(field, "Enter")
      assertEquals(prefs.timezone.now(), "Europe/Warsaw")
      assertEquals(prefs.others("timezone"), before)
    }
  }

  test("theTimezoneControlIsSearchableAndFiltersByIdAndOffset") {
    val prefs = new Preferences
    mounted(prefs.page()) { root =>
      val field = byTestId(root, "settings-timezone") match {
        case found: dom.html.Input => found
        case other => fail(s"expected an input, found ${other.tagName}")
      }
      def visibleRows(query: String): List[String] = {
        field.value = query
        dispatch(field, new dom.Event("input", new dom.EventInit { bubbles = true }))
        val rows = root.querySelectorAll("li")
        (0 until rows.length).map(rows(_).textContent).toList
      }

      assertEquals(visibleRows("tokyo"), List("UTC+09:00 Asia/Tokyo"))
      assertEquals(visibleRows("+02:00"), List("UTC+02:00 Europe/Warsaw"))
    }
  }

  test("theRefreshRateDefaultsToOffInTheRenderedControl") {
    // The evidence for the milestone's "the browser does not poll" decision, at the control itself.
    mounted(new Preferences().page())(root => assertEquals(selectedLabel(root, "settings-refresh-rate"), "Off"))
  }

  test("theAboutCardStillShowsTheBuild") {
    mounted(new Preferences().page(build = "9.9.9-rc1")) { root =>
      assertEquals(byTestId(root, "settings-build").textContent, "9.9.9-rc1")
    }
  }

  test("theSettingsPageRendersWithEveryServiceDown") {
    // Nothing here reads a capability, a client or a service, and that is the assertion: the page is
    // built with no backend of any kind in scope and is fully populated.
    mounted(new Preferences().page()) { root =>
      assertEquals(attributeOf(root, "data-testid"), Some("page-settings"))
      List("settings-theme", "settings-accent", "settings-density", "settings-timezone", "settings-refresh-rate", "settings-build")
        .foreach(testId => byTestId(root, testId): Unit)
    }
  }
}
