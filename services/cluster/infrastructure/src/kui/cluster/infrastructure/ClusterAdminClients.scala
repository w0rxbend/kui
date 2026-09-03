package kui.cluster.infrastructure

import cats.effect.kernel.{Async, Resource}
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger

import kui.cluster.domain.{ClusterProfile, ProfileVersion}
import kui.kafka.AdminClientPool
import kui.kernel.ClusterId
import kui.kernel.cluster.ClusterConnection

/** Keeps each cluster's admin client in step with the profile it was built from.
  *
  * The *client* lifecycle — one client per cluster, created on first use under a per-cluster gate, shared by
  * every caller, closed and rebuilt when the connection breaks, and closed for all when the process shuts
  * down — already lives in `libs/kafka`'s `AdminClientPool` (KAFKA-004). Building a second registry here
  * would be two pools racing to open clients against the same brokers, which is precisely the failure the
  * pool's per-cluster gate exists to prevent.
  *
  * What the pool cannot know is the one thing this module does know: that a `ClusterProfile` has a
  * `ProfileVersion`, and that a profile edited in the metadata store is a *different connection* wearing the
  * same cluster id. The pool keys by `ClusterId`, so without this component a cluster whose bootstrap list or
  * credentials changed would keep being served by the client built from the old ones — talking to the old
  * brokers with the old password, silently, until the process restarted.
  *
  * So this is a registry of versions, not of clients: it remembers which `ProfileVersion` each cluster's
  * client was built from and evicts the client when it sees a newer one.
  */
trait ClusterAdminClients[F[_]] {

  /** The connection to hand `libs/kafka` for this profile.
    *
    * The side effect is the point: if the cached client for this cluster was built from an older
    * `ProfileVersion`, it is evicted first, so the next call through the pool builds a new one from these
    * settings.
    */
  def connectionFor(profile: ClusterProfile): F[ClusterConnection]

  /** Closes and forgets this cluster's client. The next call builds a new one.
    *
    * Called by the adapter after a reconnect-class failure (see `ReconnectPolicy`), and idempotent so that
    * ten calls failing at once on one dead client cost one reconnect rather than ten.
    */
  def invalidate(id: ClusterId): F[Unit]

  /** How many clusters this registry currently vouches for.
    *
    * An upper bound on open clients rather than a count of them: the pool creates a client lazily on the
    * first call, so a cluster can be registered here with nothing open yet. It is exposed for tests and for
    * the readiness endpoint, neither of which needs more precision than that.
    */
  def openClients: F[Int]
}

object ClusterAdminClients {

  /** Registered clusters are evicted from the pool when the resource closes.
    *
    * The pool closes its own clients when *it* closes, so this matters only when the registry is scoped more
    * narrowly than the pool — which is exactly the shape a test has, and the shape a future per-tenant scope
    * would have. Releasing a narrower scope must not leave a client behind in a wider one.
    */
  def resource[F[_]: Async](
      pool: AdminClientPool[F],
      logger: StructuredLogger[F]
  ): Resource[F, ClusterAdminClients[F]] =
    Resource.make(
      cats.effect.Ref
        .of[F, Map[ClusterId, ProfileVersion]](Map.empty)
        .map(new Impl[F](pool, logger, _))
    )(_.releaseAll)

  final private class Impl[F[_]: Async](
      pool: AdminClientPool[F],
      logger: StructuredLogger[F],
      known: cats.effect.Ref[F, Map[ClusterId, ProfileVersion]]
  ) extends ClusterAdminClients[F] {

    def connectionFor(profile: ClusterProfile): F[ClusterConnection] =
      // Uncancelable from the moment the map says "this cluster is now at the new version" to the moment
      // the old client is actually gone. A cancellation in between would leave the registry believing the
      // client matches the profile while the pool still holds the one built from the old credentials — a
      // stale connection that nothing would ever evict again.
      Async[F]
        .uncancelable { _ =>
          known
            .modify { current =>
              current.get(profile.id) match {
                case Some(seen) if seen.value >= profile.version.value => (current, false)
                case Some(_) => (current + (profile.id -> profile.version), true)
                case None => (current + (profile.id -> profile.version), false)
              }
            }
            .flatMap { stale =>
              if stale then
                logger.info(
                  s"cluster ${profile.id.value} moved to profile version ${profile.version.value}; " +
                    "its admin client will be rebuilt"
                ) >> pool.evict(profile.id)
              else Async[F].unit
            }
        }
        .as(ClusterProfileConnection.of(profile))

    def invalidate(id: ClusterId): F[Unit] =
      Async[F].uncancelable(_ => pool.invalidate(id))

    def openClients: F[Int] = known.get.map(_.size)

    /** Evicts every cluster this registry registered, whether or not the caller was cancelled. */
    def releaseAll: F[Unit] =
      Async[F].uncancelable { _ =>
        known.getAndSet(Map.empty).flatMap(_.keys.toList.traverse_(pool.evict))
      }
  }
}
