package kui.ui.kernel.component

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.scalajs.dom

/** Mounts a Laminar element into a real document for the duration of one test.
  *
  * Mounting matters and cannot be skipped: a Laminar binding (`<--`) only becomes active when its
  * element is in the document, because that is when the element gains an owner for its
  * subscriptions. An element built but never mounted has none of its dynamic attributes applied, so
  * a test that inspects one is testing nothing.
  *
  * The element is always unmounted afterwards, including when the assertions fail, so that a leaked
  * subscription cannot make some later test fail for a reason that is nowhere in its own code.
  */
trait Mounted { self: FunSuite =>

  def mounted[A](element: HtmlElement)(check: dom.Element => A): A = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container): Unit
    val root = render(container, element)

    try check(element.ref)
    finally {
      root.unmount(): Unit
      dom.document.body.removeChild(container): Unit
    }
  }

  /** The first descendant carrying this `data-testid`, or a failed assertion naming what was
    * searched for — which is a far more useful failure than a `NullPointerException` three lines
    * later.
    */
  def byTestId(root: dom.Element, testId: String): dom.Element =
    Option(root.querySelector(s"[data-testid='$testId']"))
      .getOrElse(fail(s"no element with data-testid='$testId' in ${root.outerHTML}"))

  def attributeOf(element: dom.Element, name: String): Option[String] =
    Option(element.getAttribute(name))

  /** Sends a real DOM event, the way a browser would. */
  def dispatch(element: dom.Element, event: dom.Event): Unit = element.dispatchEvent(event): Unit

  def click(element: dom.Element): Unit =
    dispatch(element, new dom.MouseEvent("click", new dom.MouseEventInit { bubbles = true }))

  def keyDown(element: dom.Element, pressed: String): Unit =
    dispatch(element, new dom.KeyboardEvent("keydown", new dom.KeyboardEventInit { key = pressed; bubbles = true }))
}
