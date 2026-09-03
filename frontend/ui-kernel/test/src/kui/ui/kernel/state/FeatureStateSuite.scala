package kui.ui.kernel.state

import java.time.Instant

import munit.FunSuite

import kui.contracts.capability.{CapabilityState, DegradedReason, ReasonCode}

/** ADR-032's derivation table, in executable form.
  *
  * Every row of the table in the ADR appears here, and every one of the four predicates is checked for every
  * one of the five states. That exhaustiveness is the point: `derive` decides what a user sees for every
  * feature on every screen, and a `match` that quietly answers `false` for a state somebody adds later would
  * hide a feature, or advertise a broken one as fine, with nothing in the build going red.
  */
class FeatureStateSuite extends FunSuite {

  private val downSince = Instant.parse("2026-09-03T10:11:12Z")

  private val degraded =
    DegradedReason(ReasonCode.UpstreamTimeout, "The upstream is slow.", Some(5_000L), Some(1_200L))

  private val unavailable =
    CapabilityState.Unavailable(ReasonCode.UpstreamUnavailable, "Connection refused.", downSince)

  // ---- the table -------------------------------------------------------------------------------

  test("anUnknownCapabilityIsDegradedStartingAndNeverUnavailable") {
    // ADR-032 amendment 2. Between the gateway starting and its first readiness poll it has no
    // information; reporting `Unavailable` would be a claim it cannot support, and every operator who
    // restarted the gateway would watch the whole sidebar go red for one polling interval.
    FeatureState.derive(None, permitted = true) match {
      case FeatureState.Degraded(reason) => assertEquals(reason.code, ReasonCode.Starting)
      case other => fail(s"expected Degraded(Starting), got $other")
    }
  }

  test("availableIsReady") {
    assertEquals(FeatureState.derive(Some(CapabilityState.Available), permitted = true), FeatureState.Ready)
  }

  test("degradedKeepsItsStructuredReasonSoAScreenCanSlowItsPolling") {
    assertEquals(
      FeatureState.derive(Some(CapabilityState.Degraded(degraded)), permitted = true),
      FeatureState.Degraded(degraded)
    )
  }

  test("unavailableKeepsItsReasonMessageAndSince") {
    // `since` is on screen because "down for two minutes" and "down since Tuesday" call for very
    // different reactions (ADR-032).
    assertEquals(
      FeatureState.derive(Some(unavailable), permitted = true),
      FeatureState.Unavailable(ReasonCode.UpstreamUnavailable, "Connection refused.", Some(downSince))
    )
  }

  test("notConfiguredIsNotAFailure") {
    assertEquals(
      FeatureState.derive(Some(CapabilityState.NotConfigured), permitted = true),
      FeatureState.NotConfigured
    )
  }

  test("forbiddenWinsOverEveryHealthState") {
    // ADR-032 amendment 1. A user who may not see the schema registry must not learn from the sidebar
    // whether it is up, how long it has been down, or what its upstream error said.
    val capabilities = List(
      None,
      Some(CapabilityState.Available),
      Some(CapabilityState.Degraded(degraded)),
      Some(unavailable),
      Some(CapabilityState.NotConfigured)
    )
    capabilities.foreach { capability =>
      assertEquals(
        FeatureState.derive(capability, permitted = false),
        FeatureState.Forbidden,
        s"$capability with no permission must be Forbidden and nothing else"
      )
    }
  }

  // ---- the predicates --------------------------------------------------------------------------

  private val everyState: List[FeatureState] = List(
    FeatureState.Ready,
    FeatureState.Degraded(degraded),
    FeatureState.Unavailable(ReasonCode.UpstreamUnavailable, "Connection refused.", Some(downSince)),
    FeatureState.Forbidden,
    FeatureState.NotConfigured
  )

  test("isNavigableIsTrueForTheThreeStatesThatHaveAPageToShow") {
    import FeatureState.isNavigable
    // `Unavailable` is navigable on purpose: the page it leads to is the feature's fallback panel,
    // which is the only place the reason, the `since` and the retry exist.
    assertEquals(everyState.map(_.isNavigable), List(true, true, true, false, false))
  }

  test("isHiddenHidesNotConfiguredAlwaysAndForbiddenOnlyWhenAskedTo") {
    import FeatureState.isHidden
    assertEquals(everyState.map(_.isHidden()), List(false, false, false, false, true))
    // The `kui.ui.hideForbidden` switch, for deployments that consider a feature's existence sensitive.
    assertEquals(everyState.map(_.isHidden(hideForbidden = true)), List(false, false, false, true, true))
  }

  test("isDimmedIsTrueOnlyForUnavailable") {
    import FeatureState.isDimmed
    assertEquals(everyState.map(_.isDimmed), List(false, false, true, false, false))
  }

  test("unavailableReasonMapsTheWireDtoIntoTheKernelsOwnRecord") {
    import FeatureState.unavailableReason
    // This is the whole translation layer between a service's wire shape and the bottom of the
    // frontend: `KuiFeature.unavailableView` takes the kernel's `UnavailableReason` and never the DTO,
    // so that a new field on the DTO changes nothing below this line.
    val reason = FeatureState
      .Unavailable(ReasonCode.CircuitOpen, "Too many failures.", Some(downSince))
      .unavailableReason

    assertEquals(reason.map(_.code), Some("CIRCUIT_OPEN"))
    assertEquals(reason.map(_.message), Some("Too many failures."))
    assertEquals(reason.flatMap(_.since), Some(downSince.toString))
  }

  test("everyStateThatIsNotUnavailableHasNoReasonToShow") {
    import FeatureState.unavailableReason
    val others = everyState.filterNot(_ == everyState(2))
    others.foreach(state => assertEquals(state.unavailableReason, None, s"$state"))
  }

  test("anUnavailableWithNoRecordedStartStillRenders") {
    import FeatureState.unavailableReason
    // The gateway always sends `since`, but a client built against a newer contract must not depend
    // on a field to be able to draw a panel that says what broke.
    val reason = FeatureState.Unavailable(ReasonCode.Unknown, "Something went wrong.", None).unavailableReason
    assertEquals(reason.map(_.code), Some("UNKNOWN"))
    assertEquals(reason.flatMap(_.since), None)
  }
}
