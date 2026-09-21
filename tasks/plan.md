# Consumer filtering reliability and UX plan

## Outcome

Make filtered message browsing correct, fast, safe, and understandable for plain strings/JSON and registry-backed Avro, JSON Schema, and Protobuf records. Verify it through focused tests and a current-source container/browser run, with backend logs inspected throughout.

## Constraints and evidence

- Keep CEL as the backend execution engine; add a safe JSONPath-style authoring layer rather than evaluating arbitrary JSONPath at runtime.
- Treat the requested result limit as matched records, with separate bounded raw-record/byte/time scan budgets.
- Never silently fall back to an unfiltered browse when a filter reference is invalid.
- Do not expose producer-controlled data to unbounded parsing or new code-generation/evaluation paths.
- The current full repository suite has a pre-existing docs regression: `docs/frontend/tokens.md` and `docs/FEATURE_MATRIX.md` were deleted in an earlier commit. Focused filter/serde/message tests are green at baseline.
- Claude/Chrome DevTools MCP is not installed in this environment. Use the repository's Playwright + Chromium stack for equivalent browser E2E evidence.

## Milestones

### 1. Backend correctness

- Separate raw scan budget from delivered match limit in Kafka polling, preserving forward/backward cursors.
- Reject malformed filter IDs at the API boundary.
- Validate source/ID integrity on both cold and warm filter-cache paths.
- Add regression tests for selective matches beyond the first page and invalid references.

### 2. Frontend stream reliability

- Make malformed SSE messages produce a visible recoverable failure instead of throwing out of the callback.
- Isolate browse sessions by generation so stopped/old streams cannot mutate a newer view.
- Snapshot the complete query/filter request before asynchronous registration.
- Surface backend `filterErrors` distinctly from an honest zero-match result.

### 3. JSONPath-style field filtering UX

- Add a strict parser/compiler for paths such as `$.customer.address.city`, `$['odd-key']`, and `$.items[0].sku`.
- Compile structured target/path/operator/value inputs to escaped CEL; retain Advanced CEL for power users.
- Provide JSON/string-aware defaults and field suggestions from decoded visible records.
- Cover invalid paths and injection-shaped values with unit tests.

### 4. Performance and security hardening

- Enforce record, byte, and deadline scan budgets and report budget termination truthfully.
- Bound dynamic JSON text before parsing and retain existing node/depth safeguards.
- Avoid duplicate registration work where safe and guard URL/source size at the client boundary.
- Inspect timings, request count, browser console, network failures, and backend filter logs.

### 5. Container and browser verification

- Build the all-in-one/backend and frontend images from the current working tree.
- Start quickstart and verify health/build identity.
- Exercise plain JSON, string, Avro, JSON Schema, and Protobuf field-filter scenarios as available; add missing production-shaped fixtures where practical.
- Run Playwright consumer E2E and inspect bounded backend/frontend/registry/Kafka logs after each failing or final run.

## Verification gates

- Focused Scala suites: filter, serde-confluent, message domain/application/infrastructure/API/app.
- Focused frontend unit tests and typecheck.
- Architecture check.
- Current-source container health and Playwright E2E.
- Final worktree review for unrelated changes, secrets, unsafe logs, suppression directives, and security regressions.

## Manual QA environment extension

### Outcome

Leave a reproducible, current-source Docker environment that a developer can open and test without
reverse-engineering Compose overlays. The primary stack remains quickstart; auth, secured Kafka,
multi-cluster demo, distributed observability, and Storybook are explicit secondary modes.

### Deliverables

- Seed genuine registry-framed JSON Schema and Protobuf records alongside JSON, String, and Avro.
- Provide a single launcher/runbook for starting, verifying, logging, and stopping supported modes.
- Add expanded-record copy controls for headers, value, and the complete record, plus the requested
  2px spacing between adjacent message actions.
- Build the current working tree into Docker, restart the live stack, and verify filter/copy behavior
  with the real browser and backend logs.
- Keep demo credentials and private-upstream relaxations localhost-only and clearly marked as QA-only.

### Execution order

1. Finish and test the copy/spacing UI slice.
2. Add schema-backed fixtures and automated field-filter coverage.
3. Add the unified manual-QA launcher and runbook.
4. Rebuild images once, restart quickstart, and launch compatible secondary modes.
5. Run targeted and full browser verification; inspect bounded logs and document live URLs/credentials.

## Offset-relative message pagination

### Contract

- Keep the server's signed continuation cursor as the only authority for the next Kafka range.
- Cache completed pages in the browser so Previous never re-reads a moving log.
- Describe pages by their partition/offset bounds rather than pretending a Kafka log has a stable total.
- Default to one page at a time; make infinite scroll an explicit presentation choice.

### Slices

1. Extend the browse session with cached pages and previous/next navigation while retaining cursor safety.
2. Add a small accessible Pages / Infinite scroll control and offset-range footer.
3. Add an IntersectionObserver preloader with a visible manual fallback and in-flight guard.
4. Verify unit behavior, responsive browser behavior, network requests, and live container logs.

---

# Implementation Plan: `prometheus-query-core`

Status: approved by the user on 2026-09-20
Specification: [`../SPEC-prometheus-query-core.md`](../SPEC-prometheus-query-core.md)

## Overview

Add the explicit `prometheus-api` source as an on-demand, authenticated, TLS-capable and bounded
Prometheus query path while preserving the existing exposition collector. The work proceeds in thin
executable slices: first a safe source can probe a real query endpoint, then decode useful results,
then share/cache concurrent work, and finally expose production diagnostics and degradation semantics.

No public metrics endpoint changes in this module. Cluster, topic and consumer routes consume this
internal provider in later approved capability modules.

## Dependency graph

```text
source-kind and direct-member config discovery
    |
    +--> shared credentials + redirect policy
    |
    +--> HTTP TLS transport
    |
    +--> query limits and protocol ADTs
              |
              +--> streamed bounded decoder
              |
              +--> query cache/coalescer
                        |
                        +--> Prometheus HTTP client + probe
                                  |
                                  +--> logical telemetry + metrics wiring
                                            |
                                            +--> documentation and regression gate
```

Shared credentials/TLS and pure protocol/cache work can proceed concurrently after the source/config
contract is fixed. The HTTP client integrates them only after their focused tests are green.

## Architecture decisions

- `MetricsSourceKind.Prometheus` remains the direct exposition collector. `PrometheusApi` is additive
  and never creates a scrape loop or in-memory `MetricsBuffer`.
- A query source owns an on-demand client and one TLS-configured sttp transport. Prometheus availability
  does not affect process startup or readiness.
- Authentication is applied by a shared `libs/http` credential abstraction. Existing dependencies keep
  their current allowed auth mechanisms and behavior.
- Query POSTs disable redirects, pass through `UpstreamClient` with zero retries, and are capped while
  streaming the response, reusing/extracting the existing KSQL capped-input-stream precedent.
- Protocol decoding is pure. It accepts vector results for instant queries and matrix results for range
  queries, preserving legal non-finite sample kinds internally and rejecting unsupported result shapes.
- The query cache is purpose-built: KUI's existing `BoundedCache.getOrLoad` serializes unrelated misses
  and cannot retain a stale candidate. The new cache coalesces per key, loads different keys concurrently,
  and is bounded by entry count and decoded weight.
- Logical query telemetry wraps cache/coalescing and the resilient transport. Generic HTTP metrics remain
  useful but cannot by themselves describe cache hits, circuit refusals or stale fallback.
- No new runtime library is introduced. The unrelated existing `build.mill` modification is preserved
  and should not need to be touched by this module.

## Vertical slices

### Slice 1: Configure and probe an anonymous query source

Extend metrics-source configuration with the explicit kind and validated query budgets. Fix direct map
member discovery so nested source keys work identically in YAML, CLI and environment layers. Add the
minimum protocol/request seam needed to form a bounded `vector(1)` probe against an anonymous HTTP(S)
source with a reverse-proxy base path.

Verification checkpoint:

- Existing omitted/`prometheus` configurations decode exactly as before.
- Nested environment keys resolve to one intended cluster ID.
- A loopback server observes one form-encoded POST at the correct prefixed `/api/v1/query` path.
- An unavailable endpoint neither fails wiring nor adds readiness checks.

### Slice 2: Secure the source transport

Introduce shared anonymous/Basic/bearer/OAuth request credentials and HTTP JKS/PKCS#12 trust/mTLS
transport creation. Disable redirects, require HTTPS for credentials/TLS material, validate local stores
before listening, and sanitize circuit-breaker failure details. Do not migrate or broaden existing
Connect/ksqlDB/Registry configuration surfaces in this slice.

Verification checkpoint:

- Real loopback TLS tests cover custom CA, hostname mismatch, mTLS success and missing-client-cert failure.
- Auth tests prove exact headers, OAuth caching and mechanism-surplus rejection.
- Canary credentials, URLs and upstream bodies are absent from errors, logs, spans and metric attributes.
- Existing HTTP dependency suites remain green.

### Slice 3: Execute and strictly decode instant/range queries

Implement streamed response caps, Prometheus envelope/result ADTs, structural limits and the query client.
Support `probe`, bounded instant vector and bounded range matrix calls; preserve timestamps, empty results,
warnings/info counts and legal non-finite value kinds. Map every HTTP/envelope failure to a safe typed error.

Verification checkpoint:

- Exactly-at-limit bodies/series/points/labels succeed; one-over cases fail safely.
- Matrix/vector, empty, warning/info, non-finite, malformed, scalar/string, histogram and error fixtures
  all exercise an explicit mapping.
- Time range and step smart constructors prevent excessive or invalid requests before I/O.
- No raw Prometheus error, label, expression, URL or body reaches a `KuiError`.

### Slice 4: Make repeated and concurrent queries efficient

Add aligned cache keys, bounded weighted storage, per-key in-flight coalescing and explicit fresh/stale
answers. Serve stale data only after transient transport/timeout/429/5xx refresh failures, never after
auth, source-kind, query-profile or response-contract defects.

Verification checkpoint:

- Many callers of one key produce one HTTP load and the same result.
- Different keys execute concurrently up to the configured source bulkhead.
- Waiter/shared-load cancellation cannot strand an in-flight entry.
- Deterministic virtual-time tests prove fresh expiry, stale grace, forbidden fallback and eviction bounds.

### Slice 5: Wire, instrument and document the production behavior

Construct query clients only for `prometheus-api` sources, retain exposition collectors unchanged, add
bounded logical-query telemetry, and report the source kind accurately in startup/capability diagnostics.
Document configuration, security residuals and self-observability metrics without switching quickstart to
query mode before the later `observability-testbed` module supplies a real Prometheus server.

Verification checkpoint:

- Mixed exposition/query/JMX configurations create the correct resources and degradation states.
- Query source shutdown releases transports and in-flight loads.
- Metric-name documentation and its exact-name tests agree.
- Focused suites, full backend regression, formatting and architecture checks pass.

## Parallel execution lanes

After Slice 1 fixes the shared contracts:

- Lane A: credentials, TLS transport and security/leak tests.
- Lane B: protocol decoder, fixtures and streamed response cap.
- Lane C: cache/coalescing implementation and deterministic concurrency tests.
- Integration lane (sequential): HTTP client, telemetry, wiring and full regression.

Agents working in parallel must not edit the same shared files. `MetricsConfig.scala`,
`KuiConfigSource.scala`, `UpstreamAuthConfig.scala`, `UpstreamClient.scala`, `MetricNames.scala`, and
`MetricsWiring.scala` are serialized integration files owned by the primary agent or one designated lane.

## Risks and mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Nested environment discovery invents cluster IDs | High | Fix direct-member discovery first; exercise YAML/CLI/env precedence and hyphenated IDs |
| Extending shared auth changes existing services | High | Decoder takes an explicit allowed mechanism set; run all config/schema/Connect/ksql suites |
| TLS profiles cannot share the current transport | High | One transport per query source initially; prove resource cleanup with real TLS fixtures |
| Body cap happens after allocation | High | Extract incremental `InputStream` cap; test exactly-at-limit and one-byte-over bodies |
| Query cache serializes unrelated requests | High | Purpose-built per-key `Deferred` map; concurrency tests block and release independent keys |
| Stale data hides configuration/security defects | High | Whitelist only transient failures for stale fallback; negative tests for auth/decoder/profile errors |
| Prometheus details leak through errors/circuit logs | High | Safe error mapping plus secret/query/URL/body canaries across logs, telemetry and returned errors |
| Dirty worktree changes are overwritten | High | Scope diffs before each slice; preserve the unrelated `build.mill` and completed consumer/UI work |
| Full suite exposes unrelated existing failures | Medium | Record baseline and distinguish pre-existing failures; never weaken or delete tests |

## Verification gates

Baseline on 2026-09-20: the focused selector executed all five requested modules. Metrics
infrastructure/app and HTTP suites passed. Two pre-existing documentation-backed modules failed only
because `docs/operations/observability.md` and `docs/operations/masking.md` were removed while their
contract tests still require them: one `MetricNamesSuite` case and three `MaskingConfigSuite` cases.
The separate build-design baseline also fails four initializations because
`docs/frontend/tokens.md` is absent. Phase 3 restores these three executable-contract documents before
feature work so later green gates are meaningful; it does not restore stale roadmaps or the deleted
feature matrix.

Focused commands:

```bash
./mill libs.config.test
./mill libs.http.test
./mill libs.observability.test
./mill services.metrics.infrastructure.test
./mill services.metrics.app.test
./mill checkArchitecture
```

Final module gate:

```bash
./mill __.checkFormat
./mill __.fix --check
./scripts/run-tests.sh
```

Runtime proof for this foundation module uses a real loopback HTTP/TLS server in tests. Full Docker
Prometheus, exporter scraping and browser verification belong to `observability-testbed`, after the page
capabilities exist; the later E2E gate must still inspect backend logs after every failure and final run.

## Plan approval gate

After human approval, Phase 3 will add small, dependency-ordered tasks with per-task acceptance criteria,
verification commands and file ownership to `tasks/todo.md`. No production implementation starts before
those tasks receive their own review.
