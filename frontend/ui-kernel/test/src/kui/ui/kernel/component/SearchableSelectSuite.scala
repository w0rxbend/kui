package kui.ui.kernel.component

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.scalajs.dom

class SearchableSelectSuite extends FunSuite with Mounted {

  private val zones = List(
    "America/New_York" -> "UTC-04:00 America/New_York",
    "UTC" -> "UTC+00:00 UTC",
    "Europe/Warsaw" -> "UTC+02:00 Europe/Warsaw",
    "Asia/Tokyo" -> "UTC+09:00 Asia/Tokyo"
  )

  private def control(selected: Var[String]): HtmlElement =
    SearchableSelect(Val(zones), selected, "Timezone", testId = Some("tz"))

  private def field(root: dom.Element): dom.html.Input =
    byTestId(root, "tz") match {
      case found: dom.html.Input => found
      case other => fail(s"expected an input, found ${other.tagName}")
    }

  private def rows(root: dom.Element): List[dom.Element] = {
    val found = root.querySelectorAll("li")
    (0 until found.length).map(found(_)).toList
  }

  private def typeInto(input: dom.html.Input, query: String): Unit = {
    input.value = query
    dispatch(input, new dom.Event("input", new dom.EventInit { bubbles = true }))
  }

  test("theClosedControlShowsTheChosenLabel") {
    mounted(control(Var("Europe/Warsaw")))(root => assertEquals(field(root).value, "UTC+02:00 Europe/Warsaw"))
  }

  test("typingFiltersByIdAndByOffset") {
    mounted(control(Var("UTC"))) { root =>
      val input = field(root)
      typeInto(input, "warsaw")
      assertEquals(rows(root).map(_.textContent), List("UTC+02:00 Europe/Warsaw"))

      // The offset is part of the label, so searching by it works without a second index.
      typeInto(input, "+09:00")
      assertEquals(rows(root).map(_.textContent), List("UTC+09:00 Asia/Tokyo"))
    }
  }

  test("nothingMatchedSaysSoOutLoud") {
    mounted(control(Var("UTC"))) { root =>
      typeInto(field(root), "atlantis")
      val empty = root.querySelector(s".${kui.ui.kernel.css.KernelCss.ComboboxEmpty}")
      assertEquals(attributeOf(empty, "role"), Some("status"))
      assert(!empty.hasAttribute("hidden"), "the no-match notice was hidden while nothing matched")
    }
  }

  test("clickingARowChoosesItAndClosesTheList") {
    val selected = Var("UTC")
    mounted(control(selected)) { root =>
      typeInto(field(root), "tokyo")
      dispatch(rows(root).head, new dom.MouseEvent("mousedown", new dom.MouseEventInit { bubbles = true }))
      assertEquals(selected.now(), "Asia/Tokyo")
      assertEquals(attributeOf(field(root), "aria-expanded"), Some("false"))
    }
  }

  test("theArrowKeysMoveTheActiveRowAndEnterTakesIt") {
    val selected = Var("UTC")
    mounted(control(selected)) { root =>
      val input = field(root)
      keyDown(input, "ArrowDown")
      keyDown(input, "ArrowDown")
      // Focus never leaves the field; which row is current is published as an id instead.
      assertEquals(attributeOf(input, "aria-activedescendant"), attributeOf(rows(root)(2), "id"))
      keyDown(input, "Enter")
      assertEquals(selected.now(), "Europe/Warsaw")
    }
  }

  test("escapeClosesWithoutChangingTheSelection") {
    val selected = Var("UTC")
    mounted(control(selected)) { root =>
      val input = field(root)
      typeInto(input, "tokyo")
      keyDown(input, "Escape")
      assertEquals(selected.now(), "UTC")
      assertEquals(field(root).value, "UTC+00:00 UTC")
    }
  }

  test("theActiveRowIsClampedWhenTheListShrinks") {
    mounted(control(Var("UTC"))) { root =>
      val input = field(root)
      keyDown(input, "ArrowDown")
      keyDown(input, "ArrowDown")
      keyDown(input, "ArrowDown")
      // Three rows narrowed to one: an index left pointing past the end would make Enter do nothing
      // for no visible reason.
      typeInto(input, "tokyo")
      assertEquals(attributeOf(input, "aria-activedescendant"), attributeOf(rows(root).head, "id"))
    }
  }
}
