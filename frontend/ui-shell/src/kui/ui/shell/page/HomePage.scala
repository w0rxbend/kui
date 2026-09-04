package kui.ui.shell.page

import java.time.Instant

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import com.raquo.laminar.api.L.*

import kui.contracts.Section
import kui.contracts.capability.ReasonCode
import kui.gateway.contract.ClusterOverviewEndpoints
import kui.gateway.contract.dto.{ClusterOverviewDto, ClusterOverviewRow, GroupTotalsDto, TopicTotalsDto}
import kui.kernel.group.GroupState
import kui.ui.kernel.api.{ApiClient, ApiError}
import kui.ui.kernel.component.*
import kui.ui.kernel.css.KernelCss
import kui.ui.kernel.query.{QueryCache, QueryState}
import kui.ui.kernel.time.Timestamps
import kui.ui.shell.ShellCss

/** The dashboard: the first screen anybody sees, and the one that has to be true.
  *
  * ==What it shows==
  *
  * The fleet in one strip — how many clusters are online, and the fleet's brokers, topics, partitions and
  * consumer groups — and then one card per cluster carrying that cluster's health, its broker count and
  * controller, its topic and partition totals with the biggest topics drawn as bars, and its consumer groups
  * counted by state with their total lag.
  *
  * The bars are the design's rule that a quantity is drawn as well as printed: comparing "48" against "112"
  * costs a reader two parses, and comparing two bars costs none. The figure is always printed too, because
  * the bar says *relative* size and nothing else.
  *
  * ==Every panel is statused on its own==
  *
  * This is the screen where the product's central claim is most visible, so no two failures are folded into
  * one. A cluster's brokers, its topics and its consumer groups are three sections of the gateway's answer
  * that fail separately, and each is drawn separately: a stopped consumer service costs this screen its
  * consumer panels and leaves every other figure standing, with the reason printed where the numbers were. A
  * section this deployment has no service for is hidden entirely rather than shown as an error (ADR-032) —
  * four permanent red panels train an operator to stop reading red.
  *
  * ==Nothing here is guessed==
  *
  * A fleet total is shown only when every cluster contributed to it; otherwise the tile reads `—` and says
  * how many clusters it could not count. A partial sum is worse than no sum, because it is a plausible number
  * that changes when an outage ends. The same rule governs a cluster's partition count, which the gateway
  * omits rather than estimating when the cluster has more topics than it summed. See [[DashboardTotals]].
  *
  * ==What this replaced==
  *
  * Until 2026-09-04 this page said "cluster overviews appear here once the clusters feature is installed" —
  * written in M0 when it was accurate, false from M1 onwards, and read by every newcomer as an instruction to
  * go and find an installation step that does not exist. It was then a signpost saying a dashboard was not
  * built yet. This is that dashboard.
  *
  * ==Why it does not name the clusters feature's page types==
  *
  * The links are plain `a` elements built from `uiPrefix`. Naming `ClustersPage` here would make a static
  * reference from the shell into a feature module, which pulls that whole feature into the `main.js` every
  * user downloads — the thing `checkBundleShape` exists to forbid. The document it reads is the *gateway's*
  * own (`ClusterOverviewEndpoints.overview`), which the shell already compiles against.
  *
  * @param uiPrefix
  *   where the application is mounted, without a trailing slash
  * @param api
  *   the gateway client, or `None` when the shell was built without one — a test harness, or a bootstrap that
  *   could not reach the gateway at all. The page then shows its signpost and no figures, rather than failing
  *   to build.
  * @param zone
  *   the IANA zone the "last read" timestamps render in
  */
object HomePage {

  /** How long an answer is trusted. The gateway re-reads each cluster on this cadence, so asking more often
    * returns the same bytes. Nothing on this page polls; the user's control over freshness is a reload.
    */
  val Cadence: FiniteDuration = 30.seconds

  def apply(
      uiPrefix: String,
      api: Option[ApiClient] = None,
      zone: Signal[String] = Val("UTC"),
      now: () => Instant = () => Instant.now()
  ): HtmlElement = {
    val header =
      div(
        cls := ShellCss.DashboardHeader,
        h1("Dashboard"),
        p(
          cls := ShellCss.DashboardLead,
          "Every cluster KUI is configured with, whether it is answering or not. Open one to reach its ",
          "brokers, its topics, the records inside them and its consumer groups."
        )
      )

    api match {
      case None =>
        div(cls := ShellCss.Page, dataAttr("testid") := "page-home", header, signpost(uiPrefix))

      case Some(client) =>
        val cache: QueryCache[Unit, ClusterOverviewDto] =
          QueryCache.make(
            _ => client.call(ClusterOverviewEndpoints.overview, ()),
            staleAfter = Cadence,
            maxEntries = 1
          )

        val state = cache.state(())
        val rows = state.map(_.lastGood.map(DashboardTotals.rowsOf).getOrElse(Nil))

        div(
          cls := ShellCss.Page,
          dataAttr("testid") := "page-home",
          header,
          summaryStrip(rows, state, zone, now),
          child.maybe <-- state.map(firstLoadError),
          div(
            cls := ShellCss.DashboardCards,
            dataAttr("testid") := "dashboard-cards",
            children <-- rows.map(_.map(card(_, uiPrefix, now))),
            child.maybe <-- rows.map(list => Option.when(list.isEmpty)(noClusters(uiPrefix)))
          )
        )
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The fleet strip

  private def summaryStrip(
      rows: Signal[List[ClusterOverviewRow]],
      state: Signal[QueryState[ClusterOverviewDto]],
      zone: Signal[String],
      now: () => Instant
  ): HtmlElement = {
    val totals = rows.map(DashboardTotals.of)

    div(
      cls := ShellCss.DashboardStrip,
      dataAttr("testid") := "dashboard-strip",
      tile(
        "Clusters online",
        totals.map(figures => Some(s"${figures.clustersOnline} of ${figures.clusters}")),
        totals.map(_ => None),
        "dashboard-total-clusters"
      ),
      tile(
        "Brokers",
        totals.map(_.brokers.map(_.toString)),
        totals.map(figures => uncounted(figures.missingBrokers)),
        "dashboard-total-brokers"
      ),
      tile(
        "Topics",
        totals.map(_.topics.map(_.toString)),
        totals.map(figures => uncounted(figures.missingTopics)),
        "dashboard-total-topics"
      ),
      tile(
        "Partitions",
        totals.map(_.partitions.map(_.toString)),
        totals.map(figures => uncounted(figures.missingPartitions)),
        "dashboard-total-partitions"
      ),
      tile(
        "Consumer groups",
        totals.map(_.consumerGroups.map(_.toString)),
        totals.map(figures => uncounted(figures.missingGroups)),
        "dashboard-total-groups"
      ),
      div(
        cls := ShellCss.DashboardFetched,
        dataAttr("testid") := "dashboard-fetched",
        text <-- state
          .combineWith(zone)
          .map((current, zoneId) =>
            current.lastGoodAt.map(Timestamps.instantOf) match {
              case None => Timestamps.NeverRefreshed
              case Some(at) =>
                s"${Timestamps.lastUpdated(Some(at), now())} (${Timestamps.absolute(at, zoneId)})"
            }
          )
      )
    )
  }

  /** Why a figure is missing, as a phrase, or nothing when it is complete.
    *
    * "3 clusters not counted" and not a silent dash. A reader who sees only `—` cannot tell "KUI does not
    * collect this" from "KUI could not reach three of your clusters just now", and those need different
    * actions.
    */
  private def uncounted(missing: Int): Option[String] =
    Option.when(missing > 0)(
      if missing == 1 then "1 cluster not counted" else s"$missing clusters not counted"
    )

  private def tile(
      label: String,
      value: Signal[Option[String]],
      note: Signal[Option[String]],
      testId: String
  ): HtmlElement =
    div(
      cls := ShellCss.DashboardTile,
      dataAttr("testid") := testId,
      span(cls := ShellCss.DashboardTileValue, text <-- value.map(_.getOrElse(Missing))),
      span(cls := ShellCss.DashboardTileLabel, label),
      child.maybe <-- note.map(_.map(span(cls := ShellCss.DashboardTileNote, _)))
    )

  // ---------------------------------------------------------------------------------------------
  // One cluster

  private def card(row: ClusterOverviewRow, uiPrefix: String, now: () => Instant): HtmlElement = {
    val id = row.cluster.id.value

    div(
      cls := ShellCss.DashboardCard,
      dataAttr("testid") := s"dashboard-card-$id",
      div(
        cls := ShellCss.DashboardCardHead,
        a(
          cls := ShellCss.DashboardCardName,
          href := s"$uiPrefix/clusters/$id/brokers",
          dataAttr("testid") := s"dashboard-card-$id-link",
          row.cluster.name
        ),
        healthChip(row, id),
        Option.when(row.cluster.readOnly)(Tag(Val("Read-only"), tone = Tone.Neutral))
      ),
      div(
        cls := ShellCss.DashboardPanels,
        healthPanel(row, id),
        topicPanel(row, id, uiPrefix, now),
        groupPanel(row, id, uiPrefix, now)
      )
    )
  }

  /** The cluster's own health, from the section its summary arrived in. */
  private def healthChip(row: ClusterOverviewRow, id: String): HtmlElement = {
    val (label, tone) = row.cluster.summary match {
      case Section.Ok(_, _) => ("Online", KernelCss.TagSuccess)
      case Section.Stale(_, _, reason) => (s"Degraded: ${describe(reason)}", KernelCss.TagWarning)
      case Section.Unavailable(_, message, _) => (s"Unavailable: $message", KernelCss.TagDanger)
      case Section.Forbidden => ("Forbidden", KernelCss.TagNeutral)
      case Section.NotConfigured => ("Not configured", KernelCss.TagNeutral)
    }
    Tag(Val(label), tone = Tone.Neutral, testId = Some(s"dashboard-card-$id-status"))
      .amend(cls := tone)
  }

  private def healthPanel(row: ClusterOverviewRow, id: String): Modifier[HtmlElement] =
    panel(
      "Cluster",
      row.cluster.summary,
      testId = s"dashboard-card-$id-cluster",
      link = None,
      body = summary =>
        List(
          figureRow("Brokers", Some(summary.brokerCount.toString)),
          figureRow(
            "Controller",
            // A KRaft cluster mid-failover genuinely has no controller for a moment. "none" and `—` are
            // different statements — "there isn't one" against "we do not know" — and only the first is
            // true here, because this section arrived.
            Some(summary.controllerId.fold("none")(_.value.toString))
          ),
          figureRow("Version", summary.version),
          figureRow("Disk", summary.totalDiskUsageBytes.map(bytes => Bytes.format(Some(bytes))))
        )
    )

  private def topicPanel(
      row: ClusterOverviewRow,
      id: String,
      uiPrefix: String,
      now: () => Instant
  ): Modifier[HtmlElement] =
    panel(
      "Topics",
      row.topics,
      testId = s"dashboard-card-$id-topics",
      link = Some(s"$uiPrefix/clusters/$id/topics" -> "All topics"),
      body = totals =>
        List(
          figureRow("Topics", Some(totals.topicCount.toString)),
          figureRow(
            "Partitions",
            // Absent rather than a sum over the page the gateway could read. See `TopicTotalsDto`.
            totals.partitionCount.map(_.toString)
          )
        ) ++ largestTopics(totals),
      note = totals =>
        Option.when(totals.partitionCount.isEmpty)(
          s"Partitions are not totalled: this cluster has ${totals.topicCount} topics and " +
            s"${totals.countedTopics} were read."
        ),
      now = now
    )

  /** The biggest topics on this cluster, as bars against the biggest of them.
    *
    * Scaled to the largest topic *on this card*, not across the fleet, because the question the card answers
    * is "where are this cluster's partitions" — and a bar scaled to some other cluster's monster topic would
    * flatten every bar here into a stub.
    */
  private def largestTopics(totals: TopicTotalsDto): List[HtmlElement] = {
    val largest = totals.largest.map(_.partitionCount).maxOption.getOrElse(0)
    if totals.largest.isEmpty then Nil
    else
      List(
        div(
          cls := ShellCss.DashboardBars,
          span(cls := ShellCss.DashboardBarsTitle, "Largest by partitions"),
          totals.largest.map(topic =>
            MagnitudeBar(
              value = Val(topic.partitionCount.toString),
              fraction = Val(Bytes.fractionOf(Some(topic.partitionCount), largest)),
              label = Some(Val(topic.name.value))
            )
          )
        )
      )
  }

  private def groupPanel(
      row: ClusterOverviewRow,
      id: String,
      uiPrefix: String,
      now: () => Instant
  ): Modifier[HtmlElement] =
    panel(
      "Consumer groups",
      row.consumerGroups,
      testId = s"dashboard-card-$id-groups",
      link = Some(s"$uiPrefix/clusters/$id/consumer-groups" -> "All groups"),
      body = totals =>
        List(
          figureRow("Groups", Some(totals.groupCount.toString)),
          figureRow("Total lag", totals.totalLag.map(_.toString))
        ) ++ stateChips(totals),
      note = totals =>
        Option.when(totals.totalLag.isEmpty && totals.groupCount > 0)(
          if totals.groupsWithoutLag > 0 then
            s"Lag is not totalled: ${totals.groupsWithoutLag} of ${totals.groupCount} groups reported none."
          else "Lag is not totalled: more groups than were read in one page."
        ),
      now = now
    )

  /** How many groups are in each state, as chips with a bar behind the count.
    *
    * States that do not occur are not drawn. A chip reading "Dead 0" on every dashboard is a chip nobody
    * reads, and it would push the state that does matter off the end of the row.
    */
  private def stateChips(totals: GroupTotalsDto): List[HtmlElement] =
    if totals.byState.isEmpty then Nil
    else
      List(
        div(
          cls := ShellCss.DashboardBars,
          span(cls := ShellCss.DashboardBarsTitle, "By state"),
          totals.byState.map(entry =>
            MagnitudeBar(
              value = Val(entry.count.toString),
              fraction = Val(Bytes.fractionOf(Some(entry.count), totals.byState.map(_.count).max)),
              label = Some(Val(stateLabel(entry.state))),
              accent = entry.state != GroupState.Stable
            )
          )
        )
      )

  /** A group state as a word rather than as Kafka's shouted wire spelling. */
  private def stateLabel(state: GroupState): String =
    state match {
      case GroupState.Stable => "Stable"
      case GroupState.Empty => "Empty"
      case GroupState.Dead => "Dead"
      case GroupState.PreparingRebalance => "Preparing rebalance"
      case GroupState.CompletingRebalance => "Completing rebalance"
      case GroupState.Unknown => "Unknown"
    }

  // ---------------------------------------------------------------------------------------------
  // One panel, with its own status

  /** One independently statused panel of a cluster card.
    *
    * The five section states each get their own rendering, and the differences are the point:
    *
    *   - `Ok` — the figures.
    *   - `Stale` — the same figures, dimmed, with when they were read and why they stopped moving. The
    *     numbers stay: a lag figure from four minutes ago is worth more than an empty box, as long as the
    *     screen says it is four minutes old.
    *   - `Unavailable` — the reason the service gave, verbatim (ADR-032), and no figures, because there are
    *     none.
    *   - `Forbidden` — a sentence saying so. Not an error: it is an answer.
    *   - `NotConfigured` — nothing at all. This deployment has no such service.
    */
  private def panel[A](
      title: String,
      section: Section[A],
      testId: String,
      link: Option[(String, String)],
      body: A => List[HtmlElement],
      note: A => Option[String] = (_: A) => None,
      now: () => Instant = () => Instant.now()
  ): Modifier[HtmlElement] =
    if !DashboardTotals.isPresent(section) then emptyNode
    else
      div(
        cls := ShellCss.DashboardPanel,
        dataAttr("testid") := testId,
        dataAttr("status") := section.status,
        div(
          cls := ShellCss.DashboardPanelHead,
          span(cls := ShellCss.DashboardPanelTitle, title),
          link.map((href0, label) => a(cls := ShellCss.DashboardPanelLink, href := href0, label))
        ),
        section match {
          case Section.Ok(data, _) =>
            div(body(data), note(data).map(text => p(cls := ShellCss.DashboardPanelNote, text)))

          case Section.Stale(data, at, reason) =>
            div(
              cls := ShellCss.DashboardPanelStale,
              p(
                cls := ShellCss.DashboardPanelReason,
                role := "status",
                dataAttr("testid") := s"$testId-stale",
                s"Last read ${Timestamps.relative(at, now())} — ${describe(reason)}"
              ),
              div(body(data)),
              note(data).map(text => p(cls := ShellCss.DashboardPanelNote, text))
            )

          case Section.Unavailable(_, message, _) =>
            p(
              cls := ShellCss.DashboardPanelReason,
              role := "status",
              dataAttr("testid") := s"$testId-unavailable",
              message
            )

          case Section.Forbidden =>
            p(cls := ShellCss.DashboardPanelReason, "You are not permitted to see this.")

          // Unreachable: filtered out above. Written rather than left to a wildcard so that a sixth
          // section state stops this compiling instead of rendering nothing.
          case Section.NotConfigured => emptyNode
        }
      )

  private def figureRow(label: String, value: Option[String]): HtmlElement =
    div(
      cls := ShellCss.DashboardFigure,
      span(cls := ShellCss.DashboardFigureLabel, label),
      span(cls := ShellCss.DashboardFigureValue, value.getOrElse(Missing))
    )

  // ---------------------------------------------------------------------------------------------

  /** What a cell reads when KUI does not have the number. Never `0`, which would be a claim. */
  val Missing: String = "—"

  private def signpost(uiPrefix: String): HtmlElement =
    Card(
      header = Some(h2("Start with your clusters")),
      body = div(
        p(
          "This build has no gateway client, so there are no figures to show. Every configured cluster is ",
          "on the ",
          a(href := s"$uiPrefix/clusters", "Clusters"),
          " page."
        )
      )
    )

  private def noClusters(uiPrefix: String): HtmlElement =
    div(
      dataAttr("testid") := "dashboard-empty",
      EmptyState(
        "No clusters yet",
        description = Some(
          "KUI is configured with no clusters, or the cluster service has not answered yet. " +
            "Configuration is in kui.clusters."
        )
      ),
      p(a(href := s"$uiPrefix/settings", "Settings"))
    )

  /** The whole list failed and nothing was ever fetched. An error region, not an overlay: there is nothing
    * underneath to keep.
    */
  private def firstLoadError(state: QueryState[ClusterOverviewDto]): Option[HtmlElement] =
    Option
      .when(state.lastGood.isEmpty)(state.outcome.flatMap(_.left.toOption))
      .flatten
      .map(failure =>
        div(
          cls := ShellCss.DashboardError,
          role := "alert",
          dataAttr("testid") := "dashboard-error",
          p(s"The cluster list could not be read: ${reason(failure)}")
        )
      )

  private def reason(failure: ApiError): String =
    failure match {
      case ApiError.Envelope(_, text, _, _, _) => text
      case ApiError.Timeout => "the gateway did not answer in time"
      case ApiError.Unreachable(_) => "the gateway could not be reached"
      case ApiError.Decoding(_) => "the answer could not be read"
    }

  /** A reason code as a short phrase. The wire name would be shouted; a chip has room for a phrase. */
  private def describe(code: ReasonCode): String =
    code match {
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
