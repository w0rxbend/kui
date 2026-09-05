# ADR-009 — otel4s (oteljava backend) for traces and metrics

- Status: Accepted
- Date: 2026-09-03

## Context

Kafbat uses Micrometer for application metrics, a Prometheus client for cluster metrics
exposition and no distributed tracing. KUI requires traces, metrics and correlated logs from
day one across several processes.

## Decision

- **otel4s 1.1.0** with the **`otel4s-oteljava`** backend (Java OpenTelemetry SDK 1.65.0,
  `sdk-extension-autoconfigure`) in every JVM deployable; `otel4s-core` in libraries.
- Export: OTLP (`opentelemetry-exporter-otlp`) for traces and metrics; Prometheus scrape
  endpoint through `opentelemetry-exporter-prometheus` **1.65.0-alpha** (the only Java-SDK
  Prometheus exporter; the alpha suffix is accepted and tracked in TECH_DEBT).
- Tapir server interceptors `tapir-otel4s-tracing` and `tapir-opentelemetry-metrics` applied by
  `libs/observability`; the sttp client factory adds client spans and propagates
  `traceparent`.
- Metric names and span names follow the project's naming conventions plus the additions in `ARCHITECTURE.md` §13.
- The Kafka cluster metrics that Kafbat exposes at `/metrics/{cluster}` are a product feature
  of `kui-metrics-service` (its own exposition), separate from application telemetry.
- Prometheus Pushgateway parity is a documented gap; metrics push uses OTLP.

## Evidence

- `research/scala/ecosystem-mapping.md` F4 (otel4s 1.1.0, SDK 0.19.x line separate,
  Prometheus exporter alpha status, Pushgateway mapping).
- `research/kafbat/architecture.md` F12 (Kafbat's metrics pipeline and exposition).

## Consequences

- Two Prometheus-format endpoints exist with different purposes: application telemetry
  (otel exporter, `/telemetry/prometheus` on each process) and Kafka cluster metrics
  (`/metrics`, metrics-service).
- The pure-Scala `otel4s-sdk` (0.19.x) is not used on the JVM. The reason once given for keeping
  it in view — a Scala.js frontend that could share it — is gone with ADR-048; browser telemetry,
  if it is ever wanted, would be a JavaScript OpenTelemetry SDK.

## Alternatives rejected

- Micrometer: Java-first, not effect-aware; no tracing.
- `otel4s-sdk` + `otel4s-sdk-exporter-prometheus` 0.19.2: pre-1.0 SDK line; kept as the
  fallback if the alpha Java exporter proves unstable.

## Reversibility

High. Exporters are runtime wiring; the otel4s API is stable at 1.x.
