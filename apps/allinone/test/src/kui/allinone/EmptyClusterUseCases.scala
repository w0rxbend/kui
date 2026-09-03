package kui.allinone

import java.time.Instant

import cats.effect.IO
import fs2.Stream

import kui.cluster.application.*
import kui.cluster.domain.{ClusterProfile, ClusterRef, ProfileVersion, StoreHealth}
import kui.kernel.error.{ApplicationError, ErrorCode, KuiError}
import kui.kernel.{BrokerId, ClusterId}

/** The cluster service's use cases over a deployment that has been told about no cluster at all.
  *
  * The all-in-one suites are about *assembly* — that a service reached in process behaves exactly as one
  * reached over a socket, and that one service failing leaves the rest usable — so what the cluster service
  * answers matters far less than that it answers the same way through both transports. An empty deployment is
  * the cheapest honest thing to answer with: every cluster-shaped question is "no such cluster", which is the
  * truth about a KUI nobody has configured a cluster in, and it needs no broker.
  */
object EmptyClusterUseCases {

  private val at: Instant = Instant.EPOCH

  private def notFound(id: ClusterId): KuiError =
    ApplicationError.NotFound("cluster", id.value, ErrorCode.ClusterNotFound)

  val registry: ClusterRegistry[IO] = new ClusterRegistry[IO] {
    private val empty =
      RegistrySnapshot(Map.empty, RegistryVersion.Initial, StoreHealth.NotConfigured, at)

    def snapshot: IO[RegistrySnapshot] = IO.pure(empty)
    def list: IO[List[ClusterProfile]] = IO.pure(Nil)
    def refs: IO[List[ClusterRef]] = IO.pure(Nil)
    def registryVersion: IO[RegistryVersion] = IO.pure(RegistryVersion.Initial)
    def reload: IO[RegistrySnapshot] = IO.pure(empty)
    def changes: Stream[IO, RegistrySnapshot] = Stream.emit(empty)
    def resolve(id: ClusterId): IO[Either[KuiError, ClusterProfile]] = IO.pure(Left(notFound(id)))
  }

  val topology: ClusterTopologyUseCase[IO] = new ClusterTopologyUseCase[IO] {
    def view(id: ClusterId): IO[Either[KuiError, TopologyView]] = IO.pure(Left(notFound(id)))
    def viewAll: IO[List[TopologyView]] = IO.pure(Nil)
    def forceRefresh(id: ClusterId): IO[Either[KuiError, Unit]] = IO.pure(Left(notFound(id)))
  }

  val brokers: BrokerDetailUseCase[IO] = new BrokerDetailUseCase[IO] {
    def brokers(cluster: ClusterId): IO[Either[KuiError, BrokerList]] = IO.pure(Left(notFound(cluster)))

    def logDirs(cluster: ClusterId, broker: BrokerId): IO[Either[KuiError, BrokerLogDirs]] =
      IO.pure(Left(notFound(cluster)))

    def partitionSizes(cluster: ClusterId, broker: BrokerId): IO[Either[KuiError, PartitionSizes]] =
      IO.pure(Left(notFound(cluster)))

    def logDirsAndSizes(
        cluster: ClusterId,
        broker: BrokerId
    ): IO[Either[KuiError, (BrokerLogDirs, PartitionSizes)]] = IO.pure(Left(notFound(cluster)))

    def configs(
        cluster: ClusterId,
        broker: BrokerId,
        includeDocs: Boolean
    ): IO[Either[KuiError, BrokerConfigView]] = IO.pure(Left(notFound(cluster)))
  }

  /** Writing is refused: this deployment has no metadata store, which is what a caller is told. */
  val writes: ClusterWriteUseCase[IO] = new ClusterWriteUseCase[IO] {
    def put(profile: ClusterProfile, expected: ProfileVersion): IO[Either[KuiError, ClusterProfile]] =
      IO.pure(
        Left(ApplicationError.Unsupported("the metadata store is not configured in this deployment"))
      )
  }

  /** A cluster id nothing is configured under, for the cases that need a failure both transports agree on. */
  val UnknownCluster: ClusterId = ClusterId.unsafe("not-configured")
}
