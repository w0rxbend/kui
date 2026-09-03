package kui.ui.kernel.component

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.scalajs.dom

final class TabsSuite extends FunSuite with Mounted {

  private def threeTabs: List[Tab] = List(
    Tab("overview", "Overview", () => div("overview body")),
    Tab("messages", "Messages", () => div("messages body")),
    Tab("settings", "Settings", () => div("settings body"))
  )

  private def tabButtons(root: dom.Element): List[dom.Element] =
    root.querySelectorAll("[role='tab']").toList.collect { case element: dom.Element => element }

  test("only the selected panel is in the DOM") {
    val selected = Var("overview")

    mounted(Tabs(Val(threeTabs), selected)) { root =>
      assertEquals(root.querySelectorAll("[role='tabpanel']").length, 1)
      assert(root.textContent.contains("overview body"), root.textContent)
      assert(!root.textContent.contains("messages body"), root.textContent)

      selected.set("messages")
      assertEquals(root.querySelectorAll("[role='tabpanel']").length, 1)
      assert(root.textContent.contains("messages body"), root.textContent)
    }
  }

  test("aria-selected matches the selection and only one tab is in the Tab order") {
    val selected = Var("messages")

    mounted(Tabs(Val(threeTabs), selected)) { root =>
      val buttons = tabButtons(root)

      assertEquals(buttons.map(attributeOf(_, "aria-selected")), List(Some("false"), Some("true"), Some("false")))
      // The roving tabindex: the strip is one stop in the Tab order, not three.
      assertEquals(buttons.map(attributeOf(_, "tabindex")), List(Some("-1"), Some("0"), Some("-1")))
    }
  }

  test("the arrow keys move the selection and wrap around") {
    val selected = Var("overview")

    mounted(Tabs(Val(threeTabs), selected)) { root =>
      val list = root.querySelector("[role='tablist']")

      keyDown(list, "ArrowRight")
      assertEquals(selected.now(), "messages")

      keyDown(list, "ArrowLeft")
      assertEquals(selected.now(), "overview")

      // Wrapping is part of the WAI-ARIA pattern: Left on the first tab reaches the last.
      keyDown(list, "ArrowLeft")
      assertEquals(selected.now(), "settings")
    }
  }

  test("Home and End jump to the ends") {
    val selected = Var("messages")

    mounted(Tabs(Val(threeTabs), selected)) { root =>
      val list = root.querySelector("[role='tablist']")

      keyDown(list, "End")
      assertEquals(selected.now(), "settings")

      keyDown(list, "Home")
      assertEquals(selected.now(), "overview")
    }
  }

  test("an arrow key moves DOM focus as well as the selection") {
    val selected = Var("overview")

    mounted(Tabs(Val(threeTabs), selected)) { root =>
      keyDown(root.querySelector("[role='tablist']"), "ArrowRight")

      assertEquals(Option(dom.document.activeElement).flatMap(attributeOf(_, "data-tab-id")), Some("messages"))
    }
  }

  test("clicking a tab selects it") {
    val selected = Var("overview")

    mounted(Tabs(Val(threeTabs), selected)) { root =>
      click(tabButtons(root)(2))

      assertEquals(selected.now(), "settings")
    }
  }

  test("an unrelated key is left alone") {
    val selected = Var("overview")

    mounted(Tabs(Val(threeTabs), selected)) { root =>
      keyDown(root.querySelector("[role='tablist']"), "a")

      assertEquals(selected.now(), "overview")
    }
  }

  test("an empty tab list renders nothing and does not throw") {
    mounted(Tabs(Val(Nil), Var("overview"))) { root =>
      assertEquals(tabButtons(root), Nil)
      assertEquals(root.querySelectorAll("[role='tabpanel']").length, 0)
    }
  }

  test("a selection that matches no tab renders no panel rather than guessing") {
    // Quietly switching to the first tab would hide whatever produced the bad id.
    mounted(Tabs(Val(threeTabs), Var("does-not-exist"))) { root =>
      assertEquals(root.querySelectorAll("[role='tabpanel']").length, 0)
      assertEquals(tabButtons(root).size, 3)
    }
  }

  test("the panel is labelled by its tab") {
    mounted(Tabs(Val(threeTabs), Var("overview"))) { root =>
      val panel = root.querySelector("[role='tabpanel']")
      val tab   = tabButtons(root).head

      assertEquals(attributeOf(panel, "aria-labelledby"), attributeOf(tab, "id"))
      assertEquals(attributeOf(tab, "aria-controls"), attributeOf(panel, "id"))
    }
  }
}
