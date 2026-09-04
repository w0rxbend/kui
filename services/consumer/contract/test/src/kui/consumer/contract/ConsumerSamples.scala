package kui.consumer.contract

import java.time.Instant

import kui.consumer.contract.dto.*
import kui.contracts.capability.ReasonCode
import kui.contracts.consumer.*
import kui.contracts.paging.{PageDto, PageInfo}
import kui.kernel.group.{GroupProtocol, GroupState, LagAnomaly, ResetTarget}
import kui.kernel.{GroupId, TopicName}

/** The sample values every golden document in this module is encoded from.
  *
  * They are shared between the cross-platform suite and the JVM-only file check so that the two cannot assert
  * against different data, and they are chosen to be awkward rather than tidy: one group whose lag is
  * unknown, one partition that never committed, one that committed past the end of its log, a group in a
  * rebalance whose assignments are the last ones seen, and a reset plan that clamps. A golden file built from
  * a happy case proves only that the happy case works, and none of the defects this project has paid for were
  * in the happy case.
  */
object ConsumerSamples {

  val observedAt: Instant = Instant.parse("2026-09-04T09:15:00Z")
  val fetchedAt: Instant = Instant.parse("2026-09-04T09:14:30Z")
  val expiresAt: Instant = Instant.parse("2026-09-04T09:20:00Z")

  /** A healthy group: three members, a real total, nothing excluded. */
  val healthySummary: GroupSummaryDto = GroupSummaryDto(
    groupId = GroupId.unsafe("orders-indexer"),
    state = GroupState.Stable,
    protocol = GroupProtocol.Consumer,
    isSimple = false,
    members = 3,
    topics = 1,
    partitions = 12,
    coordinatorId = Some(2),
    totalLag = Some(1240L),
    pace = Some(415.5d),
    excludedPartitions = 0,
    incomplete = None
  )

  /** The row that matters: its lag could not be computed, so it is `null` and three partitions are named as
    * the reason. A `0` here is the fabrication M4 DEVPLAN §10 D6 forbids.
    */
  val unknownLagSummary: GroupSummaryDto = GroupSummaryDto(
    groupId = GroupId.unsafe("billing-replay"),
    state = GroupState.Empty,
    protocol = GroupProtocol.Classic,
    isSimple = false,
    members = 0,
    topics = 1,
    partitions = 12,
    coordinatorId = Some(2),
    totalLag = None,
    pace = None,
    excludedPartitions = 3,
    incomplete = Some(
      IncompleteDto(
        membersKnown = true,
        offsetsKnown = true,
        endOffsetsKnown = false,
        note = "3 of 12 partitions have no leader, so their end offsets could not be read"
      )
    )
  )

  val page: PageDto[GroupSummaryDto] = PageDto(
    items = List(healthySummary, unknownLagSummary),
    page = PageInfo(page = 1, pageSize = 25, totalItems = Some(2L), nextPageToken = None)
  )

  val partitions: List[PartitionDto] = List(
    PartitionDto(
      partition = 0,
      committed = Some(41200L),
      begin = Some(0L),
      end = Some(42440L),
      lag = Some(1240L),
      anomalies = Nil,
      memberId = Some("consumer-orders-indexer-1-6f1c"),
      host = Some("/10.1.4.7")
    ),
    PartitionDto(
      partition = 1,
      committed = None,
      begin = Some(0L),
      end = Some(9001L),
      lag = None,
      anomalies = List(LagAnomaly.NoCommit),
      memberId = Some("consumer-orders-indexer-2-9ab3"),
      host = Some("/10.1.4.8")
    ),
    PartitionDto(
      partition = 2,
      committed = Some(51000L),
      begin = Some(0L),
      end = Some(50000L),
      lag = None,
      anomalies = List(LagAnomaly.CommittedBeyondEnd),
      memberId = None,
      host = None
    )
  )

  val detail: GroupDetailDto = GroupDetailDto(
    groupId = GroupId.unsafe("orders-indexer"),
    state = GroupState.PreparingRebalance,
    protocol = GroupProtocol.Consumer,
    isSimple = false,
    partitionAssignor = "cooperative-sticky",
    coordinatorId = Some(2),
    members = List(
      MemberDto(
        memberId = "consumer-orders-indexer-1-6f1c",
        groupInstanceId = Some("indexer-a"),
        clientId = "orders-indexer",
        host = "/10.1.4.7",
        partitions = List("orders-0"),
        rebalancing = true
      )
    ),
    topics = List(
      TopicSubscriptionDto(
        topic = TopicName.unsafe("orders"),
        lag = Some(1240L),
        excludedPartitions = 2,
        partitions = partitions
      )
    ),
    totalLag = Some(1240L),
    excludedPartitions = 2,
    assignments = AssignmentFreshnessDto(AssignmentFreshness.LastSeen, Some(fetchedAt)),
    observedAt = observedAt,
    stale = Some(StaleDto(fetchedAt, ReasonCode.UpstreamTimeout))
  )

  val lagDelta: LagDeltaDto = LagDeltaDto(
    changed = List(
      LagUpdateDto(
        groupId = GroupId.unsafe("orders-indexer"),
        totalLag = Some(980L),
        pace = Some(415.5d),
        state = GroupState.Stable,
        members = 3
      ),
      LagUpdateDto(
        groupId = GroupId.unsafe("billing-replay"),
        totalLag = None,
        pace = None,
        state = GroupState.Empty,
        members = 0
      )
    ),
    gone = List(GroupId.unsafe("retired-consumer")),
    token = "v7:1a2b3c4d",
    nextPollMs = 5000L,
    full = false
  )

  val topicConsumers: TopicConsumersDto = TopicConsumersDto(
    rows = List(
      TopicConsumerRowDto(healthySummary, topicLag = Some(1240L), partitions = 12, dormant = false),
      TopicConsumerRowDto(unknownLagSummary, topicLag = None, partitions = 12, dormant = true)
    )
  )

  val resetPlan: ResetPlanDto = ResetPlanDto(
    groupId = GroupId.unsafe("orders-indexer"),
    topic = TopicName.unsafe("orders"),
    target = ResetTarget.Offset,
    partitions = List(
      PlannedPartitionDto(partition = 0, current = Some(41200L), proposed = 42440L, delta = Some(1240L)),
      PlannedPartitionDto(partition = 1, current = None, proposed = 0L, delta = None)
    ),
    warnings = List(
      ResetWarningDto(
        kind = "CLAMPED",
        partition = Some(0),
        message = "9000000 is past the end of partition 0; it was clamped to 42440"
      )
    ),
    noOp = false,
    token = "plan.v1.e30.4f6a9c",
    expiresAt = expiresAt
  )

  val resetPlanRequest: ResetPlanRequest = ResetPlanRequest(
    topic = TopicName.unsafe("orders"),
    partitions = List(0, 1),
    target = ResetTarget.Offset,
    timestamp = None,
    offsets = Some(Map("0" -> 42440L, "1" -> 0L)),
    shiftBy = None,
    durationMs = None
  )

  val resetApplyRequest: ResetApplyRequest = ResetApplyRequest("plan.v1.e30.4f6a9c")

  val deletedOffsets: DeletedOffsetsDto = DeletedOffsetsDto(
    groupId = GroupId.unsafe("billing-replay"),
    topic = TopicName.unsafe("orders"),
    partitions = List(0, 1, 2)
  )
}
