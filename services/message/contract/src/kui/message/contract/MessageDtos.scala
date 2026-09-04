package kui.message.contract

import java.time.Instant

import io.circe.syntax.*
import io.circe.{Codec, HCursor, Json}
import sttp.tapir.Schema

import kui.contracts.ErrorEnvelope.given
import kui.contracts.KernelCodecs.given
import kui.contracts.KernelSchemas.given
import kui.contracts.message.{DecodeErrorDto, DecodedPayloadDto}
import kui.contracts.paging.PageDto
import kui.kernel.{Offset, PartitionId}

/** One record, as it crosses the process boundary (ADR-035).
  *
  * The field names are the envelope's, exactly, because two things decode this document — the message
  * service's own tests and the browser — and they compile against this file. That is the whole defence
  * against M1's dashboard defect, where a browser decoded a document nobody sent and both suites were green:
  * there is one type, so there is nothing to drift.
  *
  * ==What is deliberately not here==
  *
  * `keyFormat`, `valueFormat`, `keySchemaId` and `valueSchemaId`, which the reference product carries on
  * every record. Its own API deprecated them, and each is a *property of the decode*, which
  * [[kui.contracts.message.DecodedPayloadDto]] already carries beside the text it explains. Four top-level
  * fields that are null for every record decoded by a plain String serde is four fields every client learns
  * to ignore.
  *
  * @param timestampType
  *   `CreateTime` or `LogAppendTime` — which clock produced `timestamp`. Without it, a timestamp search that
  *   returns nothing is inexplicable: the topic may be configured so that the broker overwrites every
  *   producer's timestamp on append, and a user comparing against their own application's clock is comparing
  *   against the wrong one
  * @param headers
  *   the record's headers, keyed. A record being *written* carries an ordered list instead
  *   ([[kui.contracts.message.HeaderDto]] explains why); a record being read is a table on a screen
  * @param keySize
  *   the serialised size in bytes, before decoding. It is here because a record whose value renders as three
  *   characters and weighs 2 MiB is a record worth looking at, and nothing else on this document would say so
  * @param deserializeErrors
  *   every serde that failed on this record. **Required, never absent**: a decoder that defaulted this to an
  *   empty list would turn "the producer forgot the field" into "this record decoded perfectly", and the
  *   whole point of delivering an undecodable record is that the failure travels with it
  */
final case class MessageDto(
    partition: PartitionId,
    offset: Offset,
    timestamp: Instant,
    timestampType: String,
    key: DecodedPayloadDto,
    value: DecodedPayloadDto,
    headers: Map[String, String],
    keySize: Int,
    valueSize: Int,
    headersSize: Int,
    deserializeErrors: List[DecodeErrorDto]
)

object MessageDto {

  /** The two clocks a Kafka record's timestamp can come from, spelled the way Kafka spells them. */
  object TimestampType {
    val CreateTime: String = "CreateTime"
    val LogAppendTime: String = "LogAppendTime"
  }

  given Codec[MessageDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        partition <- cursor.get[PartitionId]("partition")
        offset <- cursor.get[Offset]("offset")
        timestamp <- cursor.get[Instant]("timestamp")
        timestampType <- cursor.get[String]("timestampType")
        key <- cursor.get[DecodedPayloadDto]("key")
        value <- cursor.get[DecodedPayloadDto]("value")
        headers <- cursor.getOrElse[Map[String, String]]("headers")(Map.empty)
        keySize <- cursor.get[Int]("keySize")
        valueSize <- cursor.get[Int]("valueSize")
        headersSize <- cursor.get[Int]("headersSize")
        // Required, and the one list on this record that is. A record with no headers is a record with
        // `{}`, which is a fact; a record with no `deserializeErrors` field is a document that did not come
        // from this service, and reading it as "nothing failed" is how a wrong document becomes a clean
        // screen.
        deserializeErrors <- cursor.get[List[DecodeErrorDto]]("deserializeErrors")
      } yield MessageDto(
        partition,
        offset,
        timestamp,
        timestampType,
        key,
        value,
        headers,
        keySize,
        valueSize,
        headersSize,
        deserializeErrors
      ),
    (dto: MessageDto) =>
      Json.obj(
        "partition" -> dto.partition.asJson,
        "offset" -> dto.offset.asJson,
        "timestamp" -> dto.timestamp.asJson,
        "timestampType" -> dto.timestampType.asJson,
        "key" -> dto.key.asJson,
        "value" -> dto.value.asJson,
        "headers" -> dto.headers.asJson,
        "keySize" -> dto.keySize.asJson,
        "valueSize" -> dto.valueSize.asJson,
        "headersSize" -> dto.headersSize.asJson,
        "deserializeErrors" -> dto.deserializeErrors.asJson
      )
  )

  given Schema[MessageDto] = Schema
    .derived[MessageDto]
    .description("One Kafka record, decoded, with every serde failure that happened while decoding it")

  given CanEqual[MessageDto, MessageDto] = CanEqual.derived
}

/** One page of records.
  *
  * It is `PageDto[MessageDto]` and nothing more, deliberately. A page of messages differs from a page of
  * topics only in what the items are, and the continuation token the message endpoints need is
  * `PageInfo.nextPageToken` — the field `PageDto`'s own documentation says exists for exactly these
  * endpoints. Declaring a second page shape here would be the duplication that module was created to prevent,
  * and the two shapes would then disagree about whether `totalItems` may be absent.
  */
type MessagePageDto = PageDto[MessageDto]

/** A serde KUI thinks could decode this topic, and why it thinks so (MS-009).
  *
  * The list is ordered best-first, and `reason` is required rather than optional because a suggestion a user
  * cannot account for is a suggestion they cannot act on: "configured for topics matching `orders-*`" and "no
  * configuration matched; every topic can be read as a string" lead to different next steps.
  *
  * @param target
  *   `key` or `value` — the same two words [[kui.contracts.message.DecodeErrorDto.Target]] uses
  * @param preferred
  *   whether this is the one KUI will use if the caller names none. Exactly one suggestion per target is
  *   preferred, which is what lets a client render a pre-selected dropdown without a second request
  */
final case class SerdeSuggestionDto(name: String, target: String, preferred: Boolean, reason: String)

object SerdeSuggestionDto {

  given Codec[SerdeSuggestionDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        name <- cursor.get[String]("name")
        target <- cursor.get[String]("target")
        preferred <- cursor.getOrElse[Boolean]("preferred")(false)
        reason <- cursor.get[String]("reason")
      } yield SerdeSuggestionDto(name, target, preferred, reason),
    (dto: SerdeSuggestionDto) =>
      Json.obj(
        "name" -> dto.name.asJson,
        "target" -> dto.target.asJson,
        "preferred" -> dto.preferred.asJson,
        "reason" -> dto.reason.asJson
      )
  )

  given Schema[SerdeSuggestionDto] = Schema
    .derived[SerdeSuggestionDto]
    .description("A serde that could decode this topic's keys or values, and why it was suggested")

  given CanEqual[SerdeSuggestionDto, SerdeSuggestionDto] = CanEqual.derived
}
