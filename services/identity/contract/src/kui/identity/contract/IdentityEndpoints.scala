package kui.identity.contract

import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody

import kui.contracts.rbac.EndpointAuthorization
import kui.contracts.{ErrorEnvelope, KuiEndpoint}
import kui.identity.contract.dto.*
import kui.security.SignedPrincipal

/** Everything `kui-identity-service` serves, described once (ADR-003, ADR-015).
  *
  * ==Why none of these is proxied to the browser==
  *
  * Every other service's contract becomes a public route: the gateway rewrites `/internal/v1` to `/api/v1`
  * and forwards the call. These are deliberately **not** in `ServiceContracts`, and the reason is the
  * session. A login is not a request whose answer is forwarded — it is the moment a browser is given a new
  * session cookie, a new CSRF secret and a rotated session id (ADR-019), and all three of those live in the
  * gateway. A proxied `/api/v1/identity/login` would return a principal in a JSON body and set no cookie,
  * which is a login that does not log anybody in.
  *
  * So the gateway's own `auth` routes under `/api/v1` call these, one hop inward, and the session is created
  * where sessions live. That also keeps the public authentication surface in one file that can be read end to
  * end.
  *
  * ==Why the caller is signed even for a login==
  *
  * These are `/internal/v1` endpoints and they carry the signed principal like every other (ADR-020). At a
  * login the principal is anonymous — nobody has signed in yet, that is the point — but the header still
  * proves the call came from *the gateway* and is bound to this exact request and body. Without it, anything
  * that could reach the identity service's port could attempt passwords against it directly, unlogged by the
  * edge and unthrottled by it.
  */
object IdentityEndpoints {

  val Segment: String = "identity"

  private val base: Endpoint[SignedPrincipal, Unit, ErrorEnvelope, Unit, Any] =
    KuiEndpoint.internal.in(Segment).tag("identity")

  /** What the login screen needs before anybody has signed in.
    *
    * It declares no resource requirement, which the RBAC evaluator reads as "anyone who can reach the cluster
    * gate may call it" — and there is no cluster in the path, so the gate is not consulted at all. That is
    * correct and deliberate: a login screen that could not ask what kind of login to draw would be a login
    * screen nobody could use.
    */
  val settings: Endpoint[SignedPrincipal, Unit, ErrorEnvelope, AuthSettingsDto, Any] =
    base.get
      .in("settings")
      .out(jsonBody[AuthSettingsDto])
      .name("identity.settings")
      .summary("Which kind of sign-in this deployment uses")
      .attribute(EndpointAuthorization.Key, EndpointAuthorization("identity.settings", List.empty))

  /** A username and a password in; a principal, or a forced password change, out. */
  val login: Endpoint[SignedPrincipal, LoginRequest, ErrorEnvelope, LoginResponse, Any] =
    base.post
      .in("login")
      .in(jsonBody[LoginRequest])
      .out(jsonBody[LoginResponse])
      .name("identity.login")
      .summary("Sign in with a username and a password")
      .attribute(EndpointAuthorization.Key, EndpointAuthorization("identity.login", List.empty))

  /** Finishing the change a first sign-in demands.
    *
    * It carries no permission requirement because the challenge in its body *is* the authorization: it was
    * issued moments ago to whoever proved this account's current password, is single use, and expires in
    * minutes.
    */
  val changePassword: Endpoint[SignedPrincipal, ChangePasswordRequest, ErrorEnvelope, Unit, Any] =
    base.post
      .in("password")
      .in(jsonBody[ChangePasswordRequest])
      .name("identity.password.change")
      .summary("Set a new password, completing a required change")
      .attribute(
        EndpointAuthorization.Key,
        EndpointAuthorization("identity.password.change", List.empty)
      )

  /** Everything the caller may do, expanded, across every cluster (RB-003).
    *
    * The caller is the signed principal on the request, so this endpoint takes no username: asking about
    * somebody *else's* permissions is a different question with a different answer and a different
    * permission, and conflating the two is how a permissions endpoint becomes a role-directory leak.
    */
  val permissions: Endpoint[SignedPrincipal, Unit, ErrorEnvelope, PermissionsResponse, Any] =
    base.get
      .in("permissions")
      .out(jsonBody[PermissionsResponse])
      .name("identity.permissions")
      .summary("What the calling principal is allowed to do")
      .attribute(EndpointAuthorization.Key, EndpointAuthorization("identity.permissions", List.empty))

  /** Begins an OpenID Connect sign-in. */
  val oidcStart: Endpoint[SignedPrincipal, Unit, ErrorEnvelope, OidcStartResponse, Any] =
    base.post
      .in("oidc" / "start")
      .out(jsonBody[OidcStartResponse])
      .name("identity.oidc.start")
      .summary("Where to send the browser to sign in with the configured provider")
      .attribute(EndpointAuthorization.Key, EndpointAuthorization("identity.oidc.start", List.empty))

  /** Completes one, turning an authorization code into a principal. */
  val oidcCallback: Endpoint[SignedPrincipal, OidcCallbackRequest, ErrorEnvelope, LoginResponse, Any] =
    base.post
      .in("oidc" / "callback")
      .in(jsonBody[OidcCallbackRequest])
      .out(jsonBody[LoginResponse])
      .name("identity.oidc.callback")
      .summary("Turn a provider's authorization code into a KUI principal")
      .attribute(EndpointAuthorization.Key, EndpointAuthorization("identity.oidc.callback", List.empty))

  val all: List[AnyEndpoint] =
    List(settings, login, changePassword, permissions, oidcStart, oidcCallback)
}
