package kui.ui.kernel

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.scalajs.dom

import kui.ui.kernel.css.KernelCss

/** The module's smoke test.
  *
  * It proves two things that everything later in the frontend lane depends on, and nothing else:
  * that the build compiles a version into the browser bundle, and that a Laminar element really
  * mounts into a `document` under the jsdom test environment. If this suite is red, no component
  * test in UI-003 or UI-004 can be believed.
  */
final class KernelSuite extends FunSuite {

  test("the build compiles a non-empty version into the frontend") {
    assert(Kernel.version.nonEmpty, s"version was '${Kernel.version}'")
  }

  test("a Laminar element renders into a real document") {
    // jsdom, not a browser: `document` exists and is manipulated for real, but there is no layout
    // engine, so this asserts structure and never geometry.
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container): Unit

    val app = div(cls := KernelCss.Root, span("kui"))
    val root = render(container, app)

    try {
      assertEquals(container.textContent, "kui")
      assertEquals(container.firstElementChild.getAttribute("class"), KernelCss.Root)
    } finally {
      // Laminar bindings live as long as the mounted element does; unmounting is what releases
      // them, and a test that leaks a subscription can make a later test fail for no visible
      // reason.
      root.unmount(): Unit
      dom.document.body.removeChild(container): Unit
    }
  }
}
