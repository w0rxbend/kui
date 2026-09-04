package kui.contracts.cluster

import io.circe.syntax.*
import io.circe.{Codec, HCursor, Json}
import sttp.tapir.Schema

import kui.contracts.KernelCodecs.given
import kui.contracts.KernelSchemas.given
import kui.kernel.BrokerId

/** One node of a cluster, as the brokers list page shows it.
  *
  * `rack` is `Option` because `Node.rack()` is nullable and a broker with no rack must read as "none", not as
  * an empty string that sorts between two real racks. `partitionCount` and `leaderCount` have no source in M1
  * for the reason `ClusterSummaryDto` records, so they are always `None`; replica counts and the skew
  * percentages *are* derivable from `describeLogDirs` and do ship (BR-001).
  *
  * @param replicaCount
  *   how many partition replicas this broker holds, counted from the log directories it reports. This is the
  *   *total*, in-sync and lagging alike. It was called `inSyncReplicaCount` until 2026-09-04 and was filled
  *   from this same total, which is the same number on a healthy cluster and a false one exactly when a
  *   broker falls behind: with one broker of three stopped, the survivors kept reporting their pre-failure
  *   figure as if every replica were still caught up. The in-sync count cannot be had from the calls this
  *   service makes — `describeCluster` and `describeLogDirs` know nothing about ISR, and the only source is
  *   `describeTopics`, one call per batch of topics, which the cluster service does not sweep
  *   (`research/kafka/admin-capabilities.md`). So the field says what it holds instead of claiming a number
  *   nobody computed
  * @param replicaSkewPercent
  *   how far this broker's replica count is from the cluster's mean, as a percentage computed server-side so
  *   that the table, a CSV export and any other client round the same way. `None` means "not computable" — a
  *   cluster with no partitions — which renders `—`, never `0.00%`
  */
final case class BrokerDto(
    id: BrokerId,
    host: String,
    port: Int,
    rack: Option[String],
    isController: Boolean,
    partitionCount: Option[Int],
    leaderCount: Option[Int],
    replicaCount: Option[Int],
    replicaSkewPercent: Option[Double],
    leaderSkewPercent: Option[Double],
    diskUsageBytes: Option[Long],
    segmentCount: Option[Int]
)

object BrokerDto {

  given Codec[BrokerDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        id <- cursor.get[BrokerId]("id")
        host <- cursor.get[String]("host")
        port <- cursor.get[Int]("port")
        rack <- cursor.get[Option[String]]("rack")
        isController <- cursor.getOrElse[Boolean]("isController")(false)
        partitionCount <- cursor.get[Option[Int]]("partitionCount")
        leaderCount <- cursor.get[Option[Int]]("leaderCount")
        replicaCount <- cursor.get[Option[Int]]("replicaCount")
        replicaSkew <- cursor.get[Option[Double]]("replicaSkewPercent")
        leaderSkew <- cursor.get[Option[Double]]("leaderSkewPercent")
        diskUsageBytes <- cursor.get[Option[Long]]("diskUsageBytes")
        segmentCount <- cursor.get[Option[Int]]("segmentCount")
      } yield BrokerDto(
        id,
        host,
        port,
        rack,
        isController,
        partitionCount,
        leaderCount,
        replicaCount,
        replicaSkew,
        leaderSkew,
        diskUsageBytes,
        segmentCount
      ),
    (dto: BrokerDto) =>
      Json.obj(
        "id" -> dto.id.asJson,
        "host" -> dto.host.asJson,
        "port" -> dto.port.asJson,
        "rack" -> dto.rack.asJson,
        "isController" -> dto.isController.asJson,
        "partitionCount" -> dto.partitionCount.asJson,
        "leaderCount" -> dto.leaderCount.asJson,
        "replicaCount" -> dto.replicaCount.asJson,
        "replicaSkewPercent" -> dto.replicaSkewPercent.asJson,
        "leaderSkewPercent" -> dto.leaderSkewPercent.asJson,
        "diskUsageBytes" -> dto.diskUsageBytes.asJson,
        "segmentCount" -> dto.segmentCount.asJson
      )
  )

  given Schema[BrokerDto] = Schema.derived[BrokerDto].description("One broker node and what is on it")

  given CanEqual[BrokerDto, BrokerDto] = CanEqual.derived
}

/** One setting of one broker, as `describeConfigs` reports it.
  *
  * @param value
  *   `None` when the broker marks the setting sensitive — Kafka returns `null` for those, and KUI does not
  *   invent a placeholder. `isSensitive` is carried separately so a client can say "hidden by the broker"
  *   rather than "not set"
  * @param documentation
  *   the broker's own description of the setting, available from Kafka 2.6. `None` on an older broker
  */
final case class BrokerConfigEntryDto(
    name: String,
    value: Option[String],
    source: String,
    isSensitive: Boolean,
    isReadOnly: Boolean,
    documentation: Option[String],
    synonyms: List[String]
)

object BrokerConfigEntryDto {

  given Codec[BrokerConfigEntryDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        name <- cursor.get[String]("name")
        value <- cursor.get[Option[String]]("value")
        source <- cursor.get[String]("source")
        isSensitive <- cursor.getOrElse[Boolean]("isSensitive")(false)
        isReadOnly <- cursor.getOrElse[Boolean]("isReadOnly")(false)
        documentation <- cursor.get[Option[String]]("documentation")
        synonyms <- cursor.getOrElse[List[String]]("synonyms")(Nil)
      } yield BrokerConfigEntryDto(name, value, source, isSensitive, isReadOnly, documentation, synonyms),
    (dto: BrokerConfigEntryDto) =>
      Json.obj(
        "name" -> dto.name.asJson,
        "value" -> dto.value.asJson,
        "source" -> dto.source.asJson,
        "isSensitive" -> dto.isSensitive.asJson,
        "isReadOnly" -> dto.isReadOnly.asJson,
        "documentation" -> dto.documentation.asJson,
        "synonyms" -> dto.synonyms.asJson
      )
  )

  given Schema[BrokerConfigEntryDto] =
    Schema.derived[BrokerConfigEntryDto].description("One broker setting, its value and where it came from")

  given CanEqual[BrokerConfigEntryDto, BrokerConfigEntryDto] = CanEqual.derived
}

/** One log directory of one broker.
  *
  * `error` is per directory, not per request: `describeLogDirs` reports a failed disk by attaching an error
  * to that directory while the rest of the response is good, and a page that dropped the whole broker because
  * one disk is offline would hide exactly the fact the operator opened the page to find.
  *
  * @param totalBytes
  *   the disk's size, available from Kafka 3.3. `None` on an older broker, and `None` for an offline
  *   directory, which reports no sizes at all
  */
final case class LogDirDto(
    brokerId: BrokerId,
    path: String,
    error: Option[String],
    totalBytes: Option[Long],
    usableBytes: Option[Long],
    topicCount: Int,
    partitionCount: Int
)

object LogDirDto {

  given Codec[LogDirDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        brokerId <- cursor.get[BrokerId]("brokerId")
        path <- cursor.get[String]("path")
        error <- cursor.get[Option[String]]("error")
        totalBytes <- cursor.get[Option[Long]]("totalBytes")
        usableBytes <- cursor.get[Option[Long]]("usableBytes")
        topicCount <- cursor.getOrElse[Int]("topicCount")(0)
        partitionCount <- cursor.getOrElse[Int]("partitionCount")(0)
      } yield LogDirDto(brokerId, path, error, totalBytes, usableBytes, topicCount, partitionCount),
    (dto: LogDirDto) =>
      Json.obj(
        "brokerId" -> dto.brokerId.asJson,
        "path" -> dto.path.asJson,
        "error" -> dto.error.asJson,
        "totalBytes" -> dto.totalBytes.asJson,
        "usableBytes" -> dto.usableBytes.asJson,
        "topicCount" -> dto.topicCount.asJson,
        "partitionCount" -> dto.partitionCount.asJson
      )
  )

  given Schema[LogDirDto] =
    Schema.derived[LogDirDto].description("One log directory of one broker, with its own error if it failed")

  given CanEqual[LogDirDto, LogDirDto] = CanEqual.derived
}
