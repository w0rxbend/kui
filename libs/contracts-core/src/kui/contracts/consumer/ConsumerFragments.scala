package kui.contracts.consumer

import java.time.Instant

import io.circe.syntax.*
import io.circe.{Codec, Decoder, Encoder, HCursor, Json}
import sttp.tapir.Schema

import kui.contracts.ErrorEnvelope.given
import kui.contracts.KernelCodecs.given
import kui.contracts.KernelSchemas.given
import kui.contracts.capability.ReasonCode
import kui.contracts.consumer.GroupCodecs.given
import kui.kernel.group.{GroupProtocol, GroupState, LagAnomaly}
import kui.kernel.{GroupId, TopicName}

/** The consumer-group shapes that more than one service puts on the wire.
  *
  * The consumer service sends them from its own endpoints; the gateway sends them again inside the
  * topic-overview aggregation's `consumerGroups` section and inside the group-page aggregation (M4 DEVPLAN
  * §10 D13). Two producers of the same document is exactly the condition `libs/contracts-core` exists for —
  * `kui.contracts.topic.TopicRowDto` carries the same note — and declaring the shape twice is how the two
  * drift into two nearly identical records that a single browser decoder then has to guess between.
  *
  * Three rules bind every record here, and each one is a defect this project has already paid for:
  *
  *   - **An absent number is `null`, never `0`.** A lag that could not be computed and a lag of zero are
  *     opposite facts: one starts an investigation and the other ends it. `Option[Long]` end to end, encoded
  *     as an explicit `null` (M4 DEVPLAN §10 D6).
  *   - **An absent optional field is written as `null`, not omitted.** A browser distinguishing "the field
  *     was not there" from "the field was there and empty" is a class of bug nobody needs, so KUI's documents
  *     never make it do that.
  *   - **A required field that is missing is a decode failure, not a default.** M1's second integration
  *     defect was a browser decoding a document nobody sent, defaulting the missing list to empty, and
  *     rendering "nothing here" with no error anywhere. Every `get` below is a `get`, and the two
  *     `getOrElse`s that exist are on list fields whose absence a required sibling already rules out.
  */
/** Which parts of a group's row could not be read.
  *
  * Present only when something is missing, so `incomplete == null` is the ordinary case and a UI can treat
  * any value at all as "show the warning". The three flags are separate because they fail separately: a
  * broker can refuse `DESCRIBE` on the group while `listOffsets` on its topics succeeds, and the row is still
  * worth rendering with the parts that worked.
  *
  * @param note
  *   the operator-facing sentence, already written by the service that knows why. The browser renders it as
  *   given rather than reconstructing a sentence from the flags, so that the reason a fetch failed is not
  *   flattened into three booleans on the way to the screen
  */
final case class IncompleteDto(
    membersKnown: Boolean,
    offsetsKnown: Boolean,
    endOffsetsKnown: Boolean,
    note: String
)

object IncompleteDto {

  given Codec[IncompleteDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        membersKnown <- cursor.get[Boolean]("membersKnown")
        offsetsKnown <- cursor.get[Boolean]("offsetsKnown")
        endOffsetsKnown <- cursor.get[Boolean]("endOffsetsKnown")
        note <- cursor.get[String]("note")
      } yield IncompleteDto(membersKnown, offsetsKnown, endOffsetsKnown, note),
    (dto: IncompleteDto) =>
      Json.obj(
        "membersKnown" -> dto.membersKnown.asJson,
        "offsetsKnown" -> dto.offsetsKnown.asJson,
        "endOffsetsKnown" -> dto.endOffsetsKnown.asJson,
        "note" -> dto.note.asJson
      )
  )

  given Schema[IncompleteDto] =
    Schema.derived[IncompleteDto].description("Which parts of this group's row could not be read, and why")

  given CanEqual[IncompleteDto, IncompleteDto] = CanEqual.derived
}

/** How fresh a group's member assignments are (DC-H10).
  *
  * During a rebalance Kafka reports no assignment at all. Rendering that as an empty table tells an operator
  * their consumers have stopped, which is the opposite of what is happening, so KUI keeps the last assignment
  * it saw and says so. `LastSeen` is the case that earns the type; the timestamp travels beside it in
  * [[AssignmentFreshnessDto]].
  */
enum AssignmentFreshness(val wire: String) {
  case Current extends AssignmentFreshness("CURRENT")
  case LastSeen extends AssignmentFreshness("LAST_SEEN")
  case Unknown extends AssignmentFreshness("UNKNOWN")
}

object AssignmentFreshness {

  val All: List[AssignmentFreshness] = values.toList

  def from(wire: String): Either[String, AssignmentFreshness] =
    All.find(_.wire == wire).toRight(s"'$wire' is not an assignment freshness")

  given Codec[AssignmentFreshness] = Codec.from(
    Decoder.decodeString.emap(from),
    Encoder.encodeString.contramap(_.wire)
  )

  given Schema[AssignmentFreshness] = Schema
    .string[AssignmentFreshness]
    .description(s"How fresh the assignments are: ${All.map(_.wire).mkString(", ")}")

  given CanEqual[AssignmentFreshness, AssignmentFreshness] = CanEqual.derived
}

/** The assignment freshness with the moment the assignment was observed.
  *
  * `observedAt` is present exactly when `status` is `LAST_SEEN`: it is the timestamp the stale badge shows,
  * and for a current assignment there is nothing to date.
  */
final case class AssignmentFreshnessDto(status: AssignmentFreshness, observedAt: Option[Instant])

object AssignmentFreshnessDto {

  given Codec[AssignmentFreshnessDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        status <- cursor.get[AssignmentFreshness]("status")
        observedAt <- cursor.get[Option[Instant]]("observedAt")
      } yield AssignmentFreshnessDto(status, observedAt),
    (dto: AssignmentFreshnessDto) =>
      Json.obj("status" -> dto.status.asJson, "observedAt" -> dto.observedAt.asJson)
  )

  given Schema[AssignmentFreshnessDto] = Schema
    .derived[AssignmentFreshnessDto]
    .description("Whether the assignments are current, and when they were last seen if not")

  given CanEqual[AssignmentFreshnessDto, AssignmentFreshnessDto] = CanEqual.derived
}

/** That a document is the last good answer rather than a fresh one, with the reason and the time.
  *
  * It is the record-level twin of `Section.Stale`. A document that *is* a section carries its staleness in
  * the section; a document that is returned on its own carries it here, so that the browser's "as of" badge
  * has one shape to read whichever route produced the page.
  */
final case class StaleDto(fetchedAt: Instant, reason: ReasonCode)

object StaleDto {

  given Codec[StaleDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        fetchedAt <- cursor.get[Instant]("fetchedAt")
        reason <- cursor.get[ReasonCode]("reason")
      } yield StaleDto(fetchedAt, reason),
    (dto: StaleDto) => Json.obj("fetchedAt" -> dto.fetchedAt.asJson, "reason" -> dto.reason.asJson)
  )

  given Schema[StaleDto] =
    Schema
      .derived[StaleDto]
      .description("This is the last good answer, fetched at this time, for this reason")

  given CanEqual[StaleDto, StaleDto] = CanEqual.derived
}

/** One row of the consumer-group list.
  *
  * `totalLag` is `null` whenever any partition could not be measured, and `excludedPartitions` says how many
  * contributed nothing — so a screen can render "1 240 (3 of 12 partitions have no committed offset)" instead
  * of a number whose meaning depends on facts it does not have. The reference implementation sums with
  * `orElse(0)`, which turns an unmeasurable partition into a measured zero; that is the fabrication a
  * capacity decision must never be made from (M4 DEVPLAN §10 D6).
  *
  * @param pace
  *   the change in the group's total committed offset per second, between the last two snapshot passes.
  *   `null` until two passes exist, and `null` for one interval after the group's partition set changed — a
  *   rate computed across two different partition sets is arithmetic on two different quantities (DEVPLAN §10
  *   D12)
  */
final case class GroupSummaryDto(
    groupId: GroupId,
    state: GroupState,
    protocol: GroupProtocol,
    isSimple: Boolean,
    members: Int,
    topics: Int,
    partitions: Int,
    coordinatorId: Option[Int],
    totalLag: Option[Long],
    pace: Option[Double],
    excludedPartitions: Int,
    incomplete: Option[IncompleteDto]
)

object GroupSummaryDto {

  given Codec[GroupSummaryDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        groupId <- cursor.get[GroupId]("groupId")
        state <- cursor.get[GroupState]("state")
        protocol <- cursor.get[GroupProtocol]("protocol")
        isSimple <- cursor.get[Boolean]("isSimple")
        members <- cursor.get[Int]("members")
        topics <- cursor.get[Int]("topics")
        partitions <- cursor.get[Int]("partitions")
        coordinatorId <- cursor.get[Option[Int]]("coordinatorId")
        totalLag <- cursor.get[Option[Long]]("totalLag")
        pace <- cursor.get[Option[Double]]("pace")
        excludedPartitions <- cursor.get[Int]("excludedPartitions")
        incomplete <- cursor.get[Option[IncompleteDto]]("incomplete")
      } yield GroupSummaryDto(
        groupId,
        state,
        protocol,
        isSimple,
        members,
        topics,
        partitions,
        coordinatorId,
        totalLag,
        pace,
        excludedPartitions,
        incomplete
      ),
    (dto: GroupSummaryDto) =>
      Json.obj(
        "groupId" -> dto.groupId.asJson,
        "state" -> dto.state.asJson,
        "protocol" -> dto.protocol.asJson,
        "isSimple" -> dto.isSimple.asJson,
        "members" -> dto.members.asJson,
        "topics" -> dto.topics.asJson,
        "partitions" -> dto.partitions.asJson,
        "coordinatorId" -> dto.coordinatorId.asJson,
        "totalLag" -> dto.totalLag.asJson,
        "pace" -> dto.pace.asJson,
        "excludedPartitions" -> dto.excludedPartitions.asJson,
        "incomplete" -> dto.incomplete.asJson
      )
  )

  given Schema[GroupSummaryDto] = Schema
    .derived[GroupSummaryDto]
    .description("One consumer group as the list shows it; totalLag is null when it could not be computed")

  given CanEqual[GroupSummaryDto, GroupSummaryDto] = CanEqual.derived
}

/** One partition of one topic, as a group sees it.
  *
  * Four numbers and a set of reasons why one of them is missing. `lag` is not recomputed from `committed` and
  * `end` by any reader: it is computed once, in the domain's `LagMath`, where the anomaly rules live, and a
  * reader that did its own subtraction would reproduce a negative number for a committed offset past the end
  * of the log — which is the case `CommittedBeyondEnd` exists to report as "unknown" instead.
  */
final case class PartitionDto(
    partition: Int,
    committed: Option[Long],
    begin: Option[Long],
    end: Option[Long],
    lag: Option[Long],
    anomalies: List[LagAnomaly],
    memberId: Option[String],
    host: Option[String]
)

object PartitionDto {

  given Codec[PartitionDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        partition <- cursor.get[Int]("partition")
        committed <- cursor.get[Option[Long]]("committed")
        begin <- cursor.get[Option[Long]]("begin")
        end <- cursor.get[Option[Long]]("end")
        lag <- cursor.get[Option[Long]]("lag")
        anomalies <- cursor.getOrElse[List[LagAnomaly]]("anomalies")(Nil)
        memberId <- cursor.get[Option[String]]("memberId")
        host <- cursor.get[Option[String]]("host")
      } yield PartitionDto(partition, committed, begin, end, lag, anomalies, memberId, host),
    (dto: PartitionDto) =>
      Json.obj(
        "partition" -> dto.partition.asJson,
        "committed" -> dto.committed.asJson,
        "begin" -> dto.begin.asJson,
        "end" -> dto.end.asJson,
        "lag" -> dto.lag.asJson,
        "anomalies" -> dto.anomalies.asJson,
        "memberId" -> dto.memberId.asJson,
        "host" -> dto.host.asJson
      )
  )

  given Schema[PartitionDto] = Schema
    .derived[PartitionDto]
    .description("One partition's committed offset, end offset and lag, with why the lag is absent")

  given CanEqual[PartitionDto, PartitionDto] = CanEqual.derived
}

/** One topic a group is subscribed to, with its partitions. */
final case class TopicSubscriptionDto(
    topic: TopicName,
    lag: Option[Long],
    excludedPartitions: Int,
    partitions: List[PartitionDto]
)

object TopicSubscriptionDto {

  given Codec[TopicSubscriptionDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        topic <- cursor.get[TopicName]("topic")
        lag <- cursor.get[Option[Long]]("lag")
        excludedPartitions <- cursor.get[Int]("excludedPartitions")
        partitions <- cursor.getOrElse[List[PartitionDto]]("partitions")(Nil)
      } yield TopicSubscriptionDto(topic, lag, excludedPartitions, partitions),
    (dto: TopicSubscriptionDto) =>
      Json.obj(
        "topic" -> dto.topic.asJson,
        "lag" -> dto.lag.asJson,
        "excludedPartitions" -> dto.excludedPartitions.asJson,
        "partitions" -> dto.partitions.asJson
      )
  )

  given Schema[TopicSubscriptionDto] = Schema
    .derived[TopicSubscriptionDto]
    .description("One subscribed topic and this group's position in each of its partitions")

  given CanEqual[TopicSubscriptionDto, TopicSubscriptionDto] = CanEqual.derived
}

/** One member of a group.
  *
  * `partitions` is a list of `topic-partition` strings rather than structured pairs because it is a label:
  * the member card shows it and nothing computes with it. The structured view of the same facts is
  * [[TopicSubscriptionDto]], which is where the numbers live.
  *
  * @param rebalancing
  *   true when this member's assignment is the last one seen rather than a current one, so a card can be
  *   greyed individually — a rebalance does not always touch every member at once
  */
final case class MemberDto(
    memberId: String,
    groupInstanceId: Option[String],
    clientId: String,
    host: String,
    partitions: List[String],
    rebalancing: Boolean
)

object MemberDto {

  given Codec[MemberDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        memberId <- cursor.get[String]("memberId")
        groupInstanceId <- cursor.get[Option[String]]("groupInstanceId")
        clientId <- cursor.get[String]("clientId")
        host <- cursor.get[String]("host")
        partitions <- cursor.getOrElse[List[String]]("partitions")(Nil)
        rebalancing <- cursor.get[Boolean]("rebalancing")
      } yield MemberDto(memberId, groupInstanceId, clientId, host, partitions, rebalancing),
    (dto: MemberDto) =>
      Json.obj(
        "memberId" -> dto.memberId.asJson,
        "groupInstanceId" -> dto.groupInstanceId.asJson,
        "clientId" -> dto.clientId.asJson,
        "host" -> dto.host.asJson,
        "partitions" -> dto.partitions.asJson,
        "rebalancing" -> dto.rebalancing.asJson
      )
  )

  given Schema[MemberDto] =
    Schema.derived[MemberDto].description("One consumer in the group, with the partitions it holds")

  given CanEqual[MemberDto, MemberDto] = CanEqual.derived
}

/** One row of the topic page's Consumers tab.
  *
  * @param topicLag
  *   this group's lag **on this topic only**, which is not `group.totalLag` whenever the group consumes more
  *   than one topic. Both are on the row because the tab shows the topic figure and links to the group page,
  *   which shows the other
  * @param dormant
  *   the group has committed offsets for this topic but no members. It is not an error — a batch job between
  *   runs looks exactly like this — so it is a badge, not a warning
  */
final case class TopicConsumerRowDto(
    group: GroupSummaryDto,
    topicLag: Option[Long],
    partitions: Int,
    dormant: Boolean
)

object TopicConsumerRowDto {

  given Codec[TopicConsumerRowDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        group <- cursor.get[GroupSummaryDto]("group")
        topicLag <- cursor.get[Option[Long]]("topicLag")
        partitions <- cursor.get[Int]("partitions")
        dormant <- cursor.get[Boolean]("dormant")
      } yield TopicConsumerRowDto(group, topicLag, partitions, dormant),
    (dto: TopicConsumerRowDto) =>
      Json.obj(
        "group" -> dto.group.asJson,
        "topicLag" -> dto.topicLag.asJson,
        "partitions" -> dto.partitions.asJson,
        "dormant" -> dto.dormant.asJson
      )
  )

  given Schema[TopicConsumerRowDto] = Schema
    .derived[TopicConsumerRowDto]
    .description("A group that consumes this topic, with its lag on this topic alone")

  given CanEqual[TopicConsumerRowDto, TopicConsumerRowDto] = CanEqual.derived
}

/** The topic page's Consumers tab, whole.
  *
  * A wrapper object rather than a bare array, for the reason `ARCHITECTURE.md` §5 gives: a top-level array
  * has nowhere to grow a field, and this document will grow one the first time the tab needs to say "and 40
  * more".
  */
final case class TopicConsumersDto(rows: List[TopicConsumerRowDto])

object TopicConsumersDto {

  given Codec[TopicConsumersDto] = Codec.from(
    // `get`, not `getOrElse`: this record has no other field, so a default here would make `{}` decode into
    // "this topic has no consumers" — which is M1's second integration defect exactly, and the one shape in
    // this file where nothing else would fail first.
    (cursor: HCursor) => cursor.get[List[TopicConsumerRowDto]]("rows").map(TopicConsumersDto(_)),
    (dto: TopicConsumersDto) => Json.obj("rows" -> dto.rows.asJson)
  )

  given Schema[TopicConsumersDto] =
    Schema.derived[TopicConsumersDto].description("Every consumer group that reads this topic")

  given CanEqual[TopicConsumersDto, TopicConsumersDto] = CanEqual.derived
}
