package kui.ui.clusters.brokers

import kui.ui.kernel.component.ThresholdLevel

/** How unevenly something is spread across a cluster's brokers.
  *
  * ## What it is
  *
  * One broker's count, as a percentage above the average across brokers:
  *
  * {{{
  * skew(broker) = 100 * (count(broker) - mean) / mean
  * }}}
  *
  * It is the reason the brokers table is worth building rather than reading out of a shell. A cluster where
  * one machine holds forty percent more partitions than its share is a cluster where one disk fills up first,
  * and no single `kafka-topics.sh` invocation shows that in a glance.
  *
  * ## Four rules, each of which is a test
  *
  *   - **Only reported above the mean.** A broker carrying less than its share is not a problem, so it shows
  *     nothing rather than a negative number that a reader has to work out is good news.
  *   - **A mean of zero reports nothing.** A cluster with no partitions is an ordinary state on a fresh
  *     install, and it must not produce a division by zero, an `Infinity` or a `NaN` on screen.
  *   - **A single broker is `0 %`, not nothing.** With one broker the mean is that broker, so the answer is
  *     genuinely zero rather than unknown.
  *   - **A broker whose count is unknown is left out of the mean** and reports nothing itself. Counting it as
  *     zero would drag the mean down and inflate every other broker's skew — a wrong number that looks like a
  *     right one, which is worse than a dash.
  */
object Skew {

  /** Skew warns from here. */
  val WarningPercent: Double = 10.0

  /** And is worth interrupting someone for from here. */
  val CriticalPercent: Double = 20.0

  /** The percentages, one per broker, in the input's order. */
  def percentages(counts: List[Option[Int]]): List[Option[Double]] = {
    val known = counts.flatten
    if known.isEmpty then counts.map(_ => None)
    else {
      val mean = known.sum.toDouble / known.length
      if mean <= 0.0 then counts.map(_ => None)
      else
        counts.map(_.flatMap { count =>
          val skew = 100.0 * (count.toDouble - mean) / mean
          // At or below the mean is not reported; exactly the mean is `0.0`, which is a real answer and is
          // why the comparison is `>=` rather than `>`.
          Option.when(skew >= 0.0)(skew)
        })
    }
  }

  /** The threshold band a skew falls in. */
  def level(percent: Option[Double]): ThresholdLevel =
    percent match {
      case Some(value) if value >= CriticalPercent => ThresholdLevel.Critical
      case Some(value) if value >= WarningPercent => ThresholdLevel.Warning
      case _ => ThresholdLevel.Normal
    }

  /** `12.4 %`, or the missing marker.
    *
    * One decimal: enough to tell 12.4 from 12.9, few enough digits to scan down a column, and not so many
    * that the number looks more precise than the sampling behind it.
    */
  def format(percent: Option[Double]): String =
    percent.fold(kui.ui.kernel.component.DataTable.missing)(value => f"$value%.1f %%")
}
