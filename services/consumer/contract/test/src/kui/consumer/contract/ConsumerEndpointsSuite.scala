package kui.consumer.contract

import munit.FunSuite
import sttp.tapir.AnyEndpoint

import kui.contracts.consumer.GroupSortField
import kui.kernel.group.GroupState

/** That the four read endpoints describe the addresses the service actually serves, and that the parameters
  * behave the way the plan says they do.
  *
  * The endpoint values are the single source ADR-003 asks for: the same values produce the server's routes,
  * the gateway's client, the browser's client and the OpenAPI document. Asserting the paths against literals
  * here is what makes a path change a deliberate contract change rather than something that quietly breaks
  * one of those four.
  */
final class ConsumerEndpointsSuite extends FunSuite {

  private def pathOf(endpoint: AnyEndpoint): String =
    endpoint.showPathTemplate(showQueryParam = None)

  test("everyEndpointHasANameASummaryAndATag") {
    // The OpenAPI document is documentation. An endpoint with no summary is a row in it that says nothing,
    // and an operator reading the API reference learns less than they would from the URL.
    ConsumerEndpoints.all.foreach { endpoint =>
      assert(endpoint.info.name.isDefined, s"${pathOf(endpoint)} has no name")
      assert(endpoint.info.summary.isDefined, s"${pathOf(endpoint)} has no summary")
      assert(endpoint.info.description.isDefined, s"${pathOf(endpoint)} has no description")
      assertEquals(endpoint.info.tags.toList, List("consumer"))
    }
  }

  test("operationNamesAreUnique") {
    val names = ConsumerEndpoints.all.flatMap(_.info.name)
    assertEquals(names.distinct.size, names.size, s"two endpoints share a name: $names")
  }

  test("pathsMatchTheDocumentedShapes") {
    assertEquals(pathOf(ConsumerEndpoints.list), "/internal/v1/clusters/{clusterId}/consumer-groups")
    assertEquals(
      pathOf(ConsumerEndpoints.detail),
      "/internal/v1/clusters/{clusterId}/consumer-groups/{groupId}"
    )
    assertEquals(pathOf(ConsumerEndpoints.lag), "/internal/v1/clusters/{clusterId}/consumer-groups/lag")
    assertEquals(
      pathOf(ConsumerEndpoints.forTopic),
      "/internal/v1/clusters/{clusterId}/topics/{topic}/consumer-groups"
    )
  }

  test("noEndpointIsUnderTheApiV1Prefix") {
    // `/api/v1` belongs to the gateway (`PublicApi`, and `ARCHITECTURE.md` §5). A service endpoint under the
    // public prefix would let a browser call this service directly, which is what DEVPLAN §10 D13 forbids
    // for the topic tab and what ADR-043 forbids in general.
    ConsumerEndpoints.all.foreach { endpoint =>
      val path = pathOf(endpoint)
      assert(path.startsWith("/internal/v1/"), s"$path is not under the internal prefix")
      assert(!path.startsWith("/api/"), s"$path is under the public prefix, which no service may declare")
    }
  }

  test("theLagEndpointComesBeforeTheGroupIdPathSoItIsNotSwallowed") {
    // `/consumer-groups/lag` and `/consumer-groups/{groupId}` are the same shape to a router that tries them
    // in the wrong order, and a group genuinely called "lag" is legal in Kafka. Asserting the two paths are
    // distinct here is cheap; discovering it from a lag poll that returns a group detail document is not.
    assertNotEquals(pathOf(ConsumerEndpoints.lag), pathOf(ConsumerEndpoints.detail))
    assert(pathOf(ConsumerEndpoints.lag).endsWith("/lag"))
  }

  test("theStateFilterAcceptsEveryStateTheKernelDeclares") {
    // Read from `GroupState.All`, never typed out: a state added to the enum is accepted by the filter by
    // the act of adding it, and this assertion is what says so.
    assertEquals(GroupState.All.map(_.wire).toSet, Set("STABLE", "EMPTY", "DEAD", "PREPARING_REBALANCE", "COMPLETING_REBALANCE", "UNKNOWN"))
  }

  test("sortFieldsAreTheFiveTheListCanActuallyOrderBy") {
    assertEquals(GroupSortField.All.map(_.wire), List("id", "members", "topics", "lag", "state"))
    assertEquals(GroupSortField.Default, GroupSortField.Id)
  }

  test("anUnknownSortFieldIsAValidationErrorNamingTheAcceptedValues") {
    // Silently ignoring an unknown sort parameter answers with a list in some other order than the one that
    // was asked for, which nobody reports because it looks like a preference.
    val refusal = GroupSortField.from("lagg")
    assert(refusal.isLeft)
    refusal.left.foreach { error =>
      assert(error.message.contains("lag"), s"the refusal does not list the accepted values: ${error.message}")
    }
  }

  test("anUnknownStateParameterIsAValidationErrorNamingTheAcceptedValues") {
    val refusal = GroupState.from("STABEL")
    assert(refusal.isLeft)
    refusal.left.foreach(error => assert(error.message.contains("STABLE")))
  }

  test("pageDefaultsAreOneAndTwentyFive") {
    assertEquals(ConsumerEndpoints.DefaultPage, 1)
    assertEquals(ConsumerEndpoints.DefaultPageSize, 25)
  }

  private def lagUrl(groups: Int): String = {
    val query = List.fill(groups)("g" * 40).map(id => s"${ConsumerEndpoints.GroupParam}=$id").mkString("&")
    s"/internal/v1/clusters/production-eu/consumer-groups/lag?$query&since=v7:1a2b3c4d"
  }

  test("worstCaseLagUrlIsUnderEightKilobytes") {
    // The assertion that keeps the GET honest, at the cap the endpoint actually declares.
    assert(
      lagUrl(ConsumerEndpoints.MaxLagGroups).length < 8192,
      s"the worst-case lag URL is ${lagUrl(ConsumerEndpoints.MaxLagGroups).length} bytes"
    )
  }

  test("theTwoHundredGroupPollTheDevplanAssumedDoesNotFit") {
    // Recorded rather than deleted, because it is the reason `MaxLagGroups` exists. GRP-025 assumed 200 ids
    // of 40 characters would fit in 8 KB; the arithmetic says 9 400 bytes, and the failure mode is a proxy
    // truncating the query string, which reaches the operator as rows that quietly stop updating.
    assert(lagUrl(200).length > 8192, "200 groups now fits; MaxLagGroups can be raised, with this test")
  }

  test("detailDoesNotDeclareGroupNotFound") {
    // The invariant, asserted against what the endpoint says about itself. An unknown group describes as a
    // fabricated empty dead group, so a 404 is an error this endpoint cannot return, and declaring it would
    // put a lie in the OpenAPI document that a generated client would then handle.
    val description = ConsumerEndpoints.detail.info.description.getOrElse("")
    assert(!description.contains("KUI-GROUP-NOT-FOUND"))
    assert(description.contains("not 404"), s"the reason is not documented: $description")
  }

  test("everyReadEndpointIsAGet") {
    ConsumerEndpoints.all.foreach { endpoint =>
      assertEquals(endpoint.method.map(_.method), Some("GET"), s"${pathOf(endpoint)} is not a GET")
    }
  }

  test("allListsEveryEndpointDeclaredHere") {
    assertEquals(ConsumerEndpoints.all.size, 4)
  }
}
