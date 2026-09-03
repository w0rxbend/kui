package kui.ui.kernel.theme

import com.raquo.airstream.web.WebStorageBuilder
import munit.FunSuite
import org.scalajs.dom

/** The two preferences that are not the theme: which accent the product is painted in, and how much air a
  * table row has.
  *
  * Both work the same way and both are checked for the same three things: the default writes no attribute at
  * all, a non-default choice writes the value the stylesheet matches on, and the choice survives a reload.
  * The first of those is the one worth stating aloud — the default palette and the default density are the
  * ones declared on plain `:root`, so writing `data-accent="blue"` would be redundant at best, and a selector
  * nobody remembers to keep in step at worst.
  */
final class AppearanceSuite extends FunSuite {

  private def storageFor(key: String): WebStorageBuilder =
    new WebStorageBuilder(() => AppearanceSuite.localStorage, key, syncOwner = None)

  private def uniqueKey(name: String): String = s"kui.test.$name.${AppearanceSuite.nextKey()}"

  test("accentDefaultsToBlueAndWritesNoAttribute") {
    val root = dom.document.createElement("html")
    val chosen = Accent.persistedChoice(storageFor(uniqueKey("accent")))
    val accent = new RootPreference[AccentChoice](
      chosen,
      Accent.Attribute,
      value => Option.when(value != AccentChoice.Blue)(value.storageValue),
      root
    )

    accent.install()

    assertEquals(chosen.now(), AccentChoice.Blue)
    assertEquals(root.hasAttribute(Accent.Attribute), false)

    chosen.set(AccentChoice.Amber)
    assertEquals(root.getAttribute(Accent.Attribute), "amber")

    // And back: choosing the default has to *remove* the attribute, not leave the previous one
    // behind, or the switcher becomes one-way.
    chosen.set(AccentChoice.Blue)
    assertEquals(root.hasAttribute(Accent.Attribute), false)
  }

  test("densityDefaultsToComfortableAndOnlyCompactWritesAnAttribute") {
    val root = dom.document.createElement("html")
    val chosen = Density.persistedChoice(storageFor(uniqueKey("density")))
    val density = new RootPreference[DensityChoice](
      chosen,
      Density.Attribute,
      value => Option.when(value != DensityChoice.Comfortable)(value.storageValue),
      root
    )

    density.install()

    assertEquals(chosen.now(), DensityChoice.Comfortable)
    assertEquals(root.hasAttribute(Density.Attribute), false)

    chosen.set(DensityChoice.Compact)
    assertEquals(root.getAttribute(Density.Attribute), "compact")
  }

  test("anUnrecognisedStoredValueIsReadAsTheDefault") {
    // `localStorage` outlives upgrades and is editable in devtools, so an unknown value is a real
    // case rather than a defensive one. Starting in the default beats failing to start.
    assertEquals(AccentChoice.fromStorage("magenta"), AccentChoice.Blue)
    assertEquals(AccentChoice.fromStorage(""), AccentChoice.Blue)
    assertEquals(DensityChoice.fromStorage("microscopic"), DensityChoice.Comfortable)
  }

  test("theStoredNameOfEveryChoiceMatchesTheStylesheet") {
    // The enum's storage values are also the attribute values the stylesheet matches on, so a
    // rename here silently stops repainting rather than failing to compile. This is the only place
    // that connection is written down.
    assertEquals(AccentChoice.values.map(_.storageValue).toList, List("blue", "teal", "green", "amber"))
    assertEquals(DensityChoice.values.map(_.storageValue).toList, List("comfortable", "compact"))
  }

  test("choiceSurvivesAReload") {
    assume(AppearanceSuite.localStorage.isDefined, "this JavaScript environment has no localStorage")

    val key = uniqueKey("reload")
    val first = Accent.persistedChoice(storageFor(key))
    first.set(AccentChoice.Teal)

    // A reload is a brand-new program reading the same storage. Building a second `Var` over the
    // same key is exactly that, minus the page navigation.
    assertEquals(Accent.persistedChoice(storageFor(key)).now(), AccentChoice.Teal)
  }

  test("unavailableLocalStorageStillGivesAWorkingSwitch") {
    val withoutStorage =
      Accent.persistedChoice(new WebStorageBuilder(() => None, "kui.test.absent.accent", syncOwner = None))

    assertEquals(withoutStorage.now(), AccentChoice.Blue)

    withoutStorage.set(AccentChoice.Green)
    assertEquals(withoutStorage.now(), AccentChoice.Green)
  }
}

private object AppearanceSuite {

  /** `localStorage` if this JavaScript environment has a usable one, otherwise nothing. Merely *reading*
    * `window.localStorage` throws in a browser that has disabled it, which is why this is wrapped rather than
    * null-checked.
    */
  val localStorage: Option[dom.Storage] =
    scala.util.Try(Option(dom.window.localStorage)).toOption.flatten

  private var counter = 0

  /** A fresh number per call, so two tests using storage never collide on a key. */
  def nextKey(): Int = {
    counter += 1
    counter
  }
}
