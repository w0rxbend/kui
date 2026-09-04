package kui.kernel.browse

import kui.kernel.{Offset, PartitionId, ValidationError}

/** Which way a browse reads: from an older record towards a newer one, or the other way round.
  *
  * Kafka itself has no notion of reading backwards — a consumer only ever moves forward through a partition.
  * `Backward` therefore does not mean "poll in reverse"; it means "show me the records immediately *before*
  * this position", which the adapter implements by seeking to a window that ends at the position and reading
  * that window forwards. The distinction matters at exactly one place — the window walker of MSG-005 — and it
  * is written down here so that nobody reading `Direction.Backward` expects a reverse iterator.
  */
enum Direction(val wire: String) {
  case Forward extends Direction("FORWARD")
  case Backward extends Direction("BACKWARD")
}

object Direction {

  val All: List[Direction] = values.toList

  def from(wire: String): Either[ValidationError, Direction] =
    All.find(_.wire == wire) match {
      case Some(direction) => Right(direction)
      case None =>
        Left(ValidationError.Format("direction", s"one of ${All.map(_.wire).mkString(", ")}", wire))
    }

  given CanEqual[Direction, Direction] = CanEqual.derived
}

/** Whether a browse sees records written by a transaction that has not committed.
  *
  * `ReadCommitted` is not simply a filter: under it the broker refuses to return anything past the "last
  * stable offset", so a partition with an open transaction can look empty even though its end offset is far
  * ahead. That is a normal condition, not a fault, and it is why termination in this milestone is decided by
  * the consumer's own position rather than by counting records (`research/kafka/admin-capabilities.md` §4).
  */
enum IsolationLevel(val wire: String, val kafkaConfigValue: String) {
  case ReadUncommitted extends IsolationLevel("READ_UNCOMMITTED", "read_uncommitted")
  case ReadCommitted extends IsolationLevel("READ_COMMITTED", "read_committed")
}

object IsolationLevel {

  val All: List[IsolationLevel] = values.toList

  /** What a caller that said nothing gets. Kafka's own consumer default, kept, so that KUI shows an operator
    * the same records their applications see unless they ask otherwise.
    */
  val Default: IsolationLevel = ReadUncommitted

  def from(wire: String): Either[ValidationError, IsolationLevel] =
    All.find(_.wire == wire) match {
      case Some(level) => Right(level)
      case None =>
        Left(ValidationError.Format("isolation", s"one of ${All.map(_.wire).mkString(", ")}", wire))
    }

  given CanEqual[IsolationLevel, IsolationLevel] = CanEqual.derived
}

/** Where a browse starts.
  *
  * This is a *request*, not a resolved position: `AtTimestamp(t)` names a point in time, and which offset
  * that is — or whether there is one at all — is a question only a broker can answer. `SeekResolution`
  * (MSG-003) turns one of these into a concrete [[kui.kernel.OffsetRange]] per partition.
  *
  * `AtOffset` and `AtOffsets` both exist on purpose. The reference product's v2 API applies one offset to
  * every selected partition, while its own paging cursors carry a per-partition map; a user who can receive
  * the second but only send the first cannot ask for the page they were just looking at. KUI accepts both
  * (DEVPLAN §10 D10).
  */
enum SeekMode {

  /** The earliest offset the log still holds, which on a compacted or retention-trimmed topic is not zero. */
  case Beginning

  /** The end offset: the offset the next record written will get. Nothing is read until something arrives. */
  case Latest

  /** One offset, applied to every partition being browsed. */
  case AtOffset(offset: Offset)

  /** An offset per partition. A partition absent from the map is not browsed. */
  case AtOffsets(perPartition: Map[PartitionId, Offset])

  /** Milliseconds since the epoch, resolved through `offsetsForTimes`. */
  case AtTimestamp(millis: Long)
}

object SeekMode {

  /** The direction implied by a mode when the caller did not state one.
    *
    * `Latest` is the only mode that means "backwards": asking for the end of a topic and then reading forward
    * would show nothing at all until a producer wrote something, which is not what a person who clicked
    * "newest first" wanted. Every other mode names a lower bound and reads forward from it. A caller that
    * passes an explicit [[Direction]] always wins over this.
    */
  def defaultDirection(mode: SeekMode): Direction =
    mode match {
      case Latest => Direction.Backward
      case Beginning | AtOffset(_) | AtOffsets(_) | AtTimestamp(_) => Direction.Forward
    }

  given CanEqual[SeekMode, SeekMode] = CanEqual.derived
}
