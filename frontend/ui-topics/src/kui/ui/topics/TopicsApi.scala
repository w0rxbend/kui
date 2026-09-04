package kui.ui.topics

import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody

import kui.contracts.KernelSchemas.given
import kui.contracts.{ErrorEnvelope, KuiEndpoint, PublicApi}
import kui.gateway.contract.TopicOverviewEndpoints
import kui.gateway.contract.dto.TopicOverviewDto
import kui.kernel.{ClusterId, TopicName}
import kui.topic.contract.dto.*
import kui.topic.contract.{TopicEndpoints, TopicListParams, TopicQueryCodecs}

/** The topic service's endpoints as the *browser* calls them.
  *
  * ## Why this is not simply `TopicEndpoints`
  *
  * A browser never talks to a service. It talks to the gateway, which derives its public routes from each
  * service's published contract by rewriting the leading `/internal/v1` to `/api/v1` and replacing the signed
  * principal header — which the gateway mints and a browser must never send — with the session it already
  * holds (`ARCHITECTURE.md` §5, ADR-040). The endpoint the browser calls therefore has a different path and a
  * different security input from the one the service serves, and it cannot be the same value.
  *
  * What it *can* be is built from the same pieces, which is what this does: every path segment, every query
  * parameter and every response type comes from `TopicEndpoints`, `TopicQueryCodecs` and their DTOs. Renaming
  * a segment in `services/topic/contract` stops this file compiling, which is the whole point of
  * cross-compiling a contract — and it is why a string literal like `"/api/v1/clusters"` anywhere in this
  * module is a review failure.
  *
  * ## What comes back
  *
  * Never a bare payload. A cluster the topic service could not scrape is a *section* of a healthy 200, and a
  * client that unwrapped it here would throw away the one distinction the whole screen is built on: rows that
  * are current, rows that are old, and no rows at all. An empty page from a cluster that has ten thousand
  * topics is a lie that looks like data, and it is exactly the shape of M1's second integration defect.
  */
object TopicsApi {

  private val clustersBase = PublicApi.prefix / TopicEndpoints.ClustersSegment

  private val clusterIdPath: EndpointInput[ClusterId] = path[ClusterId](TopicEndpoints.ClusterIdParam)

  private val topicNamePath: EndpointInput[TopicName] = path[TopicName](TopicEndpoints.TopicNameParam)

  private val topicsBase = clustersBase / clusterIdPath / TopicEndpoints.TopicsSegment

  private val oneTopic = topicsBase / topicNamePath

  /** `GET /api/v1/clusters/{clusterId}/topics` — one page of the list, searched, sorted and filtered.
    *
    * The query string comes from the contract's own `TopicQueryCodecs.listParams`, so the browser cannot ask
    * for a sort field the server does not accept, and a parameter renamed on the server stops this compiling.
    * That matters more here than anywhere else in the feature: the server *refuses* an unknown `sort` or
    * `mode` rather than substituting a default, so a drifted parameter name is a 400 on the screen the
    * milestone is judged on.
    */
  val list: PublicEndpoint[(ClusterId, TopicListParams), ErrorEnvelope, TopicsResponse, Any] =
    KuiEndpoint.base.get
      .in(topicsBase)
      .in(TopicQueryCodecs.listParams)
      .out(jsonBody[TopicsResponse])
      .name("topics.list")

  /** `GET /api/v1/clusters/{clusterId}/topics/{topicName}` — one topic and the head of its partition table.
    */
  val topic: PublicEndpoint[(ClusterId, TopicName), ErrorEnvelope, TopicDetailResponse, Any] =
    KuiEndpoint.base.get
      .in(oneTopic)
      .out(jsonBody[TopicDetailResponse])
      .name("topics.get")

  /** `GET /api/v1/clusters/{clusterId}/topics/{topicName}/overview` — everything the topic page shows.
    *
    * The one endpoint in this file whose response type is the **gateway's** and not the topic service's. The
    * other four are proxied through untouched, so the service's DTO is what arrives; this one the gateway
    * assembles itself, wrapping the topic in a section beside four more for the consumer, connect, security
    * and schema services. Decoding an aggregation against the owning service's type is what made the M1
    * dashboard render "No clusters yet" against a working broker, which is why this is spelled out rather
    * than assumed.
    *
    * The detail screen reads this rather than `topic` above, so one request fills the page including the tabs
    * M4 and M7 add — and the proxied `topic` endpoint stays available for a script or an MCP tool.
    *
    * It is built from `TopicOverviewEndpoints`' own path constants, and it is a *public* endpoint already —
    * the gateway answers it rather than proxying it, so unlike the four above there is no `/internal/v1` to
    * rewrite.
    */
  val overview: PublicEndpoint[(ClusterId, TopicName), ErrorEnvelope, TopicOverviewDto, Any] =
    TopicOverviewEndpoints.overview

  /** `GET /api/v1/clusters/{clusterId}/topics/{topicName}/config` — the Settings tab. */
  val config: PublicEndpoint[(ClusterId, TopicName), ErrorEnvelope, TopicConfigResponse, Any] =
    KuiEndpoint.base.get
      .in(oneTopic / TopicEndpoints.ConfigSegment)
      .out(jsonBody[TopicConfigResponse])
      .name("topics.config")

  /** `GET /api/v1/clusters/{clusterId}/topics/{topicName}/partitions` — every partition, not the head. */
  val partitions: PublicEndpoint[(ClusterId, TopicName), ErrorEnvelope, PartitionsResponse, Any] =
    KuiEndpoint.base.get
      .in(oneTopic / TopicEndpoints.PartitionsSegment)
      .out(jsonBody[PartitionsResponse])
      .name("topics.partitions")

  /** `POST /api/v1/clusters/{clusterId}/topics/refresh` — 202, and the answer is the time the request was
    * taken, not a promise that the snapshot is new.
    */
  val refresh: PublicEndpoint[ClusterId, ErrorEnvelope, RefreshAcceptedDto, Any] =
    KuiEndpoint.base.post
      .in(topicsBase / TopicEndpoints.RefreshSegment)
      .out(jsonBody[RefreshAcceptedDto])
      .name("topics.refresh")

  /** Every client this module has. The suite walks it, so a sixth endpoint cannot be added untested. */
  val all: List[AnyEndpoint] = List(list, topic, overview, config, partitions, refresh)
}
