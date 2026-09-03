package kui.gateway.application.capability

import java.time.Instant

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import kui.contracts.capability.{ClusterCapability, ReasonCode}
import kui.http.upstream.CircuitState

/** What the readiness poller last learned about a service.
  *
  * `Unknown` is a state and not a missing value on purpose. "We have not asked yet" and "we asked and it said
  * no" are different facts, and collapsing them is the bug ADR-039 §5 exists to prevent: on the first page
  * load nothing has been polled yet, and a gateway that treated that as failure would show every feature in
  * the product as broken for the first ten seconds of its life.
  */
enum ReadinessSignal {
  case Ready
  case NotReady(reason: ReasonCode, message: String, since: Instant)
  case Unknown
}

object ReadinessSignal {
  given CanEqual[ReadinessSignal, ReadinessSignal] = CanEqual.derived
}

/** The four raw inputs the capability fold decides from (ADR-039 §1).
  *
  * They are kept apart, and passed together, so that the fold is a pure function of all of them rather than a
  * sequence of updates whose result depends on the order they arrived in. That is what makes the fold's table
  * test readable as the specification of KU-001: every row is a complete set of inputs and exactly one
  * expected state.
  *
  * Every field is optional because the gateway genuinely may not know. A service that has never been polled
  * has no readiness signal; a service that has never been called has no circuit state and no latency sample.
  */
final case class CapabilityInputs(
    readiness: Option[ReadinessSignal],
    circuit: Option[CircuitState],
    serviceReport: Option[ClusterCapability],
    p95: Option[FiniteDuration]
)

object CapabilityInputs {

  /** Nothing is known yet. The state of every service the instant the gateway starts. */
  val unknown: CapabilityInputs = CapabilityInputs(Some(ReadinessSignal.Unknown), None, None, None)

  val empty: CapabilityInputs = CapabilityInputs(None, None, None, None)

  given CanEqual[CapabilityInputs, CapabilityInputs] = CanEqual.derived
}

/** How the registry behaves, as opposed to what it decides.
  *
  * @param debounce
  *   how long an `Available -> Unavailable` transition must persist before it is published. Recovery is never
  *   debounced; the asymmetry is the decision of ADR-039 §4 and is not configurable, because a user waiting
  *   for a service to come back should see it come back at once.
  * @param subscriberQueueSize
  *   how many changes one subscriber may fall behind by before its oldest are dropped. A browser that has
  *   stopped reading must not be able to stall the registry for every other browser.
  * @param degradedP95Threshold
  *   the readiness-call latency above which a service is reported as degraded rather than available.
  */
final case class RegistryConfig(
    debounce: FiniteDuration,
    subscriberQueueSize: Int,
    degradedP95Threshold: FiniteDuration
)

object RegistryConfig {
  val Default: RegistryConfig =
    RegistryConfig(debounce = 10.seconds, subscriberQueueSize = 64, degradedP95Threshold = 2.seconds)

  given CanEqual[RegistryConfig, RegistryConfig] = CanEqual.derived
}
