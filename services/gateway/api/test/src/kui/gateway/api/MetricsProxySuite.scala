package kui.gateway.api

import java.time.Instant

import scala.concurrent.duration.DurationInt

import cats.effect.IO
import cats.effect.kernel.{Ref, Resource}
import fs2.Stream
import io.circe.parser.parse
import io.circe.syntax.*
import munit.CatsEffectSuite
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.{Endpoint, PublicEndpoint}

import kui.contracts.capability.{CapabilityKey, ReasonCode}
import kui.contracts.{ErrorEnvelope, Section}
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
import kui.kernel.{ClusterId, ServiceId}
import kui.metrics.contract.MetricsEndpoints
import kui.metrics.contract.dto.{
  ThroughputBucketDto,
  ThroughputRangeDto,
  ThroughputResponse,
  ThroughputSeriesDto
}
import kui.security.SignedPrincipal
import kui.testkit.fakes.FakeStructuredLogger

/** The metrics service's reads, at the gateway: every one routed, and one dead family costing one card.
  *
  * The metrics service is the only one whose whole reason for existing is that a *part* of its answer can be
  * missing. Every endpoint it publishes is `Section`-wrapped, so an exporter that will not answer produces a
  * 200 with `unavailable` inside it, and the tab it is drawn on keeps its other cards. That claim is made in
  * two places — `MetricsEndpoints`' own scaladoc and ADR-032 — and until this suite it was made at the
  * gateway by nothing at all: the derivation is generic, so nothing here had ever proxied a metrics response.
  *
  * The path assertion is derived from `MetricsEndpoints.all` rather than written out, because M7 adds four
  * endpoints to that list beside `throughput`. A list typed here would have to be edited by the packet that
  * adds them, in a file it does not own, to say something it already said in its own contract.
  */
final class MetricsProxySuite extends CatsEffectSuite {

  private val metrics = ServiceId.unsafe("metrics")
  private val cluster = ServiceId.unsafe("cluster")

  private val at = Instant.parse("2026-09-06T10:11:12Z")

  /** A two-bucket series whose second bucket was never sampled.
    *
    * The `None`s are the point rather than filler: the whole DTO is shaped around a bucket KUI did not
    * measure arriving as `null` and not as a zero, and a proxy that re-encoded `None` as `0.0` would turn a
    * gap in the bar into a claim that the cluster was idle. It is asserted here because the gateway decodes
    * with the service's codec and re-encodes with the same one, which is exactly the hop that could lose it.
    */
  private val series = ThroughputSeriesDto(
    range = ThroughputRangeDto.Default,
    from = at,
    to = at.plusSeconds(600),
    stepSeconds = 300L,
    buckets = List(
      ThroughputBucketDto(at, Some(98000.0), Some(26800.5), Some(124.0)),
      ThroughputBucketDto(at.plusSeconds(300), None, None, None)
    )
  )

  final private case class Recorded(context: CallContext, input: String)

  private def stubClient(answer: Either[KuiError, Any]): IO[(ServiceClient[IO], Ref[IO, List[Recorded]])] =
    Ref.of[IO, List[Recorded]](Nil).map { calls =>
      val client = new ServiceClient[IO] {
        val service: ServiceId = metrics
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

  private def signals: Resource[IO, CapabilitySignals[IO]] =
    for {
      logger <- Resource.eval(FakeStructuredLogger[IO])
      registry <- CapabilityRegistry.resource[IO](
        RegistryConfig.Default.copy(debounce = 1.millisecond),
        GatewayTestServer.noTelemetry,
        logger
      )
      built <- Resource.eval(
        CapabilitySignals.make[IO](RegistryConfig.Default, registry, List(metrics, cluster))
      )
    } yield built

  private def notReady(signal: Option[ReadinessSignal]): Boolean = signal match {
    case Some(ReadinessSignal.NotReady(_, _, _)) => true
    case _ => false
  }

  private def serving[A](
      answer: Either[KuiError, Any]
  )(body: (GatewayTestServer.Running, Ref[IO, List[Recorded]], CapabilitySignals[IO]) => IO[A]): IO[A] =
    signals.flatMap(signal => Resource.eval(stubClient(answer)).map(signal -> _)).use {
      case (signal, (client, calls)) =>
        val routes = ContractRouting
          .derive[IO](metrics, ServiceContracts.proxied(metrics), client, signal, RbacPreCheck.allowAll[IO])
          .fold(problem => fail(problem), identity)

        GatewayTestServer.resource(extraRoutes = routes).use(body(_, calls, signal))
    }

  test("everyMetricsEndpointTheGatewayRoutesIsPublishedUnderTheClusterScopedMetricsPath") {
    // Derived on both sides, so an endpoint added to the metrics contract is routed here without an edit —
    // and an endpoint that put itself somewhere other than under a cluster's `metrics` segment fails,
    // which is the one shape the browser's cards and the RBAC cluster gate both depend on.
    val routed = ServiceContracts.proxied(metrics)
    assertEquals(
      routed,
      MetricsEndpoints.all,
      "the gateway routes exactly what the metrics service publishes"
    )
    assert(routed.nonEmpty)

    // `showPathTemplate` and not `publicPathOf`: the latter answers in fixed segments only, by design — it
    // is what the prefix rewrite is checked against — so a path parameter does not appear in it and
    // `{clusterId}` would be silently absent from every comparison.
    routed.foreach { endpoint =>
      val path = endpoint.showPathTemplate().takeWhile(_ != '?').replace("/internal/v1", "/api/v1")
      assert(
        path.startsWith("/api/v1/clusters/{clusterId}/metrics/"),
        s"$path is not a cluster-scoped metrics read"
      )
      // And the rewrite the gateway will actually perform accepts it, which is what stops a contract whose
      // paths are not under /internal/v1 from binding at all.
      assert(ContractRouting.publicPathOf(endpoint).isRight, ContractRouting.publicPathOf(endpoint).toString)
      // Every one is a read: the metrics service publishes no mutation, so nothing here needs CSRF, a plan
      // token or a read-only refusal.
      assertEquals(endpoint.method.map(_.method), Some("GET"), path)
    }
  }

  test("aMetricsReadReachesTheServiceAndItsSectionSurvives") {
    // The seam, on the bytes a card is drawn from. The `null` bucket is the assertion that matters: it is
    // the difference between "we did not measure this five minutes" and "the cluster was idle", and it
    // crosses a decode and a re-encode to get here.
    val document = ThroughputResponse(Section.Ok(series, at)).asJson.noSpaces

    serving(Right(ThroughputResponse(Section.Ok(series, at)))) { (server, calls, _) =>
      for {
        response <- server.get("/api/v1/clusters/prod-eu/metrics/throughput?range=24h")
        seen <- calls.get
      } yield {
        assertEquals(response.code.code, 200, response.body)
        assertEquals(parse(response.body), parse(document))
        assert(response.body.contains("\"bytesInPerSecond\":null"), response.body)
        // And the range the caller asked for reached the service, rather than the endpoint's default.
        assertEquals(
          seen.map(_.input),
          List((ClusterId.unsafe("prod-eu"), ThroughputRangeDto.Last24Hours).toString)
        )
      }
    }
  }

  test("oneUnavailableMetricsFamilyCostsOneCardAndNotTheTab") {
    // An exporter that will not answer is a `Section.Unavailable` inside a **200**, and the metrics
    // service's capability is untouched by it — so the Traffic tab keeps drawing every other card and the
    // sidebar entry stays lit. A 5xx here, or a dimmed capability, would take the whole tab away for one
    // dead family, which is precisely what `Section` exists to prevent.
    //
    // **What each half of this case is worth, stated because they are not worth the same.** The status code
    // and the body are a gateway rule and are failable here: a proxy that re-encoded the section, or turned a
    // 200 carrying `unavailable` into a 5xx, fails on the two lines below. The *capability* assertion is not.
    // `reportIfInfrastructure` keys off `InfrastructureError` alone and a `Section.Unavailable` arrives as a
    // `Right`, so it never reaches that function at all and no gateway-side change can make the last
    // assertion fail. It pins the stub's shape — this document really does travel as a success — rather than
    // a rule. The rule underneath it is "only a transport failure dims a capability", and it is gated where
    // it can fail: `aMetricsApplicationErrorDoesNotDimTheMetricsCapability` below, and
    // `TopicProxySuite.a topic that does not exist does not dim the topic capability`.
    val unavailable: Section[ThroughputSeriesDto] =
      Section.Unavailable(ReasonCode.UpstreamUnavailable, "the exporter did not answer", Some(at))
    val document = ThroughputResponse(unavailable).asJson.noSpaces

    serving(Right(ThroughputResponse(unavailable))) { (server, _, signal) =>
      for {
        response <- server.get("/api/v1/clusters/prod-eu/metrics/throughput?range=24h")
        inputs <- signal.inputs(CapabilityKey(metrics, None))
      } yield {
        assertEquals(response.code.code, 200, response.body)
        assertEquals(parse(response.body), parse(document))
        assert(response.body.contains("\"status\":\"unavailable\""), response.body)
        assert(
          !notReady(inputs.readiness),
          "a dead metric family said nothing about the metrics service, which answered perfectly"
        )
      }
    }
  }

  test("aMetricsServiceThatCannotBeReachedDimsItsOwnCapabilityAndNoOtherServices") {
    // The other direction of the same rule, and the reason the case above is not simply "the gateway never
    // dims anything". A transport failure *is* the service being unavailable and must be reported; a
    // section inside a 200 is not. The cluster service, which was never called, must learn nothing either.
    val unreachable = InfrastructureError.Unreachable("kui-metrics", "connection refused")

    serving(Left(unreachable)) { (server, _, signal) =>
      for {
        response <- server.get("/api/v1/clusters/prod-eu/metrics/throughput?range=24h")
        metricsInputs <- signal.inputs(CapabilityKey(metrics, None))
        clusterInputs <- signal.inputs(CapabilityKey(cluster, None))
      } yield {
        assert(response.code.code >= 500, response.body)
        assert(response.body.contains("KUI-UPSTREAM-UNAVAILABLE"), response.body)
        assert(notReady(metricsInputs.readiness), "an unreachable metrics service must dim its own entry")
        assertEquals(
          clusterInputs.readiness,
          Some(ReadinessSignal.Unknown: ReadinessSignal),
          "the cluster service was never called, so nothing may have been learned about it"
        )
      }
    }
  }

  test("aMetricsApplicationErrorDoesNotDimTheMetricsCapability") {
    // The rule the case above only appears to make, at the place the decision is taken. A caller asking for
    // a cluster that does not exist gets a 404, and that 404 says something about the request rather than
    // about the metrics service, which answered correctly and promptly. Reporting it would let anybody dim
    // the Traffic tab for every other user by typing a bad cluster id into the address bar.
    //
    // `reportIfInfrastructure` is where that is decided, so a mutation removing its `InfrastructureError`
    // guard — reporting every failure — fails here and fails nowhere in the case above.
    val missing = kui.kernel.error.ApplicationError
      .NotFound("cluster", "prod-eu", kui.kernel.error.ErrorCode.ClusterNotFound)

    serving(Left(missing)) { (server, _, signal) =>
      for {
        response <- server.get("/api/v1/clusters/prod-eu/metrics/throughput?range=24h")
        inputs <- signal.inputs(CapabilityKey(metrics, None))
      } yield {
        assertEquals(response.code.code, 404, response.body)
        assert(response.body.contains("KUI-CLUSTER-NOT-FOUND"), response.body)
        assert(
          !notReady(inputs.readiness),
          "a request about a cluster that does not exist dimmed the metrics service, which answered"
        )
      }
    }
  }

  test("aNotConfiguredMetricsSourceIsATwoHundredAndNotAFourOhFour") {
    // The state most deployments are in. A 404 would be indistinguishable from a typo in the URL and would
    // leave a card unable to say the difference between "not measured here" and "this address is wrong".
    val document = ThroughputResponse(Section.NotConfigured).asJson.noSpaces

    serving(Right(ThroughputResponse(Section.NotConfigured))) { (server, _, _) =>
      server.get("/api/v1/clusters/prod-eu/metrics/throughput?range=24h").map { response =>
        assertEquals(response.code.code, 200, response.body)
        assertEquals(parse(response.body), parse(document))
        assert(response.body.contains("\"status\":\"not_configured\""), response.body)
      }
    }
  }

  test("aMalformedRangeIsRefusedAtTheEdgeAndTheMetricsServiceIsNeverCalled") {
    serving(Right(ThroughputResponse(Section.Ok(series, at)))) { (server, calls, _) =>
      for {
        response <- server.get("/api/v1/clusters/prod-eu/metrics/throughput?range=nonsense")
        seen <- calls.get
      } yield {
        assertEquals(response.code.code, 400, response.body)
        assert(response.body.contains("KUI-VALIDATION"), response.body)
        assertEquals(seen, Nil)
      }
    }
  }
}
