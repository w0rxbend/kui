package kui.kafka.admin

import scala.jdk.CollectionConverters.*

import cats.effect.Async
import cats.effect.syntax.all.*
import cats.syntax.all.*
import org.apache.kafka.clients.admin.{Admin, ListOffsetsOptions, OffsetSpec}
import org.typelevel.log4cats.Logger

import kui.kafka.{AdminClientPool, BatchResult, KafkaErrorMapper, KafkaFutures, SkipReason}
import kui.kernel.cluster.ClusterConnection
import kui.kernel.error.{ErrorCode, KuiError}
import kui.kernel.{Offset, TopicPartition}

/** Reading log offsets, with the one filter Kafka forces on anyone who does.
  *
  * ## Leaderless partitions are filtered before the request, at the port
  *
  * If any partition named in a `listOffsets` request has no leader, the AdminClient does not fail the call.
  * It retries metadata, quietly, until `default.api.timeout.ms` expires — sixty seconds with KUI's defaults —
  * and only then reports a timeout that names nothing useful.
  *
  * That is worse than an error. A single offline partition turns a millisecond call into a one-minute
  * failure; the timeout is charged to the whole request rather than to the partition that caused it; and it
  * happens only while a broker is down, which is exactly when the screen matters most.
  *
  * **The port filters, not the caller.** Every partition removed appears in the returned `BatchResult` as
  * `SkipReason.NoLeader`, so a caller can render "offline" for that partition instead of a number, and an
  * aggregate — a group's total lag, a topic's message count — can refuse to compute rather than report a
  * total that is wrong rather than merely incomplete. A request whose every partition is leaderless makes no
  * Kafka call at all.
  *
  * Evidence: `research/kafka/admin-capabilities.md` §2. Recorded in `libs/kafka/PORT-INVARIANTS.md` §1 before
  * this component existed. **`TopicAdmin.listOffsets` must call this component rather than write the filter a
  * second time**: two implementations of a sixty-second-timeout guard is one more than can be kept correct.
  */
trait OffsetLookup[F[_]] {

  /** The offset the next record will get, per partition: what lag is measured against. */
  def endOffsets(
      conn: ClusterConnection,
      partitions: Set[TopicPartition]
  ): F[Either[KuiError, BatchResult[TopicPartition, Offset]]]

  /** The oldest offset still retained, per partition: what a consumer committed before is compared with. */
  def beginningOffsets(
      conn: ClusterConnection,
      partitions: Set[TopicPartition]
  ): F[Either[KuiError, BatchResult[TopicPartition, Offset]]]

  /** `None` for a partition with no record at or after `timestampMs`.
    *
    * The caller decides what that means. KIP-122's rule for a reset is "use the end offset", and that
    * decision is the planner's, not this component's: a lookup that silently substituted the end offset would
    * make a plan that says "we found a record at this time" when none was found.
    */
  def offsetsForTimes(
      conn: ClusterConnection,
      timestamps: Map[TopicPartition, Long]
  ): F[Either[KuiError, BatchResult[TopicPartition, Option[Offset]]]]

  /** The partitions among these that currently have no leader.
    *
    * Exposed because the reset planner has to refuse *before* it plans rather than discover during the write:
    * a partial reset that silently skipped a partition leaves a group in a state nobody asked for (DEVPLAN
    * §10 D8).
    */
  def leaderless(
      conn: ClusterConnection,
      partitions: Set[TopicPartition]
  ): F[Either[KuiError, Set[TopicPartition]]]
}

object OffsetLookup {

  val Operation: String = "offsets.list"
  val LeaderOperation: String = "offsets.leaders"

  def make[F[_]: Async](
      pool: AdminClientPool[F],
      log: Option[Logger[F]] = None
  ): OffsetLookup[F] = new Impl[F](pool, log)

  final private class Impl[F[_]: Async](pool: AdminClientPool[F], log: Option[Logger[F]])
      extends OffsetLookup[F] {

    def endOffsets(
        conn: ClusterConnection,
        partitions: Set[TopicPartition]
    ): F[Either[KuiError, BatchResult[TopicPartition, Offset]]] =
      lookup(conn, partitions.map(_ -> OffsetSpec.latest()).toMap).map(_.map(required))

    def beginningOffsets(
        conn: ClusterConnection,
        partitions: Set[TopicPartition]
    ): F[Either[KuiError, BatchResult[TopicPartition, Offset]]] =
      lookup(conn, partitions.map(_ -> OffsetSpec.earliest()).toMap).map(_.map(required))

    def offsetsForTimes(
        conn: ClusterConnection,
        timestamps: Map[TopicPartition, Long]
    ): F[Either[KuiError, BatchResult[TopicPartition, Option[Offset]]]] =
      lookup(conn, timestamps.map((partition, at) => partition -> OffsetSpec.forTimestamp(at)))

    def leaderless(
        conn: ClusterConnection,
        partitions: Set[TopicPartition]
    ): F[Either[KuiError, Set[TopicPartition]]] =
      if partitions.isEmpty then Async[F].pure(Right(Set.empty[TopicPartition]))
      else
        pool
          .run(conn, LeaderOperation)(admin => leaderlessAmong(admin, partitions))
          .attempt
          .map(_.leftMap(KafkaErrorMapper.map(LeaderOperation, _, conn.admin.apiTimeout.toMillis)))

    /** The end and beginning specs always resolve to a real offset, so a partition that came back without one
      * is a partition KUI has no answer for — a skip with a stated reason, never a substituted zero. A zero
      * here would read on screen as "this partition is empty", which is a different and quite specific lie.
      */
    private def required(
        result: BatchResult[TopicPartition, Option[Offset]]
    ): BatchResult[TopicPartition, Offset] = {
      val present = result.values.collect { case (partition, Some(offset)) => partition -> offset }
      val missing = result.values.collect { case (partition, None) =>
        partition -> SkipReason.Failed(ErrorCode.UpstreamUnavailable, "the broker reported no offset")
      }

      BatchResult(present, result.skipped ++ missing)
    }

    /** One `listOffsets` call, chunked, with the leaderless partitions removed before it is sent. */
    private def lookup(
        conn: ClusterConnection,
        wanted: Map[TopicPartition, OffsetSpec]
    ): F[Either[KuiError, BatchResult[TopicPartition, Option[Offset]]]] =
      if wanted.isEmpty then Async[F].pure(Right(BatchResult.empty[TopicPartition, Option[Offset]]))
      else
        pool
          .run(conn, Operation) { admin =>
            leaderlessAmong(admin, wanted.keySet).flatMap { offline =>
              val askable = wanted.view.filterKeys(!offline.contains(_)).toMap

              val skipped = BatchResult[TopicPartition, Option[Offset]](
                Map.empty,
                offline.map(_ -> SkipReason.NoLeader).toMap
              )

              // Every partition is offline: there is nothing to ask, and asking would cost a
              // sixty-second timeout to learn what the metadata already said.
              if askable.isEmpty then
                logged(
                  _.debug(
                    s"cluster ${conn.id.value}: all ${offline.size} partition(s) are leaderless; " +
                      "no listOffsets call was made"
                  )
                ).as(skipped)
              else
                askable.toList
                  .grouped(conn.admin.partitionChunkSize)
                  .toList
                  .parTraverseN(math.max(1, conn.admin.parallelism))(chunk => listChunk(admin, chunk))
                  .map(
                    _.foldLeft(BatchResult.empty[TopicPartition, Option[Offset]])((acc, part) =>
                      acc.combine(part)
                    )
                  )
                  .map(_.combine(skipped))
            }
          }
          .attempt
          .map(_.leftMap(KafkaErrorMapper.map(Operation, _, conn.admin.apiTimeout.toMillis)))

    private def listChunk(
        admin: Admin,
        chunk: List[(TopicPartition, OffsetSpec)]
    ): F[BatchResult[TopicPartition, Option[Offset]]] = {
      val request = chunk.map((partition, spec) => kafkaPartition(partition) -> spec).toMap.asJava

      Async[F].delay(admin.listOffsets(request, new ListOffsetsOptions())).flatMap { result =>
        KafkaFutures.fromFuture(Async[F].delay(result.all())).map { answers =>
          BatchResult.complete(
            chunk.map { (partition, _) =>
              // `-1` is Kafka's "no record matches this timestamp"; every other spec answers a real
              // offset. `None` rather than a substituted end offset: KIP-122's rule is the planner's.
              val offset = Option(answers.get(kafkaPartition(partition)))
                .map(_.offset)
                .filter(_ >= 0L)
                .map(Offset.unsafe)

              partition -> offset
            }.toMap
          )
        }
      }
    }

    /** Which of these partitions has no leader right now, from the metadata the pooled client already holds.
      *
      * `describeTopics` rather than a `listOffsets` probe, because the whole point is not to send the request
      * that hangs.
      */
    private def leaderlessAmong(admin: Admin, partitions: Set[TopicPartition]): F[Set[TopicPartition]] = {
      val topics = partitions.map(_.topic.value).toList

      KafkaFutures
        .fromFuture(Async[F].delay(admin.describeTopics(topics.asJava).allTopicNames()))
        .map { described =>
          partitions.filter { partition =>
            Option(described.get(partition.topic.value)) match {
              // A topic that is not there at all has no leader for this partition either; the caller
              // sees `NoLeader` rather than a timeout, which is the same true statement.
              case None => true
              case Some(topic) =>
                topic.partitions.asScala
                  .find(_.partition == partition.partition.value)
                  .forall(info => Option(info.leader).isEmpty || info.leader.id < 0)
            }
          }
        }
    }

    private def kafkaPartition(partition: TopicPartition): org.apache.kafka.common.TopicPartition =
      new org.apache.kafka.common.TopicPartition(partition.topic.value, partition.partition.value)

    private def logged(write: Logger[F] => F[Unit]): F[Unit] = log.fold(Async[F].unit)(write)
  }
}
