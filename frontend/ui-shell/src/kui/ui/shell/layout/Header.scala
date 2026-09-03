package kui.ui.shell.layout

import com.raquo.laminar.api.L.*

import kui.ui.kernel.component.{Icon, Tooltip}
import kui.ui.kernel.css.KernelCss
import kui.ui.kernel.theme.ThemeChoice
import kui.ui.shell.ShellCss

/** The bar across the top: the product name, a place for the cluster switcher, the theme switch and the build
  * version.
  *
  * @param uiPrefix
  *   where the frontend is mounted, deployment prefix included (`/ui` normally, `/kafka/ui` behind a proxy
  *   that mounts KUI under `/kafka`). The brand link is root-relative, so it cannot be left to the injected
  *   `<base href>`: a `<base>` only rewrites *relative* URLs, and a hard-coded `/ui/` would send a user
  *   behind a prefix to a path no gateway route matches.
  * @param buildVersion
  *   which build the browser is running. A `Signal` because it is filled in from `GET /api/v1/info` once that
  *   endpoint exists; until then it is what the gateway injected into the bootstrap block, and if that failed
  *   too it says `unknown` — which is the point of the degraded behaviour: a header that cannot name the
  *   build is not a reason to fail to start.
  */
object Header {

  def apply(uiPrefix: String, buildVersion: Signal[String], theme: Var[ThemeChoice]): HtmlElement =
    headerTag(
      cls := ShellCss.Header,
      div(cls := ShellCss.HeaderBrand, a(href := s"$uiPrefix/", dataAttr("testid") := "brand-link", "KUI")),
      // The cluster switcher lands here in M1. The empty element is deliberate: it holds the space,
      // so adding the switcher does not move the theme button to a different place on screen.
      div(cls := ShellCss.ClusterSlot, dataAttr("testid") := "cluster-slot"),
      div(cls := ShellCss.HeaderSpacer),
      div(
        cls := ShellCss.HeaderActions,
        themeSwitch(theme),
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
      case ThemeChoice.Auto => Icon.dot
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
