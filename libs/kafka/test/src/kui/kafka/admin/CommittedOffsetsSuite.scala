package kui.kafka.admin

import scala.jdk.CollectionConverters.*

import cats.effect.IO
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.errors.UnsupportedVersionException
import org.apache.kafka.clients.admin.internals.CoordinatorKey
import org.apache.kafka.common.{KafkaFuture, TopicPartition as KafkaTopicPartition}

import kui.kernel.ClusterId
import kui.kernel.cluster.{AdminTuning, BootstrapServers, ClientProperties, ClusterConnection, ClusterSecurity}
import kui.kernel.{GroupId, PartitionId, TopicName, TopicPartition}
import kui.testkit.KuiIOSuite

/** The one rule this call exists to protect: a partition the group has never committed on is *absent*, and
  * never present with a zero.
  *
  * `ConsumerGroupUtil.java:28-34` in the reference product sums committed offsets with `orElse(0)`. That is
  * the line that turns "this consumer has never run" into "this consumer is perfectly caught up" on the
  * screen an operator sizes a cluster from.
  */
final class CommittedOffsetsSuite extends KuiIOSuite {

  private val connection: ClusterConnection = ClusterConnection(
    id = ClusterId.unsafe("prod"),
    bootstrapServers = BootstrapServers.unsafe("broker:9092"),
    security = ClusterSecurity.Plaintext,
    overrides = ClientProperties.empty,
    admin = AdminTuning.default
  )

  private val orders: GroupId = GroupId.unsafe("orders")

  private def kafkaPartition(n: Int): KafkaTopicPartition = new KafkaTopicPartition("orders", n)

  private def partition(n: Int): TopicPartition =
    TopicPartition(TopicName.unsafe("orders"), PartitionId.unsafe(n))

  test("a partition with no commit is left out entirely, not reported as zero") {
    val raw = new java.util.HashMap[KafkaTopicPartition, OffsetAndMetadata]()
    raw.put(kafkaPartition(0), new OffsetAndMetadata(42L, "checkpoint"))
    // Kafka's own way of saying "this group has never committed here".
    raw.put(kafkaPartition(1), null)

    val committed = AdminConversions.committedOffsets(raw)

    assertEquals(committed.map(_.partition), List(partition(0)))
    assertEquals(committed.head.offset.value, 42L)
    assertEquals(committed.head.metadata, Some("checkpoint"))
  }

  test("empty commit metadata is None rather than an empty string on screen") {
    val raw = new java.util.HashMap[KafkaTopicPartition, OffsetAndMetadata]()
    raw.put(kafkaPartition(0), new OffsetAndMetadata(7L, ""))

    assertEquals(AdminConversions.committedOffsets(raw).head.metadata, None)
  }

  test("asking for no groups makes no call at all") {
    val exploding = KafkaGroupAdmin[IO](StubAdmin.pool(StubAdmin(PartialFunction.empty)))
    exploding
      .committedOffsets(connection, Nil, partitions = None, requireStable = true)
      .map(result => assertEquals(result.map(_.values.isEmpty), Right(true)))
  }

  test("a broker that refuses requireStable is asked again without it, not answered with an error") {
    val offsets = new java.util.HashMap[KafkaTopicPartition, OffsetAndMetadata]()
    offsets.put(kafkaPartition(0), new OffsetAndMetadata(9L))

    val admin = StubAdmin { case "listConsumerGroupOffsets" => offsetsResult(orders, offsets) }

    for {
      pool <- StubAdmin.failingOnce(new UnsupportedVersionException("requireStable is 2.6+"), admin)
      answer <- KafkaGroupAdmin[IO](pool)
        .committedOffsets(connection, List(orders), partitions = None, requireStable = true)
    } yield answer match {
      case Right(result) => assertEquals(result.values(orders).map(_.offset.value), List(9L))
      case Left(error) => fail(s"the downgrade did not happen: $error")
    }
  }

  /** `ListConsumerGroupOffsetsResult`'s constructor is package-private — the client builds these and
    * applications read them — so the one call that builds a fake is reflective and confined here.
    */
  private def offsetsResult(
      group: GroupId,
      offsets: java.util.Map[KafkaTopicPartition, OffsetAndMetadata]
  ): ListConsumerGroupOffsetsResult = {
    val futures = Map(
      CoordinatorKey.byGroupId(group.value) -> KafkaFuture.completedFuture(offsets)
    ).asJava

    val constructor = classOf[ListConsumerGroupOffsetsResult].getDeclaredConstructors.head
    constructor.setAccessible(true)
    constructor.newInstance(futures).asInstanceOf[ListConsumerGroupOffsetsResult]
  }
}
