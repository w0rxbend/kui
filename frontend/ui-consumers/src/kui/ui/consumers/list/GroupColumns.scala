package kui.ui.consumers.list

import com.raquo.laminar.api.L.*

import kui.contracts.consumer.{GroupSortField, GroupSummaryDto}
import kui.kernel.ClusterId
import kui.ui.consumers.{ConsumersCss, GroupStateChip, Messages, Numbers}
import kui.ui.kernel.component.*

/** The six columns of the consumer-group list, and every rendering rule that is about *one cell*.
  *
  * ## Lag gets a magnitude bar, and the scale is the rows on screen
  *
  * Scaled against the largest lag among the rows currently displayed — not against a cluster total and not
  * against a fixed maximum. Digits are slow to compare; a bar drawn to one scale down a column answers "which
  * group is the one that is behind" before a single digit is read, which is the question this screen exists
  * to answer. The scale is a `Signal` passed in, because only the page knows which rows are on screen.
  *
  * ## A lag that is not known is not a lag of zero
  *
  * `totalLag` is `Option[Long]` on the wire precisely so that "we could not compute this" and "this group has
  * caught up" are different values, and they must stay different on the screen: an em dash with a title
  * naming the reason, never a zero and never an empty cell. A zero here would tell an operator their group
  * was fine at the exact moment it was not readable — the most expensive possible lie on this screen.
  *
  * ## The sort ids are the wire's
  *
  * A column's `id` is what goes into the sort state and therefore into the URL and then into the query
  * string, so it is `GroupSortField.wire` rather than a string typed here. A renamed field breaks the build
  * instead of producing a 400 on the screen: the server refuses an unknown sort rather than quietly ignoring
  * it, which is the right behaviour and makes this the only safe way to name it.
  */
object GroupColumns {

  def all(
      cluster: ClusterId,
      largestLag: Signal[Long],
      hrefFor: (ClusterId, String) => String,
      onOpen: (ClusterId, String) => Unit
  ): List[Column[GroupSummaryDto]] =
    List(
      Column[GroupSummaryDto](
        id = GroupSortField.Id.wire,
        header = Messages.ColumnGroup,
        render = row => nameCell(cluster, row, hrefFor, onOpen),
        sortable = true
      ),
      Column[GroupSummaryDto](
        id = GroupSortField.State.wire,
        header = Messages.ColumnState,
        render =
          row => GroupStateChip(Val(row.state), testId = Some(s"group-row-${row.groupId.value}-state")),
        sortable = true,
        width = Some("11rem")
      ),
      Column[GroupSummaryDto](
        id = GroupSortField.Members.wire,
        header = Messages.ColumnMembers,
        render = row => Numbers.grouped(row.members.toLong),
        sortable = true,
        align = ColumnAlign.Numeric
      ),
      Column[GroupSummaryDto](
        id = GroupSortField.Topics.wire,
        header = Messages.ColumnTopics,
        render = row => Numbers.grouped(row.topics.toLong),
        sortable = true,
        align = ColumnAlign.Numeric
      ),
      Column[GroupSummaryDto](
        // Not a sort field on the server, so it carries an id of its own and is not sortable. A header that
        // sorted by something the server refuses would be a 400 one click away.
        id = "partitions",
        header = Messages.ColumnPartitions,
        render = row => Numbers.grouped(row.partitions.toLong),
        align = ColumnAlign.Numeric
      ),
      Column[GroupSummaryDto](
        id = GroupSortField.Lag.wire,
        header = Messages.ColumnLag,
        render = row => lagCell(row, largestLag),
        sortable = true,
        align = ColumnAlign.Numeric,
        width = Some("14rem")
      ),
      Column[GroupSummaryDto](
        // Not a server sort field, so it carries its own id and is not sortable, exactly as `partitions`
        // does. A header that sorted by something the server refuses would be a 400 one click away.
        id = "pace",
        header = Messages.ColumnPace,
        render = row => paceCell(row),
        align = ColumnAlign.Numeric,
        width = Some("9rem")
      )
    )

  /** The group id, as a real link.
    *
    * A real `<a>` with a real `href`, so copying it, bookmarking it and opening it in a new tab all work; the
    * ordinary click is intercepted by the feature and turned into a navigation with no page reload.
    */
  private def nameCell(
      cluster: ClusterId,
      row: GroupSummaryDto,
      hrefFor: (ClusterId, String) => String,
      onOpen: (ClusterId, String) => Unit
  ): Modifier[HtmlElement] =
    div(
      cls := ConsumersCss.GroupCell,
      a(
        cls := ConsumersCss.GroupLink,
        href := hrefFor(cluster, row.groupId.value),
        dataAttr("testid") := s"group-row-${row.groupId.value}-link",
        row.groupId.value,
        // Only a plain left click is ours. A modified click is the user asking their browser for a new tab
        // or a new window, and swallowing it would break the one gesture that makes a table of links useful.
        onClick
          .filter(event => !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey)
          .preventDefault --> { _ => onOpen(cluster, row.groupId.value) }
      ),
      // A group KUI could only partly read says so beside its name rather than in a tooltip nobody opens.
      row.incomplete.map(incomplete =>
        Tag(label = Val(Messages.PartialChip), tone = Tone.Warning)
          .amend(title := incomplete.note)
      )
    )

  /** How fast the group is committing, or an em dash saying why there is no rate yet.
    *
    * ==Why this column exists at all==
    *
    * The server has computed this number since M4 and no screen has ever shown it. Lag on its own does not
    * answer the question an operator actually has, which is "is this getting better?": a lag of two million
    * that is falling at forty thousand a second needs no action, and a lag of nine hundred that is not moving
    * needs one now. Two consecutive readings of the lag column answer it after thirty seconds of watching;
    * this column answers it at a glance.
    *
    * ==Zero is not the same as absent, and negative is not an error==
    *
    * A rate of zero on a group with lag is the stalled case, and it gets a word rather than a bare `0`,
    * because `0` beside a large lag is the single most important cell on this screen and reads as nothing at
    * all. A negative rate is committed offsets moving *backwards*, which is what somebody else's offset reset
    * looks like from here; it is shown as it is rather than clamped, since noticing it is most of the value.
    */
  private def paceCell(row: GroupSummaryDto): Modifier[HtmlElement] =
    row.pace match {
      case Some(rate) if rate == 0.0 =>
        span(
          dataAttr("testid") := s"group-row-${row.groupId.value}-pace",
          title := Messages.PaceStalled,
          cls := ConsumersCss.PaceStalled,
          "0"
        )
      case Some(rate) =>
        span(
          dataAttr("testid") := s"group-row-${row.groupId.value}-pace",
          title := (if rate < 0.0 then Messages.PaceBackwards else Messages.PaceUnit),
          cls := (if rate < 0.0 then ConsumersCss.PaceBackwards else ConsumersCss.Pace),
          Numbers.rate(rate)
        )
      case None =>
        span(
          dataAttr("testid") := s"group-row-${row.groupId.value}-pace",
          title := Messages.PaceUnknown,
          DataTable.missing
        )
    }

  /** The lag figure and its bar, or an em dash that says why there is no figure. */
  private def lagCell(row: GroupSummaryDto, largestLag: Signal[Long]): Modifier[HtmlElement] =
    row.totalLag match {
      case Some(lag) =>
        MagnitudeBar(
          value = Val(Numbers.grouped(lag)),
          fraction = largestLag.map(max => Numbers.fraction(lag, max)),
          inline = true,
          testId = Some(s"group-row-${row.groupId.value}-lag")
        ).amend(
          // The excluded count is on the figure itself, because a total computed over fewer partitions than
          // the group holds is a smaller number than the truth and nothing else on the row would say so.
          Option.when(row.excludedPartitions > 0)(title := Messages.excluded(row.excludedPartitions))
        )
      case None =>
        span(
          dataAttr("testid") := s"group-row-${row.groupId.value}-lag",
          title := Messages.LagUnknown,
          DataTable.missing
        )
    }
}
