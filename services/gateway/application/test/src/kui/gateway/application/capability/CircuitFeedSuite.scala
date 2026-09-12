package kui.gateway.application.capability

import java.time.Instant

import scala.concurrent.duration.DurationInt

import cats.effect.IO
import cats.effect.testkit.TestControl
import munit.CatsEffectSuite

import kui.contracts.capability.{CapabilityKey, CapabilityState, ReasonCode}
import kui.gateway.application.client.{ServiceClients, StubServiceClient}
import kui.http.upstream.{CircuitEvent, CircuitState}
import kui.kernel.ServiceId
import kui.testkit.fakes.FakeStructuredLogger

/** That an open circuit reaches the user.
  *
  * This is the gap the feed exists to close. When a breaker opens, the gateway stops calling that service —
  * including the readiness poll — so without this the gateway would know a service was unusable and the
  * sidebar would still say it was fine until a poll eventually timed out.
  */
final class CircuitFeedSuite extends CatsEffectSuite {

  private val cluster = ServiceId.unsafe("cluster")
  private val key = CapabilityKey(cluster, None)

  private def event(state: CircuitState) =
    CircuitEvent(cluster.value, state, Instant.EPOCH, Some("connection refused"))

  private def feeding[A](events: List[CircuitEvent])(read: CapabilityRegistry[IO] => IO[A]): IO[A] = {
    val program = StubServiceClient[IO](cluster).flatMap { stub =>
      (for {
        logger <- cats.effect.kernel.Resource.eval(FakeStructuredLogger[IO])
        registry <- CapabilityRegistry.resource[IO](
          RegistryConfig.Default.copy(debounce = 1.millisecond),
          kui.observability.Telemetry.noop[IO],
          logger
        )
        signals <- cats.effect.kernel.Resource.eval(
          CapabilitySignals.make[IO](RegistryConfig.Default, registry, List(cluster))
        )
        _ <- CircuitFeed.resource[IO](ServiceClients.of(List(stub)), signals)
      } yield registry).use { registry =>
        events.foldLeft(IO.unit)((acc, one) => acc *> stub.circuit(one)) *>
          IO.sleep(1.second) *>
          read(registry)
      }
    }
    TestControl.executeEmbed(program)
  }

  private def run(states: List[CircuitState]) =
    feeding(states.map(event))(_.state(key))

  test("anOpenCircuitIsReportedAsUnavailable") {
    run(List(CircuitState.Open)).map {
      case CapabilityState.Unavailable(reason, _, _) => assertEquals(reason, ReasonCode.CircuitOpen)
      case other => fail(s"expected unavailable, got $other")
    }
  }

  test("aCircuitEventForANameThatIsNotAServiceIdIsDropped") {
    // W13-A1: `report`'s comment says "an event for a name that is not a service id is dropped rather than
    // guessed at" and the code wrapped the name with `ServiceId.unsafe`, which validates nothing — so an
    // event for any string reached `CapabilitySignals.update`, whose `Map.updated` creates the key it is
    // given. The breaker's name would then appear in the registry as a capability of its own, unavailable,
    // and reach the browser beside the real ones. Nothing here drove a name that was not already a
    // configured service, so the sentence was true of nothing. `ServiceId.from` is now what decides.
    val stray = CircuitEvent("Not A Service", CircuitState.Open, Instant.EPOCH, Some("connection refused"))

    feeding(List(stray))(_.snapshot.map(_.keySet)).map(keys => assertEquals(keys, Set(key)))
  }

  test("aCircuitThatClosesAgainLeavesTheServiceStarting") {
    // Closed means "we are no longer refusing calls", not "we have checked that it works". The service
    // goes back to whatever the readiness poll last said, which here is nothing yet.
    run(List(CircuitState.Open, CircuitState.HalfOpen, CircuitState.Closed)).map {
      case CapabilityState.Degraded(reason) => assertEquals(reason.code, ReasonCode.Starting)
      case other => fail(s"expected degraded-starting, got $other")
    }
  }
}
