package kui.metrics.api

import cats.effect.kernel.Sync
import cats.syntax.all.*

import kui.contracts.capability.{CapabilityState, ClusterCapability}
import kui.kernel.ClusterId
import kui.metrics.application.{ClusterSources, SourceAccess, SourceProfile}

/** What the metrics service can currently do, per cluster, as the gateway reads it.
  *
  * ==Today the answer is "nothing", and saying so precisely is the feature==
  *
  * This build has no collector. Every cluster is therefore `not_configured`, and the six dashboard cards that
  * read this service keep their written `NotMeasured` sentence. That is the correct rendering (ADR-032) and
  * the reason this service can ship before the adapter that measures anything: the browser is told the truth
  * in a form it already knows how to draw.
  *
  * What the row still has to get right is the **reason**, because two deployments are in different situations
  * and only one of them has something to do about it:
  *
  *   - no `kui.metrics.sources` entry for this cluster — nothing is wrong and nothing is missing;
  *   - an entry exists and this build cannot read it — the address is fine, the collector is what is absent.
  *
  * Both are `not_configured` rather than one of them `degraded`, deliberately. `degraded` means a thing that
  * normally works is having a bad day and retrying is worth doing; neither is true of a collector that has
  * not been written. A permanently degraded row that no action can clear is exactly the red panel operators
  * learn to ignore.
  *
  * ==What a service may say about itself==
  *
  * `available`, `degraded` or `not_configured`, and never `unavailable`: a service answering this request is
  * reachable by definition, and `unavailable` is the *gateway's* verdict when it gets no answer at all
  * (ADR-039 §6).
  */
trait MetricsCapabilities[F[_]] {
  def report: F[Map[ClusterId, ClusterCapability]]
}

object MetricsCapabilities {

  def make[F[_]: Sync](sources: ClusterSources[F]): MetricsCapabilities[F] =
    new MetricsCapabilities[F] {

      def report: F[Map[ClusterId, ClusterCapability]] =
        sources.all.map(_.map(profile => profile.cluster -> stateOf(profile)).toMap)
    }

  /** One cluster's row.
    *
    * `configured = false` is what the gateway folds into `CapabilityState.NotConfigured`, and it is what
    * makes the browser keep the sentence rather than render an error. It stays `false` even for a cluster
    * that named an address, because from the browser's point of view the question is "can KUI measure this",
    * and the answer is no; the difference between the two situations is in `reason`, where a person reads it.
    */
  def stateOf(profile: SourceProfile): ClusterCapability =
    ClusterCapability(
      configured = false,
      features = Nil,
      status = CapabilityState.NotConfigured.status,
      name = Some(profile.displayName),
      reason = Some(
        if profile.hasSource then SourceAccess.noCollector(profile.cluster)
        else SourceAccess.noSource(profile.cluster)
      )
    )
}
