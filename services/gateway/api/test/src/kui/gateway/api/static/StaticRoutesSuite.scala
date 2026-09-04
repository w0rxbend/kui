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

/** That the browser gets the shell, an asset it revalidates against a validator computed from that asset's
  * bytes, and a plain HTTP 404-shaped API failure never disguised as HTML — on a real server, because content
  * negotiation and cache headers are properties of a response, not of the function that built one.
  */
final class StaticRoutesSuite extends KuiIOSuite {

  /** The bootstrap block a real gateway would build for this base path — the same derivation
    * `kui.gateway.api.GatewayApi.bootstrapOf` does, so the suite exercises the shape production actually
    * sends rather than a fixed value that happens to agree with one test case.
    */
  private def bootstrapFor(basePath: String): BootstrapConfig =
    BootstrapConfig(basePath, s"$basePath/api/v1", "0.1.0-SNAPSHOT")

  /** A gateway serving only the static routes, from the suite's own fixture tree (`test/resources/web-test`)
    * rather than production's `/web` — a suite must not depend on assets a frontend build would have to link
    * in first.
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

  final private case class Running(binding: KuiServer.ServerBinding, backend: Backend[IO]) {
    def get(path: String): IO[Response[String]] =
      basicRequest
        .get(Uri.unsafeParse(s"http://localhost:${binding.port}$path"))
        .response(asStringAlways)
        .followRedirects(false)
        .send(backend)

    def getIfNoneMatch(path: String, etag: String): IO[Response[String]] =
      basicRequest
        .get(Uri.unsafeParse(s"http://localhost:${binding.port}$path"))
        .header("If-None-Match", etag)
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

  test("aMissingAssetIs404RatherThanTheShell") {
    // The upgrade case, and the reason this test exists rather than a comment. `main.js` is served
    // `no-cache`, but the per-feature module files it imports are named by content hash and served
    // `immutable` for a year. Redeploy KUI under an open browser and that browser asks for the previous
    // build's chunk names, which no longer exist. If the single-page fallback answers those, the browser
    // receives an HTML document with status 200 where it expected a JavaScript module, evaluates it as
    // one, and shows a blank page — with nothing in its network log marked as having failed.
    //
    // The distinguishing test is the file extension, so the two halves are asserted together: a name
    // ending in a known asset extension is a 404, and a route whose last segment merely *contains* a dot
    // — which every Kafka topic name does — is still the shell.
    val missingAssets = List(
      "/ui/internal-deadbeefdeadbeef.js",
      "/ui/gone.css",
      "/ui/main.js.map",
      "/ui/missing.woff2"
    )

    server().use { running =>
      missingAssets.traverse { path =>
        running.get(path).map { response =>
          assertEquals(response.code.code, 404, s"$path: ${response.body}")
          assert(!response.body.contains(IndexHtml.BootstrapElementId), s"$path served the shell")
        }
      } *> running.get("/ui/clusters/local/topics/orders.v1").map { response =>
        assertEquals(response.code.code, 200, response.body)
        assert(response.body.contains(IndexHtml.BootstrapElementId), response.body)
      } *> running.get("/ui/index.html").map { response =>
        // `html` is the one extension left out of the rule, so that a hand-typed `/ui/index.html` is
        // still answered rather than turned into a 404 by a change meant for JavaScript modules.
        assertEquals(response.code.code, 200, response.body)
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

  test("nothingIsCachedWithoutBeingRevalidated") {
    // Including the hashed-looking names, which is the part that changed. `internal-<hex>.js` is what
    // Scala.js emits for a bundle-split build, and this route used to serve it `immutable` for a year on
    // the grounds that such a name is content-addressed. It is not: it identifies the set of classes in
    // the chunk, and the linker's short member names can shift underneath an unchanged set. Two builds
    // of KUI produced the same `internal-…` filename with different bytes, and a browser holding the
    // year-old copy combined it with the new `main.js` and rendered nothing at all.
    //
    // So the assertion is about every kind of name at once: there is no name whose bytes a browser is
    // told it may keep without asking.
    val everyKindOfName =
      List("/ui/", "/ui/main-a1b2c3d4.js", "/ui/main.js", "/ui/styles.css", "/ui/font.woff2")

    server().use { running =>
      everyKindOfName.traverse { path =>
        running.get(path).map { response =>
          assertEquals(response.header("Cache-Control"), Some("no-cache"), path)
        }
      }
    }
  }

  test("everyServedAssetCarriesAStrongValidator") {
    // The other half of `no-cache`. "You may keep it, but ask before you use it" is only affordable if
    // there is something to ask *with*; without a validator every page load refetched the whole bundle,
    // and the largest chunk is about 6 MB.
    val everyKindOfName =
      List("/ui/", "/ui/main-a1b2c3d4.js", "/ui/main.js", "/ui/styles.css", "/ui/font.woff2")

    server().use { running =>
      everyKindOfName.traverse { path =>
        running.get(path).map { response =>
          val etag = response.header("ETag").getOrElse(fail(s"$path was served with no ETag"))
          assert(etag.startsWith("\"") && etag.endsWith("\""), s"$path: $etag is not a quoted strong tag")
          assert(!etag.startsWith("W/"), s"$path: a weak tag cannot support a byte-range or a strong match")
        }
      }
    }
  }

  test("aBrowserThatAlreadyHasTheseBytesIsAnswered304WithNoBody") {
    server().use { running =>
      for {
        first <- running.get("/ui/main.js")
        etag = first.header("ETag").getOrElse(fail("no ETag on the first response"))
        second <- running.getIfNoneMatch("/ui/main.js", etag)
      } yield {
        assertEquals(first.code.code, 200, first.body)
        assertEquals(second.code.code, 304, second.body)
        assertEquals(second.body, "", "a 304 must carry no body")
        assertEquals(second.header("ETag"), Some(etag))
        assertEquals(second.header("Cache-Control"), Some("no-cache"))
      }
    }
  }

  test("aBrowserHoldingAnOldValidatorIsSentTheCurrentBytes") {
    // The case the whole mechanism exists for, and the one `immutable` got wrong: a browser that has *a*
    // copy is not a browser that has *this* copy. The server decides, from the bytes it has now.
    server().use { running =>
      running.getIfNoneMatch("/ui/main.js", "\"0123456789abcdef0123456789abcdef\"").map { response =>
        assertEquals(response.code.code, 200, response.body)
        assert(response.body.nonEmpty, "the current bytes must be sent to a browser holding a stale tag")
      }
    }
  }

  test("aWeakTagAndAListOfTagsAreBothMatchedTheWayRfc9110Says") {
    // `If-None-Match` carries a list, and a cache may have weakened the tag on the way. The comparison a
    // 304 needs is the weak one, so `W/"x"` matches `"x"` — otherwise a perfectly valid cached copy is
    // refetched in full every time an intermediary touches the header.
    server().use { running =>
      for {
        first <- running.get("/ui/styles.css")
        etag = first.header("ETag").getOrElse(fail("no ETag"))
        weak <- running.getIfNoneMatch("/ui/styles.css", s"W/$etag")
        listed <- running.getIfNoneMatch("/ui/styles.css", s"\"something-else\", $etag")
        star <- running.getIfNoneMatch("/ui/styles.css", "*")
      } yield {
        assertEquals(weak.code.code, 304, weak.body)
        assertEquals(listed.code.code, 304, listed.body)
        assertEquals(star.code.code, 304, star.body)
      }
    }
  }

  test("anUpgradeUnderTheSameFileNameIsAnswered200WithTheNewBytes") {
    // The whole defect, reproduced as a test. `web-test` and `web-test-upgraded` are the same deployment
    // before and after a release: both hold a `main.js`, the two files differ, and the *name* is identical —
    // which is exactly what the Scala.js linker really does to a bundle chunk, and exactly what the old
    // `immutable` header assumed could never happen.
    //
    // A browser that fetched from the first build and comes back to the second with the tag it was given
    // must be sent the new bytes, and must be told a new tag. Two servers rather than one restart, because
    // a classpath resource cannot be edited underneath a running JVM; the request sequence a browser makes
    // is identical either way.
    for {
      before <- server(resourcePrefix = "/web-test").use(_.get("/ui/main.js"))
      tag = before.header("ETag").getOrElse(fail("no ETag from the first build"))
      after <- server(resourcePrefix = "/web-test-upgraded").use(_.getIfNoneMatch("/ui/main.js", tag))
      unchanged <- server(resourcePrefix = "/web-test-upgraded").use { running =>
        running.get("/ui/main.js").flatMap { fresh =>
          running.getIfNoneMatch("/ui/main.js", fresh.header("ETag").getOrElse(fail("no ETag")))
        }
      }
    } yield {
      assertEquals(after.code.code, 200, "an upgraded file must not be answered 304")
      assertNotEquals(after.body, before.body, "the browser must be sent the bytes of the new build")
      assert(after.body.contains("rebuilt"), after.body)
      assertNotEquals(after.header("ETag"), Some(tag), "new bytes must carry a new validator")
      // And the other half of the bargain: when nothing changed, nothing is downloaded.
      assertEquals(unchanged.code.code, 304, unchanged.body)
      assertEquals(unchanged.body, "")
    }
  }

  test("twoDifferentFilesDoNotShareAValidator") {
    // The validator is over the bytes, never over the name. That is the whole lesson of the defect this
    // replaces: `internal-<40 hex>.js` names the set of classes in a chunk, not the JavaScript emitted for
    // them, so two builds really did ship different bytes under one name.
    server().use { running =>
      for {
        script <- running.get("/ui/main.js")
        styles <- running.get("/ui/styles.css")
      } yield assertNotEquals(script.header("ETag"), styles.header("ETag"))
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
          assertEquals(
            headed.header("Content-Length"),
            Some(got.body.getBytes("UTF-8").length.toString),
            path
          )
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
