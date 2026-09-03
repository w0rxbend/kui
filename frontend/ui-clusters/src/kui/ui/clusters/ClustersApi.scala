package kui.ui.clusters

import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody

import kui.cluster.contract.ClusterEndpoints
import kui.cluster.contract.dto.PingResponse
import kui.contracts.{ErrorEnvelope, KuiEndpoint, PublicApi}

/** The cluster service's endpoints as the *browser* calls them.
  *
  * ## Why this is not simply `ClusterEndpoints.ping`
  *
  * A browser never talks to a service. It talks to the gateway, which derives its public routes from each
  * service's published contract by rewriting the leading `/internal/v1` to `/api/v1` and replacing the signed
  * principal header — which the gateway mints and a browser must never send — with the session it already
  * holds (`ARCHITECTURE.md` §5, ADR-040). So the endpoint the browser calls has a different path and a
  * different security input from the one the service serves, and it cannot be the same value.
  *
  * What it *can* be is built from the same pieces, which is what this does: the path segment, the query
  * parameter name and the response type all come from `ClusterEndpoints` and its DTO. Renaming the parameter
  * in the contract stops this file compiling, which is the whole point of cross-compiling contracts.
  */
object ClustersApi {

  /** `GET /api/v1/ping?message=…` — the gateway's public face of `ClusterEndpoints.ping`. */
  val ping: PublicEndpoint[String, ErrorEnvelope, PingResponse, Any] =
    KuiEndpoint.base.get
      .in(PublicApi.prefix)
      .in(ClusterEndpoints.PingPath)
      .in(query[String](ClusterEndpoints.PingMessageParam))
      .out(jsonBody[PingResponse])
      .name("clusters.ping")
}
