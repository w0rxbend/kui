package kui.kafka.admin

import scala.annotation.unused
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

import cats.effect.Async
import cats.syntax.all.*
import org.apache.kafka.clients.admin.ListGroupsOptions
import org.apache.kafka.common.GroupState as KafkaGroupState
import org.apache.kafka.common.errors.{InvalidRequestException, UnsupportedVersionException}
import org.typelevel.log4cats.Logger

import kui.kafka.{AdminClientPool, BatchResult, KafkaErrorMapper, KafkaFutures, SkipReason}
import kui.kernel.cluster.ClusterConnection
import kui.kernel.error.{ApplicationError, ErrorCode, KuiError}
import kui.kernel.group.GroupState
import kui.kernel.{GroupId, Offset, TopicPartition}

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

    // ---------------------------------------------------------------- listGroups

    def listGroups(
        conn: ClusterConnection,
        states: Set[GroupState]
    ): F[Either[KuiError, GroupListingResult]] =
      listWith(conn, states, filterAtBroker = states.nonEmpty)
        .recoverWith {
          // The broker does not know the state filter — before Kafka 2.6, or a managed service that
          // rejects the option with `InvalidRequestException` instead. This is a capability
          // downgrade, not a retry: the second call asks a smaller question, and the states are then
          // applied in memory so that the caller cannot tell which path answered it.
          case failure if isFilterUnsupported(failure) =>
            logged(
              _.debug(
                s"cluster ${conn.id.value} does not support the group state filter; " +
                  "listing every group and filtering in memory"
              )
            ) >> listWith(conn, states, filterAtBroker = false)
        }
        .attempt
        .map {
          case Right(result) => Right(result)
          case Left(failure) =>
            Left(KafkaErrorMapper.map(GroupAdmin.Operation.List, failure, apiTimeout(conn)))
        }

    private def listWith(
        conn: ClusterConnection,
        states: Set[GroupState],
        filterAtBroker: Boolean
    ): F[GroupListingResult] =
      pool.run(conn, GroupAdmin.Operation.List) { admin =>
        val options = new ListGroupsOptions()
        if filterAtBroker then options.inGroupStates(states.flatMap(kafkaState).asJava): Unit

        val result = admin.listGroups(options)

        for {
          listings <- KafkaFutures.fromFuture(Async[F].delay(result.valid()))
          failures <- KafkaFutures.fromFuture(Async[F].delay(result.errors()))
          seen = listings.asScala.toList
          consumerGroups = seen.filter(raw => AdminConversions.isConsumerGroup(raw.`type`.toScala))
          reasons = failures.asScala.toList.map(coordinatorFailure)
          groups = consumerGroups
            .map(AdminConversions.groupListing)
            // The in-memory half of the filter. It runs on both paths: on the broker-filtered one it
            // removes nothing, and running it unconditionally is what keeps the two paths' answers
            // identical rather than merely intended to be.
            .filter(listing => matches(states, listing))
            .sortBy(_.groupId.value)
          // Nothing answered, and something failed. "There are no groups" and "no coordinator would
          // tell me" are different screens, so this is a failure rather than an empty list.
          _ <-
            if groups.isEmpty && seen.isEmpty && reasons.nonEmpty then
              Async[F].raiseError[Unit](
                failures.asScala.headOption.getOrElse(
                  new IllegalStateException("every coordinator failed to list groups")
                )
              )
            else Async[F].unit
          _ <- logged(
            _.debug(
              s"cluster ${conn.id.value} listed ${groups.size} consumer group(s); " +
                s"${seen.size - consumerGroups.size} non-consumer group(s) dropped; " +
                s"${reasons.size} coordinator(s) did not answer"
            )
          )
        } yield GroupListingResult(groups, reasons, seen.size - consumerGroups.size)
      }

    /** A group whose state is `Unknown` survives an in-memory filter.
      *
      * Dropping it would hide a group because a broker declined to say what state it is in, which is the
      * opposite of what a state filter is for: the operator asked to see fewer groups, not fewer facts.
      */
    private def matches(states: Set[GroupState], listing: GroupListing): Boolean =
      states.isEmpty || states.contains(listing.state) || listing.state == GroupState.Unknown

    /** KUI's states, back into Kafka's. `Unknown` has no counterpart to send, and the KIP-848 pair that
      * folded onto one KUI state expands back into both.
      */
    private def kafkaState(state: GroupState): Set[KafkaGroupState] = state match {
      case GroupState.Stable => Set(KafkaGroupState.STABLE)
      case GroupState.Empty => Set(KafkaGroupState.EMPTY)
      case GroupState.Dead => Set(KafkaGroupState.DEAD)
      case GroupState.PreparingRebalance =>
        Set(KafkaGroupState.PREPARING_REBALANCE, KafkaGroupState.ASSIGNING, KafkaGroupState.NOT_READY)
      case GroupState.CompletingRebalance =>
        Set(KafkaGroupState.COMPLETING_REBALANCE, KafkaGroupState.RECONCILING)
      case GroupState.Unknown => Set.empty
    }

    private def coordinatorFailure(failure: Throwable): SkipReason =
      KafkaErrorMapper
        .suppressible(failure)
        .getOrElse(SkipReason.Failed(ErrorCode.UpstreamUnavailable, "a coordinator did not answer"))

    private def isFilterUnsupported(failure: Throwable): Boolean =
      KafkaFutures.unwrap(failure) match {
        case _: UnsupportedVersionException => true
        case _: InvalidRequestException => true
        case _ => false
      }

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

    // ---------------------------------------------------------------- helpers

    private def apiTimeout(conn: ClusterConnection): Long = conn.admin.apiTimeout.toMillis

    private def logged(write: Logger[F] => F[Unit]): F[Unit] = log.fold(Async[F].unit)(write)
  }
}
