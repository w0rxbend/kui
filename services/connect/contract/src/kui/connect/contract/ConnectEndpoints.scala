package kui.connect.contract

import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody

import kui.connect.contract.dto.*
import kui.connect.contract.dto.ConnectorListResponse.given
import kui.connect.contract.dto.ConnectorOperationDto.given
import kui.contracts.KernelSchemas.given
import kui.contracts.rbac.{EndpointAuthorization, ResourceRequirement}
import kui.contracts.{ErrorEnvelope, KuiEndpoint}
import kui.kernel.ClusterId
import kui.security.SignedPrincipal
import kui.security.rbac.{Action, Resource}

/** Everything `kui-connect-service` serves, described once.
  *
  * The paths start at `/internal/v1`, not at `/api/v1`: the public prefix belongs to the gateway, which
  * rewrites the first segment of each service's published contract to build its public routes
  * (`ARCHITECTURE.md` §5).
  *
  * ==One read across every worker, three writes against one connector==
  *
  * The read is a single endpoint that answers for **all** of a cluster's Connect clusters at once, with a
  * `Section` per worker inside the document. That is the alerts service's shape rather than the metrics
  * service's, and for the alerts service's reason: the screen is one screen — §4.14 draws `4 connectors · 1
  * failed and sulking` over cards from every configured worker — and three requests for one screen is how
  * three panels come to disagree about how many connectors there are. What varies between workers is whether
  * a *call* answered, and that is a section inside the document rather than an endpoint outside it.
  *
  * The writes address one connector on one named Connect cluster, because that is how a connector is
  * addressed: `ConnectClusterSettings` exists precisely so that a connector is `(Connect cluster, name)` and
  * not a URL, and two Connect clusters may both run a connector called `orders-sink`.
  *
  * ==What is not here==
  *
  * There is no deploy, no delete and no configuration edit. M9's list names deploy; wave 7 builds the read
  * and the three operations, which is what §3.14's cards draw, and ADR-054 §7 records the decision rather
  * than leaving the gap to be read as an oversight. There is also no stream: the Connect REST API publishes
  * no change feed, so there is nothing for the gateway to relay and the screen polls (ADR-054 §5).
  */
object ConnectEndpoints {

  val ClustersSegment: String = "clusters"
  val ConnectSegment: String = "connect"
  val ConnectorsSegment: String = "connectors"

  val ClusterIdParam: String = "clusterId"
  val ConnectNameParam: String = "connectName"
  val ConnectorNameParam: String = "connectorName"

  /** The three operation names. They are the endpoint names, the audit operation strings and the `operation`
    * field of the response, all read off `ConnectorOperation` in the domain rather than typed here — one
    * spelling per operation, in the enum that also carries the worker's path segment.
    */
  val PauseOperation: String = "connect.connector.pause"
  val ResumeOperation: String = "connect.connector.resume"
  val RestartOperation: String = "connect.connector.restart"

  val ListOperation: String = "connect.connectors"

  private val clustersBase = "internal" / "v1" / ClustersSegment

  private val clusterIdPath: EndpointInput[ClusterId] =
    path[ClusterId](ClusterIdParam).description("The configured cluster's slug id")

  private val connectNamePath: EndpointInput[String] =
    path[String](ConnectNameParam)
      .description("The Connect cluster's name, as kui.clusters.<n>.connect[].name gives it")

  private val connectorNamePath: EndpointInput[String] =
    path[String](ConnectorNameParam).description("The connector's name, as the worker reported it")

  /** Every connector of every Connect cluster configured for this Kafka cluster.
    *
    * A cluster with no Connect cluster configured answers **200** with `not_configured`, not a 404 and not an
    * empty list. The three are different facts and only one of them is this one: the deployment has no Kafka
    * Connect, ADR-032 hides the row, and nothing is wrong.
    *
    * `clusterScoped`, which is what every list endpoint in this product declares (`topic.list`,
    * `schema.subjects`, `consumer.list`): ADR-021 keeps Kafbat's rule that a list filters rather than
    * refuses. What that costs here is stated rather than hidden — see [[operating]] and ADR-054 §3 — because
    * `Resource.Connect` is a *named* resource, so a requirement that named no Connect cluster could never be
    * covered by a pattern grant and would refuse every reader in a deployment with RBAC on.
    */
  val connectors: Endpoint[SignedPrincipal, ClusterId, ErrorEnvelope, ConnectorListResponse, Any] =
    KuiEndpoint.internal.get
      .in(clustersBase / clusterIdPath / ConnectSegment / ConnectorsSegment)
      .out(jsonBody[ConnectorListResponse])
      .attribute(EndpointAuthorization.Key, EndpointAuthorization.clusterScoped(ListOperation))
      .name(ListOperation)
      .summary("The connectors this cluster's Connect workers are running")
      .description(
        "One section per configured Connect cluster, so a worker that is down costs one row rather " +
          "than the screen, and a worker that is rebalancing says so instead of reporting no " +
          "connectors. Each connector carries its tasks with their states and the worker's own failure " +
          "trace. No throughput is published: the Connect REST API measures none."
      )
      .tag("connect")

  /** Stop a connector without losing anything.
    *
    * `destructive = false`, and it is a considered false rather than a default: pausing a connector stops it
    * consuming or producing and changes no data, and `resume` puts it back. There is therefore no ADR-045
    * plan → token → confirm; there is an audit record, because a paused sink that nobody is named for is the
    * one an incident review cannot reconstruct.
    */
  val pause: Endpoint[
    SignedPrincipal,
    (String, ClusterId, String, String),
    ErrorEnvelope,
    ConnectorOperationDto,
    Any
  ] =
    operation("pause", PauseOperation, "Pause one connector", pauseNote)

  val resume: Endpoint[
    SignedPrincipal,
    (String, ClusterId, String, String),
    ErrorEnvelope,
    ConnectorOperationDto,
    Any
  ] =
    operation("resume", ResumeOperation, "Resume one paused connector", resumeNote)

  /** Restart a connector and its tasks.
    *
    * The most disruptive of the three and still not destructive: a restarted connector re-reads its own
    * committed offsets, so a source resumes where it was and a sink re-delivers at most what it had not
    * committed. What it costs is a gap in delivery while the tasks come back, which is why it is audited and
    * why §7.7's failed card puts it beside the reason rather than on its own.
    */
  val restart: Endpoint[
    SignedPrincipal,
    (String, ClusterId, String, String),
    ErrorEnvelope,
    ConnectorOperationDto,
    Any
  ] =
    operation("restart", RestartOperation, "Restart one connector and its tasks", restartNote)

  /** Every endpoint this service serves, in the order a router must try them.
    *
    * The order is contract because the gateway derives its proxy routes from this list in this order
    * (`ContractRouting.derive`), so it is also the order the public API is served in. The read is first: its
    * path is the shortest and it is the only one of the four that a screen asks for on every render.
    */
  val all: List[AnyEndpoint] = List(connectors, pause, resume, restart)

  /** The three writes, which is the list that has to move when a fourth operation arrives. */
  val writes: List[AnyEndpoint] = List(pause, resume, restart)

  private def pauseNote: String =
    "Asks the Connect cluster to stop this connector's tasks. Nothing is deleted and `resume` puts it " +
      "back, so there is no typed confirmation; it is audited, and it is refused on a read-only cluster."

  private def resumeNote: String =
    "Asks the Connect cluster to start this connector's tasks again. Audited, and refused on a " +
      "read-only cluster."

  private def restartNote: String =
    "Asks the Connect cluster to restart this connector and its tasks. The connector resumes from its " +
      "own committed offsets; what it costs is a gap in delivery while the tasks come back."

  /** One of the three writes, built once so a fourth cannot arrive with a subtly different declaration.
    *
    * The response carries no state, and the description says so: Connect answers `202 Accepted` with an empty
    * body for all three, so anything this endpoint claimed about the connector afterwards would be the state
    * before the call wearing the result's clothes.
    */
  private def operation(
      segment: String,
      name: String,
      summary: String,
      note: String
  ): Endpoint[
    SignedPrincipal,
    (String, ClusterId, String, String),
    ErrorEnvelope,
    ConnectorOperationDto,
    Any
  ] =
    KuiEndpoint
      .mutation(name, destructive = false)
      .post
      .in(
        clustersBase / clusterIdPath / ConnectSegment / connectNamePath / ConnectorsSegment /
          connectorNamePath / segment
      )
      .out(jsonBody[ConnectorOperationDto])
      .attribute(EndpointAuthorization.Key, operating(name))
      .name(name)
      .summary(summary)
      .description(
        s"$note The answer says what was accepted and when, and carries no connector state: the " +
          "cluster applies these asynchronously and the next read of the connector list is where the " +
          "change appears."
      )
      .tag("connect")

  /** The permission all three writes need, declared once.
    *
    * `CONNECT:OPERATE` on the Connect cluster named in the path — which is the action an operator's file
    * spells `RESTART`, the alias `Action.fromWire` maps for `Resource.Connect` and the reason that alias was
    * shipped in wave 1. Named by the `connectName` path parameter, so a grant written over `payments` covers
    * the Connect cluster called `payments` and no other.
    *
    * It is deliberately **not** a `Resource.Connector` requirement, and the gap is stated rather than
    * discovered: `ResourceAccess.connector` builds the connector-with-fallback-to-its-parent access the RBAC
    * model was designed around, and `EndpointAuthorization.access` has no `NameSource` that can build one —
    * it makes a `ResourceAccess` from a single path parameter with no fallback. Declaring
    * `Resource.Connector` here would therefore ask for a permission on a name spelled `orders-sink` while
    * every grant in the model is spelled `payments/orders-sink`, and it would refuse holders of the parent
    * grant the model says are covered. ADR-054 §3 carries this, with the one seam that would close it.
    */
  private def operating(operation: String): EndpointAuthorization =
    EndpointAuthorization.one(
      operation,
      ResourceRequirement.named(Resource.Connect, ConnectNameParam, Action.ConnectOperate)
    )
}
