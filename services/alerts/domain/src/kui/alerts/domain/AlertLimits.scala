package kui.alerts.domain

import scala.concurrent.duration.FiniteDuration

/** The five numbers the rules compare against, as the domain sees them.
  *
  * ==Why this is not `kui.config.AlertThresholds`==
  *
  * It is the same five fields. `libs/config` is not `libs/kernel` or cats-core, so rule A1 forbids the domain
  * from seeing it, and the copy is what that rule buys: a rule can be evaluated by a suite that names five
  * numbers, with no configuration file, no loader and no YAML anywhere near it. `AlertsWiring` maps between
  * the two, which is the one place the two spellings can disagree and therefore the one place a test can pin
  * them together.
  *
  * ==Nothing here is validated, and that is deliberate==
  *
  * `AlertThresholds`' loader already refuses every value out of range, and holds `diskUsedCriticalPercent`
  * above `diskUsedWarningPercent` at load. Re-checking any of it here would mean a configuration the loader
  * refuses behaved as though it had been accepted — the same argument `MetricsBuffer` makes for passing
  * `retention` through exactly as written. A second opinion about a bound is how two bounds come to disagree.
  */
final case class AlertLimits(
    offlinePartitions: Int,
    underReplicatedPartitions: Int,
    rebalanceDuration: FiniteDuration,
    diskUsedWarningPercent: Int,
    diskUsedCriticalPercent: Int
)

object AlertLimits {
  given CanEqual[AlertLimits, AlertLimits] = CanEqual.derived
}
