package kui.gateway.api.auth

import cats.effect.kernel.Sync
import cats.syntax.all.*
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.ServerEndpoint

import kui.gateway.application.session.{Session, SessionStore}
import kui.gateway.contract.AuthEndpoints
import kui.gateway.contract.dto.{AuthMeResponse, PermissionDto, PrincipalDto}
import kui.security.Principal
import kui.security.rbac.{ClusterPermission, ClusterScope, Rbac, RbacPolicy}

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

  /** @param policy
    *   the deployment's roles. `RbacPolicy.Disabled` until there is configuration to build one from (RB-001),
    *   and a disabled policy is not an empty answer: it grants everything, over every cluster, because a
    *   deployment that has configured no roles has not asked for authorization.
    */
  def apply[F[_]: Sync](
      store: SessionStore[F],
      policy: RbacPolicy = RbacPolicy.Disabled
  ): List[ServerEndpoint[Any, F]] =
    List(me[F](policy), logout[F](store))

  private def me[F[_]: Sync](policy: RbacPolicy): ServerEndpoint[Any, F] =
    AuthEndpoints.me.in(request).serverLogicSuccess[F](req => sessionOf[F](req).map(toResponse(policy, _)))

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

  private def toResponse(policy: RbacPolicy, session: Session): AuthMeResponse =
    AuthMeResponse(
      principal = toDto(session.principal),
      csrfToken = session.csrfSecret.value,
      authType = "disabled",
      // Computed here rather than proxied from a service, because this is the one answer that has to be
      // the same for every screen in the product: four microfrontends gating the same write control
      // against four different sources is four ways for them to disagree.
      permissions = Rbac.grants(policy, session.principal).map(toDto)
    )

  private def toDto(principal: Principal): PrincipalDto =
    PrincipalDto(principal.name.value, principal.roles.map(_.value).toList, principal.kind.wire)

  /** One grant, on the wire.
    *
    * Sorted, both lists, so that two responses describing the same permissions are byte-identical. An
    * unsorted set here would make the response change from request to request for no reason, which defeats
    * every cache and makes a golden-file test impossible to write.
    */
  private def toDto(granted: ClusterPermission): PermissionDto =
    PermissionDto(
      clusters = granted.clusters match {
        case ClusterScope.Every => List(ClusterScope.EveryWire)
        case ClusterScope.Named(clusters) => clusters.map(_.value).toList.sorted
      },
      resource = granted.permission.resource.wire,
      value = granted.permission.value.map(_.raw),
      actions = granted.permission.actions.map(_.wire).toList.sorted
    )
}
