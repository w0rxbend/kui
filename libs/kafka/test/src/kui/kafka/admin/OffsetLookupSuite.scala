package kui.kafka.admin

import scala.jdk.CollectionConverters.*

import cats.effect.{IO, Ref}
import org.apache.kafka.clients.admin.{
  Admin,
  DescribeTopicsResult,
  ListOffsetsResult,
  TopicDescription
}
import org.apache.kafka.common.{KafkaFuture, Node, TopicPartition as KafkaTopicPartition, TopicPartitionInfo}

import kui.kafka.SkipReason
import kui.kernel.ClusterId
import kui.kernel.cluster.{AdminTuning, BootstrapServers, ClientProperties, ClusterConnection, ClusterSecurity}
import kui.kernel.{Offset, PartitionId, TopicName, TopicPartition}
import kui.testkit.KuiIOSuite

/** The sixty-second timeout, and the code that stops it happening.
  *
  * A `listOffsets` request naming a partition with no leader does not fail: the client retries metadata
  * silently until `default.api.timeout.ms` expires. So the assertions here are as much about the call that is
  * *not* made as about the answers that come back.
  */
final class OffsetLookupSuite extends KuiIOSuite {

  private val connection: ClusterConnection = ClusterConnection(
    id = ClusterId.unsafe("prod"),
    bootstrapServers = BootstrapServers.unsafe("broker:9092"),
    security = ClusterSecurity.Plaintext,
    overrides = ClientProperties.empty,
    admin = AdminTuning.default
  )

  private val leader = new Node(1, "broker", 9092)

  private def partition(n: Int): TopicPartition =
    TopicPartition(TopicName.unsafe("orders"), PartitionId.unsafe(n))

  private def kafkaPartition(n: Int): KafkaTopicPartition = new KafkaTopicPartition("orders", n)

  private def info(n: Int, hasLeader: Boolean): TopicPartitionInfo =
    new TopicPartitionInfo(
      n,
      if hasLeader then leader else null,
      List(leader).asJava,
      List(leader).asJava
    )

  private def describeTopics(partitions: List[TopicPartitionInfo]): DescribeTopicsResult = {
    val description = new TopicDescription("orders", false, partitions.asJava)
    // `ofTopicNames` is package-private: the client builds these results and applications read them.
    val factory = classOf[DescribeTopicsResult]
      .getDeclaredMethods
      .find(m => m.getName == "ofTopicNames")
      .getOrElse(fail("kafka-clients no longer offers DescribeTopicsResult.ofTopicNames"))
    factory.setAccessible(true)
    factory
      .invoke(null, Map("orders" -> KafkaFuture.completedFuture(description)).asJava)
      .asInstanceOf[DescribeTopicsResult]
  }

  private def listOffsets(offsets: Map[KafkaTopicPartition, Long]): ListOffsetsResult = {
    val infos = offsets.map { (tp, offset) =>
      tp -> new ListOffsetsResult.ListOffsetsResultInfo(offset, -1L, java.util.Optional.empty())
    }
    val constructor = classOf[ListOffsetsResult].getDeclaredConstructors.head
    constructor.setAccessible(true)
    constructor
      .newInstance(infos.map((tp, resultInfo) => tp -> KafkaFuture.completedFuture(resultInfo)).asJava)
      .asInstanceOf[ListOffsetsResult]
  }

  /** Records whether `listOffsets` was called at all, which is the assertion for a fully offline topic. */
  private def lookupWith(
      partitions: List[TopicPartitionInfo],
      offsets: Map[KafkaTopicPartition, Long]
  ): IO[(OffsetLookup[IO], Ref[IO, Int])] =
    Ref.of[IO, Int](0).map { calls =>
      val admin: Admin = StubAdmin {
        case "describeTopics" => describeTopics(partitions)
        case "listOffsets" =>
          calls.update(_ + 1).unsafeRunSyncHere()
          listOffsets(offsets)
      }

      (OffsetLookup.make[IO](StubAdmin.pool(admin)), calls)
    }

  extension (io: IO[Unit]) {
    private def unsafeRunSyncHere(): Unit = {
      import cats.effect.unsafe.implicits.global
      io.unsafeRunSync()
    }
  }

  test("a leaderless partition is filtered out before the request and returned as a skip") {
    for {
      pair <- lookupWith(
        List(info(0, hasLeader = true), info(1, hasLeader = false)),
        Map(kafkaPartition(0) -> 100L)
      )
      (lookup, calls) = pair
      answer <- lookup.endOffsets(connection, Set(partition(0), partition(1)))
      made <- calls.get
    } yield {
      assertEquals(answer.map(_.values), Right(Map(partition(0) -> Offset.unsafe(100L))))
      assertEquals(answer.map(_.skipped), Right(Map(partition(1) -> SkipReason.NoLeader)))
      assertEquals(made, 1)
    }
  }

  test("a request whose every partition is leaderless makes no Kafka call at all") {
    for {
      pair <- lookupWith(List(info(0, hasLeader = false)), Map.empty)
      (lookup, calls) = pair
      answer <- lookup.endOffsets(connection, Set(partition(0)))
      made <- calls.get
    } yield {
      assertEquals(answer.map(_.values), Right(Map.empty[TopicPartition, Offset]))
      assertEquals(answer.map(_.skipped), Right(Map(partition(0) -> SkipReason.NoLeader)))
      assertEquals(made, 0, clue = "the one-minute timeout was paid for a question metadata had already answered")
    }
  }

  test("no record at or after the timestamp is None, not the end offset") {
    for {
      pair <- lookupWith(List(info(0, hasLeader = true)), Map(kafkaPartition(0) -> -1L))
      (lookup, _) = pair
      answer <- lookup.offsetsForTimes(connection, Map(partition(0) -> 1700000000000L))
    } yield assertEquals(answer.map(_.values), Right(Map(partition(0) -> None)))
  }

  test("asking for nothing makes no call") {
    val exploding = OffsetLookup.make[IO](StubAdmin.pool(StubAdmin(PartialFunction.empty)))
    exploding
      .endOffsets(connection, Set.empty)
      .map(answer => assertEquals(answer.map(_.isEmpty), Right(true)))
  }

  test("leaderless names the offline partitions, so a reset can refuse before it plans") {
    for {
      pair <- lookupWith(List(info(0, hasLeader = true), info(1, hasLeader = false)), Map.empty)
      (lookup, _) = pair
      offline <- lookup.leaderless(connection, Set(partition(0), partition(1)))
    } yield assertEquals(offline, Right(Set(partition(1))))
  }
}
