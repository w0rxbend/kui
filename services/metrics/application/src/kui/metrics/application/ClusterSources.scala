package kui.metrics.application

import kui.kernel.ClusterId
import kui.kernel.error.{ApplicationError, ErrorCode, KuiError}
import kui.metrics.domain.MetricsSourcePort

/** One cluster, as this service needs to see it.
  *
  * @param hasSource
  *   whether `kui.metrics.sources.<id>` names an address for this cluster. It is the fact the whole service
  *   is shaped around: `false` is not a failure and must never render as one — every metrics card answers
  *   `not_configured` and keeps its `NotMeasured` sentence, which is ADR-032's rule and is a different
  *   picture from an exporter that is down.
  * @param unreadableReason
  *   why a source this cluster *did* configure cannot be read by this build, when that is the case. It is a
  *   sentence rather than a flag because the only two things it is ever used for are telling a person and
  *   putting the same words on the capability row; and it is filled in by the adapter, which is the layer
  *   that knows which protocols exist. Today it has exactly one cause: `MetricsSourceKind.Jmx`, which is
  *   declared and unimplemented (ADR-050).
  */
final case class SourceProfile(
    cluster: ClusterId,
    displayName: String,
    hasSource: Boolean,
    unreadableReason: Option[String] = None
) {

  /** Whether this build can actually put a number on a chart for this cluster.
    *
    * Not the same question as `hasSource`, and keeping them apart is what stops a deployment that configured
    * an address KUI cannot read being reported as one that configured nothing.
    */
  def isMeasurable: Boolean = hasSource && unreadableReason.isEmpty
}

object SourceProfile {
  given CanEqual[SourceProfile, SourceProfile] = CanEqual.derived
}

/** Which clusters exist, and which of them have a metrics source KUI can actually read.
  *
  * The two questions are one interface for the reason `ClusterRegistries` gives in the schema service: "I
  * have never heard of this cluster" and "I know this cluster and cannot measure it" are different answers
  * with different statuses and different screens — a bad link against a deployment choice — and a lookup that
  * only returned `Option[Port]` would collapse them, leaving every use case to consult a second source to
  * tell them apart.
  */
trait ClusterSources[F[_]] {

  /** Every cluster this service knows about, source or not. The capability report is built from this, which
    * is why clusters *without* a source are in it: a cluster missing from the report reads as "the service
    * has never heard of it", which the browser renders as a service being down rather than as a feature that
    * is off.
    */
  def all: F[List[SourceProfile]]

  def profile(cluster: ClusterId): F[Option[SourceProfile]]

  /** The collector for one cluster, or `None` when there is nothing to collect from.
    *
    * `None` covers two situations that the profile tells apart: no `kui.metrics.sources` entry at all, and an
    * entry naming a protocol this build cannot read. Both answer `not_configured` on the wire and both keep
    * the card's written sentence; only the *reason* differs, and only a person ever reads it.
    */
  def source(cluster: ClusterId): F[Option[MetricsSourcePort[F]]]
}

/** Turning "which cluster" into "the source to ask, or the reason there is none", once.
  *
  * Written here rather than per use case so that the day this service has five endpoints they cannot spell
  * the same situation two ways — which is how one screen ends up saying `KUI-CLUSTER-NOT-FOUND` and the next
  * one says nothing is configured, for one request.
  */
object SourceAccess {

  def unknownCluster(cluster: ClusterId): KuiError =
    ApplicationError.NotFound("cluster", cluster.value, ErrorCode.ClusterNotFound)

  /** The sentence shown where a cluster has no metrics source.
    *
    * It names the configuration key, because the person most likely to read it is the one wondering why the
    * throughput card is showing a sentence instead of a chart.
    */
  def noSource(cluster: ClusterId): String =
    s"no metrics source is configured for cluster ${cluster.value} " +
      s"(kui.metrics.sources.${cluster.value}.url), so KUI measures nothing here"

  /** The sentence shown where a cluster *does* configure a source this build cannot read, and no more
    * specific reason reached this layer.
    *
    * A separate sentence from [[noSource]] and not a softening of it. An operator who configured an address
    * and sees "nothing is configured" goes and re-reads their own YAML; what they need to be told is that the
    * address is fine and the reading of it is what is missing.
    */
  def unreadableSource(cluster: ClusterId): String =
    s"cluster ${cluster.value} configures a metrics source that this build cannot read " +
      s"(kui.metrics.sources.${cluster.value}.kind)"

  /** The reason a cluster has to be told, given the profile the adapter built for it.
    *
    * One function so that the endpoint, the capability row and any later card cannot spell the same situation
    * three ways — which is how one screen ends up saying a cluster is unconfigured and the next one says its
    * exporter is down, for one deployment.
    */
  def explain(profile: SourceProfile): String =
    if !profile.hasSource then noSource(profile.cluster)
    else profile.unreadableReason.getOrElse(unreadableSource(profile.cluster))
}
