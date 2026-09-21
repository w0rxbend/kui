package kui.cluster.domain

import kui.kernel.{BrokerId, TopicName}

/** The fold behind every partition figure the cluster service publishes, and the refusal in front of it.
  *
  * The refusal is what these cases are mostly about. A census is easy arithmetic; the thing that can be wrong
  * is publishing it when the sweep it came from was not whole, and that mistake is invisible on screen
  * because a partial sum is still a number.
  */
final class PartitionCensusSuite extends munit.FunSuite {

  private def broker(id: Int): BrokerId = BrokerId.unsafe(id)

  private val healthy = List(
    TopologyFixtures.placement(leader = 1),
    TopologyFixtures.placement(leader = 2),
    TopologyFixtures.placement(leader = 3),
    TopologyFixtures.placement(leader = 1)
  )

  test("aCompleteSweepCountsEveryPartitionOnceAndAttributesItToEveryReplica") {
    val census = PartitionCensus.of(healthy)

    assertEquals(census.partitions, 4)
    assertEquals(census.online, 4)
    assertEquals(census.offline, 0)
    assertEquals(census.underReplicated, 0)
    // Every partition of the fixture is replicated on all three brokers, so each hosts all four.
    assertEquals(List(1, 2, 3).map(id => census.hosted(broker(id))), List(4, 4, 4))
    // And the leaders add up to the partition count, which is the invariant a broker table is read against:
    // a cluster's leader counts summed across its brokers is its partition count, never more.
    assertEquals(List(1, 2, 3).map(id => census.led(broker(id))).sum, census.partitions)
    assertEquals(census.led(broker(1)), 2)
  }

  test("aPartitionWithFewerInSyncThanAssignedIsUnderReplicated") {
    val census = PartitionCensus.of(
      List(
        TopologyFixtures.placement(leader = 1, inSync = Some(List(1, 2))),
        TopologyFixtures.placement(leader = 2)
      )
    )

    assertEquals(census.underReplicated, 1)
    // Still online: a partition can be under-replicated and perfectly readable, and conflating the two
    // would report an ordinary follower restart as an outage.
    assertEquals(census.online, 2)
  }

  test("aLeaderlessPartitionIsOfflineAndIsAttributedToNoBroker") {
    val census = PartitionCensus.of(
      List(PartitionPlacement(leader = None, replicas = Set(broker(1), broker(2)), inSync = Set.empty))
    )

    assertEquals(census.offline, 1)
    assertEquals(census.online, 0)
    assertEquals(census.ledByBroker, Map.empty[BrokerId, Int])
    // It is still *hosted*: the replicas exist on those disks whether or not one of them is leading.
    assertEquals(census.hosted(broker(1)), 1)
  }

  test("aBrokerHostingNothingCountsZeroRatherThanBeingAbsentFromTheAnswer") {
    // The distinction the whole design turns on. Once the sweep is complete a broker that leads nothing has
    // been *measured* as leading nothing, and the row shows `0`. Absence is reserved for a sweep that was
    // not complete, which never produces a census at all.
    val census = PartitionCensus.of(List(TopologyFixtures.placement(leader = 1, replicas = List(1))))

    assertEquals(census.led(broker(9)), 0)
    assertEquals(census.hosted(broker(9)), 0)
  }

  test("combineFoldsTwoChunksWithoutLosingABrokerFromEitherSide") {
    val left = PartitionCensus.of(List(TopologyFixtures.placement(leader = 1, replicas = List(1, 2))))
    val right = PartitionCensus.of(List(TopologyFixtures.placement(leader = 3, replicas = List(2, 3))))
    val whole = left.combine(right)

    assertEquals(whole.partitions, 2)
    assertEquals(whole.hosted(broker(2)), 2)
    assertEquals(whole.led(broker(1)), 1)
    assertEquals(whole.led(broker(3)), 1)
    // Folding in the other order must give the same answer, or a chunked sweep would depend on which
    // chunk the broker happened to finish first.
    assertEquals(right.combine(left), whole)
  }

  test("aSweepWithOneUnreadableTopicYieldsNoCensusAtAll") {
    // The packet's whole point. The census inside the sweep is a real fold over the topics that *did*
    // answer, and it must not be reachable: a total over 3,998 of 4,000 topics looks measured and is
    // wrong in the direction that reassures.
    val partial = TopicSweep(
      census = PartitionCensus.of(healthy),
      topics = 5,
      unreadable = Set(TopicName.unsafe("payments"))
    )

    assert(!partial.isComplete)
    assertEquals(partial.complete, None)
    assertEquals(partial.described, 4)
  }

  test("aCompleteSweepYieldsItsCensus") {
    val whole = TopicSweep(PartitionCensus.of(healthy), topics = 4, unreadable = Set.empty)

    assert(whole.isComplete)
    assertEquals(whole.complete.map(_.partitions), Some(4))
    assertEquals(whole.described, 4)
  }

  test("aClusterWithNoTopicsIsCompletelyCountedAndCountsZero") {
    // Not the same as a refusal, and the difference is the difference between "this cluster has no topics"
    // and "KUI could not count them".
    assertEquals(TopicSweep.emptyCluster.complete, Some(PartitionCensus.empty))
    assertEquals(TopicSweep.emptyCluster.census.partitions, 0)
  }
}
