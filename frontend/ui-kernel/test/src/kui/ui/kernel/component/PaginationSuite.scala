package kui.ui.kernel.component

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.scalajs.dom

final class PaginationSuite extends FunSuite with Mounted {

  private def bar(
      page: Int,
      pageCount: Int,
      pageSize: Int = 25,
      onPage: Int => Unit = _ => (),
      onPageSize: Int => Unit = _ => ()
  ): HtmlElement =
    Pagination(Val(page), Val(pageCount), Val(pageSize), onPage, onPageSize, testId = Some("pager"))

  private def button(root: dom.Element, name: String): dom.html.Button =
    byTestId(root, s"pager-$name").asInstanceOf[dom.html.Button]

  test("nothingRendersForASinglePage") {
    // A disabled pagination bar under a list of three rows is chrome that teaches the user nothing.
    mounted(bar(page = 1, pageCount = 1)) { root =>
      assertEquals(root.childElementCount, 0)
    }
    mounted(bar(page = 1, pageCount = 0)) { root =>
      assertEquals(root.childElementCount, 0)
    }
  }

  test("firstAndPreviousAreDisabledOnPageOne") {
    mounted(bar(page = 1, pageCount = 5)) { root =>
      assert(button(root, "first").disabled)
      assert(button(root, "prev").disabled)
      assert(!button(root, "next").disabled)
      assert(!button(root, "last").disabled)
    }
  }

  test("lastAndNextOnTheLastPage") {
    mounted(bar(page = 5, pageCount = 5)) { root =>
      assert(!button(root, "first").disabled)
      assert(button(root, "next").disabled)
      assert(button(root, "last").disabled)
    }
  }

  test("theStepsFireTheRightPage") {
    var fired = List.empty[Int]
    mounted(bar(page = 3, pageCount = 5, onPage = page => fired = fired :+ page)) { root =>
      click(button(root, "first"))
      click(button(root, "prev"))
      click(button(root, "next"))
      click(button(root, "last"))
      assertEquals(fired, List(1, 2, 4, 5))
    }
  }

  test("theLabelReadsPageXOfY") {
    mounted(bar(page = 3, pageCount = 7)) { root =>
      assertEquals(byTestId(root, "pager-label").textContent, "Page 3 of 7")
    }
  }

  test("goToPageRejectsOutOfRangeInput") {
    // And does not fire. Clamping 900 to 12 would show a page the user did not ask for and let them
    // believe they got the one they typed.
    var fired = List.empty[Int]
    mounted(bar(page = 1, pageCount = 12, onPage = page => fired = fired :+ page)) { root =>
      def submit(typed: String): Unit = {
        val field = byTestId(root, "pager-jump").asInstanceOf[dom.html.Input]
        field.value = typed
        dispatch(field, new dom.Event("input", new dom.EventInit { bubbles = true }))
        dispatch(field.form, new dom.Event("submit", new dom.EventInit { bubbles = true; cancelable = true }))
      }

      submit("900")
      submit("0")
      submit("-3")
      submit("not a number")
      assertEquals(fired, Nil)

      submit("7")
      assertEquals(fired, List(7))
    }
  }

  test("changingPageSizeFires") {
    var fired = List.empty[Int]
    mounted(bar(page = 1, pageCount = 5, onPageSize = size => fired = fired :+ size)) { root =>
      val select = byTestId(root, "pager-size").asInstanceOf[dom.html.Select]
      assertEquals(select.value, "25")
      select.value = "100"
      dispatch(select, new dom.Event("change", new dom.EventInit { bubbles = true }))
      assertEquals(fired, List(100))
    }
  }

  test("theStepsAreLabelledForAScreenReader") {
    mounted(bar(page = 2, pageCount = 5)) { root =>
      assertEquals(attributeOf(button(root, "next"), "aria-label"), Some("Next page"))
      assertEquals(attributeOf(byTestId(root, "pager-label"), "aria-live"), Some("polite"))
    }
  }
}
