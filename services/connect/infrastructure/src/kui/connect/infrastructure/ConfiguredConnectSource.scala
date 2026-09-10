package kui.connect.infrastructure

import cats.Applicative
import cats.syntax.all.*

import kui.config.ClusterConfig
import kui.connect.application.{ClusterConnectSource, ConnectProfileView}
import kui.connect.domain.ConnectWorkerPort
import kui.kernel.error.{ApplicationError, ErrorCode, KuiError}
import kui.kernel.{ClusterId, ConnectName}

/** The connect service's `ClusterConnectSource`, answered from this process's own configuration.
  *
  * The schema, consumer, topic and alerts services all hold one of these and the argument is theirs: the
  * all-in-one deployment has already loaded `kui.clusters[]` in order to wire the cluster service, and making
  * it call itself over a socket to read a list it is holding in memory would add a listener, a timeout and a
  * failure mode to a lookup that cannot fail.
  *
  * ==What it carries that the application layer cannot see==
  *
  * A `ConnectProfileView` has the id, the display name, `readOnly` and the *names* of the Connect clusters
  * configured for it — no addresses and no credentials. The clients are held here, already built, keyed by
  * (cluster, Connect cluster). That is what keeps a password out of a use case, an audit record and a log
  * line by construction rather than by review.
  *
  * @param workers
  *   one client per configured Connect cluster, built by `ConnectWiring`. A key that is in the profile and
  *   missing from this map is a wiring failure rather than a deployment choice, and the use case reports it
  *   as one — a row saying KUI could not build a client, not a row saying the operator configured nothing.
  */
final class ConfiguredConnectSource[F[_]: Applicative](
    clusters: List[ClusterConfig],
    workers: Map[(ClusterId, ConnectName), ConnectWorkerPort[F]]
) extends ClusterConnectSource[F] {

  private val views: List[ConnectProfileView] =
    clusters
      .map(cluster =>
        ConnectProfileView(
          cluster = cluster.id,
          displayName = cluster.name,
          readOnly = cluster.readOnly,
          // Configuration order, not name order: the operator wrote the list, and a screen that reorders
          // it makes the second entry hard to find in a file where it is second.
          connects = cluster.connect.map(_.name)
        )
      )
      .sortBy(_.cluster.value)

  def profileOf(cluster: ClusterId): F[Either[KuiError, ConnectProfileView]] =
    views
      .find(_.cluster == cluster)
      .toRight(ApplicationError.NotFound("cluster", cluster.value, ErrorCode.ClusterNotFound): KuiError)
      .pure[F]

  def all: F[List[ConnectProfileView]] = views.pure[F]

  def worker(cluster: ClusterId, connect: ConnectName): F[Option[ConnectWorkerPort[F]]] =
    workers.get((cluster, connect)).pure[F]
}

object ConfiguredConnectSource {

  /** The profiles alone, for a caller that needs the list without the clients — the startup log, and a suite
    * that is asserting what a configuration produces rather than what a worker answers.
    */
  def profilesOf(clusters: List[ClusterConfig]): List[ConnectProfileView] =
    clusters
      .map(cluster =>
        ConnectProfileView(cluster.id, cluster.name, cluster.readOnly, cluster.connect.map(_.name))
      )
      .sortBy(_.cluster.value)
}
