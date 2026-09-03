package kui.gateway.api.auth

import io.circe.parser.decode

import kui.gateway.api.GatewayTestServer
import kui.gateway.contract.GatewayEndpoints
import kui.gateway.contract.dto.AuthMeResponse
import kui.testkit.KuiIOSuite

/** The session and CSRF boundary, exercised through a real server (ADR-019, GW-009).
  *
  * `CsrfCheckSuite` proves the decision function; this proves it is actually wired to every request — the
  * cookie really gets set with the exact attributes ADR-019 specifies, `/auth/me`'s token really is the one
  * `/auth/logout` requires, and a stale token really is refused.
  */
final class SessionMiddlewareSuite extends KuiIOSuite {

  private val meUri = s"${GatewayEndpoints.ApiPrefix}/auth/me"
  private val logoutUri = s"${GatewayEndpoints.ApiPrefix}/auth/logout"

  test("cookieAttributesAreExactlyAsSpecified") {
    GatewayTestServer.resource(devInsecureCookies = false).use { server =>
      server.get(meUri).map { response =>
        val cookie = response.header("Set-Cookie").getOrElse(fail("no Set-Cookie on /auth/me"))
        assert(cookie.startsWith(s"${SessionMiddleware.CookieName}="), cookie)
        assert(cookie.contains("HttpOnly"), cookie)
        assert(cookie.contains("Secure"), cookie)
        assert(cookie.contains("SameSite=Lax"), cookie)
        assert(cookie.contains("Path=/"), cookie)
      }
    }
  }

  test("secureIsOmittedOnlyWhenDevInsecureCookiesIsSet") {
    GatewayTestServer.resource(devInsecureCookies = true).use { server =>
      server.get(meUri).map { response =>
        val cookie = response.header("Set-Cookie").getOrElse(fail("no Set-Cookie on /auth/me"))
        assert(!cookie.contains("Secure"), cookie)
      }
    }
  }

  test("authMeAnswersAnonymousInM0") {
    GatewayTestServer.resource().use { server =>
      server.get(meUri).map { response =>
        val body = decode[AuthMeResponse](response.body).fold(error => fail(s"${response.body} ($error)"), identity)
        assertEquals(body.authType, "disabled")
        assertEquals(body.principal.kind, "anonymous")
        assertEquals(body.principal.name, "anonymous")
        assert(body.csrfToken.nonEmpty)
      }
    }
  }

  test("theCsrfTokenFromAuthMeWorksAndAStaleOneDoesNot") {
    GatewayTestServer.resource().use { server =>
      for {
        first <- server.get(meUri)
        cookie = cookieOf(first)
        token = tokenOf(first)
        allowed <- server.post(logoutUri, Map("Cookie" -> cookie, SessionMiddleware.CsrfHeaderName -> token))
        second <- server.get(meUri)
        secondCookie = cookieOf(second)
        // Logging out cleared the session; the new cookie belongs to a fresh session with a different
        // token, so replaying the first token against it must fail.
        stale <- server.post(logoutUri, Map("Cookie" -> secondCookie, SessionMiddleware.CsrfHeaderName -> token))
      } yield {
        assertEquals(allowed.code.code, 200, allowed.body)
        assertEquals(stale.code.code, 403, stale.body)
      }
    }
  }

  test("postWithNoCsrfHeaderIsForbidden") {
    GatewayTestServer.resource().use { server =>
      for {
        first <- server.get(meUri)
        cookie = cookieOf(first)
        response <- server.post(logoutUri, Map("Cookie" -> cookie))
      } yield {
        assertEquals(response.code.code, 403, response.body)
        assert(response.body.contains("KUI-FORBIDDEN"), response.body)
      }
    }
  }

  test("logoutRequiresPostAndClearsTheCookie") {
    GatewayTestServer.resource().use { server =>
      for {
        first <- server.get(meUri)
        cookie = cookieOf(first)
        token = tokenOf(first)
        loggedOut <- server.post(logoutUri, Map("Cookie" -> cookie, SessionMiddleware.CsrfHeaderName -> token))
        afterLogout <- server.get(meUri, Map("Cookie" -> cookie))
        afterBody = decode[AuthMeResponse](afterLogout.body).toOption.get
      } yield {
        assertEquals(loggedOut.code.code, 200, loggedOut.body)
        // The old session is gone; a request that presents its cookie again gets a *new* anonymous
        // session rather than an error — anonymous mode has nothing to fail on, and a fresh session is
        // exactly what ADR-019 says happens for a cookie the store no longer recognises.
        assertEquals(afterBody.authType, "disabled")
      }
    }
  }

  test("getLogoutIsFourOhFive") {
    // `/auth/logout` is declared `.post` only. A `GET` against it is a decode failure on the endpoint's
    // method — not a `PathCapture` or `PathsCapture` — so `libs/http`'s `ErrorInterceptor.shouldRespond`
    // treats it the same as a malformed path segment: it answers immediately, as `KUI-VALIDATION`, rather
    // than falling through to try another endpoint or reaching the reject handler's `KUI-ROUTE-NOT-FOUND`.
    // That is KUI's one error shape doing its job — a caller that mis-spells a method still gets the
    // envelope every other failure uses, not a bare `405` with no explanation.
    GatewayTestServer.resource().use { server =>
      server.get(logoutUri).map { response =>
        assertEquals(response.code.code, 400, response.body)
        assert(response.body.contains("KUI-VALIDATION"), response.body)
      }
    }
  }

  test("noSessionCookieIsStampedOntoAStaticAssetOrAHealthProbe") {
    // Two problems, one cause. A hashed asset is served `Cache-Control: public, max-age=31536000,
    // immutable`; a response that is simultaneously cacheable by a shared proxy for a year and carrying a
    // per-user session credential hands every later visitor the first visitor's session. And because a
    // session was minted for every cookie-less request, an unauthenticated client could evict every real
    // session out of the bounded store just by fetching `/ui/` in a loop.
    GatewayTestServer.resource().use { server =>
      for {
        asset <- server.get("/ui/main-a1b2c3d4.js")
        index <- server.get("/ui/")
        health <- server.get(s"${GatewayEndpoints.ApiPrefix}/health/live")
        unmatched <- server.get("/nothing-here")
        api <- server.get(meUri)
      } yield {
        // `/ui/main-<hash>.js` is the shape a linker-hashed asset has, and `StaticRoutesSuite` proves
        // that shape is served `public, max-age=31536000, immutable`. (This fixture has no linked
        // frontend, so the path falls through to the SPA shell; the subject here is the cookie.)
        assertEquals(asset.header("Set-Cookie"), None, "an immutably cacheable asset carries a session")
        assertEquals(index.header("Set-Cookie"), None, "the SPA shell carries a session")
        assertEquals(health.header("Set-Cookie"), None, "a health probe mints a session")
        assertEquals(unmatched.header("Set-Cookie"), None, "an unmatched path mints a session")
        // The gateway's own API still issues one, which is what the CSRF machinery runs on.
        assert(api.header("Set-Cookie").isDefined, "the API stopped issuing sessions")
      }
    }
  }

  test("everyInboundXKuiHeaderIsStrippedBeforeTheHandler") {
    // The two boundaries compose: a forged principal header does not survive to influence which session
    // this request gets, because `EdgeHeaders` runs first in the chain `GatewayWiring` builds.
    GatewayTestServer.resource().use { server =>
      server.get(meUri, Map("X-Kui-Principal" -> "forged-admin")).map { response =>
        val body = decode[AuthMeResponse](response.body).toOption.get
        assertEquals(body.principal.name, "anonymous")
        assertEquals(body.principal.kind, "anonymous")
      }
    }
  }

  private def cookieOf(response: sttp.client4.Response[String]): String =
    response
      .header("Set-Cookie")
      .flatMap(_.split(";").headOption)
      .getOrElse(fail("no Set-Cookie header"))

  private def tokenOf(response: sttp.client4.Response[String]): String =
    decode[AuthMeResponse](response.body).fold(error => fail(s"${response.body} ($error)"), _.csrfToken)
}
