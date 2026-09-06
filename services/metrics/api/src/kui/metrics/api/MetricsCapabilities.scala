package kui.metrics.api

import cats.effect.kernel.Sync
import cats.syntax.all.*

import kui.contracts.capability.{CapabilityState, ClusterCapability}
import kui.kernel.ClusterId
import kui.metrics.application.{ClusterSources, SourceAccess, SourceProfile}

/** What the metrics service can currently do, per cluster, as the gateway reads it.
  *
  * ==Three situations, and the row has to tell them apart==
  *
  *   - a cluster naming a Prometheus source — **available**, and `configured = true`. This is the row that
  *     did not exist before the collector did, and it is what tells the browser that a chart here is worth
  *     asking for rather than a card that will always answer a sentence;
  *   - no `kui.metrics.sources` entry for this cluster — `not_configured`. Nothing is wrong and nothing is
  *     missing; the cards keep their written `NotMeasured` sentence, which is ADR-032's rule;
  *   - an entry exists naming a protocol this build cannot read — also `not_configured`, with a different
  *     reason. The address is fine and it is `MetricsSourceKind.Jmx` that has no implementation (ADR-050).
  *
  * The last two are `not_configured` rather than `degraded`, deliberately. `degraded` means a thing that
  * normally works is having a bad day and retrying is worth doing; neither is true of a protocol that was
  * never implemented. A permanently degraded row that no action can clear is exactly the red panel operators
  * learn to ignore.
  *
  * ==Why an available row is not probed first==
  *
  * The schema service asks its registry a question before saying `available`. This one does not, and the
  * difference is what the two answers cost: the schema row would otherwise be reporting on an upstream
  * nothing else has contacted, while a metrics source is scraped every `scrapeInterval` by a loop that is
  * already running. A row that said `degraded` when the last scrape failed would be a second, slower opinion
  * about a fact the throughput endpoint's own `Section` already carries per request — and the whole reason
  * that endpoint answers `unavailable` with a reason is so that one dead exporter costs one card.
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
    * `configured` answers one question and it is not "did somebody write a URL down": it is "can KUI measure
    * this cluster". A source naming a protocol this build cannot read is `false`, exactly as no source at all
    * is, because the browser folds `false` into `CapabilityState.NotConfigured` and keeps the card's written
    * sentence — which is the correct rendering for both. What separates them is `reason`, where a person
    * reads it.
    */
  def stateOf(profile: SourceProfile): ClusterCapability =
    if profile.isMeasurable then
      ClusterCapability(
        configured = true,
        features = List(ThroughputFeature),
        status = CapabilityState.Available.status,
        name = Some(profile.displayName),
        reason = None
      )
    else
      ClusterCapability(
        configured = false,
        features = Nil,
        status = CapabilityState.NotConfigured.status,
        name = Some(profile.displayName),
        reason = Some(SourceAccess.explain(profile))
      )

  /** The one thing this service measures. It is the endpoint's own name, so that a browser reading the
    * feature list and a browser reading the OpenAPI document are reading one string.
    */
  val ThroughputFeature: String = "metrics.throughput"
}
