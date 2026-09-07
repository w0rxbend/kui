package kui.metrics.domain

import java.time.Instant

/** The time axis every series in this service is drawn on, in one place.
  *
  * Throughput and latency are folded into buckets by two different rules — a mean of rates, and the worst of
  * a set of percentiles — but they are folded onto the *same* axis, and the axis is the part that has a wrong
  * answer which reaches a screen. A bucket boundary anchored to "now" rather than to the epoch makes a live
  * chart shiver between two polls; a series that returned only the buckets it had would draw a quiet hour on
  * a narrower axis than a busy one.
  *
  * Stated once here rather than copied per series, so that a second metric added later cannot disagree with
  * the first about where a bucket starts or how many there are.
  */
private[domain] object Bucketing {

  /** Folds samples onto `range`'s axis, ending at `endingAt`.
    *
    * @param instantOf
    *   where a sample sits on the axis. Supplied rather than required by a common supertype, because the two
    *   sample types share nothing but the fact that they were measured at a moment
    * @param bucket
    *   what one step is worth, given the samples that fell in it. It is called for **every** step, including
    *   the ones nothing fell in, which is what makes an unsampled bucket a value the caller decides rather
    *   than a hole this function leaves.
    * @return
    *   the axis's own `from` and `to` beside the buckets. Both are carried out rather than left to be
    *   derived, because a series whose every bucket is absent still has to draw a full-width axis.
    */
  def over[S, B](
      range: ThroughputRange,
      endingAt: Instant,
      samples: List[S],
      instantOf: S => Instant
  )(bucket: (Instant, List[S]) => B): (Instant, Instant, List[B]) = {
    val stepSeconds = range.step.toSeconds
    val to = floorTo(endingAt, stepSeconds).plusSeconds(stepSeconds)
    val from = to.minusSeconds(range.window.toSeconds)

    val byBucket: Map[Instant, List[S]] =
      samples
        .filter { sample =>
          val at = instantOf(sample)
          !at.isBefore(from) && at.isBefore(to)
        }
        .groupBy(sample => floorTo(instantOf(sample), stepSeconds))

    (from, to, boundaries(from, to, stepSeconds).map(start => bucket(start, byBucket.getOrElse(start, Nil))))
  }

  /** `None` for a figure nothing in the bucket measured — which is the one arithmetic mistake this file
    * exists to prevent, since `0.0 / 0` is `NaN` and a `NaN` serialises to `null` by a route nobody chose.
    */
  def mean(values: List[Double]): Option[Double] =
    Option.when(values.nonEmpty)(values.sum / values.size)

  /** The largest of the readings, or `None` when there were none.
    *
    * The fold latency uses, and it is deliberately not [[mean]]: the mean of four p99s is not a p99 of
    * anything. Taking the worst one keeps the number a reader can act on — "in this five minutes, one in a
    * hundred produce requests took at least this long" is true of the maximum and true of nothing the mean
    * describes.
    */
  def worst(values: List[Double]): Option[Double] =
    Option.when(values.nonEmpty)(values.max)

  /** The start of the step `at` falls in, measured from the epoch rather than from the request. */
  def floorTo(at: Instant, stepSeconds: Long): Instant =
    Instant.ofEpochSecond(Math.floorDiv(at.getEpochSecond, stepSeconds) * stepSeconds)

  private def boundaries(from: Instant, to: Instant, stepSeconds: Long): List[Instant] =
    Iterator
      .iterate(from)(_.plusSeconds(stepSeconds))
      .takeWhile(_.isBefore(to))
      .toList
}
