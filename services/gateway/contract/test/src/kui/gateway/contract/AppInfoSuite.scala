package kui.gateway.contract

import java.time.Instant

import io.circe.parser.decode
import io.circe.syntax.*
import munit.FunSuite
import sttp.tapir.AnyEndpoint

import kui.gateway.contract.dto.{AppInfo, BuildInfoDto}

/** The gateway's own published wire shape.
  *
  * Two kinds of assertion, and the second kind is the one that keeps working after everyone has forgotten
  * this file exists. The first pins `AppInfo`'s JSON against a committed document, so that changing the
  * format means changing a file a reviewer can read. The second walks `GatewayEndpoints.all` and asserts a
  * property of *every* endpoint — an operation id, a summary, the `/api/v1` prefix — so an endpoint added in
  * eight months is checked without anyone remembering to check it.
  */
final class AppInfoSuite extends FunSuite {

  /** Placeholder build facts a real build never produces, so that the golden file pins the document's shape
    * rather than going stale on every commit.
    */
  private val placeholderBuild: BuildInfoDto = BuildInfoDto(
    version = "0.1.0-SNAPSHOT",
    gitCommit = "0" * 40,
    gitCommitShort = "0" * 7,
    gitDirty = false,
    builtAt = Instant.EPOCH,
    scalaVersion = "3.9.0",
    jdkVersion = "21"
  )

  private val sample: AppInfo = AppInfo(
    build = placeholderBuild,
    authType = AppInfo.AuthDisabled,
    basePath = "",
    services = List("cluster"),
    features = Map("cors" -> false)
  )

  test("appInfoMatchesTheGoldenDocument") {
    assertNoDiff(sample.asJson.spaces2, GoldenDocuments.appInfo)
  }

  test("theGoldenDocumentDecodesBackToTheSameValue") {
    // Encoding and decoding are separate functions, and a codec that can write a document it cannot read is
    // a codec that breaks the browser rather than the gateway — which is the harder failure to find.
    assertEquals(decode[AppInfo](GoldenDocuments.appInfo), Right(sample))
  }

  test("everyEndpointIsUnderTheApiV1Prefix") {
    // The public prefix belongs to the gateway (`ARCHITECTURE.md` §5). An endpoint that escaped it would be
    // served at a path no client version-pins, and no reverse proxy expects.
    GatewayEndpoints.all.foreach { endpoint =>
      assert(
        endpoint.showPathTemplate().startsWith(GatewayEndpoints.ApiPrefix),
        s"${describe(endpoint)} is not under ${GatewayEndpoints.ApiPrefix}"
      )
    }
  }

  test("everyEndpointHasAnOperationIdAndASummary") {
    // The operation id is the span name (`KuiInterceptors.spanName`) and the OpenAPI operation id. An
    // endpoint without one traces as a URL template, which is unambiguous and reads like nothing a person
    // chose. The summary is what the generated documentation shows.
    GatewayEndpoints.all.foreach { endpoint =>
      assert(endpoint.info.name.isDefined, s"${describe(endpoint)} has no operation id")
      assert(endpoint.info.summary.isDefined, s"${describe(endpoint)} has no summary")
      assert(
        endpoint.info.name.exists(_.startsWith("gateway.")),
        s"${describe(endpoint)} is not named `gateway.<operation>`"
      )
    }
  }

  test("theInfoDocumentNamesServiceIdsAndNeverAddresses") {
    // The leak guard, stated at the level of the contract rather than of the route: this endpoint is
    // unauthenticated, so anything in the serialised document is public. `InfoRoutesSuite` asserts the same
    // rule on the value the running gateway actually builds.
    val serialised = sample.asJson.noSpaces

    assert(!serialised.contains("http"), serialised)
    assert(!serialised.contains("://"), serialised)
    assertEquals(sample.services, List("cluster"))
  }

  private def describe(endpoint: AnyEndpoint): String =
    s"${endpoint.method.map(_.method).getOrElse("*")} ${endpoint.showPathTemplate()}"
}
