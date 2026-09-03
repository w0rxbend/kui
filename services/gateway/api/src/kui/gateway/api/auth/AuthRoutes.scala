package kui.gateway.api.auth

import cats.effect.kernel.Sync
import cats.syntax.all.*
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.ServerEndpoint

import kui.gateway.application.session.{Session, SessionStore}
import kui.gateway.contract.AuthEndpoints
import kui.gateway.contract.dto.{AuthMeResponse, PrincipalDto}
import kui.security.Principal

/** Serving `GET /api/v1/auth/me` and `POST /api/v1/auth/logout`.
  *
  * Both read the session `SessionMiddleware`'s request-attaching interceptor already resolved, via the
  * request attribute, rather than resolving one independently. That works reliably only because attaching the
  * session happens in a `RequestInterceptor` — installed ahead of Tapir's own input decoding — and
  * `sttp.tapir.extractFromRequest` (below) is itself decoded as part of that same phase: an endpoint that
  * captured the request *after* decoding (an `EndpointInterceptor`, say) would see the version from before
  * the attribute existed. `SessionMiddleware`'s own documentation explains the ordering in full.
  */
object AuthRoutes {

  private val request: sttp.tapir.EndpointInput[ServerRequest] =
    sttp.tapir.extractFromRequest(identity)

  def apply[F[_]: Sync](store: SessionStore[F]): List[ServerEndpoint[Any, F]] =
    List(me[F], logout[F](store))

  private def me[F[_]: Sync]: ServerEndpoint[Any, F] =
    AuthEndpoints.me.in(request).serverLogicSuccess[F](req => sessionOf[F](req).map(toResponse))

  private def logout[F[_]: Sync](store: SessionStore[F]): ServerEndpoint[Any, F] =
    AuthEndpoints.logout.in(request).serverLogicSuccess[F] { req =>
      sessionOf[F](req).flatMap(session => store.delete(session.id))
    }

  /** Reads the session `SessionMiddleware` attached. In practice it is always present — the middleware runs
    * on every request before any endpoint's own logic does — but a route is written defensively rather than
    * with `.get`: a future interceptor-ordering mistake should surface as an internal error with a clear
    * cause, not a `NoSuchElementException` three stack frames from anything that explains it.
    */
  private def sessionOf[F[_]: Sync](req: ServerRequest): F[Session] =
    req.attribute(SessionMiddleware.Attribute) match {
      case Some(session) => session.pure[F]
      case None =>
        Sync[F].raiseError(
          new IllegalStateException(
            "no session attached to the request; SessionMiddleware must run before any route's own logic"
          )
        )
    }

  private def toResponse(session: Session): AuthMeResponse =
    AuthMeResponse(
      principal = toDto(session.principal),
      csrfToken = session.csrfSecret.value,
      authType = "disabled"
    )

  private def toDto(principal: Principal): PrincipalDto =
    PrincipalDto(principal.name.value, principal.roles.map(_.value).toList, principal.kind.wire)
}
