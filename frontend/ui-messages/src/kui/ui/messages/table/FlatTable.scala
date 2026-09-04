package kui.ui.messages.table

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L.*

import kui.message.contract.MessageDto
import kui.ui.kernel.component.Components
import kui.ui.kernel.css.KernelCss
import kui.ui.kernel.time.Timestamps
import kui.ui.messages.{Messages, MessagesCss}

/** The table view: a page of records with their JSON spread across columns.
  *
  * ## What it is for
  *
  * The list view answers "what is in this record". The table view answers "which of these thousand records is
  * the one I want", and it answers it by making `V.order.status` a column you can scan down instead of a
  * substring buried in the middle of a clipped line. It is Kouncil's defining feature and the reason a
  * support engineer opens that product rather than a console consumer.
  *
  * ## Why the columns are computed from the records and not configured
  *
  * Because nobody knows the shape of a topic before they look at it, and a table that had to be configured
  * first would be a table nobody uses. [[JsonFlattener.columns]] takes every path any record on screen filled
  * and orders them by first appearance, which has the property this view needs above all others: adding
  * records never reorders the columns already on screen. A live tail therefore grows the table to the right
  * while the user reads, and never shuffles what they are reading.
  *
  * ## Why hidden columns rather than chosen ones
  *
  * The picker holds what is *hidden*, so a column that appears for the first time in the record that just
  * arrived is visible. The opposite — a set of chosen columns — would silently drop every new field, which on
  * a topic whose schema is changing is precisely the field the user is looking for.
  *
  * ## The caps
  *
  * All four come from [[FlattenLimits]] and every one of them is visible when it bites: a truncated subtree
  * is a cell holding the rest as compact JSON, a truncated array is a `+N more` cell, the row cap is stated
  * under the table, and the column cap is stated in the picker. A cap the user cannot see is a lie about
  * their data.
  */
object FlatTable {

  /** @param hidden
    *   the columns the user has put away, owned by the screen rather than by this component.
    *
    * It is a parameter because the export has to agree with the table: a file that came back with the columns
    * somebody had just hidden would be an export of a screen they are not looking at. Empty by default, and
    * never pruned when a column stops appearing — a column hidden on Monday should still be hidden when the
    * record that has that field comes back.
    */
  def apply(
      records: Signal[List[MessageDto]],
      zone: Signal[String],
      empty: Signal[HtmlElement],
      hidden: Var[Set[String]] = Var(Set.empty),
      limits: FlattenLimits = FlattenLimits.Default,
      testId: Option[String] = None
  ): HtmlElement = {

    val rows: Signal[Vector[(MessageDto, FlatRow)]] =
      records.map(current =>
        current
          .take(limits.maxRows.max(0))
          .toVector
          .map(record => record -> JsonFlattener.flatten(RecordSource.of(record), limits))
      )

    val columns: Signal[Vector[String]] =
      rows.map(current => JsonFlattener.columns(current.map(_._2), limits))

    val shown: Signal[Vector[String]] =
      columns.combineWith(hidden.signal).map((all, away) => all.filterNot(away.contains))

    div(
      cls := MessagesCss.Grid,
      Components.testIdAttr(testId),
      picker(columns, hidden),
      // The table scrolls inside its own box rather than widening the page. A table view of a wide record
      // has fifty columns by design, and a page that scrolls sideways takes the navigation and the controls
      // off the screen with it.
      div(
        cls := MessagesCss.GridScroll,
        table(
          cls := KernelCss.Table,
          cls := MessagesCss.GridTable,
          thead(
            tr(
              th(cls := MessagesCss.GridFixed, Messages.ColumnPartition),
              th(cls := MessagesCss.GridFixed, Messages.ColumnOffset),
              th(cls := MessagesCss.GridFixed, Messages.ColumnTimestamp),
              children <-- shown.map(_.map(path => th(cls := MessagesCss.GridPath, title := path, path)))
            )
          ),
          tbody(
            cls := KernelCss.TableBody,
            children <-- rows
              .combineWith(shown, zone)
              .map((current, paths, zoneId) => current.map((record, row) => line(record, row, paths, zoneId)))
          ),
          child.maybe <-- rows
            .combineWith(shown, empty)
            .map((current, paths, state) =>
              Option.when(current.isEmpty)(
                tbody(tr(td(colSpan := paths.size + FixedColumns, cls := MessagesCss.EmptyCell, state)))
              )
            )
        )
      ),
      // Stated rather than silent. A table that stopped at a thousand rows without saying so is a table
      // whose user believes their topic has a thousand records in it.
      child.maybe <-- records.map(current =>
        Option.when(current.size > limits.maxRows)(
          div(cls := MessagesCss.GridNote, Messages.rowCap(limits.maxRows, current.size))
        )
      )
    )
  }

  /** The three columns that are not flattened from the payload, so the header and the empty-state cell agree
    * on the width of the table.
    */
  private val FixedColumns: Int = 3

  private def line(
      record: MessageDto,
      row: FlatRow,
      paths: Vector[String],
      zone: String
  ): HtmlElement =
    tr(
      cls := MessagesCss.Row,
      dataAttr("testid") := s"grid-${record.partition.value}-${record.offset.value}",
      td(cls := MessagesCss.GridFixed, record.partition.value.toString),
      td(cls := MessagesCss.GridFixed, record.offset.value.toString),
      td(cls := MessagesCss.GridFixed, Timestamps.absolute(record.timestamp, zone)),
      // A record with nothing for a column renders an empty cell rather than shifting its neighbours
      // along, which is the whole reason the flattener returns a map keyed by path.
      paths.map(path =>
        td(
          cls := MessagesCss.GridCell,
          // The full text is in the tooltip, because a cell is one line by design and the value that got
          // clipped is often the one being looked for.
          row.cells.get(path).map(text => title := text),
          row.cells.getOrElse(path, "")
        )
      )
    )

  private def toggle(path: String, hidden: Var[Set[String]]): HtmlElement =
    L.label(
      cls := MessagesCss.ControlLabel,
      input(
        tpe := "checkbox",
        controlled(
          checked <-- hidden.signal.map(!_.contains(path)),
          onInput.mapToChecked --> { on =>
            hidden.update(current => if on then current - path else current + path)
          }
        )
      ),
      path
    )

  /** The column picker: every column the records produced, with a checkbox each.
    *
    * A `<details>` and not a drawer or a menu, because it is a list of up to a hundred and twenty checkboxes
    * that the user opens once, adjusts, and closes — and because a native disclosure is keyboard-navigable
    * and screen-reader-announced with no code.
    */
  private def picker(columns: Signal[Vector[String]], hidden: Var[Set[String]]): HtmlElement =
    detailsTag(
      cls := MessagesCss.GridPicker,
      summaryTag(
        dataAttr("testid") := "grid-columns",
        child.text <-- columns
          .combineWith(hidden.signal)
          .map((all, away) => Messages.columnCount(all.size - all.count(away.contains), all.size))
      ),
      div(
        cls := MessagesCss.GridPickerList,
        // Built from the column list alone, with each checkbox reading the hidden set through its own
        // signal. Rebuilding the list on every toggle would be simpler and would take the keyboard focus
        // off the checkbox the user just pressed, which makes the picker unusable without a mouse.
        children <-- columns.map(_.toList.map(path => toggle(path, hidden)))
      )
    )
}
