package kui.gateway.api

import scala.concurrent.duration.DurationInt

import io.circe.parser.decode
import io.circe.syntax.*

import kui.config.{CorsConfig, GatewayConfig, SafeUrl, ServerConfig, UpstreamServiceConfig}
import kui.gateway.contract.GatewayEndpoints
import kui.gateway.contract.dto.AppInfo
import kui.kernel.{Host, Port, PositiveInt, ServiceId}
import kui.testkit.KuiIOSuite

/** That `GET /api/v1/info` answers the support question, and leaks nothing while doing it.
  *
  * The document is built by a pure function, so most of this suite needs no server: what is under test is
  * which values end up in a public document, and that is a property of the value rather than of the
  * transport. The one thing that does need a server — that the endpoint answers without a session — is
  * asserted in `GatewayApiSuite`'s neighbourhood, on a real port, at the bottom of this file.
  */
final class InfoRoutesSuite extends KuiIOSuite {

  private def upstream(url: String): UpstreamServiceConfig =
    UpstreamServiceConfig(SafeUrl.unsafe(url), 10.seconds, PositiveInt.unsafe(8))

  private val configured: GatewayConfig = GatewayConfig.Default.copy(
    services = Map(
      ServiceId.unsafe("topic") -> upstream("http://kui-topic.internal:8080"),
      ServiceId.unsafe("cluster") -> upstream("http://kui-cluster.internal:8080")
    )
  )

  private def server(basePath: String): ServerConfig =
    ServerConfig(Host.unsafe("0.0.0.0"), Port.unsafe(8080), basePath)

  private def info(gateway: GatewayConfig = configured, basePath: String = "/"): AppInfo =
    InfoRoutes.appInfo(server(basePath), gateway, InfoRoutes.buildInfo)

  test("infoMatchesTheGoldenDocumentShape") {
    // The build fields are normalised away: a real build's commit and timestamp change on every commit, and
    // pinning them would make the golden file a nuisance rather than a check. What is pinned is that the
    // document has these keys, in this shape, with these types — which is what a client compiles against.
    val document = info().asJson.spaces2
    val normalised = decode[AppInfo](document).fold(error => fail(error.toString), identity)

    assertEquals(normalised.authType, "disabled")
    assertEquals(normalised.services, List("cluster", "topic"))
    assertEquals(normalised.features, Map("cors" -> false))
    assertEquals(normalised.basePath, "")
    assert(normalised.build.version.nonEmpty)
    assert(normalised.build.gitCommitShort.nonEmpty)
  }

  test("infoListsConfiguredServiceIdsOnly") {
    // Ids, sorted, and nothing they map to. Sorted so that two identical deployments produce byte-identical
    // documents, which is what makes a golden file mean anything.
    assertEquals(info().services, List("cluster", "topic"))
    assertEquals(info(GatewayConfig.Default).services, Nil)
  }

  test("infoContainsNoUrlOrHostname") {
    // The leak guard. `/api/v1/info` is unauthenticated, so everything in it is public — and the natural
    // mistake is to add an upstream URL because it looked useful while debugging. The configuration this is
    // built from has two real URLs in it, so a version that passed them through would fail here.
    val serialised = info().asJson.noSpaces

    assert(!serialised.contains("http"), serialised)
    assert(!serialised.contains("://"), serialised)
    assert(!serialised.contains("internal"), serialised)
    assert(!serialised.contains("8080"), serialised)
  }

  test("infoNeverNamesASigningKey") {
    // ADR-020's keys live in the same configuration section as the services, one field away from the map
    // this document is derived from. A `kid` is not a secret, but it is a fact about the deployment's
    // security posture that an anonymous caller has no reason to learn.
    val withKeys = configured.copy(
      principalKeys = List(
        kui.config.PrincipalKeyConfig(
          kid = "signing-key-2026-09",
          key = kui.kernel.Secret("s" * 32),
          notBefore = java.time.Instant.EPOCH
        )
      )
    )

    assert(!info(withKeys).asJson.noSpaces.contains("signing-key"), "a key id reached the public document")
  }

  test("featuresReportWhatTheDeploymentActuallyEnabled") {
    val withCors = configured.copy(cors = CorsConfig(enabled = true, origins = List("https://example.com")))

    assertEquals(info().features, Map("cors" -> false))
    assertEquals(info(withCors).features, Map("cors" -> true))
    // The origin list is configuration, not a feature flag, and it names other people's hostnames.
    assert(!info(withCors).asJson.noSpaces.contains("example.com"))
  }

  test("basePathIsReportedInItsNormalisedForm") {
    // `""`, `"/"`, `"/kui"` and `"/kui/"` are the four spellings an operator actually writes, and the first
    // two mean the same thing as do the last two. The shell builds every link from this string, so handing
    // it two spellings of one prefix is how a `//` appears in a URL. A table rather than a property,
    // because these six rows *are* the interesting inputs — a generator would mostly produce paths nobody
    // writes. GW-008 property-tests the same normalisation where it matters for serving files.
    val spellings =
      List("" -> "", "/" -> "", "/kui" -> "/kui", "/kui/" -> "/kui", "kui" -> "/kui", "//kui//" -> "/kui")

    spellings.foreach { (written, expected) =>
      assertEquals(info(basePath = written).basePath, expected, s"base path '$written'")
    }
  }

  test("infoIsServedWithoutAuthentication") {
    // It is the endpoint a health dashboard reads, and a dashboard has no session cookie. Asserted through a
    // real server with no credentials of any kind on the request.
    GatewayTestServer.resource().use { server =>
      server.get(s"${GatewayEndpoints.ApiPrefix}/info").map { response =>
        assertEquals(response.code.code, 200, response.body)
        val document = decode[AppInfo](response.body).fold(error => fail(s"${response.body} ($error)"), identity)
        assertEquals(document.authType, "disabled")
        assert(!response.body.contains("http"), response.body)
      }
    }
  }

  test("theBuildInfoTheEndpointReportsIsTheOneCompiledIn") {
    // Cross-checking the generated object against the value the route serves. If `build.mill` stopped
    // generating a field, or `InfoRoutes` stopped reading one, this is what fails rather than a UI footer
    // rendering a gap that nobody notices for a milestone.
    assertEquals(InfoRoutes.buildInfo.version, GatewayBuildInfo.version)
    assertEquals(InfoRoutes.buildInfo.gitCommit, GatewayBuildInfo.gitCommit)
    assertEquals(InfoRoutes.buildInfo.gitDirty, GatewayBuildInfo.gitDirty)
    assertEquals(InfoRoutes.buildInfo.scalaVersion, "3.9.0")
    assert(InfoRoutes.buildInfo.jdkVersion.nonEmpty)
  }
}
