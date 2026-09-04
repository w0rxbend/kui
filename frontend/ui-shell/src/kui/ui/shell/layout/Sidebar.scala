package kui.ui.shell.layout

import com.raquo.laminar.api.L.{svg as s, *}
import com.raquo.laminar.codecs.StringAsIsCodec
import com.raquo.waypoint.Router

import kui.ui.kernel.component.Icon
import kui.ui.kernel.css.KernelCss
import kui.ui.kernel.feature.Page
import kui.ui.kernel.state.FeatureState
import kui.ui.shell.nav.NavItem
import kui.ui.shell.{Messages, ShellCss}

/** The navigation drawer down the left-hand side: the wordmark, the destinations, and the five rendering
  * rules of ADR-032.
  *
  * ## The rules, and why each one is what it is
  *
  *   - **`NotConfigured` → hidden.** This deployment has no such upstream. That is not a failure, and
  *     rendering it as one sends every operator hunting for an outage that does not exist. `Navigation`
  *     filters these out before the list ever gets here.
  *   - **`Forbidden` → shown, disabled, with a tooltip.** Not a link: a disabled link is still followable by
  *     keyboard in some browsers, and following it would produce a page the user may not see. Hidden instead
  *     when the deployment sets `kui.ui.hideForbidden`.
  *   - **`Unavailable` → dimmed and still clickable.** This is the amendment ADR-032 made to the original
  *     plan, and it is the whole reason the ADR exists. A disabled entry has nowhere to put the reason, the
  *     "since", the retry or the "what still works" — the user is left with a grey word. Clicking a dimmed
  *     entry goes to the feature's fallback panel, which has all four.
  *   - **`Degraded` → normal, with an amber dot and a tooltip carrying the reason.** The page works; the dot
  *     is a warning, not a barrier.
  *   - **`Ready` → normal.**
  *
  * ## Why the entries are `split` and not rebuilt
  *
  * `children <-- items.map(...)` would throw every entry away and build new ones each time any capability
  * changed. That loses focus — a keyboard user tabbing through the navigation when a service goes down would
  * be dropped back to the top of the page — and it makes one service's outage rewrite the whole sidebar.
  * `split` matches each item to its existing element by `testId` *and its destination*, and hands that
  * element the new value through a signal, so a state change updates exactly one entry's classes and tooltip
  * and touches nothing else. The destination is part of the key because it is one of the few things about an
  * entry that is settled once rather than bound to the signal — see `split` below.
  */
object Sidebar {

  /** @param uiPrefix
    *   where the frontend is mounted, deployment prefix included (`/ui` normally, `/kafka/ui` behind a proxy
    *   that mounts KUI under `/kafka`). The wordmark links to it, and that link is root-relative, so it
    *   cannot be left to the injected `<base href>`: a `<base>` only rewrites *relative* URLs, and a
    *   hard-coded `/ui/` would send a user behind a prefix to a path no gateway route matches.
    */
  /** @param switcher
    *   the cluster switcher, mounted between the wordmark and the destinations. Passed in rather than built
    *   here, so that a suite can render the frame without a capability store — and because from the next
    *   milestone every destination below it is scoped by it, which is why it sits above them.
    */
  def apply(
      router: Router[Page],
      items: Signal[List[NavItem]],
      uiPrefix: String,
      switcher: Option[HtmlElement] = None
  ): HtmlElement =
    navTag(
      cls := ShellCss.Sidebar,
      // Named, because a page can hold more than one navigation region — this one and the
      // breadcrumbs — and a screen reader listing two unnamed "navigation" landmarks tells the user
      // nothing about which is which.
      aria.label := "Main",
      wordmark(uiPrefix),
      switcher,
      ul(
        cls := ShellCss.SidebarList,
        // Keyed by the entry *and where it goes*, not by the entry alone.
        //
        // `entry` settles the `href` and the click handler once, from the first value it is given,
        // because whether an entry is a link at all is decided by permission and that does not change
        // while the page is open. Its destination does: every cluster-scoped entry points at
        // `/ui/clusters/<the chosen cluster>/…`, and choosing a different cluster in the switcher
        // changes it. Keyed on `testId` alone the element was reused across that change and kept the
        // old cluster's address, so after switching clusters the sidebar's Topics link still went to
        // the cluster you had just left — a navigation that lands on a page you did not ask for.
        // Including the destination in the key rebuilds exactly those entries whose address changed
        // and leaves the rest alone, which is what a state change must still not disturb.
        children <-- items.split(item => (item.testId, item.page))((_, initial, item) =>
          entry(router, initial, item)
        )
      )
    )

  /** The product name, and the mark beside it.
    *
    * The mark is drawn inline rather than loaded, for the reason `Icon` gives: an image is a second request
    * that has to arrive before the interface looks finished, and a failed one leaves a broken box in the
    * first thing the user looks at. Its tile takes its gradient from the accent tokens, so the mark follows
    * the chosen accent instead of being a fixed brand colour painted onto a themed interface.
    */
  private def wordmark(uiPrefix: String): HtmlElement =
    div(
      cls := ShellCss.Brand,
      a(
        href := s"$uiPrefix/",
        dataAttr("testid") := "brand-link",
        span(cls := ShellCss.BrandMark, aria.hidden := true, mark),
        span(cls := ShellCss.BrandName, "KUI")
      )
    )

  private val ariaHidden = s.svgAttr("aria-hidden", StringAsIsCodec, None)

  /** Three nodes and the links between them: a broker and its partitions, which is the one picture that says
    * "Kafka" without saying anything the product would have to keep true.
    */
  private def mark: SvgElement =
    s.svg(
      s.viewBox := "0 0 24 24",
      s.width := "1.75em",
      s.height := "1.75em",
      s.fill := "none",
      s.stroke := "currentColor",
      s.strokeWidth := "3",
      s.strokeLineCap := "round",
      s.strokeLineJoin := "round",
      ariaHidden := "true",
      s.path(s.d := "M6.5 12l10-6.2M6.5 12l10 6.2"),
      s.circle(s.cx := "6.5", s.cy := "12", s.r := "3.3", s.fill := "currentColor", s.stroke := "none"),
      s.circle(s.cx := "17.3", s.cy := "5.4", s.r := "3", s.fill := "currentColor", s.stroke := "none"),
      s.circle(s.cx := "17.3", s.cy := "18.6", s.r := "3", s.fill := "currentColor", s.stroke := "none")
    )

  /** One entry. `initial` decides the element's shape; `item` keeps its appearance up to date.
    *
    * The split is deliberate. Whether an entry is a link at all depends on permission, which in M0 never
    * changes while the page is open — it is decided by the session — so it is settled once, when the element
    * is built. Everything that genuinely changes second by second (dimming, the amber dot, the tooltip) is
    * bound to the signal.
    */
  private def entry(router: Router[Page], initial: NavItem, item: Signal[NavItem]): HtmlElement = {
    val state = item.map(_.state)
    val tooltipId = s"${initial.testId}-reason"

    li(
      a(
        cls := ShellCss.SidebarLink,
        dataAttr("testid") := initial.testId,
        // Which of ADR-032's five states this entry is in, as one machine-readable attribute.
        //
        // It exists for the end-to-end tests, which assert every one of those rules against a real
        // browser (E2E-002), and it is deliberately not a class name. Class names belong to the
        // visual design and change whenever the design does; this is a statement about state, and it
        // has to stay true through any restyle or the test is asserting on the wrong thing.
        dataAttr("state") <-- state.map(stateName),
        // A dimmed entry is still a link with a real address: that is what makes the fallback panel
        // reachable, bookmarkable and openable in a new tab.
        cls(ShellCss.SidebarLinkDimmed) <-- state.map(_.isDimmed),
        cls(ShellCss.SidebarLinkDisabled) <-- state.map(isForbidden),
        // `aria-current="page"` is what tells a screen reader which entry is the one you are on.
        // Colour alone says it only to people who can see it.
        aria.current <-- router.currentPageSignal.map(current =>
          if current == initial.page then "page" else ""
        ),
        cls(ShellCss.SidebarLinkCurrent) <-- router.currentPageSignal.map(_ == initial.page),
        aria.describedBy <-- reasonOf(item).map(reason => if reason.isDefined then tooltipId else ""),
        span(cls := ShellCss.SidebarLinkLabel, text <-- item.map(_.label)),
        // The amber dot only exists while the feature is degraded. It is a sibling of the label
        // rather than a background colour on it, so that it survives a theme with no colour at all.
        child.maybe <-- state.map(current =>
          Option.when(isDegraded(current))(
            span(cls := ShellCss.SidebarLinkDot, aria.hidden := true, Icon.dot)
          )
        ),
        // Always in the document, hidden rather than removed, for the same reason `Tooltip` does it:
        // `aria-describedby` has to point at an element that exists.
        span(
          idAttr := tooltipId,
          cls := KernelCss.Tooltip,
          cls := KernelCss.TooltipRight,
          role := "tooltip",
          hidden <-- reasonOf(item).map(_.isEmpty),
          text <-- reasonOf(item).map(_.getOrElse(""))
        ),
        if isForbidden(initial.state) then forbiddenModifiers else navigable(router, initial)
      )
    )
  }

  /** What makes an entry a working link: a real `href` and Waypoint's click interception. */
  private def navigable(router: Router[Page], item: NavItem): Modifier[HtmlElement] =
    Seq(
      href := router.relativeUrlForPage(item.page),
      // Waypoint's own binder: it intercepts the click and pushes history state instead of letting
      // the browser reload the whole application. The `href` above is still real, so opening the
      // link in a new tab, or copying it, works exactly as a user expects.
      router.navigateTo(item.page)
    )

  /** What makes an entry a dead one: no `href` at all, so it is neither clickable nor focusable as a link,
    * plus `aria-disabled` so a screen reader says why it is inert instead of skipping it silently.
    */
  private def forbiddenModifiers: Modifier[HtmlElement] =
    Seq(aria.disabled := true, role := "link")

  /** The tooltip sentence for an entry, or `None` when there is nothing to explain. */
  private def reasonOf(item: Signal[NavItem]): Signal[Option[String]] =
    item.map { current =>
      current.state match {
        case FeatureState.Forbidden => Some(Messages.notPermitted(current.label))
        case FeatureState.Degraded(reason) => Some(reason.message)
        case FeatureState.Unavailable(code, message, _) =>
          Some(if message.isEmpty then Messages.reason(code) else message)
        case FeatureState.Ready | FeatureState.NotConfigured => None
      }
    }

  /** The name written into `data-state`. Lower-case and hyphen-free so it reads as an attribute value rather
    * than as a Scala constructor name, and total so a state added later cannot silently fall through to
    * something misleading.
    */
  private def stateName(state: FeatureState): String =
    state match {
      case FeatureState.Ready => "ready"
      case FeatureState.Degraded(_) => "degraded"
      case FeatureState.Unavailable(_, _, _) => "unavailable"
      case FeatureState.Forbidden => "forbidden"
      case FeatureState.NotConfigured => "notconfigured"
    }

  private def isForbidden(state: FeatureState): Boolean =
    state match {
      case FeatureState.Forbidden => true
      case _ => false
    }

  private def isDegraded(state: FeatureState): Boolean =
    state match {
      case FeatureState.Degraded(_) => true
      case _ => false
    }
}
