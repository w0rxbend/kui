package kui.ksql.contract

import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody

import kui.contracts.KernelSchemas.given
import kui.contracts.rbac.{EndpointAuthorization, ResourceRequirement}
import kui.contracts.{ErrorEnvelope, KuiEndpoint}
import kui.kernel.ClusterId
import kui.ksql.contract.dto.*
import kui.ksql.contract.dto.KsqlObjectsResponse.given
import kui.ksql.contract.dto.StatementPlanDto.given
import kui.ksql.contract.dto.StatementRequestDto.given
import kui.ksql.contract.dto.StatementResultDto.given
import kui.security.SignedPrincipal
import kui.security.rbac.{Action, Resource}

/** Everything `kui-ksql-service` serves over JSON, described once.
  *
  * The paths start at `/internal/v1`, not at `/api/v1`: the public prefix belongs to the gateway, which
  * rewrites the first segment of each service's published contract to build its public routes
  * (`ARCHITECTURE.md` §5). The push query is not here — it is an event stream, so it lives in
  * `KsqlStreamEndpoint` in this module's JVM half, for the reason that file states.
  *
  * ==One read, one plan, one apply==
  *
  * The read answers for the cluster's one ksqlDB cluster. There is no per-worker section, because
  * `KsqlSettings` is singular where `ConnectClusterSettings` is a list — a second ksqlDB cluster over the
  * same brokers shares the same command topic and is the same logical service — so the outer section carries
  * the whole answer and there is nothing inside it that could have failed separately.
  *
  * The plan and the apply are ADR-045's two phases over one free-form statement, which is a shape no other
  * service in this product has. `services/topic` plans a *known* operation over a named topic; here the
  * operation is whatever somebody typed, so the classification of what they typed is the plan, and the token
  * binds the text. [[execute]] is the only endpoint in KUI that can destroy a Kafka topic without naming it.
  *
  * ==The two permissions, and the implication between them==
  *
  * `KSQL:VIEW` reads and `KSQL:EXECUTE` runs, and `Action.closure` expands a grant of `EXECUTE` to include
  * `VIEW` — which is why [[objects]] asks for the weaker of the two. Both halves are load-bearing and both
  * are asserted through a real policy in `KsqlRoutesSuite`:
  *
  *   - a principal granted only `KSQL:VIEW` lists the objects and is **refused** a statement, before the
  *     ksqlDB server is called at all;
  *   - a principal granted only `KSQL:EXECUTE` does both, because the implication reaches the endpoint that
  *     asks for `VIEW`.
  *
  * `KsqlView` exists precisely for the first of those: `EXECUTE` alone is altering, the read-only gate
  * refuses an altering request before any resource is considered, and a ksqlDB whose only action altered
  * could not be *looked at* on a read-only cluster. `RbacLawsSuite` holds the vocabulary half; these
  * declarations are the half that runs.
  */
object KsqlEndpoints {

  val ClustersSegment: String = "clusters"
  val KsqlSegment: String = "ksql"
  val ObjectsSegment: String = "objects"
  val StatementsSegment: String = "statements"
  val PlanSegment: String = "plan"

  val ClusterIdParam: String = "clusterId"

  /** The operation names. They are the endpoint names, the audit operation strings and the log lines, so
    * there is one spelling per operation in the product.
    */
  val ListOperation: String = "ksql.objects"
  val PlanOperation: String = "ksql.statement.plan"
  val ExecuteOperation: String = "ksql.statement.execute"
  val StreamOperation: String = "ksql.stream"

  /** The audit and `MutationKind`-shaped name both phases of the statement flow share.
    *
    * One name for the plan and the apply, spelled the way `kui.security.audit.MutationKind.operation` is
    * spelled — dotted, lower case, most general segment first. `services/topic` does the same across its own
    * two phases, and for the same reason: "what happened to this cluster today" must not have to be asked
    * twice because the preview and the change were filed under different words.
    */
  val StatementMutation: String = "ksql.statement"

  private val clustersBase = "internal" / "v1" / ClustersSegment

  private val clusterIdPath: EndpointInput[ClusterId] =
    path[ClusterId](ClusterIdParam).description("The configured cluster's slug id")

  /** Every stream, table, running query and topic this cluster's ksqlDB can name.
    *
    * A cluster with no ksqlDB configured answers **200** with `not_configured`, not a 404 and not an empty
    * list. The three are different facts and only one of them is this one: the deployment has no ksqlDB,
    * ADR-032 hides the row, and nothing is wrong.
    */
  val objects: Endpoint[SignedPrincipal, ClusterId, ErrorEnvelope, KsqlObjectsResponse, Any] =
    KuiEndpoint.internal.get
      .in(clustersBase / clusterIdPath / KsqlSegment / ObjectsSegment)
      .out(jsonBody[KsqlObjectsResponse])
      .attribute(EndpointAuthorization.Key, viewing(ListOperation))
      .name(ListOperation)
      .summary("The streams, tables, queries and topics this cluster's ksqlDB knows about")
      .description(
        "One list, ordered by kind and then by name, because §3.16's pane is one pane and a row that " +
          "moves between polls is one an operator cannot click accurately. A row the server returned " +
          "and KUI could not describe is named in `unreadable` rather than dropped."
      )
      .tag("ksql")

  /** What running this statement would do, and the token that confirms it if it needs one.
    *
    * `destructive = false` on the marker, and it genuinely is not a mutation: it classifies text and reads
    * nothing. It carries the marker at all for `topic.deletion.plan`'s reason — a plan rendered to somebody
    * who cannot apply it is a confirmation dialogue whose only outcome is a refusal, so the read-only gate
    * applies to the preview as well as to the change.
    */
  val plan: Endpoint[
    SignedPrincipal,
    (String, ClusterId, StatementRequestDto),
    ErrorEnvelope,
    StatementPlanDto,
    Any
  ] =
    KuiEndpoint
      .mutation(StatementMutation, destructive = false)
      .post
      .in(clustersBase / clusterIdPath / KsqlSegment / StatementsSegment / PlanSegment)
      .in(jsonBody[StatementRequestDto])
      .out(jsonBody[StatementPlanDto])
      .attribute(EndpointAuthorization.Key, executing(PlanOperation))
      .name(PlanOperation)
      .summary("What running this statement would do")
      .description(
        KuiEndpoint.mutationNote(StatementMutation, destructive = false) +
          "Answers what the statement is — a pull query, a push query or a statement — whether it is " +
          "destructive, whether it deletes a Kafka topic, the warnings to show before it is confirmed, " +
          "and a token valid for five minutes and for this statement's exact text. A statement that is " +
          "not destructive plans to no token and needs none."
      )
      .tag("ksql")

  /** Run one statement that finishes.
    *
    * Classified `destructive = true` even though most statements are not, and that is the honest
    * classification of the *endpoint*: `DROP … DELETE TOPIC` deletes a Kafka topic and every record in it,
    * and this is the address it arrives at. The per-statement answer is `plan`'s `destructive` field, which
    * is what a screen asks before it decides whether to show a confirmation.
    *
    * A push query is **refused** here rather than answered, and the refusal names the stream address: a push
    * query does not finish, so answering it from a JSON endpoint would mean buffering an unbounded result
    * into one document — a request that never returns and a heap that never stops growing.
    */
  val execute: Endpoint[
    SignedPrincipal,
    (String, ClusterId, StatementRequestDto),
    ErrorEnvelope,
    StatementResultDto,
    Any
  ] =
    KuiEndpoint
      .mutation(StatementMutation, destructive = true)
      .post
      .in(clustersBase / clusterIdPath / KsqlSegment / StatementsSegment)
      .in(jsonBody[StatementRequestDto])
      .out(jsonBody[StatementResultDto])
      .attribute(EndpointAuthorization.Key, executing(ExecuteOperation))
      .name(ExecuteOperation)
      .summary("Run one ksqlDB statement")
      .description(
        KuiEndpoint.mutationNote(StatementMutation, destructive = true) +
          "One statement per request. A `DROP ... DELETE TOPIC` is refused without the token its plan " +
          "answered with, and the token is checked against the statement's exact text so that a " +
          "confirmed statement cannot be swapped for another one. A `SELECT ... EMIT CHANGES` is refused " +
          "here and named at .../ksql/stream, because it never finishes. A pull query answers rows; " +
          "everything else answers the server's own status sentence."
      )
      .tag("ksql")

  /** Every JSON endpoint this service serves, in the order a router must try them.
    *
    * The order is contract: the gateway derives its proxy routes from this list in this order
    * (`ContractRouting.derive`), so it is also the order the public API is served in. The read is first — its
    * path is the shortest and it is the only one of the three a screen asks for on every render — and the
    * plan is before the apply, which is the order they happen in.
    *
    * The push query is not in this list and must not be: `ContractRouting.derive` decodes and re-encodes an
    * upstream's JSON, which is exactly the wrong thing to do to an event stream, so that endpoint is declared
    * in `KsqlStreamEndpoint` and relayed by hand in the gateway (house rule 16).
    */
  val all: List[AnyEndpoint] = List(objects, plan, execute)

  /** The two writes, which is the list that has to move when a fourth JSON endpoint arrives. */
  val writes: List[AnyEndpoint] = List(plan, execute)

  /** Reading ksqlDB's objects: `KSQL:VIEW`, unnamed.
    *
    * Unnamed because `Resource.Ksql` has no name — a deployment has at most one ksqlDB per cluster, the
    * cluster gate already decides which clusters a person may look at, and a pattern over a ksqlDB would be a
    * second, weaker spelling of that same decision. `Resource.Ksql.isNamed` is false and `KsqlContractSuite`
    * asserts that this declaration matches it, because a *named* requirement over an unnamed resource is a
    * permission no role file could ever satisfy.
    *
    * **`VIEW` and not `EXECUTE`, and that is the rule this packet owns.** A grant of `KSQL:EXECUTE` expands
    * through `Action.closure` to include `KSQL:VIEW`, so an operator granted only EXECUTE reaches this
    * endpoint; an operator granted only VIEW reaches it too, and is refused [[execute]]. Asking for EXECUTE
    * here would collapse the two into one permission and take the object list away from every read-only
    * reader in a deployment with RBAC on — which is the exact reason `KsqlView` was declared in wave 1.
    */
  private def viewing(operation: String): EndpointAuthorization =
    EndpointAuthorization.one(
      operation,
      ResourceRequirement.unnamed(Resource.Ksql, Action.KsqlView)
    )

  /** Running or planning a statement: `KSQL:EXECUTE`, unnamed.
    *
    * The plan asks for the same permission as the apply, deliberately. A plan is a preview of a change, and a
    * preview shown to somebody who cannot make the change is a dialogue whose only button leads to a refusal
    * — `SCREENS-V4.md` §3.7's rule that a control which changes position between users is worse than a
    * disabled one.
    */
  private def executing(operation: String): EndpointAuthorization =
    EndpointAuthorization.one(
      operation,
      ResourceRequirement.unnamed(Resource.Ksql, Action.KsqlExecute)
    )
}
