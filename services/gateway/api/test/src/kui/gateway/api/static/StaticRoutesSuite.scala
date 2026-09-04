package kui.gateway.api.static

import scala.concurrent.duration.DurationInt

import cats.effect.IO
import cats.effect.kernel.Resource
import cats.syntax.all.*
import sttp.client4.*
import sttp.client4.httpclient.fs2.HttpClientFs2Backend
import sttp.model.Uri

import kui.config.ServerConfig
import kui.http.{ErrorInterceptor, KuiServer}
import kui.kernel.{Host, Port}
import kui.testkit.KuiIOSuite
import kui.testkit.fakes.FakeStructuredLogger

/** That the browser gets the shell, an unhashed asset it must re-fetch, a hashed one it may cache forever,
  * and a plain HTTP 404-shaped API failure never disguised as HTML — on a real server, because content
  * negotiation and cache headers are properties of a response, not of the function that built one.
  */
final class StaticRoutesSuite extends KuiIOSuite {

  /** The bootstrap block a real gateway would build for this base path — the same derivation
    * `kui.gateway.api.GatewayApi.bootstrapOf` does, so the suite exercises the shape production actually
    * sends rather than a fixed value that happens to agree with one test case.
    */
  private def bootstrapFor(basePath: String): BootstrapConfig =
    BootstrapConfig(basePath, s"$basePath/api/v1", "0.1.0-SNAPSHOT")

  /** A gateway serving only the static routes, from the suite's own fixture tree
    * (`test/resources/web-test`) rather than production's `/web` — a suite must not depend on assets a
    * frontend build would have to link in first.
    */
  private def server(basePath: String = "/", resourcePrefix: String = "/web-test"): Resource[IO, Running] =
    for {
      logger <- Resource.eval(FakeStructuredLogger[IO])
      normalised = if basePath == "/" then "" else basePath
      routes = StaticRoutes[IO](normalised, bootstrapFor(normalised), resourcePrefix)
      config = ServerConfig(Host.unsafe("localhost"), Port.unsafe(0), basePath)
      binding <- KuiServer.resource[IO](
        config,
        routes,
        ErrorInterceptor.interceptors[IO](logger),
        logger,
        gracefulShutdown = 10.millis
      )
      backend <- HttpClientFs2Backend.resource[IO]()
    } yield Running(binding, backend)

  private final case class Running(binding: KuiServer.ServerBinding, backend: Backend[IO]) {
    def get(path: String): IO[Response[String]] =
      basicRequest
        .get(Uri.unsafeParse(s"http://localhost:${binding.port}$path"))
        .response(asStringAlways)
        .followRedirects(false)
        .send(backend)

    def head(path: String): IO[Response[String]] =
      basicRequest
        .head(Uri.unsafeParse(s"http://localhost:${binding.port}$path"))
        .response(asStringAlways)
        .followRedirects(false)
        .send(backend)
  }

  test("servesIndexForAnUnknownUiPath") {
    // The SPA fallback rule: Waypoint owns routing once the page has loaded, so a deep link has to reach
    // the shell before the shell can even look at the path.
    server().use(_.get("/ui/clusters/some/deep/link")).map { response =>
      assertEquals(response.code.code, 200, response.body)
      assertEquals(response.header("Content-Type"), Some("text/html; charset=utf-8"))
      assert(response.body.contains(IndexHtml.BootstrapElementId), response.body)
    }
  }

  test("servesIndexAtTheBareUiRootRatherThanADirectoryListing") {
    // `/ui/` has zero path segments after `ui` is consumed, which is also the shape
    // `getClass.getResourceAsStream` answers for a *directory* — with a listing of its child names, not a
    // 404 — so this is the case a naive "resolve the path, fall back on failure" implementation gets wrong
    // silently: it would 200 with the text `index.html` as its entire body.
    server().use(_.get("/ui/")).map { response =>
      assertEquals(response.code.code, 200, response.body)
      assertEquals(response.header("Content-Type"), Some("text/html; charset=utf-8"))
      assert(response.body.contains(IndexHtml.BootstrapElementId), response.body)
      assert(!response.body.contains("importmap.json"), response.body)
    }
  }

  test("servesARealAssetWithTheRightContentType") {
    val cases = List(
      "/ui/main.js" -> "text/javascript; charset=utf-8",
      "/ui/styles.css" -> "text/css; charset=utf-8",
      "/ui/importmap.json" -> "application/json; charset=utf-8",
      "/ui/logo.svg" -> "image/svg+xml",
      "/ui/font.woff2" -> "font/woff2"
    )

    server().use { running =>
      cases.traverse { (path, expected) =>
        running.get(path).map { response =>
          assertEquals(response.code.code, 200, s"$path: ${response.body}")
          assertEquals(response.header("Content-Type"), Some(expected), path)
        }
      }
    }
  }

  test("doesNotServeIndexForAnUnknownApiPath") {
    // The static routes only claim `/ui/**` and `/`; a typo under `/api/v1` must reach the reject handler
    // and come back as the error envelope, never as HTML that looks like a working page.
    server().use(_.get("/api/v1/nope")).map { response =>
      assertEquals(response.code.code, 404)
      assert(response.header("Content-Type").exists(_.contains("json")), response.body)
      assert(!response.body.contains("<html"), response.body)
    }
  }

  test("indexIsNoCacheAndHashedAssetsAreImmutable") {
    server().use { running =>
      for {
        index <- running.get("/ui/")
        hashed <- running.get("/ui/main-a1b2c3d4.js")
        plain <- running.get("/ui/main.js")
      } yield {
        assertEquals(index.header("Cache-Control"), Some("no-cache"))
        assertEquals(hashed.header("Cache-Control"), Some("public, max-age=31536000, immutable"))
        // An unhashed name has to be re-checked on every load: the next deploy can change its bytes
        // without changing its name, and a browser that cached it forever would never see the update.
        assertEquals(plain.header("Cache-Control"), Some("no-cache"))
      }
    }
  }

  test("bootstrapJsonMatchesTheConfiguredBasePath") {
    val cases = List("" -> "", "/kui" -> "/kui")

    cases.traverse { (basePath, expectedInHref) =>
      server(basePath = basePath).use(_.get(s"$basePath/ui/some/route")).map { response =>
        assert(
          response.body.contains(s"""<base href="$expectedInHref/ui/">"""),
          s"basePath '$basePath': ${response.body}"
        )
      }
    }.void
  }

  test("pathTraversalIsRefused") {
    val attempts = List(
      "/ui/../../etc/passwd",
      "/ui/%2e%2e/%2e%2e/etc/passwd",
      "/ui/..%2f..%2fetc/passwd"
    )

    server().use { running =>
      attempts.traverse { path =>
        running.get(path).map { response =>
          // Never a file, and never a 200: either tapir's own path-capture already collapsed the segment
          // (a 200 serving `index.html` would be the SPA fallback answering an unmatched path, and even
          // that is not "the file"), or `StaticRoutes.sanitize` refused it outright. What must never
          // happen is the traversal reaching `getClass.getResourceAsStream` unfiltered.
          assert(response.code.code != 200 || !response.body.contains("root:"), s"$path: ${response.body}")
          assert(response.code.code == 400 || response.code.code == 404, s"$path -> ${response.code.code}")
        }
      }
    }
  }

  test("basePathIsAppliedToEveryServedUrlAndTheRedirect") {
    server(basePath = "/kui").use { running =>
      for {
        // The redirect route matches the exact root of *this* deployment, which is `/kui` once the base
        // path is applied — not the bare `/`, which belongs to no deployment mounted at `/kui` at all.
        redirect <- running.get("/kui")
        bareRootUnderBasePath <- running.get("/")
        underBase <- running.get("/kui/ui/")
        bareUiUnderBasePath <- running.get("/ui/")
      } yield {
        assertEquals(redirect.code.code, 302)
        assertEquals(redirect.header("Location"), Some("/kui/ui/"))
        assertEquals(underBase.code.code, 200, underBase.body)
        // The bare paths a deployment mounted at `/kui` never answers — the acceptance criterion's own
        // words are "the bare paths 404".
        assertEquals(bareRootUnderBasePath.code.code, 404)
        assertEquals(bareUiUnderBasePath.code.code, 404)
      }
    }
  }

  test("headAnswersTheSameStatusAndHeadersAsGetWithNoBody") {
    // A health checker, an uptime monitor and a link checker all ask with `HEAD` before they ask with
    // anything else. Declaring only the `GET` route left every one of them reading `400 invalid path`
    // for a file the gateway serves perfectly well over `GET`, which is a wrong answer to a question
    // whose whole purpose is to be believed.
    val paths = List("/ui/main.js", "/ui/main-a1b2c3d4.js", "/ui/", "/ui/clusters/deep/link")

    server().use { running =>
      paths.traverse { path =>
        for {
          got <- running.get(path)
          headed <- running.head(path)
        } yield {
          assertEquals(headed.code.code, got.code.code, s"$path: ${headed.body}")
          assertEquals(headed.header("Content-Type"), got.header("Content-Type"), path)
          assertEquals(headed.header("Cache-Control"), got.header("Cache-Control"), path)
          // RFC 9110: the headers describe what a `GET` would return, and the body is empty.
          assertEquals(headed.header("Content-Length"), Some(got.body.getBytes("UTF-8").length.toString), path)
          assertEquals(headed.body, "", path)
        }
      }
    }.void
  }

  test("headOnARefusedPathStillRefusesIt") {
    // The safety rule has to hold for both methods, or `HEAD` becomes a way to ask questions about the
    // filesystem that `GET` refuses to answer.
    server().use(_.head("/ui/%2e%2e/%2e%2e/etc/passwd")).map { response =>
      assert(response.code.code == 400 || response.code.code == 404, s"${response.code.code}")
    }
  }

  test("theAssetsMissingPageAnswersWhenTheFrontendWasNeverLinked") {
    server(resourcePrefix = "/web-nonexistent").use(_.get("/ui/")).map { response =>
      assertEquals(response.code.code, 503, response.body)
      assert(response.body.contains("not bundled"), response.body)
    }
  }
}
