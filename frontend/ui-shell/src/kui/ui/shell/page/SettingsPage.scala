package kui.ui.shell.page

import com.raquo.laminar.api.L.*

import kui.ui.kernel.component.{Card, Select}
import kui.ui.kernel.theme.ThemeChoice
import kui.ui.shell.ShellCss
import kui.ui.shell.layout.Header

/** Theme and build information. The roadmap's "settings stub", and deliberately nothing more.
  *
  * @param buildVersion
  *   which build this is. `unknown` when nothing answered — see `Header`.
  */
object SettingsPage {

  def apply(theme: Var[ThemeChoice], buildVersion: Signal[String]): HtmlElement =
    div(
      cls := ShellCss.Page,
      dataAttr("testid") := "page-settings",
      h1("Settings"),
      Card(
        header = Some(h2("Appearance")),
        body = div(
          cls := ShellCss.SettingsGroup,
          Select[ThemeChoice](
            options = Val(ThemeChoice.values.toList.map(choice => choice -> Header.describe(choice))),
            // `Select` works in options rather than in `Option`s of the domain type, and the theme is
            // never unset, so the two are bridged here rather than by making `Theme.choice` optional.
            selected = theme.zoom[Option[ThemeChoice]](Some(_))((_, chosen) =>
              chosen.getOrElse(ThemeChoice.Auto)
            )(using unsafeWindowOwner),
            label = "Theme",
            testId = Some("settings-theme")
          ),
          p(
            "\"Following the system\" changes with your operating system's own light and dark ",
            "setting, including while KUI is open."
          )
        )
      ),
      Card(
        header = Some(h2("About")),
        body = dl(
          dt("Build"),
          dd(dataAttr("testid") := "settings-build", text <-- buildVersion)
        )
      )
    )
}
