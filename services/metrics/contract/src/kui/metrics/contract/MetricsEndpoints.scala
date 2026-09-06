package kui.metrics.contract

import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody

import kui.contracts.KernelSchemas.given
import kui.contracts.rbac.{EndpointAuthorization, ResourceRequirement}
import kui.contracts.{ErrorEnvelope, KuiEndpoint}
import kui.kernel.ClusterId
import kui.metrics.contract.dto.*
import kui.metrics.contract.dto.ThroughputRangeDto.given
import kui.metrics.contract.dto.ThroughputResponse.given
import kui.security.SignedPrincipal
import kui.security.rbac.{Action, Resource}

/** Everything `kui-metrics-service` reads, described once.
  *
  * The paths start at `/internal/v1`, not at `/api/v1`: the public prefix belongs to the gateway, which
  * rewrites the first segment of each service's published contract to build its public routes
  * (`ARCHITECTURE.md` §5).
  *
  * ==One endpoint, and the shape of the four that follow it==
  *
  * The metrics milestone adds latency, request handlers, top producers and the record-size histogram beside
  * this one. Each is another entry in [[all]] and another `Section`-wrapped response, which is the property
  * worth having early: one dead exporter costs one card, never a page.
  *
  * ==Every route here answers even when nothing is measured==
  *
  * A cluster with no `kui.metrics.sources` entry answers **200** with `not_configured`, not 404 and not 500.
  * That is not a degenerate case to be tidied away later — it is the state most deployments are in, and it is
  * what lets a dashboard card show its `NotMeasured` sentence instead of an empty axis (ADR-032).
  */
object MetricsEndpoints {

  val ClustersSegment: String = "clusters"
  val MetricsSegment: String = "metrics"
  val ThroughputSegment: String = "throughput"

  val ClusterIdParam: String = "clusterId"
  val RangeParam: String = "range"

  private val clustersBase = "internal" / "v1" / ClustersSegment

  private val clusterIdPath: EndpointInput[ClusterId] =
    path[ClusterId](ClusterIdParam).description("The configured cluster's slug id")

  private val rangeQuery: EndpointInput[ThroughputRangeDto] =
    query[ThroughputRangeDto](RangeParam)
      .description(s"How far back to reach: ${ThroughputRangeDto.Wires.mkString(", ")}")
      .default(ThroughputRangeDto.Default)

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
      .attribute(
        EndpointAuthorization.Key,
        // Unnamed: there is one set of broker metrics per cluster, so the access names no resource inside
        // it. The cluster gate in the path is what scopes it, exactly as it does for the ACL and audit
        // resources.
        EndpointAuthorization
          .one("metrics.throughput", ResourceRequirement.unnamed(Resource.Metrics, Action.MetricsView))
      )
      .name("metrics.throughput")
      .summary("The cluster's throughput over the requested window")
      .description(
        "Answers 200 with a not_configured section for a cluster that has no kui.metrics.sources entry, " +
          "which is a deployment choice rather than a failure: the card keeps its 'not measured' " +
          "sentence. A bucket KUI did not sample carries null rates, so a gap breaks the bar instead of " +
          "reading as a quiet cluster."
      )
      .tag("metrics")

  /** Every read endpoint this service serves, in the order a router must try them.
    *
    * One entry today. The order is still stated as contract because the gateway derives its proxy routes from
    * this list in this order (`ContractRouting.derive`), so it is also the order the public API is served in
    * — and the next endpoint added here inherits that guarantee rather than discovering it.
    */
  val all: List[AnyEndpoint] = List(throughput)
}
