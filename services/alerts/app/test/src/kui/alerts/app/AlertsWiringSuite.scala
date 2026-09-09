package kui.alerts.app

import munit.FunSuite

import kui.alerts.application.AlertsService
import kui.alerts.domain.AlertLimits
import kui.config.{AlertThresholds, AlertsConfig}

/** The one place `kui.config`'s five numbers become the domain's five numbers.
  *
  * The domain may not see `libs/config` (rule A1), so the two spellings exist and this mapping is where they
  * can disagree. Field by field rather than "the mapping compiles": four of the five have the same type, so a
  * transposition of `diskUsedWarningPercent` and `diskUsedCriticalPercent` would compile, would pass any test
  * that only checked the shape, and would open a critical event at 80% and a warning at 90%.
  */
final class AlertsWiringSuite extends FunSuite {

  test("every threshold reaches the rules as the operator wrote it") {
    val configured = AlertThresholds(
      offlinePartitions = 3,
      underReplicatedPartitions = 7,
      rebalanceDuration = scala.concurrent.duration.DurationInt(11).minutes,
      diskUsedWarningPercent = 61,
      diskUsedCriticalPercent = 83
    )

    assertEquals(
      AlertsWiring.limitsOf(configured),
      AlertLimits(
        offlinePartitions = 3,
        underReplicatedPartitions = 7,
        rebalanceDuration = scala.concurrent.duration.DurationInt(11).minutes,
        diskUsedWarningPercent = 61,
        diskUsedCriticalPercent = 83
      )
    )
  }

  test("the shipped defaults are the numbers the domain suites are written against") {
    // `AlertFixtures` types these five as literals so that "five minutes" is a fact this repository
    // asserts. This is the line that holds those literals to `libs/config`'s defaults, so changing a
    // default in one place and not the other fails here rather than silently changing what the rules do.
    val defaults = AlertsWiring.limitsOf(AlertThresholds.Default)

    assertEquals(defaults.offlinePartitions, 1)
    assertEquals(defaults.underReplicatedPartitions, 1)
    assertEquals(defaults.rebalanceDuration, scala.concurrent.duration.DurationInt(5).minutes)
    assertEquals(defaults.diskUsedWarningPercent, 80)
    assertEquals(defaults.diskUsedCriticalPercent, 90)
  }

  test("nothing in the mapping widens or clamps what the loader already refused") {
    // The loader refuses a critical bound at or below the warning one, and refuses every value outside its
    // range. A second opinion here would make a configuration the loader refuses behave as though it had
    // been accepted — so the mapping passes even a pair the loader would never have produced, and the
    // refusal stays in one place.
    val inverted = AlertThresholds.Default.copy(diskUsedWarningPercent = 95, diskUsedCriticalPercent = 10)

    assertEquals(AlertsWiring.limitsOf(inverted).diskUsedWarningPercent, 95)
    assertEquals(AlertsWiring.limitsOf(inverted).diskUsedCriticalPercent, 10)
  }

  test("the retention and the cadence the store and the loop are built with are the operator's") {
    // Read here rather than through the wiring, because building the wiring opens a meter and a pool; what
    // is being pinned is that these two keys exist with these defaults and that nothing else invents them.
    assertEquals(AlertsConfig.Default.retention, scala.concurrent.duration.DurationInt(7).days)
    assertEquals(AlertsConfig.Default.evaluationInterval, scala.concurrent.duration.DurationInt(60).seconds)
  }

  test("the service id is the one the gateway is configured with and the audit line carries") {
    assertEquals(AlertsService.Id.value, "alerts")
    assertEquals(AlertsWiring.Instrumentation, "kui.alerts")
  }
}
