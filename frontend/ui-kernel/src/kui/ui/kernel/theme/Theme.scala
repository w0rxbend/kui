package kui.ui.kernel.theme

import scala.util.{Success, Try}

import com.raquo.airstream.web.{WebStorageBuilder, WebStorageVar}
import com.raquo.laminar.api.L.*
import org.scalajs.dom

/** What the user asked for, which is not the same as what is displayed.
  *
  * `Auto` means "whatever the operating system is set to, and change with it". It is a third state, not a
  * synonym for one of the other two: a user on `Auto` whose laptop switches to dark in the evening expects
  * KUI to switch with it, and a user who picked `Light` expects KUI to stay light even at midnight.
  */
enum ThemeChoice(val storageValue: String) {
  case Auto extends ThemeChoice("auto")
  case Light extends ThemeChoice("light")
  case Dark extends ThemeChoice("dark")
}

object ThemeChoice {

  /** Reads a stored value back, treating anything unrecognised as `Auto`.
    *
    * Unrecognised is a real case, not a defensive one: `localStorage` survives upgrades, so a value written
    * by a future version of KUI (or by a user poking at devtools) can be read by an older one. Falling back
    * to the default is better than failing to start.
    */
  def fromStorage(raw: String): ThemeChoice =
    values.find(_.storageValue == raw).getOrElse(Auto)
}

/** Applies one of the three theme states to the document.
  *
  * ## How the three states become two palettes
  *
  * The stylesheet (`10-tokens.css`) defines the light palette on `:root`, the dark palette under
  * `@media (prefers-color-scheme: dark)` for the system preference, and the dark palette again under
  * `:root[data-theme="dark"]` for an explicit choice. This class's whole job is to keep the `data-theme`
  * attribute on `<html>` in step with the user's choice:
  *
  *   - choice `Auto` — remove the attribute, so only the media query decides;
  *   - choice `Light` — set `data-theme="light"`, which the media query's `:root:not([data-theme="light"])`
  *     guard excludes, so dark cannot win;
  *   - choice `Dark` — set `data-theme="dark"`, which is written last in the file and therefore wins over a
  *     system set to light.
  *
  * No colour is ever computed in Scala. This class writes one attribute; CSS does the rest.
  *
  * ## Why this is a class and not only an object
  *
  * The application has exactly one theme, and `object Theme` below is it. But a test needs to supply its own
  * `localStorage` (to prove that a choice survives a reload, and that a browser with storage disabled still
  * works) and its own answer for the system preference (to prove `Auto` follows it). Neither is possible
  * against a hard-wired singleton, so the behaviour lives in a class the tests can instantiate and the object
  * is a thin wiring of it to the real browser.
  *
  * @param choice
  *   what the user asked for. Writable, because the theme switcher writes to it; persisted, because the
  *   instance the object builds is backed by `localStorage`.
  * @param systemPrefersDark
  *   whether the operating system is currently asking for a dark interface.
  * @param root
  *   the element the `data-theme` attribute is written on. Always `<html>` in the application; a detached
  *   element in some tests.
  */
final class Theme(
    val choice: Var[ThemeChoice],
    systemPrefersDark: Signal[Boolean],
    root: dom.Element
) {

  /** The theme actually on screen. Never `Auto`: this is the resolved answer, so a component that needs to
    * know whether it is currently dark (to pick an icon, say) reads this and not `choice`.
    */
  val effective: Signal[ThemeChoice] =
    choice.signal.combineWith(systemPrefersDark).map { (chosen, prefersDark) =>
      chosen match {
        case ThemeChoice.Auto => if prefersDark then ThemeChoice.Dark else ThemeChoice.Light
        case explicit => explicit
      }
    }

  /** Starts keeping `data-theme` in step with `choice`, and applies the current value immediately.
    *
    * Called once by the shell during start-up. The subscription is deliberately never released: it lives as
    * long as the page does, which is what `unsafeWindowOwner` means.
    */
  def install(): Unit =
    // The subscription handle is discarded on purpose — see the comment above. Ascribing to `Unit`
    // is how the compiler is told the discard is intended, rather than a forgotten result.
    choice.signal.foreach(writeAttribute)(using unsafeWindowOwner): Unit

  private def writeAttribute(chosen: ThemeChoice): Unit =
    chosen match {
      // `Auto` must remove the attribute rather than write "auto": the media query in the stylesheet
      // matches on the attribute's *absence*, so leaving a value behind would pin the theme.
      case ThemeChoice.Auto => root.removeAttribute(Theme.Attribute)
      case explicit => root.setAttribute(Theme.Attribute, explicit.storageValue)
    }
}

object Theme {

  /** The attribute the stylesheet keys off, written on `<html>`. */
  val Attribute = "data-theme"

  /** The `localStorage` key. Namespaced, because a KUI deployment may share an origin with other pages.
    */
  val StorageKey = "kui.theme"

  private val DarkQuery = "(prefers-color-scheme: dark)"

  /** The application's one theme, wired to the real browser.
    *
    * `lazy` so that merely importing this object does not touch `localStorage` or `matchMedia` — which
    * matters in tests, where those may not be the ones under test.
    */
  private lazy val browser: Theme =
    new Theme(
      persistedChoice(WebStorageVar.localStorage(StorageKey, syncOwner = None)),
      systemPrefersDark(),
      dom.document.documentElement
    )

  /** What the user asked for. Writing to it persists the choice and re-themes the page. */
  def choice: Var[ThemeChoice] = browser.choice

  /** The theme currently on screen, with `Auto` already resolved. Never `Auto`. */
  def effective: Signal[ThemeChoice] = browser.effective

  /** Binds `data-theme` on `<html>`. Called once by the shell. */
  def install(): Unit = browser.install()

  /** A `Var` backed by `localStorage`, so the choice survives a reload.
    *
    * Airstream's builder is handed a `() => Option[Storage]`, so a browser that refuses storage — Safari
    * private browsing throws on the very first write, and enterprise policies can disable it outright —
    * yields `None` and the `Var` quietly behaves like an ordinary in-memory one. That is the required
    * behaviour: a user in a private window gets a working theme switcher that forgets their choice on reload,
    * not an application that fails to start.
    */
  private[theme] def persistedChoice(storage: WebStorageBuilder): Var[ThemeChoice] =
    storage.withCodec[ThemeChoice](
      encode = _.storageValue,
      // Decoding cannot fail: anything unrecognised is read as `Auto` (see `ThemeChoice.fromStorage`).
      decode = raw => Success(ThemeChoice.fromStorage(raw)),
      default = Try(ThemeChoice.Auto)
    )

  /** Tracks the operating system's dark-mode preference and its changes.
    *
    * `matchMedia` gives both the current answer and an event when it changes, which is what makes `Auto`
    * genuinely automatic: a laptop switching to dark at sunset re-themes an open KUI tab without a reload.
    */
  private def systemPrefersDark(): Signal[Boolean] = {
    val query = dom.window.matchMedia(DarkQuery)
    val prefers = Var(query.matches)
    // The event carries the new value, but reading it off the query object avoids depending on
    // a `MediaQueryListEvent` type that scala-js-dom does not expose.
    query.addEventListener("change", (_: dom.Event) => prefers.set(query.matches))
    prefers.signal
  }
}
