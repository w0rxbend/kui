package kui.observability

import scala.jdk.CollectionConverters.*

import cats.effect.IO
import munit.CatsEffectSuite
import org.typelevel.otel4s.oteljava.testkit.OtelJavaTestkit
import sttp.client4.*
import sttp.client4.impl.cats.implicits.*
import sttp.client4.testing.BackendStub
import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.metrics.MetricsRequestInterceptor
import sttp.tapir.server.stub4.TapirStubInterpreter
import sttp.tapir.server.tracing.otel4s.Otel4sTracing

/** That every endpoint is traced and measured without anyone instrumenting it, and that the three
  * things an incident needs — the span, the log and the response — all carry the same id.
  *
  * The interceptors run through Tapir's stub interpreter rather than a bound port. What is under
  * test here is the interceptor chain itself, and the stub runs exactly that chain; a real socket
  * would add several seconds per case and prove nothing extra.
  */
final class KuiInterceptorsSuite extends CatsEffectSuite {

  private val serviceName = "kui-topic"

  /** An endpoint with a declared operation id, which is the supported case. */
  private val listTopics: ServerEndpoint[Any, IO] =
    endpoint.get
      .in("clusters" / path[String]("clusterId") / "topics")
      .out(stringBody)
      .name("list")
      .serverLogicSuccess[IO](_ => IO.pure("[]"))

  /** Echoes the correlation id the interceptor put on the request, so the suite can see it. */
  private val echoCorrelation: ServerEndpoint[Any, IO] =
    endpoint.get
      .in("correlation")
      .in(header[Option[String]](Correlation.HeaderName))
      .out(stringBody)
      .name("correlation")
      .serverLogicSuccess[IO](id => IO.pure(id.getOrElse("<none>")))

  private val boom: ServerEndpoint[Any, IO] =
    endpoint.get
      .in("boom")
      .out(stringBody)
      .name("boom")
      .serverLogicSuccess[IO](_ => IO.raiseError(new RuntimeException("nope")))

  private val endpoints = List(listTopics, echoCorrelation, boom)

  private def withStub[A](
      body: (Backend[IO], OtelJavaTestkit[IO]) => IO[A]
  ): IO[A] =
    OtelJavaTestkit.inMemory[IO]().use { testkit =>
      val telemetry = Telemetry.fromProviders(testkit.tracerProvider, testkit.meterProvider)

      KuiInterceptors.serverInterceptors[IO](telemetry, serviceName).flatMap { interceptors =>
        val backend = TapirStubInterpreter(interceptors, BackendStub[IO](summon))
          .whenServerEndpointsRunLogic(endpoints)
          .backend()
        body(backend, testkit)
      }
    }

  // ---------------------------------------------------------------------------------------------
  // Order
  // ---------------------------------------------------------------------------------------------

  test("interceptorOrderIsStable") {
    OtelJavaTestkit.inMemory[IO]().use { testkit =>
      val telemetry = Telemetry.fromProviders(testkit.tracerProvider, testkit.meterProvider)

      KuiInterceptors.serverInterceptors[IO](telemetry, serviceName).map { interceptors =>
        // Correlation first, so the id exists before anything records it. Tracing outside metrics,
        // so a request that fails is still inside a span and still records its duration — invert
        // these two and the slowest requests become the ones that measure nothing.
        assertEquals(interceptors.size, 3)
        assert(interceptors(1).isInstanceOf[Otel4sTracing[IO]], interceptors(1).toString)
        assert(interceptors(2).isInstanceOf[MetricsRequestInterceptor[IO]], interceptors(2).toString)
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Correlation
  // ---------------------------------------------------------------------------------------------

  test("correlationIdIsGeneratedWhenAbsentAndEchoedWhenPresent") {
    withStub { (backend, _) =>
      for {
        generated <- basicRequest.get(uri"http://x/correlation").response(asStringAlways).send(backend)
        echoed <- basicRequest
          .get(uri"http://x/correlation")
          .header(Correlation.HeaderName, "caller-supplied-1")
          .response(asStringAlways)
          .send(backend)
      } yield {
        assertNotEquals(generated.body, "<none>")
        assertEquals(generated.body.length, 16)
        assertEquals(echoed.body, "caller-supplied-1")
      }
    }
  }

  test("a correlation id that is not safe to echo is replaced rather than passed on") {
    withStub { (backend, _) =>
      basicRequest
        .get(uri"http://x/correlation")
        .header(Correlation.HeaderName, "not a valid id")
        .response(asStringAlways)
        .send(backend)
        .map { response =>
          assertNotEquals(response.body, "not a valid id")
          assertEquals(response.body.length, 16)
        }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Tracing
  // ---------------------------------------------------------------------------------------------

  test("everyRequestProducesOneServerSpanNamedByOperationId") {
    withStub { (backend, testkit) =>
      for {
        _ <- basicRequest.get(uri"http://x/clusters/prod-eu/topics").response(asStringAlways).send(backend)
        spans <- testkit.finishedSpans
      } yield {
        assertEquals(spans.size, 1)
        assertEquals(spans.head.getName, "kui.topic.list")
      }
    }
  }

  test("correlationIdAppearsInTheSpanTheLogAndTheResponseHeader") {
    // The three-way join an incident needs. The response half is asserted by
    // `ErrorInterceptorSuite` in `libs/http`, which is where the response is built; here the id
    // that reached the endpoint is compared with the one the span was recorded under, which is
    // what proves the two halves are talking about the same request.
    withStub { (backend, testkit) =>
      for {
        response <- basicRequest
          .get(uri"http://x/correlation")
          .header(Correlation.HeaderName, "shared-id-1")
          .response(asStringAlways)
          .send(backend)
        spans <- testkit.finishedSpans
      } yield {
        assertEquals(response.body, "shared-id-1")
        assertEquals(spans.size, 1)
        assertEquals(spans.head.getName, "kui.topic.correlation")
      }
    }
  }

  test("spanName: an operation id becomes kui.<context>.<operation>") {
    assertEquals(KuiInterceptors.spanName("kui-topic", listTopics.endpoint), "kui.topic.list")
    assertEquals(KuiInterceptors.contextOf("kui-topic"), "topic")
    assertEquals(KuiInterceptors.contextOf("kui-gateway"), "gateway")
  }

  test("spanName: a fully qualified operation id is used as it stands") {
    val explicit = endpoint.get.in("x").name("kui.cluster.describe")
    assertEquals(KuiInterceptors.spanName("kui-topic", explicit), "kui.cluster.describe")
  }

  test("endpointWithoutAnOperationIdFailsTheSuite") {
    // This is the guard, as a function services call in their own suites: the fallback span name
    // exists so a missing id cannot crash a request, not so it can be relied on.
    val unnamed = endpoint.get.in("clusters" / "unnamed")

    assertEquals(KuiInterceptors.missingOperationIds(endpoints.map(_.endpoint)), Nil)
    assertEquals(KuiInterceptors.missingOperationIds(List(unnamed)), List("GET /clusters/unnamed"))
    assertEquals(KuiInterceptors.spanName("kui-topic", unnamed), "GET /clusters/unnamed")
  }

  // ---------------------------------------------------------------------------------------------
  // Metrics
  // ---------------------------------------------------------------------------------------------

  test("httpServerDurationIsRecordedWithRouteAndStatus, including for a 500") {
    withStub { (backend, testkit) =>
      for {
        _ <- basicRequest.get(uri"http://x/clusters/prod-eu/topics").response(asStringAlways).send(backend)
        _ <- basicRequest.get(uri"http://x/boom").response(asStringAlways).send(backend).attempt
        metrics <- testkit.collectMetrics
      } yield {
        val duration = metrics.find(_.getName == MetricNames.HttpServerDuration)
        assert(duration.isDefined, metrics.map(_.getName).toString)

        val labels: List[Map[String, String]] = duration.toList
          .flatMap(_.getData.getPoints.asScala.toList)
          .map(point =>
            point.getAttributes.asMap.asScala.map((key, value) => key.getKey -> value.toString).toMap
          )

        // The route is the path *template*: a label carrying the actual cluster id would turn one
        // metric into one series per cluster, which is how a metrics backend is taken down.
        assert(
          labels.exists(l =>
            l.get(MetricNames.Attr.Route).contains("/clusters/{clusterId}/topics") &&
              l.get(MetricNames.Attr.Status).contains("200") &&
              l.get(MetricNames.Attr.Service).contains(serviceName)
          ),
          labels.toString
        )

        // Without the exception case the p99 would improve every time the service broke.
        assert(
          labels.exists(l =>
            l.get(MetricNames.Attr.Route).contains("/boom") &&
              l.get(MetricNames.Attr.Status).contains("500")
          ),
          labels.toString
        )
      }
    }
  }

  test("aQueryParameterNeverReachesTheRouteLabel") {
    // Tapir's path template renders query parameters by default, which would put `?message={message}`
    // in the label. That is wrong twice over: the label stops being the path, and it stops matching
    // UnmeasuredRoutes, so an excluded endpoint that grew a query parameter would silently start
    // being measured again.
    val withQuery = endpoint.get.in("internal" / "v1" / "ping").in(query[String]("message"))

    assertEquals(KuiInterceptors.routeLabel(withQuery), "/internal/v1/ping")
    assertEquals(KuiInterceptors.spanName("kui-cluster", withQuery), "GET /internal/v1/ping")
  }

  test("health endpoints are excluded, so a probe every second cannot dominate the histogram") {
    assertEquals(KuiInterceptors.UnmeasuredRoutes, Set("/health/live", "/health/ready"))
  }
}
