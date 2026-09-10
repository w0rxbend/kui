package kui.alerts.api

import cats.effect.kernel.Sync
import cats.syntax.all.*

import kui.alerts.application.ClusterProfileSource
import kui.alerts.contract.AlertsEndpoints
import kui.contracts.capability.{CapabilityState, ClusterCapability}
import kui.kernel.ClusterId

/** What the alerts service can currently do, per cluster, as the gateway reads it.
  *
  * ==Every configured cluster is `available`, and that is a decision rather than an oversight==
  *
  * The metrics service has three rows because a metrics source is optional: a deployment can genuinely have
  * nothing to measure, and `not_configured` is the honest answer for it. Alerting has no such switch —
  * `kui.alerts` has no `enabled` key and `AlertsConfig`'s own scaladoc says why: every rule reads a fact this
  * product already measures, so alerting is on for every deployment and the only question is where the lines
  * are drawn. A cluster this service knows about can therefore always answer the feed, even if the answer is
  * an empty list beside four rules that say what they could not read.
  *
  * So there is no `not_configured` row here and no `degraded` one either. `degraded` would have to mean "some
  * rules are failing", and that is a per-rule fact the feed's own sections already carry per request — a
  * second, slower opinion about it in the capability document would be a red badge in the sidebar that an
  * operator cannot clear and learns to ignore, which is exactly what ADR-039 §6 warns about. What the
  * capability document answers is the question it is for: is this feature here, on this cluster.
  *
  * ==What a service may say about itself==
  *
  * `available`, `degraded` or `not_configured`, and never `unavailable`: a service answering this request is
  * reachable by definition, and `unavailable` is the *gateway's* verdict when it gets no answer at all
  * (ADR-039 §6).
  */
trait AlertsCapabilities[F[_]] {
  def report: F[Map[ClusterId, ClusterCapability]]
}

object AlertsCapabilities {

  def make[F[_]: Sync](profiles: ClusterProfileSource[F]): AlertsCapabilities[F] =
    new AlertsCapabilities[F] {

      def report: F[Map[ClusterId, ClusterCapability]] =
        profiles.all.map(
          _.map(profile =>
            profile.cluster -> ClusterCapability(
              configured = true,
              features = featuresFor(profile.readOnly),
              status = CapabilityState.Available.status,
              name = Some(profile.displayName),
              reason = None
            )
          ).toMap
        )
    }

  /** Which features a cluster's row advertises.
    *
    * A read-only cluster does **not** advertise `alerts.acknowledge`, and this is the one place the read-only
    * decision reaches the browser before a request is made. `Action.AlertsAcknowledge` is altering, so the
    * guard refuses it on a read-only cluster (ADR-053 §1); a browser that had been told the feature was there
    * would draw an enabled button whose only possible outcome is a refusal, and a control that changes
    * position between users is worse than a disabled one (`SCREENS-V4.md` §3.7).
    *
    * The names are derived from the published contract rather than written out, for `MetricsCapabilities`'
    * reason: a list written out is a list that goes stale, and a third endpoint added to
    * `AlertsEndpoints.all` and forgotten here would be a feature the browser never asks for on a cluster that
    * can answer it.
    */
  def featuresFor(readOnly: Boolean): List[String] =
    if readOnly then Features.filterNot(_ == AcknowledgeFeature) else Features

  /** Everything this service does, each named by its own endpoint.
    *
    * Read by [[featuresFor]] rather than restated there, so that the roster has one definition. It was a
    * `val` no code path reached for a milestone, asserted only against the expression it is defined as — a
    * case that could not fail beside a constant nothing read.
    */
  val Features: List[String] = AlertsEndpoints.all.flatMap(_.info.name)

  /** The write, named once, because two places need to be able to leave it out. */
  val AcknowledgeFeature: String = "alerts.acknowledge"
}
