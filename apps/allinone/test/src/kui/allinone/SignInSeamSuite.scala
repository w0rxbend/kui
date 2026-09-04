package kui.allinone

import scala.concurrent.duration.DurationInt

import cats.effect.IO
import cats.effect.kernel.Resource
import io.circe.parser
import sttp.client4.httpclient.fs2.HttpClientFs2Backend
import sttp.client4.{basicRequest, Backend, UriContext}
import sttp.model.StatusCode

import kui.config.{AuthConfig, AuthType, FormUserConfig, ServerConfig}
import kui.contracts.HttpHeaders
import kui.http.KuiServer
import kui.identity.domain.PasswordHash
import kui.identity.infrastructure.Pbkdf2PasswordHasher
import kui.kernel.{ClusterId, Host, Port, RoleName, Secret}
import kui.observability.Telemetry
import kui.security.rbac.*
import kui.testkit.KuiIOSuite
import kui.testkit.fakes.FakeStructuredLogger

/** Signing in to the whole product, over a real socket, exactly as a browser would.
  *
  * ==Why this suite exists, and why it is here rather than in either service==
  *
  * The sign-in path crosses three seams, and every one of them has been a source of defects in this project:
  * the gateway's session middleware, the gateway-to-identity call with its signed, body-bound principal
  * (ADR-020 Amendment 1), and the identity service's own decision. Each half is unit-tested on its own and
  * neither half's suite can see the seam. What is asserted below is only what a browser could observe:
  * a status, a `Set-Cookie`, and what `GET /api/v1/auth/me` says afterwards.
  *
  * The all-in-one process is the right place for it because it is the shape that contains both sides
  * (ADR-005), and because the in-process transport is the one the gateway uses to reach the identity service
  * on a laptop and in a small installation.
  */
final class SignInSeamSuite extends KuiIOSuite {

  private val ephemeral: ServerConfig = ServerConfig(Host.unsafe("localhost"), Port.unsafe(0), "/")

  private val Password: String = "correct horse battery staple"

  /** A policy that puts KUI's own group `platform` into a role, so that a successful sign-in is observably
    * *more* than an authenticated name: the roles have to have been resolved and to have travelled.
    */
  private val policy: RbacPolicy =
    RbacPolicy(
      List(
        Role(
          name = RoleName.unsafe("operators"),
          clusters = Set(ClusterId.unsafe("local")),
          subjects = List(Subject(Provider.Form, SubjectKind.Group, "platform", isRegex = false)),
          permissions = List(RbacPolicy.allPermission(kui.security.rbac.Resource.Topic, Some(ResourcePattern.Everything)))
        )
      ),
      None
    )

  private def configWith(hash: PasswordHash): AllInOneConfig =
    AllInOneConfig.Default.copy(
      server = ephemeral,
      auth = AuthConfig(
        AuthType.Form,
        List(FormUserConfig("ada", Secret(hash.encoded), Set("platform"), mustChangePassword = false)),
        None
      ),
      rbac = policy
    )

  /** The whole product on a port, plus an HTTP client that keeps no cookies of its own — every cookie in
    * these cases is one this suite read off a response and chose to send back.
    */
  private def running(config: AllInOneConfig): Resource[IO, (String, Backend[IO])] =
    for {
      logger <- Resource.eval(FakeStructuredLogger[IO])
      gateway <- AllInOneWiring.resource[IO](config, Telemetry.noop[IO], logger)
      binding <- KuiServer.resource[IO](
        config.server,
        gateway.routes,
        gateway.interceptors,
        logger,
        10.millis
      )
      backend <- HttpClientFs2Backend.resource[IO]()
    } yield (s"http://localhost:${binding.port}", backend)

  private def hashed: IO[PasswordHash] =
    Pbkdf2PasswordHasher.make[IO].flatMap(_.hash(Secret(Password)))

  /** `GET /auth/me`, which every browser calls first: it is where the CSRF token comes from. */
  private def me(base: String, backend: Backend[IO], cookie: Option[String]) =
    basicRequest
      .get(uri"$base/api/v1/auth/me")
      .pipe(request => cookie.fold(request)(value => request.header("Cookie", value)))
      .send(backend)

  private def login(
      base: String,
      backend: Backend[IO],
      cookie: String,
      csrf: String,
      username: String,
      password: String
  ) =
    basicRequest
      .post(uri"$base/api/v1/auth/login")
      .header("Cookie", cookie)
      .header(HttpHeaders.Csrf, csrf)
      .header("Content-Type", "application/json")
      .body(s"""{"username":"$username","password":"$password"}""")
      .send(backend)

  private def field(body: String, name: String): Option[String] =
    parser.parse(body).toOption.flatMap(_.hcursor.get[String](name).toOption)

  private def sessionCookieOf(headers: Seq[sttp.model.Header]): Option[String] =
    headers
      .filter(_.name.equalsIgnoreCase("Set-Cookie"))
      .map(_.value)
      .find(_.startsWith("kui_session="))
      .map(_.takeWhile(_ != ';'))

  // -----------------------------------------------------------------------------------------------

  test("a browser signs in, gets a new session cookie, and is somebody afterwards") {
    hashed.flatMap { hash =>
      running(configWith(hash)).use { (base, backend) =>
        for {
          anonymous <- me(base, backend, None)
          firstCookie = sessionCookieOf(anonymous.headers).getOrElse(
            fail("the anonymous request was given no session cookie")
          )
          csrf = anonymous.body.toOption.flatMap(field(_, "csrfToken")).getOrElse(fail("no CSRF token"))
          signedIn <- login(base, backend, firstCookie, csrf, "ada", Password)
          secondCookie = sessionCookieOf(signedIn.headers)
          after <- me(base, backend, secondCookie)
        } yield {
          assertEquals(signedIn.code, StatusCode.Ok, signedIn.body.toString)
          assertEquals(field(signedIn.body.merge, "status"), Some("signed_in"))

          // The session was *replaced*, not edited: the id the browser now holds is not the one it
          // presented. That is ADR-019's session-fixation defence, and it is the property a browser can
          // actually observe.
          assert(
            secondCookie.isDefined,
            s"signing in issued no session cookie; headers were ${signedIn.headers}"
          )
          assertNotEquals(
            secondCookie,
            Some(firstCookie),
            s"the session was not replaced; Set-Cookie headers were " +
              signedIn.headers.filter(_.name.equalsIgnoreCase("Set-Cookie")).toString
          )
          // Exactly one session cookie, too. Two would leave which session the operator ends up in to
          // the browser's tie-breaking rules.
          assertEquals(
            signedIn.headers
              .filter(_.name.equalsIgnoreCase("Set-Cookie"))
              .count(_.value.startsWith("kui_session=")),
            1,
            signedIn.headers.toString
          )

          val body = after.body.merge
          assertEquals(field(body, "authType"), Some("form"))
          assert(body.contains("\"name\":\"ada\""), body)
          assert(body.contains("operators"), s"the role resolved at sign-in did not reach /auth/me: $body")
          assert(body.contains("\"kind\":\"session\""), body)
        }
      }
    }
  }

  test("the wrong password is refused with 401 and leaves the browser anonymous") {
    hashed.flatMap { hash =>
      running(configWith(hash)).use { (base, backend) =>
        for {
          anonymous <- me(base, backend, None)
          cookie = sessionCookieOf(anonymous.headers).getOrElse(fail("no session cookie"))
          csrf = anonymous.body.toOption.flatMap(field(_, "csrfToken")).getOrElse(fail("no CSRF token"))
          refused <- login(base, backend, cookie, csrf, "ada", "hunter2")
          after <- me(base, backend, Some(cookie))
        } yield {
          assertEquals(refused.code, StatusCode.Unauthorized, refused.body.toString)
          // The same sentence for a wrong password as for an unknown name, and no hint which it was.
          val error = refused.body.merge
          assert(error.contains("KUI-UNAUTHENTICATED"), error)
          assert(!error.toLowerCase.contains("no such user"), error)
          assert(after.body.merge.contains("\"name\":\"anonymous\""), after.body.merge)
        }
      }
    }
  }

  test("an unknown account is refused with exactly the same answer") {
    hashed.flatMap { hash =>
      running(configWith(hash)).use { (base, backend) =>
        for {
          anonymous <- me(base, backend, None)
          cookie = sessionCookieOf(anonymous.headers).getOrElse(fail("no session cookie"))
          csrf = anonymous.body.toOption.flatMap(field(_, "csrfToken")).getOrElse(fail("no CSRF token"))
          unknown <- login(base, backend, cookie, csrf, "grace", Password)
          wrong <- login(base, backend, cookie, csrf, "ada", "hunter2")
        } yield {
          assertEquals(unknown.code, wrong.code)
          assertEquals(field(unknown.body.merge, "message"), field(wrong.body.merge, "message"))
        }
      }
    }
  }

  test("a sign-in without the CSRF token is refused before it reaches the identity service") {
    hashed.flatMap { hash =>
      running(configWith(hash)).use { (base, backend) =>
        for {
          anonymous <- me(base, backend, None)
          cookie = sessionCookieOf(anonymous.headers).getOrElse(fail("no session cookie"))
          forged <- basicRequest
            .post(uri"$base/api/v1/auth/login")
            .header("Cookie", cookie)
            .header("Content-Type", "application/json")
            .body(s"""{"username":"ada","password":"$Password"}""")
            .send(backend)
        } yield {
          // The protection the reference products switch off entirely. A hostile page can make a browser
          // send the cookie; it cannot read `/auth/me` to learn the token that has to go beside it.
          assertEquals(forged.code, StatusCode.Forbidden, forged.body.toString)
          assert(forged.body.merge.contains("KUI-FORBIDDEN"), forged.body.merge)
        }
      }
    }
  }

  test("signing out ends the session, and the next request is anonymous again") {
    hashed.flatMap { hash =>
      running(configWith(hash)).use { (base, backend) =>
        for {
          anonymous <- me(base, backend, None)
          firstCookie = sessionCookieOf(anonymous.headers).getOrElse(fail("no session cookie"))
          csrf = anonymous.body.toOption.flatMap(field(_, "csrfToken")).getOrElse(fail("no CSRF token"))
          signedIn <- login(base, backend, firstCookie, csrf, "ada", Password)
          cookie = sessionCookieOf(signedIn.headers).getOrElse(fail("signing in issued no cookie"))
          nowCsrf <- me(base, backend, Some(cookie)).map(
            _.body.toOption.flatMap(field(_, "csrfToken")).getOrElse(fail("no CSRF token"))
          )
          out <- basicRequest
            .post(uri"$base/api/v1/auth/logout")
            .header("Cookie", cookie)
            .header(HttpHeaders.Csrf, nowCsrf)
            .send(backend)
          after <- me(base, backend, Some(cookie))
        } yield {
          assertEquals(out.code, StatusCode.Ok, out.body.toString)
          // The old cookie no longer names a session, so the browser presenting it is given a fresh
          // anonymous one rather than the identity it used to have.
          assert(after.body.merge.contains("\"name\":\"anonymous\""), after.body.merge)
        }
      }
    }
  }

  test("the settings endpoint says what kind of sign-in this is, and carries no credential") {
    hashed.flatMap { hash =>
      running(configWith(hash)).use { (base, backend) =>
        basicRequest.get(uri"$base/api/v1/auth/settings").send(backend).map { response =>
          val body = response.body.merge

          assertEquals(response.code, StatusCode.Ok, body)
          assertEquals(field(body, "authType"), Some("form"))
          assert(body.contains("\"rbacEnabled\":true"), body)
          // The whole of the answer. `research/scala/security-research.md` records a reference product
          // serving its Kafka settings from an endpoint like this one; there is nothing here to serve.
          assert(!body.contains(hash.encoded), body)
          assert(!body.toLowerCase.contains("password"), body)
          assert(!body.toLowerCase.contains("bootstrap"), body)
        }
      }
    }
  }

  test("with authentication disabled — the default — nothing changes and nobody is asked to sign in") {
    running(AllInOneConfig.Default.copy(server = ephemeral)).use { (base, backend) =>
      for {
        settings <- basicRequest.get(uri"$base/api/v1/auth/settings").send(backend)
        identity <- me(base, backend, None)
      } yield {
        assertEquals(field(settings.body.merge, "authType"), Some("disabled"))
        assertEquals(field(settings.body.merge, "rbacEnabled"), None)
        assert(settings.body.merge.contains("\"rbacEnabled\":false"), settings.body.merge)

        val body = identity.body.merge
        assert(body.contains("\"name\":\"anonymous\""), body)
        assertEquals(field(body, "authType"), Some("disabled"))
        // Every write control stays visible in the quickstart: no roles configured means no restriction,
        // which is a wildcard grant per resource and never an empty list.
        assert(body.contains("\"clusters\":[\"*\"]"), body)
      }
    }
  }

  test("a password login is refused outright when this deployment does not use one") {
    running(AllInOneConfig.Default.copy(server = ephemeral)).use { (base, backend) =>
      for {
        anonymous <- me(base, backend, None)
        cookie = sessionCookieOf(anonymous.headers).getOrElse(fail("no session cookie"))
        csrf = anonymous.body.toOption.flatMap(field(_, "csrfToken")).getOrElse(fail("no CSRF token"))
        attempt <- login(base, backend, cookie, csrf, "ada", Password)
      } yield {
        // Not a 401, which would invite guessing: a deployment with authentication switched off has no
        // password to be wrong about, and says so.
        assertEquals(attempt.code, StatusCode.NotImplemented, attempt.body.toString)
        assert(attempt.body.merge.contains("KUI-UNSUPPORTED"), attempt.body.merge)
      }
    }
  }

  extension [A](value: A) private def pipe[B](f: A => B): B = f(value)
}
