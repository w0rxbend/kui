package kui.ui.clusters.brokers

import kui.contracts.cluster.{BrokerDto, ClusterSummaryDto}
import kui.kernel.BrokerId

/** One broker row, reduced to what the table draws.
  *
  * A plain value with no `Signal` in it, so every rule about what a cell shows is a test row rather than a
  * rendering somebody has to look at and judge.
  *
  * Every figure is an `Option`, and the reason matters: a broker whose `describeLogDirs` call was refused —
  * an ordinary state on a cluster whose credentials authorise reading the cluster but not the disks — has
  * *unknown* disk usage, not zero. Showing zero would say the machine is empty.
  */
final case class BrokerRow(
    brokerId: BrokerId,
    host: String,
    port: Int,
    rack: Option[String],
    isController: Boolean,
    diskUsageBytes: Option[Long],
    segmentCount: Option[Int],
    leaderCount: Option[Int],
    replicaCount: Option[Int],
    inSyncReplicaCount: Option[Int],
    leaderSkewPercent: Option[Double],
    replicaSkewPercent: Option[Double]
)

object BrokerRow {

  given CanEqual[BrokerRow, BrokerRow] = CanEqual.derived

  /** Every row, in the response's order, with both skews resolved.
    *
    * The skew comes from the service when the service computed it, and is computed here only when it did not.
    * The service is the better place for it — one rounding, shared by this table, a CSV export and anything
    * else that ever reads the endpoint — and the fallback exists so that the figure is not simply missing if
    * a deployment's service predates it. `Skew` remains the definition of the rule either way.
    */
  def of(brokers: List[BrokerDto]): List[BrokerRow] = {
    val leaderSkews = Skew.percentages(brokers.map(_.leaderCount))
    val replicaSkews = Skew.percentages(brokers.map(_.partitionCount))

    brokers.zip(leaderSkews.zip(replicaSkews)).map { case (dto, (leaderSkew, replicaSkew)) =>
      BrokerRow(
        brokerId = dto.id,
        host = dto.host,
        port = dto.port,
        // A broker that declares no rack must read as "none" and not as an empty string, which would sort
        // between two real racks and look like a rack whose name nobody typed.
        rack = dto.rack.map(_.trim).filter(_.nonEmpty),
        isController = dto.isController,
        diskUsageBytes = dto.diskUsageBytes,
        segmentCount = dto.segmentCount,
        leaderCount = dto.leaderCount,
        replicaCount = dto.partitionCount,
        inSyncReplicaCount = dto.inSyncReplicaCount,
        leaderSkewPercent = dto.leaderSkewPercent.orElse(leaderSkew),
        replicaSkewPercent = dto.replicaSkewPercent.orElse(replicaSkew)
      )
    }
  }
}

/** The strip above the table: what the cluster is, in one line.
  *
  * @param controllerType
  *   `KRaft` or `ZooKeeper`, as the cluster reports it. Worth showing because it changes what an operator
  *   should do next about half the problems this page surfaces.
  */
final case class BrokerSummary(
    brokerCount: Int,
    controller: Option[BrokerId],
    version: Option[String],
    controllerType: Option[String],
    onlinePartitions: Option[Int],
    offlinePartitions: Option[Int],
    underReplicatedPartitions: Option[Int],
    inSyncReplicas: Option[Int],
    totalReplicas: Option[Int]
)

object BrokerSummary {

  given CanEqual[BrokerSummary, BrokerSummary] = CanEqual.derived

  /** Built from the brokers themselves, and from the cluster summary when one has been read.
    *
    * The cluster summary is the same cached answer the dashboard already holds, so arriving from the
    * dashboard costs nothing; arriving on a bookmark costs one cached response and no broker call at all.
    * Nothing here asks the cluster a new question.
    */
  def of(brokers: List[BrokerDto], cluster: Option[ClusterSummaryDto]): BrokerSummary =
    BrokerSummary(
      brokerCount = brokers.length,
      controller = brokers.find(_.isController).map(_.id).orElse(cluster.flatMap(_.controllerId)),
      version = cluster.flatMap(_.version),
      controllerType = cluster.map(_.controllerKind).map(describeController),
      onlinePartitions = cluster.flatMap(_.onlinePartitionCount),
      offlinePartitions = cluster.flatMap(_.offlinePartitionCount),
      underReplicatedPartitions = cluster.flatMap(_.underReplicatedPartitionCount),
      // Summed only over the brokers that reported one: adding a zero for a broker whose disks could not be
      // read would understate the cluster's replication and read as a problem that is not there.
      inSyncReplicas = sum(brokers.map(_.inSyncReplicaCount)),
      totalReplicas = sum(brokers.map(_.partitionCount))
    )

  /** Whether the strip has to shout.
    *
    * Two states, and only two: no controller at all, or a partition that is offline. Under-replication is a
    * warning colour rather than an alarm — it is a cluster working with less redundancy than it wants, which
    * is a different thing from a cluster that has stopped serving part of its data.
    */
  def hasAlarm(summary: BrokerSummary): Boolean =
    summary.controller.isEmpty || summary.offlinePartitions.exists(_ > 0)

  private def sum(values: List[Option[Int]]): Option[Int] = {
    val known = values.flatten
    Option.when(known.nonEmpty)(known.sum)
  }

  private def describeController(kind: String): String =
    kind match {
      case ClusterSummaryDto.KRaft => "KRaft"
      case ClusterSummaryDto.ZooKeeper => "ZooKeeper"
      // A newer KUI may report a fourth kind; showing what it said beats showing nothing.
      case other => other
    }
}
