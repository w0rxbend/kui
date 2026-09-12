package kui.gateway.api.auth

import java.time.Instant

import cats.effect.IO
import sttp.model.{Header, StatusCode}
import sttp.tapir.server.model.ServerResponse

import kui.gateway.application.session.{InMemorySessionStore, SessionConfig}
import kui.kernel.UserName
import kui.security.{Principal, PrincipalKind}
import kui.testkit.KuiIOSuite

/** The two rules on the sign-in path that no case reached until W10-A1 went looking.
  *
  * Neither is reachable through `GatewayTestServer`: every suite in this tree builds a gateway with no
  * identity service, so `/auth/login` answers `KUI-UNSUPPORTED` before any of this runs, and the one route
  * that sets a session cookie of its own is the one that cannot be called. Both rules are therefore asserted
  * against the seams `AuthRoutes` and `SessionMiddleware` publish to this package for the purpose.
  */
final class AuthRoutesSuite extends KuiIOSuite {

  private val signedIn: Principal =
    Principal(UserName.unsafe("ada"), Set.empty, PrincipalKind.Session)

  test("signing in replaces the session rather than editing it, so a planted id is worthless") {
    // W10-A1: `store.delete(previous.id)` inside `signIn` had no case at all, and removing it left the
    // whole gateway module green — 1,270 targets, 29 suites. It is ADR-019's session-fixation defence:
    // an attacker who gets a victim to use an id of their choosing, through a link or a subdomain or an
    // XSS, holds a *live operator session* the moment that victim signs in, unless the id changes here.
    InMemorySessionStore.resource[IO](SessionConfig.Default).use { store =>
      val now = Instant.parse("2026-09-12T00:00:00Z")

      for {
        planted <- store.create(Principal.Anonymous, now)
        fresh <- AuthRoutes.replaceSession[IO](store, planted, signedIn)
        stale <- store.get(planted.id, now)
      } yield {
        // The id an attacker may already hold no longer resolves to anything.
        assertEquals(stale, None, "the session the request arrived on survived the sign-in")
        assertNotEquals(fresh.id.value, planted.id.value)
        // And the CSRF secret changed with it: keeping it would leave a forged mutation possible even
        // after the id rotated, which is half the defence and reads like all of it.
        assertNotEquals(fresh.csrfSecret.value, planted.csrfSecret.value)
        assertEquals(fresh.principal, signedIn)
      }
    }
  }

  test("a response that already carries a session cookie is recognised, so nothing stamps a second one") {
    // W10-A1: `alreadyCarriesSessionCookie` is what makes `SessionMiddleware` stand aside on the sign-in
    // routes, and neutering it so that it never fired left the gateway module green. Two `Set-Cookie`
    // headers for one name is a browser-dependent coin toss over which session the operator ends up in —
    // and the losing side of that toss is the session that was just thrown away.
    def responseWith(headers: List[Header]): ServerResponse[String] =
      ServerResponse(StatusCode.Ok, headers, Option.empty[String], None)

    val mine = Header("Set-Cookie", s"${SessionMiddleware.CookieName}=abc; Path=/; HttpOnly")

    assert(
      SessionMiddleware.alreadyCarriesSessionCookie(responseWith(List(mine))),
      "the route's own session cookie was not recognised, so the middleware will add a second one"
    )
    // Case-insensitively, because a header name is not case sensitive and a route is free to spell it
    // however it likes.
    assert(
      SessionMiddleware.alreadyCarriesSessionCookie(
        responseWith(List(Header("set-cookie", s"${SessionMiddleware.CookieName}=abc")))
      )
    )
    // Somebody else's cookie is not this one: a response that sets `kui_theme` must still be stamped.
    assert(
      !SessionMiddleware.alreadyCarriesSessionCookie(
        responseWith(List(Header("Set-Cookie", "kui_theme=dark")))
      )
    )
    // And a cookie whose name BEGINS with this one's is not this one either. Filed as V1 by this wave's
    // verification pass over W12-03 and reproduced here before it was closed: deleting the `=` from
    // `header.value.startsWith(s"$CookieName=")` left `./mill --no-daemon services.gateway.api.test` at
    // 1270/1270 SUCCESS. This is G15's exact-name rule on the other side of the same hop — G15 covers the
    // REQUEST cookie and is closed; the RESPONSE side had no case that could see the `=` go. With it gone,
    // any route that sets a cookie named `kui_session`-anything makes the middleware stand aside, the real
    // session cookie is never stamped, and the browser silently keeps the session it arrived with, which
    // on the sign-in path is the session `replaceSession` above has just thrown away.
    //
    // The existing negative cannot see it: `kui_theme=dark` does not begin with `kui_session` at all.
    assert(
      !SessionMiddleware.alreadyCarriesSessionCookie(
        responseWith(List(Header("Set-Cookie", s"${SessionMiddleware.CookieName}_backup=abc")))
      ),
      "a cookie whose name merely starts with the session cookie's name was taken for it, so the " +
        "middleware stands aside and the rotated session is never stamped on the response"
    )
    assert(!SessionMiddleware.alreadyCarriesSessionCookie(responseWith(Nil)))
  }
}
