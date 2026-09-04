package kui.ui.kernel.component

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L.*
import com.raquo.laminar.codecs.StringAsIsCodec

import kui.ui.kernel.css.KernelCss

/** A modal dialog: a backdrop, a panel, a title, a body and a row of actions.
  *
  * ## Nothing exists while it is closed
  *
  * The body is a thunk and is called only when the dialog opens. A dialog rendered up front and hidden with
  * CSS keeps its subscriptions alive, keeps its requests firing, and — the failure mode that matters —
  * appears on the page if the stylesheet ever fails to load. "Closed" here means "absent from the document",
  * which is a claim a test can check and a stylesheet cannot break.
  *
  * ## Accessibility contract
  *
  * `role="dialog"` with `aria-modal="true"` and `aria-labelledby` pointing at the title. Focus is trapped
  * inside while it is open and restored to whatever opened it on close (see `FocusTrap`); `Escape` closes it,
  * and so does clicking the backdrop when the dialog is dismissible.
  *
  * @param dismissible
  *   whether `Escape` and a backdrop click close it. `false` for a dialog in the middle of something that
  *   must be finished or explicitly abandoned, so that a stray click cannot discard work.
  * @param initialFocus
  *   which focusable element inside the dialog starts focused, counted from zero. `ConfirmDialog` uses it to
  *   keep initial focus off a destructive action.
  */
object Dialog {

  def apply(
      open: Var[Boolean],
      title: Signal[String],
      body: () => HtmlElement,
      actions: () => List[HtmlElement],
      size: Size = Size.Md,
      onClose: Observer[Unit] = Observer.empty,
      dismissible: Boolean = true,
      initialFocus: Int = 0,
      testId: Option[String] = None
  ): HtmlElement = {
    val titleId = Components.nextId("kui-dialog-title")

    def close(): Unit = {
      open.set(false)
      onClose.onNext(())
    }

    def panel(): HtmlElement =
      div(
        cls := KernelCss.DialogBackdrop,
        // Only a click on the backdrop itself, not one that bubbled up from inside the panel:
        // dragging a text selection out of the dialog must not close it.
        onClick.filter(event => dismissible && event.target == event.currentTarget) --> { _ => close() },
        div(
          cls := KernelCss.Dialog,
          cls := sizeClass(size),
          role := "dialog",
          ariaModal := "true",
          aria.labelledBy := titleId,
          // Focusable so that focus has somewhere to go when the dialog holds no control at all.
          // Negative, so it is not itself a stop in the Tab order.
          tabIndex := -1,
          Components.testIdAttr(testId),
          FocusTrap(initialFocus),
          onKeyDown.filter(event => dismissible && event.key == "Escape") --> { _ => close() },
          div(
            cls := KernelCss.DialogHeader,
            h2(idAttr := titleId, cls := KernelCss.DialogTitle, text <-- title),
            Option.when(dismissible)(
              button(
                tpe := "button",
                cls := KernelCss.DialogClose,
                // "×" on its own is announced as "times".
                aria.label := "Close dialog",
                Icon.close,
                onClick --> { _ => close() }
              )
            )
          ),
          div(cls := KernelCss.DialogBody, body()),
          div(cls := KernelCss.DialogActions, actions())
        )
      )

    // `child.maybe` is what makes "closed" mean "not in the document".
    // `.distinct` is load-bearing, not tidiness. A `Var[Boolean]` emits on every `set`, including a `set`
    // to the value it already holds, and callers do exactly that: the produce drawer keeps its `open` flag
    // in step with a draft by writing `open.set(draft.isDefined)` whenever the draft changes -- which is on
    // every keystroke. Without `distinct` each of those emissions builds a *new* panel and `child.maybe`
    // swaps the old one out, so the element the user is typing into is destroyed and replaced between one
    // character and the next. Found in a browser: the Publish form's value box accepted exactly one
    // character per click, and focus then jumped to the drawer's close button.
    div(
      cls := KernelCss.DialogHost,
      child.maybe <-- open.signal.distinct.map(isOpen => Option.when(isOpen)(panel()))
    )
  }

  /** `aria-modal="true"` tells a screen reader that everything outside this element is unavailable while it
    * is open. Laminar has no built-in key for it, so it is spelled out.
    */
  private val ariaModal = htmlAttr("aria-modal", StringAsIsCodec)

  private def sizeClass(size: Size): String =
    size match {
      case Size.Sm => KernelCss.DialogSm
      case Size.Md => KernelCss.DialogMd
      case Size.Lg => KernelCss.DialogLg
    }
}

/** "Are you sure?" — the dialog in front of every destructive action.
  *
  * ## Why the destructive button is not focused
  *
  * A confirmation exists to introduce a deliberate pause. If the dangerous button is the one focused when the
  * dialog opens, a user who was already pressing Enter — because that is how they submitted the form that
  * opened it — confirms the deletion without reading a word. So Cancel comes first in the DOM and takes the
  * initial focus, and the destructive action is reached deliberately.
  */
object ConfirmDialog {

  def apply(
      open: Var[Boolean],
      title: Signal[String],
      message: Signal[String],
      onConfirm: Observer[Unit],
      confirmLabel: String = "Confirm",
      cancelLabel: String = "Cancel",
      danger: Boolean = true,
      onClose: Observer[Unit] = Observer.empty,
      testId: Option[String] = None
  ): HtmlElement =
    Dialog(
      open = open,
      title = title,
      body = () => p(text <-- message),
      // Cancel first, so the focus trap's default lands on it and Enter does not delete anything.
      actions = () =>
        List(
          Button(Val(cancelLabel), Observer[Unit](_ => open.set(false)), testId = testId.map(_ + "-cancel")),
          Button(
            Val(confirmLabel),
            Observer[Unit] { _ =>
              open.set(false)
              onConfirm.onNext(())
            },
            variant = if danger then ButtonVariant.Danger else ButtonVariant.Primary,
            testId = testId.map(_ + "-confirm")
          )
        ),
      size = Size.Sm,
      onClose = onClose,
      // Focusable elements in DOM order are: the header's close button, Cancel, Confirm. Starting on
      // Cancel (index 1) means a user who was already pressing Enter cancels rather than deletes.
      initialFocus = 1,
      testId = testId
    )
}
