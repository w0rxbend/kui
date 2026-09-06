package kui.metrics.infrastructure

import cats.Applicative
import cats.syntax.all.*

import kui.config.{ClusterConfig, MetricsConfig, MetricsSourceKind, MetricsSourceSettings}
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
  * ==Where the collectors come from==
  *
  * They are built in [[kui.metrics.app.MetricsWiring]], one per cluster that names a Prometheus source, and
  * handed in here. This class holds no client and starts nothing: a buffer with a scrape loop behind it has a
  * lifetime, and a lifetime belongs to the composition root's `Resource` rather than to a lookup table.
  *
  * A cluster whose entry names `MetricsSourceKind.Jmx` gets **no** collector and an `unreadableReason` saying
  * why. It is not a failure and it is not silence: the endpoint answers `not_configured`, the capability row
  * carries the sentence, and the start-up log names the cluster (ADR-050).
  */
final class ConfiguredClusterSources[F[_]: Applicative](
    profiles: List[SourceProfile],
    ports: Map[ClusterId, MetricsSourcePort[F]] = Map.empty[ClusterId, MetricsSourcePort[F]]
) extends ClusterSources[F] {

  private val byId: Map[ClusterId, SourceProfile] =
    profiles.map(profile => profile.cluster -> profile).toMap

  def all: F[List[SourceProfile]] = profiles.pure[F]

  def profile(cluster: ClusterId): F[Option[SourceProfile]] = byId.get(cluster).pure[F]

  def source(cluster: ClusterId): F[Option[MetricsSourcePort[F]]] = ports.get(cluster).pure[F]
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
      .map { cluster =>
        val settings = metrics.sourceFor(cluster.id)

        SourceProfile(
          cluster = cluster.id,
          displayName = cluster.name,
          hasSource = settings.isDefined,
          unreadableReason = settings.flatMap(source => unreadable(cluster.id, source.kind))
        )
      }
      .sortBy(_.cluster.value)

  /** Which clusters this process will actually scrape, in the order they were configured.
    *
    * The composition root builds a client per entry in this list and nothing for the rest, which is what
    * keeps a deployment that measures nothing free of an HTTP pool, a circuit breaker and a permanently zero
    * upstream metric series — the same argument the schema service makes for a cluster with no registry.
    */
  def scrapable(
      clusters: List[ClusterConfig],
      metrics: MetricsConfig
  ): List[(ClusterId, MetricsSourceSettings)] =
    clusters.flatMap(cluster =>
      metrics
        .sourceFor(cluster.id)
        .filter(source => unreadable(cluster.id, source.kind).isEmpty)
        .map(cluster.id -> _)
    )

  /** The sentence for a configured protocol this build cannot read, or `None` when it can read it.
    *
    * A `match` over the enum rather than an `if`, so that a third `MetricsSourceKind` is a compile error here
    * — which is the one place a new protocol must not be able to arrive silently and be measured as nothing.
    */
  def unreadable(cluster: ClusterId, kind: MetricsSourceKind): Option[String] = kind match {
    case MetricsSourceKind.Prometheus => None
    case MetricsSourceKind.Jmx =>
      Some(
        s"cluster ${cluster.value} configures kui.metrics.sources.${cluster.value}.kind: jmx, and this " +
          "build reads the Prometheus text exposition only; point the address at a JMX exporter in " +
          "httpserver mode and set kind: prometheus (ADR-050)"
      )
  }
}
