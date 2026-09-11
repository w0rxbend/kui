package kui.ksql.infrastructure

import cats.Applicative
import cats.syntax.all.*

import kui.config.ClusterConfig
import kui.kernel.ClusterId
import kui.kernel.error.{ApplicationError, ErrorCode, KuiError}
import kui.ksql.application.{ClusterKsqlSource, KsqlClient, KsqlProfileView}

/** The ksql service's `ClusterKsqlSource`, answered from this process's own configuration.
  *
  * The schema, consumer, topic, alerts and connect services all hold one of these and the argument is theirs:
  * the all-in-one deployment has already loaded `kui.clusters[]` in order to wire the cluster service, and
  * making it call itself over a socket to read a list it is holding in memory would add a listener, a timeout
  * and a failure mode to a lookup that cannot fail.
  *
  * ==What it carries that the application layer cannot see==
  *
  * A `KsqlProfileView` has the id, the display name, `readOnly` and whether a ksqlDB is configured — no
  * address and no credentials. The clients are held here, already built, keyed by cluster. That is what keeps
  * a password out of a use case, an audit record and a log line by construction rather than by review.
  *
  * @param clients
  *   one client per configured ksqlDB, built by `KsqlWiring`. A cluster that is `configured` in the profile
  *   and missing from this map is a wiring failure rather than a deployment choice, and the use case reports
  *   it as one — a section saying KUI could not build a client, not one saying the operator configured
  *   nothing.
  */
final class ConfiguredKsqlSource[F[_]: Applicative](
    clusters: List[ClusterConfig],
    clients: Map[ClusterId, KsqlClient[F]]
) extends ClusterKsqlSource[F] {

  private val views: List[KsqlProfileView] = ConfiguredKsqlSource.profilesOf(clusters)

  def profileOf(cluster: ClusterId): F[Either[KuiError, KsqlProfileView]] =
    views
      .find(_.cluster == cluster)
      .toRight(ApplicationError.NotFound("cluster", cluster.value, ErrorCode.ClusterNotFound): KuiError)
      .pure[F]

  def all: F[List[KsqlProfileView]] = views.pure[F]

  def client(cluster: ClusterId): F[Option[KsqlClient[F]]] = clients.get(cluster).pure[F]
}

object ConfiguredKsqlSource {

  /** The profiles alone, for a caller that needs the list without the clients — the startup log, and a suite
    * that is asserting what a configuration produces rather than what a server answers.
    */
  def profilesOf(clusters: List[ClusterConfig]): List[KsqlProfileView] =
    clusters
      .map(cluster =>
        KsqlProfileView(cluster.id, cluster.name, cluster.readOnly, configured = cluster.ksql.isDefined)
      )
      .sortBy(_.cluster.value)
}
