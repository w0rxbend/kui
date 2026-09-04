package kui.gateway.api

import cats.effect.kernel.{Async, Clock}
import cats.syntax.all.*
import sttp.model.StatusCode
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.{extractFromRequest, statusCode, AnyEndpoint}

import kui.contracts.ErrorEnvelope
import kui.gateway.api.auth.SessionMiddleware
import kui.gateway.application.topic.TopicOverviewUseCase
import kui.gateway.contract.TopicOverviewEndpoints
import kui.http.ErrorInterceptor
import kui.kernel.{ClusterId, TopicName}
import kui.security.Principal

/** `GET /api/v1/clusters/{clusterId}/topics/{topicName}/overview`, answered by the gateway.
  *
  * The route is thin because every decision is in the use case; what belongs here is the translation of its
  * one failure case into HTTP. That case is a topic — or a cluster — that does not exist, and it keeps the
  * status and the `KUI-*` code the topic service chose, rather than being flattened into a document with an
  * empty topic section. A page that renders "this topic has no partitions" for a topic that was deleted an
  * hour ago is worse than a 404, because it looks like an answer.
  *
  * Everything else is a 200. A topic service that could not be reached gives an `unavailable` topic section
  * and the page still says which cluster and which topic the user is looking at, which is more than a 503
  * does.
  *
  * The caller's identity and correlation id are read off the request exactly as every proxied route reads
  * them: the correlation id is the one the edge already attached, never a freshly minted one, so the id a
  * user quotes out of an error body matches the log lines that explain it.
  */
object TopicOverviewRoutes {

  def apply[F[_]: Async](overview: TopicOverviewUseCase[F]): List[ServerEndpoint[Any, F]] =
    List(
      TopicOverviewEndpoints.overview
        .errorOut(statusCode)
        .securityIn(extractFromRequest[ServerRequest](identity))
        .serverSecurityLogicSuccess[ServerRequest, F](request => Async[F].pure(request))
        .serverLogic(request => { case (cluster, topic) => answer[F](overview, request, cluster, topic) })
    )

  /** Every endpoint this file serves, for the merged OpenAPI document. */
  val endpoints: List[AnyEndpoint] = TopicOverviewEndpoints.all

  private def answer[F[_]: Async](
      overview: TopicOverviewUseCase[F],
      request: ServerRequest,
      cluster: ClusterId,
      topic: TopicName
  ): F[Either[(ErrorEnvelope, StatusCode), kui.gateway.contract.dto.TopicOverviewDto]] =
    ErrorInterceptor.correlationIdOf[F](request).flatMap { correlationId =>
      val principal = request
        .attribute(SessionMiddleware.Attribute)
        .map(_.principal)
        .getOrElse(Principal.Anonymous)

      overview.overview(cluster, topic, principal, correlationId).flatMap {
        case Right(document) => Async[F].pure(Right(document))
        case Left(error) =>
          Clock[F].realTimeInstant.map(now =>
            Left((ErrorEnvelope.of(error, correlationId, now), StatusCode(ErrorEnvelope.statusOf(error))))
          )
      }
    }
}
