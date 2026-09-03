package kui.cluster.application

import scala.concurrent.duration.FiniteDuration

import cats.effect.kernel.{Ref, Resource, Temporal}
import cats.effect.std.{Semaphore, Supervisor}
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger

import kui.cache.{CacheMetrics, SnapshotCell, SnapshotLoadFailure}
import kui.cluster.domain.*
import kui.kernel.error.KuiError
import kui.kernel.{BrokerId, ClusterId}

/** The per-cluster snapshot cells, kept in step with the registry.
  *
  * One pair of cells per cluster — the topology, refreshed every thirty seconds, and the capability probe,
  * refreshed hourly and on reconnect. The pair exists because the two have completely different costs and
  * completely different rates of change, and one cell would have to be refreshed at the faster of the two.
  */
trait ClusterSnapshots[F[_]] {

  def topologyOf(id: ClusterId): F[Option[SnapshotCell[F, ClusterTopology]]]

  def capabilitiesOf(id: ClusterId): F[Option[SnapshotCell[F, ClusterFeatures]]]

  /** Every cluster currently held, in registry order. */
  def all: F[List[(ClusterRef, SnapshotCell[F, ClusterTopology])]]

  /** Starts a refresh of one cluster's topology and returns immediately.
    *
    * It must not await completion: a forced refresh against a dead cluster would otherwise block for the full
    * admin timeout, and the button that triggered it would hang — which is the exact failure the whole
    * snapshot design exists to avoid. Deduplication is the cell's, so twenty presses are one admin call.
    */
  def requestRefresh(id: ClusterId): F[Boolean]
}

object ClusterSnapshots {

  val CacheName: String = "cluster.topology"

  val CapabilityCacheName: String = "cluster.capabilities"

  /** Builds the cells and keeps them in step with the registry for as long as the resource is held.
    *
    * Releasing it cancels every refresh loop and every supervised forced refresh. That matters more than it
    * looks: a leaked refresh fiber is a fiber holding a Kafka admin client, authenticating every thirty
    * seconds to a cluster the operator has removed.
    */
  def resource[F[_]: Temporal](
      registry: ClusterRegistry[F],
      admin: ClusterAdmin[F],
      metrics: CacheMetrics[F],
      refreshInterval: FiniteDuration,
      capabilityInterval: FiniteDuration,
      logger: StructuredLogger[F]
  ): Resource[F, ClusterSnapshots[F]] =
    for {
      supervisor <- Supervisor[F]
      cells <- Resource.eval(Ref.of[F, Map[ClusterId, Entry[F]]](Map.empty))
      gate <- Resource.eval(Semaphore[F](1L))
      impl = new Impl[F](
        registry,
        admin,
        metrics,
        refreshInterval,
        capabilityInterval,
        logger,
        supervisor,
        cells,
        gate
      )
      // Release every cell before the supervisor goes away, so that a cell's own finalizer runs
      // while its fiber can still be cancelled rather than after the supervisor has torn it down.
      _ <- Resource.onFinalize(impl.releaseAll)
      _ <- Resource.eval(registry.snapshot.flatMap(impl.sync))
      _ <- Resource.eval(
        supervisor.supervise(registry.changes.evalMap(impl.sync).compile.drain).void
      )
    } yield impl

  /** The refresh: the ordered set of admin calls that produce one `ClusterTopology`.
    *
    * Only `describeCluster` is required. A cluster that answers it is reachable and its page must render: a
    * broker list with no disk figures is far more useful than an "unavailable" panel, and it is exactly what
    * a managed service or a cluster where KUI lacks `DESCRIBE_CONFIGS` looks like.
    *
    * The three optional calls run in parallel, and two of them are skipped entirely when the probed feature
    * set says the cluster cannot answer them — which is what probing buys: no `UnsupportedVersionException`
    * every thirty seconds against a ZooKeeper cluster.
    *
    * Optional failures are logged at DEBUG and not WARN. On a managed service they fire every thirty seconds
    * for ever, and a warning that always fires is noise that teaches an operator to filter the log.
    */
  def refreshOne[F[_]: Temporal](
      admin: ClusterAdmin[F],
      profile: ClusterProfile,
      features: ClusterFeatures,
      logger: StructuredLogger[F]
  ): F[Either[KuiError, ClusterTopology]] = {
    val context = Map(
      "service.name" -> ClusterService.Id.value,
      "operation" -> ClusterTopologyUseCase.Operation,
      "cluster.id" -> profile.id.value
    )

    def optional[A](name: String, call: F[Either[KuiError, A]], fallback: A): F[A] =
      call.flatMap {
        case Right(value) => value.pure[F]
        case Left(error) =>
          logger
            .debug(context ++ Map("error.code" -> error.code.wire))(
              s"$name is not available on this cluster: ${error.message}"
            )
            .as(fallback)
      }

    admin.describeCluster(profile).flatMap {
      case Left(error) => error.asLeft[ClusterTopology].pure[F]
      case Right(description) =>
        val version = optional("version detection", admin.detectVersion(profile), None)

        val quorum =
          if features.has(ClusterFeature.KRaftQuorum) then
            optional("the metadata quorum", admin.describeQuorum(profile), None)
          else Option.empty[QuorumInfo].pure[F]

        val load =
          if features.has(ClusterFeature.LogDirs) then
            optional(
              "log directories",
              admin.describeLogDirs(profile, description.brokerIds).map(_.map(loadOf)),
              Map.empty[BrokerId, BrokerLoad]
            )
          else Map.empty[BrokerId, BrokerLoad].pure[F]

        // `both` rather than a `for`-comprehension: the three optional calls are independent, and
        // running them one after another would make a page wait for three round trips instead of
        // one. They are three calls to one already-bounded admin client, so nothing further limits
        // them.
        Temporal[F].both(version, Temporal[F].both(quorum, load)).map { (detected, rest) =>
          val (members, perBroker) = rest
          ClusterTopology(
            cluster = profile.ref,
            description = description,
            version = detected,
            quorum = members,
            features = features,
            load = BrokerLoad.withSkew(perBroker),
            partitions = None,
            topics = None
          ).asRight[KuiError]
        }
    }
  }

  /** A skipped broker gets no `BrokerLoad` at all rather than an empty one: an empty load renders as a broker
    * with no disks, which is a different and wrong statement.
    */
  private def loadOf(
      dirs: PartialResult[BrokerId, List[LogDir]]
  ): Map[BrokerId, BrokerLoad] =
    dirs.values.map { (broker, logDirs) =>
      val replicas = logDirs.map(_.currentReplicas.size).sum
      broker -> BrokerLoad(replicas, leaders = None, skewPercent = None, logDirs = logDirs)
    }

  /** One cluster's pair of cells, and the action that stops both. */
  final private case class Entry[F[_]](
      profile: ClusterProfile,
      topology: SnapshotCell[F, ClusterTopology],
      capabilities: SnapshotCell[F, ClusterFeatures],
      release: F[Unit]
  )

  final private class Impl[F[_]: Temporal](
      registry: ClusterRegistry[F],
      admin: ClusterAdmin[F],
      metrics: CacheMetrics[F],
      refreshInterval: FiniteDuration,
      capabilityInterval: FiniteDuration,
      logger: StructuredLogger[F],
      supervisor: Supervisor[F],
      cells: Ref[F, Map[ClusterId, Entry[F]]],
      gate: Semaphore[F]
  ) extends ClusterSnapshots[F] {

    def topologyOf(id: ClusterId): F[Option[SnapshotCell[F, ClusterTopology]]] =
      cells.get.map(_.get(id).map(_.topology))

    def capabilitiesOf(id: ClusterId): F[Option[SnapshotCell[F, ClusterFeatures]]] =
      cells.get.map(_.get(id).map(_.capabilities))

    def all: F[List[(ClusterRef, SnapshotCell[F, ClusterTopology])]] =
      for {
        held <- cells.get
        order <- registry.refs
      } yield order.flatMap(ref => held.get(ref.id).map(entry => ref -> entry.topology))

    def requestRefresh(id: ClusterId): F[Boolean] =
      topologyOf(id).flatMap {
        case None => false.pure[F]
        case Some(cell) => supervisor.supervise(cell.refresh).as(true)
      }

    /** Starts cells for clusters that appeared, and cancels and drops cells for clusters that disappeared.
      *
      * A profile whose *contents* changed has its cells replaced rather than reused: a rotated password must
      * not keep being used by a loop that captured the old profile, and the previous topology describes a
      * cluster that may no longer be the one configured.
      */
    def sync(snapshot: RegistrySnapshot): F[Unit] =
      gate.permit.use { _ =>
        for {
          held <- cells.get
          wanted = snapshot.profiles
          obsolete = held.filter((id, entry) => !wanted.get(id).contains(entry.profile))
          fresh = wanted.filterNot((id, profile) => held.get(id).exists(_.profile == profile))
          _ <- obsolete.values.toList.traverse_(_.release)
          _ <- cells.update(_ -- obsolete.keySet)
          _ <- fresh.values.toList.traverse_(start)
        } yield ()
      }

    def releaseAll: F[Unit] =
      cells.getAndSet(Map.empty).flatMap(_.values.toList.traverse_(_.release))

    private def start(profile: ClusterProfile): F[Unit] = {
      val capabilities = SnapshotCell.resource[F, ClusterFeatures](
        CapabilityCacheName,
        profile.id,
        capabilityInterval,
        metrics
      )(admin.capabilities(profile))

      for {
        capsAllocated <- capabilities.allocated
        (capsCell, releaseCaps) = capsAllocated
        // Was the last topology refresh a failure? It is the trigger for re-probing capabilities on
        // reconnect: the usual reason a cluster was offline is that it was being upgraded, and its
        // feature set is the thing most likely to have changed while it was away.
        wasOffline <- Ref.of[F, Boolean](false)
        topology = SnapshotCell.resource[F, ClusterTopology](
          CacheName,
          profile.id,
          refreshInterval,
          metrics
        )(load(profile, capsCell, wasOffline))
        topologyAllocated <- topology.allocated
        (topologyCell, releaseTopology) = topologyAllocated
        entry = Entry(profile, topologyCell, capsCell, releaseTopology >> releaseCaps)
        _ <- cells.update(_.updated(profile.id, entry))
      } yield ()
    }

    /** The cell's `load`. It raises rather than returns the failure, because `SnapshotCell` catches a
      * `Throwable` and unwraps a `SnapshotLoadFailure` back into the `KuiError` the adapter already
      * classified — which is how a `KuiError` survives an effect that can only carry a `Throwable`.
      */
    private def load(
        profile: ClusterProfile,
        capabilities: SnapshotCell[F, ClusterFeatures],
        wasOffline: Ref[F, Boolean]
    ): F[ClusterTopology] =
      for {
        probed <- capabilities.get
        now <- Temporal[F].realTimeInstant
        // A cluster whose capability cell has not answered yet gets `unprobed`, which skips the two
        // optional calls on this first pass and fills them in thirty seconds later. The alternative
        // is a first refresh that waits on a six-feature probe of a cluster that may be down.
        features = probed.value.getOrElse(ClusterFeatures.unprobed(now))
        result <- refreshOne(admin, profile, features, logger)
        topology <- result match {
          case Left(error) =>
            wasOffline.set(true) >> Temporal[F].raiseError[ClusterTopology](SnapshotLoadFailure(error))
          case Right(value) =>
            wasOffline.getAndSet(false).flatMap { recovered =>
              if recovered then supervisor.supervise(capabilities.refresh).as(value)
              else value.pure[F]
            }
        }
      } yield topology
  }
}
