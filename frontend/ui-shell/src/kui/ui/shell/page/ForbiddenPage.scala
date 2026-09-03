package kui.ui.shell.page

import com.raquo.laminar.api.L.*
import com.raquo.waypoint.Router

import kui.ui.kernel.feature.Page
import kui.ui.shell.{ShellCss, ShellPage}

/** What a user sees when they may not view something.
  *
  * Created by UI-009 so the route has something to render; designed by UI-011.
  */
object ForbiddenPage {

  def apply(what: Signal[String], router: Router[Page]): HtmlElement =
    div(
      cls := ShellCss.Page,
      dataAttr("testid") := "page-forbidden",
      h1("You do not have permission"),
      p(text <-- what.map(subject => s"You do not have permission to view $subject.")),
      p(a(href := router.relativeUrlForPage(ShellPage.Home), "Go to the dashboard"))
    )
}
