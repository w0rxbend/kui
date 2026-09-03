package kui.ui.kernel.component

import com.raquo.laminar.api.L.*

import kui.ui.kernel.css.KernelCss
import kui.ui.kernel.state.{ActiveNotification, Notifications}

/** The stack of toasts in the corner of the window.
  *
  * Rendered once by the shell; everything else in KUI raises notifications through `NotificationBus` and
  * never renders a toast itself.
  *
  * ## Announcing without interrupting
  *
  * A toast is a *live region*: the browser tells assistive technology about it without moving focus. Which
  * announcement to use is the whole design decision here:
  *
  *   - `role="status"` (polite) waits for a pause in whatever the screen reader is currently saying. Right
  *     for "Topic created": useful, not urgent.
  *   - `role="alert"` (assertive) interrupts. Reserved for `Danger`, because an error is the one thing worth
  *     interrupting for.
  *
  * Using assertive everywhere is the common mistake, and it makes a screen reader unusable during a burst of
  * notifications.
  *
  * Focus is never moved to a toast. A toast that stole focus would yank a user out of the form they were
  * filling in, which is a worse outcome than the one it was reporting.
  */
object Toast {

  def apply(
      notifications: Signal[List[ActiveNotification]],
      queued: Signal[Int],
      dismiss: Observer[String]
  ): HtmlElement =
    div(
      cls := KernelCss.ToastStack,
      // The container is the live region, not each toast: a region that is added to the document at
      // the same moment as its content is often not announced at all, because there was nothing to
      // compare the change against.
      role := "status",
      aria.live := "polite",
      aria.atomic := false,
      children <-- notifications.split(_.id)((_, initial, signal) => toast(initial, signal, dismiss)),
      child.maybe <-- queued.map(waiting =>
        Option.when(waiting > 0)(div(cls := KernelCss.ToastQueued, s"+$waiting more"))
      )
    )

  private def toast(
      initial: ActiveNotification,
      notification: Signal[ActiveNotification],
      dismiss: Observer[String]
  ): HtmlElement = {
    val isError = initial.notification.tone == Tone.Danger

    div(
      cls := KernelCss.Toast,
      cls := toneClass(initial.notification.tone),
      role := (if isError then "alert" else "status"),
      dataAttr("testid") := "toast",
      div(
        cls := KernelCss.ToastContent,
        div(cls := KernelCss.ToastTitle, text <-- notification.map(_.notification.title)),
        child.maybe <-- notification.map(
          _.notification.message.map(body => div(cls := KernelCss.ToastMessage, body))
        )
      ),
      button(
        tpe := "button",
        cls := KernelCss.ToastDismiss,
        aria.label <-- notification.map(current => s"Dismiss: ${current.notification.title}"),
        Icon.close,
        onClick.mapTo(initial.id) --> dismiss
      )
    )
  }

  private def toneClass(tone: Tone): String =
    tone match {
      case Tone.Neutral => KernelCss.ToastNeutral
      case Tone.Info => KernelCss.ToastInfo
      case Tone.Success => KernelCss.ToastSuccess
      case Tone.Warning => KernelCss.ToastWarning
      case Tone.Danger => KernelCss.ToastDanger
    }

  /** The stack wired to the application's bus. What the shell renders. */
  def default(bus: Notifications): HtmlElement =
    apply(bus.current, bus.queued, Observer[String](bus.dismiss))
}
