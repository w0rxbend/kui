package kui.kafka.admin

import kui.kafka.BatchResult
import kui.kernel.BrokerId
import kui.kernel.cluster.ClusterConnection
import kui.kernel.error.KuiError

/** The cluster context's window onto a Kafka cluster.
  *
  * One narrow port, per `research/kafka/admin-capabilities.md` DC-D1. The other contexts' ports — topics,
  * groups, ACLs — arrive with the services that call them and not before (DEVPLAN §3): a port designed ahead
  * of its first caller is designed wrong, and an empty trait is an invitation to fill it.
  *
  * The parameter is `ClusterConnection` from `libs/kernel`, not the cluster domain's `ClusterProfile`.
  * `ARCHITECTURE.md` §4.2 writes the latter, which cannot be compiled: layering rule A5 forbids `libs/kafka`
  * from depending on a service. DEVPLAN decision D1 moved the connection ADT into `libs/kernel` for exactly
  * this reason.
  */
trait ClusterAdmin[F[_]] {

  /** Who the cluster is and what it is made of. */
  def describeCluster(connection: ClusterConnection): F[Either[KuiError, ClusterDescription]]

  /** Which Kafka release the cluster runs, as far as it will say.
    *
    * A cluster that reveals no version is not a broken cluster, so an undetectable version is a successful
    * `BrokerVersion(None, _, Unknown)` and not a `Left`.
    */
  def version(connection: ClusterConnection): F[Either[KuiError, BrokerVersion]]

  /** One broker's configuration, sorted by name.
    *
    * A managed service that does not offer broker configuration answers with an empty list rather than an
    * error: that is a true statement about the cluster, and it is the permanent steady state on MSK
    * Serverless.
    */
  def brokerConfigs(
      connection: ClusterConnection,
      broker: BrokerId,
      includeDocs: Boolean
  ): F[Either[KuiError, List[ConfigEntry]]]

  /** The log directories of each named broker, one call per broker.
    *
    * A `BatchResult`, because one broker refusing or one disk being offline must cost that broker's row and
    * not the whole table.
    */
  def describeLogDirs(
      connection: ClusterConnection,
      brokers: Set[BrokerId]
  ): F[Either[KuiError, BatchResult[BrokerId, List[LogDir]]]]

  /** The KRaft quorum, when there is one. `Right(None)` on a ZooKeeper cluster: absence is the answer, not a
    * failure.
    */
  def describeQuorum(connection: ClusterConnection): F[Either[KuiError, Option[QuorumInfo]]]

  /** What the cluster supports, as far as KUI could determine by asking it.
    *
    * Deliberately not an `Either`: a capability probe is a diagnostic, and a diagnostic that can take the
    * page down with it is worse than no diagnostic. Every failure becomes `absent` or `unknown`.
    */
  def capabilities(connection: ClusterConnection): F[ClusterFeatures]
}
