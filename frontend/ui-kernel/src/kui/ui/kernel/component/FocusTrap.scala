package kui.ui.kernel.component

import com.raquo.laminar.api.L.*
import org.scalajs.dom

/** Keeps keyboard focus inside an element for as long as it is on screen, and puts focus back where it was
  * when the element goes away.
  *
  * ## Why this is necessary
  *
  * A modal dialog covers the page, but the page underneath is still in the browser's Tab order. A sighted
  * mouse user never notices. A keyboard user tabs off the last button in the dialog and lands somewhere
  * behind it — invisible, and with no way to tell where they are. A screen-reader user is worse off still:
  * the reading cursor wanders through content that is visually hidden behind an overlay.
  *
  * Restoring focus on close is the other half, and it is the half that is nearly always missing. If a dialog
  * is opened from a row's "Delete" button and closing it drops focus onto `<body>`, the next Tab press starts
  * again from the top of the page. Getting back to where you were in a table of two hundred rows is then a
  * long walk.
  *
  * ## What it does not do
  *
  * It does not make the background inert to a screen reader — that needs `aria-hidden` or the `inert`
  * attribute on the rest of the document, which is the shell's job because only the shell knows what "the
  * rest" is. `Dialog` renders `aria-modal="true"`, which most screen readers honour.
  */
object FocusTrap {

  /** Elements a user can reach with the Tab key.
    *
    * `[tabindex="-1"]` is deliberately excluded: an element with a negative tabindex can be focused
    * programmatically but is not in the Tab order, and including it here would make Tab stop somewhere the
    * browser never would.
    */
  private val FocusableSelector: String = List(
    "a[href]",
    "button:not([disabled])",
    "input:not([disabled]):not([type='hidden'])",
    "select:not([disabled])",
    "textarea:not([disabled])",
    "[tabindex]:not([tabindex='-1'])"
  ).mkString(", ")

  private def focusable(container: dom.Element): List[dom.html.Element] =
    container.querySelectorAll(FocusableSelector).toList.collect { case element: dom.html.Element => element }

  /** Installs the trap on whichever element this modifier is attached to.
    *
    * @param initialFocus
    *   which focusable element to start on, counted from zero among the focusable descendants. The default is
    *   the first one; `ConfirmDialog` uses this to keep the initial focus off a destructive action.
    */
  def apply(initialFocus: Int = 0): Modifier[HtmlElement] =
    List[Modifier[HtmlElement]](
      onMountUnmountCallback(
        mount = { context =>
          val container = context.thisNode.ref
          // Remembered before anything is focused, because the very next line changes it.
          FocusTrap.previouslyFocused = Option(dom.document.activeElement).collect {
            case e: dom.html.Element => e
          }

          val candidates = focusable(container)
          candidates.lift(initialFocus).orElse(candidates.headOption) match {
            case Some(target) => target.focus()
            // Nothing focusable inside: focus the container itself, so that the reading cursor is at
            // least in the right place. `Dialog` gives its root `tabindex="-1"` for exactly this.
            case None => container.focus()
          }
        },
        unmount = _ => FocusTrap.previouslyFocused.foreach(_.focus())
      ),
      onKeyDown --> { event =>
        if event.key == "Tab" then {
          val candidates = focusable(event.currentTarget match {
            case element: dom.Element => element
            case _ => dom.document.body
          })

          if candidates.isEmpty then event.preventDefault()
          else {
            val active = Option(dom.document.activeElement)
            val first = candidates.head
            val last = candidates.last

            // Wrapping is the whole trick: Tab off the end goes back to the start, Shift+Tab off the
            // start goes to the end, and focus never leaves the container.
            if event.shiftKey && active.contains(first) then {
              event.preventDefault()
              last.focus()
            } else if !event.shiftKey && active.contains(last) then {
              event.preventDefault()
              first.focus()
            }
          }
        }
      }
    )

  /** Where focus was before the trap took it.
    *
    * A single slot rather than a stack: KUI never opens a dialog from inside another dialog, and a stack
    * would be state to keep correct for a case that does not arise. If nested overlays ever become a real
    * requirement, this is the line that has to change.
    */
  private var previouslyFocused: Option[dom.html.Element] = None
}
