package kui.schema.infrastructure

import cats.Applicative
import cats.syntax.all.*

import kui.config.ClusterConfig
import kui.kernel.ClusterId
import kui.schema.application.{ClusterRegistries, RegistryProfile}
import kui.schema.domain.SchemaRegistryPort

/** The clusters this process was configured with, and the registries some of them have.
  *
  * ==Why the list comes from the configuration and not from the cluster service==
  *
  * The other services get their cluster profiles over ADR-046's profile client, because they need the
  * *credentials* the cluster service holds. This one does not: a registry's address and credentials are its
  * own configuration block, read from the same file this process already loaded. Asking another service for a
  * list this one is holding in memory would add a socket, a timeout and a startup ordering dependency to a
  * lookup that cannot fail — which is the same argument `ConfiguredProfileSource` makes in the consumer
  * service, and it applies here more strongly because the answer contains nothing that service owns.
  *
  * ==Every configured cluster is listed, registry or not==
  *
  * A cluster with no registry is in [[all]] with `hasRegistry = false`. Leaving it out would make the
  * capability report say nothing about it, and a cluster missing from the report reads as "this service has
  * never heard of it" — which is the state the browser renders as a service being down rather than as a
  * feature that is off. The difference between "no registry here" and "the registry is broken" is the whole
  * point of this service's degraded behaviour, and it starts with this list.
  */
final class ConfiguredClusterRegistries[F[_]: Applicative](
    profiles: List[RegistryProfile],
    ports: Map[ClusterId, SchemaRegistryPort[F]]
) extends ClusterRegistries[F] {

  private val byId: Map[ClusterId, RegistryProfile] =
    profiles.map(profile => profile.cluster -> profile).toMap

  def all: F[List[RegistryProfile]] = profiles.pure[F]

  def profile(cluster: ClusterId): F[Option[RegistryProfile]] = byId.get(cluster).pure[F]

  def registry(cluster: ClusterId): F[Option[SchemaRegistryPort[F]]] = ports.get(cluster).pure[F]
}

object ConfiguredClusterRegistries {

  /** What `kui.clusters[]` says about each cluster, from this service's point of view.
    *
    * Sorted by id so that the capability report, the startup log and any diagnostic list the clusters in the
    * same order every time. An order that depends on the file makes two deployments of the same product look
    * different for no reason.
    */
  def profilesOf(clusters: List[ClusterConfig]): List[RegistryProfile] =
    clusters
      .map(cluster =>
        RegistryProfile(
          cluster = cluster.id,
          displayName = cluster.name,
          hasRegistry = cluster.schemaRegistry.isDefined,
          readOnly = cluster.readOnly
        )
      )
      .sortBy(_.cluster.value)
}
