package kui.cluster.infrastructure

import scala.concurrent.duration.FiniteDuration

import cats.effect.{IO, Ref, Temporal}

import kui.kafka.BatchResult
import kui.kafka.admin as adm
import kui.kernel.BrokerId
import kui.kernel.cluster.ClusterConnection
import kui.kernel.error.KuiError

/** The in-test stand-in for `libs/kafka`'s `ClusterAdmin`.
  *
  * It returns the values it was constructed with and records the calls it received, and it has no behaviour
  * of its own on purpose: a stub with logic is a second implementation to debug, and a contract suite that
  * passes against it would then be evidence about the stub.
  *
  * It lives in this module's test sources and not in `libs/testkit` because `libs/testkit` is on the
  * classpath of modules that layering rule A10 forbids a Kafka client, and this is a stub of a `libs/kafka`
  * type.
  */
final class StubKafkaClusterAdmin(
    describeClusterResult: Either[KuiError, adm.ClusterDescription],
    versionResult: Either[KuiError, adm.BrokerVersion],
    quorumResult: Either[KuiError, Option[adm.QuorumInfo]],
    brokerConfigsResult: Either[KuiError, List[adm.ConfigEntry]],
    logDirsResult: Either[KuiError, BatchResult[BrokerId, List[adm.LogDir]]],
    capabilitiesResult: IO[adm.ClusterFeatures],
    /** Simulated time one `describeCluster` takes, so a timeout can be asserted with `TestControl` rather
      * than by sleeping.
      */
    describeClusterDelay: FiniteDuration,
    val calls: Ref[IO, List[String]]
) extends adm.ClusterAdmin[IO] {

  def describeCluster(connection: ClusterConnection): IO[Either[KuiError, adm.ClusterDescription]] =
    record("describeCluster") *>
      Temporal[IO].sleep(describeClusterDelay).as(describeClusterResult)

  def version(connection: ClusterConnection): IO[Either[KuiError, adm.BrokerVersion]] =
    record("version").as(versionResult)

  def describeQuorum(connection: ClusterConnection): IO[Either[KuiError, Option[adm.QuorumInfo]]] =
    record("describeQuorum").as(quorumResult)

  def brokerConfigs(
      connection: ClusterConnection,
      broker: BrokerId,
      includeDocs: Boolean
  ): IO[Either[KuiError, List[adm.ConfigEntry]]] =
    record("brokerConfigs").as(brokerConfigsResult)

  def describeLogDirs(
      connection: ClusterConnection,
      brokers: Set[BrokerId]
  ): IO[Either[KuiError, BatchResult[BrokerId, List[adm.LogDir]]]] =
    record("describeLogDirs").as(logDirsResult)

  def capabilities(connection: ClusterConnection): IO[adm.ClusterFeatures] =
    record("capabilities") *> capabilitiesResult

  private def record(name: String): IO[Unit] = calls.update(name :: _)
}

object StubKafkaClusterAdmin {

  import scala.concurrent.duration.*

  /** Every method answering successfully with the smallest believable value, so that a test overrides only
    * the one call it is about.
    */
  def apply(
      describeCluster: Either[KuiError, adm.ClusterDescription] = Right(KafkaFixtures.description),
      version: Either[KuiError, adm.BrokerVersion] = Right(KafkaFixtures.version),
      quorum: Either[KuiError, Option[adm.QuorumInfo]] = Right(Some(KafkaFixtures.quorum)),
      brokerConfigs: Either[KuiError, List[adm.ConfigEntry]] = Right(KafkaFixtures.configs),
      logDirs: Either[KuiError, BatchResult[BrokerId, List[adm.LogDir]]] = Right(KafkaFixtures.logDirs),
      capabilities: IO[adm.ClusterFeatures] = IO.pure(KafkaFixtures.features),
      describeClusterDelay: FiniteDuration = Duration.Zero
  ): IO[StubKafkaClusterAdmin] =
    Ref
      .of[IO, List[String]](Nil)
      .map(
        new StubKafkaClusterAdmin(
          describeCluster,
          version,
          quorum,
          brokerConfigs,
          logDirs,
          capabilities,
          describeClusterDelay,
          _
        )
      )
}
