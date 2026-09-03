package kui.ui.clusters.brokers

import munit.FunSuite

import kui.contracts.cluster.{BrokerDto, ClusterSummaryDto}
import kui.ui.clusters.dashboard.ClusterFixtures

class BrokerRowSuite extends FunSuite {

  private def broker(
      id: Int,
      rack: Option[String] = Some("rack-a"),
      isController: Boolean = false,
      disk: Option[Long] = Some(1024L),
      segments: Option[Int] = Some(12),
      replicas: Option[Int] = Some(30),
      inSync: Option[Int] = Some(30),
      leaders: Option[Int] = Some(10),
      replicaSkew: Option[Double] = None,
      leaderSkew: Option[Double] = None
  ): BrokerDto =
    BrokerDto(
      id = ClusterFixtures.brokerId(id),
      host = s"broker-$id.example",
      port = 9092,
      rack = rack,
      isController = isController,
      partitionCount = replicas,
      leaderCount = leaders,
      inSyncReplicaCount = inSync,
      replicaSkewPercent = replicaSkew,
      leaderSkewPercent = leaderSkew,
      diskUsageBytes = disk,
      segmentCount = segments
    )

  test("theControllerRowIsMarkedAndOnlyOne") {
    val rows = BrokerRow.of(List(broker(1), broker(2, isController = true), broker(3)))
    assertEquals(rows.count(_.isController), 1)
    assertEquals(rows.find(_.isController).map(_.brokerId.value), Some(2))
  }

  test("aBrokerWithNoRackHasNoRackRatherThanAnEmptyOne") {
    // An empty string would sort between two real racks and read as a rack whose name nobody typed.
    assertEquals(BrokerRow.of(List(broker(1, rack = None))).head.rack, None)
    assertEquals(BrokerRow.of(List(broker(1, rack = Some("   ")))).head.rack, None)
  }

  test("aMissingLogDirEntryLeavesDiskUnknownRatherThanZero") {
    // The partly-authorised cluster: `describeCluster` allowed, `describeLogDirs` refused. The broker is
    // still a row; its disk is unknown, and unknown is not empty.
    val row = BrokerRow.of(List(broker(1, disk = None, segments = None))).head
    assertEquals(row.diskUsageBytes, None)
    assertEquals(row.segmentCount, None)
  }

  test("rowsPreserveTheResponseOrderBeforeSorting") {
    val rows = BrokerRow.of(List(broker(3), broker(1), broker(2)))
    assertEquals(rows.map(_.brokerId.value), List(3, 1, 2))
  }

  test("theServersOwnSkewIsPreferredAndTheBrowsersIsTheFallback") {
    // One rounding, wherever the figure is read from. The browser computes it only when the service did
    // not, so a deployment whose service predates the field still shows a number.
    val fromServer = BrokerRow.of(List(broker(1, replicaSkew = Some(42.0)), broker(2, replicas = Some(10))))
    assertEquals(fromServer.head.replicaSkewPercent, Some(42.0))

    val computed = BrokerRow.of(List(broker(1, replicas = Some(30)), broker(2, replicas = Some(10))))
    assertEquals(computed.head.replicaSkewPercent.map(math.round), Some(50L))
    assertEquals(computed(1).replicaSkewPercent, None)
  }
}

class BrokerSummarySuite extends FunSuite {

  private def summary(
      controller: Boolean = true,
      offline: Option[Int] = Some(0),
      inSync: Option[Int] = Some(30),
      replicas: Option[Int] = Some(30)
  ): BrokerSummary = {
    val dto = BrokerDto(
      id = ClusterFixtures.brokerId(1),
      host = "broker-1.example",
      port = 9092,
      rack = None,
      isController = controller,
      partitionCount = replicas,
      leaderCount = Some(10),
      inSyncReplicaCount = inSync,
      replicaSkewPercent = None,
      leaderSkewPercent = None,
      diskUsageBytes = Some(1024L),
      segmentCount = Some(1)
    )
    val cluster = ClusterSummaryDto(
      kafkaClusterId = None,
      version = Some("4.0.0"),
      controllerId = None,
      controllerKind = ClusterSummaryDto.KRaft,
      brokerCount = 1,
      onlinePartitionCount = Some(10),
      offlinePartitionCount = offline,
      underReplicatedPartitionCount = Some(0),
      totalDiskUsageBytes = Some(1024L),
      features = Nil,
      scrapedAt = ClusterFixtures.scrapedAt
    )
    BrokerSummary.of(List(dto), Some(cluster))
  }

  test("noControllerIsAnAlarm") {
    assert(BrokerSummary.hasAlarm(summary(controller = false)))
  }

  test("anyOfflinePartitionIsAnAlarm") {
    assert(BrokerSummary.hasAlarm(summary(offline = Some(1))))
  }

  test("aHealthyClusterIsNotAnAlarm") {
    assert(!BrokerSummary.hasAlarm(summary()))
  }

  test("inSyncBelowTotalIsReportedAndIsNotAnAlarm") {
    // Less redundancy than the cluster asked for is a warning colour. It is still serving every partition,
    // which is what separates it from an offline one.
    val current = summary(inSync = Some(28), replicas = Some(30))
    assertEquals(current.inSyncReplicas, Some(28))
    assertEquals(current.totalReplicas, Some(30))
    assert(!BrokerSummary.hasAlarm(current))
  }

  test("theControllerTypeIsNamedAsAPersonWouldWriteIt") {
    assertEquals(summary().controllerType, Some("KRaft"))
  }

  test("aSumOverBrokersThatReportedNothingIsUnknownRatherThanZero") {
    val current = BrokerSummary.of(
      List(
        BrokerDto(
          ClusterFixtures.brokerId(1),
          "broker-1.example",
          9092,
          None,
          isController = true,
          partitionCount = None,
          leaderCount = None,
          inSyncReplicaCount = None,
          replicaSkewPercent = None,
          leaderSkewPercent = None,
          diskUsageBytes = None,
          segmentCount = None
        )
      ),
      None
    )
    assertEquals(current.inSyncReplicas, None)
    assertEquals(current.totalReplicas, None)
  }
}
