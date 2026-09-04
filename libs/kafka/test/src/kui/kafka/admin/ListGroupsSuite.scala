package kui.kafka.admin

import scala.jdk.CollectionConverters.*

import cats.effect.IO
import org.apache.kafka.clients.admin.{GroupListing as KafkaGroupListing, ListGroupsResult}
import org.apache.kafka.common.errors.{CoordinatorNotAvailableException, UnsupportedVersionException}
import org.apache.kafka.common.{GroupState as KafkaGroupState, GroupType, KafkaFuture}

import kui.kernel.cluster.{AdminTuning, BootstrapServers, ClientProperties, ClusterConnection, ClusterSecurity}
import kui.kernel.error.ErrorCode
import kui.kernel.group.GroupState
import kui.kernel.ClusterId
import kui.testkit.KuiIOSuite

/** What `listGroups` does with what a cluster actually sends back.
  *
  * The interesting cases are all failure-shaped and none of them needs a broker: a coordinator that did not
  * answer, every coordinator failing, a broker too old for the state filter, and a share group that must not
  * appear in a consumer-group list. Arranging those on a live cluster means breaking one; arranging them here
  * means constructing a `ListGroupsResult`.
  */
final class ListGroupsSuite extends KuiIOSuite {

  private val connection: ClusterConnection = ClusterConnection(
    id = ClusterId.unsafe("prod"),
    bootstrapServers = BootstrapServers.unsafe("broker:9092"),
    security = ClusterSecurity.Plaintext,
    overrides = ClientProperties.empty,
    admin = AdminTuning.default
  )

  private def listing(id: String, state: KafkaGroupState, groupType: GroupType): KafkaGroupListing =
    new KafkaGroupListing(id, java.util.Optional.of(groupType), "consumer", java.util.Optional.of(state))

  /** `ListGroupsResult` partitions one collection into listings and failures, which is exactly the shape a
    * cluster with one unreachable coordinator produces.
    */
  private def result(entries: List[AnyRef]): ListGroupsResult = {
    // The constructor is package-private in kafka-clients: the client builds these, applications only read
    // them. Reaching it reflectively is confined to this one line, and it is what lets the partial-listing
    // and total-failure branches be tested at all without breaking a broker.
    val constructor = classOf[ListGroupsResult].getDeclaredConstructors.head
    constructor.setAccessible(true)
    constructor
      .newInstance(KafkaFuture.completedFuture(entries.asJava))
      .asInstanceOf[ListGroupsResult]
  }

  private def admin(entries: List[AnyRef]) =
    StubAdmin { case "listGroups" => result(entries) }

  private def port(entries: List[AnyRef]) =
    KafkaGroupAdmin[IO](StubAdmin.pool(admin(entries)))

  private val stable = listing("orders", KafkaGroupState.STABLE, GroupType.CLASSIC)
  private val empty = listing("audit", KafkaGroupState.EMPTY, GroupType.CONSUMER)
  private val share = listing("share-thing", KafkaGroupState.STABLE, GroupType.SHARE)

  test("it lists the consumer groups and drops the share groups, counting them") {
    port(List(stable, empty, share)).listGroups(connection, Set.empty).map {
      case Right(listed) =>
        assertEquals(listed.groups.map(_.groupId.value), List("audit", "orders"))
        assertEquals(listed.nonConsumerGroups, 1)
        assert(listed.isComplete)
      case Left(error) => fail(s"expected a listing, got $error")
    }
  }

  test("an empty state set means every state") {
    port(List(stable, empty)).listGroups(connection, Set.empty).map {
      case Right(listed) => assertEquals(listed.groups.size, 2)
      case Left(error) => fail(s"expected a listing, got $error")
    }
  }

  test("the in-memory filter narrows the result to the states asked for") {
    port(List(stable, empty)).listGroups(connection, Set(GroupState.Empty)).map {
      case Right(listed) => assertEquals(listed.groups.map(_.groupId.value), List("audit"))
      case Left(error) => fail(s"expected a listing, got $error")
    }
  }

  test("a group whose state the broker did not report survives a state filter") {
    val silent = new KafkaGroupListing(
      "mystery",
      java.util.Optional.of(GroupType.CLASSIC),
      "consumer",
      java.util.Optional.empty()
    )

    port(List(stable, silent)).listGroups(connection, Set(GroupState.Empty)).map {
      case Right(listed) =>
        assertEquals(listed.groups.map(_.groupId.value), List("mystery"))
        assertEquals(listed.groups.head.state, GroupState.Unknown)
      case Left(error) => fail(s"expected a listing, got $error")
    }
  }

  test("a broker that refuses the state filter is answered by filtering in memory instead") {
    for {
      pool <- StubAdmin.failingOnce(new UnsupportedVersionException("no state filter"), admin(List(stable, empty)))
      answer <- KafkaGroupAdmin[IO](pool).listGroups(connection, Set(GroupState.Empty))
    } yield answer match {
      case Right(listed) => assertEquals(listed.groups.map(_.groupId.value), List("audit"))
      case Left(error) => fail(s"expected the downgraded listing, got $error")
    }
  }

  test("one coordinator that did not answer is a skip, not a failure") {
    val entries = List[AnyRef](stable, new CoordinatorNotAvailableException("broker 3 is moving"))

    port(entries).listGroups(connection, Set.empty).map {
      case Right(listed) =>
        assertEquals(listed.groups.map(_.groupId.value), List("orders"))
        assertEquals(listed.coordinatorFailures.size, 1)
        assert(!listed.isComplete)
      case Left(error) => fail(s"expected a partial listing, got $error")
    }
  }

  test("every coordinator failing is a Left, because that is not an empty cluster") {
    val entries = List[AnyRef](new CoordinatorNotAvailableException("everything is moving"))

    port(entries).listGroups(connection, Set.empty).map {
      case Right(listed) => fail(s"expected a failure, got ${listed.groups.size} groups")
      case Left(error) => assertEquals(error.code, ErrorCode.UpstreamUnavailable)
    }
  }
}
