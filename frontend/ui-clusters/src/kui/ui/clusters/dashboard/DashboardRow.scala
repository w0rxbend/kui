package kui.ui.clusters.dashboard

import java.time.Instant

import kui.contracts.Section
import kui.contracts.capability.ReasonCode
import kui.contracts.cluster.ClusterRowDto
import kui.gateway.contract.dto.ClusterOverviewDto
import kui.kernel.{BrokerId, ClusterId}

/** What one dashboard row is in, which decides its chip, its dimming and where it sorts.
  *
  * Four states, not five: a cluster whose section is `NotConfigured` is not a row at all (see
  * [[DashboardRow.of]]).
  */
enum RowStatus {
  case Online
  case Degraded(reason: String)
  case Unavailable(reason: String, since: Option[Instant])
  case Forbidden
}

object RowStatus {
  given CanEqual[RowStatus, RowStatus] = CanEqual.derived
}

/** One dashboard row, already reduced to what the table draws.
  *
  * A plain value with no `Signal` in it, produced by a total function from the response. That is what makes
  * the row model — which chip, which cells, whether the row is clickable — a table of test cases rather than
  * a rendering somebody has to look at and judge.
  *
  * Every figure is an `Option`, and three of them are always `None` in this milestone: online, offline and
  * under-replicated partition counts are not derivable from `describeCluster`, the broker set and
  * `describeLogDirs`. Getting them needs a full `describeTopics` sweep, which belongs to the topic service
  * (`research/kafka/admin-capabilities.md` §1 "Cluster stats"; M1 DEVPLAN §10 D5 as corrected at the gate
  * review). They have a field so that a later milestone fills them without a breaking change, and until then
  * their cells read `—`. They must never read `0`, which would be a claim.
  */
final case class DashboardRow(
    clusterId: ClusterId,
    name: String,
    readOnly: Boolean,
    status: RowStatus,
    version: Option[String],
    brokerCount: Option[Int],
    controller: Option[BrokerId],
    onlinePartitions: Option[Int],
    offlinePartitions: Option[Int],
    underReplicatedPartitions: Option[Int],
    diskUsageBytes: Option[Long],
    /** How many topics the cluster holds, internal ones included, from the topic service. `None` when this
      * deployment has no topic service or that service could not answer for this cluster.
      */
    topicCount: Option[Long],
    /** Every partition of every topic. `None` when the topic service could not answer *or* when the cluster
      * has more topics than the gateway summed — see `TopicTotalsDto`. Never a partial sum.
      */
    partitionCount: Option[Int],
    consumerGroupCount: Option[Long],
    fetchedAt: Option[Instant]
) {

  /** Whether this row is one of the ones the "show unavailable only" toggle keeps. */
  def isUnavailable: Boolean =
    status match {
      case RowStatus.Unavailable(_, _) => true
      case RowStatus.Online | RowStatus.Degraded(_) | RowStatus.Forbidden => false
    }

  /** Whether the row's own numbers are known to be old, which is what dims its cells. */
  def isStale: Boolean =
    status match {
      case RowStatus.Degraded(_) => true
      case RowStatus.Online | RowStatus.Unavailable(_, _) | RowStatus.Forbidden => false
    }
}

object DashboardRow {

  given CanEqual[DashboardRow, DashboardRow] = CanEqual.derived

  /** Every row the table shows, in the response's order, with `NotConfigured` clusters dropped.
    *
    * Dropping rather than dimming is the same rule ADR-032 applies to a `NotConfigured` navigation entry:
    * this deployment has no such cluster, so putting it on screen invites a user to click something that will
    * never work. Every other state stays visible, because a user has to be able to tell "misconfigured" from
    * "down".
    */
  def of(response: ClusterOverviewDto): List[DashboardRow] =
    // `toOption.toList.flatten`: the *outer* section says whether the cluster service answered at all.
    // When it did not, there are no rows — the overlay above the table is what tells the user why, and
    // inventing rows here would put made-up clusters on a screen.
    response.clusters.toOption.toList.flatten.flatMap(entry =>
      row(entry.cluster).map(
        _.copy(
          // The topic and consumer figures arrive in their own sections and fail on their own, so they are
          // read separately from the cluster's summary and left absent when their section is anything but
          // `Ok` or `Stale`. A dead consumer service costs this table one column, not a row.
          topicCount = entry.topics.toOption.map(_.topicCount),
          partitionCount = entry.topics.toOption.flatMap(_.partitionCount),
          consumerGroupCount = entry.consumerGroups.toOption.map(_.groupCount)
        )
      )
    )

  private def row(dto: ClusterRowDto): Option[DashboardRow] =
    dto.summary match {
      case Section.NotConfigured => None

      case Section.Ok(data, fetchedAt) =>
        Some(
          DashboardRow(
            clusterId = dto.id,
            name = dto.name,
            readOnly = dto.readOnly,
            status = RowStatus.Online,
            version = data.version,
            brokerCount = Some(data.brokerCount),
            controller = data.controllerId,
            onlinePartitions = data.onlinePartitionCount,
            offlinePartitions = data.offlinePartitionCount,
            underReplicatedPartitions = data.underReplicatedPartitionCount,
            diskUsageBytes = data.totalDiskUsageBytes,
            topicCount = None,
            partitionCount = None,
            consumerGroupCount = None,
            fetchedAt = Some(fetchedAt)
          )
        )

      case Section.Stale(data, fetchedAt, reason) =>
        // Still real data, just old. The payload stays on the row and only the status changes; throwing the
        // numbers away because they are stale is the exact failure ADR-032 exists to prevent.
        Some(
          DashboardRow(
            clusterId = dto.id,
            name = dto.name,
            readOnly = dto.readOnly,
            status = RowStatus.Degraded(describe(reason)),
            version = data.version,
            brokerCount = Some(data.brokerCount),
            controller = data.controllerId,
            onlinePartitions = data.onlinePartitionCount,
            offlinePartitions = data.offlinePartitionCount,
            underReplicatedPartitions = data.underReplicatedPartitionCount,
            diskUsageBytes = data.totalDiskUsageBytes,
            topicCount = None,
            partitionCount = None,
            consumerGroupCount = None,
            fetchedAt = Some(fetchedAt)
          )
        )

      case Section.Unavailable(_, message, since) =>
        Some(empty(dto, RowStatus.Unavailable(message, since)))

      case Section.Forbidden => Some(empty(dto, RowStatus.Forbidden))
    }

  /** A row with its identity and nothing else: name, id and `readOnly` come from configuration and are known
    * whether or not the cluster answers, which is exactly why the contract keeps them outside the section.
    */
  private def empty(dto: ClusterRowDto, status: RowStatus): DashboardRow =
    DashboardRow(
      clusterId = dto.id,
      name = dto.name,
      readOnly = dto.readOnly,
      status = status,
      version = None,
      brokerCount = None,
      controller = None,
      onlinePartitions = None,
      offlinePartitions = None,
      underReplicatedPartitions = None,
      diskUsageBytes = None,
      topicCount = None,
      partitionCount = None,
      consumerGroupCount = None,
      fetchedAt = None
    )

  /** The sort key for the status column: problems first.
    *
    * Ascending on this column therefore puts the clusters that need attention at the top, which is the order
    * somebody opening the dashboard because something is wrong wants to see.
    */
  def statusOrder(status: RowStatus): Int =
    status match {
      case RowStatus.Unavailable(_, _) => 0
      case RowStatus.Degraded(_) => 1
      case RowStatus.Forbidden => 2
      case RowStatus.Online => 3
    }

  /** The rows the "show unavailable only" toggle keeps.
    *
    * Unavailable, not "not perfectly healthy". A degraded cluster is still serving data, and sweeping it in
    * here would make the toggle mean something different from what its label says.
    */
  def onlyUnavailable(rows: List[DashboardRow]): List[DashboardRow] = rows.filter(_.isUnavailable)

  /** How many rows are `Online`, and how many are not. The summary strip's two numbers.
    *
    * Computed from the rows rather than from the response, so a `NotConfigured` cluster is in neither count
    * and the two numbers always add up to what is on screen.
    */
  def counts(rows: List[DashboardRow]): (Int, Int) = {
    val online = rows.count(_.status == RowStatus.Online)
    (online, rows.length - online)
  }

  /** A reason code as a short phrase.
    *
    * The wire name would be shouted (`UPSTREAM_TIMEOUT`) and the shell's full sentences are the shell's; a
    * chip has room for a phrase. ADR-032's rule about rendering reasons verbatim is about the *message* a
    * service sends, which is what an `Unavailable` row carries; a `ReasonCode` is a closed enumeration KUI
    * defines itself, so naming its cases here invents nothing.
    */
  private def describe(reason: ReasonCode): String =
    reason match {
      case ReasonCode.UpstreamUnavailable => "cluster not responding"
      case ReasonCode.UpstreamTimeout => "cluster too slow to answer"
      case ReasonCode.CircuitOpen => "paused after repeated failures"
      case ReasonCode.UpstreamAuth => "credentials refused"
      case ReasonCode.NotConfigured => "not configured"
      case ReasonCode.Forbidden => "not permitted"
      case ReasonCode.Starting => "not read yet"
      case ReasonCode.Unknown => "reason unknown"
    }
}
