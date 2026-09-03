package kui.observability

import scala.jdk.CollectionConverters.*

import cats.Applicative
import cats.effect.LiftIO
import cats.effect.kernel.{Async, Resource}
import org.typelevel.otel4s.metrics.{Meter, MeterProvider}
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.trace.{Tracer, TracerProvider}

import kui.config.TelemetryConfig

/** The two things a KUI module ever asks the telemetry system for.
  *
  * It is a narrow interface over otel4s rather than the otel4s `Otel4s` value itself, so that a module
  * depends on "give me a tracer" and not on the whole SDK surface — and so that [[Telemetry.noop]] is a
  * two-line implementation instead of a mock.
  */
trait Telemetry[F[_]] {

  /** A tracer named after the component that will emit the spans, e.g. `kui.gateway`. */
  def tracer(name: String): F[Tracer[F]]

  /** A meter, named the same way. */
  def meter(name: String): F[Meter[F]]
}

object Telemetry {

  /** The running telemetry system for one process, shut down cleanly when the resource closes.
    *
    * ==Why an exporter failure does not fail the process==
    *
    * Telemetry is never a startup dependency. If the OTLP collector is unreachable, or the SDK cannot be
    * configured at all, this returns [[noop]] rather than failing: a monitoring outage must not take KUI down
    * with it. That is a deliberate asymmetry — a configuration error fails the start (CFG-001), an
    * observability error does not — and it is what stops a Friday-evening collector restart from becoming a
    * KUI outage.
    *
    * ==Why `LiftIO`==
    *
    * otel4s's Java backend needs somewhere to keep the current span for the current fiber, and on cats-effect
    * that place is an `IOLocal`. Obtaining one requires being able to lift an `IO` into `F`, which `Async`
    * alone does not provide. Every KUI `app` runs on `IO`, so the constraint costs nothing in practice; it is
    * spelled out rather than hidden because a caller has to know.
    *
    * @param serviceName
    *   becomes `service.name` on every span and metric, which is how a trace search separates the gateway's
    *   spans from a service's
    */
  def resource[F[_]: {Async, LiftIO}](
      serviceName: String,
      config: TelemetryConfig
  ): Resource[F, Telemetry[F]] =
    OtelJava
      .autoConfigured[F](_.addPropertiesCustomizer(_ => properties(serviceName, config).asJava))
      .map(fromOtelJava[F])
      .handleErrorWith((_: Throwable) => Resource.pure[F, Telemetry[F]](noop[F]))

  /** Records nothing. Used in tests, and as the fallback when the SDK cannot be configured. */
  def noop[F[_]: Applicative]: Telemetry[F] =
    fromProviders(TracerProvider.noop[F], MeterProvider.noop[F])

  /** Wraps providers that already exist. This is the seam the suites use to hand in the testkit's in-memory
    * providers, so that what they assert against is the real recording path.
    */
  def fromProviders[F[_]](tracers: TracerProvider[F], meters: MeterProvider[F]): Telemetry[F] =
    new Telemetry[F] {
      def tracer(name: String): F[Tracer[F]] = tracers.get(name)
      def meter(name: String): F[Meter[F]] = meters.get(name)
    }

  private def fromOtelJava[F[_]](otel: OtelJava[F]): Telemetry[F] =
    fromProviders(otel.tracerProvider, otel.meterProvider)

  /** Translates `TelemetryConfig` into the properties the OpenTelemetry autoconfigure module reads.
    *
    * `addPropertiesCustomizer` is applied after the environment and the system properties, so these win. That
    * is the right way round: `kui.telemetry.*` is KUI's own configuration surface, and an operator who sets
    * it should not have to discover that a stray `OTEL_TRACES_EXPORTER` in the container image silently
    * outranks it.
    */
  private[observability] def properties(serviceName: String, config: TelemetryConfig): Map[String, String] = {
    val exporters = (config.otlpEndpoint, config.prometheusPort) match {
      case (Some(endpoint), Some(port)) =>
        Map(
          "otel.traces.exporter" -> "otlp",
          // Both: traces go to the collector, metrics are also scrapable locally.
          "otel.metrics.exporter" -> "otlp,prometheus",
          "otel.exporter.otlp.endpoint" -> endpoint.value,
          "otel.exporter.prometheus.port" -> port.value.toString
        )
      case (Some(endpoint), None) =>
        Map(
          "otel.traces.exporter" -> "otlp",
          "otel.metrics.exporter" -> "otlp",
          "otel.exporter.otlp.endpoint" -> endpoint.value
        )
      case (None, Some(port)) =>
        Map(
          "otel.traces.exporter" -> "none",
          "otel.metrics.exporter" -> "prometheus",
          "otel.exporter.prometheus.port" -> port.value.toString
        )
      case (None, None) =>
        Map("otel.traces.exporter" -> "none", "otel.metrics.exporter" -> "none")
    }

    exporters ++ Map(
      "otel.service.name" -> serviceName,
      // Logs go through Logback, not through the OpenTelemetry log pipeline (ADR-008). Exporting
      // them twice would double every line and double the bill.
      "otel.logs.exporter" -> "none"
    )
  }
}
