package kui.cache

import java.time.Instant

import scala.concurrent.duration.FiniteDuration

import cats.effect.{Ref, Sync}
import cats.syntax.all.*

import kui.kernel.ClusterId

/** A `SeriesWindow` held in a `Ref`, counted through `CacheMetrics`.
  *
  * `SeriesWindow` is immutable and knows nothing about effects, which is what makes its arithmetic testable
  * without a runtime. This is the part a service holds: one window per (cluster, metric), fed by a scrape and
  * read by an endpoint, with the same instrumentation ADR-016 requires of the other two primitives.
  *
  * The counters mean what they mean for the other two, which is why they are the same counters:
  *
  *   - a read that answered is a `hit`, and a read that had to refuse is a `miss` — an operator watching
  *     `kui.cache.misses` on a metrics endpoint is watching the window fill up after a restart. A window
  *     holding no samples has refused however well-shaped its answer is: buckets that are all gaps are a
  *     refusal spelt as a vector, not a measurement of nothing;
  *   - a read served from a window whose newest sample is more than one step old is also a `staleRead`, the
  *     same "it did serve data, and the data is behind" distinction `SnapshotCell` draws;
  *   - a sample the window refused is a `refreshFailed`. It is the only way a scrape can silently fail to
  *     land, and it happens when the sample is older than the retention horizon — a clock corrected
  *     backwards, or a collector replaying a backlog it should have dropped.
  *
  * Instants are parameters here rather than reads of a `Clock[F]`, and that is deliberate: the instant of a
  * sample is the instant it was *measured*, which is not the instant the cell got round to storing it. A cell
  * that stamped samples with its own clock would quietly turn a slow collector into a smooth chart.
  */
trait SeriesWindowCell[F[_], A] {

  /** Files a reading. Refused, and counted as a failed refresh, if it is already past the horizon. */
  def record(at: Instant, value: A): F[Unit]

  /** The window as of `now`, with anything too old dropped. For a caller doing its own arithmetic. */
  def read(now: Instant): F[SeriesWindow[A]]

  /** Evenly spaced buckets over `period`, or `None` if the window has not been collecting that long. */
  def bucketsOver(period: FiniteDuration, now: Instant): F[Option[Vector[SeriesBucket[A]]]]

  /** The newest reading, if the window still holds one at `now`. */
  def latest(now: Instant): F[Option[SeriesSample[A]]]

  /** Drops every sample and starts the coverage clock again at `startingAt`.
    *
    * For a profile change, exactly as `BoundedCache.invalidateAll` is: the retained samples describe a
    * cluster KUI is no longer talking to, and keeping the old `startedAt` would let the new cluster answer
    * "over the last 24h" from its first minute.
    */
  def clear(startingAt: Instant): F[Unit]
}

object SeriesWindowCell {

  /** @param name
    *   the `cache` metric attribute: one short, stable string per *kind* of series — `metrics.throughput`,
    *   `metrics.latency.p99`. Never a per-cluster value; the cluster is its own attribute, and a label whose
    *   cardinality grows with the number of clusters is how a metrics backend runs out of memory.
    * @param startedAt
    *   when this window began collecting, which is when the process that owns it started or when the cluster
    *   profile it belongs to was configured. Not "now" read from a clock, because a cell built during wiring
    *   and fed from a scrape that starts later would otherwise claim coverage of the gap.
    *
    * There is no `Resource` because there is nothing to release: no fiber, no native cache, no file. The
    * other two primitives are `Resource`s because a supervised refresh loop and a Caffeine map both outlive
    * their creator otherwise, and a window does not.
    */
  def create[F[_]: Sync, A](
      name: String,
      cluster: ClusterId,
      step: FiniteDuration,
      maxAge: FiniteDuration,
      maxSamples: Int,
      startedAt: Instant,
      metrics: CacheMetrics[F]
  ): F[SeriesWindowCell[F, A]] =
    Ref
      .of[F, SeriesWindow[A]](SeriesWindow.empty[A](step, maxAge, maxSamples, startedAt))
      .map(state => new Impl[F, A](name, cluster, state, metrics))

  final private class Impl[F[_]: Sync, A](
      name: String,
      cluster: ClusterId,
      state: Ref[F, SeriesWindow[A]],
      metrics: CacheMetrics[F]
  ) extends SeriesWindowCell[F, A] {

    def record(at: Instant, value: A): F[Unit] =
      state
        .modify { window =>
          val updated = window.record(at, value)
          // `record` keeps its verdict to itself — it returns a window, not a window and a reason — so the
          // question is put a second time. Both askings are of the same immutable `window` inside one
          // atomic `modify`, so they cannot disagree; what would disagree is asking the *updated* window,
          // which by then holds the sample and would report every refusal as a success.
          (updated, window.accepts(at))
        }
        .flatMap(kept => if kept then Sync[F].unit else metrics.refreshFailed(name, cluster))

    def read(now: Instant): F[SeriesWindow[A]] =
      // Eviction is a write, so the read performs it: a window nobody has recorded into for an hour must not
      // hand out hour-old samples just because nothing has called `record` to prune them.
      state.updateAndGet(_.evict(now)).flatTap(window => observe(window, now, answered = !window.isEmpty))

    def bucketsOver(period: FiniteDuration, now: Instant): F[Option[Vector[SeriesBucket[A]]]] =
      state.updateAndGet(_.evict(now)).flatMap { window =>
        val answer = window.bucketsOver(period, now)
        // `bucketsOver` answers whenever the window has been *collecting* for the period, which is a fact
        // about `startedAt` and never about whether a sample survived: a window whose every sample has been
        // evicted still answers a 30-minute read, with thirty gaps. Counted as a hit, that is a collector
        // dead for hours reading as a healthy cache on `kui.cache.hits{outcome=fresh}`. So emptiness decides
        // here exactly as it does in `read` above and in `SnapshotCell.get`.
        observe(window, now, answered = answer.isDefined && !window.isEmpty).as(answer)
      }

    def latest(now: Instant): F[Option[SeriesSample[A]]] =
      state.updateAndGet(_.evict(now)).flatMap { window =>
        val answer = window.latest
        observe(window, now, answered = answer.isDefined).as(answer)
      }

    def clear(startingAt: Instant): F[Unit] =
      state.update(window => SeriesWindow.empty[A](window.step, window.maxAge, window.maxSamples, startingAt))

    /** A refusal is a miss; an answer is a hit, and also a stale read when the series has stopped arriving.
      */
    private def observe(window: SeriesWindow[A], now: Instant, answered: Boolean): F[Unit] =
      if !answered then metrics.miss(name, cluster)
      else if window.isStaleAt(now) then metrics.hit(name, cluster) >> metrics.staleRead(name, cluster)
      else metrics.hit(name, cluster)
  }
}
