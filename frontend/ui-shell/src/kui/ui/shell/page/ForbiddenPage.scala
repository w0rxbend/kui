package kui.ui.shell.page

import com.raquo.laminar.api.L.*
import com.raquo.waypoint.Router

import kui.ui.kernel.component.{EmptyState, Icon}
import kui.ui.kernel.css.KernelCss
import kui.ui.kernel.feature.Page
import kui.ui.shell.{ShellCss, ShellPage}

/** You are signed in, and you may not see this.
  *
  * ## The message must not depend on whether the thing exists
  *
  * This is the rule the page is built around. "You do not have permission to view topic `payroll`" and "no
  * such topic" are two different answers, and a user who is not allowed to know which topics exist can learn
  * the whole list by trying names and watching which message comes back. So the wording is identical either
  * way, and `what` is a *category* — "this topic", "the schema registry" — never an identifier.
  *
  * ## Who to ask
  *
  * A permission error a user cannot act on is a dead end. The support contact comes from the deployment's
  * configuration and is empty by default, in which case the sentence is left out rather than replaced by a
  * placeholder nobody can use.
  *
  * In M0 this page is reachable only by typing its address, because RBAC arrives in M6. It is built and
  * tested now so that M6 has nothing left to design.
  */
object ForbiddenPage {

  def apply(
      what: Signal[String],
      router: Router[Page],
      supportContact: Signal[Option[String]] = Val(None)
  ): HtmlElement =
    div(
      cls := ShellCss.Page,
      cls := ShellCss.ErrorPage,
      dataAttr("testid") := "page-forbidden",
      h1("You do not have permission"),
      EmptyState(
        title = "This is not available to your account",
        description = Some(
          "Your account does not have permission to view this. If you think it should, ask whoever " +
            "administers KUI for your organisation."
        ),
        icon = Some(() => Icon.info),
        // A real link styled as a button, and not a button. "Open in a new tab" and "copy link
        // address" are things people do with a way out of an error page, and a button supports
        // neither; Waypoint's binder still intercepts the ordinary click so it does not reload.
        action = Some(homeLink(router)),
        testId = Some("forbidden-empty")
      ),
      p(
        cls := ShellCss.ErrorPageDetail,
        dataAttr("testid") := "forbidden-subject",
        // Deliberately the same sentence whatever `what` is, and whether or not it exists.
        text <-- what.map(subject => s"You do not have permission to view $subject.")
      ),
      child.maybe <-- supportContact.map(
        _.filter(_.nonEmpty).map(contact =>
          p(cls := ShellCss.ErrorPageDetail, dataAttr("testid") := "forbidden-contact", s"Contact: $contact")
        )
      )
    )

  /** The way out. See the comment at its call site for why it is a link and not a button. */
  private def homeLink(router: Router[Page]): HtmlElement =
    a(
      cls := KernelCss.Button,
      cls := KernelCss.ButtonPrimary,
      cls := KernelCss.ButtonMd,
      dataAttr("testid") := "forbidden-home",
      href := router.relativeUrlForPage(ShellPage.Home),
      "Go to the dashboard",
      router.navigateTo(ShellPage.Home)
    )
}
