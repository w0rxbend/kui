package kui.ui.shell.page

import com.raquo.laminar.api.L.*
import com.raquo.waypoint.Router

import kui.ui.kernel.feature.Page
import kui.ui.shell.{ShellCss, ShellPage}

/** What a user sees when the address does not exist.
  *
  * Created by UI-009 so the route has something to render; designed by UI-011.
  */
object NotFoundPage {

  def apply(attempted: Signal[String], router: Router[Page]): HtmlElement =
    div(
      cls := ShellCss.Page,
      dataAttr("testid") := "page-not-found",
      h1("That page does not exist"),
      p(code(dataAttr("testid") := "not-found-url", text <-- attempted)),
      p(a(href := router.relativeUrlForPage(ShellPage.Home), "Go to the dashboard"))
    )
}
