# Capability Map: Prometheus-backed Kafka observability

## Objective

Let KUI query a configured Prometheus-compatible API for bounded historical Kafka metrics and use
those facts to deliver richer cluster, topic, and consumer-group views. Preserve the existing direct
Prometheus-exposition scrape path, keep Kafka Admin authoritative for current broker/group state,
and degrade one metric family or panel without failing an entire page.

| Module id | Responsibility | Depends on |
|---|---|---|
| `prometheus-query-core` | Explicit Prometheus API source configuration; authenticated/TLS HTTP query client; strict response decoding; fixed range/step budgets; request coalescing, bounded caching, stale fallback, and query telemetry | — |
| `kafka-metrics-catalog` | Canonical Kafka metric model and server-owned PromQL templates; source profiles for broker JMX metrics, SoftwareMill klag-exporter, legacy Kafka Lag Exporter, and kafka_exporter; capability and freshness reporting | `prometheus-query-core` |
| `cluster-observability` | Drive the existing five cluster metric contracts from either exposition samples or Prometheus queries, then extend the cluster UI with broker health and traffic/error/storage infographics | `kafka-metrics-catalog` |
| `topic-observability` | Typed, permission-aware topic metrics APIs and a lazy topic Metrics view for traffic, rate, partition activity, and carefully labelled consumer-lag summaries | `kafka-metrics-catalog` |
| `consumer-observability` | Typed, permission-aware consumer-group history APIs and UI for offset lag, lag trend, time lag when available, throughput derivatives, retention risk, collection freshness, and data-loss signals | `kafka-metrics-catalog` |
| `observability-testbed` | Quickstart Prometheus and lag-exporter containers, scrape/recording-rule configuration, seeded activity, failure fixtures, browser E2E, accessibility/performance checks, and backend-log verification | `cluster-observability`, `topic-observability`, `consumer-observability` |

Build order: `prometheus-query-core` → `kafka-metrics-catalog` →
`cluster-observability`, `topic-observability`, `consumer-observability` →
`observability-testbed`.

The three UI capabilities may be implemented concurrently after the catalog contract is stable.
The cluster slice goes live first to prove parity with the current exposition-backed dashboard before
topic and consumer views rely on the query path.

## Boundary decisions for review

- `kind: prometheus` retains its current meaning: direct Prometheus text exposition. A distinct,
  explicit source kind selects the Prometheus HTTP query API; there is no protocol auto-detection.
- PromQL is compiled exclusively from versioned server-owned templates. Browser requests select a
  known range and resource, never provide PromQL, regex matchers, step sizes, or arbitrary labels.
- Topic and consumer services remain broker-authoritative and do not call Prometheus. Prometheus
  integration stays inside `services/metrics`; the gateway forwards its typed contracts.
- Kafka Admin remains authoritative for current group state, assignments, and committed offsets.
  Prometheus adds history, trends, rates, exporter freshness, and exporter-only signals.
- The primary lag profile is SoftwareMill `klag-exporter`; common `kafka_exporter` names and the
  archived legacy Kafka Lag Exporter remain supported profiles. Unsupported metrics are absent or
  marked unsupported, never represented as zero.
- Every query has server-controlled range, step, timeout, concurrency, response-byte, series, sample,
  and label limits. Resource names are escaped as exact label values and are never logged with raw
  PromQL, credentials, response bodies, or upstream URLs.
- Backend responses expose typed, normalized measurements and diagnostic state. Frontends own page
  composition and accessible chart copy; Prometheus metric names and arbitrary labels are not a UI API.
- Each panel retains explicit loading, measured-zero, missing, stale, unsupported, unavailable, and
  permission-denied behavior. Historical gaps stay gaps rather than being coerced to zero.

## Approval

Approved by the user on 2026-09-20. Module specifications and implementation proceed in the build
order above; any later boundary or dependency change must update this map before code follows it.
