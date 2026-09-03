package kui.ui.kernel.component

import com.raquo.laminar.api.L.*
import com.raquo.laminar.codecs.StringAsIsCodec

import kui.ui.kernel.css.KernelCss

/** Which edge a drawer slides in from. */
enum DrawerSide(val className: String) {
  case Right extends DrawerSide(KernelCss.DrawerRight)
  case Left extends DrawerSide(KernelCss.DrawerLeft)
}

/** A panel that slides in from the side of the window: message detail, produce-a-message, filter editors.
  *
  * Same modal rules as `Dialog` — absent from the document while closed, focus trapped while open, focus
  * restored on close, `Escape` closes — and the same reasons for each. A drawer differs from a dialog only in
  * shape and in what it is for: a dialog interrupts to ask a question, a drawer opens a second surface beside
  * the thing you are looking at.
  *
  * @param width
  *   any CSS length. Given as a string rather than a token because the right width depends on the content — a
  *   message payload wants more room than a filter form.
  */
object Drawer {

  def apply(
      open: Var[Boolean],
      title: Signal[String],
      body: () => HtmlElement,
      side: DrawerSide = DrawerSide.Right,
      width: String = "28rem",
      onClose: Observer[Unit] = Observer.empty,
      testId: Option[String] = None
  ): HtmlElement = {
    val titleId = Components.nextId("kui-drawer-title")

    def close(): Unit = {
      open.set(false)
      onClose.onNext(())
    }

    def panel(): HtmlElement =
      div(
        cls := KernelCss.DrawerBackdrop,
        onClick.filter(event => event.target == event.currentTarget) --> { _ => close() },
        div(
          cls := KernelCss.Drawer,
          cls := side.className,
          role := "dialog",
          ariaModal := "true",
          aria.labelledBy := titleId,
          tabIndex := -1,
          styleAttr := s"width: $width",
          Components.testIdAttr(testId),
          FocusTrap(),
          onKeyDown.filter(_.key == "Escape") --> { _ => close() },
          div(
            cls := KernelCss.DrawerHeader,
            h2(idAttr := titleId, cls := KernelCss.DrawerTitle, text <-- title),
            button(
              tpe := "button",
              cls := KernelCss.DrawerClose,
              aria.label := "Close",
              Icon.close,
              onClick --> { _ => close() }
            )
          ),
          div(cls := KernelCss.DrawerBody, body())
        )
      )

    div(cls := KernelCss.DrawerHost, child.maybe <-- open.signal.map(isOpen => Option.when(isOpen)(panel())))
  }

  /** `aria-modal="true"` tells a screen reader that everything outside this element is unavailable while it
    * is open. Laminar has no built-in key for it, so it is spelled out.
    */
  private val ariaModal = htmlAttr("aria-modal", StringAsIsCodec)
}
