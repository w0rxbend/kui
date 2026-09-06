package kui.cluster.domain

import kui.kernel.{BrokerId, TopicName}

/** Where one partition lives, as `describeTopics` reports it.
  *
  * `leader` is `Option` because Kafka reports a leaderless partition as node `-1`, which is not a broker id:
  * a partition whose leader is being elected, or whose every replica is offline, has no leader and must not
  * be attributed to one. `replicas` and `inSync` are sets because the only questions asked of them here are
  * membership and size, and Kafka never assigns the same broker to one partition twice.
  */
final case class PartitionPlacement(
    leader: Option[BrokerId],
    replicas: Set[BrokerId],
    inSync: Set[BrokerId]
) {

  /** Fewer replicas in sync than assigned. The definition Kafka's own `UnderReplicatedPartitions` gauge uses,
    * stated here so that KUI's number and a broker's JMX number cannot disagree about what was counted.
    */
  def isUnderReplicated: Boolean = inSync.size < replicas.size

  /** A partition with a leader is online; one without is offline. There is no third state, which is why the
    * two counts always add up to [[PartitionCensus.partitions]] and a screen can subtract one from the other.
    */
  def isOnline: Boolean = leader.isDefined
}

object PartitionPlacement {
  given CanEqual[PartitionPlacement, PartitionPlacement] = CanEqual.derived
}

/** What one sweep of `describeTopics` counted, folded as it went.
  *
  * It is folded during the sweep rather than kept as a list of partitions because a cluster with four
  * thousand topics has hundreds of thousands of partitions, and holding them all so as to count them twice a
  * minute would be the most expensive thing this service does. Every field here is a running total.
  *
  * ==Why the counts are not `Option`==
  *
  * A census is always complete about what it saw: a broker that leads nothing led nothing, and that is a
  * measurement rather than an absence. The refusal lives one level up, in [[TopicSweep]], which is the only
  * type that knows whether the sweep it came from covered every topic. Keeping the two apart is what stops a
  * partial sum being mistaken for a total — see [[TopicSweep.complete]].
  *
  * @param hostedByBroker
  *   how many partitions have a replica on each broker. A broker absent from the map hosts none
  * @param ledByBroker
  *   how many of those it leads. Always a subset of the above, for the same broker
  */
final case class PartitionCensus(
    partitions: Int,
    online: Int,
    offline: Int,
    underReplicated: Int,
    hostedByBroker: Map[BrokerId, Int],
    ledByBroker: Map[BrokerId, Int]
) {

  def add(placement: PartitionPlacement): PartitionCensus =
    PartitionCensus(
      partitions = partitions + 1,
      online = online + (if placement.isOnline then 1 else 0),
      offline = offline + (if placement.isOnline then 0 else 1),
      underReplicated = underReplicated + (if placement.isUnderReplicated then 1 else 0),
      hostedByBroker = placement.replicas.foldLeft(hostedByBroker)(increment),
      ledByBroker = placement.leader.foldLeft(ledByBroker)(increment)
    )

  /** Folds two censuses of *disjoint* topics together, which is what a batched sweep produces. */
  def combine(other: PartitionCensus): PartitionCensus =
    PartitionCensus(
      partitions = partitions + other.partitions,
      online = online + other.online,
      offline = offline + other.offline,
      underReplicated = underReplicated + other.underReplicated,
      hostedByBroker = merged(hostedByBroker, other.hostedByBroker),
      ledByBroker = merged(ledByBroker, other.ledByBroker)
    )

  def hosted(broker: BrokerId): Int = hostedByBroker.getOrElse(broker, 0)

  def led(broker: BrokerId): Int = ledByBroker.getOrElse(broker, 0)

  /** The cluster-wide shape the summary reads. */
  def summary: PartitionSummary = PartitionSummary(online, offline, underReplicated)

  private def increment(counts: Map[BrokerId, Int], broker: BrokerId): Map[BrokerId, Int] =
    counts.updated(broker, counts.getOrElse(broker, 0) + 1)

  private def merged(left: Map[BrokerId, Int], right: Map[BrokerId, Int]): Map[BrokerId, Int] =
    right.foldLeft(left) { case (into, (broker, count)) =>
      into.updated(broker, into.getOrElse(broker, 0) + count)
    }
}

object PartitionCensus {

  val empty: PartitionCensus = PartitionCensus(0, 0, 0, 0, Map.empty, Map.empty)

  def of(placements: IterableOnce[PartitionPlacement]): PartitionCensus =
    placements.iterator.foldLeft(empty)(_.add(_))

  given CanEqual[PartitionCensus, PartitionCensus] = CanEqual.derived
}

/** One pass over every topic in the cluster, and whether it was whole.
  *
  * ==The refusal is the whole point of this type==
  *
  * Every figure the cluster service derives from a topic sweep is a *sum*, and a sum over a sweep that missed
  * some of its topics is the worst answer available: it is a number, it looks measured, and it is wrong in
  * the direction that reassures. Fifteen hundred partitions in sync out of fifteen hundred looks healthy
  * whether the other five hundred were fine or unreadable. So [[complete]] is the only way to the census, and
  * it answers `None` the moment one topic could not be described.
  *
  * The same rule is applied to disks in three other places and the three agree: `BrokerLoad.totalBytes` sums
  * only the directories that reported a size, `diskPercentOf` in the browser's `overview/model.ts` skips a
  * directory that did not report both figures, and `storageOf` in the shell's `clusterStore.ts` mirrors it
  * line for line. A failed disk is not a disk of size zero, and an unreadable topic is not a topic with no
  * partitions.
  *
  * @param topics
  *   how many topics the listing named, unreadable ones included. It is the denominator of "described 3,998
  *   of 4,000", which is what an operator needs in order to tell a permissions problem from an outage
  * @param unreadable
  *   the topics `describeTopics` refused or failed on, by name. Names rather than a count because the first
  *   question after "the figures are absent" is "which topics", and a count cannot answer it
  */
final case class TopicSweep(census: PartitionCensus, topics: Int, unreadable: Set[TopicName]) {

  def isComplete: Boolean = unreadable.isEmpty

  /** The census, and only when the sweep that produced it covered every topic. */
  def complete: Option[PartitionCensus] = Option.when(isComplete)(census)

  /** How many topics were actually described. Reported rather than derived by the caller so that the two
    * numbers on one log line come from the same sweep.
    */
  def described: Int = topics - unreadable.size
}

object TopicSweep {

  /** A sweep of a cluster with no topics at all: complete, and counting nothing. */
  val emptyCluster: TopicSweep = TopicSweep(PartitionCensus.empty, 0, Set.empty)

  given CanEqual[TopicSweep, TopicSweep] = CanEqual.derived
}
