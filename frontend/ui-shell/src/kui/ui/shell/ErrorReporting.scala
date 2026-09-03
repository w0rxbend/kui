package kui.ui.shell

import scala.util.{Failure, Success, Try}

import com.raquo.airstream.core.AirstreamError
import com.raquo.laminar.api.L.*
import org.scalajs.dom

import kui.ui.kernel.component.{Card, Tone}
import kui.ui.kernel.state.{Notification, NotificationBus}

/** What happens when something in the frontend throws.
  *
  * There is no such thing as an error boundary in Laminar, and it needs none for the usual reason a React
  * application does: there is no render pass to fail. What there is instead are two ways for an exception to
  * escape and take the interface with it, and each needs its own answer.
  *
  * The first is an exception inside an Airstream callback. Airstream does not let it propagate to the caller
  * — the caller is usually the browser's event loop — so it goes to a global handler, and the default handler
  * rethrows into an empty stack where nobody sees it. [[install]] replaces that with one that tells the user
  * and writes the detail to the console.
  *
  * The second is an exception while *building* a page's element. That one does propagate, and it happens
  * before anything is mounted, so it leaves the content area empty: the shell is on screen, the navigation
  * works, and the middle of the window is blank with no explanation. [[renderSafely]] wraps every page's
  * construction so that a page which throws shows a panel saying so.
  */
object ErrorReporting {

  /** Starts reporting unhandled Airstream errors. Called once, during start-up. */
  def install(): Unit =
    AirstreamError.registerUnhandledErrorCallback { error =>
      dom.console.error("kui: unhandled error", error.toString)
      NotificationBus.push(
        Notification(
          tone = Tone.Danger,
          title = "Something went wrong",
          message = Some(describe(error)),
          // One key for every unhandled error, so that a stream failing on a timer produces one
          // toast rather than a new one every second.
          dedupKey = Some("unhandled-error")
        )
      )
    }

  /** Builds a page, or a panel explaining why it could not be built.
    *
    * Wrapping the *construction* is what matters: an element that was built and mounted successfully and then
    * throws inside a callback is the first case above, and the toast covers it.
    */
  def renderSafely(build: () => HtmlElement): HtmlElement =
    Try(build()) match {
      case Success(element) => element
      case Failure(problem) =>
        dom.console.error("kui: a page failed to render", problem.toString)
        failedPanel(describe(problem))
    }

  private def failedPanel(detail: String): HtmlElement =
    div(
      cls := ShellCss.PageError,
      Card(
        header = Some(h1("This page could not be shown")),
        body = div(
          p(
            "Something in this page went wrong while it was being drawn. The rest of KUI still ",
            "works, so you can navigate away and come back."
          ),
          p(cls := ShellCss.PageErrorDetail, detail)
        )
      )
    )

  /** A sentence for a person, from a throwable written for a machine. */
  private def describe(problem: Throwable): String =
    Option(problem.getMessage).filter(_.nonEmpty).getOrElse(problem.getClass.getName)
}
