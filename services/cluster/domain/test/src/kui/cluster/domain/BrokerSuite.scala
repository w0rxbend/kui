package kui.cluster.domain

import cats.data.NonEmptyList

import kui.testkit.KuiSuite

/** The broker set and the four things about `describeCluster` that look wrong and are not.
  *
  * Tests 4 to 7 exist to stop a later reader "tightening" an invariant that Kafka itself does not hold. Each
  * of them is a state a reference product got wrong at least once, and each would present as an outage on a
  * perfectly healthy cluster.
  */
final class BrokerSuite extends KuiSuite {

  import TopologyFixtures.*

  private def describe(
      brokers: NonEmptyList[Broker],
      controller: Option[Broker] = None,
      kafkaClusterId: Option[kui.kernel.KafkaClusterId] = Some(kui.kernel.KafkaClusterId.unsafe("abc")),
      authorized: Option[Set[String]] = None
  ) =
    ClusterDescription.from(kafkaClusterId, controller, ControllerMode.KRaft, brokers, authorized)

  test("rackIsNoneRatherThanBlank") {
    assert(BrokerRack.from("").isLeft)
    assert(BrokerRack.from("   ").isLeft)
    assertEquals(BrokerRack.from(" eu-west-1a ").map(_.value), Right("eu-west-1a"))
  }

  test("brokersAreOrderedById") {
    val unsorted = List(broker(3), broker(1), broker(2))

    assertEquals(unsorted.sorted.map(_.id.value), List(1, 2, 3))
  }

  test("descriptionRejectsDuplicateBrokerIds") {
    val duplicated = NonEmptyList.of(broker(1), broker(1), broker(2))
    val result = describe(duplicated)

    assertEquals(result.left.toOption.map(_.details.size), Some(1))
    assert(result.left.toOption.exists(_.message.contains("1")))
  }

  test("descriptionAcceptsAControllerThatIsNotAmongTheBrokers") {
    // KRaft's dedicated controller: `process.roles=controller`, never present in `nodes()`.
    val result = describe(NonEmptyList.of(broker(1), broker(2)), controller = Some(broker(9)))

    assertEquals(result.map(_.controller.map(_.id.value)), Right(Some(9)))
  }

  test("descriptionAcceptsNoController") {
    // A controller failover in progress. `describeCluster().controller()` is `null`.
    assertEquals(describe(NonEmptyList.one(broker(1))).map(_.controller), Right(None))
  }

  test("descriptionAcceptsNoKafkaClusterId") {
    assertEquals(
      describe(NonEmptyList.one(broker(1)), kafkaClusterId = None).map(_.kafkaClusterId),
      Right(None)
    )
  }

  test("descriptionAcceptsNoAuthorizedOperations") {
    // ACLs disabled, or a broker older than 2.3.
    assertEquals(describe(NonEmptyList.one(broker(1))).map(_.authorizedOperations), Right(None))
  }
}
