package kui.alerts.infrastructure

import munit.FunSuite

import kui.kafka.SkipReason
import kui.kafka.admin.LogDir
import kui.kernel.error.ErrorCode
import kui.kernel.BrokerId

/** The two conversions the adapter makes before any rule sees a number.
  *
  * They are `object` methods rather than private helpers precisely so that they can be asserted without a
  * broker: everything else in `KafkaClusterFacts` is an admin call, and the parts of it that could be wrong
  * on a real cluster are the parts that turn a `LogDir` into a percentage and a `TopicPartitionInfo` into a
  * count.
  */
final class KafkaClusterFactsSuite extends FunSuite {

  private val broker = BrokerId.unsafe(1)

  private def dir(
      total: Option[Long],
      usable: Option[Long],
      error: Option[SkipReason] = None
  ): LogDir = LogDir("/var/lib/kafka/data", error, total, usable, Nil)

  test("a directory with a capacity gets a percentage of what is used") {
    assertEquals(KafkaClusterFacts.factOf(broker, dir(Some(1000L), Some(200L))).usedPercent, Some(80))
    assertEquals(KafkaClusterFacts.factOf(broker, dir(Some(1000L), Some(1000L))).usedPercent, Some(0))
    assertEquals(KafkaClusterFacts.factOf(broker, dir(Some(1000L), Some(0L))).usedPercent, Some(100))
  }

  test("a directory whose broker reported no capacity has no percentage at all") {
    // Brokers before 3.3 publish neither figure. The replica bytes alone cannot say whether they are most
    // of a disk or a rounding error on one, and filling the gap is the fabrication ADR-053 §4 forbids.
    assertEquals(KafkaClusterFacts.factOf(broker, dir(None, None)).usedPercent, None)
    assertEquals(KafkaClusterFacts.factOf(broker, dir(Some(1000L), None)).usedPercent, None)
    assertEquals(KafkaClusterFacts.factOf(broker, dir(None, Some(200L))).usedPercent, None)
  }

  test("a directory that answered an error has no percentage either") {
    // An offline disk answers `KafkaStorageException` for itself while its broker's other directories
    // answer normally. A disk KUI cannot read is not a disk that is empty.
    val offline = dir(Some(1000L), Some(200L), Some(SkipReason.Failed(ErrorCode.Internal, "storage")))

    assertEquals(KafkaClusterFacts.factOf(broker, offline).usedPercent, None)
  }

  test("a zero-byte capacity has no percentage rather than a division by zero") {
    assertEquals(KafkaClusterFacts.factOf(broker, dir(Some(0L), Some(0L))).usedPercent, None)
  }

  test("a share rounds down and is clamped into 0..100") {
    // Down, because the threshold comparison is `>=`: a directory rounded up to 80 would open an event
    // naming a number the disk had not reached.
    assertEquals(KafkaClusterFacts.percentOf(799L, 1000L), 79)
    assertEquals(KafkaClusterFacts.percentOf(800L, 1000L), 80)
    // `usableBytes` can briefly exceed `totalBytes` on a filesystem with reserved blocks, and a negative
    // percentage on a screen reads as a bug in KUI rather than as the rounding it is.
    assertEquals(KafkaClusterFacts.percentOf(-50L, 1000L), 0)
    assertEquals(KafkaClusterFacts.percentOf(2000L, 1000L), 100)
  }

  test("a directory's label names the broker and the path, which is what an event's subject is") {
    assertEquals(
      KafkaClusterFacts.factOf(broker, dir(Some(1000L), Some(100L))).label,
      "broker-1:/var/lib/kafka/data"
    )
  }

  test("offline and under-replicated are counted independently, so one incident reads as one incident") {
    // A partition with no leader is usually also under-replicated and is in both counts. Subtracting one
    // from the other would produce a number neither rule could explain.
    val partitions = List(
      partition(leader = None, replicas = 3, inSync = 1),
      partition(leader = Some(1), replicas = 3, inSync = 2),
      partition(leader = Some(1), replicas = 3, inSync = 3)
    )

    val counts = KafkaClusterFacts.PartitionCounts.of(partitions)

    assertEquals(counts.offline, 1)
    assertEquals(counts.underReplicated, 2)
  }

  test("a partition whose leader is the no-node sentinel counts as offline") {
    // A partition with no leader arrives either as a `null` node or as `Node.noNode()`, whose id is -1.
    val sentinel = new org.apache.kafka.common.TopicPartitionInfo(
      0,
      org.apache.kafka.common.Node.noNode(),
      java.util.List.of(node(1)),
      java.util.List.of(node(1))
    )

    assertEquals(KafkaClusterFacts.PartitionCounts.of(List(sentinel)).offline, 1)
  }

  test("counts from two chunks add up") {
    val left = KafkaClusterFacts.PartitionCounts(2, 3)
    val right = KafkaClusterFacts.PartitionCounts(1, 1)

    assertEquals(left.combine(right), KafkaClusterFacts.PartitionCounts(3, 4))
    assertEquals(KafkaClusterFacts.PartitionCounts.empty.combine(left), left)
  }

  test("both rebalancing states are watched, because a wedged group cycles through the first") {
    assertEquals(
      KafkaClusterFacts.RebalancingStates.map(_.wire),
      Set("PREPARING_REBALANCE", "COMPLETING_REBALANCE")
    )
  }

  private def node(id: Int): org.apache.kafka.common.Node =
    new org.apache.kafka.common.Node(id, "broker", 9092)

  private def partition(
      leader: Option[Int],
      replicas: Int,
      inSync: Int
  ): org.apache.kafka.common.TopicPartitionInfo = {
    val all = java.util.List.copyOf(java.util.Arrays.asList((1 to replicas).map(node)*))
    val isr = java.util.List.copyOf(java.util.Arrays.asList((1 to inSync).map(node)*))

    new org.apache.kafka.common.TopicPartitionInfo(0, leader.map(node).orNull, all, isr)
  }
}
