package kui.cache

import cats.Applicative
import cats.effect.Async
import cats.syntax.all.*
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.{Counter, Meter}

import kui.kernel.ClusterId
import kui.observability.MetricNames

/** ADR-016's metric requirement, as an interface.
  *
  * It is an interface so that the cell records without knowing anything about OpenTelemetry, and so that a
  * test can assert "a stale read was counted" with a counting fake instead of an SDK and an in-memory
  * exporter.
  */
trait CacheMetrics[F[_]] {

  /** A read that returned a value. */
  def hit(cache: String, cluster: ClusterId): F[Unit]

  /** A read that had nothing to return. */
  def miss(cache: String, cluster: ClusterId): F[Unit]

  /** A read that returned a value known to be out of date.
    *
    * A stale read is also a hit: it did serve data. Whether the data was old is a separate question an
    * operator asks separately, so it gets a separate counter rather than a third value of the hit/miss split.
    */
  def staleRead(cache: String, cluster: ClusterId): F[Unit]

  def refreshFailed(cache: String, cluster: ClusterId): F[Unit]
}

object CacheMetrics {

  /** The `outcome` attribute values that distinguish the two counters sharing `kui.cache.hits`. */
  private val StaleOutcome: String = "stale"
  private val FreshOutcome: String = "fresh"
  private val FailedOutcome: String = "refresh_failed"

  def noop[F[_]: Applicative]: CacheMetrics[F] = new CacheMetrics[F] {
    def hit(cache: String, cluster: ClusterId): F[Unit] = Applicative[F].unit
    def miss(cache: String, cluster: ClusterId): F[Unit] = Applicative[F].unit
    def staleRead(cache: String, cluster: ClusterId): F[Unit] = Applicative[F].unit
    def refreshFailed(cache: String, cluster: ClusterId): F[Unit] = Applicative[F].unit
  }

  def otel4s[F[_]: Async](meter: Meter[F]): F[CacheMetrics[F]] =
    for {
      hits <- meter
        .counter[Long](MetricNames.CacheHits)
        .withDescription("Reads of a cached value that returned something")
        .create
      misses <- meter
        .counter[Long](MetricNames.CacheMisses)
        .withDescription("Reads of a cached value that had nothing to return")
        .create
    } yield new Otel[F](hits, misses)

  final private class Otel[F[_]](hits: Counter[F, Long], misses: Counter[F, Long]) extends CacheMetrics[F] {

    def hit(cache: String, cluster: ClusterId): F[Unit] =
      hits.inc(attributes(cache, cluster, FreshOutcome)*)

    def miss(cache: String, cluster: ClusterId): F[Unit] =
      misses.inc(attributes(cache, cluster, FreshOutcome)*)

    def staleRead(cache: String, cluster: ClusterId): F[Unit] =
      hits.inc(attributes(cache, cluster, StaleOutcome)*)

    def refreshFailed(cache: String, cluster: ClusterId): F[Unit] =
      misses.inc(attributes(cache, cluster, FailedOutcome)*)

    private def attributes(
        cache: String,
        cluster: ClusterId,
        outcome: String
    ): List[Attribute[String]] = List(
      Attribute(MetricNames.Attr.Cache, cache),
      Attribute(MetricNames.Attr.Cluster, cluster.value),
      Attribute(MetricNames.Attr.Outcome, outcome)
    )
  }
}
