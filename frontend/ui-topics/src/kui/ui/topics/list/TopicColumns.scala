package kui.ui.topics.list

import com.raquo.laminar.api.L.*

import kui.kernel.ClusterId
import kui.topic.contract.TopicSortField
import kui.ui.kernel.component.*
import kui.ui.kernel.css.KernelCss
import kui.ui.topics.{Messages, TopicsCss}

/** The seven columns of the topic list, and every rendering rule that is about *one cell*.
  *
  * ## Two rules from the design, applied here and nowhere else
  *
  * **Quantities get a magnitude bar** scaled against the largest value among the rows currently displayed —
  * not against the cluster total and not against a fixed maximum. Digits are slow to compare; a bar drawn to
  * one scale down a column answers "which is the big one" before a single digit is read. The scale is a
  * `Signal` passed in, because only the page knows which rows are on screen.
  *
  * **A count that crosses a threshold changes colour**, and a count that has not does not. Colouring every
  * healthy zero teaches the eye to ignore the colour, which is the one thing the colour has to do.
  *
  * ## The sort ids are the wire's
  *
  * A column's `id` is what goes into the sort state and therefore into the URL and then into the query
  * string, so it is `TopicSortField.wire` rather than a string typed here. A renamed field breaks the build
  * instead of producing a 400 on the screen the milestone is judged on — the server refuses an unknown sort
  * rather than quietly ignoring it, which is the right behaviour and makes this the only safe way to name it.
  */
object TopicColumns {

  /** The star column has no sort field, so it needs an id of its own. It is never sent anywhere. */
  val FavouriteId: String = "favourite"

  /** Above this many out-of-sync replicas the figure turns the warning colour. One is already wrong. */
  private val OutOfSyncWarnAbove: Int = 0

  def all(
      cluster: ClusterId,
      largestMessageCount: Signal[Long],
      largestSize: Signal[Long],
      hrefFor: (ClusterId, String) => String,
      onOpen: (ClusterId, String) => Unit,
      onToggleFavourite: String => Unit
  ): List[Column[TopicRow]] =
    List(
      Column[TopicRow](
        id = FavouriteId,
        // No header label: a column of stars needs none, and "Favourite" over a 24-pixel column would set
        // the width of the whole column to the width of the word.
        header = "",
        render = row => star(row, onToggleFavourite),
        width = Some("2.5rem")
      ),
      Column[TopicRow](
        id = TopicSortField.Name.wire,
        header = Messages.ColumnName,
        render = row => nameCell(cluster, row, hrefFor, onOpen),
        sortable = true
      ),
      Column[TopicRow](
        id = TopicSortField.Partitions.wire,
        header = Messages.ColumnPartitions,
        render = row => row.partitions.toString,
        sortable = true,
        align = ColumnAlign.Numeric
      ),
      Column[TopicRow](
        id = TopicSortField.ReplicationFactor.wire,
        header = Messages.ColumnReplicationFactor,
        // `None` here means the topic's partitions disagree about it, during a reassignment. An em dash, not
        // a guess at which partition to believe.
        render = row => row.replicationFactor.fold(DataTable.missing)(_.toString),
        sortable = true,
        align = ColumnAlign.Numeric
      ),
      Column[TopicRow](
        id = TopicSortField.OutOfSyncReplicas.wire,
        header = Messages.ColumnOutOfSync,
        render = row =>
          ThresholdValue(
            value = Val(row.outOfSync.toString),
            level = Val(
              if row.outOfSync > OutOfSyncWarnAbove then ThresholdLevel.Warning else ThresholdLevel.Normal
            )
          ),
        sortable = true,
        align = ColumnAlign.Numeric
      ),
      Column[TopicRow](
        id = TopicSortField.MessageCount.wire,
        header = Messages.ColumnMessages,
        render = row => messagesCell(row, largestMessageCount),
        sortable = true,
        align = ColumnAlign.Numeric
      ),
      Column[TopicRow](
        id = TopicSortField.Size.wire,
        header = Messages.ColumnSize,
        render = row => sizeCell(row, largestSize),
        sortable = true,
        align = ColumnAlign.Numeric
      )
    )

  /** The star. A `<button>` and not a clickable span, because the keyboard has to reach it. */
  private def star(row: TopicRow, onToggle: String => Unit): Modifier[HtmlElement] =
    button(
      tpe := "button",
      cls := TopicsCss.Star,
      cls(TopicsCss.StarOn) := row.favourite,
      dataAttr("testid") := s"topic-row-${row.name}-star",
      // The label says what pressing it will do, and `aria-pressed` says what the state is now. Without the
      // second, a screen-reader user has to press it to find out whether the topic was already a favourite.
      aria.pressed := row.favourite.toString,
      aria.label := (if row.favourite then Messages.unfavourite(row.name) else Messages.favourite(row.name)),
      Icon.star,
      onClick.stopPropagation.mapTo(row.name) --> { name => onToggle(name) }
    )

  /** The name, as a real link, with a grey tag when Kafka — or KUI's own prefix list — calls it internal. */
  private def nameCell(
      cluster: ClusterId,
      row: TopicRow,
      hrefFor: (ClusterId, String) => String,
      onOpen: (ClusterId, String) => Unit
  ): Modifier[HtmlElement] =
    span(
      cls := TopicsCss.NameCell,
      a(
        cls := TopicsCss.NameLink,
        // A real `href`, so copy, bookmark and open-in-new-tab all work. The click below is an optimisation
        // on top of a working link, never a replacement for one.
        href := hrefFor(cluster, row.name),
        dataAttr("testid") := s"topic-row-${row.name}",
        row.name,
        onClick.preventDefault.mapTo(row.name) --> { name => onOpen(cluster, name) }
      ),
      Option.when(row.internal)(
        Tag(
          label = Val(Messages.InternalTag),
          tone = Tone.Neutral,
          testId = Some(s"topic-row-${row.name}-internal")
        )
      )
    )

  /** A size, with a bar only when there is a size.
    *
    * A bar drawn for an absent value is not a neutral thing to draw: an empty groove beside an em dash, on
    * every row of the column, teaches the reader that the groove means nothing — and the bar is the whole
    * reason the column is worth its width. The broker does not always report log-dir sizes, and until it does
    * the honest rendering of "unknown" is the same em dash every other unknown figure gets, with no furniture
    * around it pretending a measurement was taken.
    */
  private def sizeCell(row: TopicRow, largest: Signal[Long]): Modifier[HtmlElement] =
    row.sizeBytes match {
      case None => span(dataAttr("testid") := s"topic-row-${row.name}-size", DataTable.missing)
      case Some(_) =>
        MagnitudeBar(
          value = Val(Bytes.format(row.sizeBytes)),
          fraction = largest.map(max => Bytes.fraction(row.sizeBytes, max)),
          inline = true,
          testId = Some(s"topic-row-${row.name}-size")
        )
    }

  /** The cell to get right.
    *
    * A count that is present is a figure with a bar. A count that is absent is an em dash **and a chip saying
    * why** — never a zero. `0` reads as "this topic is empty", which ends an investigation that should have
    * started; the chip names the actual condition, which is either "three partitions are offline" or "the
    * broker did not report offsets", and those call for different actions.
    */
  private def messagesCell(row: TopicRow, largest: Signal[Long]): Modifier[HtmlElement] =
    span(
      cls := TopicsCss.MessagesCell,
      dataAttr("testid") := s"topic-row-${row.name}-messages",
      row.missingCountReason match {
        case None =>
          MagnitudeBar(
            value = Val(row.messages.fold(DataTable.missing)(count => Numbers.grouped(count))),
            fraction = largest.map(max => Bytes.fraction(row.messages, max)),
            inline = true
          )
        case Some(reason) =>
          span(
            cls := KernelCss.VisuallyHidden,
            // Read out before the dash, so a screen reader says why rather than saying nothing at all.
            reason
          ) :: span(aria.hidden := true, DataTable.missing) ::
            Tag(
              label = Val(Messages.OfflineChip),
              tone = Tone.Warning,
              testId = Some(s"topic-row-${row.name}-offline")
              // The full sentence on hover; the chip itself has room for one word, and a chip that said the
              // whole reason would set the width of the column.
            ).amend(title := reason) :: Nil
      }
    )
}
