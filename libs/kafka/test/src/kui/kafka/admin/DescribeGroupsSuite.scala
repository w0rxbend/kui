package kui.kafka.admin

import scala.jdk.CollectionConverters.*

import cats.effect.IO
import org.apache.kafka.clients.admin.{
  ConsumerGroupDescription,
  DescribeConsumerGroupsResult,
  MemberAssignment as KafkaMemberAssignment,
  MemberDescription
}
import org.apache.kafka.common.acl.AclOperation
import org.apache.kafka.common.errors.{GroupAuthorizationException, GroupIdNotFoundException}
import org.apache.kafka.common.{
  GroupState as KafkaGroupState,
  GroupType,
  KafkaFuture,
  Node,
  TopicPartition as KafkaTopicPartition
}

import kui.kafka.SkipReason
import kui.kernel.ClusterId
import kui.kernel.cluster.{AdminTuning, BootstrapServers, ClientProperties, ClusterConnection, ClusterSecurity}
import kui.kernel.group.{GroupProtocol, GroupState}
import kui.kernel.{GroupId, PartitionId, TopicName, TopicPartition}
import kui.testkit.KuiIOSuite

/** What a describe does with the three answers a real cluster gives: a group, a group that is not there, and
  * a group the caller may not see.
  *
  * The middle one is the port invariant. A broker newer than 2.4 throws `GroupIdNotFoundException` where an
  * older one answers with a dead group, and this suite is what proves the difference never leaves the
  * adapter.
  */
final class DescribeGroupsSuite extends KuiIOSuite {

  private val connection: ClusterConnection = ClusterConnection(
    id = ClusterId.unsafe("prod"),
    bootstrapServers = BootstrapServers.unsafe("broker:9092"),
    security = ClusterSecurity.Plaintext,
    overrides = ClientProperties.empty,
    admin = AdminTuning.default
  )

  private val orders: GroupId = GroupId.unsafe("orders")
  private val ghost: GroupId = GroupId.unsafe("ghost")
  private val secret: GroupId = GroupId.unsafe("secret")

  private def partition(n: Int): KafkaTopicPartition = new KafkaTopicPartition("orders", n)

  private def kuiPartition(n: Int): TopicPartition =
    TopicPartition(TopicName.unsafe("orders"), PartitionId.unsafe(n))

  private def member(
      id: String,
      held: Set[KafkaTopicPartition],
      target: Option[Set[KafkaTopicPartition]]
  ): MemberDescription =
    new MemberDescription(
      id,
      java.util.Optional.empty(),
      java.util.Optional.empty(),
      "client-1",
      "10.0.0.7",
      new KafkaMemberAssignment(held.asJava),
      java.util.Optional.ofNullable(target.map(t => new KafkaMemberAssignment(t.asJava)).orNull),
      java.util.Optional.empty(),
      java.util.Optional.empty()
    )

  private def described(
      members: List[MemberDescription],
      groupType: GroupType,
      operations: java.util.Set[AclOperation] | Null
  ): ConsumerGroupDescription =
    new ConsumerGroupDescription(
      "orders",
      false,
      members.asJava,
      "range",
      groupType,
      KafkaGroupState.STABLE,
      new Node(1, "broker", 9092),
      operations,
      java.util.Optional.empty(),
      java.util.Optional.empty()
    )

  private def port(answers: Map[String, KafkaFuture[ConsumerGroupDescription]]) = {
    val constructor = classOf[DescribeConsumerGroupsResult].getDeclaredConstructors.head
    constructor.setAccessible(true)
    val result = constructor.newInstance(answers.asJava).asInstanceOf[DescribeConsumerGroupsResult]
    KafkaGroupAdmin[IO](StubAdmin.pool(StubAdmin { case "describeConsumerGroups" => result }))
  }

  private def failed(failure: Throwable): KafkaFuture[ConsumerGroupDescription] = {
    val future = new KafkaFutureImplLike
    future.completeExceptionally(failure)
    future
  }

  /** A `KafkaFuture` that is already broken, which is what a per-group failure arrives as. */
  final private class KafkaFutureImplLike extends org.apache.kafka.common.internals.KafkaFutureImpl[ConsumerGroupDescription]

  test("a group that does not exist describes as a dead group, not an error") {
    port(Map("ghost" -> failed(new GroupIdNotFoundException("ghost")))).describeGroups(
      connection,
      List(ghost),
      includeAuthorizedOperations = false
    ).map {
      case Right(result) =>
        val fabricated = result.values(ghost)
        assertEquals(fabricated.state, GroupState.Dead)
        assertEquals(fabricated.members, Nil)
        assertEquals(result.skipped, Map.empty[GroupId, SkipReason])
      case Left(error) => fail(s"the invariant was not applied: $error")
    }
  }

  test("a group the caller may not see costs its own row, not the batch") {
    val answers = Map(
      "orders" -> KafkaFuture.completedFuture(described(Nil, GroupType.CLASSIC, null)),
      "secret" -> failed(new GroupAuthorizationException("no DESCRIBE on secret"))
    )

    port(answers).describeGroups(connection, List(orders, secret), includeAuthorizedOperations = false).map {
      case Right(result) =>
        assert(result.values.contains(orders))
        assert(result.skipped.contains(secret))
        assertEquals(result.requested, Set(orders, secret))
      case Left(error) => fail(s"one unauthorized group failed the batch: $error")
    }
  }

  test("a classic member's assignment is read, and it is never mid-rebalance") {
    val answers = Map(
      "orders" -> KafkaFuture.completedFuture(
        described(List(member("m-1", Set(partition(0), partition(1)), None)), GroupType.CLASSIC, null)
      )
    )

    port(answers).describeGroups(connection, List(orders), includeAuthorizedOperations = false).map {
      case Right(result) =>
        val group = result.values(orders)
        assertEquals(group.protocol, GroupProtocol.Classic)
        assertEquals(group.members.head.assignment.partitions, Set(kuiPartition(0), kuiPartition(1)))
        assertEquals(group.members.head.targetAssignment, None)
        assertEquals(group.coordinator.map(_.host), Some("broker"))
      case Left(error) => fail(s"expected a description, got $error")
    }
  }

  test("a KIP-848 member moving somewhere else keeps its target assignment") {
    val answers = Map(
      "orders" -> KafkaFuture.completedFuture(
        described(
          List(member("m-1", Set(partition(0)), Some(Set(partition(0), partition(1))))),
          GroupType.CONSUMER,
          null
        )
      )
    )

    port(answers).describeGroups(connection, List(orders), includeAuthorizedOperations = false).map {
      case Right(result) =>
        val member = result.values(orders).members.head
        assert(member.isRebalancing)
        assertEquals(member.targetAssignment.map(_.partitions), Some(Set(kuiPartition(0), kuiPartition(1))))
      case Left(error) => fail(s"expected a description, got $error")
    }
  }

  test("no authorizer and an empty permission set stay different answers") {
    val withAcls = Map(
      "orders" -> KafkaFuture.completedFuture(
        described(Nil, GroupType.CLASSIC, java.util.Set.of[AclOperation]())
      )
    )
    val withoutAcls = Map("orders" -> KafkaFuture.completedFuture(described(Nil, GroupType.CLASSIC, null)))

    for {
      on <- port(withAcls).describeGroups(connection, List(orders), includeAuthorizedOperations = true)
      off <- port(withoutAcls).describeGroups(connection, List(orders), includeAuthorizedOperations = true)
    } yield {
      assertEquals(on.map(_.values(orders).authorizedOperations), Right(Some(Set.empty[GroupOperation])))
      assertEquals(off.map(_.values(orders).authorizedOperations), Right(None))
    }
  }

  test("describing nothing makes no call at all") {
    val exploding = KafkaGroupAdmin[IO](StubAdmin.pool(StubAdmin(PartialFunction.empty)))
    exploding.describeGroups(connection, Nil, includeAuthorizedOperations = false).map { result =>
      assertEquals(result.map(_.values.isEmpty), Right(true))
    }
  }
}
