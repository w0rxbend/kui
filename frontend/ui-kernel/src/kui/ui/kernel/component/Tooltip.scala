package kui.ui.kernel.component

import com.raquo.laminar.api.L.*

import kui.ui.kernel.css.KernelCss

/** Where a tooltip sits relative to the thing it describes. */
enum Placement(val className: String) {
  case Top extends Placement(KernelCss.TooltipTop)
  case Bottom extends Placement(KernelCss.TooltipBottom)
  case Left extends Placement(KernelCss.TooltipLeft)
  case Right extends Placement(KernelCss.TooltipRight)
}

/** A short explanation attached to a control.
  *
  * ## Rules that keep a tooltip usable
  *
  * **It appears on focus, not only on hover.** A tooltip that only responds to a mouse does not exist for a
  * keyboard user, and does not exist on a touch screen. Both events are bound.
  *
  * **It never contains anything interactive.** No links, no buttons. There is no way to move a pointer onto a
  * tooltip without passing over the gap that dismisses it, so anything inside it is unreachable — and to a
  * keyboard user, invisible.
  *
  * **It is never the only place the information appears.** A tooltip is a hint. Anything a user must know in
  * order to act belongs in the page (ADR-032's unavailable panel is the worked example: the reason, the
  * timestamp and the retry are on the page, not in a tooltip).
  *
  * The content is tied to the trigger with `aria-describedby`, so a screen reader reads it after the
  * control's own name rather than instead of it.
  */
object Tooltip {

  def apply(
      trigger: HtmlElement,
      content: Signal[String],
      placement: Placement = Placement.Top,
      testId: Option[String] = None
  ): HtmlElement = {
    val tooltipId = Components.nextId("kui-tooltip")
    val visible = Var(false)

    span(
      cls := KernelCss.TooltipHost,
      Components.testIdAttr(testId),
      trigger.amend(
        aria.describedBy := tooltipId,
        onMouseEnter.mapTo(true) --> visible,
        onMouseLeave.mapTo(false) --> visible,
        onFocus.mapTo(true) --> visible,
        onBlur.mapTo(false) --> visible,
        // Escape dismisses it without moving focus, which is how a keyboard user gets a tooltip out
        // of the way when it covers what they were reading.
        onKeyDown.filter(_.key == "Escape").mapTo(false) --> visible
      ),
      // Always in the document, hidden with `hidden` rather than removed: `aria-describedby` has to
      // point at an element that exists, or some screen readers announce nothing at all.
      span(
        idAttr := tooltipId,
        cls := KernelCss.Tooltip,
        cls := placement.className,
        role := "tooltip",
        hidden <-- visible.signal.map(!_),
        text <-- content
      )
    )
  }
}
