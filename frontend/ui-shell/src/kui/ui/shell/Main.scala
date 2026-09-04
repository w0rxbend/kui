package kui.ui.shell

import com.raquo.laminar.api.L.*
import com.raquo.waypoint.{Router, SplitRender}
import io.circe.Json
import org.scalajs.dom

import kui.gateway.contract.dto.{AuthMeResponse, PermissionDto}
import kui.gateway.contract.{AuthEndpoints, CapabilityEndpoints}
import kui.identity.contract.dto.AuthSettingsDto
import kui.kernel.{ClusterId, RoleName, UserName}
import kui.security.rbac.{Action, ClusterPermission, ClusterScope, RbacPolicy, Resource, ResourcePattern}
import kui.security.{Principal, PrincipalKind}
import kui.ui.kernel.api.{ApiClient, ApiError, Bootstrap}
import kui.ui.kernel.feature.{FeatureRegistry, FeatureRoutes, Page}
import kui.ui.kernel.prefs.{RefreshRate, Timezone}
import kui.ui.kernel.state.FeatureState.*
import kui.ui.kernel.state.{Auth, AuthInfo, AuthState, CapabilityStore, CurrentCluster, FeatureState}
import kui.ui.kernel.theme.{Accent, Density, Theme}
import kui.ui.shell.feature.{CapabilityBanner, FeatureGate}
import kui.ui.shell.layout.{Header, Layout, Sidebar}
import kui.ui.shell.nav.{ClusterEntry, ClusterSwitcher, Navigation}
import kui.ui.shell.page.{
  ForbiddenPage,
  GalleryPage,
  GatewayUnreachable,
  HomePage,
  LoginPage,
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
    // The accent seed and the density switch are stored preferences like the theme, and like the theme
    // they are written onto `<html>` before the first paint: an interface that renders in the default
    // accent and then corrects itself is a flash the user did not ask for. Without these two calls the
    // tokens for the other accents and for compact rows are declared but never selected.
    Accent.install()
    Density.install()
    ErrorReporting.install()

    ShellHealth.install()

    // Based at the deployment root, not at `apiBase`: every contract endpoint already carries the full
    // `/api/v1/...` path. See `Bootstrap.gatewayRoot`.
    val api = ApiClient.make(Bootstrap.gatewayRoot(bootstrap), AuthState.current)

    // Before anything mutating can work. See `startSession`.
    startSession(api, AuthState.current)(using unsafeWindowOwner)

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

  /** Establishes the browser's session, and re-establishes it after the server says it has lapsed.
    *
    * ## Why start-up has to do this
    *
    * Every mutating request KUI sends must carry the session's CSRF token in `X-Csrf-Token` (ADR-019). The
    * `ApiClient` adds that header by itself, but only if it has a token to add, and the only place a token
    * ever comes from is the body of `GET /api/v1/auth/me`. Nothing else calls that endpoint. Without this
    * call the token stays `None` for the life of the page, the header is never sent, and the gateway refuses
    * every non-`GET` with `403 KUI-FORBIDDEN` and "X-Csrf-Token is missing" — in M0 that is the "Retry now"
    * button in the degraded-feature panel, which therefore never works.
    *
    * ## Why it also listens for expiry
    *
    * A session has an idle timeout. When it lapses, the next request comes back `401`, `ApiClient` reports
    * that to [[Auth.markExpired]], and the token is cleared. Re-fetching `/auth/me` at that point is what
    * gets the browser a working session again without the user reloading the page. `Auth.expired` emits at
    * most once per expiry, so a page with five requests in flight makes one recovery attempt, not five.
    *
    * The refresh streams are subscribed on `unsafeWindowOwner` — the page's own lifetime — because the
    * session outlives every element on screen.
    *
    * @param auth
    *   the session to fill in. A parameter rather than `AuthState.current` so that a test can watch one
    *   session without the application's singleton leaking into the next test.
    */
  def startSession(api: ApiClient, auth: Auth)(using Owner): Unit = {
    // A thunk, and not a stream: `Auth.refresh` calls it once per attempt, and a stream built up front
    // would be one request shared by every attempt rather than a fresh one each time.
    val fetch: () => EventStream[Either[ApiError, AuthInfo]] =
      () => api.call(AuthEndpoints.me, ()).map(_.map(toAuthInfo))

    auth.refresh(fetch).foreach(_ => ()): Unit

    // `flatMapSwitch` and not `flatMapMerge`: if a second expiry somehow arrives while a refresh is in
    // flight, the newer attempt is the one whose answer should win.
    auth.expired.flatMapSwitch(_ => auth.refresh(fetch)).foreach(_ => ()): Unit
  }

  /** Translates the gateway's wire shape into the kernel's.
    *
    * The kernel sits underneath every service contract and may not name one (ADR-041), so the shell — which
    * is allowed to see both — is where the two meet. An unrecognised `kind` becomes `Anonymous` rather than a
    * failure: a gateway that grows a new principal kind should not stop an older browser from starting.
    */
  private[shell] def toAuthInfo(response: AuthMeResponse): AuthInfo =
    AuthInfo(
      principal = Some(
        Principal(
          name = UserName.unsafe(response.principal.name),
          roles = response.principal.roles.map(RoleName.unsafe).toSet,
          kind = PrincipalKind.fromWire(response.principal.kind).getOrElse(PrincipalKind.Anonymous)
        )
      ),
      // An empty token is no token. Sending `X-Csrf-Token: ` would be rejected exactly as a missing
      // header is, but with a far more confusing message in the gateway's log.
      csrfToken = Option(response.csrfToken).filter(_.nonEmpty),
      authType = response.authType,
      permissions = response.permissions.flatMap(toPermission)
    )

  /** One grant, from the wire into the kernel's shape.
    *
    * `None` — dropped — for a grant this browser cannot make sense of: an unknown resource name, an
    * unparseable pattern, a resource with no recognised actions left after filtering. That is deliberately
    * the *safe* direction to fail in, because a dropped grant hides a control and a mistakenly kept one
    * offers a control the server will refuse. It also means a gateway that grows a twelfth resource does not
    * stop an older browser from starting.
    */
  private[shell] def toPermission(dto: PermissionDto): Option[ClusterPermission] =
    Resource.fromWire(dto.resource).flatMap { resource =>
      val actions = dto.actions.flatMap(Action.fromWire(resource, _)).toSet
      val pattern = dto.value.map(ResourcePattern.compile)

      // A `value` that will not compile is a pattern this browser cannot evaluate, so the honest answer is
      // to drop the grant rather than to treat it as matching everything or as matching nothing.
      if actions.isEmpty || pattern.exists(_.isLeft) then None
      else
        Some(
          ClusterPermission(
            clusters = toScope(dto.clusters),
            // Closed again on the way in. The server already expanded these, so this changes nothing
            // today; it means a server that one day forgets to cannot make the browser more permissive
            // than the server itself, which is the direction that matters.
            permission = RbacPolicy.permission(resource, pattern.flatMap(_.toOption), actions)
          )
        )
    }

  private def toScope(clusters: List[String]): ClusterScope =
    if clusters.contains(ClusterScope.EveryWire) then ClusterScope.Every
    else ClusterScope.Named(clusters.flatMap(ClusterId.from(_).toOption).toSet)

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

    // A cluster named in the URL wins over the stored selection, and is applied before anything reads it: a
    // pasted link has to show the recipient what the sender saw.
    ShellRouter
      .clusterInUrl(initialUrl, uiPrefix)
      .foreach(cluster => CurrentCluster.selected.set(Some(cluster)))

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

    // Which sign-in this deployment uses. Asked once, at start-up, from the gateway's own configuration
    // rather than from the identity service, so that a login screen can still be drawn during exactly the
    // outage an operator needs to see. `None` until the answer arrives, and `Disabled` if it never does:
    // a deployment that has configured no authentication must never be shown a locked door because one
    // request was slow, and that is the direction this failure has to fall.
    val authSettings: Var[Option[AuthSettingsDto]] = Var(None)
    api.foreach { client =>
      val _ = client
        .call(AuthEndpoints.settings, ())
        .foreach {
          case Right(settings) => authSettings.set(Some(settings))
          case Left(_) => authSettings.set(Some(AuthSettingsDto(AuthDisabled, None, rbacEnabled = false)))
        }
    }

    Layout(
      sidebar = Sidebar(
        router = router,
        items = Navigation.items(states, CurrentCluster.signal, hideForbidden = false),
        uiPrefix = uiPrefix,
        switcher = Some(
          ClusterSwitcher(
            entries = CapabilityStore.states.combineWith(CapabilityStore.names).map(ClusterEntry.of),
            current = CurrentCluster.selected,
            open = cluster => router.pushState(clusterPage(features, cluster))
          )
        )
      ),
      header = Header(
        buildVersion.signal,
        Theme.choice,
        AuthState.principal.signal,
        signOut(api)
      ),
      content = content(router, buildVersion.signal, states, api, uiPrefix),
      // Two full-screen states now, and their order is the whole of the rule. A gateway that is not
      // answering wins, because a sign-in form that cannot reach a server is a form that can only fail,
      // and the unreachable screen is the one that says why and retries. Only when KUI *is* reachable is
      // the sign-in question asked at all.
      fullScreen = ShellHealth.connectivity
        .combineWith(authSettings.signal, AuthState.principal.signal)
        .map((connectivity, settings, principal) =>
          connectivity match {
            case ShellConnectivity.Lost(_, _, _) => Some(unreachable)
            case ShellConnectivity.Connected(_) =>
              Option.when(mustSignIn(settings, principal))(
                LoginPage(settings.getOrElse(AuthSettingsDto(AuthDisabled, None, false)), api, signedIn)
              )
          }
        )
    )
  }

  /** The wire value `AuthType.Disabled` serialises to, and therefore the one string that means "this
    * deployment asks nobody to sign in". It is spelled out here rather than imported from
    * `libs/config`, which is a JVM module the browser cannot see.
    */
  private[shell] val AuthDisabled: String = "disabled"

  /** Whether to put the sign-in screen in front of everything.
    *
    * Both halves have to be true, and each guards against a different, serious failure.
    *
    *   - The **settings must have arrived and must not say `disabled`.** While the answer is still in
    *     flight, or if it never comes, this is `false`: a deployment with no authentication configured —
    *     the default, and what every demonstration environment runs — must never meet a login screen,
    *     because that is the product's front door and a locked door there is worse than any other bug on
    *     this screen.
    *   - The **principal must be anonymous.** A signed-in user reloading the page has a session cookie
    *     and gets their identity back from `/auth/me`; asking them to sign in again would be a loop.
    *
    * `None` for the principal means `/auth/me` has not answered yet, which is also not a reason to
    * demand a sign-in.
    */
  private[shell] def mustSignIn(
      settings: Option[AuthSettingsDto],
      principal: Option[Principal]
  ): Boolean =
    settings.exists(_.authType != AuthDisabled) &&
      principal.exists(_.kind == PrincipalKind.Anonymous)

  /** What happens the moment the server has issued a session.
    *
    * A reload, and not a re-fetch of `/auth/me`. Everything the shell had built up to that point — the
    * capability picture, the cluster list, every feature's own state — was fetched as the anonymous
    * principal, and a reload is the one way to be certain none of it survives into a session that may be
    * allowed to see less, or more.
    */
  private[shell] def signedIn: Observer[Unit] =
    Observer[Unit](_ => dom.window.location.reload())

  /** Ending the session, from the account menu.
    *
    * Three steps, in this order and for a reason each. `POST /api/v1/auth/logout` deletes the session on the
    * server, which is the only step that actually signs anybody out — everything else is housekeeping.
    * `markExpired` then empties the principal, the CSRF token and every permission in the browser, so no
    * write control survives the moment of signing out even for the length of a reload. Reloading last is what
    * puts the user back at the start of whatever the deployment's sign-in flow is, without this file needing
    * to know what that flow is.
    *
    * A failed request still clears the browser's state and still reloads. A person who has pressed "Sign out"
    * on a shared machine must not be left signed in because a request did not arrive; the server-side session
    * then expires on its own timeout, which is worse than an immediate deletion and much better than a screen
    * that says nothing happened.
    *
    * With no `ApiClient` — which is how a suite mounts the frame — it clears the browser's state and stops
    * there. Nothing reloads a page that is under test.
    */
  private[shell] def signOut(api: Option[ApiClient])(using Owner): Observer[Unit] =
    Observer[Unit] { _ =>
      api match {
        case None => AuthState.current.markExpired()
        case Some(client) =>
          val _ = client
            .call(AuthEndpoints.logout, ())
            .foreach { _ =>
              AuthState.current.markExpired()
              dom.window.location.reload()
            }
      }
    }

  /** The tag the clusters feature stores its brokers page under in `history.state`.
    *
    * A string rather than a type, and that is the point: the shell may not name a feature's page classes,
    * because a static reference is what pulls the whole feature into the bundle every user downloads. What it
    * *can* do is ask the feature's own codec to build one, which is the same mechanism the Back button
    * already uses to restore a feature page before that feature has been downloaded.
    *
    * If the feature does not recognise the tag — an older or newer build — the switcher navigates to the
    * feature's landing page instead. It degrades to "the cluster list" rather than to nothing.
    */
  private val BrokersPageTag = "clusters.brokers"

  /** Where choosing a cluster in the switcher goes. */
  private def clusterPage(features: List[FeatureRoutes], cluster: kui.kernel.ClusterId): Page =
    features.view
      .flatMap(registration =>
        registration.decodePage(
          BrokersPageTag,
          Json.obj("clusterId" -> Json.fromString(cluster.value)).hcursor
        )
      )
      .headOption
      .orElse(features.headOption.map(_.landing))
      .getOrElse(ShellPage.Home)

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
    lazy val home = ErrorReporting.renderSafely(() => HomePage(uiPrefix, api, Timezone.choice.signal))
    // The zone list is read once, when the page is first built, rather than on every render: it is
    // several hundred entries and the set does not change while a tab is open.
    lazy val settings = ErrorReporting.renderSafely(() =>
      SettingsPage(
        theme = Theme.choice,
        accent = Accent.choice,
        density = Density.choice,
        timezone = Timezone.choice,
        zones = Timezone.available(),
        refreshRate = RefreshRate.choice,
        buildVersion = buildVersion
      )
    )
    lazy val gallery = ErrorReporting.renderSafely(() => GalleryPage())

    val gates = featureGates(router, states, api, uiPrefix)

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
      api: Option[ApiClient],
      uiPrefix: String
  )(using Owner): List[(FeatureRoutes, Signal[HtmlElement])] = {
    val probe = api.map(client => new CapabilityProbe(client))

    states.map { (registration, state) =>
      registration -> FeatureGate(
        feature = FeatureRegistry.lazyFeature(registration.id),
        featureLabel = registration.nav.label,
        state = state,
        page = ownPagesOf(registration, router, uiPrefix),
        probe = probe.fold(Observer.empty[Unit])(_.observer(registration.id)),
        whatStillWorks = readyLabels(states, except = registration),
        retryInFlight = probe.fold(Val(false))(_.inFlight(registration.id)),
        retryError = probe.fold(Val(None))(_.lastError(registration.id))
      )
    }
  }

  /** The pages one feature is allowed to be asked to draw.
    *
    * Each gate used to be handed `router.currentPageSignal` directly — every page in the application,
    * including pages belonging to other features. That is a loaded gun for two reasons.
    *
    * The first is that it asks a feature to render a page it has never heard of. What a feature does with one
    * is undefined; the messages feature drawing a topics page is not a screen anybody designed, and it is
    * exactly the shape of the fault seen in the browser — an address ending in `/topics` with a message
    * browser on the screen.
    *
    * The second is timing. Both the signal that swaps which gate is on screen and the signal inside a gate
    * are derived from the same page signal, so during a navigation between two features the outgoing gate can
    * be handed the incoming page before it is taken off the screen. Keeping the wrong page out of it entirely
    * removes the whole class of race rather than trying to order two subscriptions.
    *
    * So each gate sees only the pages its own routes can produce, and holds the last such page while some
    * other feature is on screen. Holding rather than blanking is deliberate: the feature's element is built
    * once and kept, and handing it an empty page on the way out would make it tear down its content a moment
    * before it is unmounted anyway.
    */
  private[shell] def ownPagesOf(
      registration: FeatureRoutes,
      router: Router[Page],
      uiPrefix: String
  ): Signal[Page] =
    router.currentPageSignal
      .scanLeft(identity[Page])((previous, next) =>
        if owns(registration, next, uiPrefix) then next else previous
      )
      .distinct

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
