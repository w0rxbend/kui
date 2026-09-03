package kui.ui.shell

import io.circe.syntax.*
import io.circe.{Decoder, Encoder, HCursor, Json}

import kui.ui.kernel.feature.{FeatureRoutes, Page}

/** The pages the shell itself owns.
  *
  * Every other page in KUI belongs to a feature and lives in that feature's module; the shell never names one
  * (ADR-012). These four are the frame around them.
  *
  * They extend `kui.ui.kernel.feature.Page`, which is the base type the router is built over. It lives in the
  * kernel rather than here because it is the only module both the shell and every feature can see: a feature
  * has to be able to declare its pages without depending on the shell, and the shell has to be able to hold a
  * route without depending on the feature.
  */
sealed trait ShellPage extends Page

object ShellPage {

  /** The dashboard. */
  case object Home extends ShellPage

  /** Theme and build information. The "settings stub" of the roadmap; nothing else lives here yet. */
  case object Settings extends ShellPage

  /** The design-system gallery: every kernel primitive, in both themes.
    *
    * Deferred here from UI-003 and UI-004, because until the shell existed there was no router to register it
    * with. It is a development page, not a product feature — it is how a change to a primitive is reviewed
    * without opening eight screens looking for what it broke.
    */
  case object Gallery extends ShellPage

  /** The URL did not match anything. Carries what was attempted, so the page can show it. */
  final case class NotFound(url: String) extends ShellPage

  /** The user may not see something. Carries what, in the vaguest terms that are still useful.
    *
    * `what` is a human-readable noun ("the schema registry"), never an identifier that would confirm a
    * resource exists. The 403 page's message is identical for a resource that exists and one that does not,
    * which is the whole point of having one.
    */
  final case class Forbidden(what: String) extends ShellPage

  given CanEqual[ShellPage, ShellPage] = CanEqual.derived
}

/** Turns a page into the string the browser stores in `history.state`, and back.
  *
  * ## Why this exists at all
  *
  * When a user presses Back, the browser hands the application whatever it stored when that entry was
  * created. Waypoint uses it to restore the exact page rather than re-parsing the URL, which matters for a
  * page whose identity is richer than its path.
  *
  * ## Why nothing here throws
  *
  * A stored state can be *older than the running code*: a user with a tab open across a deploy presses Back
  * and the browser hands the new build a string the old build wrote. Failing to read it must not break the
  * application, so anything unrecognised — a page a later version invented, a truncated string, a value a
  * user typed into devtools — decodes to [[ShellPage.NotFound]]. The user gets a page saying the address does
  * not exist, with working navigation, instead of a blank screen.
  *
  * ## Feature pages contribute their own codec
  *
  * The shell cannot name a feature's page type — that would defeat the whole lazy-loading arrangement — so a
  * feature registers a codec alongside its route patterns (`FeatureRoutes`, ADR-012 amendment 2) and this
  * object tries each contributor in turn. Without that, Back onto a feature page would serialize as
  * `unknown`, come back as `NotFound`, and the user would be shown a "page does not exist" screen for the
  * page they had just been looking at.
  *
  * The contributors are a parameter rather than something read from a global, so a suite can exercise the
  * whole mechanism with a stub feature and no registry to install or tear down.
  */
object PageCodec {

  private val UnknownTag = "unknown"

  def encode(page: Page): String = encode(page, Nil)

  def decode(raw: String): Page = decode(raw, Nil)

  /** @param features
    *   every registered feature's codec contribution, tried in order after the shell's own pages.
    */
  def encode(page: Page, features: List[FeatureRoutes]): String =
    encoder(features)(page).noSpaces

  def decode(raw: String, features: List[FeatureRoutes]): Page =
    io.circe.parser.decode[Page](raw)(using decoder(features)).getOrElse(fallback(raw))

  def encoder(features: List[FeatureRoutes]): Encoder[Page] = Encoder.instance { page =>
    shellEncoder
      .lift(page)
      .orElse(features.iterator.map(_.encodePage(page)).collectFirst { case Some(json) => json })
      // A page from a feature this build has no codec for. Written as a recognisable placeholder
      // rather than as nothing, so that a debugger looking at `history.state` sees why.
      .getOrElse(tagged(UnknownTag))
  }

  def decoder(features: List[FeatureRoutes]): Decoder[Page] = (cursor: HCursor) =>
    cursor.get[String]("page").map { tag =>
      shellDecoder(tag, cursor)
        .orElse(features.iterator.map(_.decodePage(tag, cursor)).collectFirst { case Some(page) => page })
        .getOrElse(ShellPage.NotFound(""))
    }

  /** What an unreadable state becomes. Deliberately not an exception — see the class comment. */
  private def fallback(raw: String): Page =
    ShellPage.NotFound(if raw.isEmpty then "/" else "")

  private val shellEncoder: PartialFunction[Page, Json] = {
    case ShellPage.Home => tagged("home")
    case ShellPage.Settings => tagged("settings")
    case ShellPage.Gallery => tagged("gallery")
    case ShellPage.NotFound(url) => tagged("not-found").deepMerge(Json.obj("url" -> url.asJson))
    case ShellPage.Forbidden(what) => tagged("forbidden").deepMerge(Json.obj("what" -> what.asJson))
  }

  private def shellDecoder(tag: String, cursor: HCursor): Option[Page] =
    tag match {
      case "home" => Some(ShellPage.Home)
      case "settings" => Some(ShellPage.Settings)
      case "gallery" => Some(ShellPage.Gallery)
      case "not-found" => Some(ShellPage.NotFound(cursor.get[String]("url").getOrElse("")))
      case "forbidden" => Some(ShellPage.Forbidden(cursor.get[String]("what").getOrElse("")))
      case _ => None
    }

  private def tagged(name: String): Json = Json.obj("page" -> Json.fromString(name))
}
