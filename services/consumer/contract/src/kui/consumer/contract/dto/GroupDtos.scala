package kui.consumer.contract.dto

import java.time.Instant

import io.circe.syntax.*
import io.circe.{Codec, HCursor, Json}
import sttp.tapir.Schema

import kui.consumer.contract.dto.ConsumerCodecs.given
import kui.contracts.consumer.*
import kui.contracts.paging.PageDto
import kui.kernel.GroupId
import kui.kernel.group.{GroupProtocol, GroupState}

/** One page of the consumer-group list.
  *
  * A type alias rather than a record of its own: the page shape is `libs/contracts-core`'s
  * [[kui.contracts.paging.PageDto]] and this list has nothing to add to it. Aliasing keeps
  * `Endpoint[…, GroupPageDto, …]` readable in `ConsumerEndpoints` without inventing a second page document
  * for a browser to learn.
  *
  * Note what is *not* here: a `pageCount` field. `PageInfo` derives it from `totalItems`, because the
  * implementation this project is modelled on computes its count before its filter runs and then disagrees
  * with the rows it sent (`research/kafbat/api-analysis.md` §3.3). One number computed from another cannot
  * disagree with it.
  */
type GroupPageDto = PageDto[GroupSummaryDto]

/** One consumer group, whole: who is in it, what they hold, and how far behind they are.
  *
  * This is the group detail page's single document. It is deliberately not assembled from the list row plus
  * three more calls: every number on the page comes from one snapshot pass, so the end offsets that lag was
  * computed against and the committed offsets it was computed from are from the same moment. Pairing a fresh
  * commit with a stale end offset produces a lag that never existed (M4 DEVPLAN §10 D7).
  *
  * @param partitionAssignor
  *   what the group negotiated — `range`, `cooperative-sticky`, and so on. Empty for a group with no members,
  *   which is why it is a `String` and not an `Option`: Kafka reports `""`, and translating that to `null`
  *   would invent a distinction the broker does not make
  * @param assignments
  *   whether the member assignments below are current or the last ones seen (DC-H10). During a rebalance
  *   Kafka reports none at all; showing an empty table would tell an operator their consumers had stopped,
  *   which is the opposite of what is happening
  * @param observedAt
  *   when the snapshot these numbers came from was taken. Always present, whether the answer is fresh or not,
  *   because "as of" is what makes a lag figure interpretable
  * @param stale
  *   present when `observedAt` is older than the snapshot's refresh interval and a refresh has failed. `null`
  *   in the ordinary case
  */
final case class GroupDetailDto(
    groupId: GroupId,
    state: GroupState,
    protocol: GroupProtocol,
    isSimple: Boolean,
    partitionAssignor: String,
    coordinatorId: Option[Int],
    members: List[MemberDto],
    topics: List[TopicSubscriptionDto],
    totalLag: Option[Long],
    excludedPartitions: Int,
    assignments: AssignmentFreshnessDto,
    observedAt: Instant,
    stale: Option[StaleDto]
)

object GroupDetailDto {

  given Codec[GroupDetailDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        groupId <- cursor.get[GroupId]("groupId")
        state <- cursor.get[GroupState]("state")
        protocol <- cursor.get[GroupProtocol]("protocol")
        isSimple <- cursor.get[Boolean]("isSimple")
        partitionAssignor <- cursor.get[String]("partitionAssignor")
        coordinatorId <- cursor.get[Option[Int]]("coordinatorId")
        members <- cursor.get[List[MemberDto]]("members")
        topics <- cursor.get[List[TopicSubscriptionDto]]("topics")
        totalLag <- cursor.get[Option[Long]]("totalLag")
        excludedPartitions <- cursor.get[Int]("excludedPartitions")
        assignments <- cursor.get[AssignmentFreshnessDto]("assignments")
        observedAt <- cursor.get[Instant]("observedAt")
        stale <- cursor.get[Option[StaleDto]]("stale")
      } yield GroupDetailDto(
        groupId,
        state,
        protocol,
        isSimple,
        partitionAssignor,
        coordinatorId,
        members,
        topics,
        totalLag,
        excludedPartitions,
        assignments,
        observedAt,
        stale
      ),
    (dto: GroupDetailDto) =>
      Json.obj(
        "groupId" -> dto.groupId.asJson,
        "state" -> dto.state.asJson,
        "protocol" -> dto.protocol.asJson,
        "isSimple" -> dto.isSimple.asJson,
        "partitionAssignor" -> dto.partitionAssignor.asJson,
        "coordinatorId" -> dto.coordinatorId.asJson,
        "members" -> dto.members.asJson,
        "topics" -> dto.topics.asJson,
        "totalLag" -> dto.totalLag.asJson,
        "excludedPartitions" -> dto.excludedPartitions.asJson,
        "assignments" -> dto.assignments.asJson,
        "observedAt" -> dto.observedAt.asJson,
        "stale" -> dto.stale.asJson
      )
  )

  given Schema[GroupDetailDto] = Schema
    .derived[GroupDetailDto]
    .description("One consumer group: its members, their assignments, and its lag per partition")

  given CanEqual[GroupDetailDto, GroupDetailDto] = CanEqual.derived
}
