package kui.ui.shell.page

import com.raquo.laminar.api.L.*
import com.raquo.waypoint.Router

import kui.ui.kernel.component.{EmptyState, Icon}
import kui.ui.kernel.css.KernelCss
import kui.ui.kernel.feature.Page
import kui.ui.shell.{ShellCss, ShellPage}

/** The address does not exist.
  *
  * The navigation stays exactly where it was. That is the entire difference between a 404 page and a dead
  * end: a user who mistyped a URL, or followed a link to a page that has been renamed, is one click from
  * anywhere rather than reaching for the Back button and hoping.
  *
  * The attempted address is shown because it is often the answer: a truncated paste and a stale bookmark look
  * identical until you can see what was actually asked for.
  */
object NotFoundPage {

  def apply(attempted: Signal[String], router: Router[Page]): HtmlElement =
    div(
      cls := ShellCss.Page,
      cls := ShellCss.ErrorPage,
      dataAttr("testid") := "page-not-found",
      // A level-one heading, because this *is* the page. Screen-reader users navigate by heading,
      // and a page whose main message is not a heading is a page they have to read linearly to find.
      h1("That page does not exist"),
      EmptyState(
        title = "Nothing is served at this address",
        description = Some(
          "The link may be out of date, or the address may have been mistyped. The rest of KUI is " +
            "working normally."
        ),
        icon = Some(() => Icon.search),
        // A real link styled as a button, and not a button. "Open in a new tab" and "copy link
        // address" are things people do with a way out of an error page, and a button supports
        // neither; Waypoint's binder still intercepts the ordinary click so it does not reload.
        action = Some(homeLink(router)),
        testId = Some("not-found-empty")
      ),
      p(
        cls := ShellCss.ErrorPageDetail,
        "You asked for ",
        code(dataAttr("testid") := "not-found-url", text <-- attempted),
        "."
      )
    )

  /** The way out. See the comment at its call site for why it is a link and not a button. */
  private def homeLink(router: Router[Page]): HtmlElement =
    a(
      cls := KernelCss.Button,
      cls := KernelCss.ButtonPrimary,
      cls := KernelCss.ButtonMd,
      dataAttr("testid") := "not-found-home",
      href := router.relativeUrlForPage(ShellPage.Home),
      "Go to the dashboard",
      router.navigateTo(ShellPage.Home)
    )
}
