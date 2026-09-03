package kui.ui.kernel.component

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L.*

import kui.ui.kernel.css.KernelCss

/** How loud a button is, and what it implies about what happens when you press it. */
enum ButtonVariant(val className: String) {

  /** The one action the screen exists for. At most one per view. */
  case Primary extends ButtonVariant(KernelCss.ButtonPrimary)

  /** Everything else that is a real action. The default. */
  case Secondary extends ButtonVariant(KernelCss.ButtonSecondary)

  /** Deletes something, or cannot be undone. Always paired with a confirmation (`ConfirmDialog`). */
  case Danger extends ButtonVariant(KernelCss.ButtonDanger)

  /** A button that reads as text until you hover it: toolbar and table-row actions. */
  case Ghost extends ButtonVariant(KernelCss.ButtonGhost)
}

/** A button.
  *
  * ## Why this exists rather than a plain `button(...)`
  *
  * Two reasons, and neither is styling. First, a control that is *busy* must not fire again — a
  * double-clicked "Delete topic" that sends two requests is a real bug, and the fix belongs in one place
  * rather than in every caller. Second, a busy control has to say so to a screen reader (`aria-busy`), and
  * that is the kind of thing that gets left out when it has to be remembered.
  *
  * ## Contract
  *
  *   - It is a real `<button type="button">`. It is reachable by Tab, it activates on Enter and Space, and it
  *     does all of that without any JavaScript from us.
  *   - `disabled` and `loading` both set the DOM `disabled` attribute, so the browser itself refuses the
  *     click; the observer is additionally guarded, so a synthetic click in a test cannot get through either.
  *   - While loading it carries `aria-busy="true"` and shows a spinner in place of any icon, so the button
  *     does not change width and the layout does not jump.
  *
  * @param label
  *   the visible text. A `Signal` because plenty of buttons change their own label ("Retry" after a failure,
  *   "Stop" while tailing).
  * @param onClick
  *   what to do. `Observer[Unit]` rather than a function, because that is what composes with the rest of
  *   Airstream.
  * @param icon
  *   an optional leading icon, from `Icon`. Passed as a thunk because a DOM node can only be in one place,
  *   and the button needs to build a fresh one.
  * @param testId
  *   rendered as `data-testid`, which is what E2E tests select on.
  */
object Button {

  def apply(
      label: Signal[String],
      onClick: Observer[Unit],
      variant: ButtonVariant = ButtonVariant.Secondary,
      size: Size = Size.Md,
      disabled: Signal[Boolean] = Val(false),
      loading: Signal[Boolean] = Val(false),
      icon: Option[() => SvgElement] = None,
      testId: Option[String] = None
  ): HtmlElement = {
    // One signal for "must not act", so the DOM attribute and the click guard can never disagree.
    val blocked = disabled.combineWith(loading).map((isDisabled, isLoading) => isDisabled || isLoading)

    button(
      tpe := "button",
      cls := KernelCss.Button,
      cls := variant.className,
      cls := sizeClass(size),
      cls(KernelCss.ButtonLoading) <-- loading,
      L.disabled <-- blocked,
      aria.busy <-- loading,
      Components.testIdAttr(testId),
      // The spinner replaces the icon rather than joining it, so the button keeps its width.
      child.maybe <-- loading.map { isLoading =>
        if isLoading then Some(span(cls := KernelCss.ButtonIcon, Icon.spinner))
        else icon.map(build => span(cls := KernelCss.ButtonIcon, build()))
      },
      span(cls := KernelCss.ButtonLabel, text <-- label),
      // A native `disabled` button already fires nothing, but a test (or a caller that styles rather
      // than disables) can still produce a click event. Sampling `blocked` here makes the guarantee
      // "the observer never sees a click it should not", which is what callers actually rely on.
      L.onClick.compose(_.sample(blocked).filter(isBlocked => !isBlocked).mapTo(())) --> onClick
    )
  }

  private def sizeClass(size: Size): String =
    size match {
      case Size.Sm => KernelCss.ButtonSm
      case Size.Md => KernelCss.ButtonMd
      case Size.Lg => KernelCss.ButtonLg
    }
}
