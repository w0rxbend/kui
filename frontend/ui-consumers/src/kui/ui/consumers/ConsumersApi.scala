package kui.ui.consumers

import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody

import kui.consumer.contract.dto.ConsumerCodecs.given
import kui.consumer.contract.dto.{GroupDetailDto, GroupPageDto, LagDeltaDto}
import kui.consumer.contract.{ConsumerEndpoints, GroupListParams}
import kui.contracts.{ErrorEnvelope, KuiEndpoint, PublicApi}
import kui.gateway.contract.TopicOverviewEndpoints
import kui.gateway.contract.dto.TopicOverviewDto
import kui.kernel.{ClusterId, GroupId, TopicName}

/** The consumer service's endpoints as the *browser* calls them.
  *
  * ## Why this is not simply `ConsumerEndpoints`
  *
  * A browser never talks to a service. It talks to the gateway, which derives its public routes from each
  * service's published contract by rewriting the leading `/internal/v1` to `/api/v1` and replacing the signed
  * principal header — which the gateway mints and a browser must never send — with the session it already
  * holds (`ARCHITECTURE.md` §5, ADR-040). The endpoint the browser calls therefore has a different path and a
  * different security input from the one the service serves, and it cannot be the same value.
  *
  * What it *can* be is built from the same pieces, which is what this does: every path segment, every query
  * parameter and every response type comes from `ConsumerEndpoints` and its DTOs. Renaming a segment in
  * `services/consumer/contract` stops this file compiling, which is the whole point of cross-compiling a
  * contract — and it is why a string literal like `"/api/v1/clusters"` anywhere in this module is a review
  * failure.
  *
  * ## What comes back
  *
  * The contract's own types, decoded by the contract's own codecs. Nothing here declares a `case class` with
  * the same fields as a DTO. That is M1's second integration defect made impossible rather than merely
  * discouraged: the dashboard declared the cluster service's `ClustersResponse` while the gateway answered
  * with its own `ClusterOverviewDto`, every response decoded *successfully* into zero rows, and both modules'
  * suites were green because each tested itself against its own idea of the payload.
  */
object ConsumersApi {

  private val clustersBase = PublicApi.prefix / ConsumerEndpoints.ClustersSegment

  private val clusterIdPath: EndpointInput[ClusterId] = path[ClusterId](ConsumerEndpoints.ClusterIdParam)

  private val groupIdPath: EndpointInput[GroupId] = path[GroupId](ConsumerEndpoints.GroupIdParam)

  private val groupsBase = clustersBase / clusterIdPath / ConsumerEndpoints.GroupsSegment

  /** `GET /api/v1/clusters/{clusterId}/consumer-groups` — one page of the list, filtered, searched and
    * sorted.
    *
    * The query string is `ConsumerEndpoints.listParams`, the same input value the service serves, so the
    * browser cannot ask for a sort field the server does not accept and a parameter renamed on the server
    * stops this compiling. That matters here in particular: the server *refuses* an unknown `sort` rather
    * than substituting a default, so a drifted parameter name is a 400 on the screen, not a quiet reorder.
    */
  val list: PublicEndpoint[(ClusterId, GroupListParams), ErrorEnvelope, GroupPageDto, Any] =
    KuiEndpoint.base.get
      .in(groupsBase)
      .in(ConsumerEndpoints.listParams)
      .out(jsonBody[GroupPageDto])
      .name("consumer.list")

  /** `GET /api/v1/clusters/{clusterId}/consumer-groups/{groupId}` — one group, whole.
    *
    * An unknown group id answers 200 with an empty group in state `DEAD` rather than 404, which is the port's
    * documented behaviour and the reason a stale bookmark shows an empty group page instead of an error.
    */
  val detail: PublicEndpoint[(ClusterId, GroupId), ErrorEnvelope, GroupDetailDto, Any] =
    KuiEndpoint.base.get
      .in(groupsBase / groupIdPath)
      .out(jsonBody[GroupDetailDto])
      .name("consumer.detail")

  /** `GET /api/v1/clusters/{clusterId}/consumer-groups/lag` — what changed since a token.
    *
    * Called by `LagPoller`, once per interval, about exactly the groups the list has on screen. The `since`
    * token is the server's own and opaque to the browser — sending a browser clock back instead, as the
    * reference product does, silently drops or replays updates whenever the two clocks disagree. The answer
    * also names how long to wait before asking again, and the poller obeys it rather than holding an interval
    * of its own: that is the one mechanism by which a struggling consumer service can slow every open browser
    * down at once.
    */
  val lag: PublicEndpoint[(ClusterId, Set[GroupId], Option[String]), ErrorEnvelope, LagDeltaDto, Any] =
    KuiEndpoint.base.get
      .in(groupsBase / ConsumerEndpoints.LagSegment)
      .in(query[Set[GroupId]](ConsumerEndpoints.GroupParam))
      .in(query[Option[String]](ConsumerEndpoints.SinceParam))
      .out(jsonBody[LagDeltaDto])
      .name("consumer.lag")

  /** `GET /api/v1/clusters/{clusterId}/topics/{topicName}/overview` — what the topic page's Consumers tab
    * reads.
    *
    * It is the *gateway's* endpoint, taken whole rather than rebuilt, and that is the entire point of it.
    * This feature's own `forTopic` lives on `/internal/v1`, which no browser may call; the gateway calls it
    * while assembling the topic overview and puts the answer in the document's `consumerGroups` section. So
    * the tab reads the aggregation, and `ui-topics` never learns this service's routes — the Consumers tab
    * stays a microfrontend guest rather than an import (DEVPLAN §10 D13).
    *
    * The rows inside that section are `Json` on the gateway's type, because their shape belongs to this
    * service and the gateway must not declare it. `TopicConsumers.rowsOf` decodes them into the contract's
    * own `TopicConsumerRowDto`, which is the type the consumer service encodes them with.
    *
    * Reading the whole overview to render one tab costs nothing extra: the topic page has already fetched
    * this exact document to draw itself, and the two share the browser's HTTP cache and each other's cache
    * entry only by URL — which is why the tab's own cache holds it under the same key shape.
    */
  val topicOverview: PublicEndpoint[(ClusterId, TopicName), ErrorEnvelope, TopicOverviewDto, Any] =
    TopicOverviewEndpoints.overview

  /** Every client this module has. The suite walks it, so a fifth endpoint cannot be added untested. */
  val all: List[AnyEndpoint] = List(list, detail, lag, topicOverview)
}
