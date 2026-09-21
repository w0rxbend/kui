package kui.cache

import java.time.{Duration as JavaDuration, Instant}

import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}

/** One reading and the bucket it was filed under. */
final case class SeriesSample[A](at: Instant, value: A)

object SeriesSample {
  given [A] => CanEqual[SeriesSample[A], SeriesSample[A]] = CanEqual.derived
}

/** One step of an answer, which may be a gap.
  *
  * `value` is `None` for a bucket nobody sampled, and that is the whole reason this type exists rather than a
  * bare `Vector[A]`. A chart draws a gap; a `0` would draw a floor, and a floor is a measurement.
  */
final case class SeriesBucket[A](start: Instant, value: Option[A])

object SeriesBucket {
  given [A] => CanEqual[SeriesBucket[A], SeriesBucket[A]] = CanEqual.derived
}

/** ADR-016's third primitive: many samples of *one* value over time, bounded by age and by count.
  *
  * The other two answer questions this one cannot. `SnapshotCell` holds exactly one value and overwrites it,
  * so a sparkline drawn from it is a single point. `BoundedCache` holds many values under many keys and
  * evicts whichever it likes, so a series read out of it has holes it cannot explain. Every history this
  * product draws — the throughput chart, the p99 chart, the four stat-card sparklines, the eight-block
  * under-replication strip and the controller-uptime window — needs samples of one metric in order, and until
  * this type existed nothing in the repository retained a second sample of anything.
  *
  * Three refusals are the contract, and each of them is a figure this product would otherwise print wrongly:
  *
  *   - **A bucket nobody sampled is absent, not zero.** `bucketsOver` returns `Option` per bucket, the
  *     frontend's `LineChart` already treats `null` as a gap, and interpolating across a gap would invent a
  *     reading for a minute in which the exporter was down.
  *   - **A window shorter than the period asked for answers `None`.** "99.72 % over the last 24h" computed
  *     over the four minutes since the process booted is a worse answer than no answer, because the reader
  *     cannot tell the difference. `coverage` is what a caller asks when it wants to know why.
  *   - **Age is measured against an `Instant` the caller supplies**, never `System.currentTimeMillis`. The
  *     sample's instant is the instant it was *measured*, which is not the instant it was stored, and a
  *     window that read its own clock could not be tested without sleeping for a day.
  *
  * It is immutable and has no effect type; `SeriesWindowCell` is the `Ref`-backed, metric-carrying holder
  * that services use, exactly as `SnapshotCell` wraps a `Snapshot`.
  *
  * @param step
  *   the bucket width. Samples are filed under the bucket their instant falls in, so a scrape that drifts by
  *   a second does not produce two points a second apart, and the buckets a caller reads back are evenly
  *   spaced whatever the scrape did.
  * @param maxAge
  *   how far back the window retains. This is the retention policy ADR-016 requires every cache to declare.
  * @param maxSamples
  *   the hard bound on retained samples. `maxAge / step` is the expected count; this is the ceiling that
  *   holds even when a caller records ten samples a second into a one-minute bucket, or when `step` and
  *   `maxAge` are later reconfigured to disagree.
  */
final case class SeriesWindow[A] private (
    step: FiniteDuration,
    maxAge: FiniteDuration,
    maxSamples: Int,
    startedAt: Instant,
    samples: Vector[SeriesSample[A]]
) {

  private val stepMillis: Long = step.toMillis

  /** The longest period the ring could ever answer, whatever the clock says.
    *
    * `maxAge` alone is not it: a window of sixty samples at one minute cannot answer a 24h question however
    * long it has been running. Computed in `BigInt` because `step * maxSamples` overflows a `Long` for a
    * configuration nobody would write but a fuzzer will.
    */
  private val capacityMillis: Long = {
    val product = BigInt(stepMillis) * BigInt(maxSamples)
    val ceiling = BigInt(maxAge.toMillis)
    (if product < ceiling then product else ceiling).toLong
  }

  def isEmpty: Boolean = samples.isEmpty

  def size: Int = samples.size

  def oldest: Option[SeriesSample[A]] = samples.headOption

  def latest: Option[SeriesSample[A]] = samples.lastOption

  /** The start of the bucket an instant falls in. Floor division, so it is correct before the epoch too. */
  def bucketStart(at: Instant): Instant =
    Instant.ofEpochMilli(Math.floorDiv(at.toEpochMilli, stepMillis) * stepMillis)

  /** What was measured in the bucket this instant falls in, if anything.
    *
    * `None` means nobody sampled it. `Some(zero)` means somebody measured zero. Keeping those two apart is
    * the point of the whole type.
    */
  def valueAt(at: Instant): Option[A] = {
    val start = bucketStart(at)
    samples.find(_.at == start).map(_.value)
  }

  /** Whether a sample at this instant would be kept rather than dropped as already too old.
    *
    * Only an out-of-order sample can fail this — one that arrives after a newer one and is more than `maxAge`
    * behind it. That happens when an operator corrects a clock backwards, and silently keeping it would push
    * a point off the left edge of every chart drawn from this window.
    */
  def accepts(at: Instant): Boolean = {
    val start = bucketStart(at)
    !start.isBefore(horizonFor(start))
  }

  /** Files a reading under its bucket and evicts whatever that made too old.
    *
    * A second reading in the same bucket replaces the first: for a gauge the newest reading of the bucket is
    * the reading, and for a rate the caller has already differenced two scrapes before it gets here. A window
    * that averaged instead would be deciding, on a caller's behalf, something only the caller knows.
    */
  def record(at: Instant, value: A): SeriesWindow[A] = {
    val start = bucketStart(at)

    if !accepts(at) then this
    else {
      // Retention is measured from the newest instant the window knows about, not from the sample being
      // recorded: a late sample must not be able to resurrect samples an earlier one already evicted.
      val newest = latest.fold(start)(sample => if sample.at.isAfter(start) then sample.at else start)
      copy(samples = evicted(inserted(samples, SeriesSample(start, value)), newest))
    }
  }

  /** Drops everything older than `maxAge` relative to `now`.
    *
    * `record` already evicts, so this is for a window that has stopped being fed: an exporter that went away
    * an hour ago must not keep an hour-old sample readable as though it were current.
    */
  def evict(now: Instant): SeriesWindow[A] = copy(samples = evicted(samples, now))

  /** The longest period this window can answer honestly at `now`.
    *
    * Bounded by three things at once: how long it has been collecting, its retention, and how many samples
    * its ring can hold. A caller that got `None` from `bucketsOver` asks this to say "collecting, 41m of 24h"
    * rather than showing an empty axis.
    */
  def coverage(now: Instant): FiniteDuration = {
    val elapsed = Math.max(0L, JavaDuration.between(startedAt, now).toMillis)
    FiniteDuration(Math.min(elapsed, capacityMillis), MILLISECONDS)
  }

  /** Whether `bucketsOver(period, now)` will answer. */
  def spans(period: FiniteDuration, now: Instant): Boolean =
    period > FiniteDuration(0, MILLISECONDS) && period <= coverage(now)

  /** The newest sample is more than one bucket behind `now`, so the tail of any answer is gaps.
    *
    * The same distinction `Snapshot.isStale` draws: there is something to show and it is known to be behind.
    * A caller that renders a figure from `latest` stamps it with the sample's own instant when this is true.
    */
  def isStaleAt(now: Instant): Boolean =
    latest.exists(sample => JavaDuration.between(sample.at, bucketStart(now)).toMillis > stepMillis)

  /** Evenly spaced buckets covering `period` and ending with the bucket `now` falls in, oldest first.
    *
    * `None` when the window has not been collecting for `period` — see the type's second refusal. Otherwise
    * the vector always has `ceil(period / step)` entries whatever was sampled, so a caller can index it as an
    * axis, and an entry is `None` where nothing was measured.
    */
  def bucketsOver(period: FiniteDuration, now: Instant): Option[Vector[SeriesBucket[A]]] =
    Option.when(spans(period, now)) {
      val last = bucketStart(now)
      // Bounded by `maxSamples`, because `period` is bounded by `capacityMillis` above.
      val count = Math.ceil(period.toMillis.toDouble / stepMillis.toDouble).toInt
      val byStart = samples.iterator.map(sample => sample.at -> sample.value).toMap

      Vector.tabulate(count) { index =>
        val start = last.minusMillis((count - 1 - index).toLong * stepMillis)
        SeriesBucket(start, byStart.get(start))
      }
    }

  /** The readings over `period`, gaps dropped, oldest first.
    *
    * For a caller folding rather than drawing — an average, a maximum, a percentage. The gaps are dropped
    * only *after* the coverage refusal above, so a fold still cannot be computed over four minutes and
    * printed as a day; it can only be computed over the minutes of that day that were actually sampled.
    */
  def valuesOver(period: FiniteDuration, now: Instant): Option[Vector[A]] =
    bucketsOver(period, now).map(_.flatMap(_.value))

  private def horizonFor(newest: Instant): Instant = {
    val known = latest.fold(newest)(sample => if sample.at.isAfter(newest) then sample.at else newest)
    known.minusMillis(maxAge.toMillis)
  }

  /** Appends, which is the only path a scrape ever takes, and inserts in order when a clock went backwards.
    */
  private def inserted(into: Vector[SeriesSample[A]], sample: SeriesSample[A]): Vector[SeriesSample[A]] =
    into.lastOption match {
      case None => Vector(sample)
      case Some(last) if last.at.isBefore(sample.at) => into :+ sample
      case Some(last) if last.at == sample.at => into.init :+ sample
      case Some(_) =>
        val index = into.indexWhere(!_.at.isBefore(sample.at))
        if into(index).at == sample.at then into.updated(index, sample)
        else into.patch(index, Vector(sample), 0)
    }

  /** Age first, then the count bound. Both drop from the front: the oldest sample is the least interesting
    * one on every chart this feeds.
    */
  private def evicted(from: Vector[SeriesSample[A]], now: Instant): Vector[SeriesSample[A]] = {
    val horizon = now.minusMillis(maxAge.toMillis)
    val fresh = from.dropWhile(_.at.isBefore(horizon))

    if fresh.size <= maxSamples then fresh else fresh.takeRight(maxSamples)
  }
}

object SeriesWindow {

  /** An empty window that starts collecting at `startedAt`.
    *
    * `startedAt` is what makes the coverage refusal possible: without it a window one sample old and a window
    * one day old look the same, and both would happily answer "over the last 24h".
    *
    * The bounds are parameters and never literals, for the same reason `BoundedCache`'s are: an operator
    * whose exporter scrapes every five seconds needs different numbers from one scraping every minute, and a
    * retention they cannot change without a release is a retention they cannot fix.
    */
  def empty[A](
      step: FiniteDuration,
      maxAge: FiniteDuration,
      maxSamples: Int,
      startedAt: Instant
  ): SeriesWindow[A] = {
    require(step.toMillis > 0L, "step must be a positive number of milliseconds")
    require(maxAge >= step, s"maxAge ($maxAge) must be at least one step ($step)")
    require(maxSamples > 0, "maxSamples must be positive")

    SeriesWindow(step, maxAge, maxSamples, startedAt, Vector.empty)
  }

  given [A] => CanEqual[SeriesWindow[A], SeriesWindow[A]] = CanEqual.derived
}
