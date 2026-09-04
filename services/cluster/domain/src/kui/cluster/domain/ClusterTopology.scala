package kui.cluster.domain

import kui.kernel.BrokerId

/** What is known about one broker beyond its address: its disk, and its share of the replicas. */
final case class BrokerLoad(
    replicas: Int,
    /** Partitions this broker leads. **Always `None` in M1**: leadership comes from `describeTopics`, and the
      * cluster service does not sweep topics. Filled in M2 from the topic service's snapshot.
      */
    leaders: Option[Int],
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
    val counts = perBroker.values.map(_.replicas.toLong).toList
    val total = counts.sum

    if perBroker.isEmpty || total == 0L then perBroker.map((id, load) => id -> load.copy(skewPercent = None))
    else {
      val mean = total.toDouble / perBroker.size.toDouble

      perBroker.map { (id, load) =>
        val skew = (load.replicas.toDouble - mean) / mean * 100.0
        id -> load.copy(skewPercent = Some(round1(skew)))
      }
    }
  }

  private def round1(value: Double): Double = math.round(value * 10.0).toDouble / 10.0

  given CanEqual[BrokerLoad, BrokerLoad] = CanEqual.derived
}

/** Cluster-wide partition counts. Always `None` in M1: they need a topic sweep. */
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
    /** M2. Needs a topic sweep, which the cluster service does not do. */
    partitions: Option[PartitionSummary],
    /** M2. Needs `listTopics`. */
    topics: Option[Int]
) {

  def brokerCount: Int = description.brokerCount

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
