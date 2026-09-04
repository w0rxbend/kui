package kui.schema.contract

import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody

import kui.contracts.KernelSchemas.given
import kui.contracts.{ErrorEnvelope, KuiEndpoint}
import kui.kernel.{ClusterId, Subject}
import kui.schema.contract.dto.*
import kui.schema.contract.dto.CompatibilityCheckDto.given
import kui.schema.contract.dto.CompatibilityCheckRequest.given
import kui.schema.contract.dto.CompatibilityDto.given
import kui.schema.contract.dto.SetCompatibilityRequest.given
import kui.security.SignedPrincipal

/** The endpoints that carry a request body: two that change a setting, and one that changes nothing.
  *
  * ==Why the compatibility check is in this file and is not a mutation==
  *
  * `checkCompatibility` posts a schema and gets a verdict. Nothing is registered, nothing is stored, and
  * running it twice does exactly what running it once does — so it carries **no** `MutationKey` marker, is
  * not enumerated by the read-only policy, and is answered on a read-only cluster like any other read.
  *
  * That asymmetry is deliberate and is the opposite of the choice the consumer service's reset *plan* makes.
  * A reset plan is refused on a read-only cluster because it is the first step of a wizard whose last step
  * cannot be allowed, and letting somebody compose a change they may not apply teaches them the refusal is a
  * bug. A compatibility check is not the first step of anything KUI does: it answers "would this schema be
  * accepted", which is a question a read-only operator is entitled to an answer to, and refusing it would
  * push them to ask the registry directly instead.
  *
  * It lives here because it has a body, and a bodied endpoint is verified differently (ADR-020 Amendment 1) —
  * which is a property of the request shape, not of whether it mutates.
  *
  * ==The two writes==
  *
  * Both carry `KuiEndpoint.MutationKey` and the CSRF header, and both are marked `destructive = false`:
  * setting a compatibility level loses no data and can be set back. It is still a mutation with real
  * consequences — lowering a level to `NONE` removes the check that stops an unreadable schema from being
  * registered — which is why it is audited and refused on a read-only cluster.
  */
object SchemaMutationEndpoints {

  val ClustersSegment: String = SchemaEndpoints.ClustersSegment
  val SchemasSegment: String = SchemaEndpoints.SchemasSegment
  val SubjectsSegment: String = SchemaEndpoints.SubjectsSegment
  val VersionsSegment: String = SchemaEndpoints.VersionsSegment
  val CompatibilitySegment: String = SchemaEndpoints.CompatibilitySegment

  /** The operation names. They are the same strings as the audit vocabulary's `MutationKind.operation`, and
    * the api module's suite — the only place that can see both a contract and an application type — asserts
    * that they are.
    */
  val SetGlobalCompatibilityOperation: String = "schema.compatibility.global.set"
  val SetSubjectCompatibilityOperation: String = "schema.compatibility.subject.set"

  private val clustersBase = "internal" / "v1" / ClustersSegment

  private val clusterIdPath: EndpointInput[ClusterId] =
    path[ClusterId](SchemaEndpoints.ClusterIdParam).description("The configured cluster's slug id")

  private val subjectPath: EndpointInput[Subject] =
    path[Subject](SchemaEndpoints.SubjectParam).description("The subject, as the registry knows it")

  private val versionPath: EndpointInput[String] =
    path[String](SchemaEndpoints.VersionParam)
      .description(
        s"The version to check against: a number, or '${SchemaEndpoints.LatestVersion}' for the current one"
      )

  /** Set the level every subject without its own follows. */
  val setGlobalCompatibility: Endpoint[
    SignedPrincipal,
    (String, ClusterId, SetCompatibilityRequest),
    ErrorEnvelope,
    CompatibilityDto,
    Any
  ] =
    KuiEndpoint
      .mutation(SetGlobalCompatibilityOperation, destructive = false)
      .put
      .in(clustersBase / clusterIdPath / SchemasSegment / CompatibilitySegment)
      .in(jsonBody[SetCompatibilityRequest])
      .out(jsonBody[CompatibilityDto])
      .name("schema.compatibility.global.set")
      .summary("Set the registry-wide compatibility level")
      .description(
        KuiEndpoint.mutationNote(SetGlobalCompatibilityOperation, destructive = false) +
          "It applies to every subject that has no level of its own, so lowering it to NONE removes the " +
          "compatibility check for all of them at once. Refused on a read-only cluster, and audited either " +
          "way with the level that was in force before the change."
      )
      .tag("schema")

  /** Set one subject's own level, overriding the global one from now on. */
  val setSubjectCompatibility: Endpoint[
    SignedPrincipal,
    (String, ClusterId, Subject, SetCompatibilityRequest),
    ErrorEnvelope,
    CompatibilityDto,
    Any
  ] =
    KuiEndpoint
      .mutation(SetSubjectCompatibilityOperation, destructive = false)
      .put
      .in(
        clustersBase / clusterIdPath / SchemasSegment / SubjectsSegment / subjectPath / CompatibilitySegment
      )
      .in(jsonBody[SetCompatibilityRequest])
      .out(jsonBody[CompatibilityDto])
      .name("schema.compatibility.subject.set")
      .summary("Set one subject's compatibility level")
      .description(
        KuiEndpoint.mutationNote(SetSubjectCompatibilityOperation, destructive = false) +
          "The subject stops following the global level from this point on, which the answer reports as " +
          "inheritedFromGlobal: false. Refused on a read-only cluster."
      )
      .tag("schema")

  /** Would the registry accept this schema for this subject? Changes nothing. */
  val checkCompatibility: Endpoint[
    SignedPrincipal,
    (ClusterId, Subject, String, CompatibilityCheckRequest),
    ErrorEnvelope,
    CompatibilityCheckDto,
    Any
  ] =
    KuiEndpoint.internal.post
      .in(
        clustersBase / clusterIdPath / SchemasSegment / SubjectsSegment / subjectPath /
          VersionsSegment / versionPath / CompatibilitySegment
      )
      .in(jsonBody[CompatibilityCheckRequest])
      .out(jsonBody[CompatibilityCheckDto])
      .name("schema.compatibility.check")
      .summary("Whether a proposed schema would be accepted for this subject")
      .description(
        "Registers nothing. The check runs inside the registry, because the verdict that matters is the " +
          "one the registry will give when the schema is really registered; KUI does not reimplement the " +
          "compatibility rules of three schema languages. Answered on a read-only cluster like any other " +
          "read."
      )
      .tag("schema")

  /** Every bodied endpoint this service serves.
    *
    * The two writes and the check are one list because the gateway proxies them identically — it rewrites the
    * prefix and forwards the inputs, and the mutation marker is read by policy rather than by routing.
    */
  val all: List[AnyEndpoint] =
    List(setGlobalCompatibility, setSubjectCompatibility, checkCompatibility)
}
