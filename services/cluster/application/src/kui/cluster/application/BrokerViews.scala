package kui.cluster.application

import kui.cluster.domain.{Broker, ClusterRef, ConfigEntry, LogDir, LogDirPath}
import kui.kernel.{BrokerId, TopicPartition}

/** One row of the broker list.
  *
  * Everything on it comes from the topology snapshot, so the whole list is one memory read. `leaders` is
  * `None` in M1 and the column renders `—`: leadership needs a topic sweep the cluster service does not do.
  */
final case class BrokerListRow(
    broker: Broker,
    isController: Boolean,
    replicas: Option[Int],
    leaders: Option[Int],
    skewPercent: Option[Double],
    totalBytes: Option[Long],
    usableBytes: Option[Long],
    /** What Kafka's own data occupies on this broker. Distinct from `totalBytes - usableBytes`, which is the
      * whole filesystem's used space and is mostly not Kafka. See [[kui.cluster.domain.BrokerLoad]].
      */
    usedByKafkaBytes: Option[Long],
    offlineDirCount: Int
)

object BrokerListRow {
  given CanEqual[BrokerListRow, BrokerListRow] = CanEqual.derived
}

/** The broker list plus how fresh it is, and the metadata quorum when the cluster has one.
  *
  * The quorum travels with the broker list rather than on an endpoint of its own because it is a fact about
  * the same nodes, read in the same snapshot pass. A separate call would be a second request for a panel that
  * sits beside the table, and — worse — a second *moment*: a quorum's lag is computed against a high
  * watermark, and pairing one snapshot's watermark with another snapshot's log end offsets produces a lag
  * that never existed.
  *
  * `None` on a ZooKeeper cluster, on a KRaft cluster too old for `describeMetadataQuorum` (before 3.3), and
  * on one that refused the call. Those are all "there is no quorum information here", which is what a panel
  * that renders nothing needs to know.
  */
final case class BrokerList(
    cluster: ClusterRef,
    brokers: List[BrokerListRow],
    freshness: SnapshotFreshness,
    quorum: Option[kui.cluster.domain.QuorumInfo] = None
)

object BrokerList {
  given CanEqual[BrokerList, BrokerList] = CanEqual.derived
}

/** One broker's log directories, with the freshness of the read that produced them: `Fresh` for a live call,
  * `Stale` when the live call failed and the snapshot answered instead.
  */
final case class BrokerLogDirs(
    cluster: ClusterRef,
    broker: BrokerId,
    dirs: List[LogDir],
    freshness: SnapshotFreshness
) {

  def totalBytes: Option[Long] = sumOf(_.totalBytes)

  def usableBytes: Option[Long] = sumOf(_.usableBytes)

  def offline: List[LogDir] = dirs.filterNot(_.isHealthy)

  private def sumOf(field: LogDir => Option[Long]): Option[Long] = {
    val reported = dirs.flatMap(dir => field(dir))
    if reported.isEmpty then None else Some(reported.sum)
  }
}

object BrokerLogDirs {
  given CanEqual[BrokerLogDirs, BrokerLogDirs] = CanEqual.derived
}

/** Where one partition's data sits on this broker and how much space it takes. */
final case class PartitionSize(
    partition: TopicPartition,
    path: LogDirPath,
    sizeBytes: Long,
    offsetLag: Long,
    isFuture: Boolean
)

object PartitionSize {
  given CanEqual[PartitionSize, PartitionSize] = CanEqual.derived
}

/** Every partition hosted on one broker, largest first — which is the order the question "what is filling
  * this disk?" is asked in, and the only ordering the page ever needs.
  */
final case class PartitionSizes(
    cluster: ClusterRef,
    broker: BrokerId,
    partitions: List[PartitionSize],
    freshness: SnapshotFreshness
) {
  def totalBytes: Long = partitions.map(_.sizeBytes).sum
}

object PartitionSizes {

  /** Reshapes one broker's log directories into the per-partition view.
    *
    * Future replicas are included and flagged rather than filtered: during a replica move the disk really is
    * holding both copies, and an operator looking at a filling disk needs to see the one that is arriving.
    */
  def of(
      cluster: ClusterRef,
      broker: BrokerId,
      dirs: List[LogDir],
      freshness: SnapshotFreshness
  ): PartitionSizes = {
    val sizes = for {
      dir <- dirs
      replica <- dir.replicas
    } yield PartitionSize(replica.partition, dir.path, replica.sizeBytes, replica.offsetLag, replica.isFuture)

    PartitionSizes(cluster, broker, sizes.sortBy(size => -size.sizeBytes), freshness)
  }

  given CanEqual[PartitionSizes, PartitionSizes] = CanEqual.derived
}

/** One broker's configuration.
  *
  * `entries` is always sorted by name. The UI groups by source and hides defaults behind a toggle; the
  * grouping is the UI's, but the *sort* is here so that two requests never disagree about row order and a
  * diff between two brokers is readable.
  */
final case class BrokerConfigView(
    cluster: ClusterRef,
    broker: BrokerId,
    entries: List[ConfigEntry],
    /** True when the cluster was asked for, and answered with, documentation strings. The UI shows the help
      * affordance only then, rather than rendering an empty tooltip on every row of an older broker.
      */
    hasDocumentation: Boolean
) {

  def dynamic: List[ConfigEntry] = entries.filter(_.source.isDynamic)

  def nonDefault: List[ConfigEntry] = entries.filterNot(_.isDefault)

  /** Entries whose value the broker withheld because they are sensitive.
    *
    * They are *shown*, with no value. Hiding the row entirely would let an operator conclude a setting is
    * unset when it is set to something they are not allowed to read.
    */
  def sensitive: List[ConfigEntry] = entries.filter(_.isSensitive)
}

object BrokerConfigView {
  given CanEqual[BrokerConfigView, BrokerConfigView] = CanEqual.derived
}
