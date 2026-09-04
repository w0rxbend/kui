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
  *   - a path that names a real file under the linked assets serves that file, with a `Cache-Control` that
  *     depends on whether the name looks hashed;
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

  /** A year, in seconds. What `Cache-Control: max-age` on an immutable asset is written in. */
  private val ImmutableMaxAgeSeconds: Long = 31_536_000L

  /** A hashed KUI asset name: something, a dash, at least eight hexadecimal characters, an extension.
    * `main-a1b2c3d4.js` matches; `main.js` and `index.html` do not. Scala.js's linker names bundle-split
    * chunks this way, which is what makes them safe to cache forever — a new build is a new name, never the
    * same name with different bytes.
    */
  private val HashedName = raw"^.+-[0-9a-fA-F]{8,}\.[A-Za-z0-9]+$$".r

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
      .out(statusCode)
      .out(byteArrayBody)
      .out(header[String]("Content-Type"))
      .out(header[String]("Cache-Control"))
      .errorOut(statusCode)
      .errorOut(stringBody)
      .name("gateway.static.ui")
      .serverLogic[F](segments => respond[F](bootstrap, resourcePrefix, segments))

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
      .out(statusCode)
      .out(header[String]("Content-Type"))
      .out(header[String]("Cache-Control"))
      .out(header[String]("Content-Length"))
      .errorOut(statusCode)
      .name("gateway.static.uiHead")
      .serverLogic[F](segments =>
        respond[F](bootstrap, resourcePrefix, segments).map(
          _.bimap(
            (status, _) => status,
            (status, bytes, contentType, cacheControl) =>
              (status, contentType, cacheControl, bytes.length.toString)
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
      segments: List[String]
  ): F[Either[(StatusCode, String), (StatusCode, Array[Byte], String, String)]] =
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
            case Nil => indexOrMissing(bootstrap, resourcePrefix)
            case nonEmpty =>
              resourceBytes(resourcePrefix, nonEmpty) match {
                case Some(bytes) =>
                  val (contentType, cacheControl) = asset(nonEmpty.last)
                  Right((StatusCode.Ok, bytes, contentType, cacheControl))
                case None if namesAnAsset(nonEmpty.last) =>
                  // A miss on something that is unmistakably a static asset is a real 404, not a route
                  // into the single-page application. See [[namesAnAsset]] for why that distinction
                  // has to be made here.
                  Left((StatusCode.NotFound, s"no such asset: ${nonEmpty.last}"))
                case None => indexOrMissing(bootstrap, resourcePrefix)
              }
          }
      }
    }

  /** Whether a final path segment names a file this deployment ships rather than a screen it routes to.
    *
    * The single-page fallback exists so that `/ui/clusters/quickstart/topics/orders.v1` — a path with no
    * file behind it — is answered with `index.html`, leaving the browser's router to decide what it means.
    * Applied to *every* miss, though, it also answers a request for a missing JavaScript module with an
    * HTML document and status `200`, and that is the difference between a page that reports a problem and
    * a page that is silently blank.
    *
    * That is not hypothetical. `main.js` is served `no-cache`, but the per-feature module files it imports
    * are named by content hash and served `immutable` for a year. After a redeploy, a browser still holding
    * the previous build's `main.js` asks for the previous build's chunk names. Those files are gone. With
    * the fallback applied, each of those requests succeeded with an HTML document that the browser then
    * tried to evaluate as an ES module, and the application never started — with nothing in the network log
    * marked as a failure, because every response was a `200`. This was reproduced on 2026-09-04 by
    * upgrading a running quickstart underneath an open browser profile.
    *
    * The test is the extension, and only against the extensions in [[ContentTypes]]. It cannot be "has a
    * dot": Kafka topic names contain dots, so `topics/orders.v1` is a screen, and `v1` is deliberately not
    * in that table. `js`, `css`, `map`, `png`, `woff2` and the rest are, and none of them is a plausible
    * ending for a KUI route — except `html`, which is excluded so that a hand-typed `/ui/index.html` still
    * reaches the shell.
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

  private def asset(name: String): (String, String) =
    (contentTypeOf(name), cacheControlOf(name))

  private def contentTypeOf(name: String): String =
    extensionOf(name).flatMap(ContentTypes.get).getOrElse {
      // A name this table has never heard of. `URLConnection`'s platform-dependent guess is still better
      // than always answering `application/octet-stream`, which would make an image in an unlisted format
      // download instead of render.
      Option(URLConnection.guessContentTypeFromName(name)).getOrElse(DefaultContentType)
    }

  /** `no-cache` for anything the SPA fallback could serve — `index.html` above all, so that a deploy is
    * visible on the very next request — and a year of `immutable` caching for a name the linker hashed,
    * because a hashed name and a new set of bytes never occur together (ADR-012).
    */
  private def cacheControlOf(name: String): String =
    if name == "index.html" || HashedName.findFirstIn(name).isEmpty then "no-cache"
    else s"public, max-age=$ImmutableMaxAgeSeconds, immutable"

  private def extensionOf(name: String): Option[String] =
    Option.when(name.contains('.'))(name.substring(name.lastIndexOf('.') + 1).toLowerCase)

  /** What a path with no matching file resolves to: the shell, so Waypoint can decide what the path means, or
    * the "not bundled" page when there is no shell to fall back to at all.
    */
  private def indexOrMissing(
      bootstrap: BootstrapConfig,
      resourcePrefix: String
  ): Either[(StatusCode, String), (StatusCode, Array[Byte], String, String)] =
    resourceBytes(resourcePrefix, List("index.html")) match {
      case Some(template) =>
        val rendered = IndexHtml.render(new String(template, StandardCharsets.UTF_8), bootstrap)
        Right((StatusCode.Ok, rendered.getBytes(StandardCharsets.UTF_8), ContentTypes("html"), "no-cache"))
      case None =>
        Right(
          (
            StatusCode.ServiceUnavailable,
            AssetsMissingPage.getBytes(StandardCharsets.UTF_8),
            ContentTypes("html"),
            "no-cache"
          )
        )
    }
}
