package kui.kernel.browse

import scala.concurrent.duration.{Duration, FiniteDuration}

import kui.kernel.ValidationError

/** What a single browse may consume before it stops of its own accord (PLAN §22).
  *
  * A browse reads from a topic whose size KUI does not control, so every one of them has to be able to end
  * for a reason other than "the data ran out". This carries the three reasons: enough records, enough bytes,
  * or enough time.
  *
  * **Every dimension saturates at zero.** `consume` never produces a negative field. That is not tidiness: a
  * budget that can go negative is a budget that becomes unbounded the moment a chunk overshoots it, because
  * the next `isExhausted` check compares a negative number against zero and says "keep going".
  *
  * The deadline is a duration, not an instant, because this type is pure and cross-compiled; the use case
  * turns it into a deadline against its own clock.
  */
final case class PollBudget private (
    maxRecords: Int,
    maxBytes: Long,
    deadline: FiniteDuration,
    throttleBytesPerSecond: Option[Long]
) {

  /** What is left after reading `records` records totalling `bytes`, and after `elapsed` has passed. */
  def consume(records: Int, bytes: Long, elapsed: FiniteDuration = Duration.Zero): PollBudget =
    PollBudget(
      maxRecords = PollBudget.subtractInt(maxRecords, records),
      maxBytes = PollBudget.subtractLong(maxBytes, bytes),
      deadline = PollBudget.subtractDuration(deadline, elapsed),
      throttleBytesPerSecond = throttleBytesPerSecond
    )

  def recordsLeft: Int = maxRecords

  def bytesLeft: Long = maxBytes

  def timeLeft: FiniteDuration = deadline

  /** Spent in *any* dimension. The stream stops on the first one, and the caller reports which. */
  def isExhausted: Boolean = maxRecords <= 0 || maxBytes <= 0L || deadline <= Duration.Zero

  /** Which dimension ran out, for the `done{reason}` event and the log line. `None` while the budget holds.
    */
  def exhaustedDimension: Option[String] =
    if maxRecords <= 0 then Some("records")
    else if maxBytes <= 0L then Some("bytes")
    else if deadline <= Duration.Zero then Some("deadline")
    else None
}

object PollBudget {

  /** `Left` for a budget that is spent before it starts: a negative or zero dimension is a caller mistake —
    * usually a configuration value read as a default when it was absent — and a browse that ends immediately
    * with "budget exhausted" is a bug report nobody can read.
    */
  def of(
      maxRecords: Int,
      maxBytes: Long,
      deadline: FiniteDuration,
      throttleBytesPerSecond: Option[Long] = None
  ): Either[ValidationError, PollBudget] =
    if maxRecords <= 0 then Left(ValidationError.Invariant("maxRecords", "must be greater than zero"))
    else if maxBytes <= 0L then Left(ValidationError.Invariant("maxBytes", "must be greater than zero"))
    else if deadline <= Duration.Zero then
      Left(ValidationError.Invariant("deadline", "must be greater than zero"))
    else if throttleBytesPerSecond.exists(_ <= 0L) then
      Left(ValidationError.Invariant("throttleBytesPerSecond", "must be greater than zero when present"))
    else Right(PollBudget(maxRecords, maxBytes, deadline, throttleBytesPerSecond))

  /** Wraps values that were validated somewhere else — a configuration slice, a literal in a test. */
  def unsafe(
      maxRecords: Int,
      maxBytes: Long,
      deadline: FiniteDuration,
      throttleBytesPerSecond: Option[Long] = None
  ): PollBudget = PollBudget(maxRecords, maxBytes, deadline, throttleBytesPerSecond)

  /** Not the product's defaults — those are configuration (MSG-041). This is what a test, or a caller with no
    * configuration in scope, uses, and it is deliberately small so that a forgotten budget shows up as a
    * short answer rather than as a long one.
    */
  val Conservative: PollBudget =
    PollBudget(
      maxRecords = 100,
      maxBytes = 8L * 1024L * 1024L,
      deadline = FiniteDuration(30, "seconds"),
      None
    )

  private def subtractInt(left: Int, taken: Int): Int =
    if taken <= 0 then left
    else if taken >= left then 0
    else left - taken

  private def subtractLong(left: Long, taken: Long): Long =
    if taken <= 0L then left
    else if taken >= left then 0L
    else left - taken

  private def subtractDuration(left: FiniteDuration, taken: FiniteDuration): FiniteDuration =
    if taken <= Duration.Zero then left
    else if taken >= left then Duration.Zero
    else left - taken

  given CanEqual[PollBudget, PollBudget] = CanEqual.derived
}
