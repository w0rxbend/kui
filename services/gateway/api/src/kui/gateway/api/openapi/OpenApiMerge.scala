package kui.gateway.api.openapi

import cats.data.NonEmptyList
import cats.syntax.all.*
import sttp.apispec.openapi.{OpenAPI, Server}
import sttp.tapir.AnyEndpoint
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter

import kui.gateway.api.routing.ContractRouting
import kui.kernel.ServiceId

/** One service's published endpoints, ready to be merged. */
final case class ServiceDoc(service: ServiceId, endpoints: List[AnyEndpoint])

/** One document for the whole product.
  *
  * An integrator reads `/api/docs`, generates a client from `/api/v1/openapi.json`, and never has to learn
  * that KUI is eleven services. That is the point of having a gateway at all, and a set of eleven separate
  * documents would give the shape of the deployment away in the one artefact that should hide it.
  *
  * The merge takes contract *metadata* and never calls a service. A service being down therefore does not
  * remove its endpoints from the documentation: the API still exists, it is merely unavailable, and an
  * integrator reading the docs during an outage should not conclude that half the product was deleted.
  */
object OpenApiMerge {

  /** Tapir documents a `text/plain` 400 for every endpoint that could fail to decode its input. KUI does not
    * answer that way: `ErrorInterceptor`'s decode-failure handler turns those into the same `ErrorEnvelope`
    * as everything else (ADR-034), so the generated response describes a shape the product never sends.
    * Suppressing it makes the document match the running server, which is the only property that makes
    * generated documentation worth publishing.
    */
  private val DocsOptions: sttp.tapir.docs.openapi.OpenAPIDocsOptions =
    sttp.tapir.docs.openapi.OpenAPIDocsOptions.default.copy(defaultDecodeFailureOutput = _ => None)

  val Title: String = "KUI"

  /** The contract's version, not the build's. It changes when the published API changes, which is the only
    * thing a generated client cares about; tying it to the build would churn the committed document on every
    * commit and tell a reader nothing.
    */
  val Version: String = "1.0"

  /** Merges the gateway's own endpoints with every routed service's, under their public paths.
    *
    * Deterministic by construction: services are sorted by id, and the underlying document model is built
    * from ordered maps. The committed `docs/api/openapi.json` would otherwise churn on every build and the
    * staleness check would be worthless.
    */
  def merge(
      title: String,
      version: String,
      servers: List[String],
      docs: List[ServiceDoc]
  ): Either[NonEmptyList[String], OpenAPI] =
    for {
      ordered <- Right(docs.sortBy(_.service.value))
      _ <- duplicateOperationIds(ordered)
      tagged <- ordered.traverse(prepared)
      document = OpenAPIDocsInterpreter(DocsOptions)
        .toOpenAPI(tagged.flatten, title, version)
        .servers(servers.map(Server(_)))
    } yield document

  /** Every endpoint, tagged with its owning service and rewritten to its public path.
    *
    * The tag is the service id, so the UI groups by service -- which is the grouping an integrator finds
    * useful, and the only one that survives a service being split in two later.
    */
  private def prepared(doc: ServiceDoc): Either[NonEmptyList[String], List[AnyEndpoint]] =
    doc.endpoints
      .traverse(endpoint => publicised(doc.service, endpoint).toValidatedNel)
      .toEither

  private def publicised(service: ServiceId, endpoint: AnyEndpoint): Either[String, AnyEndpoint] =
    if ContractRouting.pathSegments(endpoint.input).take(2) == ContractRouting.PublicPrefix then
      // Already public: the gateway's own endpoints are declared under `/api/v1` because that *is* their
      // path. Only another service's `/internal/v1` endpoints need rewriting.
      Right(tagged(service, endpoint))
    else
      ContractRouting
        .publicPathOf(endpoint)
        .map(_ => tagged(service, endpoint.copy(input = ContractRouting.rewritePrefix(endpoint.input))))

  private def tagged(service: ServiceId, endpoint: AnyEndpoint): AnyEndpoint =
    if endpoint.info.tags.contains(service.value) then endpoint
    else endpoint.tag(service.value)

  /** An operation id collision fails the *build*, not a request.
    *
    * Operation ids are what a generated client turns into method names, so two services claiming `getStatus`
    * would produce a client with one of them missing, silently, in whichever language the integrator used.
    * Every KUI operation id is already prefixed with its service (`cluster.ping`), so a collision means two
    * services think they are the same service -- worth stopping a release for.
    */
  private def duplicateOperationIds(docs: List[ServiceDoc]): Either[NonEmptyList[String], Unit] = {
    val ids = docs.flatMap(_.endpoints.flatMap(_.info.name))
    val duplicates = ids.groupBy(identity).collect { case (id, more) if more.sizeIs > 1 => id }.toList.sorted

    NonEmptyList
      .fromList(duplicates.map(id => s"the operation id '$id' is claimed by more than one endpoint"))
      .toLeft(())
  }

  /** The named schemas of a merged document, for the disambiguation assertion. */
  def schemaNames(document: OpenAPI): List[String] =
    document.components.toList.flatMap(_.schemas.keys).sorted

  /** Every path in the document, sorted. */
  def paths(document: OpenAPI): List[String] = document.paths.pathItems.keys.toList.sorted
}
