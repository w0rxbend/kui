package kui.ui.clusters.brokers

import com.raquo.laminar.api.L.*

import kui.kernel.ClusterId
import kui.ui.clusters.{ClustersCss, ClustersQueries, Messages}
import kui.ui.kernel.component.{Breadcrumbs, Crumb, EmptyState}

/** One cluster's brokers.
  *
  * Skeleton: the heading, the breadcrumbs back to the dashboard, and an empty table. It exists now, with its
  * route and its `history.state` codec, because a dashboard row has to lead somewhere real — including the
  * row of a cluster that is down, which is the one the milestone's criterion is about. A row that led
  * nowhere, or to a placeholder that gets deleted later, would make the criterion untestable.
  *
  * The table is filled by the next task.
  */
object BrokersPage {

  def apply(
      cluster: Signal[ClusterId],
      queries: ClustersQueries,
      backHref: String
  ): HtmlElement = {
    // Named so the wiring the filled table will need is already the wiring this page has.
    val _ = queries

    div(
      cls := ClustersCss.Page,
      dataAttr("testid") := "page-clusters-brokers",
      div(
        cls := ClustersCss.BrokersHeader,
        Breadcrumbs(Val(List(Crumb(Messages.Title, Some(backHref)), Crumb(Messages.BrokersTitle, None)))),
        h1(text <-- cluster.map(id => Messages.brokersHeading(id.value)))
      ),
      EmptyState(Messages.BrokersEmptyTitle, description = Some(Messages.BrokersEmptyDescription))
    )
  }
}
