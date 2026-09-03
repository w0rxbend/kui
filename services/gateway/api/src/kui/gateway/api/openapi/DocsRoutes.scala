package kui.gateway.api.openapi

import cats.effect.kernel.Async
import cats.syntax.all.*
import io.circe.syntax.*
import sttp.apispec.openapi.circe.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.swagger.{SwaggerUI, SwaggerUIOptions}

import kui.gateway.api.routing.ServiceContracts
import kui.gateway.contract.GatewayEndpoints
import kui.kernel.ServiceId

/** One URL that documents the whole product.
  *
  * `GET /api/v1/openapi.json` is the merged machine-readable document; `GET /api/docs` is Swagger UI pointed
  * at it. Both are computed once at startup and cached, because the merge reads contract metadata and nothing
  * about it can change while the process runs -- and because rebuilding a few hundred endpoints' schemas per
  * request would be a slow page for no benefit.
  */
object DocsRoutes {

  val DocsPath: String = "api/docs"
  val DocumentPath: String = "openapi.json"

  /** The gateway's own endpoints, which are already public and need no rewriting. The capability stream is
    * included from `CapabilityRoutes`, because it cannot be described in the cross-compiled contract.
    */
  def gatewayEndpoints[F[_]]: List[AnyEndpoint] =
    kui.gateway.contract.InfoEndpoints.all ++
      kui.gateway.contract.AuthEndpoints.all ++
      kui.gateway.api.CapabilityRoutes.endpoints[F] ++
      // The dashboard: a public path the gateway answers itself rather than proxying, so it is documented
      // from the gateway's own list. Taking it from the cluster service's list instead would publish that
      // service's response shape for a path that answers with a different one.
      kui.gateway.contract.ClusterOverviewEndpoints.all

  /** Everything the gateway documents: its own endpoints plus every configured service it can route. */
  def documentation[F[_]](services: List[ServiceId]): List[ServiceDoc] =
    ServiceDoc(kui.gateway.application.Gateway.Id, gatewayEndpoints[F]) ::
      // `proxied`, not `of`: an endpoint the gateway answers itself is documented once, above, and one
      // that is not routed publicly at all - the cluster write - is not documented publicly at all. An
      // internal-only endpoint in the public document is an invitation to call something that 404s.
      services.sortBy(_.value).map(service => ServiceDoc(service, ServiceContracts.proxied(service)))

  /** The merged document, or the reasons it could not be built.
    *
    * The style check runs here, at construction, so a document that breaks the house rules stops the process
    * rather than being served to an integrator who then builds a client against it.
    */
  def document[F[_]](
      services: List[ServiceId],
      servers: List[String]
  ): Either[String, sttp.apispec.openapi.OpenAPI] =
    OpenApiMerge
      .merge(OpenApiMerge.Title, OpenApiMerge.Version, servers, documentation[F](services))
      .leftMap(problems => problems.toList.map("  - " + _).mkString("\n"))
      .flatMap { merged =>
        OpenApiStyleCheck.check(merged) match {
          case Nil => Right(merged)
          case violations =>
            Left(
              s"the merged OpenAPI document breaks the published API's house rules:\n" +
                OpenApiStyleCheck.report(violations)
            )
        }
      }

  def render(document: sttp.apispec.openapi.OpenAPI): String =
    document.asJson.deepDropNullValues.spaces2 + "\n"

  /** The two routes, over an already-merged document. */
  def apply[F[_]: Async](
      document: sttp.apispec.openapi.OpenAPI,
      basePath: String
  ): List[ServerEndpoint[Fs2Streams[F], F]] = {
    val json = render(document)

    val documentRoute: ServerEndpoint[Any, F] =
      endpoint.get
        .in(GatewayEndpoints.apiPrefix / DocumentPath)
        .out(stringJsonBody)
        .serverLogicSuccess[F](_ => Async[F].pure(json))

    // Swagger UI serves its own assets and its own redirect, so it is handed the document as a string and
    // the prefix to mount under. `basePath` is included because a deployment behind a reverse proxy at
    // `/kui` has to produce links that work from the browser's point of view, not the server's.
    documentRoute :: assetsBeforeRedirect(
      SwaggerUI[F](
        json,
        SwaggerUIOptions.default
          .pathPrefix(DocsPath.split('/').toList)
          .contextPath(contextOf(basePath))
      )
    )
  }

  /** Swagger UI's asset route has to be matched before its redirect route.
    *
    * Swagger UI answers `GET /api/docs` with a redirect to the trailing-slash form `/api/docs/`, and serves
    * every asset below that prefix. Tapir offers the trailing-slash form to the redirect endpoint first,
    * where it fails to decode -- and KUI's decode-failure handler answers that with a 400 rather than letting
    * the next endpoint try. A browser following Swagger UI's own redirect therefore landed on an error
    * instead of on the documentation.
    *
    * Putting the wildcard first means the trailing-slash form is served before the redirect endpoint ever
    * sees it. `GET /api/docs` has no trailing segment, so the wildcard cannot swallow it and the redirect
    * still works.
    */
  private def assetsBeforeRedirect[F[_]](
      routes: List[ServerEndpoint[Any, F]]
  ): List[ServerEndpoint[Any, F]] = {
    val (redirect, assets) = routes.partition(_.showShort == s"GET /$DocsPath")
    assets ++ redirect
  }

  private def contextOf(basePath: String): List[String] =
    basePath.split('/').filter(_.nonEmpty).toList

  /** The public paths of the merged document, used by the acceptance check and the suites. */
  def pathsOf(document: sttp.apispec.openapi.OpenAPI): List[String] = OpenApiMerge.paths(document)
}
