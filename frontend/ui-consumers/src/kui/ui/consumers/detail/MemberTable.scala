package kui.ui.consumers.detail

import com.raquo.laminar.api.L.*

import kui.contracts.consumer.MemberDto
import kui.ui.consumers.{ConsumersCss, Messages}
import kui.ui.kernel.component.*

/** Who is in the group: one row per consumer, with the partitions it holds.
  *
  * ## Why the member id is not the first column
  *
  * A member id is a client id with a UUID stapled to it and it is the least readable thing on the row. What
  * an operator recognises is the client id — the name their own application chose — and the host it is
  * running on, which is what they will `ssh` into. So those come first and the member id follows, still
  * present because it is what an offset-reset refusal or a broker log line will name.
  *
  * ## A rebalancing member says so
  *
  * `rebalancing` is a per-member fact, not a group-wide one: during a cooperative rebalance some members keep
  * consuming while others hand partitions back. Marking the individual member is the difference between "the
  * whole group has stopped" and "one consumer is being replaced", which are very different mornings.
  */
object MemberTable {

  def apply(members: List[MemberDto]): HtmlElement =
    DataTable[MemberDto](
      columns = columns,
      rows = Val(members),
      // The member id is Kafka's own unique key for a member and the only field guaranteed distinct: two
      // consumers can share a client id, and every static member shares a host with its replacement.
      rowKey = _.memberId,
      empty = () =>
        EmptyState(
          Messages.MembersEmpty,
          description = Some(Messages.MembersEmptyDescription),
          testId = Some("group-members-empty")
        ),
      testId = Some("group-members")
    )

  private def columns: List[Column[MemberDto]] =
    List(
      Column[MemberDto](
        id = "clientId",
        header = Messages.ColumnClientId,
        render = row =>
          span(
            row.clientId,
            // A static member's `group.instance.id` is why it can restart without triggering a rebalance,
            // so it belongs beside the client that carries it rather than in a column of mostly em dashes.
            row.groupInstanceId.map(id => span(cls := ConsumersCss.Note, id)),
            Option.when(row.rebalancing)(
              Tag(label = Val(Messages.Rebalancing), tone = Tone.Warning, dot = true)
            )
          )
      ),
      Column[MemberDto](
        id = "host",
        header = Messages.ColumnHost,
        render = row => row.host
      ),
      Column[MemberDto](
        id = "memberId",
        header = Messages.ColumnMemberId,
        render = row => span(cls := ConsumersCss.Note, row.memberId)
      ),
      Column[MemberDto](
        id = "assigned",
        header = Messages.ColumnAssigned,
        render = row =>
          if row.partitions.isEmpty then DataTable.missing
          else
            // The assignment strings are the wire's `topic-partition` spellings, listed rather than counted:
            // "6 partitions" hides whether they are six of one topic or one each of six.
            div(
              cls := ConsumersCss.PartitionList,
              row.partitions.map(name => span(cls := ConsumersCss.Anomaly, name))
            )
      )
    )
}
