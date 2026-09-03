package kui.ui.shell.page

import com.raquo.laminar.api.L.*

import kui.ui.kernel.component.{Card, SearchableSelect, Select}
import kui.ui.kernel.prefs.RefreshRate
import kui.ui.kernel.theme.{AccentChoice, DensityChoice, ThemeChoice}
import kui.ui.shell.layout.Header
import kui.ui.shell.{Messages, ShellCss}

/** The four preferences an operator sets once, and the build they are looking at.
  *
  * ## Why every preference is a parameter
  *
  * The preference objects in the kernel are singletons backed by `localStorage`, which is right for the
  * application and wrong for a test: a suite that drove them would share state with the next suite and would
  * need a browser storage that works. So the page is handed the `Var`s, and the shell is the one place that
  * hands it the real ones. That is what makes it possible to assert "changing this control writes to this
  * preference and to nothing else".
  *
  * ## Why this page reads nothing from any service
  *
  * It is one of the two screens that has to keep working when everything behind KUI is down — literally a
  * milestone exit criterion, and asserted end to end. Every value on it is either a browser preference or the
  * build string the shell already had. Adding a server call here would take the page away at exactly the
  * moment somebody is on it trying to work out what happened.
  */
object SettingsPage {

  def apply(
      theme: Var[ThemeChoice],
      accent: Var[AccentChoice],
      density: Var[DensityChoice],
      timezone: Var[String],
      zones: List[(String, String)],
      refreshRate: Var[RefreshRate],
      buildVersion: Signal[String]
  ): HtmlElement =
    div(
      cls := ShellCss.Page,
      dataAttr("testid") := "page-settings",
      h1("Settings"),
      appearance(theme, accent, density),
      time(timezone, zones),
      data(refreshRate),
      about(buildVersion)
    )

  private def appearance(
      theme: Var[ThemeChoice],
      accent: Var[AccentChoice],
      density: Var[DensityChoice]
  ): HtmlElement =
    Card(
      header = Some(h2("Appearance")),
      body = div(
        cls := ShellCss.SettingsGroup,
        // `Select` deals in options rather than in `Option`s of the domain type, and none of these
        // three preferences is ever unset, so the two are bridged here rather than by making every
        // preference optional for the benefit of one control.
        choice[ThemeChoice](
          theme,
          ThemeChoice.values.toList.map(v => v -> Header.describe(v)),
          "Theme",
          "settings-theme"
        ),
        p(Messages.themeHelp),
        choice[AccentChoice](
          accent,
          AccentChoice.values.toList.map(v => v -> describeAccent(v)),
          "Accent",
          "settings-accent"
        ),
        choice[DensityChoice](
          density,
          DensityChoice.values.toList.map(v => v -> describeDensity(v)),
          "Table density",
          "settings-density"
        ),
        p(Messages.densityHelp)
      )
    )

  private def time(timezone: Var[String], zones: List[(String, String)]): HtmlElement =
    Card(
      header = Some(h2("Time")),
      body = div(
        cls := ShellCss.SettingsGroup,
        // Searchable, not a dropdown: there are several hundred zones and scrolling to one is not a
        // thing to ask of anybody.
        SearchableSelect[String](
          options = Val(zones),
          selected = timezone,
          label = "Timezone",
          testId = Some("settings-timezone")
        ),
        p(Messages.timezoneHelp)
      )
    )

  private def data(refreshRate: Var[RefreshRate]): HtmlElement =
    Card(
      header = Some(h2("Data")),
      body = div(
        cls := ShellCss.SettingsGroup,
        choice[RefreshRate](
          refreshRate,
          RefreshRate.values.toList.map(rate => rate -> rate.label),
          "Refresh rate",
          "settings-refresh-rate"
        ),
        p(Messages.refreshRateHelp)
      )
    )

  private def about(buildVersion: Signal[String]): HtmlElement =
    Card(
      header = Some(h2("About")),
      body = dl(
        dt("Build"),
        dd(dataAttr("testid") := "settings-build", text <-- buildVersion)
      )
    )

  /** One never-empty preference as a `Select`.
    *
    * The zoom is where "never unset" is enforced: the control is allowed to report `None`, and this reads it
    * as "keep what you had" rather than clearing the preference. A settings page that could put a preference
    * into a state the rest of the application does not handle is a settings page that eventually does.
    */
  private def choice[A](
      preference: Var[A],
      options: List[(A, String)],
      label: String,
      testId: String
  ): HtmlElement =
    Select[A](
      options = Val(options),
      selected = preference.zoom[Option[A]](Some(_))((current, chosen) => chosen.getOrElse(current))(using
        unsafeWindowOwner
      ),
      label = label,
      testId = Some(testId)
    )

  private def describeAccent(accent: AccentChoice): String =
    accent match {
      case AccentChoice.Blue => "Blue"
      case AccentChoice.Teal => "Teal"
      case AccentChoice.Green => "Green"
      case AccentChoice.Amber => "Amber"
    }

  private def describeDensity(density: DensityChoice): String =
    density match {
      case DensityChoice.Comfortable => "Comfortable"
      case DensityChoice.Compact => "Compact"
    }
}
