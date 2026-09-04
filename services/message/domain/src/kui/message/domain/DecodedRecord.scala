package kui.message.domain

import java.time.Instant

import kui.kernel.serde.{PayloadKind, SerdeName, Target}
import kui.kernel.{Offset, PartitionId}

/** Where a record's timestamp came from.
  *
  * Kafka stamps a record either when the producer built it or when the broker appended it, per topic
  * (`message.timestamp.type`). The two can differ by a lot on a topic that was mirrored or replayed, and a
  * user comparing a record's time with a log line needs to know which they are looking at.
  */
enum TimestampType(val wire: String) {
  case CreateTime extends TimestampType("CREATE_TIME")
  case LogAppendTime extends TimestampType("LOG_APPEND_TIME")

  /** Kafka reports `-1` for a record written by a producer old enough not to have stamped one at all. */
  case NoTimestamp extends TimestampType("NO_TIMESTAMP")
}

object TimestampType {
  val All: List[TimestampType] = values.toList

  given CanEqual[TimestampType, TimestampType] = CanEqual.derived
}

/** One half of a record after a serde has read it.
  *
  * `text` is always a `String` — every rendering in KUI starts from text — and `kind` says whether that text
  * is JSON, which is what lets the table view flatten it without re-parsing every cell to find out. `serde`
  * is on every payload rather than only on surprising ones, because "which serde produced this?" is the first
  * question anyone asks about a value that looks wrong.
  */
final case class Decoded(
    text: String,
    kind: PayloadKind,
    serde: SerdeName,
    properties: Map[String, String]
)

object Decoded {

  /** A record with no key at all, or a tombstone's absent value. Empty text and the serde that was tried, so
    * that a client never has to write a second code path for a field that is sometimes missing.
    */
  def absent(serde: SerdeName): Decoded = Decoded("", PayloadKind.Text, serde, Map.empty)

  given CanEqual[Decoded, Decoded] = CanEqual.derived
}

/** Why the serde a user asked for did not work on this record.
  *
  * It is a value on the record and not an error of the request. That is the milestone's central rule about
  * decoding: a record KUI cannot decode is still a record the user came to look at, so it is shown through
  * the fallback with this attached, and the stream carries on (ADR-035). A stream that ended here would hide
  * the one record the screen was opened to find.
  *
  * `cause` is display text under `KuiError`'s rule: no stack trace, no upstream body, no credential. A
  * registry decoder handed a URL with a password in it must not put it here.
  */
final case class DecodeError(target: Target, serde: SerdeName, cause: String)

object DecodeError {
  given CanEqual[DecodeError, DecodeError] = CanEqual.derived
}

/** A header, rendered for display.
  *
  * Kafka header values are bytes and may be absent. By the time a header reaches this type the bytes have
  * been rendered — as text, as a number for the Spring retry and dead-letter headers, or as a hex dump — so
  * that the browser has nothing left to decide.
  */
final case class RenderedHeader(key: String, value: String)

object RenderedHeader {
  given CanEqual[RenderedHeader, RenderedHeader] = CanEqual.derived
}

/** What the domain has after a record has been decoded and masked: the only record shape above the adapters.
  *
  * `keySize`, `valueSize` and `headersSize` are the sizes of the *serialised* bytes, not of the decoded text.
  * They are what an operator uses to find the record that is blowing a quota, and a decoded size would answer
  * a different question — a 40-byte Avro record renders as 400 characters of JSON.
  *
  * `decodeErrors` is a list rather than an `Option` because a key and a value can each fail, for different
  * reasons, on the same record.
  */
final case class DecodedRecord(
    partition: PartitionId,
    offset: Offset,
    timestamp: Instant,
    timestampType: TimestampType,
    key: Decoded,
    value: Decoded,
    headers: List[RenderedHeader],
    keySize: Int,
    valueSize: Int,
    headersSize: Int,
    decodeErrors: List[DecodeError]
) {

  /** Total serialised bytes, which is what a browse's byte budget is spent in. */
  def serialisedSize: Long = keySize.toLong + valueSize.toLong + headersSize.toLong
}

object DecodedRecord {
  given CanEqual[DecodedRecord, DecodedRecord] = CanEqual.derived

  /** Chronological across partitions, then by offset inside one.
    *
    * The second half is not decoration. Records that share a timestamp are common — a batch written in one
    * millisecond — and a sort that used the timestamp alone would reorder a partition, which shows a user a
    * reply above the request that caused it. Ordering by offset within a partition is the only order Kafka
    * itself guarantees, so it is the one that must survive the merge.
    */
  given Ordering[DecodedRecord] =
    Ordering.by(r => (r.timestamp.toEpochMilli, r.partition.value, r.offset.value))
}
