package kui.gateway.api

import cats.effect.kernel.Async
import cats.syntax.all.*
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.{extractFromRequest, AnyEndpoint}

import kui.gateway.api.auth.SessionMiddleware
import kui.gateway.application.cluster.ClusterOverviewUseCase
import kui.gateway.contract.ClusterOverviewEndpoints
import kui.gateway.contract.dto.ClusterOverviewDto
import kui.http.ErrorInterceptor
import kui.security.Principal

/** `GET /api/v1/clusters`, answered by the gateway rather than proxied.
  *
  * The route is three lines because the decision is all in the use case; what is worth saying here is why the
  * path is served from this module at all. The cluster service publishes its own list endpoint, and
  * `ServiceContracts.aggregated` excludes it from the derived proxy routes so that exactly one route claims
  * this path. What a browser needs is not the cluster service's answer on its own — it is that answer, plus
  * what the gateway knows about its own ability to reach that service, plus the last rows it saw for the case
  * where it cannot reach it at all.
  *
  * The caller's identity and correlation id are read off the request the same way every proxied route reads
  * them: the correlation id is the one the edge already attached, never a freshly minted one, so the id a
  * user quotes out of an error body matches the log lines that explain it.
  */
object ClusterOverviewRoutes {

  def apply[F[_]: Async](overview: ClusterOverviewUseCase[F]): List[ServerEndpoint[Any, F]] =
    List(
      ClusterOverviewEndpoints.overview
        .securityIn(extractFromRequest[ServerRequest](identity))
        .serverSecurityLogicSuccess[ServerRequest, F](request => Async[F].pure(request))
        .serverLogicSuccess(request => _ => answer[F](overview, request))
    )

  /** Every endpoint this file serves, for the merged OpenAPI document. */
  val endpoints: List[AnyEndpoint] = ClusterOverviewEndpoints.all

  private def answer[F[_]: Async](
      overview: ClusterOverviewUseCase[F],
      request: ServerRequest
  ): F[ClusterOverviewDto] =
    ErrorInterceptor.correlationIdOf[F](request).flatMap { correlationId =>
      val principal = request
        .attribute(SessionMiddleware.Attribute)
        .map(_.principal)
        .getOrElse(Principal.Anonymous)

      overview.overview(principal, correlationId)
    }
}
