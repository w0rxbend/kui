package kui.ui.shell.layout

import com.raquo.laminar.api.L.*
import com.raquo.waypoint.Router

import kui.ui.kernel.feature.Page
import kui.ui.shell.{ShellCss, ShellPage}

/** One entry in the navigation.
  *
  * @param page
  *   where it goes. A `Page` and not a URL, so that the router builds the address — which means changing a
  *   route pattern cannot leave a stale link behind.
  */
final case class NavItem(label: String, page: Page, testId: String)

/** The navigation down the left-hand side.
  *
  * In M0 it holds only the shell's own entries. UI-010 fills it with the features, gated on their
  * `FeatureState`: hidden when `NotConfigured`, dimmed but clickable when `Unavailable`, and so on. The shape
  * is here now so that UI-010 adds a list rather than a layout.
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
        children <-- items.map(_.map(entry(router, _)))
      )
    )

  private def entry(router: Router[Page], item: NavItem): HtmlElement =
    li(
      a(
        cls := ShellCss.SidebarLink,
        dataAttr("testid") := item.testId,
        href := router.relativeUrlForPage(item.page),
        // `aria-current="page"` is what tells a screen reader which entry is the one you are on.
        // Colour alone says it only to people who can see it.
        aria.current <-- router.currentPageSignal.map(current => if current == item.page then "page" else ""),
        cls(ShellCss.SidebarLinkCurrent) <-- router.currentPageSignal.map(_ == item.page),
        item.label,
        // Waypoint's own binder: it intercepts the click and pushes history state instead of letting
        // the browser reload the whole application. The `href` above is still real, so opening the
        // link in a new tab, or copying it, works exactly as a user expects.
        router.navigateTo(item.page)
      )
    )

  /** What the sidebar holds in M0. */
  val shellItems: List[NavItem] = List(
    NavItem("Dashboard", ShellPage.Home, "nav-home"),
    NavItem("Components", ShellPage.Gallery, "nav-gallery"),
    NavItem("Settings", ShellPage.Settings, "nav-settings")
  )
}
