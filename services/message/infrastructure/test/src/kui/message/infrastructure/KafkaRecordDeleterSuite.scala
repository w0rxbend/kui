package kui.message.infrastructure

import java.util.concurrent.atomic.AtomicReference

import scala.jdk.CollectionConverters.*

import cats.effect.IO
import org.apache.kafka.clients.admin.{
  Admin,
  Config,
  ConfigEntry,
  DeletedRecords,
  DeleteRecordsResult,
  DescribeTopicsResult,
  KuiMessageAdminResults,
  ListOffsetsResult,
  TopicDescription
}
import org.apache.kafka.common.config.ConfigResource
import org.apache.kafka.common.errors.PolicyViolationException
import org.apache.kafka.common.{KafkaFuture, Node, TopicCollection, TopicPartition, TopicPartitionInfo}

import kui.kernel.cluster.{AdminTuning, BootstrapServers, ClientProperties, ClusterConnection, ClusterSecurity}
import kui.kernel.{ClusterId, Offset, PartitionId, TopicName}
import kui.testkit.KuiIOSuite
import kui.testkit.fakes.FakeStructuredLogger

/** The purge adapter, driven through an `Admin` rather than around one.
  *
  * `KafkaRecordDeleter` shipped with no suite at all, and it could not have had one: this module had no
  * Kafka `Admin` stub, so every rule in it — the leaderless filter, the compaction warning that must not
  * cost the plan, the per-partition split of a `deleteRecords` answer — was reachable only through a route
  * that asserts a status code. `StubAdmin` and `DirectAdminPool` beside this file are that stub.
  *
  * All three rules are ones where the wrong behaviour looks like success. Two of them are about an operator
  * who is one click from destroying records irreversibly, which is why the class exists in its own file at
  * all.
  */
final class KafkaRecordDeleterSuite extends KuiIOSuite {

  private val cluster: ClusterId = ClusterId.unsafe("local")
  private val elsewhere: ClusterId = ClusterId.unsafe("staging")
  private val orders: TopicName = TopicName.unsafe("orders.v1")

  private val broker: Node = new Node(1, "broker-1.example", 9092)

  private val connection: ClusterConnection = ClusterConnection(
    id = cluster,
    bootstrapServers = BootstrapServers.unsafe("broker-1:9092"),
    security = ClusterSecurity.Plaintext,
    overrides = ClientProperties.empty,
    admin = AdminTuning.default
  )

  private def deleterOver(admin: Admin): IO[KafkaRecordDeleter[IO]] =
    FakeStructuredLogger[IO].map(logger =>
      new KafkaRecordDeleter[IO](
        new DirectAdminPool(admin),
        id => Option.when(id == cluster)(connection),
        logger
      )
    )

  private def partition(id: Int, leader: Node | Null): TopicPartitionInfo =
    new TopicPartitionInfo(id, leader, List(broker).asJava, List(broker).asJava)

  private def described(partitions: List[TopicPartitionInfo]): DescribeTopicsResult =
    KuiMessageAdminResults.describeTopics(
      Map(
        orders.value -> KafkaFuture.completedFuture(
          new TopicDescription(orders.value, false, partitions.asJava)
        )
      ).asJava
    )

  /** `listOffsets` answering `earliest` as 0 and `latest` as 100 for whatever it is asked about, and
    * remembering which partitions those were.
    *
    * The record of what was asked is the point of the first case: a leaderless partition that reaches a
    * `listOffsets` does not fail the call, it makes the AdminClient retry metadata quietly until
    * `default.api.timeout.ms` runs out — a millisecond call turned into a minute of nothing, exactly while
    * a broker is down and the screen matters most.
    */
  private def offsetsFor(spec: String, asked: AtomicReference[List[TopicPartition]])(
      request: java.util.Map[?, ?]
  ): ListOffsetsResult = {
    val partitions = request.keySet.asScala.toList.collect { case p: TopicPartition => p }
    val _ = asked.updateAndGet(_ ++ partitions)

    new ListOffsetsResult(
      partitions
        .map(p =>
          p -> KafkaFuture.completedFuture(
            new ListOffsetsResult.ListOffsetsResultInfo(
              if spec == "earliest" then 0L else 100L,
              0L,
              java.util.Optional.empty[Integer]
            )
          )
        )
        .toMap
        .asJava
    )
  }

  // -------------------------------------------------------------------------- the leaderless partition

  test("a partition with no leader is left out of the plan and never asked for its offsets") {
    /*
     * Ungated until now: dropping `.filter(info => Option(info.leader).exists(_.id >= 0))` left
     * `./mill services.message.__.test` at 1442/1442 green, because nothing in this module could build an
     * `Admin` to drive it. The same rule is gated twice over in `libs/kafka`; this is the copy the message
     * service carries, and a copy that nothing checks is the one that drifts.
     *
     * Both halves are asserted. The plan must not name the partition — a pair of offsets nobody could have
     * measured must never reach a screen an operator is about to confirm a deletion on — and the call must
     * not name it either, which is the sixty-second timeout `OffsetLookup`'s scaladoc describes.
     *
     * Kafka has two ways of saying "no leader" and the filter has to answer both: a `null`, and
     * `Node.noNode`, whose id is -1. A filter that checked only the null would put broker -1 on a plan.
     */
    val asked = new AtomicReference[List[TopicPartition]](Nil)

    val admin = StubAdmin {
      case ("describeTopics", (_: TopicCollection.TopicNameCollection) :: _) =>
        described(List(partition(0, broker), partition(1, null), partition(2, Node.noNode)))

      case ("listOffsets", (request: java.util.Map[?, ?]) :: rest) =>
        offsetsFor(if rest.toString.contains("Earliest") then "earliest" else "latest", asked)(request)
    }

    for {
      deleter <- deleterOver(admin)
      plan <- deleter.watermarks(cluster, orders)
    } yield {
      assertEquals(
        plan.map(_.map(_.partition.value)),
        Right(List(0)),
        clue = "a partition with no leader reached a purge plan with offsets nobody could have measured"
      )
      assertEquals(
        asked.get.map(_.partition).distinct.sorted,
        List(0),
        clue = "a leaderless partition was named in a listOffsets, which is a sixty-second timeout"
      )
    }
  }

  test("a cluster this deployment does not have is a not-found rather than a call") {
    for {
      deleter <- deleterOver(StubAdmin(PartialFunction.empty))
      plan <- deleter.watermarks(elsewhere, orders)
      deleted <- deleter.deleteBefore(elsewhere, orders, Map(PartitionId.unsafe(0) -> Offset.unsafe(1L)))
    } yield {
      assert(plan.isLeft, plan.toString)
      assert(deleted.isLeft, deleted.toString)
    }
  }

  // ----------------------------------------------------------------------------- the compaction warning

  test("a cluster that will not describe its configuration costs the warning, never the plan") {
    /*
     * Ungated until now: replacing `cleanupPolicy`'s `handleErrorWith` with anything that lets the failure
     * through left the suite green. The method's own comment states the rule at length — "a purge an
     * operator is entitled to make must not be refused because KUI could not read a setting it only wanted
     * in order to say something useful about it" — and nothing had ever made the call fail.
     *
     * `StubAdmin` refusing an unlisted method is what drives it: an admin client whose connection has gone
     * throws from `describeConfigs` itself rather than returning a failed future, which is the harder of
     * the two shapes and the one a `handleErrorWith` on the wrong side of a `delay` would miss.
     */
    for {
      deleter <- deleterOver(StubAdmin(PartialFunction.empty))
      policy <- deleter.cleanupPolicy(cluster, orders)
    } yield assertEquals(
      policy,
      None,
      clue = "a describeConfigs failure was allowed to decide whether a purge could be planned"
    )
  }

  test("a cleanup policy the broker does report is the one the plan warns about") {
    // The other half, so the case above cannot pass by never reading the policy at all.
    val admin = StubAdmin { case ("describeConfigs", (resources: java.util.Collection[?]) :: _) =>
      KuiMessageAdminResults.describeConfigs(
        resources.asScala.toList
          .collect { case resource: ConfigResource =>
            resource -> KafkaFuture.completedFuture(
              new Config(List(new ConfigEntry("cleanup.policy", "compact")).asJava)
            )
          }
          .toMap
          .asJava
      )
    }

    deleterOver(admin).flatMap(_.cleanupPolicy(cluster, orders)).map(assertEquals(_, Some("compact")))
  }

  // ------------------------------------------------------------------------- the per-partition answer

  test("a partition the broker refused is a skip beside the partitions that were emptied") {
    /*
     * Ungated until now: removing the per-partition `handleError` — so that one refused partition fails
     * the whole call — left the suite green.
     *
     * The class comment argues the rule at length: `deleteRecords` answers per partition and can succeed
     * on some and fail on others, so "reporting only the first failure would hide the fact that seven of
     * eight partitions *were* emptied". On an irreversible operation that is the difference between a
     * receipt and a mystery — the records are gone either way.
     */
    val refusal = new PolicyViolationException("broker policy refused kafka-1.internal:9093")

    val admin = StubAdmin { case ("deleteRecords", (request: java.util.Map[?, ?]) :: _) =>
      new DeleteRecordsResult(
        request.keySet.asScala.toList
          .collect { case p: TopicPartition => p }
          .map(p =>
            p -> (if p.partition == 1 then KuiMessageAdminResults.failed[DeletedRecords](refusal)
                  else KafkaFuture.completedFuture(new DeletedRecords(100L)))
          )
          .toMap
          .asJava
      )
    }

    for {
      deleter <- deleterOver(admin)
      result <- deleter.deleteBefore(
        cluster,
        orders,
        Map(
          PartitionId.unsafe(0) -> Offset.unsafe(100L),
          PartitionId.unsafe(1) -> Offset.unsafe(50L)
        )
      )
    } yield result match {
      case Left(error) => fail(s"one refused partition failed the whole purge: $error")
      case Right(purged) =>
        assertEquals(purged.newLowWatermarks, Map(PartitionId.unsafe(0) -> Offset.unsafe(100L)))
        assertEquals(purged.skipped.keySet, Set(PartitionId.unsafe(1)))
        // KUI's words and never the exception's: a Kafka exception's message routinely carries the
        // bootstrap string, and this one is the receipt an operator keeps.
        val reason = purged.skipped.getOrElse(PartitionId.unsafe(1), fail("no reason"))
        assertEquals(reason, "PolicyViolation")
        assert(!reason.contains("kafka-1.internal"), s"the bootstrap address reached the receipt: $reason")
    }
  }
}
