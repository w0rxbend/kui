package kui.gateway.contract

import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody

import kui.contracts.ErrorEnvelope
import kui.gateway.contract.dto.AuthMeResponse

/** `GET /api/v1/auth/me` and `POST /api/v1/auth/logout` — the session and CSRF endpoints (ADR-019).
  *
  * Both are declared as ordinary public endpoints, carrying no `X-Kui-Principal` security input: the session
  * lives in a cookie, not in the header the gateway signs for *internal* calls, and the cookie is read at the
  * `api` layer by `SessionMiddleware` rather than through Tapir's own cookie codec, so that a missing or
  * invalid cookie can fall back to a fresh anonymous session instead of failing the request the way a Tapir
  * security input's decode failure would.
  */
object AuthEndpoints {

  val me: PublicEndpoint[Unit, ErrorEnvelope, AuthMeResponse, Any] =
    GatewayEndpoints.base.get
      .in("auth" / "me")
      .out(jsonBody[AuthMeResponse])
      .name("gateway.auth.me")
      .summary("The caller's identity and the CSRF token to use for mutations")
      .tag("auth")

  val logout: PublicEndpoint[Unit, ErrorEnvelope, Unit, Any] =
    GatewayEndpoints.base.post
      .in("auth" / "logout")
      .name("gateway.auth.logout")
      .summary("Clears the session cookie")
      .tag("auth")

  val all: List[AnyEndpoint] = List(me, logout)
}
