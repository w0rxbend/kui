package kui.gateway.api.static

import java.net.{URLConnection, URLDecoder}
import java.nio.charset.StandardCharsets

import cats.effect.kernel.{Async, Sync}
import cats.syntax.all.*
import sttp.model.{Header, StatusCode}
import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint

/** Serving the linked frontend from the gateway's own classpath (ADR-011, ADR-012).
  *
  * The browser and the API are one origin in every shipped deployment: there is no CORS, no second server to
  * keep in sync, no separate deployment step for the UI. `GET /ui/…` is one route, matched after every other
  * route the gateway serves, and it does three things depending on what the path names:
  *
  *   - a path that names a real file under the linked assets serves that file, `no-cache` with a strong
  *     `ETag` over its bytes, so a browser that already has it is answered `304` (see [[CachePolicy]]);
  *   - a path that does not — `/ui/clusters/some/deep/link` — serves `index.html`, because Waypoint owns
  *     routing once the page has loaded and a deep link has to reach the shell before the shell can look at
  *     it (the SPA fallback rule, `research/scala/frontend-research.md` §3.4);
  *   - `GET /` redirects to `<basePath>/ui/`, so a bookmark to the bare host still works.
  *
  * ==Why this is hand-written instead of built on `tapir-files`==
  *
  * `tapir-files` serves a classpath resource tree correctly but treats every unmatched path as a 404, which
  * is precisely the SPA fallback rule this task exists to implement — a naive layering of the two would mean
  * writing the fallback logic anyway, on top of a library doing work this route does not need (byte ranges,
  * conditional requests). `tapir-files` stays a declared dependency (GW-001) for GW-007, where the merged
  * OpenAPI document is served the same way Swagger UI expects.
  */
object StaticRoutes {

  /** What one served representation is: a status, the bytes, the content type, the cache policy and the
    * validator. Named because it is now five things and a five-tuple in three signatures is unreadable.
    */
  private type Served = (StatusCode, Array[Byte], String, String, String)

  /** Extensions this deployment never sends compressed and never varies by request — everything KUI itself
    * ships. The table is deliberately explicit rather than delegated to
    * `URLConnection.guessContentTypeFromName`, whose guesses are platform-dependent: the same `.js` file has
    * been observed to guess as `application/javascript` on one JVM distribution and `text/plain` on another,
    * and neither is the value a browser's module loader requires (`text/javascript`, per the HTML living
    * standard).
    */
  private val ContentTypes: Map[String, String] = Map(
    "html" -> "text/html; charset=utf-8",
    "js" -> "text/javascript; charset=utf-8",
    "mjs" -> "text/javascript; charset=utf-8",
    "css" -> "text/css; charset=utf-8",
    "json" -> "application/json; charset=utf-8",
    "svg" -> "image/svg+xml",
    "png" -> "image/png",
    "woff2" -> "font/woff2",
    "woff" -> "font/woff",
    "ico" -> "image/x-icon",
    "txt" -> "text/plain; charset=utf-8",
    "map" -> "application/json; charset=utf-8"
  )

  private val DefaultContentType: String = "application/octet-stream"

  /** The plain-text HTML a developer sees when the linked frontend was never bundled in.
    *
    * Not a stack trace and not a bare empty `200`: a backend-only build is an ordinary way to run the gateway
    * during development (`README.md`), and the person looking at this page needs to know that immediately
    * rather than debug a blank screen.
    */
  private val AssetsMissingPage: String =
    """<!doctype html><html><head><meta charset="utf-8"><title>KUI</title></head>
      |<body><h1>The UI is not bundled into this gateway.</h1>
      |<p>This build was made without the linked frontend. The API is still available under the
      |configured base path.</p></body></html>""".stripMargin

  /** The routes: the SPA-serving `/ui/…` and the redirect from `/`.
    *
    * @param basePath
    *   the deployment's mount point, as `kui.http.BasePath.normalize` returns it. Not applied again here —
    *   `KuiServer.resource` applies it once, over the whole route list — but needed to build the `Location`
    *   the redirect from `/` points at, and the `<base href>` inside `index.html`.
    * @param bootstrap
    *   the JSON block embedded in `index.html`
    * @param resourcePrefix
    *   where the linked assets live on the classpath. `/web` in production, and a suite's own fixture
    *   directory in `StaticRoutesSuite`.
    */
  def apply[F[_]: Async](
      basePath: String,
      bootstrap: BootstrapConfig,
      resourcePrefix: String = "/web"
  ): List[ServerEndpoint[Any, F]] =
    List(
      redirectToUi[F](basePath),
      serveUi[F](bootstrap, resourcePrefix),
      headUi[F](bootstrap, resourcePrefix)
    )

  /** Matches only the exact root path, `GET /`.
    *
    * An endpoint declared with no path input at all is not what it looks like: Tapir does not require the
    * request path to be empty in that case, it matches *any* path, because there is nothing declared to check
    * against. `rootOnly` is `paths`, mapped so that a non-empty segment list decodes to
    * `DecodeResult.Missing` rather than succeeding — which is the same signal `libs/http`'s
    * `ErrorInterceptor.shouldRespond` already treats as "this endpoint's path did not match, try the next
    * one" for a `PathsCapture`, exactly the way two endpoints sharing a path shape are disambiguated
    * elsewhere in this codebase (see that function's own comment). Without this, `redirectToUi` would answer
    * every request the routes before it did not, including a typo under `/api/v1`.
    */
  private val rootOnly: EndpointInput[Unit] =
    paths.mapDecode(segments => if segments.isEmpty then DecodeResult.Value(()) else DecodeResult.Missing)(
      _ => Nil
    )

  private def redirectToUi[F[_]: Async](basePath: String): ServerEndpoint[Any, F] =
    endpoint.get
      .in(rootOnly)
      .out(statusCode(StatusCode.Found))
      .out(header(Header.location(s"$basePath/ui/")))
      .name("gateway.static.rootRedirect")
      .serverLogicSuccess[F](_ => ().pure[F])

  private def serveUi[F[_]: Async](
      bootstrap: BootstrapConfig,
      resourcePrefix: String
  ): ServerEndpoint[Any, F] =
    endpoint.get
      .in("ui")
      .in(paths)
      .in(header[Option[String]]("If-None-Match"))
      .out(statusCode)
      .out(byteArrayBody)
      .out(header[String]("Content-Type"))
      .out(header[String]("Cache-Control"))
      .out(header[String]("ETag"))
      .errorOut(statusCode)
      .errorOut(stringBody)
      .name("gateway.static.ui")
      .serverLogic[F]((segments, ifNoneMatch) => respond[F](bootstrap, resourcePrefix, segments, ifNoneMatch))

  /** The same route for `HEAD`, answering the headers a `GET` would answer and no body.
    *
    * It exists because tapir matches a method exactly. With only the `GET` route declared, a `HEAD` for a
    * file the gateway serves perfectly well reached no endpoint at all and came back `400 invalid path` — a
    * wrong answer given to exactly the clients that ask with `HEAD` and believe what they are told: health
    * checkers, uptime monitors and link checkers. Browsers were never affected, because a browser fetches a
    * script with `GET`, which is why nothing looked broken on screen.
    *
    * `Content-Length` is set from the bytes the `GET` would have produced, which is what RFC 9110 requires of
    * a `HEAD`: the headers describe the representation, the body is omitted. The bytes themselves are read
    * and discarded rather than guessed at, so the length cannot drift from the file. There is no error body
    * either, for the same reason — a `HEAD` carries no body even when it fails.
    */
  private def headUi[F[_]: Async](
      bootstrap: BootstrapConfig,
      resourcePrefix: String
  ): ServerEndpoint[Any, F] =
    endpoint.head
      .in("ui")
      .in(paths)
      .in(header[Option[String]]("If-None-Match"))
      .out(statusCode)
      .out(header[String]("Content-Type"))
      .out(header[String]("Cache-Control"))
      .out(header[String]("ETag"))
      .out(header[String]("Content-Length"))
      .errorOut(statusCode)
      .name("gateway.static.uiHead")
      .serverLogic[F]((segments, ifNoneMatch) =>
        respond[F](bootstrap, resourcePrefix, segments, ifNoneMatch).map(
          _.bimap(
            (status, _) => status,
            (status, bytes, contentType, cacheControl, etag) =>
              (status, contentType, cacheControl, etag, bytes.length.toString)
          )
        )
      )

  /** The body of the route: turns a path into a status, bytes, a content type and a cache policy.
    *
    * Almost nothing here is a 404. `libs/http`'s reject handler only sees paths that reach no endpoint at
    * all, and every path under `/ui/…` reaches this one — what would be "not found" anywhere else is the SPA
    * fallback, answered `200` with `index.html`. The exceptions are a traversal attempt (`400`), the linked
    * frontend being entirely absent (`503`, see [[AssetsMissingPage]]), and a missing file whose name is
    * unmistakably a static asset rather than a screen (`404`, see [[namesAnAsset]]).
    */
  private def respond[F[_]: Sync](
      bootstrap: BootstrapConfig,
      resourcePrefix: String,
      segments: List[String],
      ifNoneMatch: Option[String]
  ): F[Either[(StatusCode, String), Served]] =
    Sync[F].delay {
      sanitize(segments) match {
        case None =>
          // A traversal attempt or an otherwise unrepresentable path. Refused outright rather than
          // resolved and rejected, so that no code path here ever calls `getResourceAsStream` with a
          // string an attacker chose unfiltered.
          Left((StatusCode.BadRequest, "invalid path"))
        case Some(safe) =>
          // A trailing slash — `/ui/`, or a real directory nested deeper — produces one trailing empty
          // segment once tapir splits the path. Dropped here rather than resolved: `getResourceAsStream`
          // answers a *directory* resource with a stream of its child names, not a 404, so without this a
          // request for `/ui/` would "succeed" by serving the literal text `index.html` — the directory
          // listing — as if it were a file.
          val fileSegments = safe match {
            case init :+ "" => init
            case other => other
          }

          fileSegments match {
            case Nil => indexOrMissing(bootstrap, resourcePrefix, ifNoneMatch)
            case nonEmpty =>
              resourceBytes(resourcePrefix, nonEmpty) match {
                case Some(bytes) =>
                  Right(revalidated(bytes, contentTypeOf(nonEmpty.last), CachePolicy, ifNoneMatch))
                case None if namesAnAsset(nonEmpty.last) =>
                  // A miss on something that is unmistakably a static asset is a real 404, not a route
                  // into the single-page application. See [[namesAnAsset]] for why that distinction
                  // has to be made here.
                  Left((StatusCode.NotFound, s"no such asset: ${nonEmpty.last}"))
                case None => indexOrMissing(bootstrap, resourcePrefix, ifNoneMatch)
              }
          }
      }
    }

  /** Whether a final path segment names a file this deployment ships rather than a screen it routes to.
    *
    * The single-page fallback exists so that `/ui/clusters/quickstart/topics/orders.v1` — a path with no file
    * behind it — is answered with `index.html`, leaving the browser's router to decide what it means. Applied
    * to *every* miss, though, it also answers a request for a missing JavaScript module with an HTML document
    * and status `200`, and that is the difference between a page that reports a problem and a page that is
    * silently blank.
    *
    * That is not hypothetical. When this was found, `main.js` was served `no-cache` while the per-feature
    * module files it imports were served `immutable` for a year on the strength of their names (see
    * [[CachePolicy]] for why that was wrong and what replaced it). After a redeploy, a browser still holding
    * the previous build's `main.js` asks for the previous build's chunk names. Those files are gone. With the
    * fallback applied, each of those requests succeeded with an HTML document that the browser then tried to
    * evaluate as an ES module, and the application never started — with nothing in the network log marked as
    * a failure, because every response was a `200`. This was reproduced on 2026-09-04 by upgrading a running
    * quickstart underneath an open browser profile.
    *
    * The test is the extension, and only against the extensions in [[ContentTypes]]. It cannot be "has a
    * dot": Kafka topic names contain dots, so `topics/orders.v1` is a screen, and `v1` is deliberately not in
    * that table. `js`, `css`, `map`, `png`, `woff2` and the rest are, and none of them is a plausible ending
    * for a KUI route — except `html`, which is excluded so that a hand-typed `/ui/index.html` still reaches
    * the shell.
    */
  private def namesAnAsset(name: String): Boolean =
    extensionOf(name).exists(extension => extension != "html" && ContentTypes.contains(extension))

  /** The path segments a request is safe to resolve against the classpath, or `None` when it is not.
    *
    * Every segment is percent-decoded first — `%2e%2e` is `..` wearing a disguise, and `..%2f..%2fetc` is a
    * whole traversal hiding inside what tapir saw as one path segment, because the encoded slash kept it from
    * being split — and then checked for what would let a path escape the resource tree it was supposed to be
    * confined to: a literal `..` or `.` segment, and a *decoded* segment that itself contains a `/` or a `\`,
    * which is what a segment like `..%2f..%2fetc` becomes after decoding. Rejecting it here, before it is
    * ever joined into a resource path, is what stops `getResourceAsStream` from being called with a string an
    * attacker chose unfiltered — on an exploded classpath directory, a `..` really does walk up the
    * filesystem.
    */
  private def sanitize(segments: List[String]): Option[List[String]] = {
    val decoded = scala.util.Try(segments.map(URLDecoder.decode(_, StandardCharsets.UTF_8)))
    decoded.toOption.filter(_.forall(isSafeSegment))
  }

  private def isSafeSegment(segment: String): Boolean =
    segment != ".." && segment != "." && !segment.contains('\\') && !segment.contains('/') &&
      !segment.contains(' ')

  private def resourceBytes(resourcePrefix: String, segments: List[String]): Option[Array[Byte]] = {
    val path = (resourcePrefix :: segments).mkString("/").replace("//", "/")
    Option(getClass.getResourceAsStream(path)).map { stream =>
      try stream.readAllBytes()
      finally stream.close()
    }
  }

  private def contentTypeOf(name: String): String =
    extensionOf(name).flatMap(ContentTypes.get).getOrElse {
      // A name this table has never heard of. `URLConnection`'s platform-dependent guess is still better
      // than always answering `application/octet-stream`, which would make an image in an unlisted format
      // download instead of render.
      Option(URLConnection.guessContentTypeFromName(name)).getOrElse(DefaultContentType)
    }

  /** `no-cache` for everything KUI serves, so that a deploy is visible on the very next request.
    *
    * ## The assumption this used to make, and why it was false
    *
    * Until 2026-09-04 an `internal-<40 hex>.js` file — the names Scala.js emits for a bundle-split build —
    * was served `public, max-age=31536000, immutable`, on the stated grounds that "a hashed name and a new
    * set of bytes never occur together".
    *
    * They do. Two consecutive builds of KUI both produced a file named
    * `internal-3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.js`, and the two files differed: a method the first
    * one defined as `Vv` the second defined as `Vw`. That name is not a hash of the emitted JavaScript. It
    * identifies the *module* — the set of classes the linker decided to put in that chunk — and that set can
    * be unchanged while the code inside it is not, because the linker assigns short member names across the
    * whole program at once. Change any Scala file anywhere and the short names can shift under every chunk
    * that survived with its old name.
    *
    * The consequence was as bad as a caching bug gets. A browser that had opened KUI before an upgrade kept
    * the year-long copy of that chunk and combined it with the new `main.js`, which is served `no-cache` and
    * so was current. The new `main.js` called `Vw`; the year-old chunk had only `Vv`; the shell threw
    * `TypeError: ... is not a function` before it rendered anything, and the user saw a blank page that a
    * reload would not fix. Reproduced twice against the quickstart on 2026-09-04.
    *
    * ## What it costs now
    *
    * Correctness first: every asset is revalidated, so no browser can assemble a page out of two builds.
    *
    * The revalidation is cheap, because `no-cache` is paired with a strong `ETag` computed from the bytes
    * ([[etagOf]]) and `If-None-Match` is answered `304` ([[revalidated]]). A browser that already has the
    * current file spends a round trip rather than a download, and the largest chunk is about 6 MB, so that is
    * the difference between a page load and a page load with a wait in it.
    *
    * The validator is derived from the content and never from the name, which is exactly the mistake above
    * not repeated: the same name really can carry different bytes, and only the bytes can say so.
    */
  private val CachePolicy: String = "no-cache"

  /** A strong validator for exactly these bytes.
    *
    * SHA-256 over the content, truncated to sixteen bytes and hex-encoded. It is a validator and not a
    * security boundary — the question it answers is "are these the bytes the browser already has?" — and 128
    * bits is far more than enough for that while keeping the header short.
    *
    * Derived from the *bytes* and never from the file name, which is the whole lesson of the caching defect
    * this replaces: Scala.js's `internal-<40 hex>.js` names identify the set of classes in a chunk, not the
    * JavaScript emitted for them, so two different builds really do produce different bytes under the same
    * name. A validator computed from the content cannot make that mistake.
    *
    * `index.html` is hashed *after* the bootstrap block has been rendered into it, so a deployment that
    * changes only its configuration still invalidates the page that carries it.
    */
  private def etagOf(bytes: Array[Byte]): String = {
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
    digest.take(16).map(byte => f"${byte & 0xff}%02x").mkString("\"", "", "\"")
  }

  /** The response, answered `304` when the browser already has these exact bytes.
    *
    * ## Why this is the fix and `immutable` was not
    *
    * Every asset KUI serves is `no-cache`, which means "you may keep it, but ask before you use it". Without
    * a validator there is nothing to ask *with*, so every page load refetched the whole bundle — about 6 MB
    * for the largest chunk. With one, the ask costs a round trip and the answer is usually 304 and empty.
    *
    * The correctness that matters is unchanged and is the reason it is done this way round: a browser can
    * never assemble a page out of two builds, because it revalidates every asset every time and the server
    * decides, from the bytes it currently has, whether the copy in the cache is still that file.
    *
    * ## What counts as a match
    *
    * `If-None-Match` may carry a list, and each entry may be weak (`W/"…"`). The comparison a `304` needs is
    * RFC 9110's *weak* comparison, which ignores the `W/` prefix, so it is stripped before comparing. `*`
    * matches anything the server has, which for a route that always has a representation means always.
    */
  private def revalidated(
      bytes: Array[Byte],
      contentType: String,
      cacheControl: String,
      ifNoneMatch: Option[String]
  ): Served = {
    val etag = etagOf(bytes)

    if matches(ifNoneMatch, etag) then
      // No body. RFC 9110 requires a `304` to carry none, and the headers that describe the representation
      // are still sent so a cache can refresh what it holds about it.
      (StatusCode.NotModified, Array.emptyByteArray, contentType, cacheControl, etag)
    else (StatusCode.Ok, bytes, contentType, cacheControl, etag)
  }

  private def matches(ifNoneMatch: Option[String], etag: String): Boolean =
    ifNoneMatch.exists { header =>
      val candidates = header.split(',').map(_.trim).filter(_.nonEmpty)
      candidates.contains("*") || candidates.map(stripWeak).contains(etag)
    }

  private def stripWeak(candidate: String): String =
    if candidate.startsWith("W/") then candidate.substring(2) else candidate

  private def extensionOf(name: String): Option[String] =
    Option.when(name.contains('.'))(name.substring(name.lastIndexOf('.') + 1).toLowerCase)

  /** What a path with no matching file resolves to: the shell, so Waypoint can decide what the path means, or
    * the "not bundled" page when there is no shell to fall back to at all.
    */
  private def indexOrMissing(
      bootstrap: BootstrapConfig,
      resourcePrefix: String,
      ifNoneMatch: Option[String]
  ): Either[(StatusCode, String), Served] =
    resourceBytes(resourcePrefix, List("index.html")) match {
      case Some(template) =>
        val rendered = IndexHtml.render(new String(template, StandardCharsets.UTF_8), bootstrap)
        Right(
          revalidated(
            rendered.getBytes(StandardCharsets.UTF_8),
            ContentTypes("html"),
            CachePolicy,
            ifNoneMatch
          )
        )
      case None =>
        // The page is still given a validator, because every response on this route carries one, but this
        // is the one place a `304` is never answered: a browser must not be able to keep serving "the UI is
        // not bundled" out of its cache once a build with the UI in it is deployed. `revalidated` is
        // therefore deliberately not used here, and the status stays `503`.
        Right(
          (
            StatusCode.ServiceUnavailable,
            AssetsMissingPage.getBytes(StandardCharsets.UTF_8),
            ContentTypes("html"),
            CachePolicy,
            etagOf(AssetsMissingPage.getBytes(StandardCharsets.UTF_8))
          )
        )
    }
}
