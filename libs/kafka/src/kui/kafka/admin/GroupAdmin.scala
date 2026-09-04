package kui.kafka.admin

import kui.kafka.BatchResult
import kui.kernel.cluster.ClusterConnection
import kui.kernel.error.KuiError
import kui.kernel.group.GroupState
import kui.kernel.{BrokerId, GroupId, Offset, TopicPartition}

/** The consumer-group context's window onto a Kafka cluster.
  *
  * The second admin port family, and a separate one from `ClusterAdmin` on purpose: one narrow port per
  * bounded context (`research/kafka/admin-capabilities.md` DC-D1). It speaks `libs/kernel` vocabulary and its
  * own result types; layering rule A5 forbids it any knowledge of a service's domain.
  *
  * Every method returns `Either[KuiError, _]` and most return a `BatchResult`, for the reason that type
  * exists: a coordinator that is down hides its groups, and a listing that quietly came back short is worse
  * than one that says how much of the cluster it could not reach.
  */
trait GroupAdmin[F[_]] {

  /** Every group the coordinators will admit to, optionally narrowed by state.
    *
    * A listing is per coordinator broker, so a down broker hides the groups it coordinates and the call still
    * succeeds. The result therefore says which coordinators failed rather than pretending the listing is
    * complete: the screen renders the groups it has, with a banner naming the brokers it could not ask.
    *
    * `states` empty means "every state". Filtering by state is a broker-side feature from Kafka 2.6, so it is
    * probed rather than inferred from a version (`GroupFeature`, ADR-030).
    */
  def listGroups(
      conn: ClusterConnection,
      states: Set[GroupState]
  ): F[Either[KuiError, BatchResult[BrokerId, List[GroupListing]]]]

  /** Describe groups in bulk.
    *
    * ## An unknown group is a dead group, not an error
    *
    * Brokers disagree about what "that group does not exist" means. Older brokers answer
    * `describeConsumerGroups` for an unknown group id with a perfectly ordinary description whose state is
    * `DEAD` and whose member list is empty. Newer brokers throw `GroupIdNotFoundException`.
    *
    * A port that passes the difference through makes every caller branch on broker version — and ADR-030 is
    * explicit that KUI gates on *capabilities*, never on version numbers. Two callers written six months
    * apart will branch differently, and the one that forgot will show a stack trace on a cluster that is
    * behaving correctly.
    *
    * **This port normalises to the older behaviour**: an unknown group is a `GroupDescription` in state
    * `Dead`, with no members and no assignment. `GroupIdNotFoundException` is caught inside the adapter and
    * turned into that value, *before* `KafkaErrorMapper` ever sees it.
    *
    * "Dead with no members" is a true statement about a group that does not exist, and it is what a screen
    * wants to render — an empty group page rather than a 404 — whereas an error forces every caller to decide
    * what to do about it. **Where existence genuinely matters, the caller confirms it with `listGroups`
    * first**, which is what the reference product does before an offset reset
    * (`OffsetsResetService.java:66-92`). In KUI that caller is `OffsetResetUseCase`, and
    * `ErrorCode.GroupNotFound` is the code it raises.
    *
    * Evidence: `research/kafka/admin-capabilities.md` §3, "Describe groups". Moved here from
    * `libs/kafka/PORT-INVARIANTS.md` §2 by task GRP-002, which deleted it there.
    */
  def describeGroups(
      conn: ClusterConnection,
      ids: List[GroupId]
  ): F[Either[KuiError, BatchResult[GroupId, GroupDescription]]]

  /** Committed offsets for several groups at once.
    *
    * A partition the group has never committed on is absent from the list — never present with a zero.
    *
    * `partitions` `None` means every partition the group has a commit for. `requireStable` asks the
    * coordinator to refuse rather than answer while a transaction is in flight, which is what a reset's plan
    * needs and what a list screen does not.
    */
  def committedOffsets(
      conn: ClusterConnection,
      groups: List[GroupId],
      partitions: Option[Set[TopicPartition]],
      requireStable: Boolean
  ): F[Either[KuiError, BatchResult[GroupId, List[CommittedOffset]]]]

  /** Write committed offsets.
    *
    * This method refuses nothing on its own: the precondition — the group is empty, in both senses — belongs
    * to the caller, and the broker's own rejection is mapped as a last line of defence rather than relied on
    * as the first.
    */
  def alterOffsets(
      conn: ClusterConnection,
      group: GroupId,
      offsets: Map[TopicPartition, Offset]
  ): F[Either[KuiError, Unit]]

  /** Forget the group's commits for these partitions, so that it starts from its `auto.offset.reset`. */
  def deleteOffsets(
      conn: ClusterConnection,
      group: GroupId,
      partitions: Set[TopicPartition]
  ): F[Either[KuiError, Unit]]

  /** Delete whole groups. One group's refusal costs that group's row and not the batch. */
  def deleteGroups(
      conn: ClusterConnection,
      ids: List[GroupId]
  ): F[Either[KuiError, BatchResult[GroupId, Unit]]]
}

object GroupAdmin {

  /** The component name this port's failures and log lines are attributed to. */
  val Component: String = "kui.kafka.group"

  /** The `operation` attribute of `kui.kafka.admin.duration` for each method, spelled once so that six tasks
    * cannot invent six spellings.
    */
  object Operation {
    val List: String = "group.list"
    val Describe: String = "group.describe"
    val Offsets: String = "group.offsets"
    val AlterOffsets: String = "group.alter_offsets"
    val DeleteOffsets: String = "group.delete_offsets"
    val Delete: String = "group.delete"
  }
}
