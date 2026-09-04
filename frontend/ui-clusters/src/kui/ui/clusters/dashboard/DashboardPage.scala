package kui.ui.clusters.dashboard

import java.time.Instant

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L.*

import kui.gateway.contract.dto.ClusterOverviewDto
import kui.kernel.{ClusterId, Sort, SortOrder}
import kui.ui.clusters.component.{Bytes, SectionChip}
import kui.ui.clusters.{ClustersCss, ClustersQueries, Messages}
import kui.ui.kernel.api.ApiError
import kui.ui.kernel.component.*
import kui.ui.kernel.query.QueryState
import kui.ui.kernel.time.Timestamps

/** The dashboard, which is also the cluster list.
  *
  * ## Why one screen and not two
  *
  * The reference product has a dashboard and a cluster list showing the same rows from the same call, and
  * keeping two screens consistent buys nothing. This is the screen the milestone is judged on: three
  * configured clusters, one of them unreachable, and the third row keeps its name and its link while the
  * other two carry their numbers.
  *
  * ## Four failures, four renderings
  *
  * Mixing these up is the failure this page exists to avoid.
  *
  *   1. **One cluster unreachable, the rest fine** — a chip and `—` cells on that row, inside a perfectly
  *      healthy 200. The page is not degraded; one row is.
  *   2. **The list call fails and a previous list is held** — the whole table goes under
  *      [[StaleDataOverlay]]: dimmed, timestamped, still readable, still sortable.
  *   3. **The list call fails and nothing was ever fetched** — an error region with the reason and a retry.
  *      Not an overlay, because there is nothing underneath it to keep.
  *   4. **The cluster service itself is unavailable** — the shell never routes here at all; it renders this
  *      feature's fallback panel.
  *
  * ## Nothing here polls
  *
  * The server re-reads each cluster on its own schedule and this page shows when that last happened. The
  * browser asks once per visit. The user's control over freshness is the refresh button, not a timer.
  */
object DashboardPage {

  /** @param queries
    *   the module's caches. The page subscribes to `queries.clusters` and asks for nothing else: one
    *   `GET /api/v1/clusters` fills every cell on the screen.
    * @param navigate
    *   how a row click reaches the brokers page. Passed in rather than reached for, so a suite can drive the
    *   page with no router and assert what a click actually did.
    * @param hrefFor
    *   the row's real `href`. A row has to be a link a user can copy, middle-click and bookmark, not a `div`
    *   with a click handler.
    * @param zone
    *   the IANA zone the timestamps render in.
    */
  def apply(
      queries: ClustersQueries,
      navigate: ClusterId => Unit,
      hrefFor: ClusterId => String,
      zone: Signal[String],
      now: () => Instant = () => Instant.now()
  ): HtmlElement = {
    val unavailableOnly = Var(false)
    val sort: Var[Option[Sort[String]]] = Var(None)

    val state: Signal[QueryState[ClusterOverviewDto]] = queries.clusters.state(())

    /** The rows the table draws: the last good response, filtered by the toggle.
      *
      * `lastGood` and not `outcome`, because that is the stale rule: when the newest call failed, the rows
      * the user was looking at stay on screen. The overlay says they are old; it does not remove them.
      */
    val rows: Signal[List[DashboardRow]] =
      state
        .map(_.lastGood.map(DashboardRow.of).getOrElse(Nil))
        .combineWith(unavailableOnly.signal)
        .map((all, filtered) => if filtered then DashboardRow.onlyUnavailable(all) else all)

    /** The scale every magnitude bar on screen is drawn against: the largest value among the rows currently
      * displayed, so filtering or sorting re-scales the column rather than leaving it compared to a row that
      * is no longer visible.
      */
    val largestDisk: Signal[Long] = rows.map(_.flatMap(_.diskUsageBytes).maxOption.getOrElse(0L))

    val table = DataTable[DashboardRow](
      columns = columns(largestDisk, hrefFor, navigate),
      rows = rows.combineWith(sort.signal).map(sorted),
      rowKey = _.clusterId.value,
      sort = sort,
      loading = Val(false),
      empty = () => EmptyState(Messages.EmptyTitle, description = Some(Messages.EmptyDescription)),
      testId = Some("clusters-table")
    )

    div(
      cls := ClustersCss.Page,
      dataAttr("testid") := "page-clusters-dashboard",
      h1(Messages.Title),
      summaryStrip(rows, state, zone, now),
      toggle(unavailableOnly),
      // Only when nothing has ever arrived. With rows in hand the overlay below is the right rendering, and
      // showing both would say the same failure twice.
      child.maybe <-- state
        .map(current =>
          Option.when(current.lastGood.isEmpty)(current.outcome.flatMap(_.left.toOption).map(firstLoadError))
        )
        .map(_.flatten),
      StaleDataOverlay(
        content = table,
        stale = state.map(staleReason),
        fetchedAt = state.map(_.lastGoodAt.map(Timestamps.instantOf)),
        zone = zone,
        now = now,
        testId = Some("clusters-table-region")
      )
    )
  }

  /** How many clusters are online, how many are not, and when the list was read. */
  private def summaryStrip(
      rows: Signal[List[DashboardRow]],
      state: Signal[QueryState[ClusterOverviewDto]],
      zone: Signal[String],
      now: () => Instant
  ): HtmlElement =
    div(
      cls := ClustersCss.Summary,
      div(
        cls := ClustersCss.SummaryFigure,
        dataAttr("testid") := "cluster-summary-online",
        span(cls := ClustersCss.SummaryValue, text <-- rows.map(DashboardRow.counts(_)._1.toString)),
        span(cls := ClustersCss.SummaryLabel, Messages.SummaryOnline)
      ),
      div(
        cls := ClustersCss.SummaryFigure,
        dataAttr("testid") := "cluster-summary-unavailable",
        span(cls := ClustersCss.SummaryValue, text <-- rows.map(DashboardRow.counts(_)._2.toString)),
        span(cls := ClustersCss.SummaryLabel, Messages.SummaryNotOnline)
      ),
      div(
        cls := ClustersCss.SummaryFetched,
        dataAttr("testid") := "cluster-summary-fetched",
        text <-- state
          .combineWith(zone)
          .map((current, zoneId) => fetchedLine(current.lastGoodAt.map(Timestamps.instantOf), zoneId, now()))
      )
    )

  private def fetchedLine(at: Option[Instant], zone: String, now: Instant): String =
    at.fold(Timestamps.NeverRefreshed)(instant =>
      s"${Timestamps.lastUpdated(Some(instant), now)} (${Timestamps.absolute(instant, zone)})"
    )

  private def toggle(unavailableOnly: Var[Boolean]): HtmlElement = {
    val id = Components.nextId("kui-clusters-toggle")
    div(
      cls := ClustersCss.Toggle,
      input(
        idAttr := id,
        tpe := "checkbox",
        dataAttr("testid") := "clusters-unavailable-only",
        controlled(checked <-- unavailableOnly.signal, onInput.mapToChecked --> unavailableOnly.writer)
      ),
      L.label(forId := id, Messages.UnavailableOnly)
    )
  }

  private def firstLoadError(failure: ApiError): HtmlElement =
    div(
      cls := ClustersCss.Error,
      dataAttr("testid") := "clusters-error",
      role := "alert",
      p(Messages.listFailed(describe(failure)))
    )

  /** Why the table is not being refreshed, or `None` when it is current.
    *
    * `isStale` and not "the last call failed": a key that has only ever failed has nothing to dim, and
    * putting a stale badge over an empty table would say the data is old when there is no data.
    */
  private def staleReason(state: QueryState[ClusterOverviewDto]): Option[StaleReason] =
    Option.when(state.isStale)(
      StaleReason.lastRequestFailed(
        state.outcome.flatMap(_.left.toOption).map(describe).getOrElse(Messages.UnknownFailure)
      )
    )

  private def describe(failure: ApiError): String =
    failure match {
      case ApiError.Envelope(_, text, _, _, _) => text
      case ApiError.Timeout => "the gateway did not answer in time"
      case ApiError.Unreachable(_) => "the gateway could not be reached"
      case ApiError.Decoding(_) => "the answer could not be read"
    }

  /** Client-side sorting over the rows already fetched.
    *
    * Deliberately still available while the data is stale. When the numbers are old, rearranging them is the
    * last thing a user can still do, and taking it away would leave them with a frozen screen and no controls
    * at all.
    *
    * Missing values sort last in *both* directions, so reversing the order never buries the rows that have
    * data under a wall of em dashes.
    */
  private def sorted(rows: List[DashboardRow], order: Option[Sort[String]]): List[DashboardRow] =
    order match {
      case None => rows
      case Some(Sort(column: String, direction)) =>
        // The rows that have a value are sorted and, for a descending sort, reversed. The rows that do not
        // are appended afterwards either way, so reversing the order never buries the rows that have data
        // under a wall of em dashes.
        val (present, absent) = rows.partition(row => key(column, row).isDefined)
        val ascending = present.sortBy(key(column, _))
        (if direction == SortOrder.Asc then ascending else ascending.reverse) ++ absent
    }

  /** The comparable key for a column: `None` where the row has no value. */
  private def key(column: String, row: DashboardRow): Option[(String, Double)] =
    column match {
      case "cluster" => Some(row.name.toLowerCase -> 0.0)
      case "status" => Some("" -> DashboardRow.statusOrder(row.status).toDouble)
      case "version" => row.version.map(_ -> 0.0)
      case "brokers" => row.brokerCount.map("" -> _.toDouble)
      case "partitions" => row.partitionCount.map("" -> _.toDouble)
      case "topics" => row.topicCount.map("" -> _.toDouble)
      case "groups" => row.consumerGroupCount.map("" -> _.toDouble)
      case "urp" => row.underReplicatedPartitions.map("" -> _.toDouble)
      case "disk" => row.diskUsageBytes.map("" -> _.toDouble)
      case _ => None
    }

  private def columns(
      largestDisk: Signal[Long],
      hrefFor: ClusterId => String,
      navigate: ClusterId => Unit
  ): List[Column[DashboardRow]] =
    List(
      Column(
        id = "cluster",
        header = Messages.ColumnCluster,
        sortable = true,
        render = row =>
          Seq[Modifier[HtmlElement]](
            dataAttr("testid") := s"cluster-row-${row.clusterId.value}",
            // A real link, so it can be copied, bookmarked and opened in a new tab. The click handler is
            // what keeps an ordinary click inside the application; the `href` is what makes it a link.
            a(
              href := hrefFor(row.clusterId),
              dataAttr("testid") := s"cluster-row-${row.clusterId.value}-link",
              row.name,
              onClick.preventDefault.mapTo(()) --> Observer[Unit](_ => navigate(row.clusterId))
            ),
            Option.when(row.readOnly)(Tag(Val(Messages.ReadOnly), tone = Tone.Neutral))
          )
      ),
      Column(
        id = "status",
        header = Messages.ColumnStatus,
        sortable = true,
        render =
          row => SectionChip(Val(row.status), testId = Some(s"cluster-row-${row.clusterId.value}-status"))
      ),
      Column(
        id = "version",
        header = Messages.ColumnVersion,
        sortable = true,
        render = row => row.version.getOrElse(DataTable.missing)
      ),
      Column(
        id = "brokers",
        header = Messages.ColumnBrokers,
        sortable = true,
        align = ColumnAlign.Numeric,
        render = row => row.brokerCount.fold(DataTable.missing)(_.toString)
      ),
      Column(
        id = "controller",
        header = Messages.ColumnController,
        align = ColumnAlign.Numeric,
        render = controllerCell
      ),
      Column(
        id = "partitions",
        header = Messages.ColumnPartitions,
        sortable = true,
        align = ColumnAlign.Numeric,
        // Filled from M4's dashboard aggregation: the gateway asks the topic service for each cluster's
        // topics and sums their partitions. Absent — never zero — when that service could not answer, or
        // when the cluster holds more topics than one page could sum, because a partial sum is a number
        // that looks exact and is not.
        render = row => row.partitionCount.fold(DataTable.missing)(_.toString)
      ),
      Column(
        id = "urp",
        header = Messages.ColumnUnderReplicated,
        sortable = true,
        align = ColumnAlign.Numeric,
        render = _ => DataTable.missing
      ),
      Column(
        id = "disk",
        header = Messages.ColumnDisk,
        sortable = true,
        align = ColumnAlign.Numeric,
        render = row =>
          if row.diskUsageBytes.isEmpty then DataTable.missing
          else
            MagnitudeBar(
              value = Val(Bytes.format(row.diskUsageBytes)),
              fraction = largestDisk.map(Bytes.fraction(row.diskUsageBytes, _)),
              inline = true
            )
      ),
      Column(
        id = "topics",
        header = Messages.ColumnTopics,
        sortable = true,
        align = ColumnAlign.Numeric,
        render = row => row.topicCount.fold(DataTable.missing)(_.toString)
      ),
      Column(
        id = "groups",
        header = Messages.ColumnConsumerGroups,
        sortable = true,
        align = ColumnAlign.Numeric,
        // Its own column and its own absence, because the consumer service fails independently of the
        // topic service: one dead service must cost this table one column and not two.
        render = row => row.consumerGroupCount.fold(DataTable.missing)(_.toString)
      )
    )

  /** The controller, or `none` in the warning colour.
    *
    * A KRaft cluster mid-failover genuinely has no controller for a moment, and that is worth seeing rather
    * than reading as a missing value: `—` would say "we do not know", and this says "there isn't one".
    */
  private def controllerCell(row: DashboardRow): Modifier[HtmlElement] =
    row.status match {
      case RowStatus.Online | RowStatus.Degraded(_) =>
        row.controller.fold[Modifier[HtmlElement]](
          ThresholdValue(Val(Messages.NoController), Val(ThresholdLevel.Warning))
        )(id => id.value.toString)
      case RowStatus.Unavailable(_, _) | RowStatus.Forbidden => DataTable.missing
    }
}
