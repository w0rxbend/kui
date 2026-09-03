# KUI architecture decision index

All ADRs live in `docs/adr/`. Status values: Proposed | Accepted | Superseded.
Reopening an Accepted ADR requires new evidence and a superseding ADR (PLAN §39).

| ID | Title | Status | Date |
| --- | --- | --- | --- |
| [ADR-001](docs/adr/ADR-001-build-toolchain.md) | Build toolchain: Scala 3.9.0 LTS, JDK 21, Mill 1.1.8, Scala.js 1.22.0 | Accepted | 2026-09-03 |
| [ADR-002](docs/adr/ADR-002-cats-effect-fs2-runtime.md) | Cats Effect 3 and FS2 as the single runtime | Accepted | 2026-09-03 |
| [ADR-003](docs/adr/ADR-003-tapir-netty-sttp4-http.md) | Tapir 1.13.31 with Netty (cats) server and sttp 4 clients | Accepted | 2026-09-03 |
| [ADR-004](docs/adr/ADR-004-service-decomposition-and-gateway.md) | Service decomposition, service catalog and the BFF gateway | Accepted | 2026-09-03 |
| [ADR-005](docs/adr/ADR-005-all-in-one-deployment.md) | All-in-one deployment shape | Accepted | 2026-09-03 |
| [ADR-006](docs/adr/ADR-006-fs2-kafka-and-admin-ports.md) | fs2-kafka 4 and per-context Kafka admin ports | Accepted | 2026-09-03 |
| [ADR-007](docs/adr/ADR-007-circe-explicit-codecs.md) | Circe with explicit codecs at the contract layer | Accepted | 2026-09-03 |
| [ADR-008](docs/adr/ADR-008-logging-log4cats-logback.md) | Structured logging with log4cats over Logback; Fabric dropped | Accepted | 2026-09-03 |
| [ADR-009](docs/adr/ADR-009-otel4s-oteljava-telemetry.md) | otel4s (oteljava backend) for traces and metrics | Accepted | 2026-09-03 |
| [ADR-010](docs/adr/ADR-010-macwire-composition-roots.md) | MacWire composition roots | Accepted | 2026-09-03 |
| [ADR-011](docs/adr/ADR-011-laminar-waypoint-frontend.md) | Laminar 17.2.1, Airstream 17.2.1, Waypoint 9.0.0 frontend | Accepted | 2026-09-03 |
| [ADR-012](docs/adr/ADR-012-microfrontend-loading-strategy.md) | Microfrontend loading: single link with module splitting and dynamic import | Accepted | 2026-09-03 |
| [ADR-013](docs/adr/ADR-013-ciris-configuration.md) | Configuration with Ciris (over PureConfig) | Accepted | 2026-09-03 |
| [ADR-014](docs/adr/ADR-014-schema-registry-client-strategy.md) | Schema Registry: own REST client for management, Confluent serializers for wire format | Accepted | 2026-09-03 |
| [ADR-015](docs/adr/ADR-015-application-authentication.md) | Application authentication: form, OIDC and LDAP in the identity service | Accepted | 2026-09-03 |
| [ADR-016](docs/adr/ADR-016-caching-strategy.md) | Caching strategy and staleness contracts | Accepted | 2026-09-03 |
| [ADR-017](docs/adr/ADR-017-cel-smart-filters.md) | CEL as the only user-programmable message predicate | Accepted | 2026-09-03 |
| [ADR-018](docs/adr/ADR-018-test-frameworks.md) | Test frameworks: MUnit only, ScalaCheck, Testcontainers, JVM Playwright | Accepted | 2026-09-03 |
| [ADR-019](docs/adr/ADR-019-session-and-csrf-model.md) | Gateway session and CSRF model | Accepted | 2026-09-03 |
| [ADR-020](docs/adr/ADR-020-signed-principal-header.md) | Signed principal header between gateway and services | Accepted | 2026-09-03 |
| [ADR-021](docs/adr/ADR-021-rbac-model.md) | RBAC model: Kafbat vocabulary, pure evaluation shared with the frontend | Accepted | 2026-09-03 |
| [ADR-022](docs/adr/ADR-022-typed-kafka-cluster-auth.md) | Kafka cluster authentication as typed configuration | Accepted | 2026-09-03 |
| [ADR-023](docs/adr/ADR-023-audit-and-masking.md) | Audit records and data masking rules | Accepted | 2026-09-03 |
| [ADR-024](docs/adr/ADR-024-css-and-design-system.md) | CSS strategy and design-system implementation | Accepted | 2026-09-03 |
| [ADR-025](docs/adr/ADR-025-frontend-facades.md) | Frontend facades: CodeMirror 6, circe JSON viewer, uPlot, kernel virtualized table | Accepted | 2026-09-03 |
| [ADR-026](docs/adr/ADR-026-paging-cursors.md) | Paging: offset pages for sorted lists, opaque signed cursors for streams | Accepted | 2026-09-03 |
| [ADR-027](docs/adr/ADR-027-per-context-state-snapshots.md) | Cluster state split into per-context snapshots | Accepted | 2026-09-03 |
| [ADR-028](docs/adr/ADR-028-serde-plugin-api.md) | Serde plugin API and compatibility with `io.kafbat.ui.serde.api` | Accepted | 2026-09-03 |
| [ADR-029](docs/adr/ADR-029-event-tracking.md) | Event tracking semantics and the table-browse page mode | Accepted | 2026-09-03 |
| [ADR-030](docs/adr/ADR-030-minimum-kafka-version.md) | Minimum supported Kafka broker version: 2.8 | Accepted | 2026-09-03 |
| [ADR-031](docs/adr/ADR-031-cluster-id-strategy.md) | Cluster identity: slug of the configured name, Kafka cluster id recorded | Accepted | 2026-09-03 |
| [ADR-032](docs/adr/ADR-032-navigation-state-model.md) | Navigation state model and degraded-state UX | Accepted | 2026-09-03 |
| [ADR-033](docs/adr/ADR-033-chimney-mapping.md) | Chimney 2.0.0-RC1 for DTO ↔ domain mapping on Scala 3.9 | Accepted | 2026-09-03 |
| [ADR-034](docs/adr/ADR-034-error-envelope.md) | Error model and HTTP error envelope | Accepted | 2026-09-03 |
| [ADR-035](docs/adr/ADR-035-streaming-envelope.md) | Streaming envelope: named SSE events with `error` and `heartbeat` | Accepted | 2026-09-03 |
| [ADR-036](docs/adr/ADR-036-dynamic-config-ownership.md) | Dynamic configuration: ownership, store and distribution without restart | Accepted | 2026-09-03 |
| [ADR-037](docs/adr/ADR-037-upstream-http-resilience.md) | Upstream HTTP clients: failover, retry, circuit breaker and bulkheads in one place | Accepted | 2026-09-03 |
| [ADR-038](docs/adr/ADR-038-search-in-memory-first.md) | Name search: in-memory index first, Lucene deferred | Accepted | 2026-09-03 |
| [ADR-039](docs/adr/ADR-039-capability-fold.md) | Capability fold: inputs, precedence, debounce and what must not feed it | Accepted | 2026-09-03 |
| [ADR-040](docs/adr/ADR-040-edge-header-policy.md) | Edge header policy: the gateway generates correlation ids and trusts no inbound `X-Kui-*` | Accepted | 2026-09-03 |
| [ADR-041](docs/adr/ADR-041-layering-rules-machine-enforced.md) | Layering rules are machine-enforced; a domain-owning `application` never depends on the wire (amended: the gateway is outside the rule) | Accepted | 2026-09-03 |
| [ADR-042](docs/adr/ADR-042-kafka-backed-metadata-store.md) | KUI metadata lives in Kafka, in internal compacted topics | Accepted | 2026-09-03 |
| [ADR-043](docs/adr/ADR-043-internal-service-to-service-calls.md) | Direct service→service calls are permitted on the callee's published `/internal/v1` contract, under four conditions (settles PLAN §16.6) | Accepted | 2026-09-03 |

ADR-039 … ADR-043 were written at and after the G6 grooming gate (2026-09-03) to record decisions the
M0 development plan had made that no ADR covered. Three further such decisions were folded
into the ADRs they belong to rather than given numbers of their own: the `KUI-ROUTE-NOT-FOUND`
code and the `ErrorCode.description` field became **ADR-034 amendments 1 and 2**; the
proxy-free dev loop and the static route patterns beside dynamic imports became **ADR-012
amendments 1 and 2**; the `Forbidden` precedence rule and `Degraded(Starting)` became
**ADR-032 amendments 1 and 2**.

**ADR-042** (2026-09-03) settles the condition STATUS.md recorded at the G6 gate: KUI's own
metadata lives in internal compacted Kafka topics, not in a relational database and not in a
versioned YAML file. It amends **ADR-036** (store adapters and distribution) and clarifies
**ADR-023** (the audit topic is the store's `__kui_audit`), closes `TECH_DEBT.md` TD-014, and
replaces `docs/FEATURE_MATRIX.md` OT-004.

## Mapping from PLAN §43

PLAN's ADR-001..018 keep their numbers. PLAN's "ADR-008 log4cats + Fabric" became
"log4cats + Logback; Fabric dropped". Research candidates numbered ADR-017..021 in
`research/scala/security-research.md` and ADR-019/020 in `research/scala/frontend-research.md`
were renumbered to ADR-019..025 above to avoid collisions.

## Decisions deliberately not yet taken (no ADR)

| Topic | Why deferred | Revisit |
| --- | --- | --- |
| MCP server library (andimiller/scala-mcp vs linkyard) | M9 scope; contracts must be stable first | M9 grooming |
| Third-party frontend plugin SDK (Option C) | post-M8 | after M8 |
| Web-component widget library (Shoelace) | design token import (Research Agent I) not delivered | after `research/design/` exists |
| Shared session store adapter (Kafka compacted topic) | single gateway replica acceptable until M6; ADR-042 now fixes the shape it would take | M6 |
| Internal events topic `kui.internal.events` | polling suffices for M0–M5 | M6 |
| Persisting topic analysis results | memory only, as Kafbat; ADR-042 gives it a home if the need appears | when a store need appears |
| `ui-clusters` page composition and feature-local state shape | one trivial page in M0; the pattern is worth deciding against a real screen | M2 grooming |
