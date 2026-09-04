package kui.cluster.application

import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.testkit.TestControl

import kui.cluster.application.fakes.FakeClusterAdmin
import kui.cluster.domain.*
import kui.kernel.BrokerId
import kui.kernel.error.{ApplicationError, InfrastructureError, KuiError}
import kui.testkit.fakes.FakeStructuredLogger

/** The refresh: what it needs, what it skips, and what happens to the fibers when a cluster goes away.
  *
  * Tests 3 and 4 are the executable form of "probe, never assume": a cluster that does not advertise a
  * feature is never asked about it, so a ZooKeeper cluster does not raise an unsupported-version error every
  * thirty seconds for ever.
  */
final class ClusterSnapshotsSuite extends munit.CatsEffectSuite {

  private val prod = ClusterProfileFixtures.plaintext("prod", "Production")
  private val staging = ClusterProfileFixtures.plaintext("staging", "Staging")

  private val unreachable: KuiError =
    InfrastructureError.Unreachable("the cluster", "connection refused")

  private def refreshOne(
      admin: FakeClusterAdmin[IO],
      features: ClusterFeatures,
      logger: FakeStructuredLogger[IO]
  ): IO[Either[KuiError, ClusterTopology]] =
    ClusterSnapshots.refreshOne[IO](admin, prod, features, logger)

  test("refreshOneNeedsOnlyDescribeCluster") {
    // The managed-service case: `describeCluster` answers and everything else refuses. The page must
    // still render.
    for {
      logger <- FakeStructuredLogger[IO]
      admin <- FakeClusterAdmin.make[IO](TopologyFixtures.defaultDescription)
      _ <- admin.set(
        _.copy(
          version = Left(unreachable),
          quorum = Left(unreachable),
          logDirs = Left(unreachable)
        )
      )
      result <- refreshOne(admin, TopologyFixtures.allFeatures, logger)
    } yield result match {
      case Right(topology) =>
        assertEquals(topology.version, None)
        assertEquals(topology.quorum, None)
        assertEquals(topology.load, Map.empty[BrokerId, BrokerLoad])
      case Left(error) => fail(s"an optional failure must not fail the refresh: $error")
    }
  }

  test("refreshOneFailsWhenDescribeClusterFails") {
    for {
      logger <- FakeStructuredLogger[IO]
      admin <- FakeClusterAdmin.make[IO](TopologyFixtures.defaultDescription)
      _ <- admin.set(_.copy(description = Left(unreachable)))
      result <- refreshOne(admin, TopologyFixtures.allFeatures, logger)
    } yield
      // Passed through unchanged: the adapter already classified it, and re-wrapping would lose the
      // code the capability fold keys on.
      assertEquals(result, Left(unreachable))
  }

  test("quorumIsNotCalledWithoutTheKRaftQuorumFeature") {
    for {
      logger <- FakeStructuredLogger[IO]
      admin <- FakeClusterAdmin.make[IO](TopologyFixtures.defaultDescription)
      _ <- refreshOne(admin, TopologyFixtures.features(Set.empty), logger)
      calls <- admin.calls
    } yield assert(!calls.exists(_._2 == "describeQuorum"), s"nothing should have asked: $calls")
  }

  test("logDirsAreNotCalledWithoutTheLogDirsFeature") {
    for {
      logger <- FakeStructuredLogger[IO]
      admin <- FakeClusterAdmin.make[IO](TopologyFixtures.defaultDescription)
      _ <- refreshOne(admin, TopologyFixtures.features(Set.empty), logger)
      calls <- admin.calls
    } yield assert(!calls.exists(_._2 == "describeLogDirs"))
  }

  test("aSkippedBrokerGetsNoBrokerLoadAndTheOthersDo") {
    val dirs = List(TopologyFixtures.logDir("/data", List(TopologyFixtures.replica("orders", 0, 10L))))

    val partial = PartialResult(
      Map(BrokerId.unsafe(1) -> dirs, BrokerId.unsafe(3) -> dirs),
      Map(BrokerId.unsafe(2) -> SkipReason.Unauthorized)
    )

    for {
      logger <- FakeStructuredLogger[IO]
      admin <- FakeClusterAdmin.make[IO](TopologyFixtures.defaultDescription)
      _ <- admin.set(_.copy(logDirs = Right(partial)))
      result <- refreshOne(admin, TopologyFixtures.allFeatures, logger)
    } yield result match {
      case Right(topology) =>
        // A skipped broker gets *no* entry rather than an empty one: an empty load renders as a
        // broker with no disks, which is a different and wrong statement.
        assertEquals(topology.load.keySet, Set(BrokerId.unsafe(1), BrokerId.unsafe(3)))
        assertEquals(topology.load(BrokerId.unsafe(1)).skewPercent, Some(0.0))
      case Left(error) => fail(s"a partial result must not fail the refresh: $error")
    }
  }

  test("optionalFailuresAreLoggedAtDebugNotWarn") {
    // The natural instinct is WARN, and it would make a healthy managed cluster produce two
    // warnings every thirty seconds for ever.
    for {
      logger <- FakeStructuredLogger[IO]
      admin <- FakeClusterAdmin.make[IO](TopologyFixtures.defaultDescription)
      _ <- admin.set(_.copy(version = Left(unreachable), logDirs = Left(unreachable)))
      _ <- refreshOne(admin, TopologyFixtures.allFeatures, logger)
      entries <- logger.entries
    } yield {
      assert(entries.count(_.level == "debug") >= 2, s"expected debug lines, got $entries")
      assertEquals(entries.count(_.level == "warn"), 0)
    }
  }

  test("theLoopRefreshesOnTheInterval") {
    val scenario = ClusterRig.resource(List(prod)).use { rig =>
      for {
        _ <- ClusterRig.settled(rig)
        // Counted from a clean slate, because settling is not free: it forces one refresh of its own
        // so that every suite starts from a topology built with a finished capability probe. Resetting
        // here keeps this test measuring the thing it is named after — the background loop's cadence —
        // rather than the sum of the loop and the setup.
        _ <- rig.admin.reset
        _ <- IO.sleep(61.seconds)
        calls <- rig.admin.callsFor(prod.id)
      } yield
      // Exactly two, not "at least one": the loop's ticks at 30 s and 60 s and nothing else. A
      // duplicated loop would still pass an at-least assertion and would double every cluster's
      // admin traffic.
      assertEquals(calls.count(_ == "describeCluster"), 2)
    }

    TestControl.executeEmbed(scenario)
  }

  test("aClusterAddedToTheRegistryGetsACell") {
    val scenario = ClusterRig.resource(List(prod)).use { rig =>
      for {
        _ <- ClusterRig.settled(rig)
        before <- rig.snapshots.topologyOf(staging.id)
        _ <- rig.store.setProfiles(List(staging))
        _ <- rig.registry.reload
        after <- ClusterRig.eventually(rig.snapshots.topologyOf(staging.id))(_.isDefined)
      } yield {
        assert(before.isEmpty)
        assert(after.isDefined)
      }
    }

    TestControl.executeEmbed(scenario)
  }

  test("aClusterRemovedFromTheRegistryHasItsLoopCancelled") {
    // The leak test. A cell whose cluster was deleted but whose fiber survived keeps
    // authenticating, every thirty seconds, to a cluster the operator removed.
    val scenario = ClusterRig.resource(Nil).use { rig =>
      for {
        _ <- rig.store.setProfiles(List(prod))
        _ <- rig.registry.reload
        _ <- ClusterRig.eventually(rig.snapshots.topologyOf(prod.id))(_.isDefined)
        _ <- ClusterRig.settled(rig)
        _ <- rig.store.setProfiles(Nil)
        _ <- rig.registry.reload
        _ <- ClusterRig.eventually(rig.snapshots.topologyOf(prod.id))(_.isEmpty)
        before <- rig.admin.callsFor(prod.id)
        _ <- IO.sleep(90.seconds)
        after <- rig.admin.callsFor(prod.id)
      } yield assertEquals(after.size, before.size, "the removed cluster's loop must be gone")
    }

    TestControl.executeEmbed(scenario)
  }

  test("aChangedProfileReplacesTheCell") {
    val rotated = ClusterProfileFixtures.at(prod, "rotated:9092")

    val scenario = ClusterRig.resource(Nil).use { rig =>
      for {
        _ <- rig.store.setProfiles(List(prod))
        _ <- rig.registry.reload
        _ <- ClusterRig.eventually(rig.snapshots.topologyOf(prod.id))(_.isDefined)
        _ <- ClusterRig.settled(rig)
        _ <- rig.admin.reset
        _ <- rig.store.setProfiles(List(rotated))
        _ <- rig.registry.reload
        seen <- ClusterRig.eventually(rig.admin.seenProfiles)(
          _.exists(_.bootstrap.value == "rotated:9092")
        )
      } yield assert(
        seen.exists(_.bootstrap.value == "rotated:9092"),
        // A rotated credential must not keep being used by a loop that captured the old profile.
        s"the new profile must reach the loop, saw ${seen.map(_.bootstrap.value).distinct}"
      )
    }

    TestControl.executeEmbed(scenario)
  }

  test("theCapabilityProbeIsNotRepeatedOnEveryTopologyRefresh") {
    // Capabilities are hourly and the topology is every thirty seconds. Probing on every refresh
    // would multiply a six-call probe by every cluster, every thirty seconds.
    val scenario = ClusterRig.resource(List(prod)).use { rig =>
      for {
        _ <- ClusterRig.settled(rig)
        _ <- IO.sleep(5.minutes)
        calls <- rig.admin.callsFor(prod.id)
      } yield assertEquals(calls.count(_ == "capabilities"), 1)
    }

    TestControl.executeEmbed(scenario)
  }

  test("anUnknownClusterHasNoCellAndForcingARefreshSaysSo") {
    ClusterRig.resource(List(prod)).use { rig =>
      for {
        missing <- rig.snapshots.topologyOf(staging.id)
        requested <- rig.snapshots.requestRefresh(staging.id)
      } yield {
        assertEquals(missing, None)
        assert(!requested, "there is nothing to refresh, and saying otherwise would be a lie")
      }
    }
  }

  test("aRefusedOptionalCallIsAnApplicationErrorAndStillYieldsATopology") {
    for {
      logger <- FakeStructuredLogger[IO]
      admin <- FakeClusterAdmin.make[IO](TopologyFixtures.defaultDescription)
      _ <- admin.set(_.copy(logDirs = Left(ApplicationError.Unsupported("log directories"))))
      result <- refreshOne(admin, TopologyFixtures.allFeatures, logger)
    } yield assert(result.isRight, "a cluster that will not answer an optional call still renders")
  }
}
