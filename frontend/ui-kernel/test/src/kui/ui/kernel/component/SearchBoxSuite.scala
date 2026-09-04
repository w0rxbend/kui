package kui.ui.kernel.component

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.scalajs.dom

import kui.kernel.search.SearchMode

final class SearchBoxSuite extends FunSuite with Mounted {

  /** The debounce is real, so the assertions have to be too: these cases wait for a timer rather than
    * replacing it with a fake, because the timer is what is under test.
    */
  private val debounce = 20.millis

  private def mountedAsync(element: HtmlElement)(check: dom.Element => Future[Unit]): Future[Unit] = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container): Unit
    val root = render(container, element)
    check(element.ref).transform { outcome =>
      root.unmount(): Unit
      dom.document.body.removeChild(container): Unit
      outcome
    }
  }

  private def afterTheDebounce: Future[Unit] = {
    val promise = scala.concurrent.Promise[Unit]()
    dom.window.setTimeout(() => promise.success(()), (debounce * 5).toMillis.toDouble): Unit
    promise.future
  }

  private def typeInto(root: dom.Element, text: String): Unit = {
    val field = byTestId(root, "search-input").asInstanceOf[dom.html.Input]
    field.value = text
    dispatch(field, new dom.Event("input", new dom.EventInit { bubbles = true }))
  }

  private def box(
      value: Var[String],
      onQuery: String => Unit,
      mode: Option[(Signal[SearchMode], SearchMode => Unit)] = None
  ): HtmlElement =
    SearchBox(value.signal, onQuery, "Search topics", debounce, mode, testId = Some("search"))

  test("typingFiresOnceAfterTheDebounce") {
    // Three keystrokes, one call. Without this a ten-character query is ten requests, nine of whose
    // answers are thrown away.
    var fired = List.empty[String]
    val value = Var("")
    mountedAsync(box(value, query => fired = fired :+ query)) { root =>
      List("o", "or", "ord").foreach { text =>
        value.set(text)
        typeInto(root, text)
      }
      assertEquals(fired, Nil, "nothing may fire before the debounce elapses")
      afterTheDebounce.map(_ => assertEquals(fired, List("ord")))
    }
  }

  test("theQueryIsTrimmed") {
    var fired = List.empty[String]
    val value = Var("")
    mountedAsync(box(value, query => fired = fired :+ query)) { root =>
      value.set("  orders  ")
      typeInto(root, "  orders  ")
      afterTheDebounce.map(_ => assertEquals(fired, List("orders")))
    }
  }

  test("clearingFiresWithTheEmptyString") {
    // "No filter" is a request, not the absence of one: a clear button that fires nothing leaves the
    // previous filter applied, and the user reads the button as broken.
    var fired = List.empty[String]
    val value = Var("orders")
    mounted(box(value, query => fired = fired :+ query)) { root =>
      click(byTestId(root, "search-clear"))
      // Not debounced: a click is already one event.
      assertEquals(fired, List(""))
    }
  }

  test("theClearButtonIsAbsentWhenThereIsNothingToClear") {
    mounted(box(Var(""), _ => ())) { root =>
      assertEquals(Option(root.querySelector("[data-testid='search-clear']")), None)
    }
  }

  test("theModeToggleFiresAndIsAbsentWhenNotConfigured") {
    mounted(box(Var(""), _ => ())) { root =>
      assertEquals(Option(root.querySelector("[data-mode]")), None)
    }

    var fired = List.empty[SearchMode]
    val mode = Var(SearchMode.Plain)
    mounted(box(Var(""), _ => (), Some((mode.signal, chosen => fired = fired :+ chosen)))) { root =>
      val fuzzy = root.querySelector("[data-mode='fts']")
      assertEquals(attributeOf(fuzzy, "aria-pressed"), Some("false"))
      click(fuzzy)
      assertEquals(fired, List(SearchMode.Fts))

      mode.set(SearchMode.Fts)
      assertEquals(attributeOf(fuzzy, "aria-pressed"), Some("true"))
      assertEquals(attributeOf(root.querySelector("[data-mode='plain']"), "aria-pressed"), Some("false"))
    }
  }

  test("theFieldIsLabelledAndTheClearButtonHasAnAccessibleName") {
    mounted(box(Var("orders"), _ => ())) { root =>
      val field = byTestId(root, "search-input")
      val label = Option(root.querySelector("label")).getOrElse(fail("no label"))
      assertEquals(attributeOf(label, "for"), attributeOf(field, "id"))
      assert(label.textContent.nonEmpty)
      assertEquals(attributeOf(byTestId(root, "search-clear"), "aria-label"), Some("Clear search"))
    }
  }
}
