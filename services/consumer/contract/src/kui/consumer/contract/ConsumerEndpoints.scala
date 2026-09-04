package kui.consumer.contract

import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody

import kui.consumer.contract.dto.*
import kui.consumer.contract.dto.ConsumerCodecs.given
import kui.contracts.consumer.{GroupSortField, TopicConsumersDto}
import kui.contracts.paging.PageDto
import kui.contracts.{ErrorEnvelope, KuiEndpoint}
import kui.kernel.group.GroupState
import kui.kernel.{ClusterId, GroupId, SortOrder, TopicName}
import kui.security.SignedPrincipal

/** What the consumer-group list request asks for, after the query string has been decoded.
  *
  * One record rather than six loose parameters so that `GroupListUseCase` takes a value with a name, and so
  * that adding a parameter later is a field rather than a seventh element of a tuple that every call site has
  * to be edited for.
  *
  * `pageSize` is an `Int` here and not a `PageSize`: a request for a larger page than the maximum is
  * *clamped* by `GroupQuery.normalise` in the application layer, not refused at the edge. Answering "you
  * asked for 900 rows and the limit is 500" with a 400 makes a caller write clamping code that the server
  * could have done once; answering with 500 rows and a `pageSize` of 500 in the response tells them the same
  * thing and still works.
  */
final case class GroupListParams(
    states: Set[GroupState],
    q: Option[String],
    sort: GroupSortField,
    direction: SortOrder,
    page: Int,
    pageSize: Int
)

/** Everything `kui-consumer-service` reads, described once.
  *
  * The paths start at `/internal/v1`, not at `/api/v1`. That is not a detail of this milestone: `/api/v1` is
  * the public prefix and it belongs to the gateway, which rewrites the first segment of each service's
  * published contract to build its public routes (`ARCHITECTURE.md` §5, and `PublicApi`'s own note, which
  * says in as many words that no service may declare the public prefix). The browser reaches these documents
  * through the gateway's contract module, which is what `ui-consumers` compiles against.
  *
  * That differs from GRP-025's sketch, which put `list`, `detail` and `lag` under `/api/v1` in this module.
  * Following the sketch would have given the consumer service two surfaces where the architecture allows it
  * one, and would have put a second declaration of the public prefix — the exact drift `PublicApi` exists to
  * prevent — in a service that must not know about it.
  *
  * `forTopic` is nonetheless different in kind from the other four, and its documentation says so: it feeds
  * the gateway's topic-overview aggregation and nothing else, so that `ui-topics` never learns this service's
  * routes and the Consumers tab stays a microfrontend guest rather than an import (DEVPLAN §10 D13).
  */
object ConsumerEndpoints {

  val ClustersSegment: String = "clusters"
  val GroupsSegment: String = "consumer-groups"
  val TopicsSegment: String = "topics"
  val LagSegment: String = "lag"

  val ClusterIdParam: String = "clusterId"
  val GroupIdParam: String = "groupId"
  val TopicParam: String = "topic"
  val StateParam: String = "state"
  val QueryParam: String = "q"
  val SortParam: String = "sort"
  val DirectionParam: String = "direction"
  val PageParam: String = "page"
  val PageSizeParam: String = "pageSize"
  val GroupParam: String = "group"
  val SinceParam: String = "since"

  /** ADR-026's defaults, named here because the endpoint declares them and the OpenAPI document prints them.
    */
  val DefaultPage: Int = 1
  val DefaultPageSize: Int = 25

  /** How many group ids one lag poll may name.
    *
    * GRP-025 assumed 200 would fit in the 8 KB every server and proxy accepts, and it does not: a group id is
    * up to 255 characters and the plan's own worst case of 40 gives `200 × len("group=" + 40 chars + "&")`,
    * which is 9 400 bytes before the path and the token. A request over the limit is silently truncated by
    * some proxies and rejected with a 414 by others, and neither failure is visible from the browser except
    * as rows that stop updating.
    *
    * 150 is the largest round number whose worst case stays under 8 KB with the path and the token included,
    * and `worstCaseLagUrlIsUnderEightKilobytes` in the suite is the assertion that keeps it true. A page
    * larger than this polls in more than one request; if that ever becomes the common case, the endpoint
    * becomes a `POST` and that test is what will say so.
    */
  val MaxLagGroups: Int = 150

  private val clustersBase = "internal" / "v1" / ClustersSegment

  private val clusterIdPath: EndpointInput[ClusterId] =
    path[ClusterId](ClusterIdParam).description("The configured cluster's slug id")

  private val groupIdPath: EndpointInput[GroupId] =
    path[GroupId](GroupIdParam).description("The consumer group id, as Kafka knows it")

  private val topicPath: EndpointInput[TopicName] =
    path[TopicName](TopicParam).description("The topic name")

  /** The list parameters, as one input.
    *
    * `state` **repeats** — `state=STABLE&state=EMPTY` — rather than taking a comma-separated list. A repeated
    * parameter is what OpenAPI, Tapir, every HTTP client and every proxy already agree on; the reference
    * product's comma list has to be split by hand on both sides, and the two hand-written splits disagree
    * about a group id containing a comma.
    */
  private val listParams: EndpointInput[GroupListParams] =
    query[Set[GroupState]](StateParam)
      .description(
        "Keep only groups in these states; repeat the parameter for more than one. Empty means all"
      )
      .and(
        query[Option[String]](QueryParam)
          .description("Substring match over the group id. Not over member hosts or topic names")
      )
      .and(
        query[GroupSortField](SortParam)
          .description("Which column to sort by")
          .default(GroupSortField.Default)
      )
      .and(query[SortOrder](DirectionParam).description("Which way to sort").default(SortOrder.Asc))
      .and(query[Int](PageParam).description("Which page, numbered from one").default(DefaultPage))
      .and(
        query[Int](PageSizeParam)
          .description("How many rows a page holds. A value above the maximum is clamped, not refused")
          .default(DefaultPageSize)
      )
      .map(GroupListParams.apply.tupled)(params =>
        (params.states, params.q, params.sort, params.direction, params.page, params.pageSize)
      )

  /** The consumer groups on one cluster, filtered, searched, sorted and paged.
    *
    * Served entirely from the 30-second snapshot: describing four thousand groups on the request path would
    * make this the slowest screen in KUI and would hammer the coordinators once per page view.
    */
  val list: Endpoint[SignedPrincipal, (ClusterId, GroupListParams), ErrorEnvelope, PageDto[
    kui.contracts.consumer.GroupSummaryDto
  ], Any] =
    KuiEndpoint.internal.get
      .in(clustersBase / clusterIdPath / GroupsSegment)
      .in(listParams)
      .out(jsonBody[GroupPageDto])
      .name("consumer.list")
      .summary("The cluster's consumer groups, with lag and pace")
      .description(
        "Read from the group snapshot rather than from a live describe, so the lag and the end offsets it " +
          "was computed against come from the same pass. A group whose lag could not be computed reports " +
          "null, never zero, and says how many partitions were excluded."
      )
      .tag("consumer")

  /** One group, whole. A deep link has to work without fetching the list first.
    *
    * It does **not** declare `KUI-GROUP-NOT-FOUND`. An unknown group id describes as a fabricated empty dead
    * group (`libs/kafka/PORT-INVARIANTS.md` §2), so an operator following a stale link gets an empty group
    * page rather than a 404, and declaring an error this endpoint cannot return would put a lie in the
    * OpenAPI document. The write endpoints, which must not touch a group that does not exist, confirm
    * existence with a listing instead (DEVPLAN §10 D5).
    */
  val detail: Endpoint[SignedPrincipal, (ClusterId, GroupId), ErrorEnvelope, GroupDetailDto, Any] =
    KuiEndpoint.internal.get
      .in(clustersBase / clusterIdPath / GroupsSegment / groupIdPath)
      .out(jsonBody[GroupDetailDto])
      .name("consumer.detail")
      .summary("One consumer group: members, assignments and per-partition lag")
      .description(
        "An unknown group answers 200 with an empty group in state DEAD, not 404: the port fabricates a " +
          "dead group for a describe of a group that does not exist, and a stale bookmark should show an " +
          "empty page rather than an error."
      )
      .tag("consumer")

  /** What changed since a token.
    *
    * A `GET` with a possibly long list of ids is deliberate: it is idempotent, cacheable by nothing, and fits
    * in a URL at the page sizes M4 allows — 200 groups of about 40 characters is well under the 8 KB every
    * server and proxy accepts. `worstCaseLagUrlIsUnderEightKilobytes` in the suite is what keeps that true;
    * if it ever stops being true this becomes a `POST`, and that test is what will say so.
    *
    * An empty `group` set means every group on the cluster. An unrecognised `since` token is answered with a
    * full payload and a fresh token, never with an error (DEVPLAN §10 D9).
    */
  val lag: Endpoint[
    SignedPrincipal,
    (ClusterId, Set[GroupId], Option[String]),
    ErrorEnvelope,
    LagDeltaDto,
    Any
  ] =
    KuiEndpoint.internal.get
      .in(clustersBase / clusterIdPath / GroupsSegment / LagSegment)
      .in(
        query[Set[GroupId]](GroupParam)
          .description("Only these groups; repeat the parameter. Empty means every group on the cluster")
      )
      .in(
        query[Option[String]](SinceParam)
          .description(
            "The opaque token from the previous answer. Absent, unrecognised or expired means send everything"
          )
      )
      .out(jsonBody[LagDeltaDto])
      .name("consumer.lag")
      .summary("Which groups' lag changed since the given token")
      .description(
        "The token is issued by this service and carries the snapshot version it was cut from. A client " +
          "clock is not a version: sending the browser's own timestamp back, as the reference product does, " +
          "silently drops or replays updates whenever the two clocks disagree."
      )
      .tag("consumer")

  /** The groups that consume one topic — the topic page's Consumers tab.
    *
    * The gateway calls this while assembling the topic overview and puts the answer in a `consumerGroups`
    * section; `ui-consumers` renders that section inside the kernel's `topic.tabs` slot. The browser never
    * calls it, which is what keeps `ui-topics` free of any knowledge of this service (DEVPLAN §10 D13).
    */
  val forTopic: Endpoint[SignedPrincipal, (ClusterId, TopicName), ErrorEnvelope, TopicConsumersDto, Any] =
    KuiEndpoint.internal.get
      .in(clustersBase / clusterIdPath / TopicsSegment / topicPath / GroupsSegment)
      .out(jsonBody[TopicConsumersDto])
      .name("consumer.forTopic")
      .summary("Every consumer group that reads this topic, with its lag on this topic alone")
      .description(
        "Feeds the gateway's topic-overview aggregation. A group's lag here is its lag on this topic, which " +
          "is not its total lag whenever it consumes more than one."
      )
      .tag("consumer")

  /** Every read endpoint this service serves. Nothing is routed that is not in this list. */
  val all: List[AnyEndpoint] = List(list, detail, lag, forTopic)
}
