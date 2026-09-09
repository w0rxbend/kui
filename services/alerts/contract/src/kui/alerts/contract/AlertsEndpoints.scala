package kui.alerts.contract

import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody

import kui.alerts.contract.dto.*
import kui.alerts.contract.dto.AcknowledgementDto.given
import kui.alerts.contract.dto.AlertFeedResponse.given
import kui.contracts.KernelSchemas.given
import kui.contracts.rbac.{EndpointAuthorization, ResourceRequirement}
import kui.contracts.{ErrorEnvelope, KuiEndpoint}
import kui.kernel.ClusterId
import kui.security.SignedPrincipal
import kui.security.rbac.{Action, Resource}

/** Everything `kui-alerts-service` serves, described once.
  *
  * The paths start at `/internal/v1`, not at `/api/v1`: the public prefix belongs to the gateway, which
  * rewrites the first segment of each service's published contract to build its public routes
  * (`ARCHITECTURE.md` §5).
  *
  * ==Two endpoints, one read and one write==
  *
  * The read is one document rather than five, which is the opposite of the metrics service's shape and for
  * the opposite reason. Metrics has five endpoints because a JMX exporter's whitelist decides which families
  * exist, so a deployment can genuinely have latency and not throughput. The alerts rules all read facts
  * every Kafka cluster publishes, so there is no configuration that produces three of the four; what varies
  * is whether a *call* answered, and that is a per-rule `Section` **inside** the document rather than four
  * endpoints outside it. The card, the bell and the notifications panel all draw from one number and one
  * list, and three requests for one screen is how they come to disagree (ADR-053 §7).
  *
  * The write is exactly one. There is no second endpoint for the read marker: `markRead` is a parameter of
  * the read, because there is no `ALERTS:READ` action in `libs/security-core`'s vocabulary and
  * `ALERTS:ACKNOWLEDGE` is emphatically the wrong one to reuse — reading a feed is not acknowledging what is
  * in it. ADR-053 §6 argues the trade, including the part that is a real cost: a GET with a side effect on
  * KUI's own bookkeeping is unusual, and it carries no CSRF header.
  */
object AlertsEndpoints {

  val ClustersSegment: String = "clusters"
  val AlertsSegment: String = "alerts"
  val EventsSegment: String = "events"
  val AcknowledgementSegment: String = "acknowledgement"

  val ClusterIdParam: String = "clusterId"
  val EventIdParam: String = "eventId"
  val LimitParam: String = "limit"
  val MarkReadParam: String = "markRead"

  /** How many rows the feed answers with, and the most it may.
    *
    * Fifty, because the card draws five and the Alerts tab draws a screenful, and a caller that wants the
    * whole week asks for it. The ceiling is **refused** rather than clamped, for `SearchEndpoints`' reason: a
    * request for a thousand that quietly answered two hundred would look like a cluster with fewer events
    * than it has, and an alerts feed that under-reports is the one kind of screen that must not.
    */
  val DefaultLimit: Int = 50
  val MaxLimit: Int = 200

  /** The operation name. It is the string the audit line carries and is spelled the way a
    * `kui.security.audit.MutationKind.operation` is spelled — see `AcknowledgementRecord` for why this
    * service writes a record of its own rather than a `MutationRecord`, and for the one line that changes it.
    */
  val AcknowledgeOperation: String = "alerts.event.acknowledge"

  private val clustersBase = "internal" / "v1" / ClustersSegment

  private val clusterIdPath: EndpointInput[ClusterId] =
    path[ClusterId](ClusterIdParam).description("The configured cluster's slug id")

  private val eventIdPath: EndpointInput[String] =
    path[String](EventIdParam).description("The alert event's id, as the feed reported it")

  private val limitQuery: EndpointInput[Int] =
    query[Int](LimitParam)
      .description(s"How many events to return, 1 to $MaxLimit")
      .default(DefaultLimit)
      .validate(Validator.min(1).and(Validator.max(MaxLimit)))

  private val markReadQuery: EndpointInput[Boolean] =
    query[Boolean](MarkReadParam)
      .description(
        "When true, this principal's read marker moves to now after unreadCount has been computed, so " +
          "the caller still learns how many it had not seen. Default false, so a card polling the feed " +
          "does not clear somebody's bell"
      )
      .default(false)

  /** The feed, the counts and the per-rule report.
    *
    * A cluster with no events answers `ok` with an empty list and a zero open count, beside an `evaluatedAt`
    * that says when the rules last ran — which is what makes the zeros readable. It is never a 404 and never
    * an empty body: the alerts card sits on a dashboard beside cards that work, and a 4xx would make a feed
    * behaving exactly as designed indistinguishable from a broken one.
    */
  val events: Endpoint[SignedPrincipal, (ClusterId, Int, Boolean), ErrorEnvelope, AlertFeedResponse, Any] =
    KuiEndpoint.internal.get
      .in(clustersBase / clusterIdPath / AlertsSegment / EventsSegment)
      .in(limitQuery)
      .in(markReadQuery)
      .out(jsonBody[AlertFeedResponse])
      .attribute(EndpointAuthorization.Key, viewing("alerts.events"))
      .name("alerts.events")
      .summary("What KUI has noticed about this cluster")
      .description(
        "Newest first, resolved events included: the design's card draws five rows of which two are " +
          "resolved. openCount and unreadCount are counted over the whole store rather than over the " +
          "page. Each rule carries its own section, so a rule whose facts could not be read says so " +
          "instead of contributing a zero, and one dead rule costs one row rather than the document."
      )
      .tag("alerts")

  /** Closing one event, by hand, with somebody's name on it.
    *
    * `destructive = false`: nothing is lost. The event stays in the feed with its resolution and the person
    * who left it, which is exactly what an incident review reads, so there is no ADR-045 plan and no typed
    * confirmation. It is still a mutation with real consequences — a silenced alert nobody is named for is
    * the one a review cannot reconstruct — which is why it is audited and why it is refused on a read-only
    * cluster (ADR-053 §1).
    *
    * No request body. An acknowledgement carries no information beyond who and which, both of which are
    * already in the request line and the signed principal, and a bodiless mutation is verified in Tapir's
    * security stage — before anything of the caller's is decoded — rather than one stage later (ADR-020
    * Amendment 1).
    */
  val acknowledge
      : Endpoint[SignedPrincipal, (String, ClusterId, String), ErrorEnvelope, AcknowledgementDto, Any] =
    KuiEndpoint
      .mutation(AcknowledgeOperation, destructive = false)
      .post
      .in(clustersBase / clusterIdPath / AlertsSegment / EventsSegment / eventIdPath / AcknowledgementSegment)
      .out(jsonBody[AcknowledgementDto])
      .attribute(
        EndpointAuthorization.Key,
        EndpointAuthorization.one(
          "alerts.acknowledge",
          ResourceRequirement.unnamed(Resource.Alerts, Action.AlertsAcknowledge)
        )
      )
      .name("alerts.acknowledge")
      .summary("Mark one alert event acknowledged")
      .description(
        KuiEndpoint.mutationNote(AcknowledgeOperation, destructive = false) +
          "The event stays in the feed with the name of whoever acknowledged it, which is what an " +
          "incident review reads. An event that is already closed, and an id that names no event, both " +
          "answer 409 KUI-INVALID-STATE: from the caller's side they are one fact, and neither message " +
          "says whether the id ever existed."
      )
      .tag("alerts")

  /** Every endpoint this service serves, in the order a router must try them.
    *
    * The order is contract because the gateway derives its proxy routes from this list in this order
    * (`ContractRouting.derive`), so it is also the order the public API is served in. The read is first
    * because its path is a prefix of the write's, and a router that tried the write first would have to
    * backtrack on every feed request.
    */
  val all: List[AnyEndpoint] = List(events, acknowledge)

  /** The permission every read here needs, declared once.
    *
    * Unnamed: there is one feed per cluster and an event's id is generated rather than chosen, so a pattern
    * written against one would match nothing an operator meant by it — which is `Resource.Alerts`' own
    * scaladoc. The cluster gate in the path is what scopes it. Written as one function so that a third
    * endpoint cannot arrive with a subtly different requirement, and so that `AlertsContractSuite`'s "every
    * endpoint declares a permission" has one place to fail.
    */
  private def viewing(operation: String): EndpointAuthorization =
    EndpointAuthorization.one(operation, ResourceRequirement.unnamed(Resource.Alerts, Action.AlertsView))
}
