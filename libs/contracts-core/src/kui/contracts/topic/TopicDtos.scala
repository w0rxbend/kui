package kui.contracts.topic

import io.circe.syntax.*
import io.circe.{Codec, HCursor, Json}
import sttp.tapir.Schema

import kui.contracts.KernelCodecs.given
import kui.contracts.KernelSchemas.given
import kui.kernel.TopicName

/** One row of the topic list.
  *
  * Every `Option` on this record means **"not knowable"**, never "zero". A browser renders an em dash for
  * each of them, and that distinction is the whole point: a topic whose message count could not be computed —
  * because one of its partitions has no leader, or because `listOffsets` timed out — must not be rendered as
  * an empty topic. "Empty" ends an investigation; "unknown" starts one (DEVPLAN §10 D6, and
  * `libs/kafka/PORT-INVARIANTS.md` §1 for where the refusal to aggregate originates).
  *
  * This record lives in `libs/contracts-core` rather than in the topic service's own contract because two
  * producers send it: the topic service's list endpoint and the gateway's topic-overview aggregation. One
  * declaration is what stops the two from drifting into two nearly identical shapes.
  *
  * @param internal
  *   whether Kafka flags the topic internal, unioned with KUI's configured internal-name prefixes — so
  *   `__kui_config`, which Kafka does not flag, is still internal here (DEVPLAN §10 D3)
  * @param replicationFactor
  *   `None` when the topic's partitions disagree about it, which happens during a reassignment. A single
  *   number would be a guess at which partition to believe
  * @param outOfSyncReplicas
  *   how many replicas of this topic are not in the in-sync set. Countable from metadata alone, so it is an
  *   `Int` and not an `Option`
  * @param messageCount
  *   latest minus earliest, summed over the partitions, or `None` if any partition could not answer
  * @param sizeBytes
  *   the topic's on-disk size across every log directory, or `None` when `describeLogDirs` is unavailable
  * @param produceRate
  *   records appended per second, differenced from the two most recent scrapes. `None` on the first scrape
  *   after a restart, across a partition increase, and whenever either scrape's offsets were incomplete —
  *   never `0`, which would say the topic is idle about a topic nobody has measured yet
  * @param cleanupPolicy
  *   `"delete"`, `"compact"` or `"compact,delete"` as the broker spells it, or `None` when the batched
  *   `describeConfigs` behind the scrape did not cover this topic. A string rather than an enum for the
  *   reason [[TopicDetailDto]] gives: a broker may name a policy this version of KUI has never heard of, and
  *   a cosmetic column must not fail a page
  */
final case class TopicRowDto(
    name: TopicName,
    internal: Boolean,
    partitionCount: Int,
    replicationFactor: Option[Int],
    outOfSyncReplicas: Int,
    offlinePartitions: Int,
    messageCount: Option[Long],
    sizeBytes: Option[Long],
    // Defaulted, so that a producer written before M5 — the gateway's topic-overview aggregation, a
    // fixture — still constructs a row rather than failing to compile. Both are `None` by default and
    // `None` here means "not knowable", which is the truthful answer for a producer that does not measure
    // them.
    produceRate: Option[Double] = None,
    cleanupPolicy: Option[String] = None
)

object TopicRowDto {

  given Codec[TopicRowDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        name <- cursor.get[TopicName]("name")
        internal <- cursor.getOrElse[Boolean]("internal")(false)
        partitionCount <- cursor.get[Int]("partitionCount")
        replicationFactor <- cursor.get[Option[Int]]("replicationFactor")
        outOfSyncReplicas <- cursor.getOrElse[Int]("outOfSyncReplicas")(0)
        offlinePartitions <- cursor.getOrElse[Int]("offlinePartitions")(0)
        messageCount <- cursor.get[Option[Long]]("messageCount")
        sizeBytes <- cursor.get[Option[Long]]("sizeBytes")
        // Absent decodes as `None`, which is what lets a document recorded before M5 added these two — a
        // committed fixture, a browser's cached response — still decode. Absent and null both mean "not
        // knowable" here, and the row renders the same for either.
        produceRate <- cursor.get[Option[Double]]("produceRate")
        cleanupPolicy <- cursor.get[Option[String]]("cleanupPolicy")
      } yield TopicRowDto(
        name,
        internal,
        partitionCount,
        replicationFactor,
        outOfSyncReplicas,
        offlinePartitions,
        messageCount,
        sizeBytes,
        produceRate,
        cleanupPolicy
      ),
    (dto: TopicRowDto) =>
      Json.obj(
        "name" -> dto.name.asJson,
        "internal" -> dto.internal.asJson,
        "partitionCount" -> dto.partitionCount.asJson,
        "replicationFactor" -> dto.replicationFactor.asJson,
        "outOfSyncReplicas" -> dto.outOfSyncReplicas.asJson,
        "offlinePartitions" -> dto.offlinePartitions.asJson,
        "messageCount" -> dto.messageCount.asJson,
        "sizeBytes" -> dto.sizeBytes.asJson,
        "produceRate" -> dto.produceRate.asJson,
        "cleanupPolicy" -> dto.cleanupPolicy.asJson
      )
  )

  given Schema[TopicRowDto] =
    Schema.derived[TopicRowDto].description("One topic as a list row; every Option means 'not knowable'")

  given CanEqual[TopicRowDto, TopicRowDto] = CanEqual.derived
}

/** A topic's detail page, as one document.
  *
  * It **embeds** the row rather than repeating its fields. The list row and the detail header show the same
  * numbers, and two flat records carrying the same ten fields would let the two screens drift apart one field
  * at a time.
  *
  * `cleanupPolicy` is the one exception, and it predates the row's copy of it: M5 put the same field on
  * [[TopicRowDto]], so this one now repeats `row.cleanupPolicy`. It stays because it is on the wire and the
  * browser decodes it, and the two cannot disagree — `TopicDetail.of` fills the summary and the detail from
  * one argument, so a reader that trusts either is reading the same value.
  *
  * @param cleanupPolicy
  *   `"delete"`, `"compact"` or `"compact,delete"` as the broker spells it, or `None` when the configuration
  *   could not be read (a caller without `DESCRIBE_CONFIGS`). A string rather than an enum, because a broker
  *   may name a policy this version of KUI has never heard of and a cosmetic field must not fail a page
  * @param segmentCount
  *   how many log segments the topic has across every replica, or `None` without `describeLogDirs`
  */
final case class TopicDetailDto(
    row: TopicRowDto,
    partitions: List[PartitionDto],
    cleanupPolicy: Option[String],
    segmentCount: Option[Int]
)

object TopicDetailDto {

  given Codec[TopicDetailDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        row <- cursor.get[TopicRowDto]("row")
        partitions <- cursor.getOrElse[List[PartitionDto]]("partitions")(Nil)
        cleanupPolicy <- cursor.get[Option[String]]("cleanupPolicy")
        segmentCount <- cursor.get[Option[Int]]("segmentCount")
      } yield TopicDetailDto(row, partitions, cleanupPolicy, segmentCount),
    (dto: TopicDetailDto) =>
      Json.obj(
        "row" -> dto.row.asJson,
        "partitions" -> dto.partitions.asJson,
        "cleanupPolicy" -> dto.cleanupPolicy.asJson,
        "segmentCount" -> dto.segmentCount.asJson
      )
  )

  given Schema[TopicDetailDto] =
    Schema.derived[TopicDetailDto].description("Everything the topic detail screen shows about one topic")

  given CanEqual[TopicDetailDto, TopicDetailDto] = CanEqual.derived
}
