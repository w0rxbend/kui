# Consumer filtering worklist

- [x] Inventory architecture, current filter behavior, test coverage, and baseline failures.
- [x] Run focused backend baseline (223 tests green).
- [x] Add failing backend regressions for scan-vs-match limit and filter reference validation.
- [x] Implement backend correctness fixes and rerun focused suites.
- [x] Add failing frontend regressions for malformed SSE, stale sessions, and request snapshot races.
- [x] Implement frontend reliability fixes and render filter evaluation warnings.
- [x] Add JSONPath-style path parser/compiler tests.
- [x] Implement field-filter builder and focused UI tests.
- [x] Add budget/security hardening with regression tests.
- [x] Build and start current-source containers.
- [x] Run browser E2E against live JSON/string/schema-backed topics.
- [x] Inspect backend/container logs and fix reproducible errors.
- [x] Run final focused/full verification and document any pre-existing blockers.
- [x] Perform final multi-axis code review.

## Manual QA environment

- [x] Audit supported Docker modes, ports, credentials, capabilities, and coexistence constraints.
- [x] Add 2px spacing between adjacent message action buttons.
- [x] Add tested Copy headers / Copy value / Copy all controls to expanded records.
- [x] Make the partitions-in-sync gauge responsive in narrow stat cards.
- [x] Add real JSON Schema and Protobuf registry fixtures with nested filterable fields.
- [x] Extend Playwright coverage across JSON, String, Avro, JSON Schema, and Protobuf topics.
- [x] Add a unified manual-QA launcher and runbook with bounded logs and readiness checks.
- [x] Build current backend/frontend images and restart the primary quickstart stack.
- [x] Launch and verify compatible secondary manual-test modes.
- [x] Exercise the live copy controls and field filters in Chromium.
- [x] Inspect final backend, registry, Kafka, and frontend logs.

## Offset-relative message pagination

- [x] Reproduce page-boundary ordering and define the session page contract with failing tests.
- [x] Add offset-relative previous/next pagination as the default message view.
- [x] Add opt-in infinite scroll with near-end preloading and a manual fallback.
- [x] Cover mode switching, cached previous pages, duplicate-load prevention, and multi-partition labels.
- [x] Rebuild the live frontend, run browser E2E, and inspect backend/frontend logs.

---

# Prometheus query core worklist

Status: approved on 2026-09-20
Specification: [`../SPEC-prometheus-query-core.md`](../SPEC-prometheus-query-core.md)
Plan: [`plan.md`](plan.md#implementation-plan-prometheus-query-core)

## Phase 0: Restore a trustworthy baseline

### PQ-00 — Restore documentation required by existing contract tests

- [x] Restore the last valid operator observability, masking and design-token pages removed before this
  initiative.
  - Acceptance: Existing metric/masking/design contracts have their required tables, pairs and figures;
    no product behavior or test expectation is weakened; stale roadmaps and the feature matrix stay removed.
  - Verify:
    `./mill libs.observability.test.testOnly kui.observability.MetricNamesSuite` and
    `./mill libs.config.test.testOnly kui.config.MaskingConfigSuite`, and `./mill build-tests.test`.
  - Dependencies: None.
  - Files: `docs/operations/observability.md`, `docs/operations/masking.md`,
    `docs/frontend/tokens.md`.
  - Scope: Medium (3 files).

### PQ-01 — Add only the query-core build edges

- [x] Give metrics infrastructure/tests the already-pinned Circe, Cats Effect, sttp/FS2 and OTel
  capabilities actually used by the implementation.
  - Acceptance: No new library/version is introduced; the unrelated existing build-info diff is preserved;
    metrics infrastructure/app compile.
  - Verify:
    `./mill '{services.metrics.infrastructure.compile,services.metrics.infrastructure.test.compile,services.metrics.app.compile}'`
    and `git diff --check -- build.mill`.
  - Dependencies: PQ-00.
  - Files: `build.mill`.
  - Scope: Extra small (1 shared dirty file; primary-agent ownership only).

## Phase 1: Freeze the configuration contract

### PQ-02 — Correct nested metrics-source member discovery

- [x] Make nested YAML, CLI and environment source keys resolve to one direct cluster member.
  - Acceptance: Auth/TLS leaves cannot invent `production-auth`-style sources; dashed/underscored IDs and
    precedence remain intact; orphaned nested settings identify the actual source ID.
  - Verify: `./mill libs.config.test` and `./mill libs.config.compile`.
  - Dependencies: PQ-01.
  - Files: `libs/config/src/kui/config/KuiConfigSource.scala`,
    `libs/config/test/src/kui/config/MetricsAndAlertsConfigSuite.scala`.
  - Scope: Small (2 files; config-lane ownership).

### PQ-03 — Add the Prometheus API source and query budgets

- [x] Add `prometheus-api` and every approved bounded query/cache setting without changing exposition.
  - Acceptance: Omitted/`prometheus` remains exposition; exact defaults/minima/maxima are tested;
    query-only keys are rejected elsewhere; query timeout is below call timeout; scrape timeout rules remain
    exposition-only.
  - Verify:
    `./mill libs.config.test.testOnly kui.config.MetricsAndAlertsConfigSuite` and
    `./mill libs.config.compile`.
  - Dependencies: PQ-02.
  - Files: `libs/config/src/kui/config/MetricsConfig.scala`,
    `libs/config/src/kui/config/KuiConfigSource.scala`,
    `libs/config/test/src/kui/config/MetricsAndAlertsConfigSuite.scala`.
  - Scope: Medium (3 files; config-lane ownership).

### PQ-04 — Add bearer auth without widening existing products

- [x] Extend shared upstream auth and make decoding consumer-aware.
  - Acceptance: Bearer material is a redacted `Secret`; Prometheus may select it later; Connect, ksqlDB and
    Schema Registry retain exactly their current configurable mechanisms; all matches remain exhaustive;
    missing/surplus/unresolved secrets fail without disclosure.
  - Verify:
    `./mill libs.config.test`,
    `./mill services.connect.infrastructure.test.testOnly kui.connect.infrastructure.ConnectCredentialsSuite`,
    and `./mill services.ksql.infrastructure.test.testOnly kui.ksql.infrastructure.KsqlCredentialsSuite`.
  - Dependencies: PQ-03.
  - Files: `libs/config/src/kui/config/UpstreamAuthConfig.scala`,
    `libs/config/src/kui/config/KuiConfigSource.scala`,
    `services/connect/infrastructure/src/kui/connect/infrastructure/ConnectCredentials.scala`,
    `services/ksql/infrastructure/src/kui/ksql/infrastructure/KsqlCredentials.scala`,
    `libs/config/test/src/kui/config/UpstreamAuthConfigSuite.scala`.
  - Scope: Medium (5 files; config-lane ownership).

### PQ-05 — Attach auth to query sources

- [x] Decode anonymous, Basic, bearer and OAuth authentication only for `prometheus-api` sources.
  - Acceptance: Omitted auth is anonymous; all source layers work; exposition/JMX reject auth keys; OAuth
    endpoints require HTTPS; malformed auth prevents startup rather than creating a partial source.
  - Verify:
    `./mill libs.config.test.testOnly kui.config.MetricsAndAlertsConfigSuite` and
    `./mill libs.config.compile`.
  - Dependencies: PQ-04.
  - Files: `libs/config/src/kui/config/MetricsConfig.scala`,
    `libs/config/src/kui/config/KuiConfigSource.scala`,
    `libs/config/test/src/kui/config/MetricsAndAlertsConfigSuite.scala`.
  - Scope: Medium (3 files; config-lane ownership).

### PQ-06 — Define TLS and URL security rules

- [x] Add HTTP JKS/PKCS#12 trust/mTLS configuration and validate the Prometheus base URL.
  - Acceptance: JVM trust is the default; location XOR inline store material; secrets are redacted; hostname
    verification cannot be disabled; partial/PEM/unknown/insecure configurations fail; query/fragment/userinfo
    and API endpoint suffixes fail; reverse-proxy paths work; auth/TLS require HTTPS.
  - Verify: `./mill libs.config.test`, `./mill libs.config.compile`, and `./mill checkArchitecture`.
  - Dependencies: PQ-05.
  - Files: `libs/config/src/kui/config/HttpTlsConfig.scala`,
    `libs/config/src/kui/config/MetricsConfig.scala`,
    `libs/config/src/kui/config/KuiConfigSource.scala`,
    `libs/config/test/src/kui/config/HttpTlsConfigSuite.scala`.
  - Scope: Medium (4 files; config-lane ownership).

### Checkpoint C1 — Configuration API freeze

- [x] Config and compatibility gates are green before parallel consumers use the new types.
  - Verify: `./mill libs.config.test`, `./mill checkArchitecture`, `./mill __.checkFormat`,
    and `./mill __.fix --check`.
  - Dependencies: PQ-02 through PQ-06.

## Phase 2: Secure reusable HTTP foundations

After C1, PQ-07/PQ-08, PQ-09/PQ-10, PQ-11 and PQ-12 are separate agent lanes. They must not edit one
another's files.

### PQ-07 — Implement static request credentials

- [x] Add shared anonymous, Basic and bearer request authentication.
  - Acceptance: Exactly one correct header is applied; caller-supplied headers cannot duplicate credentials;
    secrets never appear in rendering, errors or diagnostics; the API depends only on shared config.
  - Verify: `./mill libs.http.test.testOnly kui.http.upstream.UpstreamCredentialsSuite`.
  - Dependencies: C1.
  - Files: `libs/http/src/kui/http/upstream/UpstreamCredentials.scala`,
    `libs/http/test/src/kui/http/upstream/UpstreamCredentialsSuite.scala`.
  - Scope: Small (2 files; HTTP-auth lane).

### PQ-08 — Add bounded OAuth credential acquisition

- [x] Add client-credentials form requests with cached, coalesced refresh.
  - Acceptance: Issuer redirects are disabled; JVM trust is used; concurrent refresh performs one call;
    expiry/malformed/non-2xx/timeout paths are typed; client/token/body canaries never leak.
  - Verify: `./mill libs.http.test.testOnly kui.http.upstream.UpstreamCredentialsSuite`.
  - Dependencies: PQ-07.
  - Files: `libs/http/src/kui/http/upstream/UpstreamCredentials.scala`,
    `libs/http/test/src/kui/http/upstream/UpstreamCredentialsSuite.scala`.
  - Scope: Small (2 files; HTTP-auth lane).

### PQ-09 — Build TLS contexts and source-owned transports

- [x] Construct default/custom-trust/mTLS sttp transports from validated stores.
  - Acceptance: Custom trust replaces system trust; JKS/PKCS#12 work from path/inline material; invalid
    base64/files/passwords/stores/keys fail with sanitized errors; resources close deterministically.
  - Verify: `./mill libs.http.test.testOnly kui.http.upstream.HttpTlsSuite`.
  - Dependencies: C1.
  - Files: `libs/http/src/kui/http/upstream/HttpTls.scala`,
    `libs/http/test/src/kui/http/upstream/HttpTlsSuite.scala`,
    `libs/http/test/src/kui/http/upstream/HttpsUpstreamFixture.scala`.
  - Scope: Medium (3 files; HTTP-TLS lane).

### PQ-10 — Prove hostname validation and mTLS handshakes

- [x] Exercise the TLS transport against a real local HTTPS server.
  - Acceptance: Configured CA succeeds while default trust fails; wrong hostname fails; required client cert
    fails without and succeeds with the keystore; JKS/PKCS#12 paths work; no bypass exists.
  - Verify: `./mill libs.http.test.testOnly kui.http.upstream.HttpTlsSuite`.
  - Dependencies: PQ-09.
  - Files: `libs/http/src/kui/http/upstream/HttpTls.scala`,
    `libs/http/test/src/kui/http/upstream/HttpTlsSuite.scala`.
  - Scope: Small (2 files; HTTP-TLS lane).

### PQ-11 — Extract incremental bounded response reading

- [x] Reuse one streamed byte-cap primitive for Prometheus and existing KSQL behavior.
  - Acceptance: Exactly-at-limit succeeds; one byte over fails without retaining the excess; KSQL's existing
    capped-query behavior is unchanged; `Content-Length` is only an early hint.
  - Verify:
    `./mill libs.http.test.testOnly kui.http.upstream.BoundedResponseSuite` and
    `./mill services.ksql.infrastructure.test.testOnly kui.ksql.infrastructure.KsqlHttpSuite`.
  - Dependencies: PQ-01.
  - Files: `libs/http/src/kui/http/upstream/BoundedResponse.scala`,
    `libs/http/test/src/kui/http/upstream/BoundedResponseSuite.scala`,
    `services/ksql/infrastructure/src/kui/ksql/infrastructure/KsqlHttp.scala`,
    `services/ksql/infrastructure/test/src/kui/ksql/infrastructure/KsqlHttpSuite.scala`.
  - Scope: Medium (4 files; bounded-response lane).

### PQ-12 — Sanitize circuit failure diagnostics

- [x] Replace raw throwable messages in circuit events with bounded failure categories.
  - Acceptance: Hosts, URLs, PromQL, bodies and credentials cannot enter transition logs; circuit state,
    cancellation and breaker behavior remain unchanged.
  - Verify:
    `./mill libs.http.test.testOnly kui.http.upstream.CircuitBreakerSuite` and
    `./mill libs.http.test.testOnly kui.http.upstream.UpstreamClientSuite`.
  - Dependencies: PQ-01.
  - Files: `libs/http/src/kui/http/upstream/CircuitBreaker.scala`,
    `libs/http/test/src/kui/http/upstream/CircuitBreakerSuite.scala`,
    `libs/http/test/src/kui/http/upstream/UpstreamClientSuite.scala`.
  - Scope: Medium (3 files; diagnostics lane).

### Checkpoint C2 — Secure HTTP API freeze

- [x] Shared HTTP foundations and unaffected upstream integrations are green.
  - Verify: `./mill libs.http.test`, `./mill services.ksql.infrastructure.test`,
    `./mill services.connect.infrastructure.test`, and `./mill checkArchitecture`.
  - Dependencies: PQ-07 through PQ-12.

## Phase 3: Prometheus protocol, cache and telemetry

PQ-13 starts the internal contract. After it, the decoder lane, cache lane and telemetry lane can proceed
concurrently with exclusive ownership of their listed files.

### PQ-13 — Define query and result ADTs

- [x] Add restricted compiled queries, stable IDs, typed instant/range inputs and result/freshness values.
  - Acceptance: Range/step/alignment/expected-point validation occurs before I/O; timestamps are safely
    convertible; non-finite values are explicit; raw expression construction remains infrastructure-private.
  - Verify:
    `./mill services.metrics.infrastructure.test.testOnly kui.metrics.infrastructure.prometheus.PrometheusProtocolSuite`.
  - Dependencies: PQ-03.
  - Files:
    `services/metrics/infrastructure/src/kui/metrics/infrastructure/prometheus/PrometheusProtocol.scala`,
    `services/metrics/infrastructure/test/src/kui/metrics/infrastructure/prometheus/PrometheusProtocolSuite.scala`.
  - Scope: Small (2 files; protocol-contract ownership).

### PQ-14 — Decode valid Prometheus responses

- [x] Decode instant vectors and range matrices from strict success envelopes.
  - Acceptance: Timestamps, real zero, empty success, bounded warning/info counts and legal non-finite kinds
    are preserved; unknown nonessential fields remain forward-compatible.
  - Verify:
    `./mill services.metrics.infrastructure.test.testOnly kui.metrics.infrastructure.prometheus.PrometheusResponseDecoderSuite`.
  - Dependencies: PQ-13.
  - Files:
    `services/metrics/infrastructure/src/kui/metrics/infrastructure/prometheus/PrometheusResponseDecoder.scala`,
    `services/metrics/infrastructure/test/src/kui/metrics/infrastructure/prometheus/PrometheusResponseDecoderSuite.scala`,
    `services/metrics/infrastructure/test/resources/prometheus/success.json`,
    `services/metrics/infrastructure/test/resources/prometheus/nonfinite.json`.
  - Scope: Medium (4 files; decoder lane).

### PQ-15 — Enforce decoder limits and rejection mappings

- [x] Reject every malformed, unsupported or excessive response shape safely.
  - Acceptance: Envelopes/tuples/timestamps, scalar/string/mismatched results, histograms, duplicate series,
    non-monotonic/out-of-window points and all label/series/sample limits have boundary tests; upstream error
    and fixture canaries never enter returned errors.
  - Verify:
    `./mill services.metrics.infrastructure.test.testOnly kui.metrics.infrastructure.prometheus.PrometheusResponseDecoderSuite`.
  - Dependencies: PQ-14.
  - Files: the decoder and suite from PQ-14 plus
    `services/metrics/infrastructure/test/resources/prometheus/errors.json`,
    `services/metrics/infrastructure/test/resources/prometheus/unsupported.json`,
    `services/metrics/infrastructure/test/resources/prometheus/limits.json`.
  - Scope: Medium (5 files; decoder lane).

### PQ-16 — Implement weighted per-key cache and stale coalescing

- [x] Add bounded query storage and one shared load per canonical key.
  - Acceptance: Same keys coalesce; different keys run concurrently; 1,024-entry and decoded-weight bounds
    hold; virtual time proves fresh expiry, transient-only stale fallback, forbidden fallback, eviction,
    waiter/shared-load cancellation, release and in-flight cleanup.
  - Verify:
    `./mill services.metrics.infrastructure.test.testOnly kui.metrics.infrastructure.prometheus.PrometheusQueryCacheSuite`.
  - Dependencies: PQ-13.
  - Files:
    `services/metrics/infrastructure/src/kui/metrics/infrastructure/prometheus/PrometheusQueryCache.scala`,
    `services/metrics/infrastructure/test/src/kui/metrics/infrastructure/prometheus/PrometheusQueryCacheSuite.scala`.
  - Scope: Medium (2 files; cache lane).

### PQ-17 — Add bounded logical-query telemetry

- [x] Instrument logical duration/count, response size, series/samples, in-flight, cache/coalescing, limits
  and diagnostics.
  - Acceptance: Only stable source/query IDs and bounded enums are attributes; testkit inspection finds no
    expression, URL, labels, resource names, body or credentials; the restored operator metric table agrees.
  - Verify:
    `./mill services.metrics.infrastructure.test.testOnly kui.metrics.infrastructure.prometheus.PrometheusQueryMetricsSuite`
    and `./mill libs.observability.test.testOnly kui.observability.MetricNamesSuite`.
  - Dependencies: PQ-13 and PQ-00.
  - Files: `libs/observability/src/kui/observability/MetricNames.scala`,
    `libs/observability/test/src/kui/observability/MetricNamesSuite.scala`,
    `services/metrics/infrastructure/src/kui/metrics/infrastructure/prometheus/PrometheusQueryMetrics.scala`,
    `services/metrics/infrastructure/test/src/kui/metrics/infrastructure/prometheus/PrometheusQueryMetricsSuite.scala`,
    `docs/operations/observability.md`.
  - Scope: Medium (5 files; telemetry lane).

### Checkpoint C3 — Protocol foundations green

- [x] Protocol, cache and telemetry suites pass together without cross-lane file collisions.
  - Verify: `./mill libs.observability.test`, `./mill services.metrics.infrastructure.test`,
    `./mill checkArchitecture`, and `./mill __.checkFormat`.
  - Dependencies: PQ-13 through PQ-17.

## Phase 4: Client and composition integration

Integration tasks are sequential because they converge on the same client and wiring contracts.

### PQ-18 — Implement the bounded Prometheus HTTP client

- [x] Implement probe, instant and range form-encoded POST calls through resilient/authenticated transport.
  - Acceptance: Reverse-proxy paths, explicit timeout/limit, zero retry, credentials, disabled redirects and
    pre-decode body caps are proven; 2xx errors, 400/401/403/404/422/429/503, network failure, timeout and
    malformed/oversized answers map safely.
  - Verify:
    `./mill services.metrics.infrastructure.test.testOnly kui.metrics.infrastructure.prometheus.PrometheusQueryClientSuite`.
  - Dependencies: C2, PQ-15, and PQ-13.
  - Files:
    `services/metrics/infrastructure/src/kui/metrics/infrastructure/prometheus/PrometheusQueryClient.scala`,
    `services/metrics/infrastructure/test/src/kui/metrics/infrastructure/prometheus/PrometheusQueryClientSuite.scala`,
    `services/metrics/infrastructure/test/src/kui/metrics/infrastructure/prometheus/PrometheusTestServer.scala`.
  - Scope: Medium (3 files; integration lane).

### PQ-19 — Compose client, cache, stale policy and telemetry

- [x] Wrap physical calls with canonical caching/coalescing and exactly-once logical telemetry.
  - Acceptance: Each hit/miss/coalesced/circuit/timeout/stale outcome records once per caller; only
    transport/timeout/429/5xx failures may serve stale; auth/config/profile/contract failures cannot.
  - Verify: Run `PrometheusQueryClientSuite`, `PrometheusQueryCacheSuite`, and
    `PrometheusQueryMetricsSuite` through their `testOnly` commands.
  - Dependencies: PQ-16, PQ-17, and PQ-18.
  - Files: the three production files and client/cache suites from PQ-16 through PQ-18 (5 files total).
  - Scope: Medium (5 files; integration lane).

### PQ-20 — Wire source-owned query clients

- [x] Construct query clients only for API sources and retain exposition collectors unchanged.
  - Acceptance: Query sources have one TLS-configured on-demand client and no scrape loop/buffer; no source
    creates no pool; unavailable Prometheus does not fail startup/readiness; shutdown releases clients/loads;
    startup/capability descriptions distinguish exposition/query/JMX without sensitive data.
  - Verify:
    `./mill services.metrics.infrastructure.test.testOnly kui.metrics.infrastructure.ConfiguredClusterSourcesSuite`,
    `./mill services.metrics.app.test.testOnly kui.metrics.app.MetricsWiringSuite`, and
    `./mill checkArchitecture`.
  - Dependencies: PQ-06, PQ-10, and PQ-19.
  - Files: `services/metrics/app/src/kui/metrics/app/MetricsWiring.scala`,
    `services/metrics/app/test/src/kui/metrics/app/MetricsWiringSuite.scala`,
    `services/metrics/infrastructure/src/kui/metrics/infrastructure/ConfiguredClusterSources.scala`,
    `services/metrics/infrastructure/test/src/kui/metrics/infrastructure/ConfiguredClusterSourcesSuite.scala`.
  - Scope: Medium (4 files; integration lane).

### PQ-21 — Publish configuration guidance and close regression

- [x] Document the source-kind distinction, budgets, auth/TLS, stale behavior, security residuals and optional
  readiness semantics.
  - Acceptance: Quickstart remains exposition until `observability-testbed`; examples contain no real secret;
    no public API/OpenAPI/frontend artifact changes; all query-core success criteria trace to passing evidence.
  - Verify: Run focused modules, `./mill checkArchitecture`, `./mill __.checkFormat`,
    `./mill __.fix --check`, `git diff --check`, and `./scripts/run-tests.sh`.
  - Dependencies: PQ-17 and PQ-20.
  - Files: `README.md`, `docs/operations/configuration.md`.
  - Scope: Small (2 files; integration/documentation lane).

### Checkpoint C4 — Module complete

- [x] Every checkbox in `SPEC-prometheus-query-core.md` has direct test/runtime evidence and no required work
  remains for this module.
- [x] Review the final diff for unrelated changes, leaked secrets/URLs/queries, weakened limits, suppression
  directives, and accidental edits to the existing consumer/UI work.
- [x] Run the complete final gate from PQ-21 and record exact results before beginning
  `kafka-metrics-catalog`.

## README application media

- [x] Build and start the finished current-source quickstart stack, then wait for HTTP and Kafka readiness.
- [x] Capture every navigable application page at a consistent desktop viewport with real seeded data.
- [x] Capture representative consumer filtering, expanded-record copy controls, pagination/infinite-scroll,
  topic, consumer, cluster, connector and ksqlDB states where the quickstart supports them.
- [x] Assemble an optimized walkthrough GIF from the real captures and keep repository media reasonably sized.
- [x] Add the GIF and a compact screenshot gallery near the top of `README.md`, with useful alt text.
- [x] Run image/link checks, Playwright E2E, and inspect bounded backend/frontend/Kafka/registry logs.

---

# Cluster-scoped UI and alert persistence

- [x] Add failing codec/key/mutation tests for versioned pseudonymous cluster-principal state.
- [ ] Implement the bounded `__kui_config` projection and optimistic field-preserving updates.
- [ ] Add failing alert restart/read-watermark/acknowledgement persistence tests.
- [ ] Wire the durable alert store with explicit store-less fallback and operational diagnostics.
- [ ] Define and test typed cluster UI-settings GET/PUT contracts and authorization.
- [ ] Implement settings use cases/routes and regenerate every OpenAPI/browser contract artifact.
- [ ] Add failing frontend tests for hydration, cluster switches, stale responses and save failures.
- [ ] Implement immediate local application plus durable debounced backend synchronization and status UX.
- [ ] Enable the quickstart Kafka metadata store with safe demonstration-only key material.
- [ ] Build current-source containers and verify refresh plus KUI restart persistence in Chromium.
- [ ] Inspect `__kui_*` topic shape/content, browser network/console, and bounded backend/Kafka logs.
- [ ] Run full backend/frontend/architecture/format/security gates and complete the requirement audit.
