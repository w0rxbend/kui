package kui.topic.contract

import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody

import kui.contracts.KernelSchemas.given
import kui.contracts.{ErrorEnvelope, KuiEndpoint}
import kui.kernel.{ClusterId, TopicName}
import kui.security.SignedPrincipal
import kui.topic.contract.dto.*

/** Everything `kui-topic-service` serves, described once.
  *
  * The same five values produce the service's routes, the gateway's proxy routes, the browser's client and
  * the OpenAPI document (ADR-003). Nothing is served that is not in [[all]], and no path is written out by
  * hand anywhere else — a hand-written path is a path that drifts from the handler it was supposed to name.
  *
  * The paths start at `/internal/v1`, not `/api/v1`: `/api/v1` is the public prefix and it belongs to the
  * gateway (`ARCHITECTURE.md` §5). A service is only ever called by the gateway, over the internal prefix,
  * with a signed principal header.
  *
  * ==Nothing here changes a Kafka cluster==
  *
  * There is no create, no edit, no delete, no partition increase and no replication-factor change, and there
  * is deliberately not an unimplemented declaration of one either. Those arrive in M5, together with
  * read-only mode and the audit trail, because `docs/ROADMAP.md` §3 orders no destructive action before its
  * safety net — and an endpoint that is declared is an endpoint somebody implements. `TopicEndpointsSuite`'s
  * `noEndpointMutates` is what makes that a fact rather than an intention: the one `POST` here asks this
  * service to re-read a cluster, and touches nothing on the cluster itself.
  */
object TopicEndpoints {

  val ClustersSegment: String = "clusters"
  val TopicsSegment: String = "topics"
  val ConfigSegment: String = "config"
  val PartitionsSegment: String = "partitions"
  val RefreshSegment: String = "refresh"

  val ClusterIdParam: String = "clusterId"
  val TopicNameParam: String = "topicName"

  private val topicsBase = "internal" / "v1" / ClustersSegment

  private val clusterIdPath: EndpointInput[ClusterId] =
    path[ClusterId](ClusterIdParam).description("The configured cluster's slug id")

  private val topicNamePath: EndpointInput[TopicName] =
    path[TopicName](TopicNameParam).description("The topic's name, as Kafka spells it")

  /** One page of the cluster's topics, filtered, searched and sorted. */
  val listTopics
      : Endpoint[SignedPrincipal, (ClusterId, TopicListParams), ErrorEnvelope, TopicsResponse, Any] =
    KuiEndpoint.internal.get
      .in(topicsBase / clusterIdPath / TopicsSegment)
      .in(TopicQueryCodecs.listParams)
      .out(jsonBody[TopicsResponse])
      .name("topic.list")
      .summary("One page of a cluster's topics")
      .description(
        "The page's total is counted after every filter, including the internal-topic filter, so the " +
          "page count always agrees with the rows. Read from a per-cluster snapshot: the response carries " +
          "when it was taken and whether that is current."
      )
      .tag("topic")

  /** One topic. A deep link has to work without fetching the page it would have been a row of. */
  val getTopic: Endpoint[SignedPrincipal, (ClusterId, TopicName), ErrorEnvelope, TopicDetailResponse, Any] =
    KuiEndpoint.internal.get
      .in(topicsBase / clusterIdPath / TopicsSegment / topicNamePath)
      .out(jsonBody[TopicDetailResponse])
      .name("topic.get")
      .summary("One topic, with the head of its partition table")
      .description(
        "Answers 404 when no such topic exists on the cluster, and a different 404 when no such cluster " +
          s"is configured. The embedded partition list stops at ${TopicDetailResponse.EmbeddedPartitionLimit} " +
          "and says so; the partitions endpoint returns them all."
      )
      .tag("topic")

  /** The Settings tab. */
  val topicConfig
      : Endpoint[SignedPrincipal, (ClusterId, TopicName), ErrorEnvelope, TopicConfigResponse, Any] =
    KuiEndpoint.internal.get
      .in(topicsBase / clusterIdPath / TopicsSegment / topicNamePath / ConfigSegment)
      .out(jsonBody[TopicConfigResponse])
      .name("topic.config")
      .summary("One topic's configuration keys, read-only")
      .description(
        "Read-only in M2: edits arrive in M5 with read-only mode and audit. A caller who may see the " +
          "topic but not its configuration gets a not_permitted view rather than a 403, so the rest of " +
          "the topic page keeps working. A key the broker marks sensitive carries no value at all."
      )
      .tag("topic")

  /** Every partition of one topic — the whole table, not the head of it. */
  val topicPartitions
      : Endpoint[SignedPrincipal, (ClusterId, TopicName), ErrorEnvelope, PartitionsResponse, Any] =
    KuiEndpoint.internal.get
      .in(topicsBase / clusterIdPath / TopicsSegment / topicNamePath / PartitionsSegment)
      .out(jsonBody[PartitionsResponse])
      .name("topic.partitions")
      .summary("Every partition of one topic, with leaders, replicas and offsets")
      .description(
        "A partition with no leader is reported with a null leader and no message count, never with " +
          "Kafka's node id -1 and never with a count computed from the partitions that did answer."
      )
      .tag("topic")

  /** Asks this service to re-read the cluster's topics now.
    *
    * 202, not 200: the answer means the request was taken, not that the snapshot is new. Per cluster and not
    * per topic, because the snapshot is per cluster (see [[dto.RefreshAcceptedDto]]).
    */
  val refresh: Endpoint[SignedPrincipal, ClusterId, ErrorEnvelope, RefreshAcceptedDto, Any] =
    KuiEndpoint.internal.post
      .in(topicsBase / clusterIdPath / TopicsSegment / RefreshSegment)
      .out(jsonBody[RefreshAcceptedDto])
      .out(statusCode(sttp.model.StatusCode.Accepted))
      .name("topic.refresh")
      .summary("Ask for this cluster's topics to be read now")
      .description(
        "Answers 202 with the time the request was taken. It changes nothing on the Kafka cluster: it " +
          "asks KUI to re-scrape it, and the response exists so a button has something truthful to say."
      )
      .tag("topic")

  /** Every endpoint this service serves. The gateway and the OpenAPI generator read this.
    *
    * The health and capability endpoints are deliberately absent: they are identical in every service and
    * come from `libs/http` rather than being redeclared once per service, which is how several copies of one
    * path end up disagreeing.
    */
  val all: List[AnyEndpoint] =
    List(listTopics, getTopic, topicConfig, topicPartitions, refresh)
}
