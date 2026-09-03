package kui.ui.shell

import com.raquo.laminar.api.L.*
import com.raquo.waypoint.{Router, SplitRender}
import org.scalajs.dom

import kui.gateway.contract.CapabilityEndpoints
import kui.ui.kernel.api.{ApiClient, Bootstrap}
import kui.ui.kernel.feature.{FeatureRegistry, FeatureRoutes, Page}
import kui.ui.kernel.state.FeatureState.*
import kui.ui.kernel.state.{AuthState, CapabilityStore, FeatureState}
import kui.ui.kernel.theme.Theme
import kui.ui.shell.feature.{CapabilityBanner, FeatureGate}
import kui.ui.shell.layout.{Header, Layout, Sidebar}
import kui.ui.shell.nav.Navigation
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
  * The feature registry is installed before either, because the router is built from the feature route
  * patterns it holds and a deep link must resolve on the very first pass.
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

    FeatureRegistry.install(FeatureRegistryImpl.thunks, FeatureRegistryImpl.staticRoutes)
    Theme.install()
    ErrorReporting.install()

    ShellHealth.install()

    // Based at the deployment root, not at `apiBase`: every contract endpoint already carries the full
    // `/api/v1/...` path. See `Bootstrap.gatewayRoot`.
    val api = ApiClient.make(Bootstrap.gatewayRoot(bootstrap), AuthState.current)

    // The capability picture is what the whole navigation is drawn from, so it is started before the
    // first paint. Nothing waits for it: an empty picture renders as `Degraded(Starting)`, which is
    // the honest state — the features are usable and their health has not been established yet.
    CapabilityStore.start(
      streamUrl = streamUrlFor(bootstrap),
      poll = () => api.call(CapabilityEndpoints.snapshot, ())
    )

    // `getElementById` answers with `null` when there is no such element; `Option` is how that
    // becomes a value the rest of the code can reason about.
    val root = Option(dom.document.getElementById(RootId)).getOrElse(appendedRoot())

    renderOnDomContentLoaded(
      root,
      app(bootstrap, dom.window.location.href, dom.window.location.origin, Some(api))
    )
  }

  /** Where the capability stream lives.
    *
    * Built from the bootstrap's own `apiBase` rather than written out, so that a deployment under a prefix
    * (`/kafka/api/v1`) streams from the right address without a second setting that has to be kept in step
    * with the first.
    */
  def streamUrlFor(bootstrap: Bootstrap): String =
    s"${bootstrap.apiBase.stripSuffix("/")}/capabilities/stream"

  /** The whole application, as one element.
    *
    * Taking the URL and the origin as parameters rather than reading `window.location` is what lets a suite
    * mount the shell on any address without touching the browser's real history.
    *
    * @param api
    *   the client the retry button probes through. `None` for a suite that only wants the frame: the panels
    *   then render with a retry that does nothing, which is better than a suite having to stand up a backend
    *   to look at a layout.
    */
  def app(
      bootstrap: Bootstrap,
      initialUrl: String,
      origin: String,
      api: Option[ApiClient] = None
  ): HtmlElement = {
    given Owner = unsafeWindowOwner

    // Route patterns come from the static half of the registry, so they are registered before any
    // feature has been downloaded and a bookmarked deep link resolves on the first pass (ADR-012
    // amendment 2). The same registrations carry the `history.state` codecs, so Back onto a feature
    // page restores it rather than decoding to "not found".
    val features: List[FeatureRoutes] = FeatureRegistry.staticRoutes

    // Where the frontend is mounted, deployment prefix included. Feature patterns are declared relative
    // to it, because only the shell knows where the deployment put KUI.
    val uiPrefix = bootstrap.basePath.stripSuffix("/") + ShellRouter.UiPath

    val router = ShellRouter.make(
      basePath = bootstrap.basePath,
      featureRoutes = features.flatMap(_.routes(uiPrefix)),
      initialUrl = initialUrl,
      origin = origin,
      featureCodecs = features
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

    val states: List[(FeatureRoutes, Signal[FeatureState])] =
      features.map(registration =>
        // `permitted` is always true in M0. Roles arrive in M6, and this is the one call site that
        // changes when they do.
        registration -> CapabilityStore.featureState(registration.id, None, Val(true))
      )

    Layout(
      sidebar = Sidebar(router, Navigation.items(states, hideForbidden = false)),
      header = Header(buildVersion.signal, Theme.choice),
      content = content(router, buildVersion.signal, states, api, uiPrefix),
      fullScreen = ShellHealth.connectivity.map {
        case ShellConnectivity.Lost(_, _, _) => Some(unreachable)
        case ShellConnectivity.Connected(_) => None
      }
    )
  }

  /** The content area: the capability banner, and under it whatever the current page is. */
  private def content(
      router: Router[Page],
      buildVersion: Signal[String],
      states: List[(FeatureRoutes, Signal[FeatureState])],
      api: Option[ApiClient],
      uiPrefix: String
  )(using Owner): Signal[HtmlElement] = {
    val banner = CapabilityBanner(CapabilityStore.connection, degradedLabels(states))
    render(router, buildVersion, states, api, uiPrefix).map(page => div(banner, page))
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
  private def render(
      router: Router[Page],
      buildVersion: Signal[String],
      states: List[(FeatureRoutes, Signal[FeatureState])],
      api: Option[ApiClient],
      uiPrefix: String
  )(using Owner): Signal[HtmlElement] = {
    // `collectStatic` takes its view *by name* and re-evaluates it every time the page signal emits
    // that page — including when it re-emits the page already on screen. A `lazy val` is what turns
    // that into "built on first visit, reused afterwards": the element for a singleton page exists
    // once for the life of the application, which is what keeps its scroll position and any open
    // menu alive across a navigation away and back.
    lazy val home = ErrorReporting.renderSafely(() => HomePage())
    lazy val settings = ErrorReporting.renderSafely(() => SettingsPage(Theme.choice, buildVersion))
    lazy val gallery = ErrorReporting.renderSafely(() => GalleryPage())

    val gates = featureGates(router, states, api)

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
      // Anything that is not one of the shell's own pages belongs to a feature. Which feature is
      // decided by asking each one's routes whether they can produce this page's URL, because the
      // shell may not name a feature's page type.
      .collect[Page] { page =>
        gates
          .collectFirst { case (registration, gate) if owns(registration, page, uiPrefix) => gate }
          .fold(ErrorReporting.renderSafely(() => NotFoundPage(Val(pathOf(router, page)), router)))(gate =>
            div(child <-- gate)
          )
      }
      .signal
  }

  /** One `FeatureGate` per registered feature, built once so that a navigation back to a feature does not
    * restart its download state machine.
    */
  private def featureGates(
      router: Router[Page],
      states: List[(FeatureRoutes, Signal[FeatureState])],
      api: Option[ApiClient]
  )(using Owner): List[(FeatureRoutes, Signal[HtmlElement])] = {
    val probe = api.map(client => new CapabilityProbe(client))

    states.map { (registration, state) =>
      registration -> FeatureGate(
        feature = FeatureRegistry.lazyFeature(registration.id),
        featureLabel = registration.nav.label,
        state = state,
        page = router.currentPageSignal,
        probe = probe.fold(Observer.empty[Unit])(_.observer(registration.id)),
        whatStillWorks = readyLabels(states, except = registration),
        retryInFlight = probe.fold(Val(false))(_.inFlight(registration.id)),
        retryError = probe.fold(Val(None))(_.lastError(registration.id))
      )
    }
  }

  /** Whether a page came from this feature's routes.
    *
    * Asked by trying to build the page's URL from the feature's patterns: a route that can encode the page is
    * a route that produced it. This is how the shell dispatches to the right feature without ever naming a
    * feature's page type.
    */
  private def owns(registration: FeatureRoutes, page: Page, uiPrefix: String): Boolean =
    registration.routes(uiPrefix).exists(route => route.relativeUrlForPage(page).isDefined)

  /** The other features that are currently working, by label — the "what still works" list. */
  private def readyLabels(
      states: List[(FeatureRoutes, Signal[FeatureState])],
      except: FeatureRoutes
  ): Signal[List[String]] =
    labelsWhere(states.filterNot((registration, _) => registration.id == except.id), isReady)

  private def degradedLabels(states: List[(FeatureRoutes, Signal[FeatureState])]): Signal[List[String]] =
    labelsWhere(states, isDegraded)

  private def labelsWhere(
      states: List[(FeatureRoutes, Signal[FeatureState])],
      matches: FeatureState => Boolean
  ): Signal[List[String]] =
    if states.isEmpty then Val(Nil)
    else
      Signal
        .combineSeq(states.map((registration, state) => state.map(registration -> _)))
        .map(_.toList.collect { case (registration, current) if matches(current) => registration.nav.label })

  private def isReady(state: FeatureState): Boolean =
    state match {
      case Ready => true
      case Degraded(_) | Unavailable(_, _, _) | Forbidden | NotConfigured => false
    }

  private def isDegraded(state: FeatureState): Boolean =
    state match {
      case Degraded(_) => true
      case Ready | Unavailable(_, _, _) | Forbidden | NotConfigured => false
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
