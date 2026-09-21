package kui.cluster.application

import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.testkit.TestControl

import kui.cache.Snapshot
import kui.cluster.application.fakes.FakeClusterAdmin
import kui.cluster.domain.*
import kui.kernel.error.{ApplicationError, InfrastructureError, KuiError}
import kui.kernel.{BrokerId, TopicName}
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

  private val missingCell: Throwable =
    new AssertionError("the rig settled without giving this cluster a cell")

  private def refreshOne(
      admin: FakeClusterAdmin[IO],
      features: ClusterFeatures,
      logger: FakeStructuredLogger[IO],
      sweep: Option[TopicSweep] = None
  ): IO[Either[KuiError, ClusterTopology]] =
    ClusterSnapshots.refreshOne[IO](admin, prod, features, sweep, logger)

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

  test("aCompleteSweepFillsTheClusterWideCountsAndTheyAddUp") {
    val sweep = TopologyFixtures.sweep(
      List(
        TopologyFixtures.placement(leader = 1),
        TopologyFixtures.placement(leader = 2, inSync = Some(List(2, 3))),
        PartitionPlacement(leader = None, replicas = Set(BrokerId.unsafe(1)), inSync = Set.empty)
      ),
      topics = 2
    )

    for {
      logger <- FakeStructuredLogger[IO]
      admin <- FakeClusterAdmin.make[IO](TopologyFixtures.defaultDescription)
      result <- refreshOne(admin, TopologyFixtures.allFeatures, logger, Some(sweep))
    } yield result match {
      case Left(error) => fail(s"a complete sweep must not fail the refresh: $error")
      case Right(topology) =>
        // The offline partition is under-replicated too — no replica of it is in sync — so the two counts
        // overlap by design. They are not a partition of the total and a screen must not subtract them.
        val counted = PartitionSummary(online = 2, offline = 1, underReplicated = 2)

        assertEquals(topology.partitions, Some(counted))
        // Online plus offline is every partition the sweep saw, which is the arithmetic a screen does
        // when it draws the donut.
        assertEquals(topology.partitions.map(p => p.online + p.offline), Some(3))
        assertEquals(topology.topics, Some(2))
        assertEquals(topology.leadersOn(BrokerId.unsafe(1)), Some(1))
    }
  }

  test("oneUnreadableTopicWithholdsAllThreePartitionFiguresRatherThanSummingWhatAnswered") {
    // The refusal the whole packet turns on. The census inside this sweep is a genuine fold over the
    // topics that answered — two online partitions is a true statement about *some* of the cluster — and
    // publishing it would put a reassuring number where the honest answer is nothing.
    val partial = TopologyFixtures
      .sweep(List(TopologyFixtures.placement(leader = 1), TopologyFixtures.placement(leader = 2)), topics = 3)
      .copy(unreadable = Set(TopicName.unsafe("payments")))

    for {
      logger <- FakeStructuredLogger[IO]
      admin <- FakeClusterAdmin.make[IO](TopologyFixtures.defaultDescription)
      result <- refreshOne(admin, TopologyFixtures.allFeatures, logger, Some(partial))
    } yield result match {
      case Left(error) => fail(s"an incomplete sweep costs the figures, not the page: $error")
      case Right(topology) =>
        assertEquals(topology.partitions, None)
        assertEquals(topology.partitionsOn(BrokerId.unsafe(1)), None)
        assertEquals(topology.leadersOn(BrokerId.unsafe(1)), None)
        // The listing succeeded, so the topic count survives the describes that did not. Two different
        // calls, two different failures.
        assertEquals(topology.topics, Some(3))
    }
  }

  test("aSweepThatHasStoppedAnsweringContributesNothingToATopologyStampedNow") {
    // The cell deliberately keeps the last good sweep so that the *sweep* can be reported as stale. What
    // must not happen is that a topology stamped with this instant carries it: that dates a count from a
    // cluster which has since stopped answering as though it had just been taken. Nothing asserted it —
    // replacing the `Option.unless(swept.status.isOffline)` guard with `swept.value` left 2633 green.
    val measured = TopologyFixtures.sweep(
      List(TopologyFixtures.placement(leader = 1), TopologyFixtures.placement(leader = 2)),
      topics = 2
    )

    ClusterRig
      .resource(List(prod), setup = admin => admin.set(_.copy(sweep = Right(measured))))
      .use { rig =>
        for {
          _ <- ClusterRig.settled(rig)
          before <- rig.snapshots
            .topologyOf(prod.id)
            .flatMap(cell => cell.fold(IO.raiseError[Snapshot[ClusterTopology]](missingCell))(_.get))
          // The sweep stops answering. Its cell still holds the two partitions it found a moment ago.
          _ <- rig.admin.set(_.copy(sweep = Left(unreachable)))
          sweep <- rig.snapshots
            .partitionsOf(prod.id)
            .flatMap(cell => cell.fold(IO.raiseError[Snapshot[TopicSweep]](missingCell))(_.refresh))
          after <- rig.snapshots
            .topologyOf(prod.id)
            .flatMap(cell => cell.fold(IO.raiseError[Snapshot[ClusterTopology]](missingCell))(_.refresh))
        } yield {
          // The positive half: while the sweep was answering, the counts were published.
          assertEquals(before.value.flatMap(_.partitions).map(_.online), Some(2))
          // The cell kept its value, and reports itself offline. That is what "stale" is made of.
          assert(sweep.status.isOffline, s"the sweep cell should be offline: ${sweep.status}")
          assertEquals(sweep.value.map(_.topics), Some(2))
          // And the topology taken after it declines the figures rather than repeating them.
          assertEquals(after.value.flatMap(_.partitions), None)
          assertEquals(after.value.flatMap(_.topics), None)
        }
      }
  }

  test("aControllerWindowThatIsNotYetFullRefusesAndStillStatesItsLength") {
    val scenario = ClusterRig.resource(List(prod)).use { rig =>
      for {
        _ <- ClusterRig.settled(rig)
        view <- rig.topology.view(prod.id)
      } yield view.flatMap(_.topology.toRight(unreachable)) match {
        case Left(error) => fail(s"the cluster must have a topology: $error")
        case Right(topology) =>
          val uptime = topology.controllerUptime

          assertEquals(uptime.flatMap(_.percent), None, "a window minutes old cannot answer for six hours")
          // The length still travels, which is what lets a browser print "over the last 6h" and say it is
          // still collecting rather than drawing an empty ring.
          assertEquals(uptime.map(_.window), Some(ClusterRig.UptimeWindow))
      }
    }

    TestControl.executeEmbed(scenario)
  }

  test("aFullControllerWindowAnswersFromWhatWasObserved") {
    // Six hours of virtual time: 720 refreshes at thirty seconds, one sample per one-minute bucket, every
    // one of them finding the fixture's controller.
    val scenario = ClusterRig.resource(List(prod)).use { rig =>
      for {
        _ <- ClusterRig.settled(rig)
        _ <- IO.sleep(ClusterRig.UptimeWindow + 1.minute)
        view <- rig.topology.view(prod.id)
      } yield view.flatMap(_.topology.toRight(unreachable)) match {
        case Left(error) => fail(s"the cluster must have a topology: $error")
        case Right(topology) =>
          assertEquals(topology.controllerUptime.flatMap(_.percent), Some(100.0d))
          assertEquals(topology.controllerUptime.map(_.coverage), Some(ClusterRig.UptimeWindow))
      }
    }

    TestControl.executeEmbed(scenario)
  }

  test("aScrapeKuiCouldNotMakeRecordsNothingInTheUptimeWindow") {
    // `load`'s failure branch says it in words — "a scrape KUI could not make is not a cluster without a
    // controller, and a `false` here would report KUI's own outage as the cluster's" — and nothing
    // asserted it. Inserting `uptime.record(now, false)` before the raise left
    // `./mill services.cluster.__.test` at 509/509 green, after which three hours of KUI being unable to
    // reach a perfectly healthy cluster draws a controller-uptime ring at about 50 %.
    //
    // Six hours of virtual time fills the window, then three hours in which every scrape fails, then one
    // successful refresh so that a topology can be read at all. The window is one minute per bucket, so
    // the failing stretch is 180 buckets: a `false` in any of them moves the percentage by tens of points
    // and cannot be confused with rounding.
    val scenario = ClusterRig.resource(List(prod)).use { rig =>
      for {
        _ <- ClusterRig.settled(rig)
        _ <- IO.sleep(ClusterRig.UptimeWindow + 1.minute)
        full <- rig.topology.view(prod.id)
        _ <- rig.admin.set(_.copy(description = Left(unreachable)))
        _ <- IO.sleep(ClusterRig.UptimeWindow / 2)
        _ <- rig.admin.set(_.copy(description = Right(TopologyFixtures.defaultDescription)))
        recovered <- rig.snapshots
          .topologyOf(prod.id)
          .flatMap(cell => cell.fold(IO.raiseError[Snapshot[ClusterTopology]](missingCell))(_.refresh))
      } yield {
        // The positive half, so that this case cannot pass by measuring nothing: the window really was
        // full and really did answer before the outage.
        val before = full.toOption.flatMap(_.topology).flatMap(_.controllerUptime)
        assertEquals(before.flatMap(_.percent), Some(100.0d))

        val uptime = recovered.value
          .flatMap(_.controllerUptime)
          .getOrElse(fail("a refreshed topology must carry an uptime figure"))

        assertEquals(uptime.coverage, ClusterRig.UptimeWindow, "the window is still full")
        assertEquals(uptime.percent, Some(100.0d))
      }
    }

    TestControl.executeEmbed(scenario)
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
