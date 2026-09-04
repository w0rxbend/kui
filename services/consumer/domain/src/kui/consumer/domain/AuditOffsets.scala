package kui.consumer.domain

import kui.kernel.{ClusterId, GroupId}

/** Rendering a group's committed offsets for an audit record.
  *
  * The shared record (`kui.security.audit.MutationRecord`) carries `before` and `after` as optional strings,
  * because most mutations have no scalar before and after at all — what a topic create replaces is nothing. A
  * consumer-group offset reset is the one operation that genuinely does, so this is where the map becomes the
  * string, once, in the shape every reader of the trail already expects.
  *
  * There used to be a second `MutationRecord`, a second `MutationOutcome` and a second `AuditSink` in this
  * package: this service had forked the port that `libs/security-core` declares. Three services writing one
  * audit trail through two different records is why "everything that changed this cluster today" could not be
  * answered, and E2 in `docs/BACKLOG.md` is the correction. What survives the fork is this function.
  */
object AuditOffsets {

  /** The `partition -> offset` shape a record's `before` and `after` use.
    *
    * The topic is part of the key, because a group consuming two topics with a partition 0 each would
    * otherwise produce a record with one of them silently overwritten.
    */
  def of(offsets: Map[kui.kernel.TopicPartition, kui.kernel.Offset]): Map[String, Long] =
    offsets.map((partition, offset) =>
      s"${partition.topic.value}-${partition.partition.value}" -> offset.value
    )

  /** `topic-partition=offset`, sorted, so two records of the same change render identically and a diff
    * between them means something. `None` for an empty map: a mutation with nothing before it is different
    * from one whose before was the empty string.
    */
  def render(offsets: Map[String, Long]): Option[String] =
    if offsets.isEmpty then None
    else Some(offsets.toList.sortBy(_._1).map((partition, offset) => s"$partition=$offset").mkString(","))
}

/** What the consumer service needs to know about a cluster, and nothing more.
  *
  * `readOnly` is here because it is the flag every mutation checks before it touches a Kafka client
  * (ADR-047). The connection itself stays in the adapter: the domain states the rule, and never holds a
  * credential in order to state it.
  */
final case class ClusterProfileView(
    cluster: ClusterId,
    displayName: String,
    readOnly: Boolean
)

object ClusterProfileView {
  given CanEqual[ClusterProfileView, ClusterProfileView] = CanEqual.derived
}

/** One page of a listing, with what the listing could not see.
  *
  * `incompleteCoordinators` is not decoration: a group list that came back short because a coordinator is
  * down must not render like a cluster whose groups were deleted.
  */
final case class GroupListingPage(
    groups: List[GroupSummary],
    incompleteCoordinators: Int
) {
  def isComplete: Boolean = incompleteCoordinators == 0
}

object GroupListingPage {
  def complete(groups: List[GroupSummary]): GroupListingPage = GroupListingPage(groups, 0)
  given CanEqual[GroupListingPage, GroupListingPage] = CanEqual.derived
}

/** Where a group's offsets were before a mutation, for the audit record and for the plan's `current`. */
final case class GroupOffsets(group: GroupId, offsets: Map[kui.kernel.TopicPartition, kui.kernel.Offset])
