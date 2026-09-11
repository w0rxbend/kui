package kui.gateway.api.openapi

import java.nio.file.{Files, Path}

import scala.collection.immutable.ListMap

import cats.effect.IO
import io.circe.parser
import munit.FunSuite
import sttp.apispec.openapi.{Operation, Parameter, ParameterIn, PathItem, Paths, Reference}

import kui.gateway.api.EdgeHeaders

/** That the document the browser's TypeScript client is generated from cannot ask a browser for a header the
  * gateway would throw away.
  *
  * This is the security half of the contract seam (ADR-048 §3). The other half -- that a contract change
  * fails the build -- lives in the frontend's `contract-drift` test, because only `tsc` can assert it.
  *
  * The assertions below fall into two groups, and the split is the point of the file.
  *
  * The **committed-file** group reads `docs/api/openapi.browser.json` off disk rather than projecting one
  * here, for the same reason `openApiCheck` exists: the committed file is what `openapi-typescript` reads,
  * and a suite that re-derives its own input proves only that the derivation is self-consistent.
  *
  * The **function** group calls `BrowserProjection.project` and reads the answer. It exists because the
  * committed-file group cannot fail for a broken projection: breaking `keep` so that every reserved header
  * survives leaves every assertion above green, since the file on disk was projected by yesterday's code. The
  * two answer different questions — "is the published document clean?" and "does the rule that cleans it
  * work?" — and until wave 5 only the first was ever asked.
  */
final class BrowserProjectionSuite extends FunSuite {

  private def workspaceRoot: Path = {
    // The test's working directory is the module's output directory under `out/`, so the repository root is
    // found by walking up to the directory that has `docs/api` in it rather than by counting `..`s.
    def upwards(from: Path): Path =
      if Files.isDirectory(from.resolve("docs").resolve("api")) then from
      else Option(from.getParent).map(upwards).getOrElse(fail("no docs/api above the working directory"))
    upwards(Path.of("").toAbsolutePath)
  }

  private val browserDocument: io.circe.Json =
    parser
      .parse(Files.readString(workspaceRoot.resolve("docs/api/openapi.browser.json")))
      .fold(failure => fail(s"docs/api/openapi.browser.json is not JSON: $failure"), identity)

  private val serviceDocument: io.circe.Json =
    parser
      .parse(Files.readString(workspaceRoot.resolve("docs/api/openapi.json")))
      .fold(failure => fail(s"docs/api/openapi.json is not JSON: $failure"), identity)

  private val Methods = Set("get", "put", "post", "delete", "options", "head", "patch", "trace")

  /** Every header parameter the document asks a caller for, as `name -> how many operations declare it`. */
  private def headerParameters(document: io.circe.Json): Map[String, Int] = {
    val cursor = document.hcursor.downField("paths")
    val names = for {
      pathKeys <- cursor.keys.toList
      path <- pathKeys
      operationKeys <- cursor.downField(path).keys.toList
      method <- operationKeys if Methods.contains(method)
      parameters <- cursor.downField(path).downField(method).downField("parameters").values.toList
      parameter <- parameters
      if parameter.hcursor.get[String]("in").toOption.contains("header")
      name <- parameter.hcursor.get[String]("name").toOption
    } yield name
    names.groupBy(identity).view.mapValues(_.size).toMap
  }

  test("noReservedHeaderSurvivesIntoTheBrowserDocument") {
    val reserved = headerParameters(browserDocument).keys.filter(EdgeHeaders.isForbidden).toList.sorted
    assertEquals(
      reserved,
      List.empty[String],
      "the browser's generated client would demand headers the gateway strips at the edge (ADR-040)"
    )
  }

  /** The regression this projection exists to prevent, stated the other way round: the *service* document
    * really does declare the header, so the browser document being clean is a projection having happened and
    * not the header having quietly disappeared from the contract altogether.
    */
  test("theServiceDocumentStillDeclaresTheReservedHeaders") {
    val reserved = headerParameters(serviceDocument).filter { case (name, _) =>
      EdgeHeaders.isForbidden(name)
    }
    assert(
      reserved.values.sum > 0,
      "docs/api/openapi.json declares no X-Kui-* header, so BrowserProjection is no longer removing anything"
    )
  }

  /** The headers a browser genuinely does send survive, on exactly the operations that require them.
    *
    * The counts are read from the service document rather than written down, so that adding a mutating
    * endpoint does not turn this test red for the wrong reason -- and so that dropping CSRF from one endpoint
    * turns it red for the right one.
    */
  test("theHeadersABrowserReallySendsSurviveUnchanged") {
    val service = headerParameters(serviceDocument).filterNot { case (name, _) =>
      EdgeHeaders.isForbidden(name)
    }
    assertEquals(headerParameters(browserDocument), service)
    assert(service.getOrElse("X-Csrf-Token", 0) > 0, "no operation requires CSRF; the session model changed")
  }

  /** Removing headers must not remove anything else. A projection that quietly dropped an operation would
    * produce a client missing a call, which compiles perfectly and fails in a browser.
    */
  test("theProjectionChangesNothingButHeaderParameters") {
    def skeleton(document: io.circe.Json): List[String] = {
      val cursor = document.hcursor.downField("paths")
      for {
        pathKeys <- cursor.keys.toList
        path <- pathKeys
        operationKeys <- cursor.downField(path).keys.toList
        method <- operationKeys if Methods.contains(method)
      } yield s"$method $path"
    }
    assertEquals(skeleton(browserDocument).sorted, skeleton(serviceDocument).sorted)
    assertEquals(
      browserDocument.hcursor.downField("components").downField("schemas").keys.map(_.toList.sorted),
      serviceDocument.hcursor.downField("components").downField("schemas").keys.map(_.toList.sorted)
    )
  }

  /** The rule is `EdgeHeaders`', not a copy of it: a name that is forbidden at runtime is a name the
    * projection drops, for every casing a header can arrive in.
    */
  test("theProjectionUsesTheRuntimeRuleForEveryCasing") {
    val casings = List("X-Kui-Principal", "x-kui-principal", "X-KUI-PRINCIPAL", "x-KuI-Something-New")
    casings.foreach(name => assert(EdgeHeaders.isForbidden(name), s"$name should be forbidden"))
    val declared = headerParameters(browserDocument).keys.map(_.toLowerCase(java.util.Locale.ROOT)).toList
    assert(declared.forall(!_.startsWith(EdgeHeaders.Prefix)), declared.mkString(", "))
  }

  // ---------------------------------------------------------------------------------------------------
  // The projection as a function, over the real contract and over a document written here.

  /** The whole product's merged document, in memory, exactly as `OpenApiDocument.render` builds it. */
  private val fresh: sttp.apispec.openapi.OpenAPI =
    DocsRoutes
      .document[IO](OpenApiDocument.documentedServices, List(OpenApiDocument.DefaultServer))
      .fold(problem => fail(problem), identity)

  private def headerNames(document: sttp.apispec.openapi.OpenAPI): List[String] =
    BrowserProjection.headerParameters(document).values.toList.flatten.distinct.sorted

  test("theProjectionRemovesEveryReservedHeaderFromTheRealMergedDocument") {
    // The function, over the endpoints the product actually publishes, both directions in one case: the
    // unprojected document really does declare the trust headers, and the projected one declares none of
    // them. A `keep` that stopped filtering fails here and nowhere else in this module.
    val before = headerNames(fresh).filter(EdgeHeaders.isForbidden)
    val after = headerNames(BrowserProjection.project(fresh)).filter(EdgeHeaders.isForbidden)

    assert(before.nonEmpty, "the merged document declares no X-Kui-* header, so nothing is being removed")
    assertEquals(after, List.empty[String], "the projection left a header the gateway strips at the edge")
  }

  test("theProjectionKeepsTheHeadersABrowserReallySendsInTheRealMergedDocument") {
    // The inverse failure, which is just as bad and much quieter: a projection that stripped `X-Csrf-Token`
    // would generate a client whose types do not oblige a call site to send it, and every mutation would
    // 403 in a browser with nothing in the generated code to explain why.
    val kept = headerNames(BrowserProjection.project(fresh))

    assertEquals(kept, headerNames(fresh).filterNot(EdgeHeaders.isForbidden))
    assert(kept.contains("X-Csrf-Token"), kept.mkString(", "))
  }

  test("theProjectionFiltersPathLevelAndComponentParametersAndNotOnlyOperationOnes") {
    // Three places a parameter can be declared and only one of them is the obvious one. A path-level
    // parameter applies to every operation under it, and a component is what a `$ref` points at, so a
    // projection that handled operations alone would publish the header twice over in a document that
    // looked clean at the call site.
    def header(name: String): Parameter =
      Parameter(name = name, in = ParameterIn.Header, required = Some(true), schema = None)

    val reserved = header("X-Kui-Principal")
    val sent = header("X-Csrf-Token")
    val reference: Either[Reference, Parameter] = Left(Reference("#/components/parameters/Whatever"))

    val item = PathItem(
      get = Some(Operation(operationId = Some("thing.get"), parameters = List(Right(reserved), Right(sent)))),
      parameters = List(Right(reserved), Right(sent), reference)
    )

    val document = fresh.copy(
      paths = Paths(ListMap("/api/v1/thing" -> item)),
      components = fresh.components.map(components =>
        components.copy(parameters = ListMap("Principal" -> Right(reserved), "Csrf" -> Right(sent)))
      )
    )

    val projected = BrowserProjection.project(document)
    val projectedItem = projected.paths.pathItems.getOrElse("/api/v1/thing", fail("the path was dropped"))

    assertEquals(headerNames(projected), List("X-Csrf-Token"))
    assertEquals(
      projectedItem.parameters.collect { case Right(parameter) => parameter.name },
      List("X-Csrf-Token")
    )
    // A `$ref` survives: the component it points at is filtered by the same pass, and dropping the
    // reference instead would leave a dangling pointer rather than a stripped header.
    assertEquals(projectedItem.parameters.count(_.isLeft), 1)
    assertEquals(
      projected.components.toList.flatMap(_.parameters.keys),
      List("Csrf")
    )
  }

  test("theProjectionChangesNoOperationOfTheRealMergedDocument") {
    // The function's half of the "nothing but headers" property. The committed-file case above compares two
    // files; this one compares a document with its own projection, so it fails for a projection that
    // dropped an operation even on a day nobody regenerated anything.
    val projected = BrowserProjection.project(fresh)

    assertEquals(projected.paths.pathItems.keys.toList, fresh.paths.pathItems.keys.toList)
    assertEquals(
      BrowserProjection.headerParameters(projected).keys.toList,
      BrowserProjection.headerParameters(fresh).keys.toList
    )
    assertEquals(
      projected.components.map(_.schemas.keys.toList),
      fresh.components.map(_.schemas.keys.toList)
    )
  }
}
