package kui.cache

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

import scala.concurrent.duration.FiniteDuration

import cats.effect.std.Mutex
import cats.effect.{Async, Clock, Resource, Sync}
import cats.syntax.all.*
import com.github.benmanes.caffeine.cache.{Cache as CaffeineCache, Caffeine, Ticker}

import kui.kernel.ClusterId

/** What a cache is doing, for the metric and for an operator asking whether it is helping. */
final case class CacheStats(hits: Long, misses: Long, evictions: Long, size: Long)

object CacheStats {
  val empty: CacheStats = CacheStats(0L, 0L, 0L, 0L)
  given CanEqual[CacheStats, CacheStats] = CanEqual.derived
}

/** ADR-016's second primitive: many values, a maximum size, an optional time to live.
  *
  * `SnapshotCell` is the other one and answers a different question. A snapshot is *one* value that must
  * always be readable, even when stale, because a screen has to show something. This is *many* values that
  * are merely expensive to compute — a compiled CEL program, a schema fetched by id — where a miss is a short
  * wait rather than a blank page. That difference is why the two are separate types and not one type with a
  * flag: the snapshot never evicts and this one must.
  *
  * It arrives now rather than in M1 because ADR-016 ties each primitive to a named consumer and M1 had none.
  * Its two first callers ship in the same milestone: `libs/filter`'s compiled programs (10 000 entries, one
  * hour) and `libs/serde-confluent`'s schemas by id (size-bounded, no expiry — a schema id is immutable, so a
  * cached schema can never be wrong, only unused).
  *
  * **Bounds are parameters, never literals in the file.** An operator whose registry holds forty thousand
  * schemas needs a different number from one with forty, and a cache whose size is a constant is a cache they
  * cannot fix without a release.
  */
trait BoundedCache[F[_], K, V] {

  /** The value if it is present. Never loads: a caller that only wants to know is not a caller that wants to
    * wait.
    */
  def get(key: K): F[Option[V]]

  /** The value, loading it once if it is absent.
    *
    * "Once" is under concurrency too, and that is the whole reason this method exists rather than
    * `get`-then-`put` at each call site. Twenty records of a page arriving at the same missing schema must
    * produce one registry call, not twenty; the failure this prevents is a cache that turns a burst of reads
    * into a burst of upstream requests exactly when the upstream is already slow.
    *
    * A failing `load` is not cached. The next caller tries again — which is right for a network failure and
    * would be wrong only if failures were permanent, and a permanent failure is a configuration problem that
    * caching cannot help with.
    */
  def getOrLoad(key: K)(load: => F[V]): F[V]

  def put(key: K, value: V): F[Unit]

  def invalidate(key: K): F[Unit]

  /** Everything, dropped. For a profile change, where every cached value describes a system KUI is no longer
    * talking to.
    */
  def invalidateAll: F[Unit]

  def stats: F[CacheStats]
}

object BoundedCache {

  /** @param name
    *   the `cache` metric attribute: one short, stable string per *kind* of cache — `filter.programs`,
    *   `serde.registry.schemas`. Never a per-cluster value; the cluster is its own attribute, and a label
    *   whose cardinality grows with the number of clusters is how a metrics backend runs out of memory.
    * @param cluster
    *   which cluster's data this cache holds, for the metric attribute of the same name.
    * @param maxSize
    *   the entry bound. Caffeine evicts approximately at this size rather than exactly, using a frequency
    *   sketch that keeps the entries actually being used; the suite therefore asserts that the cache stays
    *   near its bound, not that it never holds `maxSize + 1` entries.
    * @param ttl
    *   how long an entry stays valid after it is written. `None` for values that cannot go stale — a schema
    *   fetched by its immutable id.
    */
  def make[F[_]: Async, K <: AnyRef, V <: AnyRef](
      name: String,
      cluster: ClusterId,
      maxSize: Long,
      ttl: Option[FiniteDuration],
      metrics: CacheMetrics[F]
  ): Resource[F, BoundedCache[F, K, V]] =
    Resource.make(
      for {
        // One mutex, not one per key. `getOrLoad` is the only thing it guards, the guarded region is a map
        // lookup plus at most one `load`, and the alternative — a map of per-key locks — is a second cache
        // with the same eviction problem this one has, guarding the first.
        lock <- Mutex[F]
        clock <- Clock[F].monotonic.flatMap(d => Sync[F].delay(new AtomicLong(d.toNanos)))
        underlying <- Sync[F].delay(build[K, V](maxSize, ttl, clock))
      } yield new Impl[F, K, V](name, cluster, underlying, lock, clock, metrics): BoundedCache[F, K, V]
    )(cache => cache.invalidateAll)

  /** Caffeine reads `System.nanoTime` unless it is given a ticker, and `System.nanoTime` is not the clock the
    * effect runs on. That difference is not academic: it is the difference between a time-to-live that can be
    * tested in virtual time in a millisecond and one whose only honest test takes an hour. The ticker below
    * reads an `AtomicLong` that every cache operation refreshes from `Clock[F]` first, so expiry is decided
    * against the effect's own clock. Expiry is only ever observed on an access, so refreshing at each
    * operation is exact rather than approximate.
    */
  private def build[K <: AnyRef, V <: AnyRef](
      maxSize: Long,
      ttl: Option[FiniteDuration],
      clock: AtomicLong
  ): CaffeineCache[K, V] = {
    val ticker: Ticker = () => clock.get()
    val builder = Caffeine.newBuilder().maximumSize(maxSize).ticker(ticker).recordStats()
    ttl
      .fold(builder)(d => builder.expireAfterWrite(d.toMillis, TimeUnit.MILLISECONDS))
      .build[K, V]()
  }

  final private class Impl[F[_]: Async, K <: AnyRef, V <: AnyRef](
      name: String,
      cluster: ClusterId,
      underlying: CaffeineCache[K, V],
      lock: Mutex[F],
      clock: AtomicLong,
      metrics: CacheMetrics[F]
  ) extends BoundedCache[F, K, V] {

    /** Hands the effect's current time to Caffeine's ticker before anything reads or writes. */
    private def tick: F[Unit] =
      Clock[F].monotonic.flatMap(d => Sync[F].delay(clock.set(d.toNanos)))

    private def peek(key: K): F[Option[V]] =
      tick >> Sync[F].delay(Option(underlying.getIfPresent(key)))

    def get(key: K): F[Option[V]] =
      peek(key).flatTap {
        case Some(_) => metrics.hit(name, cluster)
        case None => metrics.miss(name, cluster)
      }

    def getOrLoad(key: K)(load: => F[V]): F[V] =
      peek(key).flatMap {
        case Some(value) => metrics.hit(name, cluster).as(value)
        case None =>
          // Re-checked inside the lock. Between the read above and taking the lock another fiber may
          // have loaded the same key, and loading it twice is precisely what this method promises not
          // to do.
          lock.lock.surround {
            peek(key).flatMap {
              case Some(value) => metrics.hit(name, cluster).as(value)
              case None =>
                metrics.miss(name, cluster) >>
                  load.flatMap(value => put(key, value).as(value))
            }
          }
      }

    def put(key: K, value: V): F[Unit] = tick >> Sync[F].delay(underlying.put(key, value))

    def invalidate(key: K): F[Unit] = Sync[F].delay(underlying.invalidate(key))

    def invalidateAll: F[Unit] = Sync[F].delay(underlying.invalidateAll())

    def stats: F[CacheStats] = tick >> Sync[F].delay {
      // Caffeine's own counters are approximate under concurrency and are enough for this: the numbers
      // exist to tell an operator whether the cache is helping, not to balance a ledger. `cleanUp` is
      // called first so that an eviction that has been decided but not yet applied is counted, which is
      // what makes `evictsAtTheBound` assertable rather than timing-dependent.
      underlying.cleanUp()
      val recorded = underlying.stats()
      CacheStats(
        hits = recorded.hitCount(),
        misses = recorded.missCount(),
        evictions = recorded.evictionCount(),
        size = underlying.estimatedSize()
      )
    }
  }
}
