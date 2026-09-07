package kui.metrics.contract

import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody

import kui.contracts.KernelSchemas.given
import kui.contracts.rbac.{EndpointAuthorization, ResourceRequirement}
import kui.contracts.{ErrorEnvelope, KuiEndpoint}
import kui.kernel.ClusterId
import kui.metrics.contract.dto.*
import kui.metrics.contract.dto.LatencyResponse.given
import kui.metrics.contract.dto.RecordSizeResponse.given
import kui.metrics.contract.dto.RequestHandlersResponse.given
import kui.metrics.contract.dto.ThroughputResponse.given
import kui.metrics.contract.dto.TopProducersResponse.given
import kui.security.SignedPrincipal
import kui.security.rbac.{Action, Resource}

/** Everything `kui-metrics-service` reads, described once.
  *
  * The paths start at `/internal/v1`, not at `/api/v1`: the public prefix belongs to the gateway, which
  * rewrites the first segment of each service's published contract to build its public routes
  * (`ARCHITECTURE.md` §5).
  *
  * ==Five endpoints, and one reason they are five==
  *
  * Throughput, latency, request handlers, top producers and record size are five entries in [[all]] and five
  * `Section`-wrapped responses rather than one document with five fields. A JMX exporter is configured with a
  * whitelist and every deployment's is different, so a body that carries the byte rates and not the request
  * percentiles is an ordinary configuration; five sections means that costs one card and never a page. It is
  * also what lets the browser ask for the two cards a tab is showing rather than for all five.
  *
  * ==Every route here answers even when nothing is measured==
  *
  * A cluster with no `kui.metrics.sources` entry answers **200** with `not_configured`, not 404 and not 500.
  * That is not a degenerate case to be tidied away later — it is the state most deployments are in, and it is
  * what lets a dashboard card show its `NotMeasured` sentence instead of an empty axis (ADR-032).
  *
  * ==Three of these are drawn differently from the design, and it is written down==
  *
  * ADR-052 records what a Kafka broker publishes and what it does not: there is no purgatory percentage, no
  * per-`client.id` byte rate and no record-size distribution. Each field below is named for what it actually
  * holds, and the cards' titles follow the fields rather than the other way round.
  */
object MetricsEndpoints {

  val ClustersSegment: String = "clusters"
  val MetricsSegment: String = "metrics"
  val ThroughputSegment: String = "throughput"
  val LatencySegment: String = "latency"
  val RequestHandlersSegment: String = "request-handlers"
  val ProducersSegment: String = "producers"
  val RecordSizeSegment: String = "record-size"

  val ClusterIdParam: String = "clusterId"
  val RangeParam: String = "range"
  val WindowParam: String = "window"
  val TopParam: String = "top"

  /** How many topics the top-producers card asks for, and the most it may.
    *
    * The card is a short list beside a chart, so five is what it draws. The ceiling is refused rather than
    * clamped, for `SearchEndpoints`' reason: a request for two hundred that quietly answered fifty would look
    * like a cluster with fewer busy topics than it has.
    */
  val DefaultTop: Int = 5
  val MaxTop: Int = 50

  private val clustersBase = "internal" / "v1" / ClustersSegment

  private val clusterIdPath: EndpointInput[ClusterId] =
    path[ClusterId](ClusterIdParam).description("The configured cluster's slug id")

  /** The range vocabulary as a query parameter, under whichever name the endpoint gives it.
    *
    * One vocabulary and two parameter names — `?range=` on throughput and `?window=` on latency, which is
    * what the design's two controls are called. That is a wire decision rather than an oversight: the two
    * charts are stacked on one screen and a `24h` that meant a different window in each would be two axes a
    * reader compares without being able to see that they differ (ADR-052 §4).
    *
    * The codec is built per name rather than summoned, so that a refusal names the parameter the caller
    * actually sent. A shared codec answered `?window=90d` with a `details[0].field` of `range`, which sends
    * whoever reads it to look at a parameter that is not in their URL.
    */
  private def rangeQueryNamed(name: String): EndpointInput[ThroughputRangeDto] =
    query[ThroughputRangeDto](name)(using Codec.listHead(using ThroughputRangeDto.codecFor(name)))
      .description(s"How far back to reach: ${ThroughputRangeDto.Wires.mkString(", ")}")
      .default(ThroughputRangeDto.Default)

  private val rangeQuery: EndpointInput[ThroughputRangeDto] = rangeQueryNamed(RangeParam)

  private val windowQuery: EndpointInput[ThroughputRangeDto] = rangeQueryNamed(WindowParam)

  private val topQuery: EndpointInput[Int] =
    query[Int](TopParam)
      .description(s"How many topics to return, 1 to $MaxTop")
      .default(DefaultTop)
      .validate(Validator.min(1).and(Validator.max(MaxTop)))

  /** Bytes in, bytes out and records per second over the requested window.
    *
    * The rates are bucketed rather than returned as raw samples, because the step belongs to the range and a
    * browser that bucketed for itself would be a second implementation of the rule that a bucket nobody
    * sampled is a gap. A gap has to break the bar; a zero would claim the cluster was idle.
    */
  val throughput: Endpoint[
    SignedPrincipal,
    (ClusterId, ThroughputRangeDto),
    ErrorEnvelope,
    ThroughputResponse,
    Any
  ] =
    KuiEndpoint.internal.get
      .in(clustersBase / clusterIdPath / MetricsSegment / ThroughputSegment)
      .in(rangeQuery)
      .out(jsonBody[ThroughputResponse])
      .attribute(EndpointAuthorization.Key, viewing("metrics.throughput"))
      .name("metrics.throughput")
      .summary("The cluster's throughput over the requested window")
      .description(
        "Answers 200 with a not_configured section for a cluster that has no kui.metrics.sources entry, " +
          "which is a deployment choice rather than a failure: the card keeps its 'not measured' " +
          "sentence. A bucket KUI did not sample carries null rates, so a gap breaks the bar instead of " +
          "reading as a quiet cluster."
      )
      .tag("metrics")

  /** The p99 of produce and consumer-fetch request time over the requested window.
    *
    * The percentile is the broker's own (`RequestMetrics.TotalTimeMs.99thPercentile`) and is never derived
    * here: a p99 assembled from a mean and a maximum has no relationship to any request. Where several
    * scrapes land in one step the bucket carries the *worst* of their percentiles, because the mean of four
    * p99s is a p99 of nothing.
    */
  val latency: Endpoint[
    SignedPrincipal,
    (ClusterId, ThroughputRangeDto),
    ErrorEnvelope,
    LatencyResponse,
    Any
  ] =
    KuiEndpoint.internal.get
      .in(clustersBase / clusterIdPath / MetricsSegment / LatencySegment)
      .in(windowQuery)
      .out(jsonBody[LatencyResponse])
      .attribute(EndpointAuthorization.Key, viewing("metrics.latency"))
      .name("metrics.latency")
      .summary("The cluster's p99 request latency over the requested window")
      .description(
        "Two series, produce and consumer fetch, on the same axis as throughput. A step KUI did not " +
          "sample carries null, which breaks the line rather than drawing an instant answer. An " +
          "unavailable section here means the exporter answered and publishes no RequestMetrics family " +
          "at all, so the whitelist is what has to change."
      )
      .tag("metrics")

  /** The broker's idle ratios and the depth of each delayed-operation queue, as of the last scrape.
    *
    * The ratios are ratios in `0..1` and not pre-formatted percentages, and purgatory is a count of parked
    * requests rather than a percentage of anything — the broker publishes no ceiling to divide it by
    * (ADR-052).
    */
  val requestHandlers: Endpoint[
    SignedPrincipal,
    ClusterId,
    ErrorEnvelope,
    RequestHandlersResponse,
    Any
  ] =
    KuiEndpoint.internal.get
      .in(clustersBase / clusterIdPath / MetricsSegment / RequestHandlersSegment)
      .out(jsonBody[RequestHandlersResponse])
      .attribute(EndpointAuthorization.Key, viewing("metrics.requestHandlers"))
      .name("metrics.requestHandlers")
      .summary("How idle the broker's request handlers are, and how deep its purgatories are")
      .description(
        "Idle figures are ratios in 0..1 exactly as the broker publishes them, so the caller chooses the " +
          "rounding. Purgatory is a count of parked requests per delayed operation and never a " +
          "percentage: Kafka publishes a queue length and no ceiling to divide it by (ADR-052)."
      )
      .tag("metrics")

  /** The topics receiving the most bytes, as of the last scrape.
    *
    * Topics and not client ids. A broker publishes no per-`client.id` byte rate unless client quotas are
    * configured, and `measuredBy` says which of the two the caller is looking at (ADR-052).
    */
  val producers: Endpoint[SignedPrincipal, (ClusterId, Int), ErrorEnvelope, TopProducersResponse, Any] =
    KuiEndpoint.internal.get
      .in(clustersBase / clusterIdPath / MetricsSegment / ProducersSegment)
      .in(topQuery)
      .out(jsonBody[TopProducersResponse])
      .attribute(EndpointAuthorization.Key, viewing("metrics.producers"))
      .name("metrics.producers")
      .summary("The topics receiving the most traffic")
      .description(
        "Ranked by the broker's one-minute bytes-in rate per topic. `measuredBy` is `topic`: a Kafka " +
          "broker publishes no per-client.id byte rate unless quotas are configured (ADR-052), so this " +
          "is not a list of clients. An empty list means the exporter served the family and no per-topic " +
          "line, which is either a cluster where nothing is producing or a ruleset with no per-topic " +
          "rule; an unavailable section is an exporter that does not publish the family at all."
      )
      .tag("metrics")

  /** The mean size of a record, and the two rates it was divided from.
    *
    * A mean and never a distribution: Kafka publishes no record-size histogram at all, so there is no `p50`,
    * no `p99` and no `max` on this response and the card says so in words (ADR-052).
    */
  val recordSize: Endpoint[SignedPrincipal, ClusterId, ErrorEnvelope, RecordSizeResponse, Any] =
    KuiEndpoint.internal.get
      .in(clustersBase / clusterIdPath / MetricsSegment / RecordSizeSegment)
      .out(jsonBody[RecordSizeResponse])
      .attribute(EndpointAuthorization.Key, viewing("metrics.recordSize"))
      .name("metrics.recordSize")
      .summary("The mean size of a record on this cluster")
      .description(
        "bytes in over records in, with both rates beside it so a caller can say what the figure is. " +
          "There is no percentile and no histogram here because Kafka publishes none (ADR-052); a card " +
          "that needs a distribution keeps its 'not measured' sentence."
      )
      .tag("metrics")

  /** Every read endpoint this service serves, in the order a router must try them.
    *
    * The order is contract because the gateway derives its proxy routes from this list in this order
    * (`ContractRouting.derive`), so it is also the order the public API is served in. Throughput stays first
    * because it is the one every deployment can answer.
    */
  val all: List[AnyEndpoint] = List(throughput, latency, requestHandlers, producers, recordSize)

  /** The permission every read here needs, declared once.
    *
    * Unnamed: there is one set of broker metrics per cluster, so the access names no resource inside it. The
    * cluster gate in the path is what scopes it, exactly as it does for the ACL and audit resources. Written
    * as one function so that a sixth endpoint cannot arrive with a subtly different requirement — and so that
    * `MetricsContractSuite`'s "every endpoint declares a permission" has one place to fail.
    */
  private def viewing(operation: String): EndpointAuthorization =
    EndpointAuthorization.one(operation, ResourceRequirement.unnamed(Resource.Metrics, Action.MetricsView))
}
