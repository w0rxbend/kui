package kui.config.store

import java.time.Instant
import java.time.temporal.ChronoUnit

import io.circe.{Decoder, DecodingFailure, Encoder, Json}

/** One record as it sits in `__kui_config`, decoded.
  *
  * `version` is the record's own version, starting at 1 and incremented by one per accepted write (STORE-007
  * defines "accepted"). `deleted` marks a logical tombstone. A physical tombstone — a `null` Kafka value — is
  * also honoured on read and is what compaction eventually removes, but KUI writes the logical form, because
  * a `null` value carries no `updatedAt`, no `updatedBy` and no version, which makes "who deleted this
  * cluster, and when" impossible to answer from the log.
  */
final case class StoreRecord(
    envelopeVersion: Int,
    key: StoreKey,
    version: Long,
    updatedAt: Instant,
    updatedBy: String,
    deleted: Boolean,
    payload: Json
)

object StoreRecord {

  /** The only envelope version this KUI writes. */
  val CurrentEnvelopeVersion: Int = 1

  /** The envelope versions this KUI can read. Widening it is a compatible change; narrowing it is not. */
  val SupportedEnvelopeVersions: Set[Int] = Set(1)

  /** A first version of a record: version 1, timestamp truncated to whole seconds. */
  def create(key: StoreKey, payload: Json, updatedBy: String, at: Instant): StoreRecord =
    StoreRecord(
      CurrentEnvelopeVersion,
      key,
      1L,
      at.truncatedTo(ChronoUnit.SECONDS),
      updatedBy,
      deleted = false,
      payload
    )

  /** A logical tombstone at an explicit version, with an empty payload. */
  def tombstone(key: StoreKey, version: Long, updatedBy: String, at: Instant): StoreRecord =
    StoreRecord(
      CurrentEnvelopeVersion,
      key,
      version,
      at.truncatedTo(ChronoUnit.SECONDS),
      updatedBy,
      deleted = true,
      Json.obj()
    )

  /** The successor of this record: the same key, one version higher, a new payload and timestamp. */
  def next(previous: StoreRecord, payload: Json, updatedBy: String, at: Instant): StoreRecord =
    previous.copy(
      envelopeVersion = CurrentEnvelopeVersion,
      version = previous.version + 1L,
      updatedAt = at.truncatedTo(ChronoUnit.SECONDS),
      updatedBy = updatedBy,
      deleted = false,
      payload = payload
    )

  /** Field order is fixed to the order below. Golden files are compared as parsed JSON, so ordering is not
    * load-bearing for correctness, but a stable order keeps a committed file diffable and keeps the
    * console-consumer dump of `docs/operations/metadata-store.md` §5 readable.
    */
  given Encoder[StoreRecord] =
    Encoder.instance { record =>
      Json.obj(
        "envelopeVersion" -> Json.fromInt(record.envelopeVersion),
        "key" -> Json.fromString(record.key.render),
        "version" -> Json.fromLong(record.version),
        "updatedAt" -> Json.fromString(record.updatedAt.truncatedTo(ChronoUnit.SECONDS).toString),
        "updatedBy" -> Json.fromString(record.updatedBy),
        "deleted" -> Json.fromBoolean(record.deleted),
        "payload" -> record.payload
      )
    }

  /** Decodes an envelope, reporting the store's own named failures rather than a Circe message.
    *
    * Two compatibility rules, and the difference between them is deliberate. **Unknown fields are ignored** —
    * an added field is compatible by construction, because a reader that does not know about it behaves
    * exactly as it did before. **An unknown `envelopeVersion` is refused** — a bumped version number is the
    * writer saying "you cannot understand this", and silently skipping such a record would leave this KUI
    * serving a stale view while reporting itself healthy.
    */
  def fromJson(json: Json): Either[StoreError, StoreRecord] = {
    val cursor = json.hcursor
    def field[A: Decoder](name: String): Either[StoreError, A] =
      cursor
        .get[A](name)
        .left
        .map(f => StoreError.MalformedRecord(rawKeyOf(json), s"field '$name': ${f.message}"))

    for {
      envelopeVersion <- field[Int]("envelopeVersion")
      _ <- Either.cond(
        SupportedEnvelopeVersions.contains(envelopeVersion),
        (),
        StoreError.UnsupportedEnvelope(envelopeVersion, SupportedEnvelopeVersions)
      )
      rawKey <- field[String]("key")
      key <- StoreKey.parse(rawKey)
      version <- field[Long]("version")
      updatedAtRaw <- field[String]("updatedAt")
      updatedAt <- parseInstant(rawKey, updatedAtRaw)
      updatedBy <- field[String]("updatedBy")
      deleted <- field[Boolean]("deleted")
      payload <- field[Json]("payload")
    } yield StoreRecord(envelopeVersion, key, version, updatedAt, updatedBy, deleted, payload)
  }

  /** The same, additionally checking that the Kafka record key and the key inside the envelope agree.
    *
    * The key is duplicated inside the envelope so that an exported file is self-describing when the key
    * column is lost. Once it is duplicated, the two can disagree, so replay checks.
    */
  def fromJsonWithKey(rawKey: String, json: Json): Either[StoreError, StoreRecord] =
    fromJson(json).flatMap { record =>
      Either.cond(
        record.key.render == rawKey,
        record,
        StoreError.MalformedRecord(rawKey, s"the envelope names key '${record.key.render}'")
      )
    }

  given Decoder[StoreRecord] =
    Decoder.instance(cursor =>
      fromJson(cursor.value).left.map(e => DecodingFailure(e.message, cursor.history))
    )

  given CanEqual[StoreRecord, StoreRecord] = CanEqual.derived

  private def rawKeyOf(json: Json): String =
    json.hcursor.get[String]("key").getOrElse("<unknown>")

  private def parseInstant(rawKey: String, raw: String): Either[StoreError, Instant] =
    try Right(Instant.parse(raw))
    catch {
      case _: java.time.format.DateTimeParseException =>
        Left(StoreError.MalformedRecord(rawKey, s"field 'updatedAt': '$raw' is not an ISO-8601 instant"))
    }
}
