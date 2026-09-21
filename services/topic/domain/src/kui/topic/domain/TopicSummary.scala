package kui.topic.domain

import java.time.Instant

import kui.kernel.TopicName

/** One row of the topic list.
  *
  * Every `Option` here means something specific, and none of them is "missing data" in the general sense:
  * each one is a question a particular cluster refused to answer, and the screen renders each refusal
  * differently. Three shipped with the list itself:
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
  * Three fields arrived with M5 and each of them refuses in its own way:
  *
  *   - `cleanupPolicy` is `None` when the batched `describeConfigs` behind the scrape did not cover this
  *     topic — a caller without `DESCRIBE_CONFIGS`, or a batch that failed. Never "the topic has no policy",
  *     because every topic has one.
  *   - `endOffsetTotal` is not on the wire. It is the sample [[ProduceRate]] differences, kept on the row
  *     because a rate is a property of *this topic between two scrapes* and the alternative — a second map
  *     beside the snapshot, keyed by name — is the same data with one more way for the two to disagree.
  *   - `produceRate` is `None` on the first scrape, and on every scrape whose predecessor cannot be
  *     subtracted from; see [[ProduceRate]] for the five cases. `TopicSummary.of` always leaves it `None`,
  *     because a summary built from one set of partitions has nothing to difference against: it is filled in
  *     by `TopicSnapshot.of`, which is the only place two consecutive scrapes are both in scope.
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
    sizeBytes: Option[Long],
    cleanupPolicy: Option[String] = None,
    endOffsetTotal: Option[Long] = None,
    produceRate: Option[Double] = None
) {
  def hasOfflinePartitions: Boolean = offlinePartitions > 0
  def isUnderReplicated: Boolean = outOfSyncReplicas > 0

  /** This row as an observation to difference the next scrape against. */
  def sample(at: Instant): ProduceRate.Sample =
    ProduceRate.Sample(at, endOffsetTotal, partitionCount)
}

object TopicSummary {

  /** Builds the row from the partitions it summarises.
    *
    * This is where the aggregate arithmetic lives, and it lives here rather than at each call site because
    * the refusal rule below is the milestone's most important one and a rule implemented at three call sites
    * is a rule implemented at two. The application layer's `PartitionAggregates` delegates here; the adapter
    * that reads a real broker delegates here; a test that builds a topic out of partitions delegates here.
    */
  def of(
      name: TopicName,
      isInternal: Boolean,
      partitions: List[PartitionView],
      cleanupPolicy: Option[String] = None
  ): TopicSummary =
    TopicSummary(
      name = name,
      isInternal = isInternal,
      partitionCount = partitions.size,
      replicationFactor = Aggregate.replicationFactor(partitions),
      outOfSyncReplicas = Aggregate.outOfSyncReplicas(partitions),
      offlinePartitions = Aggregate.offlinePartitions(partitions),
      messageCount = Aggregate.messageCount(partitions),
      sizeBytes = Aggregate.sizeBytes(partitions),
      cleanupPolicy = cleanupPolicy,
      endOffsetTotal = Aggregate.endOffsetTotal(partitions)
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

  /** How far this topic's logs have been written, summed over its partitions, or `None` if any partition's
    * end offset is missing.
    *
    * The same refusal as [[messageCount]], over the other bound. It is the sample a produce rate is
    * differenced from and never a figure a screen shows on its own: an absolute offset sum is a number whose
    * magnitude depends on how long the cluster has existed, which is not a fact about the topic.
    */
  def endOffsetTotal(partitions: List[PartitionView]): Option[Long] =
    sumOrRefuse(partitions.map(_.latestOffset))

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

  /** Sums, or refuses because one contributor could not answer.
    *
    * `private[domain]` rather than private: [[TopicStatistics]] folds the same rule up one level, over topics
    * instead of over partitions, and the cluster-wide size total refusing for a different reason from the
    * per-topic one is exactly the drift this being one function prevents.
    */
  private[domain] def sumOrRefuse(values: Iterable[Option[Long]]): Option[Long] =
    if values.exists(_.isEmpty) then None else Some(values.flatten.sum)
}
