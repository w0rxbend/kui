package kui.gateway.api

import cats.effect.kernel.{Async, Clock}
import cats.syntax.all.*
import sttp.model.StatusCode
import sttp.tapir.AnyEndpoint
import sttp.tapir.model.ServerRequest

import kui.contracts.ErrorEnvelope
import kui.gateway.api.routing.{ContractRouting, RbacPreCheck}
import kui.kernel.CorrelationId
import kui.kernel.error.KuiError
import kui.security.Principal

/** Shared security stage for the hand-written streaming gateway routes.
  *
  * Unlike derived routes, these endpoints cannot wait for and decode a complete response. They still share
  * the same edge invariant: establish the caller and correlation id, reject a denied request before opening
  * the upstream stream, and render authorization failures with the product error envelope.
  */
private[api] object StreamAuthorization {

  final case class Authorized(principal: Principal, correlationId: CorrelationId)

  def authorize[F[_]: Async](
      request: ServerRequest,
      endpoint: AnyEndpoint,
      rbac: RbacPreCheck[F]
  ): F[Either[(ErrorEnvelope, StatusCode), Authorized]] =
    ContractRouting.callerOf[F](request).flatMap {
      case Left(error) => error.asLeft[Authorized].pure[F]
      case Right((principal, correlationId, cluster, segments)) =>
        rbac.check(principal, endpoint, cluster, segments).flatMap {
          case Right(_) => Authorized(principal, correlationId).asRight.pure[F]
          case Left(error) => failure[F](error, correlationId)
        }
    }

  private def failure[F[_]: Async](
      error: KuiError,
      correlationId: CorrelationId
  ): F[Either[(ErrorEnvelope, StatusCode), Authorized]] =
    Clock[F].realTimeInstant.map { now =>
      val envelope = ErrorEnvelope.of(error, correlationId, now)
      Left((envelope, StatusCode(ErrorEnvelope.statusOf(error))))
    }
}
