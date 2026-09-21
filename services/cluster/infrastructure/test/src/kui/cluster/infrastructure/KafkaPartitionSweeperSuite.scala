package kui.cluster.infrastructure

import scala.jdk.CollectionConverters.*

import org.apache.kafka.clients.admin.TopicDescription
import org.apache.kafka.common.{Node, TopicPartitionInfo}

import kui.kernel.BrokerId

/** The conversion out of Kafka's vocabulary, which is where every shape a real cluster produces has to be
  * named.
  *
  * It is a pure function on Java values, so the shapes that matter — a partition with no leader, a partition
  * whose ISR has shrunk, a partition whose leader is a node the cluster no longer lists — are constructible
  * here without a broker. The adapter around it is plumbing and is asserted separately.
  */
final class KafkaPartitionSweeperSuite extends munit.FunSuite {

  private def node(id: Int): Node = new Node(id, s"broker-$id.example", 9092)

  private def partition(index: Int, leader: Node, replicas: List[Int], isr: List[Int]): TopicPartitionInfo =
    new TopicPartitionInfo(index, leader, replicas.map(node).asJava, isr.map(node).asJava)

  private def topic(name: String, partitions: List[TopicPartitionInfo]): TopicDescription =
    new TopicDescription(name, false, partitions.asJava)

  test("aHealthyPartitionIsOnlineInSyncAndAttributedToItsLeader") {
    val placement = KafkaPartitionSweeper.placementOf(partition(0, node(1), List(1, 2, 3), List(1, 2, 3)))

    assertEquals(placement.leader, Some(BrokerId.unsafe(1)))
    assertEquals(placement.replicas.size, 3)
    assert(placement.isOnline)
    assert(!placement.isUnderReplicated)
  }

  test("aShrunkIsrIsUnderReplicatedAndStillOnline") {
    // The ordinary case of one follower restarting. It is not an outage and must not be counted as one.
    val placement = KafkaPartitionSweeper.placementOf(partition(0, node(1), List(1, 2, 3), List(1, 2)))

    assert(placement.isUnderReplicated)
    assert(placement.isOnline)
  }

  test("noNodeIsNotABrokerAndLeavesThePartitionOffline") {
    // `Node.noNode()` has id `-1`. Read as a broker id it would attribute every leaderless partition in the
    // cluster to a broker that does not exist, and the leader counts would stop adding up to the total.
    val placement = KafkaPartitionSweeper.placementOf(partition(0, Node.noNode(), List(1, 2), List(1)))

    assertEquals(placement.leader, None)
    assert(!placement.isOnline)
  }

  test("aNullLeaderIsTheSameAnswerAsNoNode") {
    // Some client versions hand back `null` rather than the sentinel, and a `NullPointerException` in a
    // sweep would take the whole cluster's figures down for a partition that is merely mid-election.
    val placement = KafkaPartitionSweeper.placementOf(partition(0, null, List(1), List(1)))

    assertEquals(placement.leader, None)
  }

  test("aTopicIsFoldedIntoOneCensusOverItsPartitions") {
    val census = KafkaPartitionSweeper.censusOf(
      topic(
        "orders",
        List(
          partition(0, node(1), List(1, 2), List(1, 2)),
          partition(1, node(2), List(1, 2), List(2)),
          partition(2, Node.noNode(), List(1, 2), List.empty)
        )
      )
    )

    assertEquals(census.partitions, 3)
    assertEquals(census.online, 2)
    assertEquals(census.offline, 1)
    // Two: the shrunk ISR, and the leaderless partition, which has no replica in sync at all.
    assertEquals(census.underReplicated, 2)
    assertEquals(census.hosted(BrokerId.unsafe(1)), 3)
    assertEquals(census.led(BrokerId.unsafe(1)), 1)
  }

  test("aTopicWithNoPartitionsCountsNothingRatherThanFailing") {
    assertEquals(KafkaPartitionSweeper.censusOf(topic("empty", Nil)).partitions, 0)
  }
}
