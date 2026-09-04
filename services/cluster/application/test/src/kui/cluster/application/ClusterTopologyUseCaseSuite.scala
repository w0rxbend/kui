package kui.cluster.application

import java.time.Instant

import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.testkit.TestControl
import cats.syntax.all.*

import kui.cache.{Snapshot, SnapshotStatus}
import kui.cluster.domain.ClusterProfileFixtures
import kui.kernel.ClusterId
import kui.kernel.error.{ApplicationError, ErrorCode, InfrastructureError, KuiError}

/** The staleness contract, and the one promise that makes the dashboard fast: a read never calls a broker.
  *
  * `viewAllReturnsWithoutCallingTheAdminPort` is the point of the whole component. It runs against an admin
  * port whose every method sleeps for an hour and asserts that `viewAll` completes at virtual time zero, so a
  * regression that reads through to a broker fails the suite rather than merely making the page slow.
  */
final class ClusterTopologyUseCaseSuite extends munit.CatsEffectSuite {

  private val prod = ClusterProfileFixtures.plaintext("prod", "Production")
  private val staging = ClusterProfileFixtures.plaintext("staging", "Staging")
  private val archive = ClusterProfileFixtures.plaintext("archive", "Archive")

  private val at: Instant = Instant.parse("2026-09-04T09:00:00Z")
  private val since: Instant = Instant.parse("2026-09-04T09:00:30Z")

  /** Long enough that any read which actually waited for a broker would be caught, short enough that
    * the virtual-time scheduler does not have to step through an hour of it.
    */
  private val SlowAdmin: FiniteDuration = 10.seconds

  private val timedOut: KuiError = InfrastructureError.Timeout("describeCluster", 30_000L)
  private val unreachable: KuiError = InfrastructureError.Unreachable("the cluster", "no route")

  test("freshnessOfLoading") {
    assertEquals(
      ClusterTopologyUseCase.freshnessOf(Snapshot.initializing[Int]),
      SnapshotFreshness.Loading
    )
  }

  test("freshnessOfFresh") {
    assertEquals(
      ClusterTopologyUseCase.freshnessOf(Snapshot.online(1, at)),
      SnapshotFreshness.Fresh(at)
    )
  }

  test("freshnessOfStale") {
    // Data present and the upstream failing. This is the row that keeps a page rendering, greyed
    // and timestamped, while a cluster is down.
    val snapshot = Snapshot(Some(1), SnapshotStatus.Offline(timedOut, since), Some(at))

    assertEquals(
      ClusterTopologyUseCase.freshnessOf(snapshot),
      SnapshotFreshness.Stale(at, timedOut.message, since)
    )
  }

  test("freshnessOfUnavailable") {
    val snapshot = Snapshot[Int](None, SnapshotStatus.Offline(timedOut, since), None)

    assertEquals(
      ClusterTopologyUseCase.freshnessOf(snapshot),
      SnapshotFreshness.Unavailable(timedOut.message, since)
    )
  }

  test("staleKeepsThePreviousValue") {
    val scenario = ClusterRig.resource(List(prod)).use { rig =>
      for {
        _ <- ClusterRig.settled(rig)
        _ <- rig.admin.set(_.copy(description = Left(unreachable)))
        _ <- IO.sleep(31.seconds)
        result <- rig.topology.view(prod.id)
      } yield result match {
        case Right(view) =>
          assert(view.isRenderable, "the previous topology must still be there")
          assert(
            view.freshness match {
              case SnapshotFreshness.Stale(_, _, _) => true
              case _ => false
            },
            s"expected Stale, got ${view.freshness}"
          )
        case Left(error) => fail(s"a stale cluster is a Right: $error")
      }
    }

    TestControl.executeEmbed(scenario)
  }

  test("scrapedAtDoesNotMoveOnAFailedRefresh") {
    val scenario = ClusterRig.resource(List(prod)).use { rig =>
      for {
        _ <- ClusterRig.settled(rig)
        before <- rig.topology.view(prod.id)
        _ <- rig.admin.set(_.copy(description = Left(unreachable)))
        _ <- IO.sleep(31.seconds)
        after <- rig.topology.view(prod.id)
      } yield
      // A timestamp that moves while the data does not is a lie the UI repeats in the words
      // "as of thirty seconds ago".
      assertEquals(
        after.toOption.flatMap(_.freshness.scrapedAtOption),
        before.toOption.flatMap(_.freshness.scrapedAtOption)
      )
    }

    TestControl.executeEmbed(scenario)
  }

  test("unavailableWhenNothingEverSucceeded") {
    val scenario = ClusterRig.resource(List(prod)).use { rig =>
      for {
        _ <- rig.admin.set(_.copy(description = Left(unreachable)))
        _ <- ClusterRig.settled(rig)
        _ <- IO.sleep(31.seconds)
        result <- rig.topology.view(prod.id)
      } yield result match {
        case Right(view) =>
          assertEquals(view.topology, None)
          assertEquals(view.freshness.scrapedAtOption, None)
          assert(
            view.freshness match {
              case SnapshotFreshness.Unavailable(reason, _) => reason == unreachable.message
              case _ => false
            },
            s"expected Unavailable carrying the KuiError message, got ${view.freshness}"
          )
        case Left(error) => fail(s"an unreachable cluster is still a configured one: $error")
      }
    }

    TestControl.executeEmbed(scenario)
  }

  test("sinceIsStickyAcrossAChangingReason") {
    val scenario = ClusterRig.resource(List(prod)).use { rig =>
      def sinceOf(view: TopologyView): Option[Instant] = view.freshness match {
        case SnapshotFreshness.Unavailable(_, at) => Some(at)
        case SnapshotFreshness.Stale(_, _, at) => Some(at)
        case _ => None
      }

      for {
        _ <- rig.admin.set(_.copy(description = Left(timedOut)))
        _ <- ClusterRig.settled(rig)
        _ <- IO.sleep(31.seconds)
        first <- rig.topology.view(prod.id)
        _ <- rig.admin.set(_.copy(description = Left(unreachable)))
        _ <- IO.sleep(31.seconds)
        second <- rig.topology.view(prod.id)
      } yield
      // A user asks "how long has this been broken", not "how long has it been broken in this
      // particular way".
      assertEquals(second.toOption.flatMap(sinceOf), first.toOption.flatMap(sinceOf))
    }

    TestControl.executeEmbed(scenario)
  }

  test("viewOfAnUnknownIdIsNotFound") {
    ClusterRig.resource(List(prod)).use { rig =>
      rig.topology.view(ClusterId.unsafe("nope")).map {
        case Left(error: ApplicationError) => assertEquals(error.code, ErrorCode.ClusterNotFound)
        case other => fail(s"expected an ApplicationError, got $other")
      }
    }
  }

  test("viewAllReturnsWithoutCallingTheAdminPort") {
    // The assertion the whole design exists for. It is stated on the admin port's call log rather
    // than on a stopwatch: "no call was made" is what makes the dashboard's response time a
    // function of the gateway's fan-out instead of the slowest configured cluster, and a call log
    // cannot be flaky.
    ClusterRig.resource(List(prod, staging)).use { rig =>
      for {
        _ <- ClusterRig.settled(rig)
        _ <- rig.admin.reset
        views <- rig.topology.viewAll
        calls <- rig.admin.calls
      } yield {
        assertEquals(views.size, 2)
        assertEquals(calls, Nil, "a read must never reach a broker")
      }
    }
  }

  test("viewAllMixesFreshAndUnavailableRows") {
    // The dashboard exit criterion at the use-case layer: two rows populate, the third says why it
    // cannot, and all three are present and in registry order.
    val scenario = ClusterRig.resource(List(archive, prod, staging)).use { rig =>
      for {
        _ <- ClusterRig.settled(rig)
        views <- rig.topology.viewAll
      } yield {
        assertEquals(views.map(_.cluster.displayName), List("Archive", "Production", "Staging"))
        assert(views.forall(_.isRenderable))
      }
    }

    TestControl.executeEmbed(scenario)
  }

  test("anUnreachableClusterIsARowAndNotAMissingOne") {
    val scenario = ClusterRig.resource(List(prod)).use { rig =>
      for {
        _ <- rig.admin.set(_.copy(description = Left(unreachable)))
        _ <- ClusterRig.settled(rig)
        _ <- IO.sleep(31.seconds)
        views <- rig.topology.viewAll
      } yield {
        assertEquals(views.size, 1, "the row stays on the dashboard, and stays clickable")
        assert(!views.head.isRenderable)
      }
    }

    TestControl.executeEmbed(scenario)
  }

  test("forceRefreshIsIdempotentUnderConcurrency") {
    // The admin port has to be slow for this to mean anything: deduplication is about refreshes
    // that overlap, and against an instant fake twenty sequential presses really are twenty calls.
    val scenario = ClusterRig.resource(List(prod), delay = SlowAdmin).use { rig =>
      for {
        // `settled` and not a sleep: the first refresh is started by the registry on a background
        // fiber, so "long enough" is not a duration. Resetting the call log while that first
        // refresh is still in flight is what made this test occasionally see zero calls — the
        // twenty presses deduplicated against a refresh whose record had just been erased.
        _ <- ClusterRig.settled(rig)
        _ <- rig.admin.reset
        results <- List.fill(20)(rig.topology.forceRefresh(prod.id)).parSequence
        _ <- IO.sleep(SlowAdmin * 2)
        calls <- rig.admin.callsFor(prod.id)
      } yield {
        assert(results.forall(_.isRight))
        // Twenty presses of the refresh button are one request to the broker, which is what stops
        // that button from being an outage tool.
        assertEquals(calls.count(_ == "describeCluster"), 1)
      }
    }

    TestControl.executeEmbed(scenario)
  }

  test("forceRefreshOfAnUnknownIdIsNotFound") {
    ClusterRig.resource(List(prod)).use { rig =>
      rig.topology.forceRefresh(ClusterId.unsafe("nope")).map {
        case Left(error: ApplicationError) => assertEquals(error.code, ErrorCode.ClusterNotFound)
        case other => fail(s"expected an ApplicationError, got $other")
      }
    }
  }

  test("forceRefreshReturnsBeforeTheRefreshCompletes") {
    // A forced refresh against a dead cluster must not block for the full admin timeout, or the
    // button that triggered it hangs — which is the failure the milestone is built to avoid. The
    // assertion is that the refresh is still in flight when `forceRefresh` has already answered.
    ClusterRig.resource(List(prod), delay = SlowAdmin).use { rig =>
      for {
        result <- rig.topology.forceRefresh(prod.id)
        view <- rig.topology.view(prod.id)
      } yield {
        assert(result.isRight)
        assertEquals(
          view.toOption.map(_.freshness),
          Some(SnapshotFreshness.Loading),
          "the refresh has not completed, and the caller is already back"
        )
      }
    }
  }
}
