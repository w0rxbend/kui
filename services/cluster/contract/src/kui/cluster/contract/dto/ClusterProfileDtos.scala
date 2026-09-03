package kui.cluster.contract.dto

import java.time.Instant

import io.circe.syntax.*
import io.circe.{Codec, HCursor, Json}
import sttp.tapir.Schema

import kui.contracts.ErrorEnvelope.given
import kui.contracts.KernelCodecs.given
import kui.contracts.KernelSchemas.given
import kui.contracts.cluster.ClusterSecurityDto
import kui.kernel.ClusterId

/** A cluster's resolved connection profile, as a consumer of the cluster service needs to see it.
  *
  * `version` is the metadata store record's optimistic version (ADR-042), and it is also the value of the
  * `ETag` header. A consumer compares versions, never payloads: rebuilding every Kafka client is expensive
  * and must happen when the profile actually changed, not when a scrape happened to re-serialise it.
  *
  * **Nothing secret is here, and that is a decision for M1 specifically.** ADR-036 says services fetch
  * "resolved profiles", and a service that must build its own Kafka client eventually needs credentials — but
  * no such service exists yet, and shipping a credential-distributing endpoint a milestone before its first
  * caller means shipping an untested secret path. So the username, the password, the keystore bytes and the
  * rendered JAAS string are absent, and `propertyKeys` publishes the *keys* of ADR-022's override map without
  * any of its values, because that map may legitimately contain `sasl.jaas.config`. M2's first consumer
  * decides how it gets credentials: a second endpoint gated on the caller's audience, or reading the store
  * itself with the key it already holds. Recorded as milestone-scoped debt.
  *
  * @param propertyKeys
  *   the keys of the `properties` override map, sorted. A consumer can see *that* a cluster overrides
  *   `ssl.endpoint.identification.algorithm` without being told what it was set to
  */
final case class ClusterProfileDto(
    id: ClusterId,
    name: String,
    version: Long,
    readOnly: Boolean,
    bootstrapServers: String,
    security: ClusterSecurityDto,
    adminTimeoutMs: Long,
    adminBatchSize: Int,
    adminParallelism: Int,
    propertyKeys: List[String],
    updatedAt: Instant
)

object ClusterProfileDto {

  /** The `ETag` for a profile version: a strong tag, quoted, never `W/`-prefixed.
    *
    * One function so that the header the server writes and the header a test asserts cannot be written twice
    * and drift. Comparison is byte equality after the quotes are trimmed.
    */
  def etagOf(version: Long): String = s"\"$version\""

  given Codec[ClusterProfileDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        id <- cursor.get[ClusterId]("id")
        name <- cursor.get[String]("name")
        version <- cursor.get[Long]("version")
        readOnly <- cursor.getOrElse[Boolean]("readOnly")(false)
        bootstrapServers <- cursor.get[String]("bootstrapServers")
        security <- cursor.get[ClusterSecurityDto]("security")
        adminTimeoutMs <- cursor.get[Long]("adminTimeoutMs")
        adminBatchSize <- cursor.get[Int]("adminBatchSize")
        adminParallelism <- cursor.get[Int]("adminParallelism")
        propertyKeys <- cursor.getOrElse[List[String]]("propertyKeys")(Nil)
        updatedAt <- cursor.get[Instant]("updatedAt")
      } yield ClusterProfileDto(
        id,
        name,
        version,
        readOnly,
        bootstrapServers,
        security,
        adminTimeoutMs,
        adminBatchSize,
        adminParallelism,
        propertyKeys,
        updatedAt
      ),
    (dto: ClusterProfileDto) =>
      Json.obj(
        "id" -> dto.id.asJson,
        "name" -> dto.name.asJson,
        "version" -> dto.version.asJson,
        "readOnly" -> dto.readOnly.asJson,
        "bootstrapServers" -> dto.bootstrapServers.asJson,
        "security" -> dto.security.asJson,
        "adminTimeoutMs" -> dto.adminTimeoutMs.asJson,
        "adminBatchSize" -> dto.adminBatchSize.asJson,
        "adminParallelism" -> dto.adminParallelism.asJson,
        "propertyKeys" -> dto.propertyKeys.asJson,
        "updatedAt" -> dto.updatedAt.asJson
      )
  )

  given Schema[ClusterProfileDto] = Schema
    .derived[ClusterProfileDto]
    .description("A cluster's resolved connection settings, with every credential removed")

  given CanEqual[ClusterProfileDto, ClusterProfileDto] = CanEqual.derived
}

/** One change notification.
  *
  * It carries no profile on purpose. A consumer that sees a version it does not hold fetches the profile,
  * which keeps the stream to four fields, keeps credentials off every subscriber's socket, and means a
  * dropped frame costs one fetch rather than leaving a service talking to a cluster with stale settings.
  *
  * @param change
  *   `"updated"` or `"removed"`. A removal is a first-class event, never inferred from silence: a cluster an
  *   operator deleted must make every consumer drop its clients, and "I have heard nothing" is
  *   indistinguishable from a healthy quiet cluster. A string rather than an enum, so a consumer meeting a
  *   future kind renders it instead of failing to decode the frame
  */
final case class ClusterChangeDto(id: ClusterId, version: Long, change: String, at: Instant)

object ClusterChangeDto {

  val Updated: String = "updated"
  val Removed: String = "removed"

  given Codec[ClusterChangeDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        id <- cursor.get[ClusterId]("id")
        version <- cursor.get[Long]("version")
        change <- cursor.get[String]("change")
        at <- cursor.get[Instant]("at")
      } yield ClusterChangeDto(id, version, change, at),
    (dto: ClusterChangeDto) =>
      Json.obj(
        "id" -> dto.id.asJson,
        "version" -> dto.version.asJson,
        "change" -> dto.change.asJson,
        "at" -> dto.at.asJson
      )
  )

  given Schema[ClusterChangeDto] =
    Schema.derived[ClusterChangeDto].description("A cluster's profile changed or the cluster was removed")

  given CanEqual[ClusterChangeDto, ClusterChangeDto] = CanEqual.derived
}

/** What `GET .../profile` answered with: the profile, or "you already have it".
  *
  * A type rather than an `Option[ClusterProfileDto]` because the two outcomes carry different HTTP status
  * codes and a 304 is not a missing profile — it is the strongest possible statement that the caller's copy
  * is current. Modelling it as an absence would make every consumer write the same three-line reconstruction.
  */
enum ProfileResult {

  /** 200: here is the profile, and here is the tag to send back next time. */
  case Current(etag: String, profile: ClusterProfileDto)

  /** 304: what you hold is current. No body, by definition of the status code. */
  case NotModified(etag: String)

  /** The tag, whichever outcome this is. Not called `etag`: that is already the name of both cases' field,
    * and a member of the same name on the enum itself would be an override of them.
    */
  def entityTag: String = this match {
    case Current(tag, _) => tag
    case NotModified(tag) => tag
  }
}

object ProfileResult {

  /** The only way a 200 should be built: the tag is derived from the version, so the two cannot disagree. */
  def current(profile: ClusterProfileDto): ProfileResult =
    Current(ClusterProfileDto.etagOf(profile.version), profile)

  /** The only way a 304 should be built, for the same reason. */
  def notModified(version: Long): ProfileResult = NotModified(ClusterProfileDto.etagOf(version))

  /** Whether a caller's `If-None-Match` header means "I already have version `version`".
    *
    * Quotes are trimmed before comparing, because a proxy may or may not preserve them, and `*` means "send
    * it regardless" — the wildcard is about preconditions on writes, and on a read the useful reading is
    * "unconditional".
    */
  def isCurrent(ifNoneMatch: Option[String], version: Long): Boolean =
    ifNoneMatch.map(_.trim) match {
      case None => false
      case Some("*") => false
      case Some(tag) => tag.stripPrefix("W/").replace("\"", "") == version.toString
    }

  given CanEqual[ProfileResult, ProfileResult] = CanEqual.derived
}
