package kui.message.contract

import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody

import kui.contracts.KernelSchemas.given
import kui.contracts.rbac.EndpointAuthorization
import kui.contracts.{ErrorEnvelope, KuiEndpoint}
import kui.kernel.ClusterId
import kui.security.SignedPrincipal

/** Registering a smart filter, and trying one against a single record (MS-007, ADR-017).
  *
  * ==Why registration exists at all==
  *
  * A CEL expression can be a paragraph, and the browse endpoint is a `GET` whose parameters end up in a URL
  * that people paste to each other. So the expression is registered once and quoted by a sixteen-character
  * id, and the browse carries the id — with the source alongside it, so that a replica which has never seen
  * that id compiles it instead of refusing a filter its neighbour registered a second ago.
  *
  * Registration is also how an expression is **validated**. The id comes back only if the expression
  * compiled; a typo is a `KUI-FILTER-COMPILE` naming the line and column, which is what the editor
  * underlines. Without it the first sign of a mistake would be a browse over a million records that matched
  * none of them, which reads as missing data rather than as a broken predicate.
  *
  * ==Why neither is a mutation==
  *
  * Neither touches Kafka and neither changes a cluster: one compiles a string, the other evaluates a program
  * against a record the caller already had. They carry no mutation marker and no plan token, because there is
  * nothing to refuse on a read-only cluster and nothing to undo.
  *
  * They are nonetheless cluster-scoped and both declare a permission. An expression language is a probe, and
  * an evaluator anybody may call is a compute service anybody may call; the cluster in the path is what the
  * filter's records belong to and what the permission is checked against.
  */
object FilterEndpoints {

  export BrowseAddress.{ClustersSegment, MessagesSegment, FiltersSegment, TestSegment, ClusterIdParam}

  private val base = "internal" / "v1" / ClustersSegment

  private val clusterIdPath: EndpointInput[ClusterId] =
    path[ClusterId](ClusterIdParam).description("The configured cluster's slug id")

  private def filtersOf: EndpointInput[ClusterId] =
    base / clusterIdPath / MessagesSegment / FiltersSegment

  /** Compile an expression and hand back the handle a browse quotes it by. */
  val register
      : Endpoint[SignedPrincipal, (ClusterId, FilterRegistrationDto), ErrorEnvelope, FilterIdDto, Any] =
    KuiEndpoint.internal.post
      .in(filtersOf)
      .in(jsonBody[FilterRegistrationDto])
      .out(jsonBody[FilterIdDto])
      .name("message.filter.register")
      // Cluster-scoped, like a list endpoint: a filter names no topic. Which topic it is *run* over is
      // decided by the browse that quotes it, and that browse declares its own topic requirement — so a
      // caller who may register a filter still may not read a topic they have no permission for.
      .attribute(EndpointAuthorization.Key, EndpointAuthorization.clusterScoped("filter"))
      .summary("Register a smart filter expression")
      .description(
        "Compiles the expression and answers with its id. The id is sha256(source) truncated, so " +
          "registering the same expression twice is free and gives the same answer on every replica. A " +
          "expression that does not compile is refused with KUI-FILTER-COMPILE naming the line and column, " +
          "which is how the editor knows what to underline. Changes nothing on the cluster."
      )
      .tag("message")

  /** Run an expression against one record the caller supplied. */
  val test: Endpoint[SignedPrincipal, (ClusterId, FilterTestDto), ErrorEnvelope, FilterTestResultDto, Any] =
    KuiEndpoint.internal.post
      .in(filtersOf / TestSegment)
      .in(jsonBody[FilterTestDto])
      .out(jsonBody[FilterTestResultDto])
      .name("message.filter.test")
      // Cluster-scoped for the same reason as `register`, and with the same consequence: the record it
      // evaluates against came from the caller, so no topic is read here at all.
      .attribute(EndpointAuthorization.Key, EndpointAuthorization.clusterScoped("filter"))
      .summary("Try a filter against one record")
      .description(
        "Evaluates the expression against the record in the request and answers whether it matched. No " +
          "Kafka client is opened: the record is the caller's own, which is what makes this cheap enough " +
          "to run from an editor as somebody types. An expression that is legal but throws on this " +
          "particular record answers matched=false with the failure in `error`, which is a different " +
          "sentence from 'your expression is wrong' and needs to read as one."
      )
      .tag("message")

  /** Both, for the gateway's contract map and the merged OpenAPI document. */
  val all: List[AnyEndpoint] = List(register, test)
}
