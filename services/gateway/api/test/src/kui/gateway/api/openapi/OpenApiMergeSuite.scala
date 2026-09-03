package kui.gateway.api.openapi

import cats.effect.IO
import io.circe.syntax.*
import munit.FunSuite
import sttp.apispec.openapi.circe.*
import sttp.tapir.*

import kui.cluster.contract.ClusterEndpoints
import kui.contracts.KuiEndpoint
import kui.kernel.ServiceId

/** That one URL documents the whole product, and that the document says the same thing twice running.
  *
  * The determinism assertion is not pedantry. The document is committed, and a build that reorders a map
  * makes it churn on every commit; once a generated file churns, nobody reads its diff, and the staleness
  * check that is supposed to protect integrators becomes noise everyone skips.
  */
final class OpenApiMergeSuite extends FunSuite {

  private val gateway = ServiceId.unsafe("gateway")
  private val cluster = ServiceId.unsafe("cluster")
  private val topic = ServiceId.unsafe("topic")

  private def merged(docs: List[ServiceDoc], servers: List[String] = List("/")) =
    OpenApiMerge
      .merge("KUI", "1.0", servers, docs)
      .fold(problems => fail(problems.toList.mkString("; ")), identity)

  private val gatewayDoc = ServiceDoc(gateway, DocsRoutes.gatewayEndpoints[IO])
  private val clusterDoc = ServiceDoc(cluster, ClusterEndpoints.all)

  test("mergesGatewayAndServiceEndpoints") {
    assertEquals(
      OpenApiMerge.paths(merged(List(gatewayDoc, clusterDoc))),
      List(
        "/api/v1/auth/logout",
        "/api/v1/auth/me",
        "/api/v1/capabilities",
        "/api/v1/capabilities/stream",
        "/api/v1/capabilities/{service}/probe",
        "/api/v1/clusters",
        "/api/v1/clusters/{clusterId}",
        "/api/v1/clusters/{clusterId}/brokers",
        "/api/v1/clusters/{clusterId}/brokers/{brokerId}/configs",
        "/api/v1/clusters/{clusterId}/log-dirs",
        "/api/v1/clusters/{clusterId}/refresh",
        "/api/v1/info"
      )
    )
  }

  test("usesPublicApiV1Paths") {
    // Not just the path list: nowhere in the whole document. An integrator who finds `/internal/v1`
    // anywhere in here will try to call it, and will get nothing, because that path is not exposed.
    val rendered = merged(List(gatewayDoc, clusterDoc)).asJson.noSpaces
    assert(!rendered.contains("internal/v1"), "the internal prefix leaked into the published document")
  }

  test("isDeterministic") {
    val first = DocsRoutes.render(merged(List(clusterDoc, gatewayDoc)))
    val second = DocsRoutes.render(merged(List(gatewayDoc, clusterDoc)))
    // The inputs are given in different orders on purpose: the merge sorts by service id, so the
    // committed file cannot depend on the order the composition root happened to build its list in.
    assertNoDiff(first, second)
  }

  test("duplicateOperationIdsFailTheMerge") {
    // Operation ids become method names in a generated client, so a collision silently loses one of them
    // in whichever language the integrator used. It has to stop the build.
    val clash: AnyEndpoint =
      KuiEndpoint.internal.get.in("internal" / "v1" / "clash").name("cluster.list").summary("s")

    OpenApiMerge.merge("KUI", "1.0", List("/"), List(clusterDoc, ServiceDoc(topic, List(clash)))) match {
      case Left(problems) => assert(problems.toList.exists(_.contains("cluster.list")), problems.toString)
      case Right(_) => fail("two endpoints claiming one operation id must not merge")
    }
  }

  test("everyServicesEndpointsAreTaggedWithThatService") {
    // The tag is what groups the UI by service, which is the grouping an integrator finds useful.
    val document = merged(List(gatewayDoc, clusterDoc))
    val clusters =
      document.paths.pathItems("/api/v1/clusters").get.getOrElse(fail("no cluster list operation"))
    assertEquals(clusters.tags, List("cluster"))
  }

  test("serversEntryIncludesTheBasePath") {
    assertEquals(merged(List(gatewayDoc), List("/kui")).servers.map(_.url), List("/kui"))
  }

  test("aContractNotUnderInternalV1FailsTheMerge") {
    val wrong: AnyEndpoint = KuiEndpoint.internal.get.in("public" / "v1" / "x").name("topic.x").summary("s")

    OpenApiMerge.merge("KUI", "1.0", List("/"), List(ServiceDoc(topic, List(wrong)))) match {
      case Left(problems) => assert(problems.toList.exists(_.contains("topic.x")), problems.toString)
      case Right(_) => fail("an endpoint outside /internal/v1 cannot be given a public path")
    }
  }

  test("theRenderedDocumentIsStableAcrossRuns") {
    // The committed file itself is checked by `./mill services.gateway.api.openApiCheck`, which runs in
    // CI and can see the workspace; a test cannot, because Mill sandboxes each suite's working directory.
    // What is asserted here is the property that check depends on: rendering twice produces one document.
    assertNoDiff(OpenApiDocument.render, OpenApiDocument.render)
  }
}
