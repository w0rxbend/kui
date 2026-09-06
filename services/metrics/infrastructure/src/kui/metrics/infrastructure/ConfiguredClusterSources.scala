package kui.metrics.infrastructure

import cats.Applicative
import cats.syntax.all.*

import kui.config.{ClusterConfig, MetricsConfig}
import kui.kernel.ClusterId
import kui.metrics.application.{ClusterSources, SourceProfile}
import kui.metrics.domain.MetricsSourcePort

/** The clusters this process was configured with, and the metrics sources some of them declare.
  *
  * ==Why the list comes from the configuration and not from the cluster service==
  *
  * The same argument `ConfiguredClusterRegistries` makes in the schema service, and it applies more strongly
  * here. A metrics source's address is its own configuration block (`kui.metrics.sources.<id>`), read from
  * the file this process already loaded; asking another service over HTTP for a list this one is holding in
  * memory would add a socket, a timeout and a start-up ordering dependency to a lookup that cannot fail.
  *
  * ==Every configured cluster is listed, source or not==
  *
  * A cluster with no source is in [[all]] with `hasSource = false`. Leaving it out would make the capability
  * report say nothing about it, and a cluster missing from the report reads as "this service has never heard
  * of it" — which the browser renders as a service being down rather than as a feature that is off. Telling
  * the two apart is the whole point of this service's behaviour, and it starts with this list.
  *
  * ==Why [[source]] answers `None` for every cluster==
  *
  * There is no collector in this build. KUI reads no broker metric: there is no JMX client and no Prometheus
  * parser anywhere in the repository, and writing one is the metrics milestone's work, not this packet's.
  * What ships here is the *shape* — the endpoint, the six layers, the wiring, the capability report — so that
  * adding the adapter later is one class and one line in [[MetricsWiring]] rather than a new service.
  *
  * The absence is honest rather than hidden: a cluster that named an address gets a WARN at start-up saying
  * the address is fine and the collector is what is missing, and its capability row carries the same
  * sentence. Nothing in this file pretends to measure anything, which is the one failure mode a metrics
  * service must not have.
  */
final class ConfiguredClusterSources[F[_]: Applicative](profiles: List[SourceProfile])
    extends ClusterSources[F] {

  private val byId: Map[ClusterId, SourceProfile] =
    profiles.map(profile => profile.cluster -> profile).toMap

  def all: F[List[SourceProfile]] = profiles.pure[F]

  def profile(cluster: ClusterId): F[Option[SourceProfile]] = byId.get(cluster).pure[F]

  /** No collector exists yet — see the class comment. This is the one line the metrics milestone replaces. */
  def source(cluster: ClusterId): F[Option[MetricsSourcePort[F]]] =
    none[MetricsSourcePort[F]].pure[F]
}

object ConfiguredClusterSources {

  /** What `kui.clusters[]` and `kui.metrics.sources` say about each cluster, from this service's point of
    * view.
    *
    * Sorted by id so that the capability report, the start-up log and any diagnostic list the clusters in the
    * same order every time. An order that depends on the file makes two deployments of the same product look
    * different for no reason.
    */
  def profilesOf(clusters: List[ClusterConfig], metrics: MetricsConfig): List[SourceProfile] =
    clusters
      .map(cluster =>
        SourceProfile(
          cluster = cluster.id,
          displayName = cluster.name,
          hasSource = metrics.sourceFor(cluster.id).isDefined
        )
      )
      .sortBy(_.cluster.value)
}
