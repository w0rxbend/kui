package kui.kernel

import scala.util.matching.Regex

/** Where a topic's data physically is: one partition of one topic.
  *
  * Kafka's own API pairs these two constantly (offsets, assignments, lag, reassignment), and passing them as
  * two loose arguments is how a partition of the wrong topic ends up in a result set.
  */
final case class TopicPartition(topic: TopicName, partition: PartitionId)

object TopicPartition {
  given CanEqual[TopicPartition, TopicPartition] = CanEqual.derived

  /** Topic first, then partition number: the order a human reads a partition list in. */
  given Ordering[TopicPartition] =
    Ordering.by(tp => (tp.topic.value, tp.partition.value))
}

/** One copy of one partition, on one broker. Replica placement, reassignment plans and log-directory queries
  * are all keyed by this triple.
  */
final case class TopicPartitionReplica(tp: TopicPartition, broker: BrokerId)

object TopicPartitionReplica {
  given CanEqual[TopicPartitionReplica, TopicPartitionReplica] = CanEqual.derived

  given Ordering[TopicPartitionReplica] =
    Ordering.by(r => (r.tp.topic.value, r.tp.partition.value, r.broker.value))
}

/** A half-open interval of offsets: `from` is included, `until` is not.
  *
  * Half-open is the same convention Kafka uses for its own end offsets — the end offset of a partition is the
  * offset the *next* record will get, not the offset of the last one — so keeping it here means no `+ 1` or
  * `- 1` has to be remembered anywhere else. `size` is then simply `until - from`, and an empty range is one
  * where the two are equal.
  *
  * The constructor is private: the only way to build a range is [[OffsetRange.of]], which refuses an inverted
  * one. That is what makes `size` safe to call without checking for a negative result.
  */
final case class OffsetRange private (from: Offset, until: Offset) {
  def isEmpty: Boolean = from.value == until.value
  def size: Long = until.value - from.value

  /** Whether one offset falls in the range. */
  def contains(offset: Offset): Boolean =
    offset.value >= from.value && offset.value < until.value

  /** The last `n` offsets of this range, clamped at `from`.
    *
    * This is the backward window walker's one primitive (M3, MSG-005). Browsing backwards means reading the
    * records immediately before a position, and Kafka can only read forwards, so the walker repeatedly takes
    * the tail of what is left and reads that window forwards. Asking for more than the range holds gives the
    * whole range rather than a `from` below the log start, which is what stops a walk near the beginning of a
    * partition from seeking to a negative offset.
    */
  def tail(n: Long): OffsetRange =
    if n <= 0L then OffsetRange(until, until)
    else if n >= size then this
    else OffsetRange(Offset.unsafe(until.value - n), until)

  /** Everything before [[tail]] `n`.
    *
    * `tail(n)` and `dropTail(n)` tile this range exactly: they are adjacent, their sizes sum to `size`, and
    * neither escapes the bounds. The walker's termination argument rests on that, which is why it is a
    * property test and not a comment.
    */
  def dropTail(n: Long): OffsetRange =
    if n <= 0L then this
    else if n >= size then OffsetRange(from, from)
    else OffsetRange(from, Offset.unsafe(until.value - n))

  /** This range narrowed to `bounds`, and empty when the two do not overlap.
    *
    * Every explicit offset a user sends goes through this, so a seek to offset 5 on a partition whose log
    * starts at 900 reads from 900 rather than failing — the reference behaviour, and the one an operator
    * expects after retention has moved underneath their bookmark.
    */
  def clampTo(bounds: OffsetRange): OffsetRange = {
    val begin = math.max(from.value, bounds.from.value)
    val end = math.min(until.value, bounds.until.value)
    if begin >= end then OffsetRange(Offset.unsafe(begin), Offset.unsafe(begin))
    else OffsetRange(Offset.unsafe(begin), Offset.unsafe(end))
  }
}

object OffsetRange {

  /** `Left` when `begin` is after `until`. */
  def from(begin: Offset, until: Offset): Either[ValidationError, OffsetRange] =
    if begin.value <= until.value then Right(OffsetRange(begin, until))
    else
      Left(
        ValidationError.Invariant(
          "offsetRange",
          s"the start offset ${begin.value} must not be after the end offset ${until.value}"
        )
      )

  /** The empty range at a point, which every fold over a partition starts from. */
  def emptyAt(offset: Offset): OffsetRange = OffsetRange(offset, offset)

  given CanEqual[OffsetRange, OffsetRange] = CanEqual.derived
}

/** A host name or an IP address, as written in a bootstrap-servers list or a Connect URL.
  *
  * KUI does not try to decide whether the host resolves — that is the network's answer, not a type's. It only
  * refuses the shapes that are certainly wrong: empty, over-long, or containing whitespace or a path
  * separator, which is almost always a mis-split configuration line.
  */
opaque type Host = String

object Host {
  private val Field: String = "host"
  private val Pattern: Regex = "^[A-Za-z0-9._:\\[\\]-]{1,253}$".r
  private val Expected: String =
    "1 to 253 characters from letters, digits, '.', '-', '_' and, for IPv6, ':' and brackets"

  def from(raw: String): Either[ValidationError, Host] = Checks.matching(Field, Pattern, Expected)(raw)

  /** Wraps a value that has already been validated somewhere else. Never call it on user input. */
  def unsafe(raw: String): Host = raw

  extension (h: Host) def value: String = h

  given Ordering[Host] = Ordering.String
  given CanEqual[Host, Host] = CanEqual.derived
}

/** A TCP port: 1 to 65535. Zero is excluded on purpose — it means "any free port" to an operating system,
  * which is never what a KUI configuration entry intends.
  */
opaque type Port = Int

object Port {
  def from(raw: Int): Either[ValidationError, Port] = Checks.within("port", 1, 65535)(raw)

  /** Wraps a value that has already been validated somewhere else. Never call it on user input. */
  def unsafe(raw: Int): Port = raw

  extension (p: Port) def value: Int = p

  given Ordering[Port] = Ordering.Int
  given CanEqual[Port, Port] = CanEqual.derived
}

/** A whole number greater than zero: a page number, a partition count, a replication factor.
  *
  * It exists so that "how many" arguments cannot be zero or negative without the caller being asked about it
  * at the point the value is built, rather than at the point it is used.
  */
opaque type PositiveInt = Int

object PositiveInt {
  def from(raw: Int): Either[ValidationError, PositiveInt] = Checks.positive("positiveInt")(raw)

  /** Wraps a value that has already been validated somewhere else. Never call it on user input. */
  def unsafe(raw: Int): PositiveInt = raw

  val One: PositiveInt = 1

  extension (n: PositiveInt) def value: Int = n

  given Ordering[PositiveInt] = Ordering.Int
  given CanEqual[PositiveInt, PositiveInt] = CanEqual.derived
}

/** A number of bytes: a record size, a retention limit, a streaming budget.
  *
  * Zero is allowed — an empty record value really is zero bytes — but a negative size is not.
  */
opaque type ByteSize = Long

object ByteSize {
  def from(raw: Long): Either[ValidationError, ByteSize] = Checks.nonNegativeLong("byteSize")(raw)

  /** Wraps a value that has already been validated somewhere else. Never call it on user input. */
  def unsafe(raw: Long): ByteSize = raw

  val Zero: ByteSize = 0L

  extension (size: ByteSize) def value: Long = size

  given Ordering[ByteSize] = Ordering.Long
  given CanEqual[ByteSize, ByteSize] = CanEqual.derived
}
