package kui.ui.clusters.brokers

import com.raquo.laminar.api.L.*

import kui.kernel.{BrokerId, ClusterId}
import kui.ui.clusters.{ClustersCss, ClustersQueries, Messages}
import kui.ui.kernel.component.{Breadcrumbs, Crumb, EmptyState}

/** One broker.
  *
  * Skeleton: the identity strip, the breadcrumbs back through the brokers list, and an empty region where the
  * log-directories and configuration tabs go. It exists now, with its route and its `history.state` codec,
  * for the reason the brokers page did: a row that is drawn as a link has to lead somewhere real on the day
  * it is drawn, and a placeholder that gets deleted later is not that.
  */
object BrokerDetailPage {

  def apply(
      cluster: ClusterId,
      broker: BrokerId,
      queries: ClustersQueries,
      clustersHref: String,
      brokersHref: String
  ): HtmlElement = {
    // Named so that the wiring the tabs will need is already the wiring this page has.
    val _ = queries

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
      EmptyState(Messages.BrokerEmptyTitle, description = Some(Messages.BrokerEmptyDescription))
    )
  }
}
