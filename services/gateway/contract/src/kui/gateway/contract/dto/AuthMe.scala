package kui.gateway.contract.dto

import io.circe.syntax.*
import io.circe.{Codec, HCursor, Json}
import sttp.tapir.Schema

/** The caller's identity, as the wire states it — never `kui.security.Principal` itself, which is a domain
  * type the gateway's `application` layer owns and which `ARCHITECTURE.md` §3 forbids a contract module from
  * depending on (ADR-041 rule A2). `AuthRoutes` maps one to the other (ADR-033).
  */
final case class PrincipalDto(name: String, roles: List[String], kind: String)

object PrincipalDto {

  given Codec[PrincipalDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        name <- cursor.get[String]("name")
        roles <- cursor.get[List[String]]("roles")
        kind <- cursor.get[String]("kind")
      } yield PrincipalDto(name, roles, kind),
    (dto: PrincipalDto) =>
      Json.obj("name" -> dto.name.asJson, "roles" -> dto.roles.asJson, "kind" -> dto.kind.asJson)
  )

  given Schema[PrincipalDto] = Schema.derived[PrincipalDto]

  given CanEqual[PrincipalDto, PrincipalDto] = CanEqual.derived
}

/** What `GET /api/v1/auth/me` answers with — the endpoint the frontend's `ApiClient` (UI-005) reads its CSRF
  * token from before it sends its first mutating request.
  *
  * @param principal
  *   who the caller is. `PrincipalDto("anonymous", Nil, "anonymous")` for every M0 request.
  * @param csrfToken
  *   the value to echo back in `X-Kui-Csrf` on every non-`GET` request. Safe to expose to JavaScript — it is
  *   read from the response body, not a cookie — which is exactly the double-submit property CSRF protection
  *   depends on: a page on another origin can make the browser attach a cookie automatically, but it cannot
  *   read this response to learn the token to put beside it.
  * @param authType
  *   `"disabled"` in M0; the value M6's login form checks to decide what to show
  */
final case class AuthMeResponse(principal: PrincipalDto, csrfToken: String, authType: String)

object AuthMeResponse {

  given Codec[AuthMeResponse] = Codec.from(
    (cursor: HCursor) =>
      for {
        principal <- cursor.get[PrincipalDto]("principal")
        csrfToken <- cursor.get[String]("csrfToken")
        authType <- cursor.get[String]("authType")
      } yield AuthMeResponse(principal, csrfToken, authType),
    (response: AuthMeResponse) =>
      Json.obj(
        "principal" -> response.principal.asJson,
        "csrfToken" -> response.csrfToken.asJson,
        "authType" -> response.authType.asJson
      )
  )

  given Schema[AuthMeResponse] = Schema.derived[AuthMeResponse]

  given CanEqual[AuthMeResponse, AuthMeResponse] = CanEqual.derived
}
