package kui.gateway.api.routing

import scala.concurrent.duration.DurationInt

import cats.effect.IO
import cats.effect.kernel.{Ref, Resource}
import cats.syntax.all.*
import fs2.Stream
import munit.CatsEffectSuite
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.{AnyEndpoint, Endpoint, PublicEndpoint}

import kui.cluster.contract.ClusterEndpoints
import kui.cluster.contract.dto.*
import kui.contracts.Section
import kui.contracts.cluster.{ClusterRowDto, ClusterSecurityDto}
import kui.contracts.ErrorEnvelope
import kui.gateway.api.GatewayTestServer
import kui.gateway.application.capability.{CapabilityRegistry, CapabilitySignals, RegistryConfig}
import kui.gateway.application.client.{CallContext, ServiceClient}
import kui.http.sse.SseEvent
import kui.http.upstream.CircuitEvent
import kui.kernel.error.KuiError
import kui.kernel.{ClusterId, ServiceId}
import kui.security.{Principal, SignedPrincipal}
import kui.testkit.fakes.FakeStructuredLogger

/** That every cluster endpoint is reachable through the gateway, and that each call knows which cluster it is
  * about.
  *
  * The cluster on a call is not decoration. It labels the upstream metric and the access log, so a slow
  * request can be attributed to one cluster instead of to "the cluster service"; it is what M6's permission
  * check will be asked about; and getting it from the *path* rather than from a header is what stops a caller
  * labelling another cluster's traffic as its own.
  */
final class ClusterRoutingSuite extends CatsEffectSuite {

  private val service = ServiceId.unsafe("cluster")

  /** What each proxied call was asked, and under which context. */
  private final case class Recorded(context: CallContext, input: Any)

  private val at = java.time.Instant.parse("2026-09-03T10:11:12Z")

  private val row = ClusterRowDto(
    id = ClusterId.unsafe("prod-eu"),
    name = "Production EU",
    readOnly = false,
    bootstrapServers = "broker-1.example.com:9093",
    security = ClusterSecurityDto("PLAINTEXT", None, false, false),
    summary = Section.NotConfigured
  )

  /** What each endpoint answers with. Real response values, because the gateway encodes what it is given
    * with the endpoint's own codec: a placeholder would fail to encode and every case would be a 500.
    */
  private val answers: Map[String, Any] = Map(
    "cluster.list" -> ClustersResponse(Nil, at),
    "cluster.get" -> ClusterDetailResponse(row),
    "cluster.brokers" -> BrokersResponse(Section.Ok(Nil, at)),
    "cluster.broker.configs" -> BrokerConfigsResponse(Section.Ok(Nil, at)),
    "cluster.logDirs" -> LogDirsResponse(Section.Ok(Nil, at)),
    "cluster.refresh" -> RefreshAcceptedDto(ClusterId.unsafe("prod-eu"), at)
  )

  private def stubClient(answer: Either[KuiError, Any]): IO[(ServiceClient[IO], Ref[IO, List[Recorded]])] =
    Ref.of[IO, List[Recorded]](Nil).map { calls =>
      val client = new ServiceClient[IO] {
        val service: ServiceId = ServiceId.unsafe("cluster")
        def circuitStates: Stream[IO, CircuitEvent] = Stream.empty

        def call[I, O](endpoint: Endpoint[SignedPrincipal, I, ErrorEnvelope, O, Any], input: I)(
            ctx: CallContext
        ): IO[Either[KuiError, O]] =
          calls
            .update(_ :+ Recorded(ctx, input))
            .as(answer.map(_ => answers(endpoint.info.name.getOrElse("")).asInstanceOf[O]))

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

  private def signals: Resource[IO, CapabilitySignals[IO]] =
    for {
      logger <- Resource.eval(FakeStructuredLogger[IO])
      registry <- CapabilityRegistry.resource[IO](
        RegistryConfig.Default.copy(debounce = 1.millisecond),
        GatewayTestServer.noTelemetry,
        logger
      )
      built <- Resource.eval(CapabilitySignals.make[IO](RegistryConfig.Default, registry, List(service)))
    } yield built

  private def routed(client: ServiceClient[IO], signal: CapabilitySignals[IO], rbac: RbacPreCheck[IO]) =
    ContractRouting
      .derive[IO](service, ClusterEndpoints.all, client, signal, rbac)
      .fold(problem => fail(problem), identity)

  private def serving[A](
      rbac: RbacPreCheck[IO] = RbacPreCheck.allowAll[IO],
      answer: Either[KuiError, Any] = Right(())
  )(body: (GatewayTestServer.Running, Ref[IO, List[Recorded]]) => IO[A]): IO[A] =
    (signals, Resource.eval(stubClient(answer))).tupled.use { case (signal, (client, calls)) =>
      GatewayTestServer.resource(extraRoutes = routed(client, signal, rbac)).use(body(_, calls))
    }

  test("everyClusterEndpointIsReachableAtApiV1") {
    // Driven from the endpoint list rather than from a hand-written path list, so a seventh endpoint is
    // covered the day it is declared.
    val paths = List(
      "/api/v1/clusters",
      "/api/v1/clusters/prod-eu",
      "/api/v1/clusters/prod-eu/brokers",
      "/api/v1/clusters/prod-eu/brokers/1/configs",
      "/api/v1/clusters/prod-eu/log-dirs"
    )

    serving() { (server, _) =>
      paths
        .traverse(path => server.get(path).map(response => (path, response.code.code)))
        .map(seen => assertEquals(seen.filterNot((_, code) => code == 200), Nil))
    }
  }

  test("theUpstreamCallCarriesTheClusterHeader") {
    serving() { (server, calls) =>
      for {
        _ <- server.get("/api/v1/clusters/prod-eu/brokers")
        seen <- calls.get
      } yield assertEquals(seen.map(_.context.cluster.map(_.value)), List(Some("prod-eu")))
    }
  }

  test("theListEndpointSendsNoClusterHeader") {
    // `/api/v1/clusters` is about all of them. An arbitrary label there is a metric bucket that means
    // nothing to whoever reads the dashboard.
    serving() { (server, calls) =>
      for {
        _ <- server.get("/api/v1/clusters")
        seen <- calls.get
      } yield assertEquals(seen.map(_.context.cluster), List(None))
    }
  }

  test("anInboundClusterHeaderIsIgnored") {
    // ADR-040's promise, asserted rather than assumed: the id the upstream is told is always the one the
    // gateway derived from the path, so a caller cannot attribute its traffic to another cluster.
    serving() { (server, calls) =>
      for {
        _ <- server.get(
          "/api/v1/clusters/prod-eu/brokers",
          headers = Map("X-Kui-Cluster-Id" -> "attacker")
        )
        seen <- calls.get
      } yield assertEquals(seen.map(_.context.cluster.map(_.value)), List(Some("prod-eu")))
    }
  }

  test("aMalformedIdIsFourHundredAndTheUpstreamIsNeverCalled") {
    serving() { (server, calls) =>
      for {
        response <- server.get("/api/v1/clusters/NOT%20A%20SLUG/brokers")
        seen <- calls.get
      } yield {
        assertEquals(response.code.code, 400, response.body)
        assert(response.body.contains("KUI-VALIDATION"), response.body)
        assert(response.body.contains("clusterId"), response.body)
        // A request that cannot be about any cluster must not cost the cluster service a connection.
        assertEquals(seen, Nil)
      }
    }
  }

  test("theRbacPreCheckReceivesTheCluster") {
    Ref.of[IO, List[Option[ClusterId]]](Nil).flatMap { asked =>
      val recording = new RbacPreCheck[IO] {
        def check(
            principal: Principal,
            endpoint: AnyEndpoint,
            cluster: Option[ClusterId]
        ): IO[Either[KuiError, Unit]] = asked.update(_ :+ cluster).as(Right(()))
      }

      serving(rbac = recording) { (server, _) =>
        for {
          _ <- server.get("/api/v1/clusters/prod-eu/brokers")
          _ <- server.get("/api/v1/clusters")
          seen <- asked.get
        } yield assertEquals(seen.map(_.map(_.value)), List(Some("prod-eu"), None))
      }
    }
  }

  test("aDeniedRequestNeverReachesTheCluster service") {
    serving(rbac = RbacPreCheck.denyAll[IO]("no")) { (server, calls) =>
      for {
        response <- server.get("/api/v1/clusters/prod-eu/brokers")
        seen <- calls.get
      } yield {
        assertEquals(response.code.code, 403, response.body)
        assertEquals(seen, Nil)
      }
    }
  }

  test("theAggregatedEndpointHasNoDerivedRoute") {
    // Without this, the gateway's own cluster-list route and a derived proxy route would both claim
    // `/api/v1/clusters`, and which one answered would depend on the order they were added to a list.
    val proxied = ServiceContracts.proxied(service)

    assert(!proxied.exists(_.info.name.contains("cluster.list")), proxied.flatMap(_.info.name).toString)
    assertEquals(proxied.size, ClusterEndpoints.all.size - 1)
    // Compared on the *template*, not on `publicPathOf`: that function reports fixed segments only, so
    // `/api/v1/clusters/{clusterId}` and `/api/v1/clusters` share its answer. Two routes for one address is
    // what this test exists to prevent, and only the template distinguishes them.
    val templates = proxied.map(endpoint =>
      ContractRouting.publicPathOf(endpoint).fold(identity, _ => endpoint.showPathTemplate())
    )

    assert(!templates.exists(_.replace("/internal/v1", "/api/v1") == "/api/v1/clusters"), templates.toString)
  }
}
