package kui.cluster.application

import java.time.Instant

import cats.effect.kernel.{Concurrent, Ref, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.Topic
import org.typelevel.log4cats.StructuredLogger

import kui.cluster.domain.*
import kui.kernel.ClusterId
import kui.kernel.error.{ApplicationError, ErrorCode, KuiError}

/** How many times the resolved cluster set has changed since this process started.
  *
  * Starts at 1 rather than 0, so that "the registry has never been loaded" and "the registry loaded and found
  * nothing" are different values. An ETag of `"0"` on a response is indistinguishable from a bug.
  */
opaque type RegistryVersion = Long

object RegistryVersion {

  val Initial: RegistryVersion = 1L

  extension (v: RegistryVersion) {
    def value: Long = v
    def next: RegistryVersion = v + 1L

    /** The ETag body. Quoting it is the HTTP layer's job, not this type's. */
    def tag: String = v.toString
  }

  given Ordering[RegistryVersion] = Ordering.Long
  given CanEqual[RegistryVersion, RegistryVersion] = CanEqual.derived
}

/** One resolved snapshot of the registry: what is configured, at which version, and how healthy the store
  * that contributed to it is.
  */
final case class RegistrySnapshot(
    profiles: Map[ClusterId, ClusterProfile],
    version: RegistryVersion,
    storeHealth: StoreHealth,
    loadedAt: Instant
) {

  /** Sorted by display name, then id, which is the order every list screen shows them in. */
  def refs: List[ClusterRef] = profiles.values.map(_.ref).toList.sorted

  def get(id: ClusterId): Option[ClusterProfile] = profiles.get(id)

  def size: Int = profiles.size
}

object RegistrySnapshot {
  given CanEqual[RegistrySnapshot, RegistrySnapshot] = CanEqual.derived
}

/** Which clusters this KUI knows about, and how to reach each one.
  *
  * The single resolution point for a `ClusterId` in this service. Nothing else may read the static
  * configuration list or the store directly: two resolvers would be two precedence rules, and the second one
  * is always the one nobody documented.
  */
trait ClusterRegistry[F[_]] {

  /** The current resolved snapshot. Never fails and never blocks on the store: it is a `Ref` read. A store
    * that is unreachable is reflected in `storeHealth`, not in an error.
    */
  def snapshot: F[RegistrySnapshot]

  def list: F[List[ClusterProfile]]

  /** `Left(ApplicationError.NotFound)` with `ErrorCode.ClusterNotFound` for an id that is not configured — a
    * 404 and not a 500. An unknown cluster id is a statement about the request, and returning an
    * infrastructure error here would let a user dim the cluster capability for everyone by typing a bad path
    * segment.
    */
  def resolve(id: ClusterId): F[Either[KuiError, ClusterProfile]]

  def refs: F[List[ClusterRef]]

  def registryVersion: F[RegistryVersion]

  /** Re-reads the store and recomputes the overlay.
    *
    * Idempotent, and safe to call concurrently: two simultaneous reloads produce one resolved state and at
    * most one version bump. Returns the snapshot it settled on, so a caller need not read it back.
    */
  def reload: F[RegistrySnapshot]

  /** Emits the current snapshot immediately, then one element per *actual* change. A reload that resolves to
    * the same profiles emits nothing.
    */
  def changes: Stream[F, RegistrySnapshot]
}

object ClusterRegistry {

  val Operation: String = "kui.cluster.registry"

  /** The overlay rule, as a pure function.
    *
    * Whole-profile replacement by the store, keyed on `ClusterId`, with the static entry kept as the
    * fallback. Not a field-by-field merge: an operator who removes `security` from a stored record would
    * silently inherit the configuration file's credentials — a change that reads as "I removed the
    * credentials" and behaves as "I kept them". It would also make the version meaningless, because a version
    * identifies a record and half a record has no version.
    *
    * A cluster the store knows about and the configuration file does not is **added**, not ignored: that is
    * the whole point of storing clusters at all.
    */
  def overlay(
      static: List[ClusterProfile],
      stored: List[ClusterProfile]
  ): Map[ClusterId, ClusterProfile] = {
    val staticById = static.map(profile => profile.id -> profile).toMap
    val storedById = stored.map(profile => profile.id -> profile).toMap

    val fromStatic = staticById.map { (id, profile) =>
      id -> profile.at(profile.version, ProfileOrigin.Static)
    }

    val fromStore = storedById.map { (id, profile) =>
      val origin =
        if staticById.contains(id) then ProfileOrigin.StaticThenStored else ProfileOrigin.Stored

      id -> profile.at(profile.version, origin)
    }

    fromStatic ++ fromStore
  }

  /** Builds the registry and performs the first resolution.
    *
    * `Resource` because `changes` is backed by a `Topic` that must be shut down with the service, and
    * `Concurrent` because the registry holds a `Ref` and serialises `reload` behind a `Semaphore` — the
    * weakest bound that works, since nothing here sleeps or times out.
    *
    * **The first resolution never fails.** If the store cannot be read at construction time the registry is
    * built from the static list alone, `storeHealth` is `Degraded`, and one WARN is logged. A cluster service
    * that refused to start because its metadata store was down would take the whole UI with it — and the
    * bootstrap ordering already completes the store's replay before this is constructed, so a failure here
    * means the store broke after replay, which is exactly the case KUI is designed to survive.
    */
  def make[F[_]: Concurrent](
      static: List[ClusterProfile],
      store: ClusterConfigStore[F],
      clock: ClockPort[F],
      logger: StructuredLogger[F]
  ): Resource[F, ClusterRegistry[F]] =
    for {
      topic <- Resource.eval(Topic[F, RegistrySnapshot])
      gate <- Resource.eval(Semaphore[F](1L))
      // A placeholder the first `reload` immediately replaces. It is never observable: `make` does
      // not yield the registry until that reload has completed.
      now <- Resource.eval(clock.now)
      state <- Resource.eval(
        Ref.of[F, RegistrySnapshot](
          RegistrySnapshot(Map.empty, RegistryVersion.Initial, StoreHealth.NotConfigured, now)
        )
      )
      registry = new Impl[F](static, store, clock, logger, state, topic, gate)
      _ <- Resource.eval(registry.firstLoad)
      // Closing the topic wakes every subscriber with an end-of-stream instead of leaving it
      // parked on a `Topic` nobody will ever publish to again.
      _ <- Resource.onFinalize(topic.close.void)
    } yield registry

  final private class Impl[F[_]: Concurrent](
      static: List[ClusterProfile],
      store: ClusterConfigStore[F],
      clock: ClockPort[F],
      logger: StructuredLogger[F],
      state: Ref[F, RegistrySnapshot],
      topic: Topic[F, RegistrySnapshot],
      gate: Semaphore[F]
  ) extends ClusterRegistry[F] {

    private val context: Map[String, String] =
      Map("service.name" -> ClusterService.Id.value, "operation" -> Operation)

    def snapshot: F[RegistrySnapshot] = state.get

    def list: F[List[ClusterProfile]] = state.get.map(_.profiles.values.toList)

    def refs: F[List[ClusterRef]] = state.get.map(_.refs)

    def registryVersion: F[RegistryVersion] = state.get.map(_.version)

    def resolve(id: ClusterId): F[Either[KuiError, ClusterProfile]] =
      state.get.map { current =>
        current.get(id).toRight(notFound(id))
      }

    def changes: Stream[F, RegistrySnapshot] =
      // `subscribe` first, then the current value: a subscriber that read the value first could
      // miss a change published in the gap and then never learn about it.
      Stream.resource(topic.subscribeAwait(1)).flatMap { updates =>
        Stream.eval(state.get) ++ updates
      }

    /** The construction-time load. Identical to `reload` except that it logs its outcome at INFO, because the
      * first resolution is the one line an operator looks for when a deployment comes up.
      */
    def firstLoad: F[Unit] =
      reload.flatMap { resolved =>
        logger.info(
          context ++ Map(
            "cluster.count" -> resolved.size.toString,
            "store.health" -> healthLabel(resolved.storeHealth),
            "registry.version" -> resolved.version.tag
          )
        )(s"resolved ${resolved.size} configured cluster(s)")
      }

    def reload: F[RegistrySnapshot] =
      // One resolution at a time. Without this, two reloads racing on a read-then-write `Ref` would
      // each see the pre-change version and both bump to the same number, and one of the two
      // resolved sets would be silently discarded.
      gate.permit.use(_ => resolveOnce)

    private def resolveOnce: F[RegistrySnapshot] =
      for {
        stored <- store.list
        health <- storeHealth(stored)
        now <- clock.now
        change <- state.modify { previous =>
          // A store that could not be read takes nothing away. Recomputing the overlay from an
          // empty stored list would delete every cluster the store contributed the moment the
          // store went away, which is the opposite of "clusters keep resolving from last known
          // state"; so a failed read replays the records the last successful one produced. Static
          // configuration is applied either way, which is what makes the very first resolution
          // succeed against a store that was already down.
          val records = stored.getOrElse(lastStored(previous))
          val resolved = overlay(static, records)

          val changed = previous.profiles != resolved
          val version = if changed then previous.version.next else previous.version
          val updated = RegistrySnapshot(resolved, version, health, now)

          (updated, (changed, previous, updated))
        }
        (changed, previous, updated) = change
        _ <- logChange(changed, previous, updated)
        _ <- if changed then topic.publish1(updated).void else Concurrent[F].unit
      } yield updated

    /** The store's own verdict, unless reading it failed — in which case the failure *is* the verdict, and
      * the store's answer to `health` cannot be trusted either.
      */
    private def storeHealth(read: Either[KuiError, List[ClusterProfile]]): F[StoreHealth] =
      read match {
        case Right(_) => store.health
        case Left(error) =>
          for {
            now <- clock.now
            // WARN and never ERROR: the product is designed to serve through this, and an ERROR
            // here would page someone for a state that has a documented, tested behaviour.
            _ <- logger.warn(
              context ++ Map("store.health" -> "degraded", "error.code" -> error.code.wire)
            )(s"the metadata store could not be read: ${error.message}")
          } yield StoreHealth.Degraded(error.message, now)
      }

    private def logChange(
        changed: Boolean,
        previous: RegistrySnapshot,
        updated: RegistrySnapshot
    ): F[Unit] =
      if !changed then
        logger.debug(context ++ Map("registry.version" -> updated.version.tag))(
          "the resolved cluster set did not change"
        )
      else {
        val before = previous.profiles.keySet
        val after = updated.profiles.keySet
        val added = (after -- before).size
        val removed = (before -- after).size
        val modified = (before intersect after).count(id => previous.profiles(id) != updated.profiles(id))

        // Counts, never names or profiles: the interesting number is how much changed, and a
        // profile on a log line is how a bootstrap string ends up in a log aggregator.
        logger.info(
          context ++ Map(
            "registry.version" -> updated.version.tag,
            "cluster.count" -> updated.size.toString,
            "cluster.added" -> added.toString,
            "cluster.removed" -> removed.toString,
            "cluster.changed" -> modified.toString
          )
        )(s"the resolved cluster set changed: +$added -$removed ~$modified")
      }

    /** The profiles in a snapshot that came from the store rather than from configuration.
      *
      * They are identified by their origin, which `overlay` is the only place that sets — so this cannot
      * drift from the rule it is recovering.
      */
    private def lastStored(snapshot: RegistrySnapshot): List[ClusterProfile] =
      snapshot.profiles.values.filter(_.origin != ProfileOrigin.Static).toList

    private def healthLabel(health: StoreHealth): String = health match {
      case StoreHealth.Online => "online"
      case StoreHealth.Degraded(_, _) => "degraded"
      case StoreHealth.NotConfigured => "not-configured"
    }

    private def notFound(id: ClusterId): KuiError =
      ApplicationError.NotFound("cluster", id.value, ErrorCode.ClusterNotFound)
  }
}
