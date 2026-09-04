package kui.message.domain

import java.time.Instant

import cats.data.NonEmptySet

import kui.kernel.error.{DomainError, FieldError, KuiError}
import kui.kernel.serde.SerdeName
import kui.kernel.{ClusterId, Offset, OffsetRange, PartitionId, TopicName}

/** The three things M3 does that change a cluster: publishing a record, copying a range of records into
  * another topic, and emptying a topic's partitions.
  *
  * They are gathered in one file because they share one rule, and the rule is more important than the
  * grouping: **every one of them is a mutation** in the sense of ADR-047, so every one is refused on a
  * read-only cluster before any Kafka client is touched, carries a marker the endpoint list can be enumerated
  * against, and writes exactly one audit record whether it succeeded or failed. M3 is the first milestone in
  * KUI to change anything, so M3 is where that rule starts being kept.
  */
enum MutationKind(val wire: String) {
  case Produce extends MutationKind("PRODUCE")
  case Resend extends MutationKind("RESEND")
  case Purge extends MutationKind("PURGE")
}

object MutationKind {
  val All: List[MutationKind] = values.toList

  given CanEqual[MutationKind, MutationKind] = CanEqual.derived
}

/** A record to publish, `count` times.
  *
  * `key` and `value` are `Option[String]` and an absent one is a **null**, not an empty string. That
  * distinction is the whole of tombstones: on a compacted topic a null value deletes the key and an empty
  * string is a record whose value happens to be empty, and a form that maps one to the other silently breaks
  * compaction for whoever uses it.
  *
  * Placeholder expansion — `{{count}}`, `{{uuid}}`, `{{timestamp}}` — is a browser feature (ADR-029). The
  * server produces exactly what it was handed, `count` times, so that what a user sees in the form is what
  * lands in the topic.
  */
final case class ProduceRequest private (
    cluster: ClusterId,
    topic: TopicName,
    partition: Option[PartitionId],
    key: Option[String],
    value: Option[String],
    headers: List[(String, String)],
    keySerde: Option[SerdeName],
    valueSerde: Option[SerdeName],
    keySerdeProperties: Map[String, String],
    valueSerdeProperties: Map[String, String],
    count: Int
) {
  val kind: MutationKind = MutationKind.Produce
}

object ProduceRequest {

  /** The most records one form submission may publish (`kui.message.produce.maxCount`). */
  val DefaultMaxCount: Int = 1000

  def of(
      cluster: ClusterId,
      topic: TopicName,
      partition: Option[PartitionId],
      key: Option[String],
      value: Option[String],
      headers: List[(String, String)],
      keySerde: Option[SerdeName],
      valueSerde: Option[SerdeName],
      keySerdeProperties: Map[String, String],
      valueSerdeProperties: Map[String, String],
      count: Option[Int],
      maxCount: Int = DefaultMaxCount
  ): Either[KuiError, ProduceRequest] = {
    val requested = count.getOrElse(1)
    if requested < 1 || requested > maxCount then
      // Unlike a browse limit, this one is refused rather than clamped: a user who asked for a million
      // records and silently got a thousand has written a thousand records they did not mean to write, and
      // there is no undo for that.
      Left(
        DomainError.InvariantViolation(
          s"a produce may publish between 1 and $maxCount records",
          List(FieldError.of("count", s"between 1 and $maxCount"))
        )
      )
    else if headers.exists(_._1.isEmpty) then
      Left(
        DomainError.InvariantViolation(
          "a header has no name",
          List(FieldError.of("headers", "a non-empty name for every header"))
        )
      )
    else
      Right(
        ProduceRequest(
          cluster,
          topic,
          partition,
          key,
          value,
          headers,
          keySerde,
          valueSerde,
          keySerdeProperties,
          valueSerdeProperties,
          requested
        )
      )
  }

  given CanEqual[ProduceRequest, ProduceRequest] = CanEqual.derived
}

/** Where a published record landed. One per record produced, in order. */
final case class ProducedAt(partition: PartitionId, offset: Offset, timestamp: Instant)

object ProducedAt {
  given CanEqual[ProducedAt, ProducedAt] = CanEqual.derived
}

/** The half-open offset window of one source partition a resend copies. */
final case class SourceRange(topic: TopicName, partition: PartitionId, offsets: OffsetRange)

object SourceRange {
  given CanEqual[SourceRange, SourceRange] = CanEqual.derived
}

/** Where a resend writes. An absent partition lets Kafka's partitioner choose, as a normal produce does. */
final case class Destination(topic: TopicName, partition: Option[PartitionId])

object Destination {
  given CanEqual[Destination, Destination] = CanEqual.derived
}

/** Copy a range of records from one topic into another, as bytes.
  *
  * Nothing on this path is ever deserialized, which is not an optimisation: it is what makes a topic KUI
  * cannot decode still copyable, and that is the case an operator most often needs a resend for. It also
  * makes it structurally impossible to mask a record on the way through, which ADR-023 requires — masking a
  * value on the way *in* would corrupt the destination topic.
  *
  * **A resend is not atomic.** It is a read and a series of produces; cancelled or failed halfway it leaves
  * what it already wrote, and reports how far it got. A caller that assumes otherwise will write a retry that
  * duplicates records.
  */
final case class ResendRequest private (
    cluster: ClusterId,
    source: SourceRange,
    destination: Destination,
    keepHeaders: Boolean
) {
  val kind: MutationKind = MutationKind.Resend
}

object ResendRequest {

  def of(
      cluster: ClusterId,
      source: SourceRange,
      destination: Destination,
      keepHeaders: Boolean
  ): Either[KuiError, ResendRequest] =
    if source.offsets.isEmpty then
      Left(
        DomainError.InvariantViolation(
          "the source range is empty, so there is nothing to copy",
          List(FieldError.of("source.offsets", "a range with at least one offset in it"))
        )
      )
    else if source.topic == destination.topic && destination.partition.contains(source.partition) then
      // Copying a partition onto itself appends every record it reads and then reads what it appended.
      Left(
        DomainError.InvariantViolation(
          "the destination is the source partition, which would copy a partition into itself",
          List(FieldError.of("destination", "a topic or partition other than the source"))
        )
      )
    else Right(ResendRequest(cluster, source, destination, keepHeaders))

  given CanEqual[ResendRequest, ResendRequest] = CanEqual.derived
}

/** What one offset failed to copy, and why. Reported per offset so that an operator can resume from a known
  * point rather than from the beginning.
  */
final case class ResendFailure(sourceOffset: Offset, error: KuiError)

/** How far a resend got. `read` and `produced` are separate numbers because they differ whenever retention
  * removed part of the source under the copy, and reporting only one of them hides that.
  */
final case class ResendResult(read: Long, produced: Long, failures: List[ResendFailure])

/** Empty a topic's partitions up to their current end (MS-008).
  *
  * Irreversible: `deleteRecords` moves a log's low watermark and the records below it are gone. An absent
  * partition set means every partition, which is the common case and is stated rather than implied.
  */
final case class PurgeRequest(
    cluster: ClusterId,
    topic: TopicName,
    partitions: Option[NonEmptySet[PartitionId]]
) {
  val kind: MutationKind = MutationKind.Purge
}

object PurgeRequest {
  given CanEqual[PurgeRequest, PurgeRequest] = CanEqual.derived
}

/** What a purge did, per partition.
  *
  * Every partition that was asked for appears in exactly one of the two maps. That invariant is M1's
  * `BatchResult` rule carried into this response, and it is the reason a partition cannot silently vanish
  * from a destructive operation's report.
  */
final case class PurgeResult(
    newLowWatermarks: Map[PartitionId, Offset],
    skipped: Map[PartitionId, String]
)

object PurgeResult {
  given CanEqual[PurgeResult, PurgeResult] = CanEqual.derived
}
