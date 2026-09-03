package kui.gateway.application.capability

import java.time.Instant
import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}

import org.scalacheck.{Arbitrary, Gen, Prop}

import kui.contracts.capability.{CapabilityState, ClusterCapability, DegradedReason, ReasonCode}
import kui.http.upstream.CircuitState
import kui.testkit.KuiSuite

/** The executable specification of KU-001: "a broken part of KUI never breaks the rest, and the user is
  * told which part and why".
  *
  * Read this suite against ADR-039 line by line. Every row of the precedence table there is a test here,
  * and a change to the product's behaviour has to appear in both. It is deliberately a table over a pure
  * function rather than a set of scenarios over a running registry: scenarios prove that a path works,
  * tables prove that every combination was considered.
  */
final class CapabilityFoldSuite extends KuiSuite {

  private val now: Instant = Instant.parse("2026-09-03T12:00:00Z")
  private val earlier: Instant = Instant.parse("2026-09-03T11:00:00Z")

  private def report(configured: Boolean, status: String): ClusterCapability =
    ClusterCapability(configured, Nil, status)

  private val configured: ClusterCapability = report(configured = true, "available")

  private def inputs(
      readiness: Option[ReadinessSignal] = Some(ReadinessSignal.Ready),
      circuit: Option[CircuitState] = Some(CircuitState.Closed),
      serviceReport: Option[ClusterCapability] = Some(configured),
      p95: Option[FiniteDuration] = None
  ): CapabilityInputs = CapabilityInputs(readiness, circuit, serviceReport, p95)

  private val notReady: ReadinessSignal =
    ReadinessSignal.NotReady(ReasonCode.UpstreamUnavailable, "connection refused", earlier)

  test("precedenceTable") {
    val rows: List[(String, CapabilityInputs, CapabilityState => Boolean)] = List(
      (
        "everything clear is available",
        inputs(),
        _ == CapabilityState.Available
      ),
      (
        "a readiness failure is unavailable, stamped with the first failure",
        inputs(readiness = Some(notReady)),
        _ == CapabilityState.Unavailable(ReasonCode.UpstreamUnavailable, "connection refused", earlier)
      ),
      (
        "an open circuit is unavailable",
        inputs(circuit = Some(CircuitState.Open)),
        {
          case CapabilityState.Unavailable(ReasonCode.CircuitOpen, _, _) => true
          case _ => false
        }
      ),
      (
        "a service that reports itself unconfigured is not configured, even while it is also down",
        inputs(readiness = Some(notReady), serviceReport = Some(report(configured = false, "unavailable"))),
        _ == CapabilityState.NotConfigured
      ),
      (
        "not configured outranks an open circuit",
        inputs(circuit = Some(CircuitState.Open), serviceReport = Some(report(configured = false, "available"))),
        _ == CapabilityState.NotConfigured
      ),
      (
        "a service that reports itself degraded is degraded",
        inputs(serviceReport = Some(report(configured = true, "degraded"))),
        {
          case CapabilityState.Degraded(_) => true
          case _ => false
        }
      ),
      (
        "a readiness failure outranks the service's own degraded report",
        inputs(readiness = Some(notReady), serviceReport = Some(report(configured = true, "degraded"))),
        {
          case CapabilityState.Unavailable(_, _, _) => true
          case _ => false
        }
      ),
      (
        "a slow but healthy service is degraded, not unavailable",
        inputs(p95 = Some(5.seconds)),
        {
          case CapabilityState.Degraded(reason) => reason.code == ReasonCode.UpstreamTimeout
          case _ => false
        }
      ),
      (
        "a p95 under the threshold is not degraded",
        inputs(p95 = Some(100.millis)),
        _ == CapabilityState.Available
      ),
      (
        "never polled is degraded-starting, whatever else is known",
        inputs(readiness = Some(ReadinessSignal.Unknown)),
        {
          case CapabilityState.Degraded(reason) => reason.code == ReasonCode.Starting
          case _ => false
        }
      )
    )

    rows.foreach { (name, given_, expected) =>
      val folded = CapabilityFold.fold(None, given_, now)
      assert(expected(folded), s"$name: got $folded")
    }
  }

  test("sinceIsStickyWhileUnavailable") {
    // The outage started an hour ago and is now reported for a different reason. "How long has this been
    // broken?" must still answer an hour, not zero.
    val started = CapabilityState.Unavailable(ReasonCode.UpstreamUnavailable, "refused", earlier)
    val folded = CapabilityFold.fold(Some(started), inputs(circuit = Some(CircuitState.Open)), now)

    folded match {
      case CapabilityState.Unavailable(reason, _, since) =>
        assertEquals(reason, ReasonCode.CircuitOpen)
        assertEquals(since, earlier)
      case other => fail(s"expected an outage, got $other")
    }
  }

  test("sinceIsClearedOnRecovery") {
    val started = CapabilityState.Unavailable(ReasonCode.UpstreamUnavailable, "refused", earlier)
    assertEquals(CapabilityFold.fold(Some(started), inputs(), now), CapabilityState.Available)
  }

  test("unknownReadinessIsDegradedStartingNotUnavailable") {
    // The first-page-load bug this rule exists to prevent: for the first ten seconds of the gateway's
    // life nothing has been polled, and showing every feature as broken would train operators to ignore
    // the one signal that is supposed to mean something.
    val folded = CapabilityFold.fold(None, CapabilityInputs.unknown, now)
    assertEquals(folded, CapabilityState.Degraded(DegradedReason(ReasonCode.Starting, "waiting for the first readiness check", None, None)))
  }

  test("aServiceWithNoInputsAtAllIsStartingRatherThanAvailable") {
    // Absence of evidence is not evidence of health.
    assert(CapabilityFold.fold(None, CapabilityInputs.empty, now) match {
      case CapabilityState.Degraded(reason) => reason.code == ReasonCode.Starting
      case _ => false
    })
  }

  test("p95AboveThresholdProducesDegradedWithASuggestedPollInterval") {
    CapabilityFold.fold(None, inputs(p95 = Some(3.seconds)), now) match {
      case CapabilityState.Degraded(reason) =>
        assertEquals(reason.p95Ms, Some(3000L))
        assert(
          reason.suggestedPollIntervalMs.exists(_ >= 3000L),
          s"the UI must not be asked to poll faster than the service can answer: ${reason.suggestedPollIntervalMs}"
        )
      case other => fail(s"expected degraded, got $other")
    }
  }

  test("aBusinessErrorHasNoWayToReachTheFold") {
    // ADR-039 §6, asserted structurally rather than behaviourally: `CapabilityInputs` has four fields and
    // none of them can carry a proxied 404. A user typing a topic name that does not exist therefore
    // cannot dim a feature for everyone else, because there is nowhere to put that fact.
    assertEquals(
      CapabilityInputs.empty.productElementNames.toList,
      List("readiness", "circuit", "serviceReport", "p95")
    )
  }

  private given Arbitrary[CapabilityInputs] = Arbitrary(
    for {
      readiness <- Gen.option(
        Gen.oneOf(
          Gen.const(ReadinessSignal.Ready),
          Gen.const(ReadinessSignal.Unknown),
          Gen.const(notReady)
        ).flatMap(identity)
      )
      circuit <- Gen.option(Gen.oneOf(CircuitState.values.toList))
      status <- Gen.oneOf("available", "degraded", "unavailable", "something-from-a-newer-service")
      configured <- Gen.oneOf(true, false)
      serviceReport <- Gen.option(Gen.const(report(configured, status)))
      p95 <- Gen.option(Gen.chooseNum(0L, 10000L).map(_.millis))
    } yield CapabilityInputs(readiness, circuit, serviceReport, p95)
  )

  property("foldIsPure") {
    Prop.forAll { (given_ : CapabilityInputs) =>
      CapabilityFold.fold(None, given_, now) == CapabilityFold.fold(None, given_, now)
    }
  }

  property("foldIsTotal") {
    // The registry must never fail. Every combination of inputs, including ones no real service could
    // produce, yields a state rather than an exception.
    Prop.forAll { (given_ : CapabilityInputs) =>
      CapabilityFold.fold(None, given_, now).status.nonEmpty
    }
  }
}
