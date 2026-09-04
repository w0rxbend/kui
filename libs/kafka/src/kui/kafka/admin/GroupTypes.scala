package kui.kafka.admin

import java.time.Instant

import kui.kafka.SkipReason
import kui.kernel.group.{GroupProtocol, GroupState}
import kui.kernel.{BrokerId, GroupId, Offset, TopicPartition}

/** Which broker is coordinating a group, when the cluster will say. */
final case class GroupCoordinator(id: BrokerId, host: String, port: Int)

object GroupCoordinator {
  given CanEqual[GroupCoordinator, GroupCoordinator] = CanEqual.derived
}

/** One row of a listing. Cheap by construction: a listing does not describe.
  *
  * The distinction matters because describing four thousand groups is a real cost on a real cluster, and the
  * group list screen is served entirely from listings plus a periodic snapshot rather than from a describe on
  * the request path (DEVPLAN risk R-4).
  */
final case class GroupListing(
    groupId: GroupId,
    /** A "simple" group was created by the low-level `assign` API and has no protocol. It can still hold
      * committed offsets, and it can still be reset, so it is listed like any other.
      */
    isSimple: Boolean,
    /** `GroupState.Unknown` when the broker did not report one — see `GroupState`'s scaladoc. */
    state: GroupState,
    protocol: GroupProtocol
)

object GroupListing {
  given CanEqual[GroupListing, GroupListing] = CanEqual.derived
  given Ordering[GroupListing] = Ordering.by(_.groupId.value)
}

/** What one listing pass saw, and what it could not see.
  *
  * Not a `BatchResult[BrokerId, _]`, which is what this port's spec asked for. A listing is answered by every
  * coordinator in the cluster at once, and `ListGroupsResult` in kafka-clients 4.x hands back two things: the
  * listings that arrived (`valid()`) and the failures that did not (`errors()`) — a bare
  * `Collection[Throwable]` with no node attached. There is no broker id to key a `BatchResult` by, and
  * inventing one would put a number on screen that names no broker. So the failures are carried as reasons,
  * with their count, which is exactly what the caller renders: "3 groups; one coordinator did not answer".
  *
  * The distinction that matters is preserved either way: a short list that says it is short, versus a short
  * list that looks complete. The reference product returns the second, and an operator reads a group that is
  * merely hidden as a group that was deleted.
  */
final case class GroupListingResult(
    groups: List[GroupListing],
    /** One entry per coordinator that failed, in the order the client reported them. */
    coordinatorFailures: List[SkipReason],
    /** Share groups and Streams groups seen and dropped. Counted rather than silently discarded, because a
      * cluster where this is large is a cluster whose group list is not what its operator expects.
      */
    nonConsumerGroups: Int
) {

  def isComplete: Boolean = coordinatorFailures.isEmpty
}

object GroupListingResult {
  def complete(groups: List[GroupListing]): GroupListingResult = GroupListingResult(groups, Nil, 0)
  given CanEqual[GroupListingResult, GroupListingResult] = CanEqual.derived
}

/** The partitions one member holds. */
final case class MemberAssignment(partitions: Set[TopicPartition])

object MemberAssignment {
  val Empty: MemberAssignment = MemberAssignment(Set.empty)
  given CanEqual[MemberAssignment, MemberAssignment] = CanEqual.derived
}

/** One member of a group, and what it is holding.
  *
  * Build these with [[GroupMember.of]] rather than the case-class constructor: it is what collapses a target
  * assignment equal to the current one to `None`, so that "is this member mid-rebalance?" has exactly one
  * answer instead of two spellings of the same one.
  */
final case class GroupMember(
    memberId: String,
    /** Present only for static members (`group.instance.id`). */
    groupInstanceId: Option[String],
    clientId: String,
    host: String,
    /** What the member holds now. For a KIP-848 consumer-protocol member this is `assignment()`, and
      * [[targetAssignment]] carries where the coordinator is moving it to.
      */
    assignment: MemberAssignment,
    /** KIP-848 only; `None` for classic members and whenever the target equals the assignment. */
    targetAssignment: Option[MemberAssignment]
) {

  /** True when the coordinator is moving this member somewhere it is not yet. The UI shows the previous
    * assignment with a stale badge rather than an empty table while that is true (DC-H10).
    */
  def isRebalancing: Boolean = targetAssignment.isDefined
}

object GroupMember {

  /** The smart constructor. A `target` equal to `assignment` — which is what a settled KIP-848 group reports
    * on every describe — becomes `None`, because a member that is exactly where it is being sent is not
    * rebalancing, and rendering it as though it were would put a stale badge on every healthy group.
    */
  def of(
      memberId: String,
      groupInstanceId: Option[String],
      clientId: String,
      host: String,
      assignment: MemberAssignment,
      target: Option[MemberAssignment]
  ): GroupMember =
    GroupMember(
      memberId,
      groupInstanceId,
      clientId,
      host,
      assignment,
      target.filterNot(_.partitions == assignment.partitions)
    )

  given CanEqual[GroupMember, GroupMember] = CanEqual.derived
  given Ordering[GroupMember] = Ordering.by(_.memberId)
}

/** What a principal is allowed to do with a group, as the broker reports it. */
enum GroupOperation {
  case Describe, Read, Delete, All, Unknown
}

object GroupOperation {
  given CanEqual[GroupOperation, GroupOperation] = CanEqual.derived
}

/** A full description of one group.
  *
  * A description of a group that does not exist is a legitimate value of this type: state `Dead`, no members,
  * no assignor. See `GroupAdmin.describeGroups` for why, and for what a caller that genuinely needs to know
  * whether the group exists does instead.
  */
final case class GroupDescription(
    groupId: GroupId,
    isSimple: Boolean,
    state: GroupState,
    protocol: GroupProtocol,
    /** The assignor the group negotiated ("range", "cooperative-sticky", …); empty for a simple group. */
    partitionAssignor: String,
    members: List[GroupMember],
    /** `None` when the coordinator could not be determined — a real state during a coordinator move, and not
      * an error.
      */
    coordinator: Option[GroupCoordinator],
    /** `None` when the cluster has no authorizer configured, which means "ACLs are off". That is a different
      * fact from `Some(Set.empty)`, which means "this principal may do nothing with this group", and the two
      * render differently: a full page, versus a lock.
      */
    authorizedOperations: Option[Set[GroupOperation]]
) {

  /** Whether an offset operation is allowed on this group right now.
    *
    * Both halves, never one (DEVPLAN §10 D4). Kafbat checks the state and Kouncil checks the member list, and
    * the two disagree on a group that reports `Empty` while a member is joining. The union is the only safe
    * reading, and it is stated here — on the type that has both halves — so that the planner, the guard and
    * the use case all ask the same question.
    */
  def permitsOffsetChange: Boolean =
    GroupState.permitsOffsetChange(state) && members.isEmpty
}

object GroupDescription {

  /** The fabricated dead group of `GroupAdmin.describeGroups`' invariant: what a broker that throws
    * `GroupIdNotFoundException` is normalised to, and what an older broker answers by itself.
    */
  def dead(groupId: GroupId): GroupDescription =
    GroupDescription(
      groupId = groupId,
      isSimple = false,
      state = GroupState.Dead,
      protocol = GroupProtocol.Unknown,
      partitionAssignor = "",
      members = Nil,
      coordinator = None,
      authorizedOperations = None
    )

  given CanEqual[GroupDescription, GroupDescription] = CanEqual.derived
}

/** One committed offset of one group.
  *
  * A partition the group has never committed on is *absent* from a result, never present with a zero. That
  * absence is the whole reason lag is an `Option` downstream (DEVPLAN §10 D6).
  */
final case class CommittedOffset(
    partition: TopicPartition,
    offset: Offset,
    /** Kafka's free-text metadata field on the commit. Rendered, never parsed. */
    metadata: Option[String],
    /** Present from Kafka 2.1; `None` on older brokers and on some managed services. */
    committedAt: Option[Instant]
)

object CommittedOffset {
  given CanEqual[CommittedOffset, CommittedOffset] = CanEqual.derived
  given Ordering[CommittedOffset] = Ordering.by(_.partition)
}
