package kui.ui.shell.page

import scala.scalajs.js

import com.raquo.laminar.api.L.*

import kui.ui.kernel.component.{Button, ButtonVariant, Icon}
import kui.ui.shell.{ShellConnectivity, ShellCss}

/** The one full-screen state KUI has (feature matrix KU-009, "Screen 33").
  *
  * ## When it appears, and when it must not
  *
  * Only when the *shell's own* calls cannot reach the gateway. A feature's call failing is the feature's
  * fallback panel's job (ADR-032), and confusing the two is the mistake this whole task exists to prevent:
  * taking the entire application away from a user because one endpoint is down means throwing them out of
  * everything that still worked.
  *
  * ## What it has to contain
  *
  * Four things, and each earns its place:
  *
  *   - **what happened**, in a sentence, so the user knows this is not their doing;
  *   - **an automatic retry with a visible countdown**. The countdown is not decoration. A screen that says
  *     "cannot connect" and then sits still reads as frozen, and a frozen screen gets reloaded — which throws
  *     away every bit of state the application still had;
  *   - **a manual "Try again"**, because a user who has just reconnected their wifi should not have to wait
  *     out a thirty-second timer they did not choose;
  *   - **when contact was last made**, because "a moment ago" and "at 09:14" lead to very different decisions
  *     about whether to call somebody.
  *
  * ## It renders with no data at all
  *
  * No version, no principal, no capabilities, no icons fetched from anywhere. By definition nothing answered,
  * so anything this page needed from the server would be a thing it could not have. Everything here is
  * compiled in.
  */
object GatewayUnreachable {

  def apply(state: Signal[ShellConnectivity], retry: Observer[Unit]): HtmlElement =
    div(
      cls := ShellCss.Unreachable,
      dataAttr("testid") := "gateway-unreachable",
      // A dialog rather than a region: it covers everything and nothing behind it is usable, and
      // `alertdialog` is what tells a screen reader to announce it immediately rather than when the
      // user next happens to move there.
      role := "alertdialog",
      // Laminar does not define `aria-modal`, so it is spelled out.
      htmlAttr("aria-modal", com.raquo.laminar.codecs.StringAsIsCodec) := "true",
      aria.labelledBy := TitleId,
      div(
        cls := ShellCss.UnreachableCard,
        div(cls := ShellCss.UnreachableIcon, Icon.warning),
        h1(idAttr := TitleId, "KUI cannot reach the server"),
        p(
          "The KUI gateway is not answering. This is not a problem with your browser, and nothing ",
          "you were doing has been lost — KUI will pick up where it left off as soon as the server ",
          "responds."
        ),
        p(
          cls := ShellCss.UnreachableCountdown,
          dataAttr("testid") := "unreachable-countdown",
          // Polite rather than assertive: it changes every second, and an assertive live region
          // would interrupt a screen-reader user continuously.
          aria.live := "polite",
          text <-- state.map(countdown)
        ),
        p(
          cls := ShellCss.UnreachableLastContact,
          dataAttr("testid") := "unreachable-last-contact",
          text <-- state.map(lastContact)
        ),
        Button(
          label = Val("Try again"),
          onClick = retry,
          variant = ButtonVariant.Primary,
          icon = Some(() => Icon.refresh),
          testId = Some("unreachable-retry")
        )
      )
    )

  private val TitleId = "kui-unreachable-title"

  private def countdown(state: ShellConnectivity): String =
    state match {
      case ShellConnectivity.Lost(_, _, 1) => "Trying again in 1 second."
      case ShellConnectivity.Lost(_, _, seconds) => s"Trying again in $seconds seconds."
      // Not reachable while this element is on screen, and the string still has to be something: a
      // live region that goes empty is announced as an emptying, which is worse than a stale line.
      case ShellConnectivity.Connected(_) => "Connected."
    }

  private def lastContact(state: ShellConnectivity): String =
    state match {
      case ShellConnectivity.Lost(_, at, _) => s"Last contact with the server: ${clockTime(at)}."
      case ShellConnectivity.Connected(at) => s"Last contact with the server: ${clockTime(at)}."
    }

  /** A wall-clock time, in the browser's own locale.
    *
    * The time of day rather than "three minutes ago" on purpose: a relative time has to be recomputed to stay
    * true, and a relative time that has silently stopped updating is a lie. An absolute one is right for
    * ever.
    */
  private def clockTime(at: js.Date): String = at.toLocaleTimeString()
}
