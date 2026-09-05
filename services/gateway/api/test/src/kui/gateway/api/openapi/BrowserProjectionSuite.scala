package kui.gateway.api.openapi

import java.nio.file.{Files, Path}

import io.circe.parser
import munit.FunSuite

import kui.gateway.api.EdgeHeaders

/** That the document the browser's TypeScript client is generated from cannot ask a browser for a header the
  * gateway would throw away.
  *
  * This is the security half of the contract seam (ADR-048 §3). The other half -- that a contract change
  * fails the build -- lives in the frontend's `contract-drift` test, because only `tsc` can assert it.
  *
  * The assertions are made against the **committed** file, not against a freshly projected one, for the same
  * reason `openApiCheck` exists: the committed file is what `openapi-typescript` reads, and a suite that
  * re-derives its own input proves only that the derivation is self-consistent.
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
    val reserved = headerParameters(serviceDocument).filter { case (name, _) => EdgeHeaders.isForbidden(name) }
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
    val service = headerParameters(serviceDocument).filterNot { case (name, _) => EdgeHeaders.isForbidden(name) }
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
}
