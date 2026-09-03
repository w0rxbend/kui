package kui.ui.shell.page

import com.raquo.laminar.api.L.*

import kui.ui.kernel.component.Card
import kui.ui.shell.ShellCss

/** The dashboard.
  *
  * A placeholder in M0 and honest about it. The cluster summary cards that belong here arrive with the
  * clusters feature (UI-012 and M1); saying so is better than an empty page that looks broken.
  */
object HomePage {

  def apply(): HtmlElement =
    div(
      cls := ShellCss.Page,
      dataAttr("testid") := "page-home",
      h1("KUI"),
      Card(
        header = Some(h2("Nothing to show yet")),
        body = div(
          p(
            "This is the KUI shell. Cluster overviews appear here once the clusters feature is ",
            "installed."
          )
        )
      )
    )
}
