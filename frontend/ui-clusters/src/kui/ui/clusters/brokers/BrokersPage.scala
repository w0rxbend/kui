package kui.ui.clusters.brokers

import java.time.Instant

import com.raquo.laminar.api.L.*

import kui.contracts.Section
import kui.contracts.cluster.{BrokerDto, ClusterSummaryDto}
import kui.kernel.{BrokerId, ClusterId, Sort, SortOrder}
import kui.ui.clusters.component.{Bytes, RefreshButton}
import kui.ui.clusters.{ClustersCss, ClustersQueries, Messages, RefreshFlow}
import kui.ui.kernel.component.*
import kui.ui.kernel.state.FeatureState
import kui.ui.kernel.time.Timestamps

/** One cluster's brokers.
  *
  * The screen an operator opens when something is wrong. Every broker, which one is the controller, where
  * each one sits, how much disk it is using — and the figure that makes the page worth building rather than
  * reading out of a shell: how unevenly the partitions are spread. See [[Skew]].
  *
  * ## Nothing here refetches
  *
  * The reference product reloads this page every five seconds and draws a full-page loader each time, so the
  * table a user is reading disappears under a spinner while they read it. KUI reads the server's snapshot
  * once and shows when it was taken. The timestamp above the table is what makes that acceptable: the answer
  * to "how old is this" is always on screen.
  */
object BrokersPage {

  def apply(
      cluster: ClusterId,
      queries: ClustersQueries,
      openBroker: (ClusterId, BrokerId) => Unit,
      brokerHref: (ClusterId, BrokerId) => String,
      backHref: String,
      zone: Signal[String],
      capability: Signal[FeatureState] = Val(FeatureState.Ready),
      now: () => Instant = () => Instant.now()
  )(using Owner): HtmlElement = {
    val sort: Var[Option[Sort[String]]] = Var(Some(Sort("broker", SortOrder.Asc)))

    val section: Signal[Option[Section[List[BrokerDto]]]] =
      queries.brokers.state(cluster).map(_.lastGood.map(_.brokers))

    /** The cluster's own summary, from the cache the dashboard already fills. Absent until it arrives, which
      * renders as `—` in the strip rather than as a spinner over the table.
      */
    val clusterSummary: Signal[Option[ClusterSummaryDto]] =
      queries.clusters
        .state(())
        .map(_.lastGood.flatMap(_.items.find(_.id == cluster).flatMap(_.summary.toOption)))

    val brokers: Signal[List[BrokerDto]] = section.map(_.flatMap(_.toOption).getOrElse(Nil))

    val rows: Signal[List[BrokerRow]] = brokers.map(BrokerRow.of)

    val summary: Signal[BrokerSummary] =
      brokers.combineWith(clusterSummary).map(BrokerSummary.of)

    val largestDisk: Signal[Long] = rows.map(_.flatMap(_.diskUsageBytes).maxOption.getOrElse(0L))

    val table = DataTable[BrokerRow](
      columns = columns(cluster, largestDisk, brokerHref, openBroker),
      rows = rows.combineWith(sort.signal).map(sorted),
      rowKey = _.brokerId.value.toString,
      sort = sort,
      loading = Val(false),
      empty =
        () => EmptyState(Messages.BrokersEmptyTitle, description = Some(Messages.BrokersEmptyDescription)),
      testId = Some("brokers-table")
    )

    div(
      cls := ClustersCss.Page,
      dataAttr("testid") := "page-clusters-brokers",
      div(
        cls := ClustersCss.BrokersHeader,
        Breadcrumbs(Val(List(Crumb(Messages.Title, Some(backHref)), Crumb(Messages.BrokersTitle, None)))),
        h1(Messages.brokersHeading(cluster.value))
      ),
      // An unavailable section is an explanation, never an empty table: a broker table with no rows in it is
      // a claim that the cluster has no brokers.
      child.maybe <-- section.map(_.flatMap(unavailableNotice)),
      child.maybe <-- section.map(current => Option.when(current.exists(hasData))(strip(summary))),
      child.maybe <-- section.map(current =>
        Option.when(current.exists(hasData))(
          StaleDataOverlay(
            content = table,
            stale = section.map(_.flatMap(staleReason)),
            fetchedAt = section.map(_.flatMap(fetchedAt)),
            zone = zone,
            now = now,
            testId = Some("brokers-table-region")
          )
        )
      ),
      div(
        cls := ClustersCss.ScrapedAt,
        span(
          dataAttr("testid") := "brokers-scraped-at",
          text <-- section
            .combineWith(zone)
            .map((current, zoneId) => scrapedLine(current.flatMap(fetchedAt), zoneId, now()))
        ),
        // Beside the timestamp, where "how old is this" is already being answered.
        RefreshButton(
          new RefreshFlow(cluster, queries, section.map(_.flatMap(fetchedAt))),
          capability
        )
      )
    )
  }

  private def hasData(section: Section[List[BrokerDto]]): Boolean = section.toOption.isDefined

  private def fetchedAt(section: Section[List[BrokerDto]]): Option[Instant] =
    section match {
      case Section.Ok(_, at) => Some(at)
      case Section.Stale(_, at, _) => Some(at)
      case _ => None
    }

  private def staleReason(section: Section[List[BrokerDto]]): Option[StaleReason] =
    section match {
      case Section.Stale(_, _, reason) => Some(StaleReason.degraded(reason.wire))
      case _ => None
    }

  private def unavailableNotice(section: Section[List[BrokerDto]]): Option[HtmlElement] =
    section match {
      case Section.Unavailable(_, message, _) =>
        Some(
          div(
            cls := ClustersCss.Error,
            dataAttr("testid") := "brokers-unavailable",
            role := "alert",
            // The message the service sent, unedited: it is the string an operator can search for.
            p(Messages.brokersUnavailable(message))
          )
        )
      case Section.Forbidden =>
        Some(
          div(
            cls := ClustersCss.Error,
            dataAttr("testid") := "brokers-unavailable",
            role := "alert",
            p(Messages.BrokersForbidden)
          )
        )
      case _ => None
    }

  private def scrapedLine(at: Option[Instant], zone: String, now: Instant): String =
    at.fold(Timestamps.NeverRefreshed)(instant =>
      s"${Timestamps.lastUpdated(Some(instant), now)} (${Timestamps.absolute(instant, zone)})"
    )

  /** The strip above the table. */
  private def strip(summary: Signal[BrokerSummary]): HtmlElement =
    div(
      cls := ClustersCss.Summary,
      cls(ClustersCss.SummaryAlarm) <-- summary.map(BrokerSummary.hasAlarm),
      figure(
        "broker-summary-brokers",
        summary.map(_.brokerCount.toString),
        Messages.SummaryBrokers
      ),
      div(
        cls := ClustersCss.SummaryFigure,
        dataAttr("testid") := "broker-summary-controller",
        child <-- summary.map(current =>
          current.controller.fold(
            // The one thing on this strip that is worth interrupting somebody for.
            ThresholdValue(Val(Messages.NoActiveController), Val(ThresholdLevel.Critical))
          )(id => span(cls := ClustersCss.SummaryValue, id.value.toString))
        ),
        span(cls := ClustersCss.SummaryLabel, Messages.SummaryController)
      ),
      figure(
        "broker-summary-version",
        summary.map(_.version.getOrElse(DataTable.missing)),
        Messages.SummaryVersion
      ),
      figure(
        "broker-summary-controller-type",
        summary.map(_.controllerType.getOrElse(DataTable.missing)),
        Messages.SummaryControllerType
      ),
      figure(
        "broker-summary-partitions",
        summary.map(current => partitionsLine(current)),
        Messages.SummaryPartitions
      ),
      figure(
        "broker-summary-replicas",
        summary.map(current => ratio(current.inSyncReplicas, current.totalReplicas)),
        Messages.SummaryInSync
      )
    )

  private def figure(testId: String, value: Signal[String], label: String): HtmlElement =
    div(
      cls := ClustersCss.SummaryFigure,
      dataAttr("testid") := testId,
      span(cls := ClustersCss.SummaryValue, text <-- value),
      span(cls := ClustersCss.SummaryLabel, label)
    )

  private def partitionsLine(summary: BrokerSummary): String =
    (summary.onlinePartitions, summary.offlinePartitions) match {
      case (None, None) => DataTable.missing
      case (online, offline) => s"${online.getOrElse(0)} / ${online.getOrElse(0) + offline.getOrElse(0)}"
    }

  private def ratio(part: Option[Int], whole: Option[Int]): String =
    (part, whole) match {
      case (Some(a), Some(b)) => s"$a / $b"
      case _ => DataTable.missing
    }

  private def sorted(rows: List[BrokerRow], order: Option[Sort[String]]): List[BrokerRow] =
    order match {
      case None => rows
      case Some(Sort(column: String, direction)) =>
        val (present, absent) = rows.partition(row => key(column, row).isDefined)
        val ascending = present.sortBy(key(column, _))
        (if direction == SortOrder.Asc then ascending else ascending.reverse) ++ absent
    }

  private def key(column: String, row: BrokerRow): Option[(String, Double)] =
    column match {
      case "broker" => Some("" -> row.brokerId.value.toDouble)
      case "host" => Some(row.host.toLowerCase -> 0.0)
      case "port" => Some("" -> row.port.toDouble)
      case "rack" => row.rack.map(_.toLowerCase -> 0.0)
      case "disk" => row.diskUsageBytes.map("" -> _.toDouble)
      case "leaders" => row.leaderCount.map("" -> _.toDouble)
      case "leaderSkew" => row.leaderSkewPercent.map("" -> _)
      case "replicas" => row.replicaCount.map("" -> _.toDouble)
      case "inSync" => row.inSyncReplicaCount.map("" -> _.toDouble)
      case "replicaSkew" => row.replicaSkewPercent.map("" -> _)
      case _ => None
    }

  private def columns(
      cluster: ClusterId,
      largestDisk: Signal[Long],
      brokerHref: (ClusterId, BrokerId) => String,
      openBroker: (ClusterId, BrokerId) => Unit
  ): List[Column[BrokerRow]] =
    List(
      Column(
        id = "broker",
        header = Messages.ColumnBroker,
        sortable = true,
        render = row =>
          Seq[Modifier[HtmlElement]](
            dataAttr("testid") := s"broker-row-${row.brokerId.value}",
            a(
              href := brokerHref(cluster, row.brokerId),
              dataAttr("testid") := s"broker-row-${row.brokerId.value}-link",
              row.brokerId.value.toString,
              onClick.preventDefault.mapTo(()) --> Observer[Unit](_ => openBroker(cluster, row.brokerId))
            ),
            Option.when(row.isController)(Tag(Val(Messages.ControllerTag), tone = Tone.Info))
          )
      ),
      Column("host", Messages.ColumnHost, sortable = true, render = _.host),
      Column(
        "port",
        Messages.ColumnPort,
        sortable = true,
        align = ColumnAlign.Numeric,
        render = _.port.toString
      ),
      // Often `—`, and here the dash is the truth: many clusters set no rack at all.
      Column("rack", Messages.ColumnRack, sortable = true, render = _.rack.getOrElse(DataTable.missing)),
      Column(
        id = "disk",
        header = Messages.ColumnDisk,
        sortable = true,
        align = ColumnAlign.Numeric,
        render = row =>
          if row.diskUsageBytes.isEmpty then DataTable.missing
          else
            MagnitudeBar(
              value = Val(diskLine(row)),
              fraction = largestDisk.map(Bytes.fraction(row.diskUsageBytes, _)),
              inline = true
            )
      ),
      Column(
        "leaders",
        Messages.ColumnLeaders,
        sortable = true,
        align = ColumnAlign.Numeric,
        render = _.leaderCount.fold(DataTable.missing)(_.toString)
      ),
      skewColumn("leaderSkew", Messages.ColumnLeaderSkew, _.leaderSkewPercent),
      Column(
        "replicas",
        Messages.ColumnReplicas,
        sortable = true,
        align = ColumnAlign.Numeric,
        render = _.replicaCount.fold(DataTable.missing)(_.toString)
      ),
      Column(
        id = "inSync",
        header = Messages.ColumnInSync,
        sortable = true,
        align = ColumnAlign.Numeric,
        // Below the replica count means the cluster is running with less redundancy than it asked for: a
        // warning colour, not an alarm, because it is still serving every partition.
        render = row =>
          row.inSyncReplicaCount.fold[Modifier[HtmlElement]](DataTable.missing)(inSync =>
            ThresholdValue(
              Val(inSync.toString),
              Val(if row.replicaCount.exists(_ > inSync) then ThresholdLevel.Warning
              else ThresholdLevel.Normal)
            )
          )
      ),
      skewColumn("replicaSkew", Messages.ColumnReplicaSkew, _.replicaSkewPercent)
    )

  private def diskLine(row: BrokerRow): String =
    row.segmentCount match {
      case Some(segments) => s"${Bytes.format(row.diskUsageBytes)}, ${Messages.segments(segments)}"
      case None => Bytes.format(row.diskUsageBytes)
    }

  /** A skew column, threshold-coloured and only above the threshold.
    *
    * The header carries the explanation: a bare `12.4 %` in a column called "Leader skew" is a number nobody
    * can act on without being told what it is a percentage *of*.
    */
  private def skewColumn(
      id: String,
      header: String,
      percent: BrokerRow => Option[Double]
  ): Column[BrokerRow] =
    Column(
      id = id,
      header = header,
      sortable = true,
      align = ColumnAlign.Numeric,
      render = row =>
        Tooltip(
          trigger = ThresholdValue(
            Val(Skew.format(percent(row))),
            Val(Skew.level(percent(row))),
            testId = Some(s"broker-row-${row.brokerId.value}-$id")
          ),
          content = Val(Messages.SkewExplanation)
        )
    )
}
