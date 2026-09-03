package kui.ui.kernel.component

import com.raquo.laminar.api.L.*
import com.raquo.laminar.codecs.StringAsIsCodec

import kui.kernel.{Sort, SortOrder}
import kui.ui.kernel.css.KernelCss

/** How a column's cells line up.
  *
  * Only two, because only two are ever right. Text is read along a row and starts at the same left edge in
  * every one of them; numbers are compared *down* a column and only line up if their units digits do.
  * Centring is the third option and it is the wrong one for both: it puts nothing in a predictable place.
  */
enum ColumnAlign {

  /** The default: text, chips, names, anything a reader scans left to right. */
  case Start

  /** Right-aligned with tabular figures, so "1,204" and "18,220" stack digit over digit. The whole column
    * takes the alignment, header included, and so does a missing value's em dash — it lands where the number
    * it replaced would have been rather than drifting left.
    */
  case Numeric
}

/** One column of a `DataTable`.
  *
  * @param id
  *   the column's stable name. It is what goes in the sort state and therefore what ends up in the URL, so it
  *   must not change when the header text does.
  * @param render
  *   how to draw one row's cell. Returns a `Modifier`, so a cell can be plain text, a `Tag`, a `Button`, or
  *   several of those.
  * @param width
  *   any CSS length, or `None` to let the browser decide.
  * @param align
  *   how the column lines up. Set `Numeric` for offsets, counts, sizes and rates.
  */
final case class Column[A](
    id: String,
    header: String,
    render: A => Modifier[HtmlElement],
    sortable: Boolean = false,
    width: Option[String] = None,
    align: ColumnAlign = ColumnAlign.Start
)

/** A plain, non-virtualized table.
  *
  * ## What this is for, and what it is not
  *
  * Lists of tens or a few hundred rows: brokers, consumer groups, schema versions, connectors. Every row is
  * in the DOM. `VirtualizedTable` arrives in M2 for message browsing, where the row count is unbounded and
  * only the visible window can be rendered; until then, "how many rows can this hold" has an honest answer —
  * as many as the browser can lay out — rather than a hidden cliff.
  *
  * ## Rows are keyed, so re-sorting does not rebuild them
  *
  * Rows are rendered with Laminar's `split`, which matches each item to its existing element by `rowKey`. A
  * list that arrives reordered, or with one row's numbers updated, moves and updates the existing elements
  * instead of destroying and recreating them. That is not only faster: a rebuilt row loses focus, loses a
  * text selection, and closes anything the user had expanded.
  *
  * ## Accessibility contract
  *
  * A real `<table>` with `<th scope="col">` headers, so a screen reader can say which column a cell belongs
  * to. A sortable header is a `<button>` inside the `<th>` — the header itself is not clickable, because a
  * clickable `<th>` is invisible to the keyboard — and the `<th>` carries `aria-sort` naming the current
  * direction.
  *
  * ## Loading and empty
  *
  * While loading, the header stays and the previous rows stay, dimmed. Replacing them with a spinner would
  * collapse the table to nothing and jump the whole page, and then jump it back. When there are genuinely no
  * rows, the `empty` element replaces the body and the header stays, so the columns still say what the table
  * would have contained.
  *
  * A cell whose value is missing renders `—`. An empty cell is ambiguous — zero, unmeasured, or failed — and
  * the em dash says "no value here" out loud. Use `DataTable.missing` for it.
  */
object DataTable {

  /** What a missing value looks like in a cell. Every screen in KUI uses this and not a blank. */
  val missing: String = "—"

  def apply[A](
      columns: List[Column[A]],
      rows: Signal[List[A]],
      rowKey: A => String,
      sort: Var[Option[Sort[String]]] = Var(None),
      loading: Signal[Boolean] = Val(false),
      empty: () => HtmlElement = () => EmptyState.default,
      testId: Option[String] = None
  ): HtmlElement = {

    /** Clicking a header cycles ascending, descending, then back to unsorted.
      *
      * The third state matters: without it there is no way back to the server's natural order, which for a
      * list of brokers is broker id and for a list of messages is offset.
      */
    def nextSort(current: Option[Sort[String]], columnId: String): Option[Sort[String]] =
      current match {
        case Some(Sort(`columnId`, SortOrder.Asc)) => Some(Sort(columnId, SortOrder.Desc))
        case Some(Sort(`columnId`, SortOrder.Desc)) => None
        case _ => Some(Sort(columnId, SortOrder.Asc))
      }

    def ariaSort(current: Option[Sort[String]], columnId: String): String =
      current match {
        case Some(Sort(`columnId`, SortOrder.Asc)) => "ascending"
        case Some(Sort(`columnId`, SortOrder.Desc)) => "descending"
        case _ => "none"
      }

    def headerCell(column: Column[A]): HtmlElement =
      th(
        columnScope := "col",
        cls := KernelCss.TableHeaderCell,
        Option.when(column.align == ColumnAlign.Numeric)(cls := KernelCss.TableHeaderCellNumeric),
        column.width.map(value => styleAttr := s"width: $value"),
        aria.sort <-- sort.signal.map(current => ariaSort(current, column.id)),
        if column.sortable then
          button(
            tpe := "button",
            cls := KernelCss.TableSortButton,
            column.header,
            child <-- sort.signal.map {
              case Some(Sort(column.id, SortOrder.Asc)) => Icon.chevronUp
              case Some(Sort(column.id, SortOrder.Desc)) => Icon.chevronDown
              // A placeholder of the same size, so a column does not shift when it becomes sorted.
              case _ => span(cls := KernelCss.TableSortPlaceholder, aria.hidden := true)
            },
            onClick.compose(_.sample(sort.signal)) --> { current => sort.set(nextSort(current, column.id)) }
          )
        else column.header
      )

    table(
      cls := KernelCss.Table,
      cls(KernelCss.TableLoading) <-- loading,
      aria.busy <-- loading,
      Components.testIdAttr(testId),
      thead(tr(columns.map(headerCell))),
      tbody(
        cls := KernelCss.TableBody,
        children <-- rows.split(rowKey)((_, _, rowSignal) =>
          tr(
            cls := KernelCss.TableRow,
            columns.map(column =>
              td(
                cls := KernelCss.TableCell,
                Option.when(column.align == ColumnAlign.Numeric)(cls := KernelCss.TableCellNumeric),
                child <-- rowSignal.map(row => span(column.render(row)))
              )
            )
          )
        ),
        // The empty state sits inside the table so that the header stays above it and the columns
        // still say what the table would have held.
        child.maybe <-- rows.map(currentRows =>
          Option.when(currentRows.isEmpty)(
            tr(td(colSpan := columns.size.max(1), cls := KernelCss.TableEmpty, empty()))
          )
        )
      )
    )
  }

  /** `<th scope="col">`. Laminar has no built-in key for it, so it is spelled out. Without it a screen reader
    * has to guess whether a header describes its column or its row.
    */
  private val columnScope = htmlAttr("scope", StringAsIsCodec)
}
