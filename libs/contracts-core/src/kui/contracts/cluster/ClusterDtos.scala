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

/** How much of a stated window a cluster had an active controller.
  *
  * The window travels with the figure because the figure is meaningless without it: "99.98 %" is a claim
  * about a period, and a browser that printed "over the last 6h" from a literal of its own would keep
  * printing it the day the window was retuned. The label is built from `windowSeconds`.
  *
  * @param percent
  *   the share of the observed scrapes in which a controller was present, to two decimals. `None` until the
  *   window has been collecting for `windowSeconds` — a percentage computed over the four minutes since a
  *   restart and printed as a day is a worse answer than no answer, because the reader cannot tell which one
  *   they are looking at
  * @param windowSeconds
  *   the period the percentage is over
  * @param coverageSeconds
  *   how long the window has actually been collecting, never more than `windowSeconds`. It exists so that the
  *   refusal can be explained rather than merely shown: a client with a `None` can say "collecting, 41m of
  *   6h" instead of drawing an empty ring
  */
final case class ControllerUptimeDto(
    percent: Option[Double],
    windowSeconds: Long,
    coverageSeconds: Long
)

object ControllerUptimeDto {

  given Codec[ControllerUptimeDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        percent <- cursor.get[Option[Double]]("percent")
        windowSeconds <- cursor.get[Long]("windowSeconds")
        coverageSeconds <- cursor.getOrElse[Long]("coverageSeconds")(0L)
      } yield ControllerUptimeDto(percent, windowSeconds, coverageSeconds),
    (dto: ControllerUptimeDto) =>
      Json.obj(
        "percent" -> dto.percent.asJson,
        "windowSeconds" -> dto.windowSeconds.asJson,
        "coverageSeconds" -> dto.coverageSeconds.asJson
      )
  )

  given Schema[ControllerUptimeDto] = Schema
    .derived[ControllerUptimeDto]
    .description("How much of a stated window this cluster had an active controller")

  given CanEqual[ControllerUptimeDto, ControllerUptimeDto] = CanEqual.derived
}

/** What the cluster looks like from the outside, as of one scrape.
  *
  * Every number a broker cannot supply is an `Option`. The three partition counts — online, offline and
  * under-replicated — come from a `describeTopics` sweep, which the cluster service now makes on a cadence of
  * its own (`research/kafka/admin-capabilities.md` §1 "Cluster stats"). They are still `Option` and they are
  * still absent together, because the sweep refuses as a whole: one topic it could not describe and all three
  * are `None`, since a sum over some of the topics is a number that looks measured and is wrong in the
  * direction that reassures. A client renders `—` rather than `0`, which would be a lie.
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
    scrapedAt: Instant,
    /** `None` when this deployment keeps no controller-uptime window at all, which is a different statement
      * from a window that is not yet full — that one is present with a `None` percentage and a stated length.
      * Defaulted so that a caller written before the field existed still compiles and still means "not kept".
      */
    controllerUptime: Option[ControllerUptimeDto] = None
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
        // Read leniently, as `ClusterRowDto` reads its own two later fields: a recorded document or a
        // gateway from before this field existed decodes to "no window is kept", which is the answer that
        // draws nothing rather than the one that draws a zero.
        uptime <- cursor.getOrElse[Option[ControllerUptimeDto]]("controllerUptime")(None)
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
        scrapedAt,
        uptime
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
        "scrapedAt" -> dto.scrapedAt.asJson,
        "controllerUptime" -> dto.controllerUptime.asJson
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
  * @param version
  *   the metadata store version this profile was resolved at, which is what a write has to send back in
  *   `If-Match`. `None` for a cluster that has never been stored — one that comes only from the deployment's
  *   configuration file — because there is no version for such a cluster to be replaced at, and a screen
  *   sending zero would be asking to *create* one that already exists.
  * @param origin
  *   where this cluster's definition comes from: `"static"`, `"stored"` or `"static-then-stored"`. It exists
  *   so that the administration screen can say "this one is in the configuration file" *before* the operator
  *   fills in a form, rather than offering an edit and a delete that the server will refuse. A string rather
  *   than an enum for the reason `controllerKind` is one: a client meeting a fourth value from a newer KUI
  *   should degrade, not fail to decode
  */
final case class ClusterRowDto(
    id: ClusterId,
    name: String,
    readOnly: Boolean,
    bootstrapServers: String,
    security: ClusterSecurityDto,
    summary: Section[ClusterSummaryDto],
    version: Option[Long] = None,
    origin: String = ClusterRowDto.OriginStatic
)

object ClusterRowDto {

  /** Only this deployment's configuration file names it. It cannot be edited or removed from the interface:
    * the store record would go and the file would put it straight back on the next resolve.
    */
  val OriginStatic: String = "static"

  /** Only a metadata-store record names it. Editable and removable. */
  val OriginStored: String = "stored"

  /** Both do, and the stored record won. Editable, because a store write still wins; not removable, for the
    * reason `OriginStatic` gives.
    */
  val OriginStaticThenStored: String = "static-then-stored"

  /** Whether this cluster's definition can be changed from the interface at all. */
  def isEditable(dto: ClusterRowDto): Boolean = dto.origin != OriginStatic

  /** Whether it can be removed. Narrower than [[isEditable]] on purpose: a cluster the configuration file
    * also names comes back on the next resolve, so removing it is a button that appears to do nothing.
    */
  def isRemovable(dto: ClusterRowDto): Boolean = dto.origin == OriginStored

  given Codec[ClusterRowDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        id <- cursor.get[ClusterId]("id")
        name <- cursor.get[String]("name")
        readOnly <- cursor.getOrElse[Boolean]("readOnly")(false)
        bootstrapServers <- cursor.get[String]("bootstrapServers")
        security <- cursor.get[ClusterSecurityDto]("security")
        summary <- cursor.get[Section[ClusterSummaryDto]]("summary")
        // Read leniently: a gateway or a recorded document from before these two fields existed still
        // decodes, and lands on "configured in a file", which is the answer that offers no buttons. The
        // safe default is the one that refuses to edit, not the one that tries.
        version <- cursor.getOrElse[Option[Long]]("version")(None)
        origin <- cursor.getOrElse[String]("origin")(ClusterRowDto.OriginStatic)
      } yield ClusterRowDto(id, name, readOnly, bootstrapServers, security, summary, version, origin),
    (dto: ClusterRowDto) =>
      Json.obj(
        "id" -> dto.id.asJson,
        "name" -> dto.name.asJson,
        "readOnly" -> dto.readOnly.asJson,
        "bootstrapServers" -> dto.bootstrapServers.asJson,
        "security" -> dto.security.asJson,
        "summary" -> dto.summary.asJson,
        "version" -> dto.version.asJson,
        "origin" -> dto.origin.asJson
      )
  )

  given Schema[ClusterRowDto] = Schema
    .derived[ClusterRowDto]
    .description("One configured cluster: identity always, live data only when a scrape succeeded")

  given CanEqual[ClusterRowDto, ClusterRowDto] = CanEqual.derived
}
