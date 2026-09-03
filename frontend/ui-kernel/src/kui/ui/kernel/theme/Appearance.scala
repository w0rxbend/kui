package kui.ui.kernel.theme

import scala.util.{Success, Try}

import com.raquo.airstream.web.{WebStorageBuilder, WebStorageVar}
import com.raquo.laminar.api.L.*
import org.scalajs.dom

/** Which accent the product is painted in.
  *
  * The design ships four interchangeable seed palettes and treats the choice as a first-class control rather
  * than a build-time constant. It is nearly free to offer: the neutral ramp — every surface, every text
  * colour, every status colour — is identical under all four, so a seed redefines exactly four custom
  * properties (`primary`, its text colour, its container and that container's text colour) and repaints
  * instantly.
  *
  * `Blue` is the design's default and is the palette declared on `:root`, so a page that has never touched
  * this preference carries no `data-accent` attribute at all.
  */
enum AccentChoice(val storageValue: String) {
  case Blue extends AccentChoice("blue")
  case Teal extends AccentChoice("teal")
  case Green extends AccentChoice("green")
  case Amber extends AccentChoice("amber")
}

object AccentChoice {

  /** Reads a stored value back, treating anything unrecognised as the default.
    *
    * Unrecognised is a real case rather than a defensive one: `localStorage` outlives upgrades, so a value
    * written by a later version of KUI — or typed into devtools — can be read by an earlier one. Starting in
    * the default beats failing to start.
    */
  def fromStorage(raw: String): AccentChoice =
    values.find(_.storageValue == raw).getOrElse(Blue)
}

/** How much vertical air a table row has.
  *
  * The design treats density as a switch and not as a theme: `Compact` changes the padding inside a table row
  * from 15 px to 9 px, and changes nothing else — not type size, not the gaps between sections, not the
  * height of a control. That restraint is the point. An operator scanning thousands of topics wants more rows
  * on the screen; shrinking everything else would only make the interface harder to hit.
  */
enum DensityChoice(val storageValue: String) {
  case Comfortable extends DensityChoice("comfortable")
  case Compact extends DensityChoice("compact")
}

object DensityChoice {

  /** Reads a stored value back, treating anything unrecognised as `Comfortable`. */
  def fromStorage(raw: String): DensityChoice =
    values.find(_.storageValue == raw).getOrElse(Comfortable)
}

/** A user preference whose entire effect is one attribute on the `<html>` element.
  *
  * Both preferences below work the same way, and so does the theme switcher: Scala writes an attribute, the
  * stylesheet keys off it, and no colour or measurement is ever computed in Scala. A preference whose default
  * needs *no* attribute (because the default values are the ones declared on plain `:root`) says so by
  * mapping that case to `None`, and the attribute is removed rather than written with a value the stylesheet
  * would then have to match.
  *
  * It is a class rather than an object so that a test can supply its own storage and its own element; the
  * objects below are the thin wiring of it to the real browser.
  *
  * @param choice
  *   what the user asked for. Writable, because a switcher writes to it; persisted, because the instance the
  *   companion object builds is backed by `localStorage`.
  * @param attribute
  *   the attribute name, for example `data-accent`.
  * @param attributeValue
  *   the value to write for a choice, or `None` to remove the attribute entirely.
  * @param root
  *   the element the attribute is written on. Always `<html>` in the application.
  */
final class RootPreference[A](
    val choice: Var[A],
    attribute: String,
    attributeValue: A => Option[String],
    root: dom.Element
) {

  /** Starts keeping the attribute in step with `choice`, and applies the current value immediately.
    *
    * The subscription is deliberately never released: it lives as long as the page does, which is what
    * `unsafeWindowOwner` means.
    */
  def install(): Unit =
    // The subscription handle is discarded on purpose — see above. Ascribing to `Unit` is how the
    // compiler is told the discard is intended rather than a forgotten result.
    choice.signal.foreach(write)(using unsafeWindowOwner): Unit

  private def write(chosen: A): Unit =
    attributeValue(chosen) match {
      case None => root.removeAttribute(attribute)
      case Some(value) => root.setAttribute(attribute, value)
    }
}

object RootPreference {

  /** A `Var` backed by `localStorage`, so a preference survives a reload.
    *
    * Airstream's builder is handed a `() => Option[Storage]`, so a browser that refuses storage — Safari
    * private browsing throws on the first write, and enterprise policy can disable it outright — yields
    * `None` and the `Var` quietly behaves like an ordinary in-memory one. That is the required behaviour: a
    * user in a private window gets a working switcher that forgets the choice on reload, not an application
    * that fails to start.
    *
    * `private[kernel]` rather than `private[theme]`: the preferences in `kui.ui.kernel.prefs` — the timezone
    * and the refresh rate — are stored the same way but are not matters of appearance, so they live in their
    * own package and still need this.
    */
  private[kernel] def persisted[A](
      storage: WebStorageBuilder,
      encode: A => String,
      decode: String => A,
      default: A
  ): Var[A] =
    storage.withCodec[A](
      encode = encode,
      // Decoding cannot fail: anything unrecognised is read as the default.
      decode = raw => Success(decode(raw)),
      default = Try(default)
    )
}

/** The application's accent preference, wired to the real browser. */
object Accent {

  /** The attribute the stylesheet keys off, written on `<html>`. */
  val Attribute = "data-accent"

  /** The `localStorage` key. Namespaced, because a KUI deployment may share an origin. */
  val StorageKey = "kui.accent"

  /** `lazy` so that merely importing this object does not touch `localStorage`, which matters in tests where
    * storage is not the thing under test.
    */
  private lazy val browser: RootPreference[AccentChoice] =
    new RootPreference[AccentChoice](
      persistedChoice(WebStorageVar.localStorage(StorageKey, syncOwner = None)),
      Attribute,
      // Blue is what plain `:root` already declares, so the default writes no attribute at all.
      chosen => Option.when(chosen != AccentChoice.Blue)(chosen.storageValue),
      dom.document.documentElement
    )

  /** What the user asked for. Writing to it persists the choice and repaints the accent. */
  def choice: Var[AccentChoice] = browser.choice

  /** Binds `data-accent` on `<html>`. Called once by the shell. */
  def install(): Unit = browser.install()

  private[theme] def persistedChoice(storage: WebStorageBuilder): Var[AccentChoice] =
    RootPreference.persisted(
      storage,
      _.storageValue,
      AccentChoice.fromStorage,
      AccentChoice.Blue
    )
}

/** The application's density preference, wired to the real browser. */
object Density {

  /** The attribute the stylesheet keys off, written on `<html>`. */
  val Attribute = "data-density"

  /** The `localStorage` key. */
  val StorageKey = "kui.density"

  private lazy val browser: RootPreference[DensityChoice] =
    new RootPreference[DensityChoice](
      persistedChoice(WebStorageVar.localStorage(StorageKey, syncOwner = None)),
      Attribute,
      // Comfortable is what plain `:root` declares, so only compact writes an attribute.
      chosen => Option.when(chosen != DensityChoice.Comfortable)(chosen.storageValue),
      dom.document.documentElement
    )

  /** What the user asked for. Writing to it persists the choice and re-lays the tables. */
  def choice: Var[DensityChoice] = browser.choice

  /** Binds `data-density` on `<html>`. Called once by the shell. */
  def install(): Unit = browser.install()

  private[theme] def persistedChoice(storage: WebStorageBuilder): Var[DensityChoice] =
    RootPreference.persisted(
      storage,
      _.storageValue,
      DensityChoice.fromStorage,
      DensityChoice.Comfortable
    )
}
