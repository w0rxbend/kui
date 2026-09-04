package kui.kafka.admin

import scala.jdk.CollectionConverters.*

import cats.effect.IO
import munit.ScalaCheckSuite
import org.apache.kafka.clients.admin.{AlterConsumerGroupOffsetsResult, DeleteConsumerGroupsResult}
import org.apache.kafka.common.errors.*
import org.apache.kafka.common.protocol.Errors
import org.apache.kafka.common.{KafkaFuture, TopicPartition as KafkaTopicPartition}
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import kui.kafka.KafkaErrorMapper
import kui.kernel.ClusterId
import kui.kernel.cluster.{AdminTuning, BootstrapServers, ClientProperties, ClusterConnection, ClusterSecurity}
import kui.kernel.error.ErrorCode
import kui.kernel.{GroupId, Offset, PartitionId, TopicName, TopicPartition}
import cats.effect.unsafe.implicits.global

/** What the three mutations do, and — mostly — what they say when the cluster refuses.
  *
  * The refusal mapping is the substance. `KUI-INVALID-STATE` is true of every one of these and useful for
  * none: the operator wants to be told that the group still has members and that stopping its consumers is
  * the fix.
  */
final class GroupMutationsSuite extends ScalaCheckSuite {

  private val connection: ClusterConnection = ClusterConnection(
    id = ClusterId.unsafe("prod"),
    bootstrapServers = BootstrapServers.unsafe("broker:9092"),
    security = ClusterSecurity.Plaintext,
    overrides = ClientProperties.empty,
    admin = AdminTuning.default
  )

  private val orders: GroupId = GroupId.unsafe("orders")

  private def partition(n: Int): TopicPartition =
    TopicPartition(TopicName.unsafe("orders"), PartitionId.unsafe(n))

  private def alterResult(failure: Option[Throwable]): AlterConsumerGroupOffsetsResult = {
    val outcome =
      failure match {
        case None =>
          KafkaFuture.completedFuture(Map.empty[KafkaTopicPartition, Errors].asJava)
        case Some(error) =>
          val future = new org.apache.kafka.common.internals.KafkaFutureImpl[java.util.Map[KafkaTopicPartition, Errors]]
          future.completeExceptionally(error)
          future
      }

    val constructor = classOf[AlterConsumerGroupOffsetsResult].getDeclaredConstructors.head
    constructor.setAccessible(true)
    constructor.newInstance(outcome).asInstanceOf[AlterConsumerGroupOffsetsResult]
  }

  private def alterPort(failure: Option[Throwable]) =
    KafkaGroupAdmin[IO](
      StubAdmin.pool(StubAdmin { case "alterConsumerGroupOffsets" => alterResult(failure) })
    )

  test("altering no offsets makes no call and is not a failure") {
    val exploding = KafkaGroupAdmin[IO](StubAdmin.pool(StubAdmin(PartialFunction.empty)))
    assertEquals(exploding.alterOffsets(connection, orders, Map.empty).unsafeRunSync(), Right(()))
  }

  test("altering offsets on an empty group succeeds") {
    val answer =
      alterPort(None).alterOffsets(connection, orders, Map(partition(0) -> Offset.unsafe(12L))).unsafeRunSync()
    assertEquals(answer, Right(()))
  }

  test("a group that still has members is refused with a code that names the remedy") {
    val answer = alterPort(Some(new GroupNotEmptyException("orders has 3 members")))
      .alterOffsets(connection, orders, Map(partition(0) -> Offset.unsafe(12L)))
      .unsafeRunSync()

    assertEquals(answer.left.map(_.code), Left(ErrorCode.GroupNotEmpty))
    assert(answer.left.exists(_.message.contains("stop its consumers")))
  }

  test("a member id the coordinator does not recognise means the same thing: the group is live") {
    val answer = alterPort(Some(new UnknownMemberIdException("m-1")))
      .alterOffsets(connection, orders, Map(partition(0) -> Offset.unsafe(12L)))
      .unsafeRunSync()

    assertEquals(answer.left.map(_.code), Left(ErrorCode.GroupNotEmpty))
  }

  test("deleting three groups where one refuses deletes two and reports the third") {
    val futures = Map(
      "orders" -> KafkaFuture.completedFuture(null.asInstanceOf[Void]),
      "audit" -> KafkaFuture.completedFuture(null.asInstanceOf[Void]),
      "live" -> {
        val future = new org.apache.kafka.common.internals.KafkaFutureImpl[Void]
        future.completeExceptionally(new GroupIdNotFoundException("live"))
        future
      }
    ).asJava

    val constructor = classOf[DeleteConsumerGroupsResult].getDeclaredConstructors.head
    constructor.setAccessible(true)
    val result = constructor.newInstance(futures).asInstanceOf[DeleteConsumerGroupsResult]

    val port = KafkaGroupAdmin[IO](
      StubAdmin.pool(StubAdmin { case "deleteConsumerGroups" => result })
    )

    val answer = port
      .deleteGroups(connection, List(orders, GroupId.unsafe("audit"), GroupId.unsafe("live")))
      .unsafeRunSync()

    answer match {
      case Right(batch) =>
        assertEquals(batch.values.keySet.map(_.value), Set("orders", "audit"))
        assertEquals(batch.skipped.keySet.map(_.value), Set("live"))
      case Left(error) => fail(s"one refusal failed the whole batch: $error")
    }
  }

  property("the group mapping is total over the exceptions these operations throw, and never invents a 500") {
    val refusals = Gen.oneOf[Throwable](
      new GroupNotEmptyException("x"),
      new UnknownMemberIdException("x"),
      new IllegalGenerationException("x"),
      new GroupIdNotFoundException("x"),
      new GroupSubscribedToTopicException("x"),
      new GroupAuthorizationException("x"),
      new TopicAuthorizationException("x"),
      new UnknownTopicOrPartitionException("x"),
      new OffsetOutOfRangeException("x")
    )

    forAll(refusals) { failure =>
      KafkaErrorMapper.mapGroupError(failure) match {
        case Some(error) => assert(error.code.httpStatus < 500, s"${error.code.wire} is a server error")
        case None => fail(s"${failure.getClass.getSimpleName} is not mapped")
      }
    }
  }

  test("an exception these operations do not have a rule for falls through to the general mapping") {
    assertEquals(KafkaErrorMapper.mapGroupError(new TimeoutException("slow")), None)
  }
}
