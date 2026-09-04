package kui.ui.kernel.component

import com.raquo.laminar.api.L.*
import com.raquo.laminar.codecs.IntAsStringCodec
import org.scalajs.dom

import kui.kernel.Sort
import kui.ui.kernel.css.KernelCss
import kui.ui.kernel.theme.Density

/** A table that keeps only the visible rows in the document.
  *
  * ## Why this exists
  *
  * `DataTable` puts every row in the document, which is right for thirty brokers and wrong for ten thousand
  * topics: the browser lays out every row whether or not anyone can see it, and a list that size takes long
  * enough that a scroll visibly stutters. This component renders the rows the viewport can show plus a few
  * either side, and stands the rest in with two empty spacer rows whose heights add up to the space the
  * missing rows would have taken. The scrollbar is therefore the length of the whole list and the document
  * stays about twenty rows long, no matter how long the list is.
  *
  * ## The row height is given, not measured
  *
  * Measuring would allow variable-height rows, and would also make every scroll event read layout back out of
  * the browser. A fixed height turns the window into arithmetic — [[Window.slice]] — which is what makes the
  * frame budget in `docs/benchmarks/` achievable and, more importantly, *predictable*: a virtualizer whose
  * cost depends on its content is one that is fast in a test and slow on the one cluster that matters. The
  * height given here is also the height the stylesheet uses: it is written into a CSS custom property on the
  * root element, so the arithmetic and the layout cannot disagree. One number typed into two files is exactly
  * the class of defect M0 spent a day on.
  *
  * ## What this is not
  *
  * It is not a grid. No column resizing, no reordering, no grouping, no pinned columns, no cell editing, no
  * expandable rows. It takes a fixed `List[Column[A]]`, the same one `DataTable` takes. When a screen needs a
  * grid, the answer is to decide that a grid is a product feature and to build one, not to grow this until it
  * is a bad one.
  *
  * It also does no data fetching and no paging. It draws the rows it is handed. Paging is [[Pagination]], and
  * it is a sibling rather than a parent.
  *
  * ## Sorting belongs to the caller
  *
  * `sort` is a `Var` the caller owns and this component only writes to. It never reorders the rows it was
  * given. The screens that use it sort on the server — the topic list sorts ten thousand rows it has never
  * seen, of which it holds five hundred — and a table that quietly re-sorted its own page would disagree with
  * the page it is on, showing the right rows in an order no page boundary matches.
  *
  * ## Accessibility
  *
  * `aria-rowcount` is the length of the whole list and each row's `aria-rowindex` is its position in that
  * list, not in the window. Without absolute indices a screen reader on a list of ten thousand announces "row
  * 3 of 12", because twelve rows is all that is in the document — which is the single most misleading thing a
  * virtualized table can say. The spacer rows are `role="presentation"` and hidden from assistive technology;
  * they are layout, and announcing two empty rows around every window would be worse than useless.
  *
  * Keyboard: up and down move one row, Page Up and Page Down move a viewport's worth, Home and End go to the
  * ends. The focused row is scrolled into view, and because a focused row can be scrolled *out* of the window
  * and recycled out of the document, the focus is remembered as an index rather than as an element and is
  * reapplied when that row comes back.
  *
  * @param rowKey
  *   a stable identity per row. Rows are keyed by it rather than by position, so a re-sort moves the existing
  *   elements instead of destroying and rebuilding them — which is what keeps a text selection, an open
  *   tooltip and the keyboard focus where the user left them.
  * @param overscan
  *   rows rendered above and below the viewport. Three is enough to hide a fast scroll's repaint and small
  *   enough that the document stays short.
  * @param compact
  *   the design's density switch. It changes the row's vertical padding and nothing else — not the type size,
  *   not the control heights — because shrinking those makes an interface harder to hit, not denser. It
  *   defaults to the user's own preference (`Density.isCompact`) rather than to `false`, because the switch
  *   in Settings is a statement about every table in the product and not about one of them; a caller passes
  *   its own signal only where a table is deliberately exempt, and a test passes `Val(false)` to pin it.
  * @param viewportHeight
  *   the scroller's height in pixels. Supplied by the component itself from the real element in a browser;
  *   the parameter exists so a caller can both observe it and set it, which is what a jsdom suite and the
  *   benchmark harness need — jsdom performs no layout at all and reports every element as zero pixels tall,
  *   so a component that could only measure itself would be untestable outside a real browser.
  */
object VirtualizedTable {

  /** The default row height, in pixels, and the value the stylesheet falls back to.
    *
    * Forty-eight is not a taste: it is the design's comfortable row, spelled out. A cell's line box is the
    * `md` type size at the tight line height — 14px x 1.25, so 18px — and the design puts 15px of padding
    * above and below it. 15 + 18 + 15 = 48. The compact row keeps the same line box with the design's 9px
    * padding, so 9 + 18 + 9 = 36, which is why `compactSaving` below is twelve.
    */
  val DefaultRowHeight: Int = 48

  /** The custom property the row height is published through, so the arithmetic and the CSS share one number.
    */
  val RowHeightProperty: String = "--kui-vtable-row-height"

  /** The attribute the benchmark harness (TOP-037) drives the component through. Its value is the number of
    * rows the component was handed, which is what the harness asserts it actually mounted before it starts
    * timing scroll frames.
    */
  val BenchAttribute: String = "data-kui-bench"

  def apply[A](
      rows: Signal[List[A]],
      columns: List[Column[A]],
      rowKey: A => String,
      rowHeight: Int = DefaultRowHeight,
      overscan: Int = 3,
      compact: Signal[Boolean] = Density.isCompact,
      sort: Var[Option[Sort[String]]] = Var(None),
      emptyState: () => HtmlElement = () => EmptyState.default,
      testId: Option[String] = None,
      viewportHeight: Var[Int] = Var(0)
  ): HtmlElement = {

    val safeRowHeight = rowHeight.max(1)

    /** How much shorter a compact row is: six pixels off each of the density token's two edges.
      *
      * The design says compact changes the row's vertical padding from 15px to 9px and nothing else. In a
      * *windowed* table that cannot be only a stylesheet change, because the window arithmetic is done from
      * the row height: a stylesheet that shortened the rows without telling the arithmetic would leave the
      * component rendering a screenful of rows for a viewport that now holds a third more of them, and the
      * bottom of the list would be blank until the user scrolled. So the switch moves both numbers, from one
      * place, and the stylesheet reads the height back out of the custom property this sets.
      */
    val compactSaving = 12

    val rowHeightOf: Signal[Int] =
      compact.map(isCompact => if isCompact then (safeRowHeight - compactSaving).max(1) else safeRowHeight)

    val scrollTop = Var(0)
    val focused = Var(0)

    /** Whether the user is driving the table from the keyboard.
      *
      * Focus is only ever *taken* while this is true. Without it, a table that reapplies focus whenever its
      * focused row re-enters the window would snatch the caret away from whatever the user was typing in the
      * moment a background refresh moved a row.
      */
    val keyboardEngaged = Var(false)

    val total: Signal[Int] = rows.map(_.size)

    val slice: Signal[Window.Slice] =
      Signal
        .combine(scrollTop.signal, viewportHeight.signal, total, rowHeightOf)
        .map((top, height, count, rowPx) => Window.slice(top, height, rowPx, overscan, count))

    /** The rows in the window, each carrying the index it has in the whole list. */
    val windowed: Signal[List[Windowed[A]]] =
      rows
        .combineWith(slice)
        .map { (all, cut) =>
          all.iterator
            .slice(cut.firstIndex, cut.endIndex)
            .zipWithIndex
            .map((row, offset) => Windowed(cut.firstIndex + offset, rowKey(row), row))
            .toList
        }

    // The element reference is captured on mount so that keyboard navigation can move the scroll position.
    // Setting `scrollTop` on the element does not raise a `scroll` event in every environment, so the Var is
    // written directly alongside it; the two are only ever set together, here.
    var scroller: Option[dom.Element] = None

    def scrollTo(px: Int): Unit = {
      val clamped = px.max(0)
      scroller.foreach(_.scrollTop = clamped.toDouble)
      scrollTop.set(clamped)
    }

    /** Moves the focused row and brings it into view, one row of margin either side so the row that is being
      * moved *towards* is visible before it is reached.
      */
    def moveFocus(to: Int, count: Int, height: Int, rowPx: Int): Unit =
      if count > 0 then {
        val target = to.max(0).min(count - 1)
        val top = target * rowPx
        val currentTop = scrollTop.now()
        keyboardEngaged.set(true)
        focused.set(target)
        if top < currentTop then scrollTo(top)
        else if top + rowPx > currentTop + height then scrollTo(top + rowPx - height)
      }

    /** Arrow keys, Page Up and Page Down, Home and End.
      *
      * A page is a viewport's worth of rows and at least one, so a table in a very short container still
      * moves when Page Down is pressed rather than appearing to ignore the key.
      */
    def handleKey(event: dom.KeyboardEvent, count: Int, current: Int, height: Int, rowPx: Int): Unit = {
      val pageStep = (height / rowPx).max(1)
      val target = event.key match {
        case "ArrowDown" => Some(current + 1)
        case "ArrowUp" => Some(current - 1)
        case "PageDown" => Some(current + pageStep)
        case "PageUp" => Some(current - pageStep)
        case "Home" => Some(0)
        case "End" => Some(count - 1)
        case _ => None
      }
      target.foreach { to =>
        // Otherwise the browser scrolls the page as well, and the table jumps twice for one key.
        event.preventDefault()
        moveFocus(to, count, height, rowPx)
      }
    }

    def spacer(heightPx: Signal[Int]): HtmlElement =
      tr(
        cls := KernelCss.VirtualTableSpacer,
        role := "presentation",
        aria.hidden := true,
        td(colSpan := columns.size.max(1), styleAttr <-- heightPx.map(px => s"height: ${px}px; padding: 0"))
      )

    def cell(column: Column[A], rowSignal: Signal[A]): HtmlElement =
      td(
        cls := KernelCss.TableCell,
        Option.when(column.align == ColumnAlign.Numeric)(cls := KernelCss.TableCellNumeric),
        child <-- rowSignal.map(row => span(column.render(row)))
      )

    def rowElement(itemSignal: Signal[Windowed[A]]): HtmlElement = {
      val index = itemSignal.map(_.index)
      val isFocused = index.combineWith(focused.signal).map(_ == _)
      tr(
        cls := KernelCss.TableRow,
        cls := KernelCss.VirtualTableRow,
        // Absolute, not relative to the window: see the class comment.
        ariaRowIndex <-- index.map(_ + 1),
        // A roving tab index: exactly one row is in the tab order, so Tab enters the table once and the
        // arrow keys do the rest. Every other row is reachable but not tabbable.
        tabIndex <-- isFocused.map(if _ then 0 else -1),
        onFocus.compose(_.sample(index)) --> { at => focused.set(at) },
        columns.map(column => cell(column, itemSignal.map(_.row))),
        inContext { element =>
          isFocused --> { shouldHold =>
            if shouldHold && keyboardEngaged.now() && !element.ref.contains(dom.document.activeElement) then
              element.ref.focus()
          }
        }
      )
    }

    div(
      cls := KernelCss.VirtualTable,
      cls(KernelCss.VirtualTableCompact) <-- compact,
      styleAttr <-- rowHeightOf.map(rowPx => s"$RowHeightProperty: ${rowPx}px"),
      Components.testIdAttr(testId),
      div(
        cls := KernelCss.VirtualTableScroller,
        onMountCallback { context =>
          scroller = Some(context.thisNode.ref)
          val measured = context.thisNode.ref.clientHeight
          if measured > 0 then viewportHeight.set(measured)
        },
        onUnmountCallback(_ => scroller = None),
        onScroll.mapTo(()) --> { _ => scroller.foreach(element => scrollTop.set(element.scrollTop.toInt)) },
        onKeyDown.compose(_.withCurrentValueOf(total, focused.signal, viewportHeight.signal, rowHeightOf)) -->
          { (event, count, current, height, rowPx) => handleKey(event, count, current, height, rowPx) },
        table(
          cls := KernelCss.Table,
          dataAttr("kui-bench") <-- total.map(_.toString),
          ariaRowCount <-- total,
          thead(tr(columns.map(column => DataTable.headerCell(column, sort)))),
          tbody(
            cls := KernelCss.TableBody,
            spacer(slice.map(_.offsetPx)),
            children <-- windowed.split(_.key)((_, _, itemSignal) => rowElement(itemSignal)),
            spacer(
              slice
                .combineWith(rowHeightOf)
                .map((cut, rowPx) => (cut.totalHeightPx - cut.offsetPx - cut.count * rowPx).max(0))
            ),
            // The empty state sits inside the table so the header stays above it and the columns still say
            // what the table would have held. A header with nothing under it and no sentence is the
            // rendering that leaves a user unable to tell "no topics" from "the request failed".
            child.maybe <-- total.map(count =>
              Option.when(count == 0)(
                tr(td(colSpan := columns.size.max(1), cls := KernelCss.VirtualTableEmpty, emptyState()))
              )
            )
          )
        )
      )
    )
  }

  /** `aria-rowcount` and `aria-rowindex`. Laminar's `aria` object does not define either, because both exist
    * for exactly this situation — a table whose document holds fewer rows than the table has — and Laminar
    * has no virtualized table. They are spelled out here rather than written as raw strings at the use site,
    * for the same reason every class name is a constant: a typo in an attribute name produces an element that
    * is merely silently unannounced.
    */
  private val ariaRowCount = htmlAttr("aria-rowcount", IntAsStringCodec)
  private val ariaRowIndex = htmlAttr("aria-rowindex", IntAsStringCodec)

  /** One row plus where it sits in the whole list. */
  final private case class Windowed[A](index: Int, key: String, row: A)
}
