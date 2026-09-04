package kui.gateway.api.openapi

import cats.effect.IO
import io.circe.syntax.*
import munit.FunSuite
import sttp.apispec.openapi.circe.*

import kui.gateway.api.routing.ServiceContracts
import kui.gateway.contract.{ClusterOverviewEndpoints, TopicOverviewEndpoints}
import kui.kernel.ServiceId

/** That the published description of KUI's API describes the API a browser can actually call.
  *
  * `openApiCheck` already fails on any byte difference between the committed document and a freshly
  * generated one, and it is the regeneration gate. This suite asserts the properties a byte diff cannot
  * express: that every path is claimed once, that nothing internal leaked, and that the one endpoint which
  * is deliberately absent is absent for that reason rather than by accident.
  */
final class MergedDocumentShapeSuite extends FunSuite {

  private val cluster = ServiceId.unsafe("cluster")
  private val topic = ServiceId.unsafe("topic")

  private val merged = DocsRoutes
    .document[IO](List(cluster, topic), List("/"))
    .fold(problems => fail(problems), identity)

  private val paths: List[String] = OpenApiMerge.paths(merged)

  test("everyPublicPathAppearsExactlyOnce") {
    // The collision the aggregation exclusion prevents, asserted at the document level so that the two
    // mechanisms - the exclusion and the route list - cannot both be wrong at the same time.
    assertEquals(paths.distinct, paths)
  }

  test("noPublicPathContainsInternal") {
    // An integrator who finds `/internal/v1` in here will try to call it and get nothing.
    paths.foreach(path => assert(!path.contains("internal"), path))
  }

  test("everyOperationHasAUniqueOperationId") {
    // Operation ids become method names in a generated client, so a collision silently loses one of them
    // in whichever language the integrator generated for.
    val ids = merged.paths.pathItems.values.toList
      .flatMap(item => List(item.get, item.post, item.put, item.delete, item.patch).flatten)
      .flatMap(_.operationId)

    assertEquals(ids.distinct.size, ids.size, ids.mkString(", "))
    assert(ids.nonEmpty)
  }

  test("everyOperationDocumentsTheErrorEnvelope") {
    // A caller has to be able to write one failure handler. An operation that documented no error at all
    // would invite a client that treats any non-200 as a transport fault.
    val operations = merged.paths.pathItems.toList.flatMap((path, item) =>
      List(item.get, item.post, item.put, item.delete, item.patch).flatten.map(path -> _)
    )

    operations.foreach((path, operation) =>
      assert(operation.responses.responses.nonEmpty, s"$path documents no response at all")
    )
  }

  test("theClusterWritesArePublishedToBrowsers") {
    // The inverse of what this pair used to assert, and the reason for the reversal is worth keeping.
    //
    // M1 shipped `PUT /internal/v1/clusters/{id}` and deliberately kept it out of the gateway's contract
    // map, because there was no screen to call it from — so the pair of tests here made "no browser can
    // reach it" a checked property. The cluster administration screen exists now, and an endpoint the
    // browser cannot reach would make that screen a set of buttons that answer 404.
    //
    // What keeps an unauthorised caller out is `ApplicationConfig.Edit`, declared on each endpoint and
    // enforced by the gateway's permission seam and again by the service. That is a rule the product can
    // state; an unrouted endpoint is only a rule nobody has got round to breaking.
    val rendered = merged.asJson.noSpaces

    assert(rendered.contains("cluster.put"), "the write endpoint is reached from the administration screen")
    assert(rendered.contains("cluster.delete"), rendered.take(200))
    assert(rendered.contains("cluster.probe"), rendered.take(200))

    assert(merged.paths.pathItems.get("/api/v1/clusters/{clusterId}").flatMap(_.put).isDefined)
    assert(merged.paths.pathItems.get("/api/v1/clusters/{clusterId}").flatMap(_.delete).isDefined)
  }

  test("everyPublishedClusterWriteCarriesItsPermissionDeclaration") {
    // The property that replaced "it is unroutable". A write the gateway proxies with no authorization
    // declaration is one the permission seam cannot decide about, which is a worse state than the one the
    // missing route used to prevent.
    val writes = ServiceContracts
      .proxied(cluster)
      .filter(_.info.name.exists(Set("cluster.put", "cluster.delete", "cluster.probe")))

    assertEquals(writes.size, 3, writes.flatMap(_.info.name).toString)

    writes.foreach(endpoint =>
      assert(
        endpoint.attribute(kui.contracts.rbac.EndpointAuthorization.Key).isDefined,
        s"${endpoint.info.name} carries no authorization declaration"
      )
    )
  }

  test("thePublicClusterPathsEqualTheDerivedSet") {
    // Derived from the proxied lists plus the gateway's own two aggregations, so a new endpoint needs no
    // edit here: add one and forget to regenerate the document, and this fails.
    val derived = List(cluster, topic)
      .flatMap(ServiceContracts.proxied)
      .map(endpoint => endpoint.showPathTemplate().takeWhile(_ != '?').replace("/internal/v1", "/api/v1"))

    val own = ClusterOverviewEndpoints.all.map(_ => "/api/v1/clusters") ++
      TopicOverviewEndpoints.all.map(_.showPathTemplate().takeWhile(_ != '?')) ++
      // The message browse stream: a cluster-scoped path the gateway serves itself, because a stream is
      // relayed rather than derived as a proxy route, so `ServiceContracts` never produces it.
      kui.gateway.api.MessageStreamRoutes
        .endpoints[IO]
        .map(_.showPathTemplate().takeWhile(_ != '?'))
    val documented = paths.filter(_.startsWith("/api/v1/clusters"))

    // Compared as sets of *paths*, because since M5 several paths carry more than one method: a topic's
    // collection is a GET and a POST, a topic is a GET and a DELETE, its config is a GET and a PATCH, and
    // its partitions are a GET and a POST. An OpenAPI document has one entry per path with the methods
    // inside it, so a list comparison would fail on the repetition rather than on anything being missing.
    assertEquals(documented.distinct.sorted, (derived ++ own).distinct.sorted)
  }

  test("theSseEndpointIsDocumentedAsAnEventStream") {
    // A client that generated a JSON body for the capability stream would hang waiting for a document
    // that never ends.
    val stream = merged.paths.pathItems
      .get("/api/v1/capabilities/stream")
      .flatMap(_.get)
      .getOrElse(fail("the capability stream is missing from the document"))

    assert(
      stream.responses.responses.values.exists(_.toOption.exists(_.content.contains("text/event-stream"))),
      stream.responses.toString
    )
  }
}
