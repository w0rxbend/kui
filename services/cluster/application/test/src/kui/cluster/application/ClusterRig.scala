package kui.cluster.application

import java.time.Instant

import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.kernel.Resource
import cats.syntax.all.*

import kui.cache.{CacheMetrics, SnapshotStatus}
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

  /** Waits until every configured cluster has a cell whose first refresh has finished, one way or the other.
    *
    * The registry publishes its changes asynchronously, so a suite that read a snapshot immediately would be
    * asserting on the gap rather than on the behaviour. It polls rather than sleeping for a fixed time
    * because a fixed sleep is a race that passes on a quiet machine and fails on a busy one — which is
    * exactly the flake this helper exists to remove.
    */
  def settled(rig: ClusterRig): IO[Unit] = {
    // The budget is one virtual (or real) minute rather than a second: a suite that deliberately
    // makes the admin port take ten seconds per call still has to be able to reach a first
    // snapshot, and polling stops the moment the cells are ready, so a healthy rig pays nothing
    // for the headroom.
    def ready: IO[Boolean] =
      rig.registry.refs.flatMap { refs =>
        refs.traverse { ref =>
          rig.snapshots.topologyOf(ref.id).flatMap {
            case None => false.pure[IO]
            case Some(cell) => cell.get.map(_.status != SnapshotStatus.Initializing)
          }
        }
      }.map(_.forall(identity))

    // Giving up quietly would turn "the snapshot was never loaded" into a confusing assertion
    // failure somewhere else in the suite, minutes of reading away from the cause. It fails here,
    // saying what it waited for, instead.
    def attempt(remaining: Int): IO[Unit] =
      ready.flatMap { settled =>
        if settled then IO.unit
        else if remaining <= 0 then
          IO.raiseError(
            new AssertionError(
              "the snapshot cells were still initializing after 60 seconds; the rig never settled"
            )
          )
        else IO.sleep(1.millisecond) >> attempt(remaining - 1)
      }

    attempt(60000)
  }

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
