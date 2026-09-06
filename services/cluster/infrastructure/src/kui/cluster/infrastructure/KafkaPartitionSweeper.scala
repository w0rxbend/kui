package kui.cluster.infrastructure

import scala.jdk.CollectionConverters.*

import cats.effect.kernel.Async
import cats.syntax.all.*
import org.apache.kafka.clients.admin.{ListTopicsOptions, TopicDescription}
import org.apache.kafka.common.{Node, TopicCollection, TopicPartitionInfo}
import org.typelevel.log4cats.StructuredLogger

import kui.cluster.domain.{PartitionCensus, PartitionPlacement, TopicSweep}
import kui.kafka.{AdminBatch, AdminClientPool, KafkaErrorMapper, KafkaFutures}
import kui.kernel.cluster.ClusterConnection
import kui.kernel.error.KuiError
import kui.kernel.{BrokerId, TopicName}

/** One `describeTopics` sweep over every topic in a cluster, folded into partition counts.
  *
  * ==Why it is not in `libs/kafka`==
  *
  * `libs/kafka`'s `ClusterAdmin` is the cluster context's window onto a broker, and its scaladoc says the
  * other contexts' ports "arrive with the services that call them and not before". A topic sweep is a topic
  * call being made by the cluster service, which is a shape neither port anticipated. `services/topic` made
  * the same judgement for the same reason and put its four call shapes in `KafkaTopicAdmin` — written, as
  * this is, against the shared `AdminClientPool`, so the client lifecycle, the timeouts, the metrics and the
  * reconnect handling are still `libs/kafka`'s and only the call shape is local. Hoisting both later is a
  * move, not a rewrite.
  *
  * ==Why the cluster service sweeps at all==
  *
  * The alternative was to fold the topic service's per-topic figures at the gateway. It was rejected because
  * it would make one service's completeness a property of another service's paging: the topic list is paged
  * and the cluster's totals are not, so a gateway fold would be summing whichever page it happened to hold.
  * Two independent sweeps refuse independently, which is the honest shape — the topics list can be complete
  * while the cluster sweep is not.
  *
  * ==What it costs==
  *
  * One `listTopics` and one `describeTopics` per `AdminTuning.topicChunkSize` topics, at the sweep cadence
  * and not the topology cadence — `ClusterSnapshots` holds it in a cell of its own for exactly that reason.
  */
final class KafkaPartitionSweeper[F[_]: Async](
    pool: AdminClientPool[F],
    logger: StructuredLogger[F]
) {

  import KafkaPartitionSweeper.*

  /** The whole sweep, or the reason there is none.
    *
    * `Left` is a sweep that did not happen: `listTopics` failed, or the cluster refused it. Once the listing
    * is in hand every later failure is per topic and lands in `TopicSweep.unreadable`, because a cluster that
    * will not let KUI describe one namespace must still produce a broker list.
    */
  def sweep(connection: ClusterConnection): F[Either[KuiError, TopicSweep]] =
    listTopics(connection).flatMap {
      case Left(error) => error.asLeft[TopicSweep].pure[F]
      case Right(names) if names.isEmpty =>
        // A cluster with no topics is completely counted, and its counts are zeros. That is a
        // measurement, and it must not be confused with the refusal a partial sweep produces.
        TopicSweep.emptyCluster.asRight[KuiError].pure[F]
      case Right(names) => describe(connection, names).map(_.asRight[KuiError])
    }

  private def listTopics(connection: ClusterConnection): F[Either[KuiError, List[TopicName]]] =
    pool
      .run(connection, ListTopics) { admin =>
        KafkaFutures
          .fromFuture(
            // Internal topics included. `__consumer_offsets` holds fifty partitions on most clusters and
            // an under-replicated one is an outage; a cluster-wide count that quietly excluded it would
            // disagree with the broker's own `UnderReplicatedPartitions` gauge by exactly the partitions
            // an operator most needs to see.
            Async[F].delay(admin.listTopics(new ListTopicsOptions().listInternal(true)).names())
          )
          .map(_.asScala.toList.map(TopicName.unsafe))
      }
      .attempt
      .map(
        _.leftMap(failure => KafkaErrorMapper.map(ListTopics, failure, connection.admin.apiTimeout.toMillis))
      )

  /** Chunked, with a bounded number of chunks in flight, through the batching every other large admin call in
    * this repository goes through. A `describeTopics` of ten thousand names is one response the broker has to
    * build in memory before it can send any of it.
    */
  private def describe(connection: ClusterConnection, names: List[TopicName]): F[TopicSweep] =
    AdminBatch
      .chunked[F, TopicName, PartitionCensus](
        names,
        AdminBatch.topicChunk(connection.admin),
        connection.admin.parallelism,
        DescribeTopics
      )(chunk => describeChunk(connection, chunk))
      .map { batch =>
        TopicSweep(
          census = batch.values.values.foldLeft(PartitionCensus.empty)(_.combine(_)),
          topics = names.size,
          unreadable = batch.skipped.keySet
        )
      }

  /** One chunk. A topic whose own future failed is simply left out of the map, which `AdminBatch` turns into
    * a skip for that key — so one unauthorized topic costs its own row and not the chunk's.
    */
  private def describeChunk(
      connection: ClusterConnection,
      chunk: List[TopicName]
  ): F[Map[TopicName, PartitionCensus]] =
    pool.run(connection, DescribeTopics) { admin =>
      val result = admin.describeTopics(TopicCollection.ofTopicNames(chunk.map(_.value).asJava))

      result.topicNameValues.asScala.toList
        .traverse { case (raw, future) =>
          KafkaFutures
            .fromFuture(Async[F].delay(future))
            .map(description => Option(TopicName.unsafe(raw) -> censusOf(description)))
            .handleErrorWith(failure =>
              logger
                .debug(failure)(s"topic '$raw' could not be described; its partitions will not be counted")
                .as(None)
            )
        }
        .map(_.flatten.toMap)
    }
}

object KafkaPartitionSweeper {

  /** The operation labels. They are the `AdminClientPool` metric attribute and must come from a short closed
    * set, which is why they are constants rather than literals at the call sites.
    */
  val ListTopics: String = "listTopics"
  val DescribeTopics: String = "describeTopics"

  /** One topic's partitions, counted. */
  def censusOf(description: TopicDescription): PartitionCensus =
    PartitionCensus.of(description.partitions.asScala.iterator.map(placementOf))

  /** One partition's placement, out of Kafka's vocabulary.
    *
    * Two shapes have to be named here and both are ordinary. A partition with no leader arrives either as a
    * `null` node or as `Node.noNode()`, whose id is `-1`; both mean "being elected, or every replica is
    * offline", and neither is a broker. And a node that is not `isEmpty` can still be a real broker that is
    * currently down, which is why the only thing rejected is the sentinel.
    */
  def placementOf(info: TopicPartitionInfo): PartitionPlacement =
    PartitionPlacement(
      leader = brokerOf(info.leader),
      replicas = info.replicas.asScala.flatMap(brokerOf).toSet,
      inSync = info.isr.asScala.flatMap(brokerOf).toSet
    )

  private def brokerOf(node: Node): Option[BrokerId] =
    Option(node).filterNot(_.isEmpty).map(found => BrokerId.unsafe(found.id))
}
