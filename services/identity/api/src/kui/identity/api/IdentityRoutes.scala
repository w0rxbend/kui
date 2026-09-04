package kui.identity.api

import cats.effect.kernel.Async
import cats.syntax.all.*
import sttp.tapir.server.ServerEndpoint

import kui.identity.application.*
import kui.identity.contract.IdentityEndpoints
import kui.identity.contract.dto.*
import kui.identity.domain.Credentials
import kui.http.principal.SecuredRoutes
import kui.kernel.Secret

/** Binding the identity contract to the use cases behind it.
  *
  * ==Why a login endpoint is still a signed, verified call==
  *
  * Every route here is a `/internal/v1` route and every one of them verifies the gateway's signed principal
  * first (ADR-020). At a login that principal is anonymous — nobody has signed in yet — but the token still
  * proves the call came from the gateway and is bound to this method, this path and these exact bytes. Take
  * it away and anything that can reach this service's port can try passwords against it directly, past the
  * edge's logging and past whatever the edge does about rate.
  *
  * ==Why the bodied routes go through `withBody`==
  *
  * ADR-020 Amendment 1: the signed digest covers the request body, and a body cannot be read in Tapir's
  * security stage. `SecuredRoutes.withBody` re-encodes the decoded input with the same codec and printer the
  * gateway signed it with, and verifies against that. It is not optional for these three: without it every
  * login would be refused as `request_mismatch`, which is exactly what happened to the first bodied mutation
  * this project shipped.
  */
object IdentityRoutes {

  def apply[F[_]: Async](
      settings: SettingsUseCase[F],
      login: LoginUseCase[F],
      changePassword: ChangePasswordUseCase[F],
      permissions: PermissionsUseCase[F],
      oidc: OidcLoginUseCase[F],
      secured: IdentityApi.Securing[F]
  ): List[ServerEndpoint[Any, F]] =
    List(
      settingsRoute(settings, secured),
      loginRoute(login, secured),
      changePasswordRoute(changePassword, secured),
      permissionsRoute(permissions, secured),
      oidcStartRoute(oidc, secured),
      oidcCallbackRoute(oidc, secured)
    )

  private def settingsRoute[F[_]: Async](
      settings: SettingsUseCase[F],
      secured: IdentityApi.Securing[F]
  ): ServerEndpoint[Any, F] =
    secured(IdentityEndpoints.settings) { _ => _ =>
      settings().map(
        _.map(answer => AuthSettingsDto(answer.mode.wire, answer.provider.map(_.label), answer.rbacEnabled))
      )
    }

  private def loginRoute[F[_]: Async](
      login: LoginUseCase[F],
      secured: IdentityApi.Securing[F]
  ): ServerEndpoint[Any, F] =
    secured.withBody(IdentityEndpoints.login)(SecuredRoutes.bodyBytes) { _ => request =>
      login(Credentials(request.username, Secret(request.password)))
        .map(_.map(IdentityMapping.login))
    }

  // No `Async` here, and that is not an oversight: this route hands the request straight to the use case
  // with nothing to map afterwards, so it needs no effect capability of its own. `-Werror` catches the
  // unused constraint, which is exactly the kind of copy-paste this codebase would otherwise accumulate.
  private def changePasswordRoute[F[_]](
      changePassword: ChangePasswordUseCase[F],
      secured: IdentityApi.Securing[F]
  ): ServerEndpoint[Any, F] =
    secured.withBody(IdentityEndpoints.changePassword)(SecuredRoutes.bodyBytes) { _ => request =>
      changePassword(Secret(request.challenge), Secret(request.newPassword))
    }

  /** Everything the *calling* principal may do — the one the gateway signed, not one named in the request. */
  private def permissionsRoute[F[_]: Async](
      permissions: PermissionsUseCase[F],
      secured: IdentityApi.Securing[F]
  ): ServerEndpoint[Any, F] =
    secured(IdentityEndpoints.permissions) { principal => _ =>
      permissions(principal).map(_.map(grants => PermissionsResponse(grants.map(IdentityMapping.grant))))
    }

  private def oidcStartRoute[F[_]: Async](
      oidc: OidcLoginUseCase[F],
      secured: IdentityApi.Securing[F]
  ): ServerEndpoint[Any, F] =
    secured(IdentityEndpoints.oidcStart) { _ => _ =>
      oidc.start().map(_.map(redirect => OidcStartResponse(redirect.authorizationUrl, redirect.state.value)))
    }

  private def oidcCallbackRoute[F[_]: Async](
      oidc: OidcLoginUseCase[F],
      secured: IdentityApi.Securing[F]
  ): ServerEndpoint[Any, F] =
    secured.withBody(IdentityEndpoints.oidcCallback)(SecuredRoutes.bodyBytes) { _ => request =>
      oidc
        .complete(request.code, Secret(request.state))
        .map(_.map(principal => LoginResponse.SignedIn(IdentityMapping.principal(principal))))
    }
}
