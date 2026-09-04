package kui.consumer.domain

import java.time.Instant

import kui.kernel.group.LagAnomaly
import kui.kernel.{Offset, TopicPartition}

/** The lag rules. Pure, total, and each one a test.
  *
  * Every rule here exists because its wrong answer is a number an operator would act on. The reference
  * product's `ConsumerGroupUtil.java:28-34` sums committed offsets with `orElse(0)`, which is how "this
  * consumer has never run" becomes "this consumer is perfectly caught up" on a capacity screen. Nothing in
  * this object substitutes a value for an absent one.
  */
object LagMath {

  /** The four cases of DEVPLAN §10 D6.
    *
    *   - No committed offset: `None` and `NoCommit`. The group has never read this partition.
    *   - No end offset: `None` and `NoLeader`, the only reason M4 can produce for it.
    *   - Committed past the end: `None` and `CommittedBeyondEnd`, never a negative number. It happens after a
    *     topic is recreated or records are deleted, and "these numbers do not make sense" is the honest
    *     answer.
    *   - Committed before the log start: the lag **is** computed, and flagged `CommittedBeforeStart`. The
    *     consumer really is that far behind; it will simply resume from the earliest retained record rather
    *     than from where it committed.
    */
  def lagOf(committed: Option[Offset], begin: Option[Offset], end: Option[Offset]): PartitionLag =
    (committed, end) match {
      case (None, _) => PartitionLag.noCommit
      case (Some(_), None) => PartitionLag.noLeader
      case (Some(at), Some(logEnd)) if at.value > logEnd.value =>
        PartitionLag(None, Set(LagAnomaly.CommittedBeyondEnd))
      case (Some(at), Some(logEnd)) =>
        val behindStart = begin.exists(_.value > at.value)
        PartitionLag(
          Some(logEnd.value - at.value),
          if behindStart then Set(LagAnomaly.CommittedBeforeStart) else Set.empty
        )
    }

  /** A total, and how much of the group it is a total *of*.
    *
    * `value` is `None` when no partition has a defined lag: an empty total is not zero. `excluded` is the
    * number of partitions that contributed nothing, so a screen can say "of 12 partitions, 3 have no
    * committed offset" instead of showing a number that silently means less than it looks like.
    */
  final case class LagTotal(value: Option[Long], counted: Int, excluded: Int)

  object LagTotal {
    val Empty: LagTotal = LagTotal(None, 0, 0)
    given CanEqual[LagTotal, LagTotal] = CanEqual.derived
  }

  def total(lags: Iterable[PartitionLag]): LagTotal = {
    val defined = lags.flatMap(_.value)

    LagTotal(
      value = Option.when(defined.nonEmpty)(defined.sum),
      counted = defined.size,
      excluded = lags.size - defined.size
    )
  }

  /** One observation of where a group's commits were, and when. */
  final case class PaceSample(
      at: Instant,
      committedTotal: Option[Long],
      partitions: Set[TopicPartition]
  )

  object PaceSample {
    given CanEqual[PaceSample, PaceSample] = CanEqual.derived
  }

  /** Committed-offset movement per second between two observations of the same group.
    *
    * `None` when there is only one observation, when either total is unknown, when no time has passed, or
    * when the partition set changed between them. That last rule is the one worth arguing: arithmetic across
    * a changed partition set subtracts two different quantities, and it renders as a spike exactly when an
    * operator is looking at a rebalance and least needs a fictional number. Rendering a dash for one refresh
    * interval is honest; rendering a spike is not.
    *
    * A negative result — commits moving backwards, which a reset does — is reported as it is. Clamping it to
    * zero would hide the one event this number is most useful for noticing.
    */
  def pace(previous: Option[PaceSample], current: PaceSample): Option[Double] =
    for {
      earlier <- previous
      if earlier.partitions == current.partitions
      before <- earlier.committedTotal
      after <- current.committedTotal
      seconds = (current.at.toEpochMilli - earlier.at.toEpochMilli).toDouble / 1000.0
      if seconds > 0.0
    } yield (after - before).toDouble / seconds
}
