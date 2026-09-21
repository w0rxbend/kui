package kui.gateway.api.search

import cats.effect.IO
import cats.effect.kernel.Ref
import io.circe.parser.parse
import munit.CatsEffectSuite

import kui.contracts.ErrorEnvelope
import kui.gateway.api.search.SearchRig.{Behaviour, Call, Data}
import kui.gateway.api.{GatewayTestServer, SearchRoutes}
import kui.gateway.application.client.CallContext
import kui.gateway.application.search.{SearchSource, SearchUseCase}
import kui.gateway.contract.SearchQuery
import kui.gateway.contract.dto.{SearchAnswerDto, SearchResultsDto}
import kui.kernel.group.GroupState
import kui.kernel.{ClusterId, CorrelationId, ServiceId}
import kui.security.Principal

/** Cross-entity search, asserted where a browser meets it: over a real server, at `/api/v1/search`.
  *
  * Every case here goes through the route, the contract's own query decoding and the error interceptor,
  * because that is where each of these rules is actually applied. A suite that called `SearchUseCase.search`
  * directly could not see the 400 at all — the query bounds are declared on the endpoint and enforced by
  * Tapir before any handler runs — and would be asserting an arrangement of its own rather than the product.
  */
final class SearchSuite extends CatsEffectSuite {

  /** The call context a route would have built: anonymous, one correlation id, one cluster.
    *
    * Only `aTopicSearchReturnsNoMoreHitsThanTheCallerAskedFor` needs it — it is the one case that drives a
    * `SearchSource` directly, because the cap it asserts is the gateway's own `.take` and the fold's
    * out-going cut hides its absence completely. Everything else goes through the route, which builds its own
    * context from the request. The case is named rather than counted: this comment said "the two cases" for
    * two waves after the second one stopped existing, and a count with nothing behind it drifts.
    */
  private val context: CallContext =
    CallContext(
      Principal.Anonymous,
      CorrelationId.unsafe("00000000-0000-4000-8000-000000000001"),
      Some(SearchRig.Cluster)
    )

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

  test("aServiceThatFailedOnEveryClusterIsNamedInPartialOnce") {
    // `partialOf` is `distinct` and sorted, and its scaladoc says why: two identical requests must produce
    // identical bytes, and a browser rendering the list would otherwise print "Schema Registry, Schema
    // Registry" on a two-cluster deployment. Every other case here runs on one cluster, so nothing could
    // ever see a repeat — dropping `.distinct` left the whole gateway suite green when it was tried.
    serve(
      clusters = List(ClusterId.unsafe("prod-eu"), ClusterId.unsafe("prod-us")),
      topics = List("orders.v1"),
      down = Set(SearchRig.SchemaService)
    ) { (server, _) =>
      server.get("/api/v1/search?q=orders").map { response =>
        assertEquals(response.code.code, 200, response.body)
        assertEquals(answerOf(response.body).partial.map(_.value), List("schema"))
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

  test("aSearchAsksEachSourceForTheCallersLimitAndNoMore") {
    // The fan-out's *cost* bound, which no response body can show: `SearchResultsDto.take` cuts every kind
    // to `limit` on the way out, so a source that asked its service for a hundred rows and one that asked
    // for ten produce the same document. The difference is paid upstream — the schema service enriches
    // each subject it returns with per-subject registry calls, so asking for the contract's maximum would
    // be five hundred of them — and it is visible only in what was asked.
    serve(
      groups = (1 to 40).toList.map(n => s"orders-consumer-$n"),
      subjects = (1 to 40).toList.map(n => s"orders-$n-value")
    ) { (server, calls) =>
      for {
        response <- server.get("/api/v1/search?q=orders&limit=3")
        asked <- calls
      } yield {
        assertEquals(response.code.code, 200, response.body)
        assertEquals(
          asked.filter(_.pageSize.isDefined).map(call => call.operation -> call.pageSize).sortBy(_._1),
          List("consumer.list" -> Some(3), "schema.subjects" -> Some(3))
        )
      }
    }
  }

  test("aTopicSearchReturnsNoMoreHitsThanTheCallerAskedFor") {
    // The topic half's share of the same bound, and it is asserted on the source rather than on the
    // response because `topics/names` is unpaged by design: the cap is the gateway's own `.take`, and the
    // fold's out-going cut hides its absence completely. Forty matches cut to three here is forty
    // `TopicHitDto`s not built, per cluster, on every keystroke.
    Ref.of[IO, List[Call]](Nil).flatMap { calls =>
      val data = Data(topics = (1 to 40).toList.map(n => s"orders.v$n"))
      val source = TopicSearchSource[IO](SearchRig.client(SearchRig.TopicService, data, calls))

      source
        .find(SearchRig.Cluster, SearchQuery("orders", 3), context)
        .map(_.fold(error => fail(error.message), results => assertEquals(results.topics.size, 3)))
    }
  }

  test("aGroupSearchAsksForEveryState") {
    // No state filter, and the reason is the case the field is most often opened for: somebody is looking
    // for a group precisely *because* it is dead. A default that asked only for `Stable` would answer
    // "nothing matches" for a group that is sitting right there, and would do it silently — the answer is
    // a well-formed empty list either way.
    serve(groups = List("orders-consumer")) { (server, calls) =>
      for {
        _ <- server.get("/api/v1/search?q=orders")
        asked <- calls
      } yield assertEquals(
        asked.filter(_.operation == "consumer.list").map(_.states),
        List(Some(Set.empty[GroupState]))
      )
    }
  }

  test("aDeploymentWithNoClustersNamesNoServiceInPartialBecauseAllThreeWereAskable") {
    // ADR-049 §2 defines `partial` as the services the gateway *could not ask*, in four enumerated ways,
    // and a deployment with no clusters is none of them: the topic, consumer and schema services are all
    // routed and all perfectly askable, there is simply nothing to ask them about. Naming them would put a
    // sentence in front of a user — "Topics, Consumer groups, Schema Registry could not be asked" — that is
    // not true, which is the one thing this endpoint's whole design is against.
    //
    // What it costs is stated rather than hidden: the browser cannot tell this answer from a search that
    // matched nothing, and it renders "Nothing matches". That is a browser-side fact and the browser
    // already holds it — it draws the cluster selector from the same list — so the remedy is a sentence on
    // the shell's empty state and not a fifth cause in this field. `SearchAnswerDto.partial` says so too.
    serve(clusters = Nil, topics = List("orders.v1")) { (server, calls) =>
      for {
        response <- server.get("/api/v1/search?q=orders")
        asked <- calls
      } yield {
        assertEquals(response.code.code, 200, response.body)
        assertEquals(answerOf(response.body).partial, Nil)
        assertEquals(answerOf(response.body).results, SearchResultsDto.Empty)
        // And nothing was asked past the cluster list, which is the other half of the same fact.
        assertEquals(asked.map(_.operation), List("cluster.list"))
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
      server
        .get(s"/api/v1/search?q=${"o" * 200}")
        .map(response => assertEquals(response.code.code, 200, response.body))
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
