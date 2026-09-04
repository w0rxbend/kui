package kui.ui.kernel.state

import com.raquo.laminar.api.L.*

import kui.security.Principal
import kui.security.rbac.ClusterPermission
import kui.ui.kernel.api.ApiError

/** What `GET /api/v1/auth/me` says about the current browser session.
  *
  * A kernel-owned shape rather than the gateway's response type. The gateway's contract module sits *above*
  * the kernel — the kernel is the bottom of the frontend and may not depend on any one service's wire shapes
  * — so the shell decodes the response and hands the result down as this. The same rule the earlier frontend
  * task applied to `UnavailableReason`, applied to identity.
  *
  * @param authType
  *   how this deployment authenticates: `"none"`, `"basic"`, `"oidc"`, … The settings and header screens show
  *   it, and the sign-in flow M6 adds branches on it.
  * @param permissions
  *   what this session is allowed to do, already expanded by the server. It travels with the identity rather
  *   than being fetched separately because it changes with the identity: a re-established session can be a
  *   different person with different roles, and two requests would leave a window in which the interface
  *   showed one person's controls to another.
  */
final case class AuthInfo(
    principal: Option[Principal],
    csrfToken: Option[String],
    authType: String,
    permissions: List[ClusterPermission]
)

object AuthInfo {

  /** What KUI assumes before `/auth/me` has answered, and what a deployment with authentication switched off
    * keeps forever.
    */
  val Unknown: AuthInfo =
    AuthInfo(principal = None, csrfToken = None, authType = "none", permissions = List.empty)

  given CanEqual[AuthInfo, AuthInfo] = CanEqual.derived
}

/** Who the browser is signed in as, and the token that proves a mutation came from KUI's own pages.
  *
  * ## Why the session expiring is an event and not a flag
  *
  * A page typically has several requests in flight at once — a list, a count, a detail panel. When a session
  * expires, all of them come back `401` within a few milliseconds of each other. Re-establishing the session
  * once per failed request would send three or five identical `/auth/me` calls, and in M6, where a `401`
  * raises a sign-in dialog, it would raise three or five dialogs.
  *
  * So `expired` fires at most once per expiry: the first `401` after a known-good state emits, and every
  * later one is swallowed until the state is known-good again. `markSignedIn` is what resets it, which means
  * a successful refresh re-arms the mechanism and a failed one does not.
  *
  * ## Why this is a class as well as an object
  *
  * The application has exactly one session, and `AuthState` below is it. A test needs several — one per
  * scenario, with no leakage between them — and a hard-wired singleton cannot give it that. The same
  * arrangement `Theme` and `Notifications` already use in this module.
  */
final class Auth {

  /** Who the user is, once `/auth/me` has answered. `None` before that, and after an expiry. */
  val principal: Var[Option[Principal]] = Var(AuthInfo.Unknown.principal)

  /** The token `ApiClient` puts in `X-Kui-Csrf` on every mutation (ADR-019).
    *
    * It is a `Var` and not a constant because it changes: it arrives with the first `/auth/me` and is
    * replaced whenever the session is re-established.
    */
  val csrfToken: Var[Option[String]] = Var(AuthInfo.Unknown.csrfToken)

  val authType: Var[String] = Var(AuthInfo.Unknown.authType)

  /** What this session may do. Empty until `/auth/me` answers, which is why every write control starts
    * disabled — see [[Permissions]] for why that is the right way round.
    */
  val permissions: Permissions = new Permissions

  private val expiredBus: EventBus[Unit] = new EventBus[Unit]

  /** Emits once each time a live session stops being one. See the class comment for why "once" matters. */
  val expired: EventStream[Unit] = expiredBus.events

  /** Whether an expiry has already been reported and not yet cleared by a successful refresh. */
  private var expiryReported: Boolean = false

  /** Adopts a fresh answer from `/auth/me`. Re-arms [[expired]]. */
  def markSignedIn(info: AuthInfo): Unit = {
    principal.set(info.principal)
    csrfToken.set(info.csrfToken)
    authType.set(info.authType)
    permissions.adopt(info.permissions)
    expiryReported = false
  }

  /** Reports that the server no longer recognises this session.
    *
    * Idempotent within one expiry: the second and later calls do nothing, so concurrent `401`s produce one
    * recovery attempt. Called by `ApiClient` for every `401`, whatever the endpoint.
    */
  def markExpired(): Unit =
    if !expiryReported then {
      expiryReported = true
      principal.set(None)
      csrfToken.set(None)
      // Every write control goes back to disabled the instant the session stops being one. Leaving the
      // old grants in place would offer an operator a delete button belonging to a session the server has
      // already forgotten.
      permissions.adopt(List.empty)
      expiredBus.emit(())
    }

  /** Fetches `/auth/me` and adopts the answer.
    *
    * `fetch` is a function rather than an endpoint because the endpoint is defined in the gateway's contract
    * module, which the kernel may not see. The shell supplies it during start-up:
    * `AuthState.refresh(() => client.call(AuthApi.me, ()).map(_.map(toAuthInfo)))`.
    *
    * The returned stream emits the outcome exactly once and never fails. A failure leaves the previous
    * identity in place: an unreachable gateway is not evidence that the user has been signed out, and wiping
    * the principal because one request did not arrive would sign a user out every time their train enters a
    * tunnel.
    */
  def refresh(fetch: () => EventStream[Either[ApiError, AuthInfo]]): EventStream[Either[ApiError, AuthInfo]] =
    fetch().map { outcome =>
      outcome.foreach(markSignedIn)
      outcome
    }
}

/** The application's one session, for code that is not under test. */
object AuthState {

  /** `lazy` so that importing this object does not create Airstream state a test did not ask for. */
  lazy val current: Auth = new Auth

  def principal: Var[Option[Principal]] = current.principal

  def csrfToken: Var[Option[String]] = current.csrfToken

  def authType: Var[String] = current.authType

  def permissions: Permissions = current.permissions

  def expired: EventStream[Unit] = current.expired

  def refresh(fetch: () => EventStream[Either[ApiError, AuthInfo]]): EventStream[Either[ApiError, AuthInfo]] =
    current.refresh(fetch)
}
