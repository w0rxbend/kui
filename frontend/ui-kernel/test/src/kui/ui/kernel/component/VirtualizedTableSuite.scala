package kui.ui.kernel.component

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.scalajs.dom

import kui.kernel.{Sort, SortOrder}
import kui.ui.kernel.css.KernelCss

/** The component, in a document.
  *
  * ## Why the viewport height is set by hand here
  *
  * jsdom parses HTML and runs script but performs no layout at all: every element reports a `clientHeight` of
  * zero, forever. A component that could only learn its own height by measuring itself would therefore render
  * an empty window in every test in this file, and the tests would pass while asserting nothing. So
  * `VirtualizedTable` takes the height as a `Var` it normally fills in from the real element, and these tests
  * fill it in themselves — which is also exactly what the benchmark harness does.
  */
final class VirtualizedTableSuite extends FunSuite with Mounted {

  private final case class Row(id: String, name: String, size: Long)

  private def sample(count: Int): List[Row] =
    List.tabulate(count)(index => Row(s"row-$index", f"topic-$index%05d", index.toLong * 100L))

  private val columns: List[Column[Row]] = List(
    Column[Row]("name", "Name", row => row.name, sortable = true),
    Column[Row]("size", "Size", row => row.size.toString, sortable = true, align = ColumnAlign.Numeric)
  )

  private def table(
      rows: List[Row],
      viewport: Var[Int] = Var(600),
      sort: Var[Option[Sort[String]]] = Var(None),
      compact: Signal[Boolean] = Val(false),
      rowHeight: Int = 40
  ): HtmlElement =
    VirtualizedTable(
      rows = Val(rows),
      columns = columns,
      rowKey = _.id,
      rowHeight = rowHeight,
      compact = compact,
      sort = sort,
      testId = Some("vtable"),
      viewportHeight = viewport
    )

  /** Only the real rows. The two spacers are layout and are excluded by their class, which is also how a
    * screen reader sees them: `role="presentation"`.
    */
  private def dataRows(root: dom.Element): List[dom.Element] =
    root.querySelectorAll(s".${KernelCss.VirtualTableRow}").toList.map(_.asInstanceOf[dom.Element])

  private def scroller(root: dom.Element): dom.Element =
    root.querySelector(s".${KernelCss.VirtualTableScroller}")

  /** Scrolls the way a user does: move the position, then let the element tell the component about it. */
  private def scrollTo(root: dom.Element, top: Int): Unit = {
    val element = scroller(root)
    element.scrollTop = top.toDouble
    dispatch(element, new dom.Event("scroll"))
  }

  test("onlyTheWindowIsInTheDocument") {
    // The whole point of the component. Ten thousand rows, and the document holds a screenful.
    mounted(table(sample(10_000))) { root =>
      assert(dataRows(root).size < 30, s"expected a window, found ${dataRows(root).size} rows")
      assert(dataRows(root).nonEmpty, "expected the window not to be empty")
    }
  }

  test("ariaRowIndexIsAbsolute") {
    // "Row 3 of 12" on a list of ten thousand is the most misleading thing a virtualized table can say.
    mounted(table(sample(10_000))) { root =>
      val grid = root.querySelector("table")
      assertEquals(attributeOf(grid, "aria-rowcount"), Some("10000"))

      scrollTo(root, 200_000)
      val firstRendered = dataRows(root).head
      val announced = attributeOf(firstRendered, "aria-rowindex").map(_.toInt).getOrElse(0)
      // 200 000px at 40px a row is row 5 000; the overscan puts the first rendered row a little above it.
      assert(announced > 4_900 && announced <= 5_001, s"announced row $announced")
    }
  }

  test("scrollingReplacesTheRenderedRows") {
    mounted(table(sample(10_000))) { root =>
      val before = dataRows(root).map(_.textContent)
      scrollTo(root, 100_000)
      val after = dataRows(root).map(_.textContent)
      assertNotEquals(before, after)
      assert(after.exists(_.contains("topic-02500")), s"expected the rows around 2 500, got $after")
    }
  }

  test("sortingAColumnUpdatesTheSortVarAndDoesNotResortLocally") {
    // The screens that use this sort on the server. A table that re-sorted its own page would show the right
    // rows in an order that no page boundary matches.
    val sort = Var(Option.empty[Sort[String]])
    val rows = sample(50)
    mounted(table(rows, sort = sort)) { root =>
      val header = root.querySelectorAll(s".${KernelCss.TableSortButton}").head.asInstanceOf[dom.Element]
      val orderBefore = dataRows(root).map(_.textContent)
      click(header)
      assertEquals(sort.now(), Some(Sort("name", SortOrder.Asc)))
      assertEquals(dataRows(root).map(_.textContent), orderBefore)
    }
  }

  test("theHeaderStaysWhileTheBodyScrolls") {
    // The header lives outside the scrolled rows in the document and is made sticky by the stylesheet; what
    // is asserted here is the part jsdom can see, which is that scrolling does not remove or replace it.
    mounted(table(sample(10_000))) { root =>
      val headerBefore = root.querySelector("thead")
      scrollTo(root, 50_000)
      assert(root.querySelector("thead") eq headerBefore, "the header row was rebuilt by a scroll")
      assertEquals(root.querySelectorAll("thead th").length, 2)
    }
  }

  test("keyboardNavigationMovesFocusAndScrolls") {
    mounted(table(sample(10_000))) { root =>
      val element = scroller(root)
      keyDown(element, "ArrowDown")
      assertEquals(attributeOf(dom.document.activeElement, "aria-rowindex"), Some("2"))

      keyDown(dom.document.activeElement, "PageDown")
      // A 600px viewport at 40px a row is fifteen rows; from row 2 that is row 17.
      assertEquals(attributeOf(dom.document.activeElement, "aria-rowindex"), Some("17"))

      keyDown(dom.document.activeElement, "End")
      assertEquals(attributeOf(dom.document.activeElement, "aria-rowindex"), Some("10000"))
      assert(element.scrollTop > 0, "End did not scroll the container")

      keyDown(dom.document.activeElement, "Home")
      assertEquals(attributeOf(dom.document.activeElement, "aria-rowindex"), Some("1"))
      assertEquals(element.scrollTop, 0.0)
    }
  }

  test("focusIsNotLostWhenTheFocusedRowScrollsOutAndBackIn") {
    // The focus is remembered as an index rather than as an element, precisely because the element is
    // destroyed when its row leaves the window.
    mounted(table(sample(10_000))) { root =>
      keyDown(scroller(root), "ArrowDown")
      assertEquals(attributeOf(dom.document.activeElement, "aria-rowindex"), Some("2"))

      scrollTo(root, 200_000)
      assert(
        !dataRows(root).exists(row => attributeOf(row, "aria-rowindex").contains("2")),
        "row 2 should have been recycled out of the document"
      )

      scrollTo(root, 0)
      assertEquals(attributeOf(dom.document.activeElement, "aria-rowindex"), Some("2"))
    }
  }

  test("compactChangesOnlyThePadding") {
    // Asserted on the class and the published row height, not on a pixel: jsdom lays nothing out.
    val compact = Var(false)
    mounted(table(sample(20), compact = compact.signal, rowHeight = 48)) { root =>
      assert(!root.classList.contains(KernelCss.VirtualTableCompact))
      assert(root.getAttribute("style").contains("48px"), root.getAttribute("style"))

      compact.set(true)
      assert(root.classList.contains(KernelCss.VirtualTableCompact))
      // 48 minus the twelve pixels the design's 15px-to-9px switch takes off the two edges.
      assert(root.getAttribute("style").contains("36px"), root.getAttribute("style"))
      assertEquals(root.querySelectorAll("thead th").length, 2)
    }
  }

  test("anEmptyListRendersTheEmptyStateNotAHeaderWithNothingUnderIt") {
    mounted(table(Nil)) { root =>
      assertEquals(dataRows(root).size, 0)
      assert(
        root.querySelector(s".${KernelCss.EmptyState}") != null,
        s"expected an empty state in ${root.outerHTML}"
      )
      // The header stays, so the columns still say what the table would have held.
      assertEquals(root.querySelectorAll("thead th").length, 2)
    }
  }

  test("rowsAreKeyedSoASortDoesNotRecreateThem") {
    val rows = Var(sample(10))
    val element = VirtualizedTable(
      rows = rows.signal,
      columns = columns,
      rowKey = _.id,
      viewportHeight = Var(600),
      testId = Some("vtable")
    )
    mounted(element) { root =>
      val before = dataRows(root)
      rows.set(sample(10).reverse)
      val after = dataRows(root)
      assertEquals(after.size, before.size)
      assert(before.forall(node => after.exists(_ eq node)), "a reorder destroyed and rebuilt the rows")
    }
  }

  test("theScrollbarIsTheLengthOfTheWholeListAndNotOfTheWindow") {
    // The spacers. Their two heights plus the rendered rows must add up to the full list, or the scrollbar
    // says the list is a screenful long.
    mounted(table(sample(1_000))) { root =>
      val spacers = root.querySelectorAll(s".${KernelCss.VirtualTableSpacer} td").toList
      assertEquals(spacers.size, 2)
      val heights = spacers.map(node => heightOf(node.asInstanceOf[dom.Element]))
      assertEquals(heights.sum + dataRows(root).size * 40, 40_000)
    }
  }

  test("theSpacersAreHiddenFromAssistiveTechnology") {
    mounted(table(sample(1_000))) { root =>
      val spacers = root.querySelectorAll(s".${KernelCss.VirtualTableSpacer}").toList
      spacers.foreach { node =>
        val row = node.asInstanceOf[dom.Element]
        assertEquals(attributeOf(row, "role"), Some("presentation"))
        assertEquals(attributeOf(row, "aria-hidden"), Some("true"))
      }
    }
  }

  test("theBenchmarkHookNamesHowManyRowsWereMounted") {
    // TOP-037's harness reads this before it starts timing frames, so that a benchmark cannot silently
    // measure a table that never received its rows.
    mounted(table(sample(10_000))) { root =>
      assertEquals(attributeOf(root.querySelector("table"), VirtualizedTable.BenchAttribute), Some("10000"))
    }
  }

  private def heightOf(element: dom.Element): Int =
    Option(element.getAttribute("style"))
      .flatMap(style => "height: (\\d+)px".r.findFirstMatchIn(style))
      .map(_.group(1).toInt)
      .getOrElse(0)
}
