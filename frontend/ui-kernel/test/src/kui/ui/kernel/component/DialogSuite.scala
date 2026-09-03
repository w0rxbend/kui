package kui.ui.kernel.component

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.scalajs.dom

final class DialogSuite extends FunSuite with Mounted {

  private def twoButtons(): List[HtmlElement] = List(
    Button(Val("Cancel"), Observer.empty, testId = Some("cancel")),
    Button(Val("Save"), Observer.empty, testId = Some("save"))
  )

  test("bodyIsNotRenderedWhileClosed") {
    // "Closed" has to mean "absent from the document", not "hidden by CSS": a hidden dialog keeps its
    // subscriptions alive and its requests firing, and appears on the page if the stylesheet fails.
    var built = 0
    val open  = Var(false)
    val dialog = Dialog(open, Val("Delete topic"), () => { built += 1; div("body") }, () => Nil)

    mounted(dialog) { root =>
      assertEquals(built, 0)
      assertEquals(Option(root.querySelector("[role='dialog']")), None)

      open.set(true)
      assertEquals(built, 1)
      assert(root.textContent.contains("body"), root.outerHTML)

      open.set(false)
      assertEquals(Option(root.querySelector("[role='dialog']")), None)
    }
  }

  test("it is announced as a modal dialog labelled by its title") {
    mounted(Dialog(Var(true), Val("Delete topic"), () => div(), () => Nil)) { root =>
      val panel = root.querySelector("[role='dialog']")

      assertEquals(attributeOf(panel, "aria-modal"), Some("true"))

      val titleId = attributeOf(panel, "aria-labelledby")
      assertEquals(titleId.flatMap(id => Option(dom.document.getElementById(id))).map(_.textContent), Some("Delete topic"))
    }
  }

  test("trapsFocusWithinTheDialog") {
    mounted(Dialog(Var(true), Val("Confirm"), () => div(), () => twoButtons())) { root =>
      val panel = root.querySelector("[role='dialog']")
      val save  = byTestId(root, "save")

      // Tab from the last control wraps back to the first, which here is the header's close button.
      save match {
        case element: dom.html.Element => element.focus()
        case _                         => fail("save button is not an HTML element")
      }
      keyDown(panel, "Tab")

      assertEquals(Option(dom.document.activeElement).map(_.tagName), Some("BUTTON"))
      assert(panel.contains(dom.document.activeElement), "focus escaped the dialog")
    }
  }

  test("restoresFocusToTheTriggerOnClose") {
    // The accessibility bug everyone ships: closing a dialog drops focus onto <body>, and the next
    // Tab press starts again from the top of the page.
    val open    = Var(false)
    val trigger = button(tpe := "button", dataAttr("testid") := "trigger", "Delete")

    mounted(div(trigger, Dialog(open, Val("Delete topic"), () => div(), () => twoButtons()))) { _ =>
      trigger.ref.focus()
      assertEquals(dom.document.activeElement, trigger.ref)

      open.set(true)
      assert(dom.document.activeElement != trigger.ref, "focus should have moved into the dialog")

      open.set(false)
      assertEquals(dom.document.activeElement, trigger.ref)
    }
  }

  test("escapeClosesAndFiresOnClose") {
    val open   = Var(true)
    val closed = Var(0)

    mounted(Dialog(open, Val("Delete topic"), () => div(), () => Nil, onClose = Observer[Unit](_ => closed.update(_ + 1)))) {
      root =>
        keyDown(root.querySelector("[role='dialog']"), "Escape")

        assertEquals(open.now(), false)
        assertEquals(closed.now(), 1)
    }
  }

  test("clickingTheBackdropClosesOnlyWhenDismissible") {
    val dismissible = Var(true)

    mounted(Dialog(dismissible, Val("Title"), () => div(), () => Nil)) { root =>
      click(root.querySelector(".kui-dialog-backdrop"))
      assertEquals(dismissible.now(), false)
    }

    val fixed = Var(true)
    mounted(Dialog(fixed, Val("Title"), () => div(), () => Nil, dismissible = false)) { root =>
      click(root.querySelector(".kui-dialog-backdrop"))
      assertEquals(fixed.now(), true)

      keyDown(root.querySelector("[role='dialog']"), "Escape")
      assertEquals(fixed.now(), true)
    }
  }

  test("a click inside the panel does not close the dialog") {
    // Dragging a text selection out of the dialog produces a click whose target is inside it.
    val open = Var(true)

    mounted(Dialog(open, Val("Title"), () => div(dataAttr("testid") := "inner", "content"), () => Nil)) { root =>
      click(byTestId(root, "inner"))

      assertEquals(open.now(), true)
    }
  }

  test("confirmDialogDoesNotAutoFocusADestructiveAction") {
    val open = Var(true)

    mounted(ConfirmDialog(open, Val("Delete topic"), Val("This cannot be undone."), Observer.empty, testId = Some("delete"))) {
      root =>
        val confirm = byTestId(root, "delete-confirm")

        assert(dom.document.activeElement != confirm, "the destructive button must not start focused")
        assertEquals(Option(dom.document.activeElement).map(_.textContent), Some("Cancel"))
    }
  }

  test("confirming closes the dialog and fires onConfirm exactly once") {
    val open      = Var(true)
    val confirmed = Var(0)

    mounted(ConfirmDialog(open, Val("Delete"), Val("Sure?"), Observer[Unit](_ => confirmed.update(_ + 1)), testId = Some("d"))) {
      root =>
        click(byTestId(root, "d-confirm"))

        assertEquals(confirmed.now(), 1)
        assertEquals(open.now(), false)
    }
  }

  test("cancelling closes without confirming") {
    val open      = Var(true)
    val confirmed = Var(0)

    mounted(ConfirmDialog(open, Val("Delete"), Val("Sure?"), Observer[Unit](_ => confirmed.update(_ + 1)), testId = Some("d"))) {
      root =>
        click(byTestId(root, "d-cancel"))

        assertEquals(confirmed.now(), 0)
        assertEquals(open.now(), false)
    }
  }

  test("a drawer follows the same modal rules") {
    val open = Var(false)

    mounted(Drawer(open, Val("Message"), () => div("payload"))) { root =>
      assertEquals(Option(root.querySelector("[role='dialog']")), None)

      open.set(true)
      assertEquals(attributeOf(root.querySelector("[role='dialog']"), "aria-modal"), Some("true"))

      keyDown(root.querySelector("[role='dialog']"), "Escape")
      assertEquals(open.now(), false)
    }
  }
}
