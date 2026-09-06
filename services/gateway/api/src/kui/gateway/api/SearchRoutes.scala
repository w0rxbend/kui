package kui.gateway.api

import cats.effect.kernel.Async
import cats.syntax.all.*
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.{extractFromRequest, AnyEndpoint}

import kui.gateway.api.auth.SessionMiddleware
import kui.gateway.application.search.SearchUseCase
import kui.gateway.contract.dto.SearchAnswerDto
import kui.gateway.contract.{SearchEndpoints, SearchQuery}
import kui.http.ErrorInterceptor
import kui.security.Principal

/** `GET /api/v1/search`, answered by the gateway rather than proxied.
  *
  * The route has no failure branch, and that is the design rather than an omission. The only thing that can
  * be wrong with this request is the query itself, which the contract's validators refuse before any handler
  * runs — so the 400 with `details[0].field == "q"` comes out of `ErrorInterceptor`, from the same decode
  * path every other endpoint's malformed input takes, and nothing is re-implemented here. Everything past
  * that point is a 200 with a `partial` list, because a search that 503s when one of three services is down
  * would take the top bar's field away from the two that are up.
  *
  * The caller's identity and correlation id are read off the request exactly as every proxied route reads
  * them: each upstream call carries the principal, so the topic, consumer and schema services each apply
  * their own permission rules to it and a caller sees only what they may see.
  */
object SearchRoutes {

  def apply[F[_]: Async](search: SearchUseCase[F]): List[ServerEndpoint[Any, F]] =
    List(
      SearchEndpoints.search
        .securityIn(extractFromRequest[ServerRequest](identity))
        .serverSecurityLogicSuccess[ServerRequest, F](request => Async[F].pure(request))
        .serverLogicSuccess(request => query => answer[F](search, request, query))
    )

  /** Every endpoint this file serves, for the merged OpenAPI document. */
  val endpoints: List[AnyEndpoint] = SearchEndpoints.all

  private def answer[F[_]: Async](
      search: SearchUseCase[F],
      request: ServerRequest,
      query: SearchQuery
  ): F[SearchAnswerDto] =
    ErrorInterceptor.correlationIdOf[F](request).flatMap { correlationId =>
      val principal = request
        .attribute(SessionMiddleware.Attribute)
        .map(_.principal)
        .getOrElse(Principal.Anonymous)

      search.search(query, principal, correlationId)
    }
}
