package kui.consumer.domain

import java.time.Instant

import kui.kernel.{ClusterId, GroupId}

/** How a mutation ended. */
enum MutationOutcome {
  case Succeeded
  case Refused(code: String, reason: String)
  case Failed(code: String, reason: String)

  /** The operation was cancelled, or timed out after the request had been sent.
    *
    * Kafka gives no guarantee that it was *not* applied, so a record claiming either would be a lie. An
    * operator who sees this knows to go and look, which is the only honest thing this case can offer.
    */
  case Unknown(reason: String)
}

object MutationOutcome {
  given CanEqual[MutationOutcome, MutationOutcome] = CanEqual.derived
}

/** One line in the audit trail.
  *
  * Deliberately flat, and deliberately free of anything that could carry a credential: no profile, no
  * connection, no properties map. Offsets are not credentials, and an audit record of an offset reset that
  * did not say which offsets were written would answer none of the questions it exists for.
  */
final case class MutationRecord(
    at: Instant,
    cluster: ClusterId,
    /** `consumer.group.offsets.reset`, `consumer.group.offsets.delete`, `consumer.group.delete`. */
    operation: String,
    /** The group id. A string rather than a typed id because M5's sink is generic over resources. */
    resource: String,
    /** Anonymous until M6 gives this service a real principal. */
    principal: String,
    before: Map[String, Long],
    after: Map[String, Long],
    outcome: MutationOutcome,
    /** The trace id, so a record can be joined to the request that produced it. */
    traceId: Option[String]
)

object MutationRecord {

  val AnonymousPrincipal: String = "anonymous"

  /** The `partition -> offset` shape the record's `before` and `after` maps use.
    *
    * A string key because the sink is generic; the topic is included, because a group consuming two topics
    * with a partition 0 each would otherwise produce a record with one of them silently overwritten.
    */
  def offsetsOf(offsets: Map[kui.kernel.TopicPartition, kui.kernel.Offset]): Map[String, Long] =
    offsets.map((partition, offset) =>
      s"${partition.topic.value}-${partition.partition.value}" -> offset.value
    )

  given CanEqual[MutationRecord, MutationRecord] = CanEqual.derived
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
