package kui.gateway.application.capability

import scala.concurrent.duration.DurationInt

import cats.effect.IO
import cats.effect.kernel.{Ref, Resource}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import munit.CatsEffectSuite

import kui.contracts.capability.{CapabilityKey, CapabilityState}
import kui.gateway.application.client.{ServiceClients, ServiceHealth, StubServiceClient}
import kui.kernel.ServiceId
import kui.testkit.fakes.FakeStructuredLogger

/** That the gateway notices, by itself, within one interval, when a service dies or comes back.
  *
  * All of it on `TestControl`'s virtual clock. These are assertions about a ten-second poll interval, a
  * five-second poll budget and a one-minute log rate limit; running them in real time would make the suite
  * take minutes and would make it flaky on a loaded machine, which is the last place flakiness belongs.
  */
final class ReadinessPollerSuite extends CatsEffectSuite {

  private val interval = 10.seconds
  private val cluster = ServiceId.unsafe("cluster")
  private val topic = ServiceId.unsafe("topic")

  private def keyOf(service: ServiceId) = CapabilityKey(service, None)

  /** A poller over the given stubs, plus the registry it reports into and the log it writes to. */
  private def fixture(
      stubs: List[StubServiceClient[IO]]
  ): Resource[IO, (CapabilityRegistry[IO], FakeStructuredLogger[IO], Trigger[IO])] =
    for {
      logger <- Resource.eval(FakeStructuredLogger[IO])
      registry <- CapabilityRegistry.resource[IO](
        // A one-millisecond debounce: the debounce itself is `CapabilityRegistrySuite`'s subject, and
        // leaving it at ten seconds here would mean every assertion about the poller had to reason about
        // it too.
        RegistryConfig.Default.copy(debounce = 1.millisecond),
        kui.observability.Telemetry.noop[IO],
        logger
      )
      signals <- Resource.eval(
        CapabilitySignals.make[IO](RegistryConfig.Default, registry, stubs.map(_.service))
      )
      trigger <- ReadinessPoller.resource[IO](
        ServiceClients.of(stubs.map(stub => stub: kui.gateway.application.client.ServiceClient[IO])),
        signals,
        interval,
        logger
      )
      _ <- Resource.eval(registry.attachProbe(trigger.probe))
    } yield (registry, logger, trigger)

  private def stub(id: ServiceId, health: ServiceHealth = ServiceHealth.Healthy) =
    StubServiceClient[IO](id, health)

  test("everyConfiguredServiceIsStartingBeforeItsFirstPoll") {
    // The first-page-load rule: a service the gateway knows about but has not asked yet appears in the
    // snapshot as starting, rather than being absent or reported as down.
    val program =
      for {
        logger <- FakeStructuredLogger[IO]
        snapshot <- CapabilityRegistry
          .resource[IO](RegistryConfig.Default, kui.observability.Telemetry.noop[IO], logger)
          .use(registry =>
            CapabilitySignals.make[IO](RegistryConfig.Default, registry, List(cluster, topic)) *>
              registry.snapshot
          )
      } yield snapshot

    TestControl.executeEmbed(program).map { snapshot =>
      assertEquals(snapshot.keySet, Set(keyOf(cluster), keyOf(topic)))
      snapshot(keyOf(cluster)) match {
        case CapabilityState.Degraded(reason) =>
          assertEquals(reason.code, kui.contracts.capability.ReasonCode.Starting)
        case other => fail(s"expected degraded-starting before the first poll, got $other")
      }
    }
  }

  test("pollsEachServiceOncePerInterval") {
    val program = (stub(cluster), stub(topic)).tupled.flatMap { (a, b) =>
      fixture(List(a, b)).use { _ =>
        // Four intervals, plus the interval the jitter can push the first poll into.
        IO.sleep(interval * 5) *> (a.polls, b.polls).tupled
      }
    }

    TestControl.executeEmbed(program).map { (first, second) =>
      // Between four and five polls each: the exact count depends on where the jitter put the first one,
      // and pinning it would be asserting the random number generator rather than the cadence.
      assert(first >= 4 && first <= 5, s"cluster was polled $first times in five intervals")
      assert(second >= 4 && second <= 5, s"topic was polled $second times in five intervals")
    }
  }

  test("aFailingPollReportsUnavailableWithinOneInterval") {
    // The milestone's timing promise: the sidebar dims within seconds of a service dying.
    val program = stub(cluster).flatMap { stub =>
      fixture(List(stub)).use { (registry, _, _) =>
        for {
          _ <- IO.sleep(interval * 2)
          healthy <- registry.state(keyOf(cluster))
          _ <- stub.health.set(ServiceHealth.Down)
          _ <- IO.sleep(interval + 1.second)
          dead <- registry.state(keyOf(cluster))
        } yield (healthy, dead)
      }
    }

    TestControl.executeEmbed(program).map { (healthy, dead) =>
      assertEquals(healthy, CapabilityState.Available)
      assert(
        dead match {
          case CapabilityState.Unavailable(_, _, _) => true
          case _ => false
        },
        s"one interval after the service died it should be unavailable, was $dead"
      )
    }
  }

  test("recoveryIsReportedOnTheNextSuccessfulPoll") {
    val program = stub(cluster, ServiceHealth.Down).flatMap { stub =>
      fixture(List(stub)).use { (registry, _, _) =>
        for {
          _ <- IO.sleep(interval * 2)
          _ <- stub.health.set(ServiceHealth.Healthy)
          _ <- IO.sleep(interval + 1.second)
          state <- registry.state(keyOf(cluster))
        } yield state
      }
    }

    TestControl.executeEmbed(program).assertEquals(CapabilityState.Available)
  }

  test("aServiceSlowerThanThePollBudgetIsUnavailableThoughItAnswersInsideTheInterval") {
    // `MaxPollTimeout` is the whole of "a readiness check that has not answered in five seconds has
    // answered". It could be raised from five seconds to fifty-five with every one of the gateway's cases
    // green, because none of them distinguished a service slower than the *budget* from one slower than
    // the *interval*: the budget is `interval.min(MaxPollTimeout)`, so at this suite's ten-second interval
    // any value at or above ten leaves the poller behaving exactly as if the constant did not exist.
    //
    // Two services on either side of the five-second line, polled on the same interval, so the assertion
    // is about the budget and not about slowness. `Hanging` above cannot make this point: a service that
    // never answers is refused by any budget shorter than for ever.
    val program =
      (stub(cluster, ServiceHealth.Slow(8.seconds)), stub(topic, ServiceHealth.Slow(3.seconds))).tupled
        .flatMap { (slower, quicker) =>
          fixture(List(slower, quicker)).use { (registry, _, _) =>
            IO.sleep(interval * 6) *>
              (registry.state(keyOf(cluster)), registry.state(keyOf(topic))).tupled
          }
        }

    TestControl.executeEmbed(program).map { (slowerState, quickerState) =>
      assert(
        slowerState match {
          case CapabilityState.Unavailable(_, _, _) => true
          case _ => false
        },
        s"a service answering after 8s, past the ${ReadinessPoller.MaxPollTimeout} poll budget, " +
          s"was $slowerState"
      )
      // Not `Available`: three seconds is inside the budget but outside the registry's latency threshold,
      // so this one is reported `Degraded`. That is the contrast the case needs — it answered, and the
      // poller believed it — and asserting the exact state here would be asserting `RegistryConfig`'s
      // thresholds, which are `CapabilityRegistrySuite`'s subject and not this one's.
      assert(
        quickerState match {
          case CapabilityState.Unavailable(_, _, _) => false
          case _ => true
        },
        s"a service answering inside the poll budget was refused as $quickerState"
      )
    }
  }

  test("aHangingServiceDoesNotBlockOtherServicesPolls") {
    // Fault isolation at the poller level: one service that never answers must not stop the gateway
    // learning about the other ten.
    val program = (stub(cluster, ServiceHealth.Hanging), stub(topic)).tupled.flatMap { (hung, healthy) =>
      fixture(List(hung, healthy)).use { (registry, _, _) =>
        IO.sleep(interval * 3) *> (registry.state(keyOf(topic)), hung.polls).tupled
      }
    }

    TestControl.executeEmbed(program).map { (topicState, hungPolls) =>
      assertEquals(topicState, CapabilityState.Available)
      assert(hungPolls > 0, "the hanging service should still have been polled")
    }
  }

  test("pollsDoNotOverlap") {
    // A service slower than the interval is polled once at a time. Queueing polls behind each other would
    // turn one slow service into a growing pile of calls that all time out together.
    //
    // The assertion is on concurrency, not on a count. A count cannot tell the two implementations apart:
    // the scheduled loop is a single sequential fiber, so it can only overlap with a poll started from
    // somewhere else -- which is exactly what `probeNow` does when a user presses "Retry now" while a slow
    // poll is already in flight, and exactly what the poller's semaphore exists to prevent.
    val program = stub(cluster, ServiceHealth.Slow(30.seconds)).flatMap { slow =>
      Concurrency.wrap[IO](slow).flatMap { watched =>
        fixture(List(watched)).use { (_, _, trigger) =>
          // Long enough for a scheduled poll to be in flight, then a user's retry on top of it.
          (IO.sleep(2.seconds) *> trigger.probe(cluster)).start *>
            IO.sleep(interval * 6) *> (watched.peak, watched.polls).tupled
        }
      }
    }

    TestControl.executeEmbed(program).map { (peak, polls) =>
      assertEquals(peak, 1, s"$peak calls to one service were in flight at once; polls overlapped")
      assert(polls > 1, s"the slow service was polled $polls times; the test proved nothing")
    }
  }

  test("pollsAreJittered") {
    // Eleven services polled on the same tick is a self-inflicted load spike with a period.
    val services = (1 to 8).map(n => ServiceId.unsafe(s"service-$n")).toList

    val program = services.traverse(stub(_)).flatMap { stubs =>
      fixture(stubs).use { _ =>
        // Part-way into the first interval, some services have been polled and some have not.
        IO.sleep(interval / 2) *> stubs.traverse(_.polls)
      }
    }

    TestControl.executeEmbed(program).map { counts =>
      assert(counts.distinct.size > 1, s"every service polled at the same instant: $counts")
    }
  }

  test("releasingTheResourceStopsEveryFiber") {
    val program = stub(cluster).flatMap { stub =>
      for {
        _ <- fixture(List(stub)).use(_ => IO.sleep(interval * 2))
        afterRelease <- stub.polls
        _ <- IO.sleep(interval * 5)
        later <- stub.polls
      } yield (afterRelease, later)
    }

    TestControl.executeEmbed(program).map { (afterRelease, later) =>
      assertEquals(later, afterRelease, "polls continued after the poller was released")
    }
  }

  test("pollFailuresAreRateLimitedInTheLog") {
    val program = stub(cluster, ServiceHealth.Down).flatMap { stub =>
      fixture(List(stub)).use { (_, logger, _) =>
        // Ten minutes of a dead service: sixty failed polls.
        IO.sleep(10.minutes) *> (logger.entries.map(_.count(_.level == "warn")), stub.polls).tupled
      }
    }

    TestControl.executeEmbed(program).map { (warnings, polls) =>
      assert(polls >= 50, s"expected around sixty polls, got $polls")
      // One line a minute, not one a poll. A log that repeats itself every ten seconds buries the lines
      // that would explain the incident.
      assert(warnings <= 12, s"$warnings warnings for $polls failed polls is not rate limited")
      assert(warnings > 0, "a dead service must be logged at least once")
    }
  }

  test("probeForcesAnImmediatePoll") {
    // The "Retry now" button: it polls, and it does not return until the state has been recomputed.
    val program = stub(cluster, ServiceHealth.Down).flatMap { stub =>
      fixture(List(stub)).use { (registry, _, _) =>
        for {
          _ <- IO.sleep(interval * 2)
          before <- stub.polls
          _ <- stub.health.set(ServiceHealth.Healthy)
          _ <- registry.probeNow(cluster)
          // Read immediately, with no sleep: if `probeNow` returned before the state was recomputed,
          // this would still say unavailable.
          state <- registry.state(keyOf(cluster))
          after <- stub.polls
        } yield (before, after, state)
      }
    }

    TestControl.executeEmbed(program).map { (before, after, state) =>
      assert(after > before, "probe did not poll")
      assertEquals(state, CapabilityState.Available)
    }
  }

  test("aServiceThatIsUpButNotReadyIsUnavailable") {
    // Readiness wins: a service that says it is not ready must not receive traffic, whatever else it
    // says about itself.
    val program = stub(cluster, ServiceHealth.NotReady("kafka")).flatMap { stub =>
      fixture(List(stub)).use { (registry, _, _) =>
        IO.sleep(interval * 2) *> registry.state(keyOf(cluster))
      }
    }

    TestControl.executeEmbed(program).map {
      case CapabilityState.Unavailable(_, message, _) =>
        assert(message.contains("kafka"), s"the failing check should be named: $message")
      case other => fail(s"expected unavailable, got $other")
    }
  }

  /** A `StubServiceClient` that also records how many of its calls were in flight at the same time.
    *
    * It wraps rather than replaces the stub, so the answers, the health `Ref` and the counts stay exactly the
    * ones every other test in this suite reasons about; the only thing added is the peak.
    */
  private object Concurrency {

    trait Watched[F[_]] extends StubServiceClient[F] {
      def peak: F[Int]
    }

    def wrap[F[_]: cats.effect.kernel.Async](
        underlying: StubServiceClient[F]
    ): F[Watched[F]] =
      cats.effect.kernel.Ref.of[F, (Int, Int)]((0, 0)).map { inFlight =>
        new Watched[F] {
          val service: ServiceId = underlying.service
          def health: cats.effect.kernel.Ref[F, ServiceHealth] = underlying.health
          def polls: F[Int] = underlying.polls
          def capabilityCalls: F[Int] = underlying.capabilityCalls
          def circuit(event: kui.http.upstream.CircuitEvent): F[Unit] = underlying.circuit(event)
          def circuitStates: fs2.Stream[F, kui.http.upstream.CircuitEvent] = underlying.circuitStates
          def peak: F[Int] = inFlight.get.map(_._2)

          def call[I, O](
              endpoint: sttp.tapir.Endpoint[
                kui.security.SignedPrincipal,
                I,
                kui.contracts.ErrorEnvelope,
                O,
                Any
              ],
              input: I
          )(ctx: kui.gateway.application.client.CallContext): F[Either[kui.kernel.error.KuiError, O]] =
            counted(underlying.call(endpoint, input)(ctx))

          def callPublic[I, O](
              endpoint: sttp.tapir.PublicEndpoint[I, kui.contracts.ErrorEnvelope, O, Any],
              input: I
          )(ctx: kui.gateway.application.client.CallContext): F[Either[kui.kernel.error.KuiError, O]] =
            counted(underlying.callPublic(endpoint, input)(ctx))

          def stream[I](
              endpoint: sttp.tapir.Endpoint[
                kui.security.SignedPrincipal,
                I,
                kui.contracts.ErrorEnvelope,
                fs2.Stream[F, Byte],
                sttp.capabilities.fs2.Fs2Streams[F]
              ],
              input: I
          )(ctx: kui.gateway.application.client.CallContext): fs2.Stream[F, kui.http.sse.SseEvent] =
            underlying.stream(endpoint, input)(ctx)

          private def counted[A](call: F[A]): F[A] =
            cats.effect.kernel.Resource
              .make(inFlight.update((live, top) => (live + 1, math.max(top, live + 1))))(_ =>
                inFlight.update((live, top) => (live - 1, top))
              )
              .use(_ => call)
        }
      }
  }

  // -----------------------------------------------------------------------------------------------
  // W10-A1. Three rules the `StubServiceClient` fixture cannot express, because it answers the
  // readiness probe and the capability probe from one `ServiceHealth`: a capability call that fails
  // while readiness succeeds, a readiness call that fails with an *application* error, and a probe that
  // throws rather than answering. All three were green under mutation across the whole module.
  // -----------------------------------------------------------------------------------------------

  /** A `ServiceClient` whose two health probes are scripted independently of each other. */
  private def scripted(
      id: ServiceId,
      readiness: IO[Either[kui.kernel.error.KuiError, kui.contracts.health.ReadinessReport]],
      capabilities: IO[Either[kui.kernel.error.KuiError, kui.contracts.capability.ServiceCapabilities]]
  ): kui.gateway.application.client.ServiceClient[IO] =
    new kui.gateway.application.client.ServiceClient[IO] {
      val service: ServiceId = id

      def circuitStates: fs2.Stream[IO, kui.http.upstream.CircuitEvent] = fs2.Stream.empty

      def call[I, O](
          endpoint: sttp.tapir.Endpoint[kui.security.SignedPrincipal, I, kui.contracts.ErrorEnvelope, O, Any],
          input: I
      )(ctx: kui.gateway.application.client.CallContext): IO[Either[kui.kernel.error.KuiError, O]] =
        IO.raiseError(new UnsupportedOperationException("this fixture only answers health probes"))

      def callPublic[I, O](
          endpoint: sttp.tapir.PublicEndpoint[I, kui.contracts.ErrorEnvelope, O, Any],
          input: I
      )(ctx: kui.gateway.application.client.CallContext): IO[Either[kui.kernel.error.KuiError, O]] =
        if endpoint.info.name.contains("health.ready") then readiness.map(_.map(_.asInstanceOf[O]))
        else capabilities.map(_.map(_.asInstanceOf[O]))

      def stream[I](
          endpoint: sttp.tapir.Endpoint[
            kui.security.SignedPrincipal,
            I,
            kui.contracts.ErrorEnvelope,
            fs2.Stream[IO, Byte],
            sttp.capabilities.fs2.Fs2Streams[IO]
          ],
          input: I
      )(ctx: kui.gateway.application.client.CallContext): fs2.Stream[IO, kui.http.sse.SseEvent] =
        fs2.Stream.raiseError[IO](new UnsupportedOperationException("this fixture does not stream"))
    }

  private def fixtureOf(
      clients: List[kui.gateway.application.client.ServiceClient[IO]]
  ): Resource[IO, (CapabilityRegistry[IO], FakeStructuredLogger[IO], Trigger[IO])] =
    for {
      logger <- Resource.eval(FakeStructuredLogger[IO])
      registry <- CapabilityRegistry.resource[IO](
        RegistryConfig.Default.copy(debounce = 1.millisecond),
        kui.observability.Telemetry.noop[IO],
        logger
      )
      signals <- Resource.eval(
        CapabilitySignals.make[IO](RegistryConfig.Default, registry, clients.map(_.service))
      )
      trigger <- ReadinessPoller.resource[IO](
        kui.gateway.application.client.ServiceClients.of(clients),
        signals,
        interval,
        logger
      )
      _ <- Resource.eval(registry.attachProbe(trigger.probe))
    } yield (registry, logger, trigger)

  private val ready: kui.contracts.health.ReadinessReport =
    kui.contracts.health.ReadinessReport(true, Nil, java.time.Instant.EPOCH)

  test("aCapabilityCallThatFailsLeavesTheLastKnownPayloadInPlace") {
    // W10-A1: `.orElse(inputs.serviceReport)` had no case, and dropping it left the whole module green.
    // Losing the payload turns a momentary blip into "this service can do nothing", which is a much
    // stronger claim than the evidence supports — and here it says the *opposite* of the truth: a service
    // whose only cluster is not configured goes from a greyed-out feature to a lit one, because the fact
    // that made it grey was thrown away while the service was still answering its readiness probe.
    val notConfigured = kui.contracts.capability.ServiceCapabilities(
      cluster,
      Map(
        kui.kernel.ClusterId.unsafe("prod-eu") ->
          kui.contracts.capability.ClusterCapability(configured = false, Nil, "available")
      )
    )

    val program = Ref.of[IO, Boolean](true).flatMap { answering =>
      val client = scripted(
        cluster,
        IO.pure(Right(ready)),
        answering.get.map {
          case true => Right(notConfigured)
          case false =>
            Left(kui.kernel.error.InfrastructureError.Unreachable(cluster.value, "connection reset"))
        }
      )

      fixtureOf(List(client)).use { (registry, _, _) =>
        for {
          _ <- IO.sleep(interval * 2)
          learned <- registry.state(keyOf(cluster))
          _ <- answering.set(false)
          _ <- IO.sleep(interval * 2)
          afterTheBlip <- registry.state(keyOf(cluster))
        } yield (learned, afterTheBlip)
      }
    }

    TestControl.executeEmbed(program).map { (learned, afterTheBlip) =>
      assertEquals(learned, CapabilityState.NotConfigured)
      assertEquals(
        afterTheBlip,
        CapabilityState.NotConfigured,
        "a capability call that failed re-lit a feature this deployment has not configured"
      )
    }
  }

  test("aServiceThatAnswersUnsupportedIsNotConfiguredRatherThanUnavailable") {
    // W10-A1: the `ErrorCode.Unsupported` arm of `reasonOf` had no case, and deleting it left the whole
    // module green — after a second, visible edit, because `-Werror` then refused the now-unused import.
    // The two sentences are not interchangeable: `UpstreamUnavailable` is an outage an operator goes
    // looking for, and `NotConfigured` is a feature this deployment did not ask for. ADR-032 draws the
    // second with no error styling at all, precisely so that nobody is sent hunting.
    val program = fixtureOf(
      List(
        scripted(
          cluster,
          IO.pure(
            Left(
              kui.kernel.error.ApplicationError
                .Unsupported("this build has no cluster service")
            )
          ),
          IO.pure(Left(kui.kernel.error.ApplicationError.Unsupported("no capabilities either")))
        )
      )
    ).use { (registry, _, _) =>
      IO.sleep(interval * 2) *> registry.state(keyOf(cluster))
    }

    TestControl.executeEmbed(program).map {
      case CapabilityState.Unavailable(reason, _, _) =>
        assertEquals(reason, kui.contracts.capability.ReasonCode.NotConfigured)
      case other => fail(s"expected an unavailable-because-not-configured verdict, got $other")
    }
  }

  test("aProbeThatThrowsIsReportedRatherThanKillingThePollerForGood") {
    // W10-A1: the `.handleError` on `timed` had no case, and removing it left the whole module green.
    // Every fixture in this suite returns a `Left` for a failure, which is what a `ServiceClient` promises
    // — but the promise is the thing being defended, and the cost of it being broken once is not one bad
    // poll: the fiber dies, nothing restarts it, and that service is reported as whatever it was last
    // seen as for the rest of the process's life, with nothing anywhere saying the poll stopped.
    val program = fixtureOf(
      List(
        scripted(
          cluster,
          IO.raiseError(new IllegalStateException("a client that broke its own promise")),
          IO.raiseError(new IllegalStateException("a client that broke its own promise"))
        )
      )
    ).use { (registry, _, _) =>
      IO.sleep(interval * 2) *> registry.state(keyOf(cluster))
    }

    TestControl.executeEmbed(program).map {
      case CapabilityState.Unavailable(_, message, _) =>
        assert(clue(message).contains(cluster.value))
      case other =>
        fail(s"a probe that threw left the service reported as $other rather than as unreachable")
    }
  }
}
