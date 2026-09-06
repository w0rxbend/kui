package kui.cluster.domain

import kui.kernel.BrokerId

/** What is known about one broker beyond its address: its disk, and its share of the replicas.
  *
  * Everything here comes from `describeLogDirs`, which is why a broker that did not answer that call has no
  * `BrokerLoad` at all rather than an empty one. The partition and leader counts a broker row also shows come
  * from a different call over a different set — every topic, rather than every broker — so they live on
  * [[ClusterTopology.census]] and not here. Two sources, two lifetimes, two refusals; folding them into one
  * record would make a broker with no readable disk look like a broker leading nothing.
  */
final case class BrokerLoad(
    replicas: Int,
    /** `(replicas - mean) / mean * 100`, rounded to one decimal, or `None` when the cluster hosts no replicas
      * at all. Not a metric: it is arithmetic on the replica counts above.
      */
    skewPercent: Option[Double],
    logDirs: List[LogDir]
) {

  /** The sum of what the directories reported, or `None` when none of them reported a size — which is what a
    * broker older than 3.3 looks like, and is different from a broker with an empty disk.
    */
  def totalBytes: Option[Long] = sumOf(_.totalBytes)

  def usableBytes: Option[Long] = sumOf(_.usableBytes)

  /** What Kafka's own data actually occupies on this broker: the sum of the replica sizes its log directories
    * reported. `None` when the broker reported no log directories at all, which is what a broker older than
    * Kafka 3.3 looks like and is different from a broker holding nothing.
    *
    * Deliberately not `totalBytes - usableBytes`. That subtraction is the *filesystem's* used space, which on
    * a shared disk is mostly other people's files: on a laptop running the quickstart it reads about 184 GiB
    * for a broker holding a hundred records.
    */
  def usedByKafkaBytes: Option[Long] =
    if logDirs.isEmpty then None else Some(logDirs.map(_.usedByKafkaBytes).sum)

  def offlineDirs: List[LogDir] = logDirs.filterNot(_.isHealthy)

  private def sumOf(field: LogDir => Option[Long]): Option[Long] = {
    val reported = logDirs.flatMap(dir => field(dir))
    if reported.isEmpty then None else Some(reported.sum)
  }
}

object BrokerLoad {

  /** Computes `skewPercent` for every broker from the replica counts of the whole set.
    *
    * It takes the whole map rather than one broker because a caller computing one broker's skew on its own
    * would divide by a different denominator than its neighbour, and the two numbers would not add up on the
    * page they are shown on together.
    */
  def withSkew(perBroker: Map[BrokerId, BrokerLoad]): Map[BrokerId, BrokerLoad] = {
    val total = perBroker.values.map(_.replicas.toLong).sum

    perBroker.map { (id, load) =>
      id -> load.copy(skewPercent = skewOf(load.replicas, total, perBroker.size))
    }
  }

  /** How far one broker's share is from an even one: `(count - mean) / mean * 100`, to one decimal, where the
    * mean is `total / brokers`.
    *
    * Shared rather than written twice. [[ClusterTopology.leaderSkewOn]] is the second caller, and the two
    * numbers sit in adjacent columns of the same table: a replica skew and a leader skew computed by two
    * expressions that had drifted apart would be two scales the reader has no way to tell apart. `-100.0` is
    * a broker holding none of something every other broker holds, and `0.0` is a perfectly even spread; both
    * are measurements.
    *
    * `None` when there is nothing to be skewed away from — no brokers, or a cluster holding none of the
    * quantity at all, where every distance from a zero mean is a division by zero rather than a figure.
    */
  def skewOf(count: Int, total: Long, brokers: Int): Option[Double] =
    if brokers <= 0 || total == 0L then None
    else {
      val mean = total.toDouble / brokers.toDouble
      Some(round1((count.toDouble - mean) / mean * 100.0))
    }

  private def round1(value: Double): Double = math.round(value * 10.0).toDouble / 10.0

  given CanEqual[BrokerLoad, BrokerLoad] = CanEqual.derived
}

/** Cluster-wide partition counts, as one `describeTopics` sweep counted them.
  *
  * Reachable only through [[PartitionCensus.summary]], and the census only through `TopicSweep.complete`, so
  * there is no route to these three numbers that does not pass the completeness check first.
  */
final case class PartitionSummary(online: Int, offline: Int, underReplicated: Int)

object PartitionSummary {
  given CanEqual[PartitionSummary, PartitionSummary] = CanEqual.derived
}

/** Everything the cluster service knows about one cluster at one instant.
  *
  * This is the value the snapshot cell holds. It is a *finding*, never configuration: it holds a `ClusterRef`
  * and not a `ClusterProfile`, so no code path can reach a bootstrap string or a password by starting from a
  * snapshot. That is a structural barrier rather than a preference — it is what makes "no secret appears in a
  * response body" an assertion about a type instead of about a code path.
  */
final case class ClusterTopology(
    cluster: ClusterRef,
    description: ClusterDescription,
    version: Option[KafkaVersion],
    quorum: Option[QuorumInfo],
    features: ClusterFeatures,
    load: Map[BrokerId, BrokerLoad],
    /** What the last complete `describeTopics` sweep counted, or `None` when the last sweep was not complete
      * — one topic it could not describe is enough. Every partition figure this service publishes is derived
      * from here, so the refusal is made once and cannot be forgotten at a call site.
      */
    census: Option[PartitionCensus],
    /** How many topics the sweep listed. Present even when the census is not: a listing that succeeded and a
      * describe that partly failed are different failures, and the topic count survives the second.
      *
      * It has no consumer today, which is stated here rather than left for a reader to discover: nothing on
      * the wire carries it, and the "described 3,998 of 4,000" line an operator sees is logged by
      * `ClusterSnapshots` from the `TopicSweep` itself, before the topology is built. It is kept because it
      * is the denominator that makes `census = None` legible — a screen that wanted to say *why* the
      * partition figures are absent needs it and cannot recompute it — and because the field costs one
      * `Option[Int]` per cluster per scrape.
      */
    topics: Option[Int],
    /** The controller-uptime window, when this deployment keeps one. `None` means no window is being kept at
      * all, which is a different statement from a window that is not yet full — that one is a `Some` whose
      * `percent` is `None`.
      */
    controllerUptime: Option[ControllerUptime]
) {

  def brokerCount: Int = description.brokerCount

  /** The cluster-wide partition counts, or `None` when the sweep they would come from was not complete. */
  def partitions: Option[PartitionSummary] = census.map(_.summary)

  /** How many partitions this broker holds a replica of, and how many of them it leads.
    *
    * They refuse together and they refuse with the cluster-wide counts, because all three are sums over the
    * same sweep: if any topic in it could not be described, none of them is a total.
    */
  def partitionsOn(broker: BrokerId): Option[Int] = census.map(_.hosted(broker))

  def leadersOn(broker: BrokerId): Option[Int] = census.map(_.led(broker))

  /** How far this broker's share of the *leaderships* is from an even one, on the same scale and through the
    * same function as [[BrokerLoad.skewOf]] computes the replica skew.
    *
    * The denominator is every broker the cluster describes, not every broker that appears in the census: a
    * broker that leads nothing leads nothing, and leaving it out of the mean would report the brokers that do
    * lead as evenly balanced. The total is [[PartitionCensus.online]], because an online partition has
    * exactly one leader and an offline one has none — so the leaderships and the online partitions are the
    * same count, arrived at from two directions.
    *
    * It refuses with `leadersOn` and with every other partition figure, because all of them are sums over one
    * sweep: if a topic in it could not be described, none of them is a total.
    */
  def leaderSkewOn(broker: BrokerId): Option[Double] =
    census.flatMap(counted => BrokerLoad.skewOf(counted.led(broker), counted.online.toLong, brokerCount))

  def totalDiskBytes: Option[Long] = sumOf(_.totalBytes)

  def usableDiskBytes: Option[Long] = sumOf(_.usableBytes)

  /** What Kafka's data occupies across every broker, summed from each broker's log directories. `None` when
    * no broker reported one. See [[BrokerLoad.usedByKafkaBytes]] for why this is not the filesystem's number.
    */
  def usedByKafkaBytes: Option[Long] = sumOf(_.usedByKafkaBytes)

  def offlineLogDirCount: Int = load.values.map(_.offlineDirs.size).sum

  def has(feature: ClusterFeature): Boolean = features.has(feature)

  /** True when the detected version is below the minimum KUI supports. Drives a banner, never a refusal — and
    * `None` is not a warning: an undetected version is an unknown, and warning about it would fire on every
    * managed service.
    */
  def belowMinimumVersion: Boolean = version.exists(!_.meetsMinimum)

  private def sumOf(field: BrokerLoad => Option[Long]): Option[Long] = {
    val reported = load.values.toList.flatMap(load => field(load))
    if reported.isEmpty then None else Some(reported.sum)
  }
}

object ClusterTopology {
  given CanEqual[ClusterTopology, ClusterTopology] = CanEqual.derived
}
