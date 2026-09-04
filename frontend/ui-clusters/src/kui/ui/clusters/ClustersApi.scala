package kui.ui.clusters

import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody

import kui.cluster.contract.{ClusterEndpoints, ClusterWriteEndpoints}
import kui.cluster.contract.dto.*
import kui.contracts.KernelSchemas.given
import kui.contracts.{ErrorEnvelope, KuiEndpoint, PublicApi}
import kui.gateway.contract.dto.ClusterOverviewDto
import kui.kernel.{BrokerId, ClusterId}

/** The cluster service's endpoints as the *browser* calls them.
  *
  * ## Why this is not simply `ClusterEndpoints`
  *
  * A browser never talks to a service. It talks to the gateway, which derives its public routes from each
  * service's published contract by rewriting the leading `/internal/v1` to `/api/v1` and replacing the signed
  * principal header — which the gateway mints and a browser must never send — with the session it already
  * holds (`ARCHITECTURE.md` §5, ADR-040). So the endpoint the browser calls has a different path and a
  * different security input from the one the service serves, and it cannot be the same value.
  *
  * What it *can* be is built from the same pieces, which is what this does: every path segment, every
  * parameter name and every response type comes from `ClusterEndpoints` and its DTOs. Renaming a segment in
  * the contract stops this file compiling, which is the whole point of cross-compiling contracts, and it is
  * why a string literal like `"/api/v1/clusters"` anywhere in this module is a review failure.
  *
  * ## What comes back
  *
  * Never a bare payload. A cluster the service cannot reach is a *section* of a healthy 200 — the list of
  * configured clusters comes from configuration overlaid by the metadata store and is available whenever the
  * service is, while each cluster's own summary is the part that needs a live broker. A client that unwrapped
  * that here would throw away the one distinction the dashboard is built on.
  */
object ClustersApi {

  private val clustersBase = PublicApi.prefix / ClusterEndpoints.ClustersSegment

  private val clusterIdPath: EndpointInput[ClusterId] = path[ClusterId](ClusterEndpoints.ClusterIdParam)

  private val brokerIdPath: EndpointInput[BrokerId] = path[BrokerId](ClusterEndpoints.BrokerIdParam)

  /** `GET /api/v1/clusters` — every configured cluster, each with its own section status.
    *
    * The one endpoint in this file whose response type is the *gateway's* and not the cluster service's. The
    * other five are proxied through untouched, so the service's DTO is what arrives; this one the gateway
    * assembles itself (CLAPI-007), wrapping the list in an outer `Section` that says whether the cluster
    * *service* answered and attaching each row's capability. Those are two different questions from "did this
    * Kafka cluster answer", and the dashboard needs both.
    *
    * It was previously declared as the service's `ClustersResponse`, whose decoder defaults a missing `items`
    * field to `Nil`. The gateway sends `clusters`, never `items`, so every response decoded successfully into
    * an empty list: against a healthy broker the dashboard drew "No clusters yet" under a "last updated just
    * now" timestamp, and nothing anywhere reported an error. Two modules were each self-consistent and the
    * seam between them was untested — which is why `ClustersApiSuite` now checks this endpoint against a
    * recorded gateway response.
    */
  val clusters: PublicEndpoint[Unit, ErrorEnvelope, ClusterOverviewDto, Any] =
    KuiEndpoint.base.get
      .in(clustersBase)
      .out(jsonBody[ClusterOverviewDto])
      .name("clusters.list")

  /** `GET /api/v1/clusters/{clusterId}` — one cluster, so a deep link does not fetch the other thirty-nine.
    */
  val cluster: PublicEndpoint[ClusterId, ErrorEnvelope, ClusterDetailResponse, Any] =
    KuiEndpoint.base.get
      .in(clustersBase / clusterIdPath)
      .out(jsonBody[ClusterDetailResponse])
      .name("clusters.get")

  /** `GET /api/v1/clusters/{clusterId}/brokers` */
  val brokers: PublicEndpoint[ClusterId, ErrorEnvelope, BrokersResponse, Any] =
    KuiEndpoint.base.get
      .in(clustersBase / clusterIdPath / ClusterEndpoints.BrokersSegment)
      .out(jsonBody[BrokersResponse])
      .name("clusters.brokers")

  /** `GET /api/v1/clusters/{clusterId}/brokers/{brokerId}/configs?docs=`
    *
    * The `docs` flag asks the broker for each setting's own description. It is off by default because the
    * option exists only from Kafka 2.6 and roughly doubles the size of the answer; the configs tab is the
    * only screen that turns it on.
    */
  val brokerConfigs
      : PublicEndpoint[(ClusterId, BrokerId, Boolean), ErrorEnvelope, BrokerConfigsResponse, Any] =
    KuiEndpoint.base.get
      .in(
        clustersBase / clusterIdPath / ClusterEndpoints.BrokersSegment / brokerIdPath /
          ClusterEndpoints.ConfigsSegment
      )
      .in(query[Boolean](ClusterEndpoints.DocsParam).default(false))
      .out(jsonBody[BrokerConfigsResponse])
      .name("clusters.broker.configs")

  /** `GET /api/v1/clusters/{clusterId}/log-dirs?brokerId=`
    *
    * One endpoint for the whole cluster with an optional broker filter, matching the contract: the brokers
    * *list* needs every broker's disk totals in one call, and two endpoints would make that page issue one
    * request per broker to fill a column.
    */
  val logDirs: PublicEndpoint[(ClusterId, Option[BrokerId]), ErrorEnvelope, LogDirsResponse, Any] =
    KuiEndpoint.base.get
      .in(clustersBase / clusterIdPath / ClusterEndpoints.LogDirsSegment)
      .in(query[Option[BrokerId]](ClusterEndpoints.BrokerIdParam))
      .out(jsonBody[LogDirsResponse])
      .name("clusters.logDirs")

  /** `POST /api/v1/clusters/{clusterId}/refresh` — 202, and the answer is the time the request was taken. */
  val refresh: PublicEndpoint[ClusterId, ErrorEnvelope, RefreshAcceptedDto, Any] =
    KuiEndpoint.base.post
      .in(clustersBase / clusterIdPath / ClusterEndpoints.RefreshSegment)
      .out(jsonBody[RefreshAcceptedDto])
      .name("clusters.refresh")

  // -----------------------------------------------------------------------------------------------
  // Administering the deployment's own cluster list
  // -----------------------------------------------------------------------------------------------

  /** `PUT /api/v1/clusters/{clusterId}` — register a cluster, or replace the one that is there.
    *
    * `If-Match` is required by the contract and is a plain header here rather than part of the body: the
    * version is metadata *about* the record, and a body that carried one could disagree with the record it
    * was replacing. `"0"` means "create; fail if it already exists", which keeps one code path instead of two
    * endpoints.
    *
    * The CSRF header the service declares is deliberately not declared here. `ApiClient` puts it on every
    * request that is not a `GET`, and a header declared in two places is a header that stops agreeing.
    */
  val put: PublicEndpoint[(ClusterId, String, ClusterWriteRequest), ErrorEnvelope, ClusterProfileDto, Any] =
    KuiEndpoint.base.put
      .in(clustersBase / clusterIdPath)
      .in(header[String](ClusterWriteEndpoints.IfMatchHeader))
      .in(jsonBody[ClusterWriteRequest])
      .out(jsonBody[ClusterProfileDto])
      .name("clusters.put")

  /** `DELETE /api/v1/clusters/{clusterId}` — remove a cluster from the metadata store. */
  val delete: PublicEndpoint[(ClusterId, String), ErrorEnvelope, Unit, Any] =
    KuiEndpoint.base.delete
      .in(clustersBase / clusterIdPath)
      .in(header[String](ClusterWriteEndpoints.IfMatchHeader))
      .name("clusters.delete")

  /** `POST /api/v1/clusters/connection-test` — can KUI reach this, with these settings?
    *
    * No cluster id anywhere, because the whole point is to answer before anything has been written. The
    * answer distinguishes "could not reach it" from "reached it and it refused our credentials", which are
    * the two different forms the operator has to go back to.
    */
  val probe: PublicEndpoint[ClusterWriteRequest, ErrorEnvelope, ConnectivityDto, Any] =
    KuiEndpoint.base.post
      .in(clustersBase / ClusterWriteEndpoints.ProbeSegment)
      .in(jsonBody[ClusterWriteRequest])
      .out(jsonBody[ConnectivityDto])
      .name("clusters.probe")

  /** Every client this module has. The suite walks it, so a tenth endpoint cannot be added untested. */
  val all: List[AnyEndpoint] =
    List(clusters, cluster, brokers, brokerConfigs, logDirs, refresh, put, delete, probe)
}
