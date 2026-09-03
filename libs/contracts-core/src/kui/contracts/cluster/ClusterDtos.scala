package kui.contracts.cluster

import java.time.Instant

import io.circe.syntax.*
import io.circe.{Codec, HCursor, Json}
import sttp.tapir.Schema

import kui.contracts.ErrorEnvelope.given
import kui.contracts.KernelCodecs.given
import kui.contracts.KernelSchemas.given
import kui.contracts.Section
import kui.kernel.{BrokerId, ClusterId, KafkaClusterId}

/** What the cluster looks like from the outside, as of one scrape.
  *
  * Every number a broker cannot supply is an `Option`, and three of them are always `None` in M1: online,
  * offline and under-replicated partition counts are not derivable from `describeCluster`, the broker set and
  * `describeLogDirs` — the reference product aggregates `describeTopics`, `describeLogDirs` and `listOffsets`
  * to get them, which is the topic sweep that belongs to `services/topic`
  * (`research/kafka/admin-capabilities.md` §1 "Cluster stats", DEVPLAN §10 D5 as corrected by the M1 gate
  * review). They have a field because they will be filled by a later milestone without a breaking change;
  * until then a client renders `—` rather than `0`, which would be a lie.
  *
  * @param kafkaClusterId
  *   the id the brokers report (ADR-031), which is not the configured `ClusterId`. `None` before the first
  *   successful scrape
  * @param controllerKind
  *   `"kraft"`, `"zookeeper"` or `"unknown"`, as a string rather than an enum so that a client meeting a
  *   fourth value from a newer KUI degrades instead of failing to decode
  * @param features
  *   probed capability names, sorted; empty when nothing has been probed yet. Strings for the same
  *   forward-compatibility reason, and because `ClusterFeature` is a `libs/kafka` type this module may not
  *   see (rule A10)
  * @param scrapedAt
  *   when the values were read from the brokers. The browser does not poll; it shows this
  */
final case class ClusterSummaryDto(
    kafkaClusterId: Option[KafkaClusterId],
    version: Option[String],
    controllerId: Option[BrokerId],
    controllerKind: String,
    brokerCount: Int,
    onlinePartitionCount: Option[Int],
    offlinePartitionCount: Option[Int],
    underReplicatedPartitionCount: Option[Int],
    totalDiskUsageBytes: Option[Long],
    features: List[String],
    scrapedAt: Instant
)

object ClusterSummaryDto {

  /** The three controller kinds KUI produces. A decoder accepts any string; these are what it writes. */
  val KRaft: String = "kraft"
  val ZooKeeper: String = "zookeeper"
  val UnknownController: String = "unknown"

  given Codec[ClusterSummaryDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        kafkaClusterId <- cursor.get[Option[KafkaClusterId]]("kafkaClusterId")
        version <- cursor.get[Option[String]]("version")
        controllerId <- cursor.get[Option[BrokerId]]("controllerId")
        controllerKind <- cursor.get[String]("controllerKind")
        brokerCount <- cursor.get[Int]("brokerCount")
        online <- cursor.get[Option[Int]]("onlinePartitionCount")
        offline <- cursor.get[Option[Int]]("offlinePartitionCount")
        underReplicated <- cursor.get[Option[Int]]("underReplicatedPartitionCount")
        totalDisk <- cursor.get[Option[Long]]("totalDiskUsageBytes")
        features <- cursor.getOrElse[List[String]]("features")(Nil)
        scrapedAt <- cursor.get[Instant]("scrapedAt")
      } yield ClusterSummaryDto(
        kafkaClusterId,
        version,
        controllerId,
        controllerKind,
        brokerCount,
        online,
        offline,
        underReplicated,
        totalDisk,
        features,
        scrapedAt
      ),
    (dto: ClusterSummaryDto) =>
      Json.obj(
        "kafkaClusterId" -> dto.kafkaClusterId.asJson,
        "version" -> dto.version.asJson,
        "controllerId" -> dto.controllerId.asJson,
        "controllerKind" -> dto.controllerKind.asJson,
        "brokerCount" -> dto.brokerCount.asJson,
        "onlinePartitionCount" -> dto.onlinePartitionCount.asJson,
        "offlinePartitionCount" -> dto.offlinePartitionCount.asJson,
        "underReplicatedPartitionCount" -> dto.underReplicatedPartitionCount.asJson,
        "totalDiskUsageBytes" -> dto.totalDiskUsageBytes.asJson,
        "features" -> dto.features.asJson,
        "scrapedAt" -> dto.scrapedAt.asJson
      )
  )

  given Schema[ClusterSummaryDto] = Schema
    .derived[ClusterSummaryDto]
    .description("What one scrape found out about a cluster")

  given CanEqual[ClusterSummaryDto, ClusterSummaryDto] = CanEqual.derived
}

/** One cluster as a row: identity outside the section, data inside it.
  *
  * The split is what makes the milestone's dashboard criterion implementable. An unreachable cluster's row
  * "remains clickable", and a row whose id and name lived inside the failed section would have nothing to
  * render and nothing to link to. So `id`, `name`, `readOnly`, `bootstrapServers` and `security` come from
  * configuration and are always present, and only `summary` — the part that needs a live broker — is a
  * `Section` that can be `Unavailable` with a reason.
  *
  * @param bootstrapServers
  *   the address KUI dials, `host:port,host:port`. An operator debugging a dead row needs to see it; it is an
  *   address, never a credential
  */
final case class ClusterRowDto(
    id: ClusterId,
    name: String,
    readOnly: Boolean,
    bootstrapServers: String,
    security: ClusterSecurityDto,
    summary: Section[ClusterSummaryDto]
)

object ClusterRowDto {

  given Codec[ClusterRowDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        id <- cursor.get[ClusterId]("id")
        name <- cursor.get[String]("name")
        readOnly <- cursor.getOrElse[Boolean]("readOnly")(false)
        bootstrapServers <- cursor.get[String]("bootstrapServers")
        security <- cursor.get[ClusterSecurityDto]("security")
        summary <- cursor.get[Section[ClusterSummaryDto]]("summary")
      } yield ClusterRowDto(id, name, readOnly, bootstrapServers, security, summary),
    (dto: ClusterRowDto) =>
      Json.obj(
        "id" -> dto.id.asJson,
        "name" -> dto.name.asJson,
        "readOnly" -> dto.readOnly.asJson,
        "bootstrapServers" -> dto.bootstrapServers.asJson,
        "security" -> dto.security.asJson,
        "summary" -> dto.summary.asJson
      )
  )

  given Schema[ClusterRowDto] = Schema
    .derived[ClusterRowDto]
    .description("One configured cluster: identity always, live data only when a scrape succeeded")

  given CanEqual[ClusterRowDto, ClusterRowDto] = CanEqual.derived
}
