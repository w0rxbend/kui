package kui.ui.kernel.component

import com.raquo.laminar.api.L.*
import munit.FunSuite

import kui.ui.kernel.css.KernelCss

final class ButtonSuite extends FunSuite with Mounted {

  test("renders the variant and size classes") {
    val element = Button(Val("Save"), Observer.empty, variant = ButtonVariant.Primary, size = Size.Lg)

    mounted(element) { root =>
      assert(root.classList.contains(KernelCss.Button), root.outerHTML)
      assert(root.classList.contains(KernelCss.ButtonPrimary), root.outerHTML)
      assert(root.classList.contains(KernelCss.ButtonLg), root.outerHTML)
    }
  }

  test("testId reaches the DOM") {
    mounted(Button(Val("Save"), Observer.empty, testId = Some("save"))) { root =>
      assertEquals(attributeOf(root, "data-testid"), Some("save"))
    }
  }

  test("a click reaches the observer") {
    val clicks = Var(0)

    mounted(Button(Val("Save"), Observer[Unit](_ => clicks.update(_ + 1)))) { root =>
      click(root)
      assertEquals(clicks.now(), 1)
    }
  }

  test("a disabled button is disabled in the DOM and does not fire") {
    val clicks = Var(0)
    val button = Button(Val("Save"), Observer[Unit](_ => clicks.update(_ + 1)), disabled = Val(true))

    mounted(button) { root =>
      // Disabled in the DOM, not merely styled: the browser itself must refuse the interaction, so
      // that the guarantee survives a missing stylesheet.
      assertEquals(attributeOf(root, "disabled").isDefined, true, root.outerHTML)
      click(root)
      assertEquals(clicks.now(), 0)
    }
  }

  test("loading sets aria-busy, disables the button and blocks clicks") {
    val clicks  = Var(0)
    val loading = Var(true)
    val button  = Button(Val("Save"), Observer[Unit](_ => clicks.update(_ + 1)), loading = loading.signal)

    mounted(button) { root =>
      assertEquals(attributeOf(root, "aria-busy"), Some("true"))
      assertEquals(attributeOf(root, "disabled").isDefined, true)

      // The bug this prevents: a double-clicked "Delete topic" sending two requests.
      click(root)
      assertEquals(clicks.now(), 0)

      loading.set(false)
      assertEquals(attributeOf(root, "aria-busy"), Some("false"))
      click(root)
      assertEquals(clicks.now(), 1)
    }
  }

  test("the label follows its signal") {
    val label = Var("Retry")

    mounted(Button(label.signal, Observer.empty)) { root =>
      assertEquals(root.textContent, "Retry")
      label.set("Stop")
      assertEquals(root.textContent, "Stop")
    }
  }
}
