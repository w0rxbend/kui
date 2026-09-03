package kui.ui.kernel.theme

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.airstream.web.WebStorageBuilder
import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.scalajs.dom

/** The three-state theme switcher: what the user asked for, what is actually displayed, and what
  * survives a reload.
  *
  * Every test builds its own `Theme` rather than using the application-wide `Theme` object, because
  * each one needs to control either the stored value or the operating system's preference, and a
  * singleton wired to the real browser lets it control neither.
  */
final class ThemeSuite extends FunSuite {

  private val owner = new ManualOwner

  /** A `Theme` with storage that is present but empty, and a system preference the test controls. */
  private def themeWith(systemPrefersDark: Signal[Boolean], storageKey: String): Theme =
    new Theme(
      Theme.persistedChoice(storageFor(storageKey)),
      systemPrefersDark,
      dom.document.createElement("html")
    )

  /** A builder over `localStorage` if this environment has one, and over nothing if it does not.
    *
    * jsdom's `localStorage` depends on the document having a real origin, which is not something a
    * test should assume. Where it is missing, Airstream's `Var` behaves as an ordinary in-memory one
    * and every test here except the reload test still means what it says.
    */
  private def storageFor(key: String): WebStorageBuilder =
    new WebStorageBuilder(() => ThemeSuite.localStorage, key, syncOwner = None)

  private def uniqueKey(name: String): String = s"kui.test.$name.${ThemeSuite.nextKey()}"

  test("defaultsToAuto") {
    val theme = themeWith(Val(false), uniqueKey("default"))

    assertEquals(theme.choice.now(), ThemeChoice.Auto)
  }

  test("autoFollowsPrefersColorScheme") {
    val prefersDark = Var(false)
    val theme       = themeWith(prefersDark.signal, uniqueKey("auto"))
    val effective   = theme.effective.observe(using owner)

    assertEquals(effective.now(), ThemeChoice.Light)

    // The operating system switches to dark — at sunset, or because the user flipped the setting in
    // another window. An open KUI tab must follow without a reload.
    prefersDark.set(true)
    assertEquals(effective.now(), ThemeChoice.Dark)
  }

  test("explicitChoiceOverridesTheMediaQuery") {
    val prefersDark = Var(true)
    val theme       = themeWith(prefersDark.signal, uniqueKey("explicit"))
    val effective   = theme.effective.observe(using owner)

    // System says dark, user says light: the user wins.
    theme.choice.set(ThemeChoice.Light)
    assertEquals(effective.now(), ThemeChoice.Light)

    // And the other direction: system says light, user says dark.
    prefersDark.set(false)
    theme.choice.set(ThemeChoice.Dark)
    assertEquals(effective.now(), ThemeChoice.Dark)

    // Back to Auto and the system is in charge again.
    theme.choice.set(ThemeChoice.Auto)
    assertEquals(effective.now(), ThemeChoice.Light)
  }

  test("choiceSurvivesAReload") {
    assume(ThemeSuite.localStorage.isDefined, "this JavaScript environment has no localStorage")

    val key   = uniqueKey("reload")
    val first = themeWith(Val(false), key)
    first.choice.set(ThemeChoice.Dark)

    // A reload is a brand-new program reading the same storage. Constructing a second `Theme` over
    // the same key is exactly that, minus the page navigation.
    val second = themeWith(Val(false), key)

    assertEquals(second.choice.now(), ThemeChoice.Dark)
  }

  test("unavailableLocalStorageFallsBackToAutoWithoutThrowing") {
    // Safari's private browsing and some enterprise policies make storage unavailable. The switcher
    // must still work; it just forgets the choice when the tab closes.
    val withoutStorage = new Theme(
      Theme.persistedChoice(new WebStorageBuilder(() => None, "kui.test.absent", syncOwner = None)),
      Val(false),
      dom.document.createElement("html")
    )

    assertEquals(withoutStorage.choice.now(), ThemeChoice.Auto)

    withoutStorage.choice.set(ThemeChoice.Dark)
    assertEquals(withoutStorage.choice.now(), ThemeChoice.Dark)
  }

  test("anUnrecognisedStoredValueIsReadAsAuto") {
    // `localStorage` outlives upgrades and is editable in devtools, so an unknown value is a real
    // case. Starting in the default beats failing to start.
    assertEquals(ThemeChoice.fromStorage("solarized"), ThemeChoice.Auto)
    assertEquals(ThemeChoice.fromStorage(""), ThemeChoice.Auto)
  }

  test("dataThemeAttributeIsSetOnTheHtmlElement") {
    val root  = dom.document.createElement("html")
    val theme = new Theme(Theme.persistedChoice(storageFor(uniqueKey("attribute"))), Val(false), root)

    theme.install()

    // Auto must leave the attribute off entirely: the stylesheet's media query is guarded by
    // `:not([data-theme="light"])` and matches on the attribute's absence, so writing "auto" would
    // pin the theme instead of following the system.
    assertEquals(root.hasAttribute(Theme.Attribute), false)

    theme.choice.set(ThemeChoice.Dark)
    assertEquals(root.getAttribute(Theme.Attribute), "dark")

    theme.choice.set(ThemeChoice.Light)
    assertEquals(root.getAttribute(Theme.Attribute), "light")

    theme.choice.set(ThemeChoice.Auto)
    assertEquals(root.hasAttribute(Theme.Attribute), false)
  }
}

private object ThemeSuite {

  /** `localStorage` if this JavaScript environment has a usable one, otherwise nothing.
    *
    * Merely *reading* `window.localStorage` throws in a browser that has disabled it, which is why
    * this is wrapped rather than tested with a null check.
    */
  val localStorage: Option[dom.Storage] =
    scala.util.Try(Option(dom.window.localStorage)).toOption.flatten

  private var counter = 0

  /** A fresh number per call, so that two tests using storage never collide on a key. */
  def nextKey(): Int = {
    counter += 1
    counter
  }
}
