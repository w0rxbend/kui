package kui.ui.shell

import com.raquo.laminar.api.L.*
import com.raquo.waypoint.*

import kui.ui.kernel.feature.Page

/** The one router, over every page in the application.
  *
  * ## Route patterns are static; only rendering is dynamically imported
  *
  * The routes handed to `make` include every *feature's* routes, and they are registered before any feature
  * has been downloaded. That is ADR-012 amendment 2, and the reason for it is concrete: a bookmarked link to
  * a topic page must resolve on the first load, before the topics module exists in the browser. If the router
  * only learned about a URL once its feature had been imported, the very first thing it saw would be an
  * address it could not match, and the user would get a 404 for a page that exists.
  *
  * A route *pattern* is data — a path shape — so linking against it costs a few bytes in `main.js` and pulls
  * nothing else in. A route's *rendering* is a function inside the feature, and that waits for the import.
  * `checkBundleShape` (BUILD-006) fails the build if a real class reference leaks across that line.
  */
object ShellRouter {

  /** Where the frontend is served from, under whatever prefix the deployment uses.
    *
    * The gateway serves `index.html` for every URL beneath this, which is what makes a deep link work: the
    * browser asks for `/ui/settings`, gets the application, and the router then decides what `/ui/settings`
    * means (ARCHITECTURE.md §12).
    */
  val UiPath = "/ui"

  /** The shell's own routes, in the order they are tried.
    *
    * `endOfSegments` is on every one of them deliberately. Without it a pattern matches a *prefix*, so
    * `/settings` would also claim `/settings/anything`, and the 404 for a mistyped sub-path would never
    * appear. It is also what ADR-011 asks for as forward compatibility with Waypoint 10, where the explicit
    * form becomes the only form.
    */
  def shellRoutes(basePath: String): List[Route[? <: Page, ?]] = {
    val prefix = basePath.stripSuffix("/") + UiPath

    List(
      Route.static(ShellPage.Home, root / endOfSegments, prefix),
      Route.static(ShellPage.Settings, root / "settings" / endOfSegments, prefix),
      Route.static(ShellPage.Gallery, root / "gallery" / endOfSegments, prefix),
      Route[ShellPage.Forbidden, String](
        encode = page => page.what,
        decode = what => ShellPage.Forbidden(what),
        pattern = root / "forbidden" / segment[String] / endOfSegments,
        basePath = prefix
      )
    )
  }

  /** Builds the router.
    *
    * @param featureRoutes
    *   every loaded *and* not-yet-loaded feature's route patterns. Empty in M0; UI-012 supplies the first.
    * @param initialUrl
    *   the address the browser is on. A parameter so that a suite can start the router anywhere without a
    *   real `window.location`.
    */
  def make(
      basePath: String,
      featureRoutes: List[Route[? <: Page, ?]],
      initialUrl: String,
      origin: String
  )(using owner: Owner): Router[Page] =
    new Router[Page](
      // Feature routes come last, so that a feature cannot accidentally shadow the shell's own
      // addresses by declaring a pattern that also matches `/ui/settings`.
      routes = shellRoutes(basePath) ++ featureRoutes,
      getPageTitle = titleOf,
      serializePage = PageCodec.encode,
      deserializePage = PageCodec.decode,
      // Anything that matches no route is the 404 page, carrying the address that was attempted, so
      // the page can show the user what they actually asked for.
      routeFallback = url => ShellPage.NotFound(url),
      // The same, for a `history.state` this build cannot read — see `PageCodec`.
      deserializeFallback = _ => ShellPage.NotFound(""),
      popStateEvents = windowEvents(_.onPopState),
      owner = owner,
      origin = origin,
      initialUrl = initialUrl
    )

  /** What goes in the browser tab.
    *
    * The product name comes last, because a tab strip truncates from the right and the part a user is
    * scanning for is which page it is, not which application.
    */
  def titleOf(page: Page): String =
    page match {
      case ShellPage.Home => "KUI"
      case ShellPage.Settings => "Settings · KUI"
      case ShellPage.Gallery => "Components · KUI"
      case ShellPage.NotFound(_) => "Not found · KUI"
      case ShellPage.Forbidden(_) => "No permission · KUI"
      // A feature page. The feature will supply its own title in a later milestone; until then the
      // product name alone is better than a blank tab.
      case _ => "KUI"
    }
}
