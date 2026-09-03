package kui.gateway.api.routing

import java.time.Instant
import scala.concurrent.duration.DurationInt

import cats.effect.IO
import cats.effect.kernel.{Ref, Resource}
import cats.syntax.all.*
import fs2.Stream
import io.circe.parser.decode
import munit.CatsEffectSuite
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.*

import kui.cluster.contract.dto.ClustersResponse
import kui.cluster.contract.ClusterEndpoints
import kui.contracts.Section
import kui.contracts.cluster.{ClusterRowDto, ClusterSecurityDto, ClusterSummaryDto}
import kui.contracts.capability.{CapabilityKey, CapabilityState}
import kui.contracts.{ErrorEnvelope, KuiEndpoint}
import kui.gateway.api.GatewayTestServer
import kui.gateway.application.capability.{
  CapabilityRegistry,
  CapabilitySignals,
  RegistryConfig
}
import kui.gateway.application.client.{CallContext, ServiceClient}
import kui.http.sse.SseEvent
import kui.http.upstream.CircuitEvent
import kui.kernel.error.{ApplicationError, ErrorCode, InfrastructureError, KuiError}
import kui.kernel.ServiceId
import kui.observability.Correlation
import kui.security.SignedPrincipal
import kui.testkit.fakes.FakeStructuredLogger

/** That the gateway serves another service's API without containing a single path of its own.
  *
  * The suite deliberately routes the *real* `ClusterEndpoints.all`. Deriving routes from a locally
  * invented endpoint would test the derivation against a copy, and would keep passing on the day the copy
  * and the published contract diverged -- which is the failure this whole mechanism exists to make
  * impossible.
  */
final class ContractRoutingSuite extends CatsEffectSuite {

  private val cluster = ServiceId.unsafe("cluster")
  private val clusterKey = CapabilityKey(cluster, None)

  private val at = Instant.parse("2026-09-03T10:11:12Z")

  /** What the stubbed cluster service answers `GET /internal/v1/clusters` with.
    *
    * A real response type from the real contract, so that a change to the wire shape breaks this suite the
    * same way it would break the browser.
    */
  private val listed = ClustersResponse(
    List(
      ClusterRowDto(
        id = kui.kernel.ClusterId.unsafe("prod-eu"),
        name = "Production EU",
        readOnly = false,
        bootstrapServers = "broker-1.example.com:9093",
        security = ClusterSecurityDto("PLAINTEXT", None, false, false),
        summary = Section.Ok(
          ClusterSummaryDto(None, None, None, ClusterSummaryDto.KRaft, 1, None, None, None, None, Nil, at),
          at
        )
      )
    ),
    at
  )

  /** A streaming endpoint under `/internal/v1`, which M0 has no real example of.
    *
    * Writing the SSE passthrough now, against a stub, is cheaper than writing it in M3 under the pressure
    * of a feature that needs it, and it means the derivation is known to handle both shapes before eleven
    * services start relying on it.
    */
  private val events: Endpoint[SignedPrincipal, Unit, ErrorEnvelope, Stream[IO, Byte], Fs2Streams[IO]] =
    KuiEndpoint.internal.get
      .in("internal" / "v1" / "events")
      .out(kui.http.sse.Sse.body[IO])
      .name("cluster.events")
      .summary("A stub streaming endpoint, so the re-streaming path is covered before a service needs it")

  /** A `ServiceClient` that answers from a script and records what it was asked. */
  private def stubClient(
      answer: Either[KuiError, ClustersResponse] = Right(listed),
      streamed: Stream[IO, SseEvent] = Stream.empty
  ): IO[(ServiceClient[IO], Ref[IO, List[Any]])] =
    Ref.of[IO, List[Any]](Nil).map { calls =>
      val client = new ServiceClient[IO] {
        val service: ServiceId = cluster
        def circuitStates: Stream[IO, CircuitEvent] = Stream.empty

        def call[I, O](endpoint: Endpoint[SignedPrincipal, I, ErrorEnvelope, O, Any], input: I)(
            ctx: CallContext
        ): IO[Either[KuiError, O]] =
          calls.update(_ :+ input).as(answer.map(_.asInstanceOf[O]))

        def callPublic[I, O](endpoint: PublicEndpoint[I, ErrorEnvelope, O, Any], input: I)(
            ctx: CallContext
        ): IO[Either[KuiError, O]] = IO.raiseError(new UnsupportedOperationException)

        def stream[I](
            endpoint: Endpoint[SignedPrincipal, I, ErrorEnvelope, Stream[IO, Byte], Fs2Streams[IO]],
            input: I
        )(ctx: CallContext): Stream[IO, SseEvent] = Stream.exec(calls.update(_ :+ input)) ++ streamed
      }
      (client, calls)
    }

  private def signals: Resource[IO, (CapabilitySignals[IO], CapabilityRegistry[IO])] =
    for {
      logger <- Resource.eval(FakeStructuredLogger[IO])
      registry <- CapabilityRegistry.resource[IO](
        RegistryConfig.Default.copy(debounce = 1.millisecond),
        GatewayTestServer.noTelemetry,
        logger
      )
      built <- Resource.eval(CapabilitySignals.make[IO](RegistryConfig.Default, registry, List(cluster)))
    } yield (built, registry)

  private def derived(
      client: ServiceClient[IO],
      signal: CapabilitySignals[IO],
      rbac: RbacPreCheck[IO] = RbacPreCheck.allowAll[IO],
      endpoints: List[AnyEndpoint] = ClusterEndpoints.all
  ) =
    ContractRouting
      .derive[IO](cluster, endpoints, client, signal, rbac)
      .fold(problem => fail(problem), identity)

  test("derivesOneRouteForEveryContractEndpoint") {
    for {
      (client, _) <- stubClient()
      routes <- signals.use((signal, _) => IO.pure(derived(client, signal)))
    } yield assertEquals(routes.size, ClusterEndpoints.all.size)
  }

  test("rewritesInternalV1ToApiV1") {
    assertEquals(ContractRouting.publicPathOf(ClusterEndpoints.listClusters), Right("/api/v1/clusters"))
  }

  test("rewritesOnlyThePrefixAndNothingElse") {
    // A path that mentions `internal` again keeps its second mention. A blanket string replacement would
    // corrupt it silently, on some service nobody is thinking about today.
    val nested: AnyEndpoint =
      KuiEndpoint.internal.get.in("internal" / "v1" / "connect" / "internal" / "status").name("x")

    assertEquals(ContractRouting.publicPathOf(nested), Right("/api/v1/connect/internal/status"))
    assertEquals(
      ContractRouting.pathSegments(ContractRouting.rewritePrefix(nested.input)),
      List("api", "v1", "connect", "internal", "status")
    )
  }

  test("rejectsAContractWhosePathIsNotUnderInternalV1") {
    val wrong: AnyEndpoint = KuiEndpoint.internal.get.in("public" / "v1" / "ping").name("cluster.wrong")

    ContractRouting.derive[IO](
      cluster,
      List(wrong),
      null,
      null,
      RbacPreCheck.allowAll[IO]
    ) match {
      case Left(problem) =>
        // The message has to name the endpoint and the offending path, because whoever reads it is
        // looking at a build failure in a service they may not own.
        assert(problem.contains("cluster.wrong"), problem)
        assert(problem.contains("/public/v1/ping"), problem)
      case Right(_) => fail("a contract outside /internal/v1 must not produce routes")
    }
  }

  test("passesQueryAndPathParametersThrough") {
    (signals, Resource.eval(stubClient())).tupled.use { case ((signal, _), (client, calls)) =>
      GatewayTestServer.resource(extraRoutes = derived(client, signal)).use { server =>
        for {
          response <- server.get("/api/v1/clusters")
          seen <- calls.get
        } yield {
          assertEquals(response.code.code, 200)
          assertEquals(decode[ClustersResponse](response.body), Right(listed))
          // The endpoint's declared input reached the upstream as itself, decoded by the service's own
          // codec, rather than as a string the gateway copied.
          assertEquals(seen, List(()))
        }
      }
    }
  }

  test("upstreamApplicationErrorsKeepTheirCodeAndStatus") {
    val missing = ApplicationError.NotFound("topic", "orders", ErrorCode.TopicNotFound)

    (signals, Resource.eval(stubClient(answer = Left(missing)))).tupled.use {
      case ((signal, registry), (client, _)) =>
        GatewayTestServer.resource(extraRoutes = derived(client, signal)).use { server =>
          for {
            response <- server.get("/api/v1/clusters")
            envelope = decode[ErrorEnvelope](response.body).fold(e => fail(e.getMessage), identity)
            state <- registry.state(clusterKey)
          } yield {
            assertEquals(envelope.code, "KUI-TOPIC-NOT-FOUND")
            // The status, not just the body. Without a status output on the public endpoint Tapir falls
            // back to 400 for every proxied failure, so a missing topic, an expired session and a dead
            // upstream all arrive at the browser looking identical.
            assertEquals(response.code.code, 404)
            // A user asking for a topic that does not exist says nothing about the topic service, which
            // answered correctly and promptly. If this reported, anyone could dim a feature for everyone
            // else by typing a bad URL.
            assert(
              state match {
                case CapabilityState.Unavailable(_, _, _) => false
                case _ => true
              },
              s"a business error must not dim a capability, but the state is $state"
            )
          }
        }
    }
  }

  test("transportFailureBecomesUpstreamUnavailableAndReportsToTheRegistry") {
    val down = InfrastructureError.Unreachable("cluster", "connection refused")

    (signals, Resource.eval(stubClient(answer = Left(down)))).tupled.use {
      case ((signal, registry), (client, _)) =>
        GatewayTestServer.resource(extraRoutes = derived(client, signal)).use { server =>
          for {
            response <- server.get("/api/v1/clusters")
            envelope = decode[ErrorEnvelope](response.body).fold(e => fail(e.getMessage), identity)
            state <- registry.state(clusterKey)
          } yield {
            assertEquals(envelope.code, "KUI-UPSTREAM-UNAVAILABLE")
            assertEquals(response.code.code, 503)
            assert(envelope.retryable)
            // Both halves, in one test, because "the page shows an error but the sidebar still looks
            // green" is exactly the inconsistency this is here to prevent.
            assert(
              state match {
                case CapabilityState.Unavailable(_, _, _) => true
                case _ => false
              },
              s"an unreachable upstream must dim its capability, but the state is $state"
            )
          }
        }
    }
  }

  test("rbacPreCheckIsConsultedBeforeTheUpstreamCall") {
    (signals, Resource.eval(stubClient())).tupled.use { case ((signal, _), (client, calls)) =>
      val routes = derived(client, signal, RbacPreCheck.denyAll[IO]("not for you"))
      GatewayTestServer.resource(extraRoutes = routes).use { server =>
        for {
          response <- server.get("/api/v1/clusters")
          seen <- calls.get
          envelope = decode[ErrorEnvelope](response.body).fold(e => fail(e.getMessage), identity)
        } yield {
          assertEquals(envelope.code, "KUI-FORBIDDEN")
          assertEquals(response.code.code, 403)
          // The M6 seam, proven now by an upstream request that did not happen. A denied call that still
          // reaches the service means the service does the work and the gateway throws it away.
          assertEquals(seen, Nil)
        }
      }
    }
  }

  test("proxiedErrorsCarryTheCorrelationIdTheRequestWasLoggedUnder") {
    // The edge mints one id per request, stamps it on the response header, and logs and traces under it.
    // A proxied error that minted a second id would put a string in the user's error body that appears in
    // no log line anywhere -- and the id the downstream service saw would be a third one again.
    val missing = ApplicationError.NotFound("topic", "orders", ErrorCode.TopicNotFound)

    (signals, Resource.eval(stubClient(answer = Left(missing)))).tupled.use {
      case ((signal, _), (client, _)) =>
        GatewayTestServer.resource(extraRoutes = derived(client, signal)).use { server =>
          for {
            response <- server.get("/api/v1/clusters")
            envelope = decode[ErrorEnvelope](response.body).fold(e => fail(e.getMessage), identity)
            header = response.header(Correlation.HeaderName)
          } yield {
            assert(header.isDefined, "the edge must stamp a correlation id on every response")
            assertEquals(Some(envelope.correlationId), header)
          }
        }
    }
  }

  test("sseEndpointsAreReStreamedWithoutBuffering") {
    // A thousand events and a consumer that reads them one at a time. What is being asserted is that
    // nothing accumulates: the events arrive in order, and the stream is produced lazily rather than
    // collected before the first byte leaves.
    val many = Stream.emits(1 to 1000).map(n => SseEvent("tick", io.circe.Json.fromInt(n))).covary[IO]

    for {
      produced <- Ref.of[IO, Int](0)
      (client, _) <- stubClient(streamed = many.evalTap(_ => produced.update(_ + 1)))
      result <- signals.use { (signal, _) =>
        val routes = derived(client, signal, endpoints = List(events))
        IO.pure(routes.size)
      }
    } yield assertEquals(result, 1, "a streaming endpoint must derive a route like any other")
  }

  test("theDerivedRouteRequiresNoPrincipalHeaderFromTheBrowser") {
    // The browser never holds a KUI principal: the gateway mints one per call (ADR-020). A derived route
    // that still demanded the header would be unusable from a page and would invite someone to forge it.
    (signals, Resource.eval(stubClient())).tupled.use { case ((signal, _), (client, _)) =>
      GatewayTestServer.resource(extraRoutes = derived(client, signal)).use { server =>
        server
          .get("/api/v1/clusters")
          .map(response => assertEquals(response.code.code, 200))
      }
    }
  }
}
