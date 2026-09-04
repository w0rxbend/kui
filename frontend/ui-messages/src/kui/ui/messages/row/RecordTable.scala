package kui.ui.messages.row

import com.raquo.laminar.api.L.*

import kui.contracts.message.DecodedPayloadDto
import kui.message.contract.MessageDto
import kui.ui.kernel.component.Components
import kui.ui.kernel.css.KernelCss
import kui.ui.kernel.time.Timestamps
import kui.ui.messages.{Messages, MessagesCss}

/** The record table: offset, partition, key, timestamp and value, and a row that opens where it is.
  *
  * ## Why this is not `DataTable`
  *
  * The kernel's table renders one `<tr>` per row and has no notion of a row that opens. A record's detail
  * belongs *inside* the table — a second `<tr>` whose single cell spans every column — because that is what
  * keeps the disclosure attached to the row it belongs to as the table scrolls, and what lets a screen reader
  * read the detail immediately after the summary it expands. Bolting that onto the shared component for one
  * screen would put a rarely-used mode into a primitive four other features depend on.
  *
  * Everything else it copies deliberately: the same `<table>` shape, the same header cells, the same class
  * names from `KernelCss`, so the two tables look like one product rather than two.
  *
  * ## The value column is one line, always
  *
  * A JSON payload can be twenty kilobytes. Rendered in full it would make one row taller than the viewport
  * and push every other record off the screen, so the cell holds a single line, clipped, and the whole
  * document is one click away in the detail. Truncating in the cell rather than wrapping is what keeps rows
  * the same height and therefore keeps the table scannable.
  */
object RecordTable {

  /** How many columns the header declares. Named once, because two cells span the whole width and a number
    * that disagrees with the header is a layout bug nobody sees until a browser has to guess.
    */
  private val ColumnCount = 5

  /** How much of a payload a summary cell shows before it is clipped. Long enough for a JSON object's first
    * few fields, which is what tells a reader whether this is the record they are looking for.
    */
  val PreviewLength: Int = 160

  def apply(
      records: Signal[List[MessageDto]],
      zone: Signal[String],
      empty: Signal[HtmlElement],
      testId: Option[String] = None,
      actions: MessageDto => List[HtmlElement] = _ => Nil
  ): HtmlElement = {

    /** Which records are open, by key.
      *
      * A set rather than a single "open row", because comparing two records is the common act and closing one
      * to look at another makes that impossible. It holds keys and not records, so a row that is re-delivered
      * — a live tail redrawing — stays open.
      */
    val open: Var[Set[String]] = Var(Set.empty)

    table(
      cls := KernelCss.Table,
      cls := MessagesCss.Table,
      Components.testIdAttr(testId),
      thead(
        tr(
          th(scrollColumn, Messages.ColumnOffset),
          th(scrollColumn, Messages.ColumnPartition),
          th(Messages.ColumnKey),
          th(Messages.ColumnTimestamp),
          th(Messages.ColumnValue)
        )
      ),
      tbody(
        cls := KernelCss.TableBody,
        children <-- records
          .combineWith(zone)
          .map((rows, zoneId) => rows.map(record => rowsFor(record, zoneId, open, actions)))
          .map(_.flatten)
      ),
      // The empty state lives under the table so that the header stays above it and the columns still say
      // what the table would have held.
      //
      // In its own `tbody`, and in a cell spanning every column. It used to be attached straight to the
      // `<table>`, which is not somewhere HTML allows an element to be: the browser recovers by hoisting it
      // out, and the result was the words "No records yet" stacked one per line inside the width of the
      // Offset column -- the first thing anybody saw on the message browser.
      tbody(
        child.maybe <-- records
          .combineWith(empty)
          .map((rows, state) =>
            Option.when(rows.isEmpty)(
              tr(td(colSpan := ColumnCount, cls := MessagesCss.EmptyCell, state))
            )
          )
      )
    )
  }

  /** The summary row, and the detail row when it is open. */
  private def rowsFor(
      record: MessageDto,
      zone: String,
      open: Var[Set[String]],
      actions: MessageDto => List[HtmlElement]
  ): List[HtmlElement] = {
    val key = keyOf(record)
    val isOpen = open.signal.map(_.contains(key))

    val summary =
      tr(
        cls := KernelCss.TableRow,
        cls := MessagesCss.Row,
        cls(MessagesCss.RowOpen) <-- isOpen,
        dataAttr("testid") := s"record-$key",
        td(
          cls := KernelCss.TableCell,
          cls := KernelCss.TableCellNumeric,
          // The toggle is a real <button> carrying `aria-expanded`, so the keyboard reaches it and a screen
          // reader says whether the record is open. A clickable row would be invisible to both.
          button(
            tpe := "button",
            cls := MessagesCss.Toggle,
            dataAttr("testid") := s"record-$key-toggle",
            aria.expanded <-- isOpen,
            aria.label <-- isOpen.map(current => if current then Messages.Collapse else Messages.Expand),
            record.offset.value.toString,
            onClick --> { _ =>
              open.update(current => if current.contains(key) then current - key else current + key)
            }
          )
        ),
        td(cls := KernelCss.TableCell, cls := KernelCss.TableCellNumeric, record.partition.value.toString),
        td(cls := KernelCss.TableCell, cls := MessagesCss.Key, preview(record.key, Messages.NoKey)),
        td(
          cls := KernelCss.TableCell,
          // The absolute time in the reader's own zone, with the clock that stamped it on the title: a
          // timestamp search that finds nothing is inexplicable unless you know the broker may have
          // overwritten the producer's clock on append.
          title := Messages.timestampType(record.timestampType),
          Timestamps.absolute(record.timestamp, zone)
        ),
        td(cls := KernelCss.TableCell, cls := MessagesCss.Value, preview(record.value, Messages.Tombstone))
      )

    val detail =
      tr(
        cls := KernelCss.TableRow,
        dataAttr("testid") := s"record-$key-detail-row",
        // Hidden rather than absent, so opening a record does not rebuild the row above it and lose a text
        // selection the reader had made in it.
        hidden <-- isOpen.map(!_),
        td(
          colSpan := ColumnCount,
          cls := KernelCss.TableCell,
          child <-- isOpen.map(current => if current then RecordDetail(record, actions) else emptyNode)
        )
      )

    List(summary, detail)
  }

  /** A record's identity: partition and offset, which is the only pair Kafka guarantees unique. */
  private[messages] def keyOf(record: MessageDto): String =
    s"${record.partition.value}-${record.offset.value}"

  /** One line of a payload for the summary cell.
    *
    * An absent payload is words, never an empty cell: "no key" and "tombstone" are facts about the record,
    * and a blank cell reads as a rendering bug. Newlines are collapsed so that a multi-line JSON document
    * does not turn one row into thirty.
    */
  private[messages] def preview(decoded: DecodedPayloadDto, absent: String): String =
    if decoded.kind == DecodedPayloadDto.Kind.Absent then absent
    else {
      val flattened = decoded.text.replaceAll("\\s+", " ").trim
      if flattened.length <= PreviewLength then flattened
      else s"${flattened.take(PreviewLength)}…"
    }

  /** The two narrow numeric columns. Named once so the two headers cannot drift apart. */
  private def scrollColumn: Modifier[HtmlElement] = width := "9rem"
}
