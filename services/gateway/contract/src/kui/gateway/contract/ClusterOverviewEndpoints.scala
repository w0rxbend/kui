package kui.gateway.contract

import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody

import kui.contracts.ErrorEnvelope
import kui.gateway.contract.dto.ClusterOverviewDto

/** The dashboard's own endpoint, which the gateway answers rather than proxies.
  *
  * `/api/v1/clusters` is the one public path in M1 that is *not* a rewritten service path. The cluster
  * service's own list endpoint is excluded from the derived proxy routes (`ServiceContracts.aggregated`) and
  * this takes its place, because the answer a browser needs is not the answer one service gives: it is that
  * answer merged with what the gateway knows about its own ability to reach that service, plus the last rows
  * it saw for the case where it cannot reach it at all.
  *
  * It is declared on the public base rather than on `KuiEndpoint.internal`: this path belongs to the gateway,
  * and the gateway is the process a browser talks to.
  */
object ClusterOverviewEndpoints {

  val overview: PublicEndpoint[Unit, ErrorEnvelope, ClusterOverviewDto, Any] =
    GatewayEndpoints.base.get
      .in("clusters")
      .out(jsonBody[ClusterOverviewDto])
      .name("gateway.clusters.overview")
      .summary("Every configured cluster, with a status for the list and one for each row")
      .description(
        "Always answers 200. The outer section says whether the cluster service could be reached at all - " +
          "stale means these rows are the last ones that arrived, with the time they did. Each row's own " +
          "summary says whether that Kafka cluster could be reached. A cluster that is down costs its own " +
          "row's data and nothing else."
      )
      .tag("clusters")

  val all: List[AnyEndpoint] = List(overview)
}
