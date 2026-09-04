package kui.kafka.admin

import scala.annotation.unused
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

import cats.effect.Async
import cats.effect.syntax.all.*
import cats.syntax.all.*
import org.apache.kafka.clients.admin.{
  DescribeConsumerGroupsOptions,
  ListConsumerGroupOffsetsOptions,
  ListConsumerGroupOffsetsSpec,
  ListGroupsOptions
}
import org.apache.kafka.common.GroupState as KafkaGroupState
import org.apache.kafka.common.errors.{
  GroupIdNotFoundException,
  InvalidRequestException,
  UnsupportedVersionException
}
import org.typelevel.log4cats.Logger

import kui.kafka.{AdminBatch, AdminClientPool, BatchResult, KafkaErrorMapper, KafkaFutures, SkipReason}
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

    // ---------------------------------------------------------------- describeGroups

    def describeGroups(
        conn: ClusterConnection,
        ids: List[GroupId],
        includeAuthorizedOperations: Boolean
    ): F[Either[KuiError, BatchResult[GroupId, GroupDescription]]] =
      if ids.isEmpty then Async[F].pure(Right(BatchResult.empty[GroupId, GroupDescription]))
      else
        ids.distinct
          .grouped(conn.admin.groupChunkSize)
          .toList
          .parTraverseN(bounded(conn.admin.parallelism))(chunk =>
            describeChunk(conn, chunk, includeAuthorizedOperations)
          )
          // Merged in the order the chunks were cut, not the order they finished: a result that
          // depends on scheduling cannot be reproduced from a bug report.
          .map(_.foldLeft(BatchResult.empty[GroupId, GroupDescription])((acc, part) => acc.combine(part)))
          .attempt
          .map {
            case Right(result) => Right(result)
            case Left(failure) =>
              Left(KafkaErrorMapper.map(GroupAdmin.Operation.Describe, failure, apiTimeout(conn)))
          }

    /** One `describeConsumerGroups` request, awaited per group id rather than through `all()`.
      *
      * `all()` fails the whole chunk when one group is not authorized, which would cost fifty groups their
      * row because of one ACL. `AdminBatch.perKey` awaits each id's own future, so that failure stays on its
      * own key with the reason its own exception gives.
      *
      * This is also where the fabricated dead group is produced: a `GroupIdNotFoundException` for one id
      * becomes a `Dead` description with no members, before `KafkaErrorMapper` — or any caller — sees it.
      */
    private def describeChunk(
        conn: ClusterConnection,
        chunk: List[GroupId],
        includeAuthorizedOperations: Boolean
    ): F[BatchResult[GroupId, GroupDescription]] =
      pool.run(conn, GroupAdmin.Operation.Describe) { admin =>
        val options = new DescribeConsumerGroupsOptions()
          .includeAuthorizedOperations(includeAuthorizedOperations)

        Async[F]
          .delay(admin.describeConsumerGroups(chunk.map(_.value).asJava, options).describedGroups())
          .flatMap { described =>
            val perGroup = chunk.map { id =>
              val effect = Option(described.get(id.value)) match {
                case None => Async[F].pure(GroupDescription.dead(id))
                case Some(future) =>
                  KafkaFutures
                    .fromFuture(Async[F].delay(future))
                    .map(raw => AdminConversions.groupDescription(id, raw))
                    .recoverWith {
                      case failure if isGroupNotFound(failure) =>
                        logged(
                          _.debug(
                            s"cluster ${conn.id.value} does not know group ${id.value}; " +
                              "describing it as a dead group with no members"
                          )
                        ).as(GroupDescription.dead(id))
                    }
              }

              id -> effect
            }.toMap

            AdminBatch.perKey(perGroup, bounded(conn.admin.parallelism), GroupAdmin.Operation.Describe)
          }
      }

    private def isGroupNotFound(failure: Throwable): Boolean =
      KafkaFutures.unwrap(failure) match {
        case _: GroupIdNotFoundException => true
        case _ => false
      }

    private def bounded(parallelism: Int): Int = math.max(1, parallelism)

    // ---------------------------------------------------------------- committedOffsets

    def committedOffsets(
        conn: ClusterConnection,
        groups: List[GroupId],
        partitions: Option[Set[TopicPartition]],
        requireStable: Boolean
    ): F[Either[KuiError, BatchResult[GroupId, List[CommittedOffset]]]] =
      if groups.isEmpty then Async[F].pure(Right(BatchResult.empty[GroupId, List[CommittedOffset]]))
      else
        groups.distinct
          .grouped(conn.admin.groupChunkSize)
          .toList
          .parTraverseN(bounded(conn.admin.parallelism))(chunk =>
            offsetsChunk(conn, chunk, partitions, requireStable)
          )
          .map(
            _.foldLeft(BatchResult.empty[GroupId, List[CommittedOffset]])((acc, part) => acc.combine(part))
          )
          .attempt
          .map {
            case Right(result) => Right(result)
            case Left(failure) =>
              Left(KafkaErrorMapper.map(GroupAdmin.Operation.Offsets, failure, apiTimeout(conn)))
          }

    /** One multi-group `listConsumerGroupOffsets` (KIP-709, Kafka 3.3), downgraded to one call per group when
      * the broker will not take the multi-group form.
      *
      * `requireStable` is dropped on a broker that refuses it rather than failing the call: an operator
      * looking at a group's offsets is better served by offsets that may include an in-flight transaction
      * than by an error, and the downgrade is logged.
      */
    private def offsetsChunk(
        conn: ClusterConnection,
        chunk: List[GroupId],
        partitions: Option[Set[TopicPartition]],
        requireStable: Boolean
    ): F[BatchResult[GroupId, List[CommittedOffset]]] =
      pool
        .run(conn, GroupAdmin.Operation.Offsets) { admin =>
          val spec = new ListConsumerGroupOffsetsSpec()
          partitions.foreach(wanted => spec.topicPartitions(wanted.toList.map(kafkaPartition).asJava): Unit)

          val request = chunk.map(id => id.value -> spec).toMap.asJava
          val options = new ListConsumerGroupOffsetsOptions().requireStable(requireStable)

          Async[F]
            .delay(admin.listConsumerGroupOffsets(request, options))
            .flatMap { result =>
              val perGroup = chunk.map { id =>
                id -> KafkaFutures
                  .fromFuture(Async[F].delay(result.partitionsToOffsetAndMetadata(id.value)))
                  .map(AdminConversions.committedOffsets)
              }.toMap

              AdminBatch.perKey(perGroup, bounded(conn.admin.parallelism), GroupAdmin.Operation.Offsets)
            }
        }
        .recoverWith {
          case failure if requireStable && isFilterUnsupported(failure) =>
            logged(
              _.debug(
                s"cluster ${conn.id.value} does not support requireStable committed offsets; " +
                  "asking without it"
              )
            ) >> offsetsChunk(conn, chunk, partitions, requireStable = false)
        }

    private def kafkaPartition(partition: TopicPartition): org.apache.kafka.common.TopicPartition =
      new org.apache.kafka.common.TopicPartition(partition.topic.value, partition.partition.value)

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
