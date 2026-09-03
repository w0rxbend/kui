package kui.cluster.application

import java.time.Instant

import cats.effect.IO
import cats.effect.testkit.TestControl
import cats.syntax.all.*

import kui.cluster.application.fakes.FakeClusterConfigStore
import kui.cluster.domain.*
import kui.kernel.error.{ApplicationError, ErrorCode, InfrastructureError}
import kui.kernel.cluster.{ClusterSecurity, SaslMechanism, SaslProtocol}
import kui.kernel.{ClusterId, Secret}
import kui.testkit.fakes.{FakeClock, FakeStructuredLogger}

/** Which clusters this KUI knows about, and which of two sources wins when they disagree.
  *
  * The first six tests are the overlay table, asserted on the pure function with no effect at all. The rest
  * are about the two promises the registry makes when the store is not there: it still answers, and it says
  * so in `storeHealth` rather than in an error.
  */
final class ClusterRegistrySuite extends munit.CatsEffectSuite {

  private val prod = ClusterProfileFixtures.plaintext("prod", "Production")
  private val staging = ClusterProfileFixtures.plaintext("staging", "Staging")

  private def registry(
      static: List[ClusterProfile],
      store: FakeClusterConfigStore[IO]
  ): IO[(ClusterRegistry[IO], FakeStructuredLogger[IO])] =
    for {
      clock <- FakeClock[IO]()
      logger <- FakeStructuredLogger[IO]
      built <- ClusterRegistry
        .make[IO](static, store, clockPort(clock), logger)
        .allocated
        .map(_._1)
    } yield (built, logger)

  private def clockPort(clock: FakeClock[IO]): ClockPort[IO] =
    new ClockPort[IO] {
      def now: IO[Instant] = clock.now
    }

  test("staticOnlyClusterIsServedAsStatic") {
    val resolved = ClusterRegistry.overlay(List(prod), Nil)

    assertEquals(resolved.keySet, Set(prod.id))
    assertEquals(resolved(prod.id).origin, ProfileOrigin.Static)
  }

  test("storedOnlyClusterIsAdded") {
    // The exit criterion: a cluster the configuration file has never heard of must appear, not be
    // ignored. It is the whole point of storing clusters at all.
    val resolved = ClusterRegistry.overlay(Nil, List(staging))

    assertEquals(resolved.keySet, Set(staging.id))
    assertEquals(resolved(staging.id).origin, ProfileOrigin.Stored)
  }

  test("storedProfileWinsWholesale") {
    val configured = ClusterProfileFixtures.saslScram("prod", "Production")
    val stored = ClusterProfileFixtures.build("prod", "Production", ClusterSecurityFixture, "b:9092")

    val resolved = ClusterRegistry.overlay(List(configured), List(stored))

    // Both fields, because `security` is the one a field-level merge would have silently kept.
    assertEquals(resolved(stored.id).bootstrap.value, "b:9092")
    assertEquals(resolved(stored.id).security, ClusterSecurityFixture)
  }

  test("originIsStaticThenStoredWhenBothDescribeIt") {
    val stored = ClusterProfileFixtures.at(prod, "b:9092")
    val resolved = ClusterRegistry.overlay(List(prod), List(stored))

    assertEquals(resolved(prod.id).origin, ProfileOrigin.StaticThenStored)
  }

  test("removingTheStoreRecordFallsBackToStatic") {
    val stored = ClusterProfileFixtures.at(prod, "b:9092")

    assertEquals(ClusterRegistry.overlay(List(prod), List(stored))(prod.id).bootstrap.value, "b:9092")
    assertEquals(ClusterRegistry.overlay(List(prod), Nil)(prod.id).bootstrap.value, "broker-1:9092")
    assertEquals(ClusterRegistry.overlay(List(prod), Nil)(prod.id).origin, ProfileOrigin.Static)
  }

  test("overlayIsDeterministicUnderInputOrder") {
    val static = List(prod, staging)
    val stored = List(ClusterProfileFixtures.at(prod, "b:9092"))

    assertEquals(
      ClusterRegistry.overlay(static, stored),
      ClusterRegistry.overlay(static.reverse, stored.reverse)
    )
  }

  test("snapshotIsServedFromMemoryWhileTheStoreIsUnreachable") {
    for {
      store <- FakeClusterConfigStore.make[IO](List(prod, staging))
      built <- registry(Nil, store)
      (registryUnderTest, _) = built
      before <- registryUnderTest.snapshot
      _ <- store.fail(InfrastructureError.Unreachable("the metadata store", "connection refused"))
      after <- registryUnderTest.reload
    } yield {
      assertEquals(after.profiles.keySet, before.profiles.keySet, "the last known set keeps resolving")
      assert(after.storeHealth.isDegraded, s"expected degraded, got ${after.storeHealth}")
    }
  }

  test("constructionSucceedsWithAFailingStore") {
    for {
      store <- FakeClusterConfigStore.make[IO](Nil)
      _ <- store.fail(InfrastructureError.Unreachable("the metadata store", "no route"))
      built <- registry(List(prod), store)
      (registryUnderTest, logger) = built
      resolved <- registryUnderTest.snapshot
      entries <- logger.entries
    } yield {
      assertEquals(resolved.profiles.keySet, Set(prod.id))
      assert(resolved.storeHealth.isDegraded)
      // WARN and not ERROR: an ERROR here would page someone for a state the product is designed,
      // and tested, to serve through.
      assertEquals(entries.count(_.level == "warn"), 1)
      assertEquals(entries.count(_.level == "error"), 0)
    }
  }

  test("noStoreConfiguredReportsNotConfiguredAndNotDegraded") {
    for {
      store <- FakeClusterConfigStore.make[IO](Nil, StoreHealth.NotConfigured)
      built <- registry(List(prod), store)
      resolved <- built._1.snapshot
    } yield assertEquals(resolved.storeHealth, StoreHealth.NotConfigured)
  }

  test("versionBumpsOnlyOnARealChange") {
    for {
      store <- FakeClusterConfigStore.make[IO](List(prod))
      built <- registry(Nil, store)
      registryUnderTest = built._1
      first <- registryUnderTest.reload
      second <- registryUnderTest.reload
      _ <- store.setProfiles(List(ClusterProfileFixtures.at(prod, "b:9092")))
      third <- registryUnderTest.reload
    } yield {
      assertEquals(second.version, first.version, "an identical reload must not move the ETag")
      assertEquals(third.version.value, first.version.value + 1L)
    }
  }

  test("changesEmitsCurrentThenOnlyRealChanges") {
    for {
      store <- FakeClusterConfigStore.make[IO](List(prod))
      built <- registry(Nil, store)
      registryUnderTest = built._1
      // The changes are triggered *from inside* the stream, after the first element has arrived, so
      // the test cannot pass or fail on whether the subscription won a race with the reload.
      collected <- registryUnderTest.changes.zipWithIndex
        .evalTap { (_, index) =>
          if index == 0L then
            // A reload that resolves to the same profiles must emit nothing; only the second one
            // may wake the subscriber.
            registryUnderTest.reload >>
              store.setProfiles(List(prod, staging)) >>
              registryUnderTest.reload.void
          else IO.unit
        }
        .map(_._1)
        .take(2)
        .compile
        .toList
    } yield {
      assertEquals(collected.head.profiles.keySet, Set(prod.id), "the current value arrives first")
      assertEquals(collected(1).profiles.keySet, Set(prod.id, staging.id))
    }
  }

  test("concurrentReloadsProduceOneStateAndAtMostOneBump") {
    val scenario = for {
      store <- FakeClusterConfigStore.make[IO](List(prod))
      built <- registry(Nil, store)
      registryUnderTest = built._1
      before <- registryUnderTest.registryVersion
      _ <- store.setProfiles(List(prod, staging))
      results <- List.fill(20)(registryUnderTest.reload).parSequence
      after <- registryUnderTest.registryVersion
    } yield {
      // The test that fails if `reload` is written as read-then-write on a `Ref` with no semaphore.
      assert(after.value <= before.value + 1L, s"version moved from $before to $after")
      assertEquals(results.map(_.profiles).distinct.size, 1)
    }

    TestControl.executeEmbed(scenario)
  }

  test("resolveOfAnUnknownIdIsANotFoundApplicationError") {
    for {
      store <- FakeClusterConfigStore.make[IO](Nil)
      built <- registry(List(prod), store)
      result <- built._1.resolve(ClusterId.unsafe("nope"))
    } yield result match {
      case Left(error: ApplicationError) => assertEquals(error.code, ErrorCode.ClusterNotFound)
      case other => fail(s"expected an ApplicationError, got $other")
    }
  }

  test("refsAreSortedByDisplayNameAndCarryNoSecrets") {
    val secretive = ClusterProfileFixtures.saslScram("secure", "Alpha")

    for {
      store <- FakeClusterConfigStore.make[IO](Nil)
      built <- registry(List(prod, staging, secretive), store)
      refs <- built._1.refs
    } yield {
      assertEquals(refs.map(_.displayName), List("Alpha", "Production", "Staging"))
      assert(!refs.mkString.contains(ClusterProfileFixtures.Canary))
    }
  }

  /** A security setting that is visibly different from the fixture's default, so that
    * `storedProfileWinsWholesale` is asserting a replacement rather than a coincidence.
    */
  private val ClusterSecurityFixture: ClusterSecurity =
    ClusterSecurity.Sasl(
      SaslProtocol.SaslPlaintext,
      SaslMechanism.Plain("stored", Secret("stored-password")),
      None
    )
}
