package kui.topic.contract

import sttp.model.Method
import sttp.tapir.AnyEndpoint

import kui.contracts.{HttpHeaders, KuiEndpoint}

/** The properties of the administration contract that a reviewer would otherwise have to take on trust.
  *
  * Every one of them is a rule ADR-045 or ADR-047 states, turned into something that fails the build. A rule
  * only a document states is a rule the next endpoint quietly breaks — which is the failure mode this project
  * has recorded four times.
  */
final class TopicAdminEndpointsSuite extends munit.FunSuite {

  private def pathTemplate(endpoint: AnyEndpoint): String = endpoint.showPathTemplate()

  test("everyEndpointCarriesTheMutationMarker") {
    // ADR-047: the classification M5's read-only policy and M6's RBAC both key on. An endpoint that
    // changes something and forgets the marker is invisible to both.
    TopicAdminEndpoints.all.foreach { endpoint =>
      assert(
        KuiEndpoint.isMutation(endpoint),
        s"${endpoint.info.name.getOrElse(pathTemplate(endpoint))} carries no mutation marker"
      )
    }
  }

  test("everyEndpointRequiresTheCsrfHeader") {
    // Required from the day the endpoint exists rather than from the day there is a session to bind it to:
    // a header added later has to be added to every client that already shipped.
    TopicAdminEndpoints.all.foreach { endpoint =>
      assert(
        endpoint.show.contains(HttpHeaders.Csrf),
        s"${endpoint.info.name.getOrElse(pathTemplate(endpoint))}: ${endpoint.show}"
      )
    }
  }

  test("onlyTheTwoIrreversibleOperationsAreMarkedDestructive") {
    assertEquals(
      TopicAdminEndpoints.destructive.flatMap(_.info.name).sorted,
      List("topic.delete", "topic.partitions.increase")
    )
  }

  test("theOperationNamesAreTheSameFourStringsEverywhere") {
    // The audit record, the marker and the application layer's enum all use these. The api module's suite
    // holds them equal to the application enum; this one holds the contract's own two copies together.
    assertEquals(
      TopicAdminEndpoints.all.flatMap(_.attribute(KuiEndpoint.MutationKey)).map(_.operation).distinct.sorted,
      List(
        TopicAdminEndpoints.AlterConfigOperation,
        TopicAdminEndpoints.CreateOperation,
        TopicAdminEndpoints.DeleteOperation,
        TopicAdminEndpoints.IncreasePartitionsOperation
      ).sorted
    )
  }

  test("noDestructiveEndpointAcceptsAnythingButAToken") {
    // ADR-045's central guarantee. The *type* of the apply endpoint's body is `ConfirmRequest` and the
    // compiler holds that; what a document cannot promise is that the shape refuses everything else, so
    // this drives the codec: a body naming a partition count and no token does not decode, which is what
    // stops a caller destroying something it was never shown a plan for.
    import io.circe.parser.decode

    assert(decode[dto.ConfirmRequest]("""{"partitions":12}""").isLeft)
    assert(decode[dto.ConfirmRequest]("""{"token":"abc"}""").isRight)

    // The delete carries its token in the query string, and nothing else beyond the path.
    assert(
      TopicAdminEndpoints.deleteTopic.show.contains(TopicAdminEndpoints.TokenParam),
      TopicAdminEndpoints.deleteTopic.show
    )

    List(TopicAdminEndpoints.increasePartitions, TopicAdminEndpoints.deleteTopic).foreach { endpoint =>
      assert(
        endpoint.info.description.exists(_.contains("Takes only a plan token")),
        endpoint.info.description.toString
      )
    }
  }

  test("theTwoPlanEndpointsChangeNothingAndSaySo") {
    List(TopicAdminEndpoints.planPartitions, TopicAdminEndpoints.planDeletion).foreach { endpoint =>
      assert(
        endpoint.info.description.exists(_.contains("This call changes nothing")),
        endpoint.info.description.toString
      )
      assert(!TopicAdminEndpoints.destructive.contains(endpoint))
    }
  }

  test("theMethodsAreTheOnesTheOperationsMean") {
    assertEquals(
      TopicAdminEndpoints.all.map(endpoint => endpoint.info.name.getOrElse("") -> endpoint.method),
      List(
        "topic.create" -> Some(Method.POST),
        // PATCH and not PUT: the body is a change, and a PUT would promise it is the whole configuration.
        "topic.config.update" -> Some(Method.PATCH),
        "topic.partitions.plan" -> Some(Method.POST),
        "topic.partitions.increase" -> Some(Method.POST),
        "topic.deletion.plan" -> Some(Method.POST),
        "topic.delete" -> Some(Method.DELETE)
      )
    )
  }

  test("everyPathIsUnderTheInternalPrefix") {
    // `/api/v1` belongs to the gateway. A service path that started there would be served twice under two
    // different security models.
    TopicAdminEndpoints.all.foreach { endpoint =>
      assert(pathTemplate(endpoint).startsWith("/internal/v1/"), pathTemplate(endpoint))
    }
  }

  test("aPlanPathAndItsApplyPathAreNotTheSameResource") {
    // If they were, a client that got the method wrong would apply what it meant to preview.
    assertNotEquals(
      pathTemplate(TopicAdminEndpoints.planPartitions),
      pathTemplate(TopicAdminEndpoints.increasePartitions)
    )
    assertNotEquals(
      pathTemplate(TopicAdminEndpoints.planDeletion),
      pathTemplate(TopicAdminEndpoints.deleteTopic)
    )
  }
}
