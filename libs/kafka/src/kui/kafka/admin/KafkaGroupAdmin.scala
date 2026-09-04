package kui.kafka.admin

import scala.annotation.unused

import cats.effect.Async
import org.typelevel.log4cats.Logger

import kui.kafka.{AdminClientPool, BatchResult}
import kui.kernel.cluster.ClusterConnection
import kui.kernel.error.{ApplicationError, KuiError}
import kui.kernel.group.GroupState
import kui.kernel.{BrokerId, GroupId, Offset, TopicPartition}

/** `GroupAdmin` over the raw `Admin` client, through `AdminClientPool`.
  *
  * The same shape as `KafkaClusterAdmin`, and for the same reasons: raw `Admin` for admin work (ADR-006
  * amendment 1), one exception-to-`KuiError` translation at this boundary and nowhere else, and every call
  * timed by the pool rather than by twelve remembered call sites.
  */
object KafkaGroupAdmin {

  def apply[F[_]: Async](
      pool: AdminClientPool[F],
      log: Option[Logger[F]] = None
  ): GroupAdmin[F] = new Impl[F](pool, log)

  /** Every method not yet implemented answers `ApplicationError.Unsupported`, a typed value — never a `???`
    * and never a silently empty result. That is permitted only because GRP-003 … GRP-007 land inside this
    * same milestone: a method still stubbed after GRP-007 is a bug, and `GroupTypesSuite` is what notices a
    * signature added with no body.
    */
  final private class Impl[F[_]: Async](@unused pool: AdminClientPool[F], @unused log: Option[Logger[F]])
      extends GroupAdmin[F] {

    private def notYet[A](method: String): F[Either[KuiError, A]] =
      Async[F].pure(Left(ApplicationError.Unsupported(s"GroupAdmin.$method")))

    def listGroups(
        @unused conn: ClusterConnection,
        @unused states: Set[GroupState]
    ): F[Either[KuiError, BatchResult[BrokerId, List[GroupListing]]]] = notYet("listGroups")

    def describeGroups(
        @unused conn: ClusterConnection,
        @unused ids: List[GroupId]
    ): F[Either[KuiError, BatchResult[GroupId, GroupDescription]]] = notYet("describeGroups")

    def committedOffsets(
        @unused conn: ClusterConnection,
        @unused groups: List[GroupId],
        @unused partitions: Option[Set[TopicPartition]],
        @unused requireStable: Boolean
    ): F[Either[KuiError, BatchResult[GroupId, List[CommittedOffset]]]] = notYet("committedOffsets")

    def alterOffsets(
        @unused conn: ClusterConnection,
        @unused group: GroupId,
        @unused offsets: Map[TopicPartition, Offset]
    ): F[Either[KuiError, Unit]] = notYet("alterOffsets")

    def deleteOffsets(
        @unused conn: ClusterConnection,
        @unused group: GroupId,
        @unused partitions: Set[TopicPartition]
    ): F[Either[KuiError, Unit]] = notYet("deleteOffsets")

    def deleteGroups(
        @unused conn: ClusterConnection,
        @unused ids: List[GroupId]
    ): F[Either[KuiError, BatchResult[GroupId, Unit]]] = notYet("deleteGroups")
  }
}
