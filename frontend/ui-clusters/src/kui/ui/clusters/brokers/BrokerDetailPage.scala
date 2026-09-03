package kui.ui.clusters.brokers

import java.time.Instant

import com.raquo.laminar.api.L.*

import kui.contracts.cluster.BrokerDto
import kui.kernel.{BrokerId, ClusterId}
import kui.ui.clusters.component.Bytes
import kui.ui.clusters.{ClustersCss, ClustersQueries, Messages}
import kui.ui.kernel.component.*

/** One broker, in detail.
  *
  * Two tabs, answering the two questions that bring somebody to a single machine: *why is this disk filling
  * up*, and *is this broker configured like its peers*.
  *
  * ## The tabs fail independently, and load independently
  *
  * They read two different endpoints. One being unavailable must not blank the other — a broker whose
  * settings cannot be read still has disks worth looking at — so the section handling is inside each tab,
  * with its own timestamp, rather than around the page.
  *
  * They also load separately: opening the page fetches the log directories, and the settings are fetched the
  * first time the Configs tab is opened. Somebody who came to look at disk usage should neither wait for a
  * `describeConfigs` call nor cause one. `Tabs` renders only the selected panel's body, and `QueryCache`
  * fetches on subscription, so the laziness falls out of the two working together rather than from a flag.
  *
  * ## No metrics tab
  *
  * Not disabled — absent. There is no metrics service for several milestones, and a permanently greyed tab
  * would be noise on every visit for all of them.
  */
object BrokerDetailPage {

  def apply(
      cluster: ClusterId,
      broker: BrokerId,
      tab: Signal[BrokerTab],
      selectTab: BrokerTab => Unit,
      queries: ClustersQueries,
      clustersHref: String,
      brokersHref: String,
      zone: Signal[String],
      now: () => Instant = () => Instant.now()
  ): HtmlElement = {

    /** This broker's row from the brokers list, which the previous screen already fetched. */
    val identity: Signal[Option[BrokerDto]] =
      queries.brokers
        .state(cluster)
        .map(_.lastGood.flatMap(_.brokers.toOption).flatMap(_.find(_.id == broker)))

    // `selectTab` navigates rather than writing to a `Var`, which is what stops the URL and the visible tab
    // from becoming two independent truths that drift apart on a Back button.
    val selected: Var[String] = Var(BrokerTab.idOf(BrokerTab.LogDirs))

    div(
      cls := ClustersCss.Page,
      dataAttr("testid") := "page-clusters-broker",
      div(
        cls := ClustersCss.BrokersHeader,
        Breadcrumbs(
          Val(
            List(
              Crumb(Messages.Title, Some(clustersHref)),
              Crumb(Messages.BrokersTitle, Some(brokersHref)),
              Crumb(broker.value.toString, None)
            )
          )
        ),
        h1(Messages.brokerHeading(cluster.value, broker.value))
      ),
      strip(identity),
      // The URL is the source of truth: the route's tab drives the control, and the control asks to
      // navigate. The two are never written independently.
      tab --> Observer[BrokerTab](current => selected.set(BrokerTab.idOf(current))),
      // Only a *change* the user made is navigated on, and only when it differs from the route: without
      // that guard the two binders above and below would push a history entry at each other for ever.
      selected.signal.changes
        .map(BrokerTab.fromId)
        .withCurrentValueOf(tab) --> Observer[(BrokerTab, BrokerTab)] { (wanted, current) =>
        if wanted != current then selectTab(wanted)
      },
      Tabs(
        tabs = Val(
          BrokerTab.values.toList.map(current =>
            Tab(
              id = BrokerTab.idOf(current),
              label = current.label,
              body = () => body(current, cluster, broker, queries, zone, now)
            )
          )
        ),
        selected = selected,
        testId = Some("broker-tabs")
      )
    )
  }

  private def body(
      tab: BrokerTab,
      cluster: ClusterId,
      broker: BrokerId,
      queries: ClustersQueries,
      zone: Signal[String],
      now: () => Instant
  ): HtmlElement =
    tab match {
      case BrokerTab.LogDirs => BrokerLogDirsTab(cluster, broker, queries, zone, now)
      case BrokerTab.Configs => BrokerConfigsTab(cluster, broker, queries, zone, now)
    }

  /** Host, port, rack, whether this is the controller, and what is on its disks.
    *
    * Read from the brokers list this page was almost certainly reached from, so arriving by a link costs
    * nothing; arriving on a bookmark costs one cached response. Every figure is `—` until it arrives, rather
    * than the page waiting behind a spinner for a strip.
    */
  private def strip(identity: Signal[Option[BrokerDto]]): HtmlElement =
    div(
      cls := ClustersCss.Summary,
      figure(
        "broker-identity-host",
        identity.map(_.map(_.host).getOrElse(DataTable.missing)),
        Messages.ColumnHost
      ),
      figure(
        "broker-identity-port",
        identity.map(_.map(_.port.toString).getOrElse(DataTable.missing)),
        Messages.ColumnPort
      ),
      figure(
        "broker-identity-rack",
        identity.map(_.flatMap(_.rack).getOrElse(DataTable.missing)),
        Messages.ColumnRack
      ),
      figure(
        "broker-identity-disk",
        identity.map(current => Bytes.format(current.flatMap(_.diskUsageBytes))),
        Messages.ColumnDisk
      ),
      figure(
        "broker-identity-segments",
        identity.map(_.flatMap(_.segmentCount).map(_.toString).getOrElse(DataTable.missing)),
        Messages.Segments
      ),
      div(
        cls := ClustersCss.SummaryFigure,
        dataAttr("testid") := "broker-identity-role",
        child.maybe <-- identity.map(current =>
          Option.when(current.exists(_.isController))(Tag(Val(Messages.ControllerTag), tone = Tone.Info))
        )
      )
    )

  private def figure(testId: String, value: Signal[String], label: String): HtmlElement =
    div(
      cls := ClustersCss.SummaryFigure,
      dataAttr("testid") := testId,
      span(cls := ClustersCss.SummaryValue, text <-- value),
      span(cls := ClustersCss.SummaryLabel, label)
    )
}
