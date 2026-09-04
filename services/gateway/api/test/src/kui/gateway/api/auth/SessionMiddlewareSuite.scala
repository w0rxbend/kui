package kui.gateway.api.auth

import io.circe.parser.decode

import kui.gateway.api.GatewayTestServer
import kui.gateway.contract.GatewayEndpoints
import kui.gateway.contract.dto.AuthMeResponse
import kui.security.rbac.{ClusterScope, Resource}
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

  test("authMeCarriesTheExpandedPermissionSetTheBrowserGatesOn") {
    // E4. With no roles configured, the answer is a wildcard grant per resource over every cluster, not an
    // empty list: an empty list would read in the browser as "you may do nothing", and the interface would
    // hide every write control in the deployment that has asked for no authorization at all.
    GatewayTestServer.resource().use { server =>
      server.get(meUri).map { response =>
        val body = decode[AuthMeResponse](response.body).fold(error => fail(s"${response.body} ($error)"), identity)

        assertEquals(body.permissions.map(_.resource).toSet, Resource.values.map(_.wire).toSet)
        assert(body.permissions.forall(_.clusters == List(ClusterScope.EveryWire)))
        assert(body.permissions.forall(_.value.contains(".*")))

        val topic = body.permissions.find(_.resource == Resource.Topic.wire).getOrElse(fail("no TOPIC grant"))
        assertEquals(topic.actions.toSet, Resource.Topic.allActions.map(_.wire))
        // Sorted, so two responses describing the same permissions are byte-identical.
        assertEquals(topic.actions, topic.actions.sorted)
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

  test("getLogoutIsRouteNotFoundAndStillTheKuiEnvelope") {
    // `/auth/logout` is declared `.post` only, so a `GET` against it is a decode failure on the endpoint's
    // *method*. `libs/http`'s `ErrorInterceptor.shouldRespond` treats that as a routing question and lets
    // the router try the next endpoint, exactly as it does for a path that does not match; when no
    // endpoint claims the method either, the request reaches the reject handler and comes back as
    // `KUI-ROUTE-NOT-FOUND`, naming the method and the path.
    //
    // It used to answer `400 KUI-VALIDATION` from the first endpoint that declared this path, which meant
    // no *later* endpoint was ever tried — including one declared for a different method on the same path.
    // That is what made `HEAD /ui/main.js` answer `400` while `GET /ui/main.js` answered `200`. The error
    // shape a caller sees is still KUI's own envelope, which was the point of answering here at all; only
    // the code and the status changed, and 404 is the honest one for "nothing serves this".
    GatewayTestServer.resource().use { server =>
      server.get(logoutUri).map { response =>
        assertEquals(response.code.code, 404, response.body)
        assert(response.body.contains("KUI-ROUTE-NOT-FOUND"), response.body)
        assert(response.body.contains("GET"), response.body)
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
