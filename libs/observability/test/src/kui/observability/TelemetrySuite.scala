package kui.observability

import cats.effect.IO
import munit.CatsEffectSuite
import org.typelevel.otel4s.oteljava.testkit.trace.TracesTestkit

import kui.config.{SafeUrl, TelemetryConfig, UrlPolicy}
import kui.kernel.Port

/** That telemetry starts, stops and stays out of the way.
  *
  * The last of those three is the one worth testing hardest: a monitoring outage must never become
  * a KUI outage, so a telemetry failure has to degrade to recording nothing rather than to a
  * process that will not boot.
  */
final class TelemetrySuite extends CatsEffectSuite {

  private def url(raw: String): SafeUrl =
    SafeUrl.from(raw, UrlPolicy.Dev).fold(error => fail(error.message), identity)

  private val serviceName = "kui-test"

  test("resourceStartsAndShutsDownCleanly") {
    val before = nonDaemonThreadNames

    Telemetry
      .resource[IO](serviceName, TelemetryConfig.Default)
      .use(telemetry => telemetry.tracer("kui.test"))
      .map { _ =>
        val leaked = nonDaemonThreadNames.diff(before).filter(name => name.toLowerCase.contains("otel"))
        assertEquals(leaked, Set.empty[String], "the SDK left a non-daemon thread behind")
      }
  }

  test("a process with no collector configured still gets a working Telemetry") {
    Telemetry
      .resource[IO](serviceName, TelemetryConfig.Default)
      .use(telemetry => telemetry.meter("kui.test").as(()))
      .map(_ => assert(cond = true))
  }

  test("an unreachable collector is not a startup failure") {
    // Nothing connects at startup, and even if the SDK could not be configured at all the resource
    // falls back to the no-op rather than failing. This is the "telemetry is never a startup
    // dependency" rule of OBS-001, asserted rather than assumed.
    val config = TelemetryConfig.Default.copy(otlpEndpoint = Some(url("http://127.0.0.1:1/")))

    Telemetry
      .resource[IO](serviceName, config)
      .use(telemetry => telemetry.tracer("kui.test"))
      .map(_ => assert(cond = true))
  }

  test("noopEmitsNothing") {
    Telemetry
      .noop[IO]
      .tracer("kui.test")
      .flatMap(tracer => tracer.span("ignored").use_ *> tracer.meta.isEnabled)
      .map(enabled => assertEquals(enabled, false))
  }

  test("a span recorded through Telemetry reaches the exporter under the name it was given") {
    TracesTestkit
      .inMemory[IO]()
      .use { testkit =>
        val telemetry = Telemetry.fromProviders(testkit.tracerProvider, org.typelevel.otel4s.metrics.MeterProvider.noop[IO])
        for {
          tracer <- telemetry.tracer("kui.test")
          _ <- tracer.span("kui.test.operation").use_
          spans <- testkit.finishedSpans
        } yield assertEquals(spans.map(_.getName), List("kui.test.operation"))
      }
  }

  // `service.name` reaches a span through the SDK's resource, which is configured from this map.
  // Asserting the map is what makes the mapping from `TelemetryConfig` to exporter behaviour
  // visible: it is the whole of the decision, and it is otherwise only observable by running a
  // collector.
  test("spanCarriesServiceName: the service name is configured as otel.service.name") {
    assertEquals(
      Telemetry.properties(serviceName, TelemetryConfig.Default).get("otel.service.name"),
      Some(serviceName)
    )
  }

  test("no collector and no scrape port means no exporter is started") {
    val properties = Telemetry.properties(serviceName, TelemetryConfig.Default)

    assertEquals(properties("otel.traces.exporter"), "none")
    assertEquals(properties("otel.metrics.exporter"), "none")
  }

  test("an OTLP endpoint turns on both exporters and points them at it") {
    val config = TelemetryConfig.Default.copy(otlpEndpoint = Some(url("http://collector:4318")))
    val properties = Telemetry.properties(serviceName, config)

    assertEquals(properties("otel.traces.exporter"), "otlp")
    assertEquals(properties("otel.metrics.exporter"), "otlp")
    assertEquals(properties("otel.exporter.otlp.endpoint"), "http://collector:4318")
  }

  test("a scrape port alone exposes metrics locally and exports no traces") {
    val config = TelemetryConfig.Default.copy(prometheusPort = Some(Port.unsafe(9464)))
    val properties = Telemetry.properties(serviceName, config)

    assertEquals(properties("otel.traces.exporter"), "none")
    assertEquals(properties("otel.metrics.exporter"), "prometheus")
    assertEquals(properties("otel.exporter.prometheus.port"), "9464")
  }

  test("logs are never exported through OpenTelemetry, because Logback already ships them") {
    assertEquals(Telemetry.properties(serviceName, TelemetryConfig.Default)("otel.logs.exporter"), "none")
  }

  private def nonDaemonThreadNames: Set[String] =
    Thread.getAllStackTraces.keySet.toArray.toList.collect {
      case thread: Thread if !thread.isDaemon => thread.getName
    }.toSet
}
