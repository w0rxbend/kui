# Spec: `prometheus-query-core`

Status: approved by the user on 2026-09-20
Capability map: [`CAPABILITY-MAP-prometheus-observability.md`](CAPABILITY-MAP-prometheus-observability.md)

## Objective

Add a production-safe internal client for the Prometheus HTTP query API without changing the meaning
or behavior of KUI's existing direct text-exposition scraper. The client is the sole transport and
normalization boundary used by later cluster, topic, and consumer metric catalogs.

This module succeeds when a configured `prometheus-api` source can execute bounded instant and range
queries through KUI's resilient HTTP stack; strictly decode Prometheus responses; preserve gaps,
warnings, timestamps, and freshness; coalesce/cache identical requests; and fail as typed KUI errors
without leaking credentials, PromQL, label values, URLs, or upstream response bodies.

### Assumptions carried from the approved capability map

1. `kind: prometheus` continues to mean a Prometheus text-exposition endpoint.
2. `kind: prometheus-api` explicitly selects `/api/v1/query` and `/api/v1/query_range`; KUI never
   guesses or falls back between protocols.
3. The browser never supplies PromQL, arbitrary start/end/step values, regexes, or label names.
4. Prometheus is optional. A missing or failing source degrades metric sections and never prevents KUI
   from starting or becoming ready.
5. Query results stay internal in this module. Public cluster/topic/consumer DTOs belong to their own
   capability modules.

## Tech stack

- Scala 3.9.0 and Java 21
- Cats Effect 3.7.1 / FS2
- sttp client 4.0.26 using the existing resilient `UpstreamClient`
- Circe 0.14.16 for explicit JSON decoding
- OTel4s 1.1.0 for bounded query telemetry
- MUnit Cats Effect for unit, concurrency, and live-loopback integration tests

No new runtime dependency or Prometheus client library is required. Prometheus's stable form-encoded
HTTP API is small enough to model directly, and a generic client would expose raw-query features this
product intentionally does not support.

Authoritative protocol references:

- <https://prometheus.io/docs/prometheus/latest/querying/api/>
- <https://prometheus.io/docs/prometheus/latest/querying/basics/>
- <https://prometheus.io/docs/operating/security/>
- <https://prometheus.io/docs/prometheus/latest/configuration/https/>

## Commands

Run from the repository root:

```bash
# Focused red/green loop
./mill libs.config.test
./mill libs.http.test
./mill libs.observability.test
./mill services.metrics.infrastructure.test
./mill services.metrics.app.test

# Compile and architectural boundaries
./mill '{libs.config.compile,libs.http.compile,services.metrics.infrastructure.compile,services.metrics.app.compile}'
./mill checkArchitecture

# Formatting and static checks
./mill __.checkFormat
./mill __.fix --check

# Final backend regression gate for this module
./scripts/run-tests.sh
```

This module adds no public route, so it does not regenerate OpenAPI or frontend schemas. Those gates
start with `cluster-observability`.

## Project structure

```text
libs/config/
  src/kui/config/MetricsConfig.scala
  src/kui/config/KuiConfigSource.scala
  src/kui/config/UpstreamAuthConfig.scala
  test/.../MetricsAndAlertsConfigSuite.scala
  test/.../UpstreamAuthConfigSuite.scala

libs/http/
  src/kui/http/upstream/UpstreamCredentials.scala
  src/kui/http/upstream/HttpTls.scala
  test/.../UpstreamCredentialsSuite.scala
  test/.../HttpTlsSuite.scala

services/metrics/infrastructure/
  src/kui/metrics/infrastructure/prometheus/PrometheusQueryClient.scala
  src/kui/metrics/infrastructure/prometheus/PrometheusProtocol.scala
  src/kui/metrics/infrastructure/prometheus/PrometheusQueryCache.scala
  src/kui/metrics/infrastructure/prometheus/PrometheusQueryMetrics.scala
  test/.../prometheus/PrometheusQueryClientSuite.scala
  test/.../prometheus/PrometheusProtocolSuite.scala
  test/.../prometheus/PrometheusQueryCacheSuite.scala

services/metrics/app/
  src/kui/metrics/app/MetricsWiring.scala
  test/src/kui/metrics/app/MetricsWiringSuite.scala
```

The exact filenames may be consolidated when a file would contain only a trivial forwarding type,
but responsibilities must remain in these layers. Prometheus JSON, URLs, authentication, caching, and
PromQL must not enter `metrics/domain`, `metrics/application`, public contracts, or frontend packages.

## Configuration contract

The new source is additive and existing configurations remain valid:

```yaml
kui:
  metrics:
    sources:
      production:
        kind: prometheus-api
        url: https://prometheus.example.net/prometheus
        callTimeout: 10s
        queryTimeout: 8s
        maxConcurrentQueries: 4
        maxSeriesPerQuery: 200
        maxPointsPerSeries: 600
        maxResponseBytes: 4194304
        maxCacheBytes: 67108864
        cacheTtl: 15s
        staleTtl: 2m
        auth:
          type: bearer
          token: env:KUI_PROMETHEUS_TOKEN
        tls:
          truststore:
            location: /etc/kui/prometheus-truststore.p12
            password: env:KUI_PROMETHEUS_TRUSTSTORE_PASSWORD
            type: PKCS12
          keystore:
            location: /etc/kui/prometheus-client.p12
            password: env:KUI_PROMETHEUS_KEYSTORE_PASSWORD
            keyPassword: env:KUI_PROMETHEUS_KEY_PASSWORD
            type: PKCS12
```

`auth.type` supports `none`, `basic`, `bearer`, and OAuth client credentials. Secret values use the
existing literal, `env:NAME`, and `file:/path` resolution. Adding bearer to the shared ADT does not
silently add bearer configuration to Connect, ksqlDB, or Schema Registry: their decoders retain an
explicit allowed-mechanism set. `tls` is optional; without it, HTTPS uses the JVM trust store with
hostname verification enabled. KUI provides no option to disable certificate or hostname verification.
HTTP TLS stores support JKS and PKCS#12 in this module; PEM is deferred until its certificate/private-key
pairing and secret-handling contract is specified.

Defaults and accepted bounds:

| Setting | Default | Accepted rule |
|---|---:|---|
| `callTimeout` | 10s | existing 1s–60s bounds |
| `queryTimeout` | 8s | 1s–55s and strictly less than `callTimeout` |
| `maxConcurrentQueries` | 4 | 1–32 |
| `maxSeriesPerQuery` | 200 | 1–1,000 |
| `maxPointsPerSeries` | 600 | 60–2,000 |
| `maxResponseBytes` | 4 MiB | 64 KiB–32 MiB |
| `maxCacheBytes` | 64 MiB | 4 MiB–512 MiB |
| `cacheTtl` | 15s | 1s–5m |
| `staleTtl` | 2m | at least `cacheTtl`, at most 30m |

Nested source blocks require fixing metrics-source member discovery across YAML, CLI, and environment
layers. The loader must discover the direct cluster member rather than treating `production-auth` or
`production-tls` as another cluster. Unknown keys and auth-mechanism surplus keys remain startup
errors. Query-only settings on `kind: prometheus` or `kind: jmx` are refused rather than ignored.

The URL is the Prometheus server base, including an optional reverse-proxy prefix but excluding
`/api/v1/query`. Query strings, fragments, and URLs already ending in `/api/v1/query` or
`/api/v1/query_range` are rejected. Authenticated sources and sources with TLS material require HTTPS;
anonymous HTTP remains available only where the existing deployment URL policy explicitly allows it.
The OAuth token endpoint also requires HTTPS. TLS stores and the SSL context are validated before the
listener starts; a malformed local security configuration fails startup, while an unreachable
Prometheus server remains runtime degradation. A custom trust store replaces rather than silently
augments JVM trust roots. `SafeUrl` continues to reject user-info and enforce KUI's upstream URL policy.

The existing `callTimeout < scrapeInterval` rule applies only to exposition sources. An on-demand
query source is instead governed by `queryTimeout < callTimeout` and has no relationship to the scrape
loop's interval.

## Internal interface

The provider boundary exposed to `kafka-metrics-catalog` is deliberately narrower than Prometheus:

```scala
trait PrometheusQueryClient[F[_]] {
  def probe(at: Instant): F[Either[KuiError, PrometheusProbe]]
  def instant(
      query: CompiledPromQuery,
      at: Instant
  ): F[Either[KuiError, QueryAnswer[InstantQueryResult]]]
  def range(
      query: CompiledPromQuery,
      from: Instant,
      to: Instant,
      step: FiniteDuration
  ): F[Either[KuiError, QueryAnswer[RangeQueryResult]]]
}
```

- `CompiledPromQuery` has no public/string constructor. Only server-owned catalog builders in
  `kui.metrics.infrastructure` can create it.
- Each query carries a validated, stable `QueryId` used for telemetry. The expression and resource
  values are never telemetry attributes or log fields.
- `probe` posts `vector(1)` with `limit=1`. It verifies the query protocol but does not participate in
  service readiness and is never retried automatically.
- Requests use form-encoded `POST`, an explicit `timeout=queryTimeout`, and `limit=maxSeriesPerQuery`.
- One captured, step-aligned `now` is supplied by the calling use case so related panels share axes.
- The cache key contains source identity, query ID, a one-way expression digest, aligned time range,
  and step. Raw PromQL and resource names are not retained in cache diagnostics.

`QueryAnswer` distinguishes fresh data, stale fallback data, and diagnostics. The decoder recognizes
all Prometheus result-type discriminators, but the client accepts `vector` for instant queries and
`matrix` for range queries only. `scalar`, `string`, mismatched vector/matrix, and native histogram
payloads return explicit unsupported-result failures rather than being silently coerced.

The HTTP client disables redirects. This avoids sending an Authorization header to a different
authority behind an upstream redirect and keeps every destination inside the resilient client's URL
policy. Operators configure the final Prometheus base URL directly.

## Protocol and correctness rules

1. Preserve Prometheus sample timestamps. Retrieval time is separate and never substituted for sample
   time.
2. Parse sample values from JSON strings. Finite values are measurements; `NaN`, `Inf`, `+Inf`, and
   `-Inf` are explicit non-finite ADT values that later mappings render as gaps with bounded diagnostics.
   Other malformed strings fail decoding.
3. Preserve successful-response `warnings` and `infos` as bounded counts/flags. Their text is neither
   returned to clients nor logged because it may contain expressions or label values.
4. Empty success is valid empty data, not an upstream failure and not measured zero.
5. Zero remains zero. Missing series and missing samples remain absent.
6. Reject excessive label count/bytes, duplicate canonical label sets, points beyond the configured
   bound, series or total samples beyond configured bounds, unexpected result types, non-monotonic or
   out-of-window timestamps, native histograms, malformed envelopes, and invalid sample tuples.
7. Apply the response-body bound at the HTTP backend/request layer before materializing the full body,
   then enforce structural bounds while decoding. A `Content-Length` check alone is insufficient.
8. Prometheus error strings and response bodies never enter a `KuiError` exposed to the browser.
9. Sample tuples contain exactly a finite Unix-seconds timestamp and a quoted value. Series timestamps
   are monotonic, and a range response cannot contain more than
   `floor((end - start) / step) + 1` samples per series.

HTTP outcomes map as follows:

| Outcome | Internal result |
|---|---|
| 2xx `success` | decoded result, retaining bounded warning/info diagnostics |
| 2xx `error` envelope or 400 | query/profile defect; no retry |
| 401/403 | upstream authentication failure |
| 404 | wrong base path or source kind |
| 422 | query execution failure; no retry |
| 429 | upstream overloaded; no immediate retry |
| 503 | upstream timeout/abort |
| other 5xx/network failure | existing typed upstream-unavailable/circuit behavior |
| timeout, oversized or malformed body | typed timeout or upstream-contract failure |

## Cache, concurrency, and freshness

- The resilient upstream uses the configured per-source bulkhead and zero retries. A timed-out query is
  not repeated automatically.
- Fresh results live for `cacheTtl`; they may serve as explicitly stale fallback until `staleTtl` after
  their observation/retrieval boundary.
- Only successful protocol results are cached, including empty successful results. Authentication,
  configuration, decode, and transport failures are never cached.
- Identical in-flight keys share one upstream request. Different keys execute concurrently up to the
  source bulkhead and must not serialize behind one global cache mutex.
- Cancelling one waiter does not cancel a request still needed by other waiters. The in-flight entry is
  always removed after success, failure, or cancellation of the shared load.
- Cache size is bounded by both 1,024 entries and `maxCacheBytes` per source. Entry weight accounts for
  decoded labels, samples, timestamps, and diagnostics so a handful of maximum-sized matrices cannot
  consume unbounded heap.
- Stale fallback is eligible only for transient transport, timeout, 429, or 5xx failures. It must not hide
  authentication, malformed-response, source-kind, or query-profile defects.

## Telemetry and logging

Add logical-query self-observability outside the cache/coalescer and generic `UpstreamClient`, so a
cache hit, coalesced waiter, circuit refusal, timeout, or stale fallback is recorded exactly once per
caller. Measure query duration/count, response bytes, returned series/samples, in-flight requests,
cache outcome, coalesced waiters, rejected limits, and warning/info counts. Allowed dimensions are
stable source ID, stable query ID, operation (`instant` or `range`), and small outcome/cache-state/limit
enums.

Never use PromQL, source URLs, topic names, group names, arbitrary Prometheus labels, response text,
or credential material as telemetry labels or log values. Logs are emitted for startup configuration
summary and circuit transitions, not once per failed poll. Circuit events retain a bounded failure
class, never a raw `Throwable.getMessage`, because transport exceptions commonly contain hosts or URLs.
Every secret-bearing type must preserve redacted `toString` behavior.

## Code style

Use explicit ADTs and total matching at untrusted boundaries. Keep parsing pure and effects at the
transport/cache edge:

```scala
enum PrometheusResult {
  case Matrix(series: Vector[RangeSeries])
  case Vector(series: Vector[InstantSeries])
}

enum SampleValue {
  case Finite(value: Double)
  case NonFinite(kind: NonFiniteKind)
}

def sampleValue(raw: String): Either[DecodeProblem, SampleValue] =
  raw match {
    case "NaN"            => Right(SampleValue.NonFinite(NonFiniteKind.NaN))
    case "Inf" | "+Inf" => Right(SampleValue.NonFinite(NonFiniteKind.PositiveInfinity))
    case "-Inf"           => Right(SampleValue.NonFinite(NonFiniteKind.NegativeInfinity))
    case value             => value.toDoubleOption.filter(_.isFinite).toRight(DecodeProblem.InvalidNumber(value)).map(SampleValue.Finite(_))
  }
```

Names describe domain meaning (`maxSeriesPerQuery`, `staleUntil`, `queryId`) rather than mechanics.
Methods stay small enough that request construction, envelope decoding, limit enforcement, caching,
and error mapping are independently testable.

## Testing strategy

Follow red-green-refactor for each behavior.

### Configuration tests

- Existing exposition-only configurations decode identically.
- `prometheus-api` defaults and every lower/upper bound.
- `queryTimeout < callTimeout`, `staleTtl >= cacheTtl`, and query-only-key enforcement.
- Nested YAML, CLI, and environment discovery selects exactly the intended cluster.
- Anonymous, Basic, bearer, and OAuth auth; missing/surplus/unresolved secret failures.
- Existing non-metrics HTTP decoders do not start accepting bearer merely because the shared ADT does.
- Default TLS, custom trust store replacement, mTLS, invalid stores, and mandatory hostname verification.
- TLS material on plain HTTP and partial mTLS credentials are rejected.
- Query strings/fragments/API endpoint suffixes are rejected and reverse-proxy base paths are preserved.
- URL user-info/private-address policy and reverse-proxy prefixes.

### Pure protocol tests

Use committed JSON fixtures for matrix, vector, scalar, string, empty success, warnings/infos,
Prometheus error envelopes, native histograms, non-finite values, malformed numbers/timestamps,
duplicate/excessive labels, excessive series/points, and boundary-sized bodies. Fixtures contain no
real credentials or production names.

### Client integration tests

Use sttp `BackendStub` for request-shape/error mapping and a loopback HTTP server for behavior a stub
cannot prove: form encoding, reverse-proxy paths, Basic/bearer/OAuth headers, disabled redirects,
response streaming limits, timeouts, TLS trust, hostname verification, and mTLS. Extract or reuse the
incremental capped-input-stream precedent in `KsqlHttp` instead of buffering with `asStringAlways`.
Assert that errors and captured logs omit the token, URL, query expression, labels, and body.

### Deterministic concurrency tests

Use `Deferred`, `Ref`, and Cats Effect test time to prove one load for identical concurrent keys,
parallel loads for different keys up to the bulkhead, waiter cancellation, fresh expiry, allowed stale
fallback, forbidden stale fallback, cleanup after failure/cancellation, and the 1,024-entry bound.

### Wiring/regression tests

- No query client, HTTP pool, probe, or background fiber when no query source is configured.
- Exposition sources still build their existing scrape loop and buffer unchanged.
- Query sources build an on-demand client and no scrape loop/buffer. Each query source initially owns
  its TLS-configured transport; transport pooling by non-secret TLS fingerprint is a measured follow-up,
  not a correctness shortcut in this module.
- A query source that is down does not fail service startup or readiness.
- Startup/capability descriptions distinguish exposition, query, and unsupported JMX sources without
  disclosing addresses or credentials.

## Boundaries

### Always

- Keep the existing exposition path byte-for-byte compatible at the public API boundary.
- Use `SafeUrl`, secret references, `UpstreamClient`, bounded POST requests, typed errors, and zero retry.
- Enforce limits before mapping results into later domain DTOs.
- Add tests before behavior and inspect the dirty worktree before editing shared files.

### Ask first

- Adding a runtime dependency or changing an existing public metrics endpoint.
- Broadening private-upstream policy beyond the already explicit deployment-level mechanism.
- Making cache/query limits unbounded or operator-supplied outside the documented ranges.
- Changing shared auth behavior for existing Connect, ksqlDB, or Schema Registry deployments.

### Never

- Accept raw PromQL, regexes, label names, arbitrary time bounds, or arbitrary steps from a browser.
- Auto-detect source protocol or silently fall back between query and exposition modes.
- Disable certificate/hostname verification in production.
- Log or return credentials, source URLs, PromQL, response bodies, topic/group values, or arbitrary labels.
- Turn missing, stale, unsupported, non-finite, or malformed values into zero.
- Make Prometheus a startup/readiness dependency.

## Success criteria

- [ ] Existing `kind: prometheus` tests and quickstart configuration retain scrape behavior.
- [ ] `prometheus-api` configuration, auth, TLS, bounds, and cross-field validation are covered for YAML,
      CLI, and environment sources.
- [ ] `probe`, instant query, and range query issue correctly encoded bounded POST requests against a base
      URL with or without a path prefix.
- [ ] Every documented Prometheus response/result form and HTTP outcome has an explicit tested mapping.
- [ ] Response bytes, series, samples, points, labels, concurrency, duration, cache entry count/weight,
      and staleness are all bounded and exercised at and beyond their limits.
- [ ] Coalescing and caching tests prove unrelated queries do not serialize and stale fallback cannot hide
      authentication/configuration/contract defects.
- [ ] Telemetry uses only bounded source/query/outcome dimensions, and leak tests cover logs and errors.
- [ ] Redirects are disabled, circuit failure reasons are sanitized, and canary secrets/URLs/queries never
      appear in errors, logs, spans, or metric attributes.
- [ ] The metrics service starts and stays ready with Prometheus absent or failing.
- [ ] Focused suites, architecture, formatting, and the full backend regression suite pass.

## Open questions

Approval of this spec accepts the following recommended choices:

1. `prometheus-api` is the wire spelling.
2. One URL is configured per source; Prometheus HA remains behind an operator-managed frontend/load
   balancer until a concrete multi-URL requirement exists.
3. The Prometheus server may use custom trust/mTLS. An OAuth issuer uses JVM system trust in this first
   slice; issuer-specific TLS requires a separately named future contract.
4. The existing hostname/DNS-rebinding limitation of KUI's shared `SafeUrl` policy remains documented as
   a deployment-egress concern. Query-core disables redirects and accepts only trusted operator
   configuration; changing the JVM client's DNS resolver safely is a cross-product hardening initiative,
   not a Prometheus-only implementation hidden in this module.

Later catalog and UI specs will choose metric profiles, PromQL, recording rules, public DTOs, panel
composition, and page polling behavior; those decisions are outside this module.
