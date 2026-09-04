package kui.schema.contract

import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody

import kui.contracts.KernelCodecs.given
import kui.contracts.KernelSchemas.given
import kui.contracts.paging.PageDto
import kui.contracts.rbac.{EndpointAuthorization, ResourceRequirement}
import kui.contracts.{ErrorEnvelope, KuiEndpoint}
import kui.kernel.{ClusterId, SortOrder, Subject}
import kui.schema.contract.dto.*
import kui.schema.contract.dto.CompatibilityDto.given
import kui.schema.contract.dto.SchemaDto.given
import kui.schema.contract.dto.SubjectVersionsDto.given
import kui.security.SignedPrincipal
import kui.security.rbac.{Action, Resource}

/** What a subject list request asks for, after the query string has been decoded.
  *
  * One record rather than four loose parameters, so that adding a parameter later is a field rather than a
  * fifth element of a tuple every call site has to be edited for.
  */
final case class SubjectListParams(q: Option[String], direction: SortOrder, page: Int, pageSize: Int)

/** Everything `kui-schema-service` reads, described once.
  *
  * The paths start at `/internal/v1`, not at `/api/v1`: the public prefix belongs to the gateway, which
  * rewrites the first segment of each service's published contract to build its public routes
  * (`ARCHITECTURE.md` §5). A service that declared the public prefix would be declaring a second surface the
  * architecture does not allow it.
  *
  * ==Everything here is optional at runtime==
  *
  * Every route in this file answers `KUI-UNSUPPORTED` for a cluster with no Schema Registry configured, and
  * the capability document says `not_configured` for that cluster before a browser ever calls one. That is
  * the contract's part of this service's central promise: a deployment without a registry hides the feature,
  * and never shows a screen that cannot work.
  */
object SchemaEndpoints {

  val ClustersSegment: String = "clusters"
  val SchemasSegment: String = "schemas"
  val SubjectsSegment: String = "subjects"
  val VersionsSegment: String = "versions"
  val CompatibilitySegment: String = "compatibility"

  val ClusterIdParam: String = "clusterId"
  val SubjectParam: String = "subject"
  val VersionParam: String = "version"
  val QueryParam: String = "q"
  val DirectionParam: String = "direction"
  val PageParam: String = "page"
  val PageSizeParam: String = "pageSize"

  /** ADR-026's defaults, named here because the endpoint declares them and the OpenAPI document prints them.
    */
  val DefaultPage: Int = 1
  val DefaultPageSize: Int = 25

  /** The word the registry itself uses for "whichever version is current".
    *
    * It is a legal value of the version path parameter, and it is not a number: resolving it in the browser
    * by fetching the version list first would show a schema that was latest a moment ago, which is exactly
    * the staleness the word exists to avoid.
    */
  val LatestVersion: String = "latest"

  private val clustersBase = "internal" / "v1" / ClustersSegment

  private val clusterIdPath: EndpointInput[ClusterId] =
    path[ClusterId](ClusterIdParam).description("The configured cluster's slug id")

  private val subjectPath: EndpointInput[Subject] =
    path[Subject](SubjectParam).description("The subject, as the registry knows it, such as orders-value")

  private val versionPath: EndpointInput[String] =
    path[String](VersionParam)
      .description(s"A version number, or '$LatestVersion' for whichever version is current")

  /** The list parameters, as one input. Public because the browser's own endpoint value is built from it. */
  val listParams: EndpointInput[SubjectListParams] =
    query[Option[String]](QueryParam)
      .description("Case-insensitive substring match over the subject name. Not a regular expression")
      .and(query[SortOrder](DirectionParam).description("Which way to sort by name").default(SortOrder.Asc))
      .and(query[Int](PageParam).description("Which page, numbered from one").default(DefaultPage))
      .and(
        query[Int](PageSizeParam)
          .description("How many rows a page holds. A value above the maximum is clamped, not refused")
          .default(DefaultPageSize)
      )
      .map(SubjectListParams.apply.tupled)(params =>
        (params.q, params.direction, params.page, params.pageSize)
      )

  /** One page of the cluster's subjects.
    *
    * The registry has no search, no sort and no paging of its own — `GET /subjects` returns every name — so
    * all three happen in the service. The parameters are still on this endpoint rather than in the browser,
    * because moving the whole subject list of a large registry into every browser tab is worse than moving it
    * into one service that already has it.
    */
  val subjects: Endpoint[
    SignedPrincipal,
    (ClusterId, SubjectListParams),
    ErrorEnvelope,
    PageDto[Subject],
    Any
  ] =
    KuiEndpoint.internal.get
      .in(clustersBase / clusterIdPath / SchemasSegment / SubjectsSegment)
      .in(listParams)
      .out(jsonBody[PageDto[Subject]])
      .attribute(EndpointAuthorization.Key, EndpointAuthorization.clusterScoped("schema.subjects"))
      .name("schema.subjects")
      .summary("The subjects registered on this cluster's Schema Registry")
      .description(
        "Answers KUI-UNSUPPORTED for a cluster with no registry configured, which is a deployment choice " +
          "rather than a failure: the capability document reports that cluster as not_configured and the " +
          "browser hides the feature for it."
      )
      .tag("schema")

  /** Every version number of one subject. */
  val versions: Endpoint[SignedPrincipal, (ClusterId, Subject), ErrorEnvelope, SubjectVersionsDto, Any] =
    KuiEndpoint.internal.get
      .in(clustersBase / clusterIdPath / SchemasSegment / SubjectsSegment / subjectPath / VersionsSegment)
      .out(jsonBody[SubjectVersionsDto])
      .attribute(
        EndpointAuthorization.Key,
        EndpointAuthorization
          .one("schema.versions", ResourceRequirement.named(Resource.Schema, SubjectParam, Action.SchemaView))
      )
      .name("schema.versions")
      .summary("The version numbers of one subject")
      .description(
        "A subject the registry does not hold answers 404 KUI-SCHEMA-NOT-FOUND, so a stale link says so " +
          "rather than showing an empty version list that reads as a subject with no schemas."
      )
      .tag("schema")

  /** One version's schema. */
  val schema: Endpoint[SignedPrincipal, (ClusterId, Subject, String), ErrorEnvelope, SchemaDto, Any] =
    KuiEndpoint.internal.get
      .in(
        clustersBase / clusterIdPath / SchemasSegment / SubjectsSegment / subjectPath /
          VersionsSegment / versionPath
      )
      .out(jsonBody[SchemaDto])
      .attribute(
        EndpointAuthorization.Key,
        EndpointAuthorization
          .one("schema.version", ResourceRequirement.named(Resource.Schema, SubjectParam, Action.SchemaView))
      )
      .name("schema.version")
      .summary("The schema behind one version of a subject")
      .description(
        "The schema text is returned exactly as the registry stores it, so that it can be compared and " +
          "diffed against the registry's own screen."
      )
      .tag("schema")

  /** The registry-wide compatibility level. */
  val globalCompatibility: Endpoint[SignedPrincipal, ClusterId, ErrorEnvelope, CompatibilityDto, Any] =
    KuiEndpoint.internal.get
      .in(clustersBase / clusterIdPath / SchemasSegment / CompatibilitySegment)
      .out(jsonBody[CompatibilityDto])
      .attribute(
        EndpointAuthorization.Key,
        EndpointAuthorization.clusterScoped("schema.compatibility.global")
      )
      .name("schema.compatibility.global")
      .summary("The compatibility level every subject without its own follows")
      .description(
        "A registry that has never had a global level set reports its own default, BACKWARD, because that " +
          "is what it will actually apply to the next registration."
      )
      .tag("schema")

  /** One subject's compatibility level, and where it comes from. */
  val subjectCompatibility: Endpoint[
    SignedPrincipal,
    (ClusterId, Subject),
    ErrorEnvelope,
    CompatibilityDto,
    Any
  ] =
    KuiEndpoint.internal.get
      .in(
        clustersBase / clusterIdPath / SchemasSegment / SubjectsSegment / subjectPath / CompatibilitySegment
      )
      .out(jsonBody[CompatibilityDto])
      .attribute(
        EndpointAuthorization.Key,
        EndpointAuthorization
          .one(
            "schema.compatibility.subject",
            ResourceRequirement.named(Resource.Schema, SubjectParam, Action.SchemaView)
          )
      )
      .name("schema.compatibility.subject")
      .summary("The compatibility level in force for one subject")
      .description(
        "Reports inheritedFromGlobal when the subject has no level of its own. Without that flag a client " +
          "cannot tell a subject that is governed globally from one that has been pinned, and 'confirming' " +
          "the value it displays would silently create an override."
      )
      .tag("schema")

  /** Every read endpoint this service serves, **in the order a router must try them**.
    *
    * The order is load bearing, exactly as it is in the consumer service. `/subjects/{subject}/compatibility`
    * and `/subjects/{subject}/versions` share a prefix with nothing else here, but `/schemas/compatibility`
    * and `/schemas/subjects` both sit directly under `/schemas`, and a router that tried a subject path first
    * would answer the global compatibility request with a lookup of a subject named "compatibility" — a
    * well-formed 404 for a request that should have succeeded.
    *
    * The gateway derives its proxy routes from this list in this order (`ContractRouting.derive`), so this is
    * also the order the public API is served in.
    */
  val all: List[AnyEndpoint] =
    List(globalCompatibility, subjects, subjectCompatibility, versions, schema)
}
