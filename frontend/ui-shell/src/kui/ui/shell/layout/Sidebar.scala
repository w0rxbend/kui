package kui.ui.shell.layout

import com.raquo.laminar.api.L.*
import com.raquo.waypoint.Router

import kui.ui.kernel.component.Icon
import kui.ui.kernel.css.KernelCss
import kui.ui.kernel.feature.Page
import kui.ui.kernel.state.FeatureState
import kui.ui.shell.nav.NavItem
import kui.ui.shell.{Messages, ShellCss}

/** The navigation down the left-hand side, and the five rendering rules of ADR-032.
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
  * `split` matches each item to its existing element by `testId` and hands that element the new value through
  * a signal, so a state change updates exactly one entry's classes and tooltip and touches nothing else.
  */
object Sidebar {

  def apply(router: Router[Page], items: Signal[List[NavItem]]): HtmlElement =
    navTag(
      cls := ShellCss.Sidebar,
      // Named, because a page can hold more than one navigation region — this one and the
      // breadcrumbs — and a screen reader listing two unnamed "navigation" landmarks tells the user
      // nothing about which is which.
      aria.label := "Main",
      ul(
        cls := ShellCss.SidebarList,
        children <-- items.split(_.testId)((_, initial, item) => entry(router, initial, item))
      )
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
