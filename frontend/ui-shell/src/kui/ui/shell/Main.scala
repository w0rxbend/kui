package kui.ui.shell

import com.raquo.laminar.api.L.*
import com.raquo.waypoint.{Route, Router, SplitRender}
import org.scalajs.dom

import kui.ui.kernel.api.Bootstrap
import kui.ui.kernel.feature.Page
import kui.ui.kernel.theme.Theme
import kui.ui.shell.layout.{Header, Layout, Sidebar}
import kui.ui.shell.page.{
  ForbiddenPage,
  GalleryPage,
  GatewayUnreachable,
  HomePage,
  NotFoundPage,
  SettingsPage
}

/** The application's entry point, and the only one.
  *
  * ADR-012 puts every KUI-owned feature behind a single Scala.js link, with the linker splitting features
  * into modules the browser fetches on demand. That arrangement needs exactly one `main`, and this is it.
  *
  * ## The order of start-up matters
  *
  * The theme is installed before anything is rendered, so that the first paint is already in the user's
  * chosen colours rather than flashing light and then correcting itself. Error reporting is installed before
  * the router, so that a failure while building the first page is reported rather than silently swallowed.
  */
@main def main(): Unit = Shell.start()

/** Everything `main` does, as a value a test can call.
  *
  * Separate from `@main` because a `@main` method is a generated object with a `main(Array[String])`, which
  * is awkward to invoke and impossible to parameterise. This can be started against a container and a URL of
  * the caller's choosing.
  */
object Shell {

  /** The element in `index.html` the application is rendered into (GW-008). */
  val RootId = "kui-root"

  def start(): Unit = {
    val bootstrap = Bootstrap.read()

    Theme.install()
    ErrorReporting.install()

    // `getElementById` answers with `null` when there is no such element; `Option` is how that
    // becomes a value the rest of the code can reason about.
    val root = Option(dom.document.getElementById(RootId)).getOrElse(appendedRoot())

    renderOnDomContentLoaded(root, app(bootstrap, dom.window.location.href, dom.window.location.origin))
  }

  /** The whole application, as one element.
    *
    * Taking the URL and the origin as parameters rather than reading `window.location` is what lets a suite
    * mount the shell on any address without touching the browser's real history.
    */
  def app(bootstrap: Bootstrap, initialUrl: String, origin: String): HtmlElement = {
    given Owner = unsafeWindowOwner

    val router = ShellRouter.make(
      basePath = bootstrap.basePath,
      // Empty in M0. UI-012 supplies the clusters feature's patterns here, and they are registered
      // before the feature is downloaded — see `ShellRouter`.
      featureRoutes = List.empty[Route[? <: Page, ?]],
      initialUrl = initialUrl,
      origin = origin
    )

    // What the *server* said its build was. `Bootstrap` already falls back to "dev" when the block is
    // missing, and `GET /api/v1/info` will replace this once the gateway's contract module exists;
    // the signal is here so that becomes a change of source and not a change of shape.
    val buildVersion = Var(bootstrap.buildVersion)

    // Built once and shown or hidden, rather than built when it is needed: by the time it is
    // needed, nothing is answering, and this is the worst possible moment to be constructing
    // something for the first time.
    val unreachable = GatewayUnreachable(
      ShellHealth.connectivity,
      Observer[Unit](_ => ShellHealth.retryNow())
    )

    Layout(
      sidebar = Sidebar(router, Val(Sidebar.shellItems)),
      header = Header(buildVersion.signal, Theme.choice),
      content = render(router, buildVersion.signal),
      fullScreen = ShellHealth.connectivity.map {
        case ShellConnectivity.Lost(_, _, _) => Some(unreachable)
        case ShellConnectivity.Connected(_) => None
      }
    )
  }

  /** Which element is in the content area, for the current page.
    *
    * `SplitRender` is Waypoint's wrapper around Airstream's `split`, and it gives the property ADR-011 §3.3
    * asks for: each page's element is built **once per page instance**, not once per navigation. Navigating
    * from a topic to another topic hands the existing element a new value through its signal rather than
    * throwing it away, which is what keeps a scroll position, an open menu and a half-typed filter alive
    * across a navigation that did not change the *kind* of page.
    *
    * Every branch goes through `ErrorReporting.renderSafely`, so a page that throws while being built shows a
    * panel saying so and leaves the rest of the shell working (ADR-011 §3.6).
    */
  private def render(router: Router[Page], buildVersion: Signal[String]): Signal[HtmlElement] = {
    // `collectStatic` takes its view *by name* and re-evaluates it every time the page signal emits
    // that page — including when it re-emits the page already on screen. A `lazy val` is what turns
    // that into "built on first visit, reused afterwards": the element for a singleton page exists
    // once for the life of the application, which is what keeps its scroll position and any open
    // menu alive across a navigation away and back.
    lazy val home = ErrorReporting.renderSafely(() => HomePage())
    lazy val settings = ErrorReporting.renderSafely(() => SettingsPage(Theme.choice, buildVersion))
    lazy val gallery = ErrorReporting.renderSafely(() => GalleryPage())

    SplitRender[Page, HtmlElement](router.currentPageSignal)
      .collectStatic(ShellPage.Home)(home)
      .collectStatic(ShellPage.Settings)(settings)
      .collectStatic(ShellPage.Gallery)(gallery)
      .collectSignal[ShellPage.NotFound](signal =>
        ErrorReporting.renderSafely(() => NotFoundPage(signal.map(_.url), router))
      )
      .collectSignal[ShellPage.Forbidden](signal =>
        ErrorReporting.renderSafely(() => ForbiddenPage(signal.map(_.what), router))
      )
      // A page from a feature this build cannot render. Not reachable in M0 — there are no feature
      // routes — but the branch has to exist, because `SplitRender` with no match emits nothing at
      // all and the content area would simply stay blank with no explanation.
      .collect[Page](page =>
        ErrorReporting.renderSafely(() => NotFoundPage(Val(pathOf(router, page)), router))
      )
      .signal
  }

  private def pathOf(router: Router[Page], page: Page): String =
    router.relativeUrlForPage(page)

  /** Somewhere to render when the served `index.html` has no `#kui-root`.
    *
    * Only reachable when the frontend is opened from a bare page — a linker output loaded by hand. Creating
    * the element beats refusing to start with a message nobody sees.
    */
  private def appendedRoot(): dom.Element = {
    val created = dom.document.createElement("div")
    created.id = RootId
    dom.document.body.appendChild(created): Unit
    created
  }
}
