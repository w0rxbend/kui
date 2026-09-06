package kui.gateway.api.search

import cats.effect.IO
import cats.effect.kernel.Ref
import io.circe.parser.parse
import munit.CatsEffectSuite

import kui.contracts.ErrorEnvelope
import kui.gateway.api.search.SearchRig.{Behaviour, Call, Data}
import kui.gateway.api.{GatewayTestServer, SearchRoutes}
import kui.gateway.application.search.{SearchSource, SearchUseCase}
import kui.gateway.contract.dto.SearchAnswerDto
import kui.kernel.{ClusterId, ServiceId}

/** Cross-entity search, asserted where a browser meets it: over a real server, at `/api/v1/search`.
  *
  * Every case here goes through the route, the contract's own query decoding and the error interceptor,
  * because that is where each of these rules is actually applied. A suite that called `SearchUseCase.search`
  * directly could not see the 400 at all — the query bounds are declared on the endpoint and enforced by
  * Tapir before any handler runs — and would be asserting an arrangement of its own rather than the product.
  */
final class SearchSuite extends CatsEffectSuite {

  private val allThree: Set[ServiceId] =
    Set(SearchRig.TopicService, SearchRig.ConsumerService, SearchRig.SchemaService)

  /** A gateway serving the search route over stubbed services, plus the record of what it asked them. */
  private def serve(
      clusters: List[ClusterId] = List(SearchRig.Cluster),
      topics: List[String] = Nil,
      groups: List[String] = Nil,
      subjects: List[String] = Nil,
      routed: Set[ServiceId] = allThree,
      down: Set[ServiceId] = Set.empty,
      clusterListDown: Boolean = false
  )(check: (GatewayTestServer.Running, IO[List[Call]]) => IO[Unit]): IO[Unit] =
    Ref.of[IO, List[Call]](Nil).flatMap { calls =>
      val data = Data(clusters, topics, groups, subjects)

      def behaviour(service: ServiceId): Behaviour =
        if down.contains(service) then Behaviour.Down else Behaviour.Answers

      def stub(service: ServiceId) = SearchRig.client(service, data, calls, behaviour(service))

      val sources: List[SearchSource[IO]] = List(
        Option.when(routed.contains(SearchRig.TopicService))(
          TopicSearchSource[IO](stub(SearchRig.TopicService))
        ),
        Option.when(routed.contains(SearchRig.ConsumerService))(
          GroupSearchSource[IO](stub(SearchRig.ConsumerService))
        ),
        Option.when(routed.contains(SearchRig.SchemaService))(
          SubjectSearchSource[IO](stub(SearchRig.SchemaService))
        )
      ).flatten

      val clusterClient = SearchRig.client(
        SearchRig.ClusterService,
        data,
        calls,
        if clusterListDown then Behaviour.Down else Behaviour.Answers
      )

      GatewayTestServer
        .resource(extraRoutes = SearchRoutes[IO](SearchUseCase.of[IO](clusterClient, sources)))
        .use(server => check(server, calls.get))
    }

  private def answerOf(body: String): SearchAnswerDto =
    parse(body)
      .flatMap(_.as[SearchAnswerDto])
      .fold(failure => fail(s"the search answer must decode: $failure\n$body"), identity)

  private def envelopeOf(body: String): ErrorEnvelope =
    parse(body)
      .flatMap(_.as[ErrorEnvelope])
      .fold(failure => fail(s"the refusal must be an error envelope: $failure\n$body"), identity)

  test("aQueryMatchingATopicAGroupAndASubjectReturnsAllThree") {
    serve(
      topics = List("orders.v1", "payments"),
      groups = List("orders-consumer", "billing"),
      subjects = List("orders.v1-value", "billing-value")
    ) { (server, _) =>
      server.get("/api/v1/search?q=orders").map { response =>
        assertEquals(response.code.code, 200, response.body)
        val answer = answerOf(response.body)

        assertEquals(answer.results.topics.map(_.name.value), List("orders.v1"))
        assertEquals(answer.results.groups.map(_.groupId.value), List("orders-consumer"))
        assertEquals(answer.results.subjects.map(_.subject.value), List("orders.v1-value"))
        assertEquals(answer.results.topics.map(_.cluster), List(SearchRig.Cluster))
        assertEquals(answer.partial, Nil)
      }
    }
  }

  test("aTopicThatDoesNotMatchIsLeftOut") {
    // The topic half is the one the gateway matches itself: `topics/names` has no `q`, so the stub answers
    // its whole index and this case fails the moment the fold stops filtering.
    serve(topics = List("orders.v1", "payments")) { (server, _) =>
      server.get("/api/v1/search?q=pay").map { response =>
        assertEquals(answerOf(response.body).results.topics.map(_.name.value), List("payments"))
      }
    }
  }

  test("theMatchIsCaseInsensitive") {
    serve(topics = List("Orders.V1")) { (server, _) =>
      server.get("/api/v1/search?q=orders").map { response =>
        assertEquals(answerOf(response.body).results.topics.map(_.name.value), List("Orders.V1"))
      }
    }
  }

  test("aServiceThatAnswersAnErrorIsNamedInPartialAndTheOtherTwoStillReturn") {
    // The rule this endpoint exists for. A service that could not be asked is *reported*, not silently
    // reduced to an empty list, because an empty list and "we could not ask" look identical on a screen.
    serve(
      topics = List("orders.v1"),
      groups = List("orders-consumer"),
      subjects = List("orders-value"),
      down = Set(SearchRig.SchemaService)
    ) { (server, _) =>
      server.get("/api/v1/search?q=orders").map { response =>
        assertEquals(response.code.code, 200, response.body)
        val answer = answerOf(response.body)

        assertEquals(answer.partial.map(_.value), List("schema"))
        assertEquals(answer.results.subjects, Nil)
        assertEquals(answer.results.topics.map(_.name.value), List("orders.v1"))
        assertEquals(answer.results.groups.map(_.groupId.value), List("orders-consumer"))
      }
    }
  }

  test("aServiceThisDeploymentDoesNotRouteIsNamedInPartial") {
    // The distributed stack routes no schema service at all, so this is the ordinary answer there rather
    // than an outage. It must still say so: subjects that can never arrive are not subjects that matched
    // nothing.
    serve(topics = List("orders.v1"), routed = allThree - SearchRig.SchemaService) { (server, _) =>
      server.get("/api/v1/search?q=orders").map { response =>
        assertEquals(response.code.code, 200, response.body)
        assertEquals(answerOf(response.body).partial.map(_.value), List("schema"))
        assertEquals(answerOf(response.body).results.topics.map(_.name.value), List("orders.v1"))
      }
    }
  }

  test("aClusterListThatCouldNotBeReadNamesEveryServiceInPartial") {
    // Losing the cluster list is not one missing category: it is every service unasked, because nothing
    // knows which clusters to ask about. An empty answer with an empty `partial` would say the product
    // holds nothing at all.
    serve(topics = List("orders.v1"), clusterListDown = true) { (server, calls) =>
      for {
        response <- server.get("/api/v1/search?q=orders")
        asked <- calls
      } yield {
        assertEquals(response.code.code, 200, response.body)
        assertEquals(
          answerOf(response.body).partial.map(_.value),
          List("cluster", "consumer", "schema", "topic")
        )
        assertEquals(asked.map(_.operation), List("cluster.list"))
      }
    }
  }

  test("theFoldIssuesOneRequestPerServicePerClusterAndNotOnePerResult") {
    // Ten matching topics on two clusters is one cluster list plus six calls, not twenty-six. This is the
    // bound the whole design rests on: the field fires on every keystroke.
    serve(
      clusters = List(ClusterId.unsafe("prod-eu"), ClusterId.unsafe("prod-us")),
      topics = (1 to 10).toList.map(n => s"orders.v$n"),
      groups = (1 to 10).toList.map(n => s"orders-consumer-$n"),
      subjects = (1 to 10).toList.map(n => s"orders-$n-value")
    ) { (server, calls) =>
      for {
        response <- server.get("/api/v1/search?q=orders&limit=10")
        asked <- calls
      } yield {
        assertEquals(response.code.code, 200, response.body)
        assertEquals(asked.count(_.operation == "cluster.list"), 1, asked.toString)
        assertEquals(asked.count(_.operation == "topic.names"), 2, asked.toString)
        assertEquals(asked.count(_.operation == "consumer.list"), 2, asked.toString)
        assertEquals(asked.count(_.operation == "schema.subjects"), 2, asked.toString)
        assertEquals(asked.size, 7, asked.toString)
      }
    }
  }

  test("theQueryReachesTheServicesThatNarrowItThemselves") {
    // The consumer and schema services hold the lists and already match on `q`; a gateway that fetched
    // everything and filtered here would move a whole registry across the network per keystroke.
    serve(groups = List("orders-consumer"), subjects = List("orders-value")) { (server, calls) =>
      for {
        _ <- server.get("/api/v1/search?q=orders")
        asked <- calls
        // Sorted, because the fold issues its calls in parallel and the order they are recorded in is the
        // order they finished — which is a property of the machine, not of the product.
      } yield assertEquals(
        asked.filter(_.q.isDefined).map(call => call.operation -> call.q).sortBy(_._1),
        List("consumer.list" -> Some("orders"), "schema.subjects" -> Some("orders"))
      )
    }
  }

  test("theLimitCapsEachKindSeparately") {
    // Per kind rather than over the total: a query matching forty topics must not push every group off the
    // end of a shared budget.
    serve(
      topics = (1 to 9).toList.map(n => s"orders.v$n"),
      groups = (1 to 9).toList.map(n => s"orders-consumer-$n")
    ) { (server, _) =>
      server.get("/api/v1/search?q=orders&limit=2").map { response =>
        val answer = answerOf(response.body)
        assertEquals(answer.results.topics.size, 2, response.body)
        assertEquals(answer.results.groups.size, 2, response.body)
      }
    }
  }

  test("aQueryOfTwoHundredAndOneCharactersIsRefusedNamingTheField") {
    serve() { (server, calls) =>
      for {
        response <- server.get(s"/api/v1/search?q=${"o" * 201}")
        asked <- calls
      } yield {
        assertEquals(response.code.code, 400, response.body)
        val envelope = envelopeOf(response.body)
        assertEquals(envelope.code, "KUI-VALIDATION")
        assertEquals(envelope.details.headOption.flatMap(_.field), Some("q"))
        // And nothing upstream was disturbed by a request that was never going to be answerable.
        assertEquals(asked, Nil)
      }
    }
  }

  test("aQueryOfTwoHundredCharactersIsAccepted") {
    // The bound is checked at both ends, so a refusal cannot be mistaken for the endpoint refusing
    // everything long.
    serve() { (server, _) =>
      server.get(s"/api/v1/search?q=${"o" * 200}").map(response =>
        assertEquals(response.code.code, 200, response.body)
      )
    }
  }

  test("anEmptyQueryIsRefusedNamingTheField") {
    // A blank query matches every name there is, which is the substring rule rather than an accident — and
    // a browser sends one on every backspace.
    serve(topics = List("orders.v1")) { (server, _) =>
      server.get("/api/v1/search?q=").map { response =>
        assertEquals(response.code.code, 400, response.body)
        assertEquals(envelopeOf(response.body).details.headOption.flatMap(_.field), Some("q"))
      }
    }
  }

  test("aMissingQueryIsRefusedNamingTheField") {
    serve() { (server, _) =>
      server.get("/api/v1/search").map { response =>
        assertEquals(response.code.code, 400, response.body)
        assertEquals(envelopeOf(response.body).details.headOption.flatMap(_.field), Some("q"))
      }
    }
  }

  test("aLimitAboveTheCeilingIsRefusedNamingTheField") {
    serve() { (server, _) =>
      server.get("/api/v1/search?q=orders&limit=500").map { response =>
        assertEquals(response.code.code, 400, response.body)
        assertEquals(envelopeOf(response.body).details.headOption.flatMap(_.field), Some("limit"))
      }
    }
  }

  test("theEndpointIsPublishedForTheMergedOpenApiDocument") {
    // Otherwise the browser's generated client would have no method for the one field on every screen.
    assertEquals(SearchRoutes.endpoints.flatMap(_.info.name), List("gateway.search"))
  }
}
