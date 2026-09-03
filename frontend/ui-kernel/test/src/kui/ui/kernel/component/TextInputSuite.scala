package kui.ui.kernel.component

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.scalajs.dom

final class TextInputSuite extends FunSuite with Mounted {

  private def inputIn(root: dom.Element): dom.html.Input =
    root.querySelector("input") match {
      case element: dom.html.Input => element
      case _                       => fail(s"no <input> in ${root.outerHTML}")
    }

  private def labelIn(root: dom.Element): dom.html.Label =
    root.querySelector("label") match {
      case element: dom.html.Label => element
      case _                       => fail(s"no <label> in ${root.outerHTML}")
    }

  test("the label is associated with the input by for and id") {
    mounted(TextInput(Var(""), "Topic name")) { root =>
      val forAttribute = attributeOf(labelIn(root), "for")

      assertEquals(forAttribute, attributeOf(inputIn(root), "id"))
      assert(forAttribute.exists(_.nonEmpty), root.outerHTML)
    }
  }

  test("two instances get different ids") {
    // Ids must be unique in the document, and two fields with the same visible label on one screen
    // are perfectly ordinary — so the id cannot be derived from the label.
    mounted(div(TextInput(Var(""), "Name"), TextInput(Var(""), "Name"))) { root =>
      val ids = root.querySelectorAll("input").toList.collect { case element: dom.Element => element }.flatMap(attributeOf(_, "id"))

      assertEquals(ids.distinct.size, 2, ids.toString)
    }
  }

  test("typing writes to the Var and writing to the Var updates the input") {
    val value = Var("")

    mounted(TextInput(value, "Topic name")) { root =>
      val field = inputIn(root)

      field.value = "orders"
      dispatch(field, new dom.Event("input", new dom.EventInit { bubbles = true }))
      assertEquals(value.now(), "orders")

      // The other direction: a form reset, or a value arriving from the server.
      value.set("payments")
      assertEquals(field.value, "payments")
    }
  }

  test("aria-invalid and the error message appear exactly when there is an error") {
    val error = Var(Option.empty[String])

    mounted(TextInput(Var(""), "Topic name", error = error.signal)) { root =>
      assertEquals(attributeOf(inputIn(root), "aria-invalid"), Some("false"))
      assertEquals(Option(root.querySelector("[role='alert']")), None)

      error.set(Some("must not be empty"))
      assertEquals(attributeOf(inputIn(root), "aria-invalid"), Some("true"))
      assertEquals(Option(root.querySelector("[role='alert']")).map(_.textContent), Some("must not be empty"))
    }
  }

  test("aria-describedby names the hint and the error, and only the ones present") {
    val error = Var(Option.empty[String])

    mounted(TextInput(Var(""), "Topic name", hint = Some("lower case only"), error = error.signal)) { root =>
      val field = inputIn(root)

      // Pointing at an element that is not in the document makes some screen readers announce
      // nothing at all, so the error id must appear only once the error does.
      val withoutError = attributeOf(field, "aria-describedby").getOrElse("").split(' ').toList.filter(_.nonEmpty)
      assertEquals(withoutError.size, 1, withoutError.toString)

      error.set(Some("must not be empty"))
      val withError = attributeOf(field, "aria-describedby").getOrElse("").split(' ').toList.filter(_.nonEmpty)
      assertEquals(withError.size, 2, withError.toString)
      assert(withError.forall(id => Option(dom.document.getElementById(id)).isDefined), withError.toString)
    }
  }
}
