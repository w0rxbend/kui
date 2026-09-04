package kui.cluster.application

import java.time.Instant

import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.kernel.Resource
import cats.syntax.all.*

import kui.cache.{CacheMetrics, SnapshotCell, SnapshotStatus}
import kui.cluster.application.fakes.{FakeClusterAdmin, FakeClusterConfigStore}
import kui.cluster.domain.*
import kui.testkit.fakes.{FakeClock, FakeStructuredLogger}

/** The whole application layer, wired to fakes, as one resource.
  *
  * Every suite above the registry needs the same five pieces in the same order, and assembling them by hand
  * in each one would mean five slightly different rigs and five different reasons a test could be wrong.
  */
final case class ClusterRig(
    registry: ClusterRegistry[IO],
    snapshots: ClusterSnapshots[IO],
    topology: ClusterTopologyUseCase[IO],
    brokers: BrokerDetailUseCase[IO],
    capabilities: CapabilityReportUseCase[IO],
    admin: FakeClusterAdmin[IO],
    store: FakeClusterConfigStore[IO],
    logger: FakeStructuredLogger[IO]
)

object ClusterRig {

  val RefreshInterval: FiniteDuration = 30.seconds

  val CapabilityInterval: FiniteDuration = 1.hour

  def resource(
      profiles: List[ClusterProfile],
      features: ClusterFeatures = TopologyFixtures.allFeatures,
      delay: FiniteDuration = Duration.Zero,
      storeHealth: StoreHealth = StoreHealth.Online,
      description: ClusterDescription = TopologyFixtures.defaultDescription,
      /** Applied to the admin fake *before* the snapshot cells start, so that the first refresh already
        * sees what the suite wants it to see. A suite that configured the fake afterwards would be
        * asserting against a snapshot filled from the defaults.
        */
      setup: FakeClusterAdmin[IO] => IO[Unit] = _ => IO.unit
  ): Resource[IO, ClusterRig] =
    for {
      clock <- Resource.eval(FakeClock[IO]())
      logger <- Resource.eval(FakeStructuredLogger[IO])
      store <- Resource.eval(FakeClusterConfigStore.make[IO](Nil, storeHealth))
      admin <- Resource.eval(FakeClusterAdmin.make[IO](description, features, delay))
      _ <- Resource.eval(setup(admin))
      registry <- ClusterRegistry.make[IO](profiles, store, clockPort(clock), logger)
      snapshots <- ClusterSnapshots.resource[IO](
        registry,
        admin,
        CacheMetrics.noop[IO],
        RefreshInterval,
        CapabilityInterval,
        logger
      )
      topology = ClusterTopologyUseCase.make[IO](registry, snapshots, logger)
      brokers = BrokerDetailUseCase.make[IO](registry, snapshots, admin, logger)
      capabilities = CapabilityReportUseCase.make[IO](registry, snapshots)
    } yield ClusterRig(registry, snapshots, topology, brokers, capabilities, admin, store, logger)

  /** Waits until every configured cluster has a topology built from a finished capability probe.
    *
    * The registry publishes its changes asynchronously, so a suite that read a snapshot immediately would be
    * asserting on the gap rather than on the behaviour. It polls rather than sleeping for a fixed time
    * because a fixed sleep is a race that passes on a quiet machine and fails on a busy one — which is
    * exactly the flake this helper exists to remove.
    *
    * ==Why the capability cell is waited for first, and the topology then re-read==
    *
    * A cluster's two cells are started together and each loads on a fiber of its own. `SnapshotCell.get`
    * never blocks, so the topology load reads whatever the capability cell holds *at that moment*, and a
    * capability cell that has not answered yet reports every feature as unknown — which makes that first
    * topology refresh skip `describeLogDirs` and `describeQuorum` altogether. In production that is the
    * intended trade: a first page in a second beats a first page that waits on a six-feature probe of a
    * cluster that may be down, and the next refresh thirty seconds later fills the gaps in.
    *
    * For a suite it is a coin toss. Whether the first snapshot contains log directories depended on which
    * of two fibers finished first, and on a starved machine the topology won about one run in thirty —
    * `BrokerDetailUseCaseSuite.logDirsFallsBackToTheSnapshotWhenTheLiveCallFails` then had nothing to fall
    * back to. So "settled" here means the stronger and more useful thing: the probe has answered *and* the
    * topology has been refreshed since, so what the cells hold is what a running KUI holds after its first
    * minute rather than during its first second. The extra refresh is forced rather than waited for,
    * because waiting for the background loop would put a thirty-second sleep in every suite.
    */
  def settled(rig: ClusterRig): IO[Unit] = {
    // The budget is one virtual (or real) minute rather than a second: a suite that deliberately
    // makes the admin port take ten seconds per call still has to be able to reach a first
    // snapshot, and polling stops the moment the cells are ready, so a healthy rig pays nothing
    // for the headroom.
    def loaded(cellOf: ClusterRef => IO[Option[SnapshotCell[IO, ?]]]): IO[Boolean] =
      rig.registry.refs.flatMap { refs =>
        refs.traverse { ref =>
          cellOf(ref).flatMap {
            case None => false.pure[IO]
            case Some(cell) => cell.get.map(_.status != SnapshotStatus.Initializing)
          }
        }
      }.map(_.forall(identity))

    // Giving up quietly would turn "the snapshot was never loaded" into a confusing assertion
    // failure somewhere else in the suite, minutes of reading away from the cause. It fails here,
    // saying what it waited for, instead.
    def waitFor(what: String)(ready: IO[Boolean]): IO[Unit] = {
      def attempt(remaining: Int): IO[Unit] =
        ready.flatMap { done =>
          if done then IO.unit
          else if remaining <= 0 then
            IO.raiseError(new AssertionError(s"$what after 60 seconds; the rig never settled"))
          else IO.sleep(1.millisecond) >> attempt(remaining - 1)
        }

      attempt(60000)
    }

    for {
      _ <- waitFor("the capability cells were still initializing")(
        loaded(ref => rig.snapshots.capabilitiesOf(ref.id).map(_.map(cell => cell)))
      )
      _ <- waitFor("the snapshot cells were still initializing")(
        loaded(ref => rig.snapshots.topologyOf(ref.id).map(_.map(cell => cell)))
      )
      _ <- waitFor("a topology built from the probed feature set never arrived")(probed(rig))
    } yield ()
  }

  /** Refreshes each topology until it was built from the probe, and says whether it has been.
    *
    * One forced refresh is not enough, and the reason is worth writing down because it is the shape of
    * the flake this helper exists to remove. `SnapshotCell` writes its new state *before* it clears the
    * slot that marks a refresh as in flight, and `refresh` joins an in-flight refresh rather than
    * starting another. In the window between those two writes — microseconds on an idle machine,
    * unbounded on a starved one — a forced refresh joins the load that is already finishing and comes
    * back with that load's value, which for the very first load is a topology built before the
    * capability probe answered. Asking again until the answer is right turns a race into a condition,
    * which is the whole point.
    */
  private def probed(rig: ClusterRig): IO[Boolean] =
    rig.registry.refs.flatMap { refs =>
      refs.traverse { ref =>
        (rig.snapshots.capabilitiesOf(ref.id), rig.snapshots.topologyOf(ref.id)).tupled.flatMap {
          case (Some(capabilities), Some(topology)) =>
            for {
              features <- capabilities.get.map(_.value)
              snapshot <- topology.refresh
            } yield features.forall(probe => snapshot.value.forall(_.features == probe))
          // A cluster with no cells yet is not settled; the caller polls again.
          case _ => false.pure[IO]
        }
      }
    }.map(_.forall(identity))

  /** Retries `read` until `holds` is true, or gives up after a bounded number of attempts.
    *
    * The registry's changes reach the snapshot cells on a background fiber, so "the cell for the cluster I
    * just added" is available *eventually* and not immediately. Polling for the condition the test actually
    * cares about is what keeps these suites deterministic on a busy machine.
    */
  def eventually[A](read: IO[A])(holds: A => Boolean): IO[A] = {
    def attempt(remaining: Int): IO[A] =
      read.flatMap { value =>
        if holds(value) || remaining <= 0 then value.pure[IO]
        else IO.sleep(1.millisecond) >> attempt(remaining - 1)
      }

    attempt(1000)
  }

  private def clockPort(clock: FakeClock[IO]): ClockPort[IO] =
    new ClockPort[IO] {
      def now: IO[Instant] = clock.now
    }
}
