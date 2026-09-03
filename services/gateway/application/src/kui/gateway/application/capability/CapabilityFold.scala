package kui.gateway.application.capability

import java.time.Instant

import scala.concurrent.duration.FiniteDuration

import kui.contracts.capability.{CapabilityState, DegradedReason, ReasonCode}
import kui.http.upstream.CircuitState

/** The one function that decides whether a feature of KUI is usable.
  *
  * Every dimmed sidebar entry and every fallback panel in the product is downstream of this. It is pure,
  * total, and takes `now` as a parameter rather than reading a clock, so its whole behaviour can be written
  * as a table — and `CapabilityFoldSuite` is that table. ADR-039 is the prose version of this file; when the
  * two disagree, one of them is a bug.
  *
  * ==Precedence==
  *
  * {{{
  * NotConfigured  >  Unavailable  >  Degraded  >  Available
  * }}}
  *
  * `NotConfigured` sits on top because it is not a health verdict at all. A deployment that attached no
  * schema registry does not have a *broken* schema registry, and showing one would send an operator hunting
  * for an outage that does not exist. Below it the worse health state wins, because a feature that is partly
  * broken must never be advertised as fine.
  *
  * ==Stickiness==
  *
  * `since` answers "how long has this been broken?", which is the first question anyone asks. It is stamped
  * on the transition *into* `Unavailable` and kept for as long as the state stays `Unavailable`, even when
  * the reason changes underneath it — a refused connection that becomes an open circuit is the same outage,
  * and restarting the clock would quietly answer a different and much less useful question.
  */
object CapabilityFold {

  /** How much slower than its own p95 the browser is asked to poll.
    *
    * The suggestion has to be at least the p95 or the UI would be asking again before the service could
    * possibly have answered the previous request, which turns a slow service into an overloaded one. Doubling
    * leaves room for the half of the distribution that is slower than the p95.
    */
  private val PollIntervalFactor: Int = 2

  /** @param threshold
    *   the readiness-call p95 above which a service counts as slow. It is a parameter with a default rather
    *   than a field of `CapabilityInputs` because it is a knob the deployment turns, not something observed
    *   about the service; keeping it out of the inputs is what lets the fold's table read as a table of
    *   facts.
    */
  def fold(
      previous: Option[CapabilityState],
      inputs: CapabilityInputs,
      now: Instant,
      threshold: FiniteDuration = RegistryConfig.Default.degradedP95Threshold
  ): CapabilityState =
    notConfigured(inputs)
      .orElse(unavailable(previous, inputs, now))
      .orElse(degraded(inputs, threshold))
      .getOrElse(CapabilityState.Available)

  /** A deployment decision, known without asking anyone, so it outranks every health signal. */
  private def notConfigured(inputs: CapabilityInputs): Option[CapabilityState] =
    Option.when(inputs.serviceReport.exists(!_.configured))(CapabilityState.NotConfigured)

  /** Readiness first, then the circuit.
    *
    * A service that says it is not ready must not receive traffic whatever its breaker thinks, and its own
    * message names the check that failed, which is more use to an operator than "the circuit is open".
    */
  private def unavailable(
      previous: Option[CapabilityState],
      inputs: CapabilityInputs,
      now: Instant
  ): Option[CapabilityState] =
    readinessFailure(inputs)
      .orElse(circuitFailure(inputs, now))
      .orElse(reportedUnavailable(inputs, now))
      .map((reason, message, observed) =>
        CapabilityState.Unavailable(reason, message, stickySince(previous, observed))
      )

  private def readinessFailure(inputs: CapabilityInputs): Option[(ReasonCode, String, Instant)] =
    inputs.readiness.collect { case ReadinessSignal.NotReady(reason, message, since) =>
      (reason, message, since)
    }

  private def circuitFailure(
      inputs: CapabilityInputs,
      now: Instant
  ): Option[(ReasonCode, String, Instant)] =
    Option.when(inputs.circuit.contains(CircuitState.Open))(
      (
        ReasonCode.CircuitOpen,
        "calls to this service are suspended while it recovers",
        now
      )
    )

  /** A service that answered, and said of itself that it is unavailable. */
  private def reportedUnavailable(
      inputs: CapabilityInputs,
      now: Instant
  ): Option[(ReasonCode, String, Instant)] =
    inputs.serviceReport.filter(_.status == Status.Unavailable).map { report =>
      (ReasonCode.UpstreamUnavailable, s"the service reports itself ${report.status}", now)
    }

  /** `since` is set once, on the way in, and preserved while the state stays `Unavailable`. */
  private def stickySince(previous: Option[CapabilityState], observed: Instant): Instant =
    previous match {
      case Some(CapabilityState.Unavailable(_, _, since)) => since
      case _ => observed
    }

  /** The three ways a service can be working but not working well. */
  private def degraded(inputs: CapabilityInputs, threshold: FiniteDuration): Option[CapabilityState] =
    starting(inputs)
      .orElse(reportedDegraded(inputs))
      .orElse(slow(inputs, threshold))
      .map(CapabilityState.Degraded.apply)

  /** Never polled. Degraded, not unavailable: the gateway has no evidence of a problem, only an absence of
    * evidence, and ADR-032 renders the two very differently.
    */
  private def starting(inputs: CapabilityInputs): Option[DegradedReason] =
    Option.when(inputs.readiness.forall(_ == ReadinessSignal.Unknown))(
      DegradedReason(ReasonCode.Starting, "waiting for the first readiness check", None, None)
    )

  /** The service's own verdict on itself. Anything it says that is not one of the three known statuses is
    * carried through as `Unknown` rather than ignored: a service reporting a status this build of the gateway
    * has never heard of is a real signal, and dropping it would hide a version mismatch.
    */
  private def reportedDegraded(inputs: CapabilityInputs): Option[DegradedReason] =
    inputs.serviceReport.filterNot(_.status == Status.Available).map { report =>
      DegradedReason(ReasonCode.Unknown, s"the service reports itself ${report.status}", None, None)
    }

  private def slow(inputs: CapabilityInputs, threshold: FiniteDuration): Option[DegradedReason] =
    inputs.p95.filter(_ > threshold).map { p95 =>
      DegradedReason(
        ReasonCode.UpstreamTimeout,
        s"this service is answering slowly (p95 ${p95.toMillis}ms)",
        suggestedPollIntervalMs = Some(p95.toMillis * PollIntervalFactor),
        p95Ms = Some(p95.toMillis)
      )
    }

  /** The statuses a service may report about itself, matching `ClusterCapability.status` on the wire. */
  object Status {
    val Available: String = "available"
    val Degraded: String = "degraded"
    val Unavailable: String = "unavailable"
  }
}
