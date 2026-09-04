package kui.ui.shell.layout

import com.raquo.laminar.api.L.*

import kui.security.Principal
import kui.ui.kernel.component.{Icon, Tooltip}
import kui.ui.kernel.css.KernelCss
import kui.ui.kernel.theme.ThemeChoice
import kui.ui.shell.ShellCss

/** The bar across the top of the content: the theme switch, the account menu and the build version.
  *
  * The product name is not here. In the design the wordmark belongs to the navigation drawer, which runs the
  * full height of the window, and the top bar belongs to the content beside it — so `Sidebar` carries the
  * brand and this bar carries only the controls that act on what is on screen.
  *
  * The cluster switcher is not here either. It scopes every destination in the drawer, and a control that
  * scopes the things under it sits above them, so `Sidebar` mounts it. This bar used to hold an empty
  * placeholder reserving space for it; the placeholder outlived the decision and was removed.
  *
  * @param buildVersion
  *   which build the browser is running. A `Signal` because it is filled in from `GET /api/v1/info` once that
  *   endpoint exists; until then it is what the gateway injected into the bootstrap block, and if that failed
  *   too it says `unknown` — which is the point of the degraded behaviour: a header that cannot name the
  *   build is not a reason to fail to start.
  */
object Header {

  /** @param principal
    *   who is signed in, for the account menu. It is a `Signal` and not a value because it arrives after the
    *   page does — `/auth/me` answers a moment into start-up — and because it goes away again when a session
    *   expires, which must take the sign-out control with it
    * @param signOut
    *   what ending the session does. The header does not know: it owns no API client and decides nothing
    *   about what happens after
    */
  def apply(
      buildVersion: Signal[String],
      theme: Var[ThemeChoice],
      principal: Signal[Option[Principal]] = Val(None),
      signOut: Observer[Unit] = Observer.empty
  ): HtmlElement =
    headerTag(
      cls := ShellCss.Header,
      div(cls := ShellCss.HeaderSpacer),
      div(
        cls := ShellCss.HeaderActions,
        themeSwitch(theme),
        UserMenu(principal, signOut),
        span(cls := ShellCss.HeaderVersion, dataAttr("testid") := "build-version", text <-- buildVersion)
      )
    )

  /** Cycles the three theme states.
    *
    * A single button rather than three radio buttons: the choice is a preference somebody sets once, and a
    * control that takes one click and no reading is worth more here than one that shows every option. The
    * `aria-label` names the *next* state, so a screen-reader user knows what pressing it does rather than
    * only what it currently is.
    */
  private def themeSwitch(theme: Var[ThemeChoice]): HtmlElement = {
    val nextLabel = theme.signal.map(current => s"Switch theme (currently ${describe(current)})")

    Tooltip(
      trigger = button(
        tpe := "button",
        cls := KernelCss.Button,
        cls := KernelCss.ButtonGhost,
        cls := KernelCss.ButtonMd,
        dataAttr("testid") := "theme-switch",
        aria.label <-- nextLabel,
        child <-- theme.signal.map(icon),
        onClick.mapTo(()) --> Observer[Unit](_ => theme.update(next))
      ),
      content = nextLabel,
      testId = Some("theme-switch-tooltip")
    )
  }

  private def next(current: ThemeChoice): ThemeChoice =
    current match {
      case ThemeChoice.Auto => ThemeChoice.Light
      case ThemeChoice.Light => ThemeChoice.Dark
      case ThemeChoice.Dark => ThemeChoice.Auto
    }

  private def icon(current: ThemeChoice): SvgElement =
    current match {
      case ThemeChoice.Auto => Icon.themeAuto
      case ThemeChoice.Light => Icon.sun
      case ThemeChoice.Dark => Icon.moon
    }

  def describe(current: ThemeChoice): String =
    current match {
      case ThemeChoice.Auto => "following the system"
      case ThemeChoice.Light => "light"
      case ThemeChoice.Dark => "dark"
    }
}
