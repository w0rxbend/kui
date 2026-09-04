package kui.topic.domain

import kui.kernel.TopicName

/** One row of the topic list.
  *
  * Four fields are `Option` and each absence means something specific. They are not "missing data" in the
  * general sense; each one is a question a particular cluster refused to answer, and the screen renders each
  * refusal differently:
  *
  *   - `messageCount` is `None` when the count could not be computed **for the whole topic** — which is what
  *     happens when any partition is leaderless or its offsets were skipped. A count summed over a partial
  *     set of partitions would be wrong rather than incomplete, and a wrong number is worse than no number
  *     because only one of the two starts an investigation (`libs/kafka/PORT-INVARIANTS.md` §1, DEVPLAN §10
  *     D6).
  *   - `sizeBytes` is `None` when the broker would not report its log directories. It refuses independently
  *     of the count: a cluster that answers `listOffsets` and refuses `describeLogDirs` shows counts and an
  *     em dash for size, because losing a number the operator can have is a choice with no upside.
  *   - `replicationFactor` is `None` for a topic with no partitions, which Kafka permits transiently just
  *     after a create.
  *
  * `offlinePartitions` is a count and not a boolean because it is what lets the list say "the count is
  * missing *because* two partitions are offline" instead of showing an unexplained em dash.
  *
  * Whether a topic is internal is whatever this value was constructed with. The rule that decides it — the
  * union of Kafka's own flag and a configured name prefix — is the application layer's (DEVPLAN §10 D3), and
  * it lives in exactly one place so that there is exactly one place it can be got wrong.
  */
final case class TopicSummary(
    name: TopicName,
    isInternal: Boolean,
    partitionCount: Int,
    replicationFactor: Option[Int],
    outOfSyncReplicas: Int,
    offlinePartitions: Int,
    messageCount: Option[Long],
    sizeBytes: Option[Long]
) {
  def hasOfflinePartitions: Boolean = offlinePartitions > 0
  def isUnderReplicated: Boolean = outOfSyncReplicas > 0
}

object TopicSummary {

  /** Builds the row from the partitions it summarises.
    *
    * This is where the aggregate arithmetic lives, and it lives here rather than at each call site because
    * the refusal rule below is the milestone's most important one and a rule implemented at three call sites
    * is a rule implemented at two. The application layer's `PartitionAggregates` delegates here; the adapter
    * that reads a real broker delegates here; a test that builds a topic out of partitions delegates here.
    */
  def of(name: TopicName, isInternal: Boolean, partitions: List[PartitionView]): TopicSummary =
    TopicSummary(
      name = name,
      isInternal = isInternal,
      partitionCount = partitions.size,
      replicationFactor = Aggregate.replicationFactor(partitions),
      outOfSyncReplicas = Aggregate.outOfSyncReplicas(partitions),
      offlinePartitions = Aggregate.offlinePartitions(partitions),
      messageCount = Aggregate.messageCount(partitions),
      sizeBytes = Aggregate.sizeBytes(partitions)
    )

  given Ordering[TopicSummary] = Ordering.by((summary: TopicSummary) => summary.name.value)
  given CanEqual[TopicSummary, TopicSummary] = CanEqual.derived
}

/** The arithmetic a topic row and a topic detail are both built from.
  *
  * Kept beside [[TopicSummary]] rather than in the application layer so that the domain's own invariants —
  * "an aggregate over a partial set refuses" above all — are stated in the module that owns them and cannot
  * be bypassed by a caller that builds a `TopicSummary` by hand for a screen.
  */
object Aggregate {

  /** `None` if **any** partition's count is missing.
    *
    * The empty list is `Some(0)`: a topic with no partitions holds no records, which is a fact, not a
    * refusal.
    */
  def messageCount(partitions: List[PartitionView]): Option[Long] =
    sumOrRefuse(partitions.map(_.messageCount))

  /** `None` if any partition's size is missing — the same refusal, for the same reason, and taken separately
    * so that a cluster which answers one call and refuses the other still shows the answer it gave.
    */
  def sizeBytes(partitions: List[PartitionView]): Option[Long] =
    sumOrRefuse(partitions.map(_.sizeBytes))

  /** Replicas that are lagging, summed over every partition. Replicas, not partitions. */
  def outOfSyncReplicas(partitions: List[PartitionView]): Int = partitions.map(_.outOfSyncReplicas).sum

  def offlinePartitions(partitions: List[PartitionView]): Int = partitions.count(_.isLeaderless)

  /** The replica count of the first partition, or `None` when there are no partitions.
    *
    * Kafka's own replication factor is a create-time parameter it does not store, so every tool derives it
    * from a partition. Partitions can genuinely differ after a manual reassignment, and the first one is what
    * the reference products report; a topic in that state is visible through its per-partition replica lists
    * on the detail page.
    */
  def replicationFactor(partitions: List[PartitionView]): Option[Int] =
    partitions.headOption.map(_.replicas.size)

  private def sumOrRefuse(values: List[Option[Long]]): Option[Long] =
    if values.exists(_.isEmpty) then None else Some(values.flatten.sum)
}
