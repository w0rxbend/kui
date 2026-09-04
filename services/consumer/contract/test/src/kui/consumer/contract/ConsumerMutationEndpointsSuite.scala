package kui.consumer.contract

import io.circe.parser.decode
import munit.FunSuite
import sttp.tapir.AnyEndpoint

import kui.consumer.contract.dto.{ResetApplyRequest, ResetPlanRequest}
import kui.contracts.{HttpHeaders, KuiEndpoint}

/** That the destructive half of this contract is marked, guarded and shaped the way ADR-045 and ADR-047 say.
  *
  * Three of these assertions are hooks for milestones that have not been written yet. M5's read-only mode
  * enumerates every endpoint and asserts each is classified; M6's RBAC reads the same marker. A marker that
  * were only a naming convention would leave both of them guessing, and a mutation that shipped without one
  * would be invisible to the policy that is supposed to refuse it.
  */
final class ConsumerMutationEndpointsSuite extends FunSuite {

  private def pathOf(endpoint: AnyEndpoint): String =
    endpoint.showPathTemplate(showQueryParam = None)

  private def markerOf(endpoint: AnyEndpoint): Option[KuiEndpoint.MutationMarker] =
    endpoint.attribute(KuiEndpoint.MutationKey)

  test("everyMutatingEndpointCarriesTheMarker") {
    // The M5 hook. Exactly three endpoints change the cluster, and each names the operation it performs.
    assertEquals(ConsumerMutationEndpoints.mutating.size, 3)
    assertEquals(
      ConsumerMutationEndpoints.mutating.flatMap(markerOf).map(_.operation).toSet,
      Set(
        ConsumerMutationEndpoints.ResetOffsetsOperation,
        ConsumerMutationEndpoints.DeleteGroupOperation,
        ConsumerMutationEndpoints.DeleteOffsetsOperation
      )
    )
  }

  test("thePlanEndpointIsMarkedButIsNotDestructive") {
    // It carries the marker so that the read-only refusal has something to key on — a wizard that renders a
    // plan the operator may not apply teaches them the refusal at the end is a bug — and it is not in
    // `mutating` because it writes nothing.
    val marker = markerOf(ConsumerMutationEndpoints.planReset)
    assertEquals(marker.map(_.destructive), Some(false))
    assert(!ConsumerMutationEndpoints.mutating.contains(ConsumerMutationEndpoints.planReset))
    assert(ConsumerMutationEndpoints.all.contains(ConsumerMutationEndpoints.planReset))
  }

  test("theTwoResetEndpointsNameTheSameOperation") {
    // Plan and apply are two phases of one operation, and the audit record for the apply has to be findable
    // by the same name an operator saw on the plan.
    assertEquals(
      markerOf(ConsumerMutationEndpoints.planReset).map(_.operation),
      markerOf(ConsumerMutationEndpoints.applyReset).map(_.operation)
    )
  }

  test("everyMutatingEndpointRequiresTheCsrfHeader") {
    // Required from the day the endpoint exists, even though M4 has no session to forge. A header added
    // later has to be added to every client that already shipped, and the ones that were missed start
    // failing with a 403 that reads like a permissions problem.
    ConsumerMutationEndpoints.all.foreach { endpoint =>
      val inputs = endpoint.show
      assert(
        inputs.contains(HttpHeaders.Csrf),
        s"${pathOf(endpoint)} does not require ${HttpHeaders.Csrf}: $inputs"
      )
    }
  }

  test("noMutationIsAGet") {
    // What stops a link, a browser prefetch or a crawler from resetting a consumer group.
    ConsumerMutationEndpoints.all.foreach { endpoint =>
      assertNotEquals(endpoint.method.map(_.method), Some("GET"), s"${pathOf(endpoint)} is reachable by GET")
    }
  }

  test("pathsMatchTheDocumentedShapes") {
    assertEquals(
      pathOf(ConsumerMutationEndpoints.planReset),
      "/internal/v1/clusters/{clusterId}/consumer-groups/{groupId}/offsets/plan"
    )
    assertEquals(
      pathOf(ConsumerMutationEndpoints.applyReset),
      "/internal/v1/clusters/{clusterId}/consumer-groups/{groupId}/offsets"
    )
    assertEquals(
      pathOf(ConsumerMutationEndpoints.deleteGroup),
      "/internal/v1/clusters/{clusterId}/consumer-groups/{groupId}"
    )
    assertEquals(
      pathOf(ConsumerMutationEndpoints.deleteOffsets),
      "/internal/v1/clusters/{clusterId}/consumer-groups/{groupId}/offsets"
    )
  }

  test("theMethodIsWhatSeparatesApplyFromDeleteOffsets") {
    // The two share a path on purpose: they are the same resource, written and removed. What tells them
    // apart is the verb, so this assertion is the one that would catch a copy-paste that made both POSTs.
    assertEquals(ConsumerMutationEndpoints.applyReset.method.map(_.method), Some("POST"))
    assertEquals(ConsumerMutationEndpoints.deleteOffsets.method.map(_.method), Some("DELETE"))
    assertEquals(ConsumerMutationEndpoints.deleteGroup.method.map(_.method), Some("DELETE"))
    assertEquals(ConsumerMutationEndpoints.planReset.method.map(_.method), Some("POST"))
  }

  test("deleteOffsetsTakesTheTopicAsAQueryParameter") {
    val path = pathOf(ConsumerMutationEndpoints.deleteOffsets)
    assert(!path.contains("{topic}"), s"the topic is in the path: $path")
    assert(ConsumerMutationEndpoints.deleteOffsets.show.contains(ConsumerMutationEndpoints.TopicParam))
  }

  test("applyAcceptsOnlyATokenAndASmuggledSpecIsIgnored") {
    // There is no second path that takes a raw specification — not for tests, and not for the MCP server of
    // M8. A body carrying one decodes, and the specification is dropped on the floor.
    val smuggled =
      """{"token":"plan.v1.e30.4f6a9c","target":"EARLIEST","topic":"orders","offsets":{"0":0}}"""
    assertEquals(decode[ResetApplyRequest](smuggled), Right(ResetApplyRequest("plan.v1.e30.4f6a9c")))
  }

  test("aPlanRequestWithoutItsModeParameterDoesNotReachTheServer") {
    // The same rule as the contract suite's, asserted here because it is the mutation flow's first line of
    // defence: a malformed plan request never becomes a plan, so it never becomes a token.
    assert(decode[ResetPlanRequest]("""{"topic":"orders","target":"TIMESTAMP"}""").isLeft)
  }

  test("everyMutatingEndpointHasANameASummaryAndATag") {
    ConsumerMutationEndpoints.all.foreach { endpoint =>
      assert(endpoint.info.name.isDefined, s"${pathOf(endpoint)} has no name")
      assert(endpoint.info.summary.isDefined, s"${pathOf(endpoint)} has no summary")
      assertEquals(endpoint.info.tags.toList, List("consumer"))
    }
  }

  test("theGeneratedDescriptionSaysThatTheCallChangesSomething") {
    // So that an operator reading the API reference can see which calls change the cluster without having to
    // infer it from the HTTP verb.
    ConsumerMutationEndpoints.all.foreach { endpoint =>
      val description = endpoint.info.description.getOrElse("")
      assert(
        description.contains("Mutation (") || endpoint.info.summary.exists(_.nonEmpty),
        s"${pathOf(endpoint)} does not say what it does"
      )
    }
    assert(
      ConsumerMutationEndpoints.applyReset.info.description.exists(_.contains("Mutation (")),
      "the mutation marker's sentence did not reach the description"
    )
  }

  test("operationNamesAreUniquePerEndpoint") {
    val names = ConsumerMutationEndpoints.all.flatMap(_.info.name)
    assertEquals(names.distinct.size, names.size, s"two endpoints share a name: $names")
  }
}
