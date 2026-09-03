package kui.cache

import cats.effect.{Ref, Sync}
import cats.syntax.all.*

import kui.kernel.ClusterId

/** A `CacheMetrics` that records what it was asked to count.
  *
  * It is in this module's test sources rather than in `libs/testkit` because `CacheMetrics` is a
  * `libs/cache` type, and a testkit that depended on `libs/cache` would put a cache on the test
  * classpath of every module in KUI.
  */
final class FakeCacheMetrics[F[_]: Sync] private (
    recorded: Ref[F, List[FakeCacheMetrics.Entry]]
) extends CacheMetrics[F] {

  def hit(cache: String, cluster: ClusterId): F[Unit] = record(cache, cluster, "hit")
  def miss(cache: String, cluster: ClusterId): F[Unit] = record(cache, cluster, "miss")
  def staleRead(cache: String, cluster: ClusterId): F[Unit] = record(cache, cluster, "stale")
  def refreshFailed(cache: String, cluster: ClusterId): F[Unit] =
    record(cache, cluster, "refreshFailed")

  def entries: F[List[FakeCacheMetrics.Entry]] = recorded.get

  def countOf(kind: String): F[Int] = recorded.get.map(_.count(_.kind == kind))

  private def record(cache: String, cluster: ClusterId, kind: String): F[Unit] =
    recorded.update(_ :+ FakeCacheMetrics.Entry(cache, cluster, kind))
}

object FakeCacheMetrics {

  final case class Entry(cache: String, cluster: ClusterId, kind: String)

  def create[F[_]: Sync]: F[FakeCacheMetrics[F]] =
    Ref.of[F, List[Entry]](Nil).map(new FakeCacheMetrics[F](_))
}
