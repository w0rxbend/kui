package kui.gateway.api

import scala.concurrent.duration.DurationInt

import cats.effect.IO
import cats.effect.kernel.{Ref, Resource}
import fs2.Stream
import io.circe.parser.parse
import munit.CatsEffectSuite
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.{Endpoint, PublicEndpoint}

import kui.contracts.ErrorEnvelope
import kui.contracts.capability.{CapabilityKey, CapabilityState}
import kui.gateway.api.routing.{ContractRouting, RbacPreCheck, ServiceContracts}
import kui.gateway.application.capability.{
  CapabilityRegistry,
  CapabilitySignals,
  ReadinessSignal,
  RegistryConfig
}
import kui.gateway.application.client.{CallContext, ServiceClient}
import kui.http.sse.SseEvent
import kui.http.upstream.CircuitEvent
import kui.kernel.error.{InfrastructureError, KuiError}
import kui.kernel.search.SearchMode
import kui.kernel.{ClusterId, PageRequest, PageSize, PositiveInt, ServiceId, Sort, SortOrder}
import kui.security.SignedPrincipal
import kui.testkit.fakes.FakeStructuredLogger
import kui.topic.contract.dto.TopicsResponse
import kui.topic.contract.{GoldenDocuments, TopicEndpoints, TopicListParams, TopicSortField}

/** The gateway seam: that a document the topic service really sends survives the middle hop unchanged.
  *
  * M1's second integration defect was a browser decoding a document nobody sends. Two components were each
  * correct and each unit-tested; nothing exercised the join. The documents replayed here are not written in
  * this file — they are `kui.topic.contract.GoldenDocuments`, the very constants the topic contract's own
  * suite asserts against its committed golden files, on both the JVM and Node. One document, three readers,
  * and a drift between any two of them fails a build.
  *
  * The gateway's proxy is generic — it decodes with the endpoint's own codec and re-encodes with the same
  * one — so what these tests really assert is that the generic path does not lose or reshape anything on the
  * way through, including the parts of a response that are *not* the happy case.
  */
final class TopicProxySuite extends CatsEffectSuite {

  private val topic = ServiceId.unsafe("topic")
  private val cluster = ServiceId.unsafe("cluster")

  /** What each proxied call was asked, and under which context.
    *
    * The input is kept as its rendered form rather than as `Any`. Tapir erases an endpoint's input type the
    * moment it is put in a `List[AnyEndpoint]`, so a recording stub cannot name the type it received without
    * a cast; the decoded query is a case class, and its rendering names every field and every value, which
    * is exactly what these assertions are about.
    */
  private final case class Recorded(context: CallContext, input: String)

  private def recorded(document: String): TopicsResponse =
    parse(document)
      .flatMap(_.as[TopicsResponse])
      .fold(failure => fail(s"a recorded topic-service document must decode: $failure"), identity)

  /** A stub upstream that answers every topic endpoint with one value, or fails every call with one error.
    *
    * The answers are real response values rather than placeholders, because the gateway encodes what it is
    * given with the endpoint's own codec: a placeholder would fail to encode and every case would be a 500
    * that proved nothing.
    */
  private def stubClient(
      answer: Either[KuiError, Any]
  ): IO[(ServiceClient[IO], Ref[IO, List[Recorded]])] =
    Ref.of[IO, List[Recorded]](Nil).map { calls =>
      val client = new ServiceClient[IO] {
        val service: ServiceId = topic
        def circuitStates: Stream[IO, CircuitEvent] = Stream.empty

        def call[I, O](endpoint: Endpoint[SignedPrincipal, I, ErrorEnvelope, O, Any], input: I)(
            ctx: CallContext
        ): IO[Either[KuiError, O]] =
          calls.update(_ :+ Recorded(ctx, input.toString)).as(answer.map(_.asInstanceOf[O]))

        def callPublic[I, O](endpoint: PublicEndpoint[I, ErrorEnvelope, O, Any], input: I)(
            ctx: CallContext
        ): IO[Either[KuiError, O]] = IO.raiseError(new UnsupportedOperationException)

        def stream[I](
            endpoint: Endpoint[SignedPrincipal, I, ErrorEnvelope, Stream[IO, Byte], Fs2Streams[IO]],
            input: I
        )(ctx: CallContext): Stream[IO, SseEvent] = Stream.empty
      }
      (client, calls)
    }

  /** Whether something has been learned that dims a capability.
    *
    * `Unknown` — what every service starts as, before its first poll — is not a failure: the gateway knows
    * the service exists and has not asked it anything yet, which is a different statement from "it did not
    * answer" and renders differently.
    */
  private def notReady(signal: Option[ReadinessSignal]): Boolean = signal match {
    case Some(ReadinessSignal.NotReady(_, _, _)) => true
    case _ => false
  }

  /** Signals for both services, because half of this suite is about what a topic failure does *not* touch. */
  private def signals: Resource[IO, CapabilitySignals[IO]] =
    for {
      logger <- Resource.eval(FakeStructuredLogger[IO])
      registry <- CapabilityRegistry.resource[IO](
        RegistryConfig.Default.copy(debounce = 1.millisecond),
        GatewayTestServer.noTelemetry,
        logger
      )
      built <- Resource.eval(
        CapabilitySignals.make[IO](RegistryConfig.Default, registry, List(topic, cluster))
      )
    } yield (built, registry)._1

  private def serving[A](
      answer: Either[KuiError, Any]
  )(body: (GatewayTestServer.Running, Ref[IO, List[Recorded]], CapabilitySignals[IO]) => IO[A]): IO[A] =
    signals.flatMap(signal => Resource.eval(stubClient(answer)).map(signal -> _)).use {
      case (signal, (client, calls)) =>
        val routes = ContractRouting
          .derive[IO](topic, ServiceContracts.proxied(topic), client, signal, RbacPreCheck.allowAll[IO])
          .fold(problem => fail(problem), identity)

        GatewayTestServer.resource(extraRoutes = routes).use(body(_, calls, signal))
    }

  test("everyTopicEndpointIsReachableAtApiV1") {
    // Five endpoints, five public addresses, derived from the contract rather than written out here.
    val expected = List(
      "GET /api/v1/clusters/{clusterId}/topics",
      "GET /api/v1/clusters/{clusterId}/topics/{topicName}",
      "GET /api/v1/clusters/{clusterId}/topics/{topicName}/config",
      "GET /api/v1/clusters/{clusterId}/topics/{topicName}/partitions",
      "POST /api/v1/clusters/{clusterId}/topics/refresh"
    )

    val derived = TopicEndpoints.all.map { endpoint =>
      val method = endpoint.method.map(_.method).getOrElse("GET")
      s"$method ${endpoint.showPathTemplate().takeWhile(_ != '?').replace("/internal/v1", "/api/v1")}"
    }

    assertEquals(derived, expected)
  }

  test("aRecordedTopicServiceResponseIsProxiedUnchanged") {
    // The middle hop, on the real bytes. The topic service's own golden document is decoded by the
    // gateway's client codec and re-encoded to the browser; if the two ever disagree about a field name,
    // an optional field or a discriminator, this is where it shows — with the document named, rather than
    // as an empty table in a browser with no error anywhere.
    val document = GoldenDocuments.topicsResponse
    val response = recorded(document)

    serving(Right(response)) { (server, _, _) =>
      server.get("/api/v1/clusters/prod-eu/topics").map { proxied =>
        assertEquals(proxied.code.code, 200, proxied.body)
        assertEquals(parse(proxied.body), parse(document))
      }
    }
  }

  test("aStaleSectionSurvivesTheProxyIntact") {
    // A stale section carries data, the time it was fetched and why it is old. A proxy that dropped the
    // reason would leave a screen able to say "this is old" and unable to say why, which is the difference
    // between an operator who investigates a slow cluster and one who investigates nothing.
    val document = GoldenDocuments.topicsResponseStale
    val response = recorded(document)

    serving(Right(response)) { (server, _, _) =>
      server.get("/api/v1/clusters/prod-eu/topics").map { proxied =>
        assertEquals(proxied.code.code, 200, proxied.body)
        assertEquals(parse(proxied.body), parse(document))
        assert(proxied.body.contains("UPSTREAM_TIMEOUT"), proxied.body)
      }
    }
  }

  test("an unavailable section is still a 200, not a 5xx") {
    val document = GoldenDocuments.topicsResponseUnavailable
    val response = recorded(document)

    serving(Right(response)) { (server, _, _) =>
      server.get("/api/v1/clusters/prod-eu/topics").map { proxied =>
        assertEquals(proxied.code.code, 200, proxied.body)
        assertEquals(parse(proxied.body), parse(document))
      }
    }
  }

  test("the list query reaches the upstream as the parameters that were asked for") {
    // The gateway re-encodes the query with the contract's own codec, so a parameter it silently dropped
    // would be a filter the user set and the service never saw.
    val response = recorded(GoldenDocuments.topicsResponse)

    serving(Right(response)) { (server, calls, _) =>
      val asked =
        "/api/v1/clusters/prod-eu/topics?q=ord&mode=fts&showInternal=true&sort=size:desc&page=2&pageSize=50"

      // What the topic service would have been handed, built from the same contract the gateway decoded
      // with, so the comparison is against a value rather than against a string somebody typed twice.
      val expected = TopicListParams(
        q = Some("ord"),
        mode = SearchMode.Fts,
        showInternal = true,
        sort = Some(Sort(TopicSortField.Size, SortOrder.Desc)),
        page = PageRequest(PositiveInt.unsafe(2), PageSize.unsafe(50))
      )

      for {
        _ <- server.get(asked)
        seen <- calls.get
      } yield assertEquals(seen.map(_.input), List((ClusterId.unsafe("prod-eu"), expected).toString))
    }
  }

  test("a malformed list parameter is a 400 at the edge and the topic service is never called") {
    serving(Right(recorded(GoldenDocuments.topicsResponse))) { (server, calls, _) =>
      for {
        response <- server.get("/api/v1/clusters/prod-eu/topics?sort=nonsense:asc")
        seen <- calls.get
      } yield {
        assertEquals(response.code.code, 400, response.body)
        assert(response.body.contains("sort"), response.body)
        assertEquals(seen, Nil)
      }
    }
  }

  test("theClusterIdHeaderIsForwarded") {
    serving(Right(recorded(GoldenDocuments.topicsResponse))) { (server, calls, _) =>
      for {
        _ <- server.get("/api/v1/clusters/prod-eu/topics")
        seen <- calls.get
      } yield assertEquals(seen.map(_.context.cluster.map(_.value)), List(Some("prod-eu")))
    }
  }

  test("an inbound cluster header cannot relabel a topic call") {
    // ADR-040: the id the upstream is told always comes from the path, so a caller cannot attribute its
    // traffic — or its slowness — to another cluster.
    serving(Right(recorded(GoldenDocuments.topicsResponse))) { (server, calls, _) =>
      for {
        _ <- server.get("/api/v1/clusters/prod-eu/topics", headers = Map("X-Kui-Cluster-Id" -> "attacker"))
        seen <- calls.get
      } yield assertEquals(seen.map(_.context.cluster.map(_.value)), List(Some("prod-eu")))
    }
  }

  test("anUnknownClusterIdIsRejectedAtTheEdge") {
    serving(Right(recorded(GoldenDocuments.topicsResponse))) { (server, calls, _) =>
      for {
        response <- server.get("/api/v1/clusters/NOT%20A%20SLUG/topics")
        seen <- calls.get
      } yield {
        assertEquals(response.code.code, 400, response.body)
        assert(response.body.contains("KUI-VALIDATION"), response.body)
        assert(response.body.contains("clusterId"), response.body)
        // A request that cannot be about any cluster must not cost the topic service a connection.
        assertEquals(seen, Nil)
      }
    }
  }

  test("aTopicUpstreamFailureIsReportedToTheCapabilitySignals") {
    val unreachable = InfrastructureError.Unreachable("kui-topic", "connection refused")

    serving(Left(unreachable)) { (server, _, signal) =>
      for {
        response <- server.get("/api/v1/clusters/prod-eu/topics")
        inputs <- signal.inputs(CapabilityKey(topic, None))
      } yield {
        assert(response.code.code >= 500, response.body)
        assert(response.body.contains("KUI-UPSTREAM-UNAVAILABLE"), response.body)
        assert(notReady(inputs.readiness), s"an unreachable topic service must dim its own capability")
      }
    }
  }

  test("aTopicUpstreamFailureDoesNotAffectTheClusterCapability") {
    // The milestone's headline claim, asserted at the level that could break it. The topic service is
    // Degradable: it being down leaves the shell, the dashboard and the brokers page working, and the
    // sidebar's Clusters entry untouched.
    val unreachable = InfrastructureError.Unreachable("kui-topic", "connection refused")

    serving(Left(unreachable)) { (server, _, signal) =>
      for {
        _ <- server.get("/api/v1/clusters/prod-eu/topics")
        clusterInputs <- signal.inputs(CapabilityKey(cluster, None))
      } yield assertEquals(
        clusterInputs.readiness,
        Some(ReadinessSignal.Unknown: ReadinessSignal),
        "the cluster service was never called, so nothing may have been learned about it"
      )
    }
  }

  test("a topic that does not exist does not dim the topic capability") {
    // A 404 says something about the request, not about the service, which answered correctly and
    // promptly. Reporting it would let anybody dim a feature for everyone else by typing a bad URL
    // (ADR-039 §6).
    val missing = kui.kernel.error.ApplicationError.NotFound("topic", "nope", kui.kernel.error.ErrorCode.TopicNotFound)

    serving(Left(missing)) { (server, _, signal) =>
      for {
        response <- server.get("/api/v1/clusters/prod-eu/topics/nope")
        inputs <- signal.inputs(CapabilityKey(topic, None))
      } yield {
        assertEquals(response.code.code, 404, response.body)
        assertEquals(inputs.readiness, Some(ReadinessSignal.Unknown: ReadinessSignal))
      }
    }
  }

  test("an upstream timeout is reported as a timeout, not as a generic outage") {
    // The per-upstream timeout itself belongs to `libs/http`'s resilience layer and is tested there; what
    // is this layer's business is that the failure it produces keeps its identity. A timeout and an
    // unreachable host get different remedies, and collapsing them is the defect M1's cluster service
    // shipped (CLAPI-004 deviation 2).
    val timeout = InfrastructureError.Timeout("kui-topic", 2.seconds.toMillis)

    serving(Left(timeout)) { (server, _, signal) =>
      for {
        response <- server.get("/api/v1/clusters/prod-eu/topics")
        state <- signal.inputs(CapabilityKey(topic, None))
      } yield {
        assert(response.body.contains("KUI-TIMEOUT"), response.body)
        assert(notReady(state.readiness), "a timing-out topic service must dim its own capability")
      }
    }
  }

  test("the capability key a topic failure writes to is the service, never one cluster") {
    // A connection that could not be made says something about the service. Writing it to the cluster's
    // key would dim the topic feature for one cluster and leave it green for the rest, which is the wrong
    // shape of both statements.
    val unreachable = InfrastructureError.Unreachable("kui-topic", "connection refused")

    serving(Left(unreachable)) { (server, _, signal) =>
      for {
        _ <- server.get("/api/v1/clusters/prod-eu/topics")
        keys <- signal.keysOf(topic)
      } yield assertEquals(keys, Set(CapabilityKey(topic, None)))
    }
  }

  test("the topic capability is reported under the key the browser reads") {
    // `GET /api/v1/capabilities` keys on the service id, and the sidebar's Topics entry reads the entry
    // named `topic`. A different spelling here is a permanently missing menu item.
    assertEquals(topic.value, "topic")
    assert(ServiceContracts.byService.contains(topic))
    assertEquals(CapabilityKey(topic, None).service, topic)
    assertNotEquals(CapabilityState.Available: CapabilityState, CapabilityState.NotConfigured: CapabilityState)
  }
}
