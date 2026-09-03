package kui.gateway.application.capability

import java.time.Instant
import scala.concurrent.duration.DurationInt

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import munit.CatsEffectSuite

import kui.contracts.capability.{CapabilityChange, CapabilityKey, CapabilityState, ReasonCode}
import kui.kernel.ServiceId
import kui.observability.Telemetry
import kui.testkit.fakes.FakeStructuredLogger

/** That the registry's concurrency behaves, given that the fold already decides correctly.
  *
  * Everything here is about timing, ordering and isolation, and all of it runs on `TestControl`'s virtual
  * clock: a suite that asserts a ten-second debounce by sleeping for ten seconds is both slow and flaky,
  * and the flakiness lands on the one component that must never be flaky.
  */
final class CapabilityRegistrySuite extends CatsEffectSuite {

  private val cluster: CapabilityKey = CapabilityKey(ServiceId.unsafe("cluster"), None)
  private val topic: CapabilityKey = CapabilityKey(ServiceId.unsafe("topic"), None)

  private val outage: CapabilityState =
    CapabilityState.Unavailable(ReasonCode.UpstreamUnavailable, "refused", Instant.EPOCH)

  private def registry(config: RegistryConfig = RegistryConfig.Default) =
    for {
      logger <- fs2.Stream.resource(cats.effect.kernel.Resource.eval(FakeStructuredLogger[IO])).compile.lastOrError
      resource = CapabilityRegistry.resource[IO](config, Telemetry.noop[IO], logger)
    } yield (resource, logger)

  test("publishesOneChangePerTransitionAndNoneForRepeatedIdenticalReports") {
    val program = registry().flatMap { (resource, _) =>
      resource.use { registry =>
        for {
          seen <- Ref.of[IO, Vector[CapabilityChange]](Vector.empty)
          reader <- registry.changes.evalMap(change => seen.update(_ :+ change)).compile.drain.start
          _ <- IO.sleep(1.second)
          _ <- registry.report(cluster, CapabilityState.Available)
          _ <- registry.report(cluster, CapabilityState.Available)
          _ <- registry.report(cluster, CapabilityState.Available)
          _ <- IO.sleep(1.second)
          changes <- seen.get
          _ <- reader.cancel
        } yield changes.toList
      }
    }

    TestControl.executeEmbed(program).map { changes =>
      assertEquals(changes.size, 1, s"a steady state must publish nothing: $changes")
      assertEquals(changes.head.previous, None)
      assertEquals(changes.head.entry.state, CapabilityState.Available)
    }
  }

  test("availableToUnavailableIsDebouncedButRecoveryIsImmediate") {
    val program = registry().flatMap { (resource, _) =>
      resource.use { registry =>
        for {
          _ <- registry.report(cluster, CapabilityState.Available)
          _ <- registry.report(cluster, outage)
          // One second in, the outage has not been published: a single dropped poll is not an outage.
          _ <- IO.sleep(1.second)
          duringDebounce <- registry.state(cluster)
          // Past the debounce window it is.
          _ <- IO.sleep(10.seconds)
          afterDebounce <- registry.state(cluster)
          // Recovery is not debounced at all: someone is watching a fallback panel waiting for it.
          _ <- registry.report(cluster, CapabilityState.Available)
          recovered <- registry.state(cluster)
        } yield (duringDebounce, afterDebounce, recovered)
      }
    }

    TestControl.executeEmbed(program).map { (during, after, recovered) =>
      assertEquals(during, CapabilityState.Available)
      assertEquals(after, outage)
      assertEquals(recovered, CapabilityState.Available)
    }
  }

  test("aFlapThatRecoversInsideTheWindowIsNeverPublished") {
    val program = registry().flatMap { (resource, _) =>
      resource.use { registry =>
        for {
          _ <- registry.report(cluster, CapabilityState.Available)
          _ <- registry.report(cluster, outage)
          _ <- IO.sleep(2.seconds)
          _ <- registry.report(cluster, CapabilityState.Available)
          _ <- IO.sleep(30.seconds)
          state <- registry.state(cluster)
        } yield state
      }
    }

    // The whole point of the asymmetry: the sidebar does not strobe.
    TestControl.executeEmbed(program).assertEquals(CapabilityState.Available)
  }

  test("aSlowSubscriberDoesNotBlockTheRegistry") {
    val config = RegistryConfig.Default.copy(subscriberQueueSize = 2)

    val program = registry(config).flatMap { (resource, logger) =>
      resource.use { registry =>
        for {
          seen <- Ref.of[IO, Vector[CapabilityChange]](Vector.empty)
          // A subscriber that takes a whole day over its first event -- a laptop that was suspended
          // with a tab open. `IO.never` would be more literal and would deadlock the virtual clock,
          // which has nothing to advance to; a day is longer than the rest of this test by any measure.
          stalled <- registry.changes.evalMap(_ => IO.sleep(1.day)).compile.drain.start
          // And one that keeps up.
          reader <- registry.changes.evalMap(change => seen.update(_ :+ change)).compile.drain.start
          _ <- IO.sleep(1.second)
          // Far more changes than the stalled subscriber's mailbox can hold.
          _ <- (1 to 20).toList.traverse_ { n =>
            // A millisecond of virtual time between reports, so the *healthy* subscriber gets a chance
            // to drain its two-slot mailbox. Without it this would be asserting that a two-slot queue
            // can hold twenty events, which is a statement about the fixture rather than the registry.
            registry.report(
              CapabilityKey(ServiceId.unsafe(s"service-$n"), None),
              CapabilityState.Available
            ) *> IO.sleep(1.millisecond)
          }
          _ <- IO.sleep(1.second)
          healthy <- seen.get
          warnings <- logger.entries.map(_.count(_.level == "warn"))
          snapshot <- registry.snapshot
          _ <- stalled.cancel
          _ <- reader.cancel
        } yield (healthy.size, warnings, snapshot.size)
      }
    }

    TestControl.executeEmbed(program).map { (healthy, warnings, snapshot) =>
      // `report` completed twenty times despite the stalled subscriber, the healthy subscriber saw
      // everything, and the drops were logged rather than swallowed.
      assertEquals(snapshot, 20)
      assertEquals(healthy, 20)
      assert(warnings > 0, "dropping a slow subscriber's events must be logged")
    }
  }

  test("disconnectingOneSubscriberDoesNotAffectAnother") {
    val program = registry().flatMap { (resource, _) =>
      resource.use { registry =>
        for {
          seen <- Ref.of[IO, Vector[CapabilityChange]](Vector.empty)
          leaving <- registry.changes.compile.drain.start
          staying <- registry.changes.evalMap(change => seen.update(_ :+ change)).compile.drain.start
          _ <- IO.sleep(1.second)
          _ <- leaving.cancel
          _ <- registry.report(cluster, CapabilityState.Available)
          _ <- registry.report(topic, CapabilityState.Available)
          _ <- IO.sleep(1.second)
          changes <- seen.get
          _ <- staying.cancel
        } yield changes.size
      }
    }

    TestControl.executeEmbed(program).assertEquals(2)
  }

  test("twoSubscribersBothReceiveEveryChange") {
    val program = registry().flatMap { (resource, _) =>
      resource.use { registry =>
        for {
          first <- Ref.of[IO, Int](0)
          second <- Ref.of[IO, Int](0)
          a <- registry.changes.evalMap(_ => first.update(_ + 1)).compile.drain.start
          b <- registry.changes.evalMap(_ => second.update(_ + 1)).compile.drain.start
          _ <- IO.sleep(1.second)
          _ <- registry.report(cluster, CapabilityState.Available)
          _ <- registry.report(topic, CapabilityState.Available)
          _ <- IO.sleep(1.second)
          counts <- (first.get, second.get).tupled
          _ <- a.cancel *> b.cancel
        } yield counts
      }
    }

    TestControl.executeEmbed(program).assertEquals((2, 2))
  }

  test("probeNowReturnsAfterTheStateIsRecomputed") {
    val program = registry().flatMap { (resource, _) =>
      resource.use { registry =>
        for {
          // Standing in for GW-004's poller: the trigger polls and reports before it returns, which is
          // what makes the UI's "Retry now" button honest rather than decorative.
          _ <- registry.attachProbe(service =>
            registry.report(CapabilityKey(service, None), CapabilityState.Available)
          )
          _ <- registry.probeNow(ServiceId.unsafe("cluster"))
          state <- registry.state(cluster)
        } yield state
      }
    }

    TestControl.executeEmbed(program).assertEquals(CapabilityState.Available)
  }

  test("probeNowIsANoOpBeforeAPollerIsAttached") {
    // A deployment with no poller at all must not make the retry button throw.
    val program = registry().flatMap { (resource, _) =>
      resource.use(_.probeNow(ServiceId.unsafe("cluster")).attempt)
    }
    TestControl.executeEmbed(program).map(result => assert(result.isRight, s"probeNow failed: $result"))
  }

  test("snapshotIsConsistentWithTheDeltasASubscriberReceived") {
    // The invariant the SSE contract rests on (ADR-032): a client is sent a snapshot on connect and
    // deltas afterwards, so replaying the deltas onto the snapshot it started from has to reproduce the
    // registry exactly. If it does not, a browser's sidebar drifts from reality and nothing tells it.
    val services = (1 to 8).map(n => CapabilityKey(ServiceId.unsafe(s"service-$n"), None)).toList
    val statuses = List(CapabilityState.Available, CapabilityState.NotConfigured, outage)

    val reports = for {
      round <- (1 to 4).toList
      key <- services
    } yield (key, statuses((round + key.service.value.length) % statuses.size))

    val program = registry(RegistryConfig.Default.copy(debounce = 1.millisecond)).flatMap {
      (resource, _) =>
        resource.use { registry =>
          for {
            seen <- Ref.of[IO, Vector[CapabilityChange]](Vector.empty)
            initial <- registry.snapshot
            reader <- registry.changes.evalMap(change => seen.update(_ :+ change)).compile.drain.start
            _ <- IO.sleep(1.second)
            _ <- reports.traverse_((key, state) => registry.report(key, state) *> IO.sleep(10.milliseconds))
            _ <- IO.sleep(1.second)
            deltas <- seen.get
            snapshot <- registry.snapshot
            _ <- reader.cancel
          } yield (initial, deltas.toList, snapshot)
        }
    }

    TestControl.executeEmbed(program).map { (initial, deltas, snapshot) =>
      val replayed = deltas.foldLeft(initial)((acc, change) => acc.updated(change.entry.key, change.entry.state))
      assertEquals(replayed, snapshot)
    }
  }
}
