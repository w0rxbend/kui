package kui.connect.application

import kui.connect.domain.ConnectWorkerPort
import kui.kernel.error.KuiError
import kui.kernel.{ClusterId, ConnectName}

/** One cluster, as this service needs to see it: a name for a screen, a flag for a refusal, and which Connect
  * clusters an operator configured for it.
  *
  * No addresses and no credentials. The adapter that builds clients holds those, and keeping them out of the
  * type the application layer sees is what keeps a password out of a use case, an audit record and a log line
  * by construction rather than by review — `ConfiguredProfileSource`'s argument, one service over.
  *
  * @param connects
  *   the configured Connect clusters' names, in configuration order. An empty list is the ordinary case and
  *   not a broken deployment: most Kafka clusters run no Kafka Connect, and the honest answer for them is
  *   `not_configured` rather than an empty connector list.
  */
final case class ConnectProfileView(
    cluster: ClusterId,
    displayName: String,
    readOnly: Boolean,
    connects: List[ConnectName]
) {

  def configured: Boolean = connects.nonEmpty

  def has(connect: ConnectName): Boolean = connects.contains(connect)
}

object ConnectProfileView {
  given CanEqual[ConnectProfileView, ConnectProfileView] = CanEqual.derived
}

/** How this service learns which clusters exist, which of them have Connect clusters, and how to reach one
  * (ADR-036, ADR-046).
  *
  * There is no `changes` here and no snapshot. Everything this service reports is read from a worker at the
  * moment it is asked, so there is no cached state for a configuration change to invalidate; the only moment
  * a statically configured list can change is a restart.
  */
trait ClusterConnectSource[F[_]] {

  def profileOf(cluster: ClusterId): F[Either[KuiError, ConnectProfileView]]

  /** Every cluster this service is serving, in id order. The capability report is built from it, which is why
    * a cluster with no Connect cluster at all is still in it: a cluster missing from the report reads as a
    * service that has never heard of it, which the browser draws as a service being down.
    */
  def all: F[List[ConnectProfileView]]

  /** The client for one Connect cluster of one Kafka cluster.
    *
    * `None` means this deployment configured no such Connect cluster — or configured it and could not build a
    * client for it, which is a wiring failure rather than a deployment choice and is why the caller of this
    * distinguishes the two by asking the profile first.
    */
  def worker(cluster: ClusterId, connect: ConnectName): F[Option[ConnectWorkerPort[F]]]
}
