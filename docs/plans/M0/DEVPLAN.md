# M0 — Foundation (no Kafka): technical development plan

**Status:** grooming step G5 output (PLAN §39, format PLAN §41), 2026-09-03.
**Owners:** Planner (this document), Principal Scala Engineer (build, libs), Chief Architect
(module boundaries), Frontend Architect (frontend lane), QA Engineer (E2E lane).

This plan is the only input an implementation worker gets, together with the task spec it
picks up (`tasks/<ID>.md`), the ADRs that task cites, and `CLAUDE.md`. If a worker has to ask
a question, the answer belongs in this plan or in the task spec — not in a private reply.

---

## 1. Milestone goal

One repository that compiles, tests, links, ships as containers and as a single JVM, and
renders a browser shell whose navigation is driven by a live capability registry. **No Kafka
client code of any kind.**

The point of M0 is that the whole chain — Tapir contract → sttp client → gateway → capability
registry → Scala.js shell → feature fallback panel — exists and is proven once, on a sample
service that talks to nothing, so that the eleven real services built in M1–M8 copy a pattern
instead of inventing one.

## 2. Exit criteria (copied from `docs/ROADMAP.md`, M0)

- `./mill __.compile`, `./mill __.test`, `./mill __.checkFormat`, `./mill __.fix --check`
  all green in CI on a clean checkout.
- `./mill apps.allinone.run` serves the shell; `GET /api/v1/capabilities` lists the sample
  service as `Available`.
- Fault-isolation E2E: with the compose stack running, `docker stop kui-cluster` flips the
  capability to `Unavailable(reason, since)` within the readiness interval, the sidebar entry
  dims and clicking it renders the fallback panel with the reason; `docker start` flips it
  back with no page reload.
- Bundle-shape check: after `fullLinkJS`, `main.js` does not contain the sample feature's
  classes and a separate module file exists for it (ADR-012 Option B works).
- `GET /api/v1/openapi.json` aggregates the gateway and sample-service contracts.
- A `Secret[String]` field logged, traced, or returned from any endpoint renders as `***`
  (unit + contract test).
- Design tokens and the kernel primitives listed in `research/kafbat/ui-analysis.md` §IA.4
  ("Layout and navigation", "Actions and feedback") exist in light and dark themes.

Inherited from PLAN §46 for every milestone: compiles with `-Werror`; all unit / property /
integration / contract tests pass; fault-isolation tests pass for every service introduced;
formatting and scalafix clean; OpenAPI regenerated and committed; docs and feature matrix
updated; ADRs Accepted; CEO acceptance recorded in `STATUS.md`.

Feature-matrix rows closed by M0: KU-001 … KU-009, MT-007, CW-001, NX-005, NX-006,
NX-007 (partially — see §8 risk R-1), OT-005.

## 3. Non-goals

Restating the roadmap, and adding the boundaries workers most often cross by accident:

- **No Kafka.** No `libs/kafka`, no `libs/kafka-auth`, no `org.apache.kafka` dependency, no
  `fs2-kafka`, no Testcontainers Kafka container. `services/cluster` in M0 is a shell with
  `/health/*`, `/capabilities` and one `ping` endpoint. Its domain model (`ClusterProfile`,
  `Broker`, …) is **M1 work** and must not be started here.
- **No login.** `kui.auth.type` is `disabled`; the gateway runs anonymous. `services/identity`
  does not exist. The session/CSRF machinery of ADR-019 and the signed principal of ADR-020
  are built as skeletons that are exercised by tests, not by a login screen.
- **No RBAC policy evaluation.** `Rbac.decide` is M6. `libs/security-core` in M0 holds
  `Principal`, `PrincipalClaims`, `PrincipalCodec` and nothing else.
- **No real screens.** The shell, the settings stub, the fallback panels and one minimal
  `ui-clusters` page (a ping button) are the entire UI surface.
- **No metrics collection product feature.** Self-telemetry only (MT-007). `services/metrics`
  does not exist.
- **No virtualization, no CodeMirror, no uPlot, no ScalablyTyped in the routine build.** The
  kernel ships the non-virtualized `DataTable` only (SF-003 is M2). ADR-025's facades are M2+;
  the only M0 obligation from ADR-025 is the time-boxed `mill-scalablytyped` compatibility
  spike in BUILD-006.
- **No Helm chart**, no SBOM, no vulnerability scanning, no performance gate (M8).
- **No paging implementation beyond the kernel value types.** ADR-026 cursors are M3;
  `libs/kernel` only defines `Page`, `PageRequest`, `PageToken`, `SortOrder`.

## 4. Architecture references

| Reference | Why M0 needs it |
| --- | --- |
| ADR-001 build toolchain | Scala 3.9.0, JDK 21, Mill 1.1.8, Scala.js 1.22.0, compiler flags |
| ADR-002 Cats Effect 3 / FS2 | one runtime; `F[_]` in libs, `IO` only in `app` |
| ADR-003 Tapir + Netty + sttp 4 | contract modules, server, clients, OpenAPI |
| ADR-004 service decomposition | gateway is application code only; `/health/*` + `/capabilities` on every service |
| ADR-005 all-in-one | `ServiceClient[F]` with sttp and in-process implementations |
| ADR-007 Circe explicit codecs | no `auto` derivation anywhere |
| ADR-008 log4cats + Logback | `StructuredLogger[F]`, MDC bridge, JSON lines |
| ADR-009 otel4s oteljava | traces, metrics, Tapir interceptors, exporters |
| ADR-010 MacWire | one `<Name>Wiring.make` per deployable, reused by all-in-one |
| ADR-011 Laminar / Waypoint | frontend versions and state idioms |
| ADR-012 module splitting | `ModuleKind.ESModule`, `SmallModulesFor`, `js.dynamicImport` |
| ADR-013 Ciris | typed config, `Secret[A]`, accumulated validation |
| ADR-018 test frameworks | MUnit only, ScalaCheck, Testcontainers, JVM Playwright |
| ADR-019 session and CSRF | cookie shape, `X-Kui-Csrf`, `Sec-Fetch-Site`, edge header stripping |
| ADR-020 signed principal | `X-Kui-Principal` JWS, claims, in-process codec |
| ADR-024 CSS and design system | plain CSS, BEM, CSS custom properties, three-state theme |
| ADR-025 frontend facades | what M0 must *not* build; the ScalablyTyped spike |
| ADR-026 paging | kernel value types only in M0 |
| ADR-032 navigation state model | the five `FeatureState`s and their rendering rules |
| ADR-034 error envelope | `KuiError`, envelope shape, code table |
| ADR-035 streaming envelope | named SSE events, `heartbeat`, one terminal event |
| ADR-037 upstream resilience | `UpstreamClient.make`, circuit state stream into the registry |
| ADR-039 capability fold | the registry's four inputs, precedence, sticky `since`, asymmetric debounce |
| ADR-040 edge header policy | the gateway generates correlation ids and strips every inbound `X-Kui-*` |
| ADR-041 layering, machine-enforced | the `checkArchitecture` rule table; `application` never touches the wire |

`ARCHITECTURE.md` sections: §1 topology, §3 module layout, §4 shared library APIs (§4.1 kernel
types, §4.5 `CapabilityRegistry`, §4.6 `PrincipalCodec`), §5 headers, §6 capability registry and
`Section`, §7 SSE envelope, §10 configuration ownership, §11 all-in-one, §12 frontend shell,
§13 observability, §14 security boundaries, §15 errors, §16 repository layout.

`docs/domain/context-map.md`: the shared-kernel type list (bottom section) is the authoritative
inventory for `libs/kernel`; M0 implements the subset listed in KERN-001 … KERN-003.

## 5. Module map

Every module below is created in M0. Mill module ids use `.` for directory nesting; a
cross-compiled module has `.jvm` and `.js` children (BUILD-003 defines the traits).

### 5.1 Build root

| Path | Mill id | Purpose |
| --- | --- | --- |
| `build.mill` | — | root build: `Versions`, shared traits, module tree |
| `.mill-version`, `.scalafmt.conf`, `.scalafix.conf` | — | toolchain pins and style config |

### 5.2 Libraries

| Path | Mill id | Platforms | Depends on | Purpose |
| --- | --- | --- | --- | --- |
| `libs/kernel` | `libs.kernel.{jvm,js}` | JVM + JS | cats-core, iron | shared-kernel ids, `KuiError`, `Secret[A]`, paging value types |
| `libs/contracts-core` | `libs.contractsCore.{jvm,js}` | JVM + JS | `libs.kernel`, tapir-core, tapir-json-circe, tapir-iron, circe | error envelope DTO, `Section[A]`, capability DTOs, SSE event DTOs, Tapir codecs for kernel ids |
| `libs/security-core` | `libs.securityCore.{jvm,js}` | JVM + JS | `libs.kernel`, circe | `Principal`, `PrincipalClaims`, `PrincipalCodec[F]`; nimbus JWS adapter on the JVM side only |
| `libs/config` | `libs.config` | JVM | `libs.kernel`, ciris, ciris-circe-yaml, iron-ciris, circe-yaml, fs2-io | `KuiConfig` model slices, loaders, redaction. **No `ConfigStore[F]` in M0**: the port and its file adapter arrive with the cluster registry in M1, and the Kafka adapter of ADR-042 with it. `libs/config` therefore has no Kafka dependency in M0. |
| `libs/observability` | `libs.observability` | JVM | `libs.kernel`, otel4s, log4cats, tapir interceptors | telemetry bootstrap, structured logger, MDC bridge, metric names |
| `libs/http` | `libs.http` | JVM | `libs.kernel`, `libs.contractsCore.jvm`, `libs.observability`, tapir-netty-server-cats, sttp4, fs2-io | Netty server builder, error interceptor, CORS, base path, health endpoints, `UpstreamClient`, SSE helpers |
| `libs/testkit` | `libs.testkit` | JVM | `libs.kernel.jvm`, `libs.contractsCore.jvm`, munit, scalacheck, testcontainers, tapir-sttp-stub4-server | generators, fakes, golden files, fault-injection stubs |

The metadata store of ADR-042 (`__kui_config`, `__kui_files`, `__kui_audit`) is **out of M0
scope** in its entirety: M0 has no Kafka client code at all (`docs/ROADMAP.md` M0 goal). M1
adds the `ConfigStore[F]` port with both adapters as feature-matrix row OT-004. Nothing in M0
is invalidated by that: the static Ciris configuration M0 builds stays the canonical base that
the store overlays (ADR-036, unchanged by ADR-042).

Dependency edges added: `contracts-core → kernel`, `security-core → kernel`,
`config → kernel`, `observability → kernel`, `http → {kernel, contracts-core, observability}`,
`testkit → {kernel, contracts-core}`. No library depends on a service. `libs/http` must not
depend on `libs/config` (config is passed in as already-parsed case classes).

### 5.3 Services

`services/cluster` is the **sample service** of the roadmap: in M0 it has no domain logic
beyond a `ping`. It is built with five of the six layers of `ARCHITECTURE.md` §3 — `infrastructure` is the
sixth and arrives in M1 with the first adapter, since M0's sample service talks to nothing —
so M1 only adds content.

| Path | Mill id | Depends on | Purpose |
| --- | --- | --- | --- |
| `services/cluster/domain` | `services.cluster.domain` | `libs.kernel.jvm`, cats-core | `Ping` value object and the `ClockPort` used by it; nothing else |
| `services/cluster/application` | `services.cluster.application` | `services.cluster.domain`, cats-effect, fs2, log4cats, otel4s-core | `PingUseCase`, `CapabilityReportUseCase` |
| `services/cluster/contract` | `services.cluster.contract.{jvm,js}` | `libs.contractsCore.{jvm,js}` | Tapir endpoints `/internal/v1/ping`, `/capabilities`, `/health/*` and their DTOs |
| `services/cluster/api` | `services.cluster.api` | `services.cluster.{application,contract.jvm}`, `libs.http`, `libs.securityCore.jvm` | server logic, `KuiError` → envelope, principal verification, OpenAPI document |
| `services/cluster/app` | `services.cluster.app` | `services.cluster.api`, `libs.config`, `libs.observability`, macwire, logback | `ClusterWiring.make`, `main`, Netty listener |
| `services/gateway/application` | `services.gateway.application` | `libs.kernel.jvm`, `libs.securityCore.jvm`, cats-effect, fs2 | capability registry (own types), `ServiceClient[F]` port, session store, aggregation helpers |
| `services/gateway/contract` | `services.gateway.contract.{jvm,js}` | `libs.contractsCore.{jvm,js}` | `/api/v1/capabilities`, `/api/v1/capabilities/stream`, `/api/v1/info`, `/api/v1/auth/me` |
| `services/gateway/api` | `services.gateway.api` | `services.gateway.{application,contract.jvm}`, `services.cluster.contract.jvm`, `libs.contractsCore.jvm`, `libs.http`, tapir-files, tapir-swagger-ui-bundle | routing derived from contracts, static assets, OpenAPI merge, CSRF and session middleware |
| `services/gateway/app` | `services.gateway.app` | `services.gateway.api`, `libs.config`, `libs.observability`, macwire, logback | `GatewayWiring.make`, `main`, Netty listener |

The gateway depends on each service's **`contract`** module and on nothing else from a service
(`ARCHITECTURE.md` §3). A `moduleDeps` edge from the gateway to `services.cluster.application`
is an architecture violation and CI must fail on it (BUILD-005).

No `application` module — the gateway's included — depends on `libs.contractsCore`, `libs.http`,
tapir or circe (ADR-041). The gateway's capability registry therefore owns its own state types
and `services.gateway.api` maps them to the `libs/contracts-core` DTOs on the way out, the same
way `services.cluster.api` maps `CapabilityReport` to `ServiceCapabilities` (SVC-001).

### 5.4 Frontend

| Path | Mill id | Depends on | Purpose |
| --- | --- | --- | --- |
| `frontend/ui-kernel` | `frontend.uiKernel` | `libs.kernel.js`, `libs.contractsCore.js`, `libs.securityCore.js`, `services.gateway.contract.js`, laminar, airstream, scalajs-dom, sttp4, tapir-sttp-client4, scala-java-time | tokens, CSS pipeline, primitives, `ApiClient`, `QueryCache`, `Sse`, `KuiFeature`, capability store |
| `frontend/ui-shell` | `frontend.uiShell` | `frontend.uiKernel`, waypoint | router, layout, navigation, fallback panels, module entry point |
| `frontend/ui-clusters` | `frontend.uiClusters` | `frontend.uiKernel`, `services.cluster.contract.js` | the one M0 feature module: a page that calls `ping` |

`frontend.uiShell` has a `moduleDeps` edge to `frontend.uiClusters` (needed so one link covers
both) but **must never reference a `kui.ui.clusters` class statically** — only through the
`js.dynamicImport` thunk registry (ADR-012). BUILD-006's bundle-shape check enforces this.

### 5.5 Apps, deployment, tests

| Path | Mill id | Depends on | Purpose |
| --- | --- | --- | --- |
| `apps/allinone` | `apps.allinone` | `services.gateway.{api,app}`, `services.cluster.{api,app}`, `libs.*` | one MacWire root, one Netty listener, in-process service client |
| `deployment/docker` | `deployment.docker.*` | `mill-contrib-docker` | image definitions for `kui-gateway`, `kui-cluster`, `kui-allinone` |
| `deployment/compose` | — | — | `docker-compose.yml` dev environment (not a Mill module) |
| `e2e` | `e2e` | `apps.allinone`, `libs.testkit`, playwright | JVM Playwright + MUnit suites, fault-injection scenarios |

## 6. Task graph

57 tasks. Sizes: **S** ≈ 1–2 h, **M** ≈ 2–4 h, **L** ≈ 4–6 h. Every task ends on a green
`main`: a task that adds a module also adds that module's first test, and a task that changes
a contract regenerates the committed OpenAPI document in the same commit.

### 6.1 Parallel lanes

| Lane | Owner role | Tasks |
| --- | --- | --- |
| **A — Build and CI** | Principal Scala Engineer | BUILD-001 … BUILD-006 |
| **B — Kernel and contracts** | Principal Scala Engineer + Chief Architect | KERN-001 … KERN-008 |
| **C — Platform libraries** | Principal Scala Engineer | CFG-001, CFG-002, OBS-001, OBS-002, HTTP-001 … HTTP-004 |
| **D — Sample service** | Domain Architect (cluster) | SVC-001 … SVC-004 |
| **E — Gateway** | Chief Architect | GW-001 … GW-010 |
| **F — Frontend** | Frontend Architect | UI-001 … UI-013 |
| **G — Assembly** | Infrastructure Lead | AIO-001, AIO-002, INFRA-001 … INFRA-004 |
| **H — End-to-end** | QA Engineer | E2E-001, E2E-002 |

Lane B unblocks C, D, E and F. Lane F can start as soon as BUILD-003 and KERN-005 exist: the
frontend needs the capability DTOs, not the gateway that serves them (UI-008 develops against
a hand-written JSON fixture and switches to the live stream in UI-010).

### 6.2 Ordered task list

| ID | Title | Size | Depends on | Lane |
| --- | --- | --- | --- | --- |
| [BUILD-001](tasks/BUILD-001.md) | Repository skeleton and Mill root build | S | — | A |
| [BUILD-002](tasks/BUILD-002.md) | Compiler flags, scalafmt and scalafix gates | S | BUILD-001 | A |
| [BUILD-003](tasks/BUILD-003.md) | Cross-platform, Scala.js and test module traits | M | BUILD-002 | A |
| [BUILD-004](tasks/BUILD-004.md) | CI pipeline (PLAN §49) | M | BUILD-003 | A |
| [BUILD-005](tasks/BUILD-005.md) | Module-dependency architecture test | S | BUILD-003 | A |
| [BUILD-006](tasks/BUILD-006.md) | Toolchain spikes: Netty SSE, ScalablyTyped, Playwright pin | M | BUILD-003 | A |
| [KERN-001](tasks/KERN-001.md) | `libs/kernel`: identifiers and value objects | M | BUILD-003 | B |
| [KERN-002](tasks/KERN-002.md) | `libs/kernel`: `KuiError`, `ErrorCode`, `Secret[A]` | M | KERN-001 | B |
| [KERN-003](tasks/KERN-003.md) | `libs/kernel`: paging and sorting primitives | S | KERN-001 | B |
| [KERN-004](tasks/KERN-004.md) | `libs/contracts-core`: error envelope and kernel codecs | M | KERN-002, KERN-003 | B |
| [KERN-005](tasks/KERN-005.md) | `libs/contracts-core`: capability, `Section` and SSE DTOs | M | KERN-004 | B |
| [KERN-006](tasks/KERN-006.md) | `libs/security-core`: principal and `PrincipalCodec` | M | KERN-002 | B |
| [KERN-007](tasks/KERN-007.md) | `libs/testkit`: generators, fakes and golden files | M | KERN-004 | B |
| [KERN-008](tasks/KERN-008.md) | Generated `docs/api/error-codes.md` | S | KERN-004 | B |
| [CFG-001](tasks/CFG-001.md) | `libs/config`: Ciris model, loaders and precedence | L | KERN-002 | C |
| [OBS-001](tasks/OBS-001.md) | `libs/observability`: telemetry bootstrap and logger | M | KERN-002 | C |
| [OBS-002](tasks/OBS-002.md) | `libs/observability`: Tapir interceptors and metric names | M | OBS-001, KERN-004 | C |
| [CFG-002](tasks/CFG-002.md) | Secret redaction proof across log, trace and JSON | S | CFG-001, OBS-001, KERN-004 | C |
| [HTTP-001](tasks/HTTP-001.md) | `libs/http`: Netty server, error interceptor, CORS, base path | L | KERN-004, OBS-002 | C |
| [HTTP-002](tasks/HTTP-002.md) | `libs/http`: health, readiness and capabilities endpoints | M | HTTP-001, KERN-005 | C |
| [HTTP-003](tasks/HTTP-003.md) | `libs/http`: `UpstreamClient` resilience factory | L | HTTP-001, KERN-002 | C |
| [HTTP-004](tasks/HTTP-004.md) | `libs/http`: SSE helpers and heartbeat discipline | M | HTTP-001, KERN-005, BUILD-006 | C |
| [SVC-001](tasks/SVC-001.md) | `services/cluster`: domain and application skeleton | S | KERN-002 | D |
| [SVC-002](tasks/SVC-002.md) | `services/cluster/contract`: endpoints and DTOs | M | KERN-005, SVC-001 | D |
| [SVC-003](tasks/SVC-003.md) | `services/cluster/api`: server logic and OpenAPI | M | SVC-002, HTTP-002, KERN-006 | D |
| [SVC-004](tasks/SVC-004.md) | `services/cluster/app`: wiring, config slice, main | M | SVC-003, CFG-001, OBS-001 | D |
| [GW-001](tasks/GW-001.md) | `services/gateway`: skeleton, correlation id, error envelope | M | HTTP-001, KERN-005 | E |
| [GW-002](tasks/GW-002.md) | Gateway `ServiceClient[F]` and sttp implementation | M | GW-001, HTTP-003 | E |
| [GW-003](tasks/GW-003.md) | Capability registry core (`CapabilityRegistry[F]`) | L | GW-002, KERN-005 | E |
| [GW-004](tasks/GW-004.md) | Readiness poller and circuit-state feed | M | GW-003 | E |
| [GW-005](tasks/GW-005.md) | Capability endpoints: snapshot, SSE stream, probe | M | GW-003, HTTP-004 | E |
| [GW-006](tasks/GW-006.md) | Contract-derived proxy route to the sample service | M | GW-002, SVC-002 | E |
| [GW-007](tasks/GW-007.md) | OpenAPI aggregation and Swagger UI | M | GW-006 | E |
| [GW-008](tasks/GW-008.md) | Static asset serving, base path and SPA fallback | M | GW-001 | E |
| [GW-009](tasks/GW-009.md) | Session, CSRF and signed-principal edge skeleton | L | GW-001, KERN-006 | E |
| [GW-010](tasks/GW-010.md) | `GET /api/v1/info` build information | S | GW-001 | E |
| [UI-001](tasks/UI-001.md) | `frontend/ui-kernel` module and CSS pipeline | M | BUILD-003 | F |
| [UI-002](tasks/UI-002.md) | Placeholder design tokens and three-state theme | M | UI-001 | F |
| [UI-003](tasks/UI-003.md) | Kernel primitives A: button, input, select, tag, card, tabs | L | UI-002 | F |
| [UI-004](tasks/UI-004.md) | Kernel primitives B: dialog, drawer, toast, tooltip, breadcrumbs, empty state, `DataTable` | L | UI-003 | F |
| [UI-005](tasks/UI-005.md) | Kernel `ApiClient` over sttp `FetchBackend` | M | UI-001, KERN-005 | F |
| [UI-006](tasks/UI-006.md) | Kernel `QueryCache` and `Sse` wrappers | M | UI-005 | F |
| [UI-007](tasks/UI-007.md) | `KuiFeature` contract and lazy feature registry | M | UI-001 | F |
| [UI-008](tasks/UI-008.md) | Capability store and `FeatureState` derivation | M | UI-006, KERN-005 | F |
| [UI-009](tasks/UI-009.md) | `frontend/ui-shell`: router, layout, page ADT | L | UI-004, UI-007 | F |
| [UI-010](tasks/UI-010.md) | Capability-driven navigation, `FeatureGate`, fallback panel | L | UI-008, UI-009, GW-005 | F |
| [UI-011](tasks/UI-011.md) | Error pages: 403, 404, gateway-unreachable | S | UI-009 | F |
| [UI-012](tasks/UI-012.md) | `frontend/ui-clusters` sample feature and bundle-shape check | M | UI-010, SVC-002, BUILD-006 | F |
| [UI-013](tasks/UI-013.md) | Optional: reconcile tokens with a design import, if one ever lands | S | UI-002 | F |
| [AIO-001](tasks/AIO-001.md) | `apps/allinone` composition root and in-process client | L | SVC-004, GW-006, GW-009 | G |
| [AIO-002](tasks/AIO-002.md) | Frontend assets packaged into the all-in-one image | M | AIO-001, UI-012, GW-008 | G |
| [INFRA-001](tasks/INFRA-001.md) | Docker images for gateway, cluster and all-in-one | M | SVC-004, GW-010, AIO-001 | G |
| [INFRA-002](tasks/INFRA-002.md) | Docker Compose development environment | M | INFRA-001 | G |
| [INFRA-003](tasks/INFRA-003.md) | Developer loop: dev server and README | S | UI-009, INFRA-002 | G |
| [INFRA-004](tasks/INFRA-004.md) | Milestone documentation and feature-matrix update | S | everything | G |
| [E2E-001](tasks/E2E-001.md) | Playwright + MUnit harness against all-in-one | L | AIO-002 | H |
| [E2E-002](tasks/E2E-002.md) | Fault-isolation scenario: stop the sample service | L | E2E-001, INFRA-002 | H |

### 6.3 Critical path

The longest chain of real dependencies in §6.2 — every arrow below is an edge that table
actually declares, so nothing here can be reordered or parallelised:

```
BUILD-001 → BUILD-002 → BUILD-003 → KERN-001 → KERN-002 → OBS-001 → OBS-002 → HTTP-001
   → GW-001 → GW-002 → GW-003 → GW-005
   → UI-010 → UI-012 → AIO-002 → E2E-001 → E2E-002
```

17 tasks, roughly 70 working hours of single-threaded effort, and it runs through the
observability library rather than the kernel contracts, because `libs/http` cannot be built
before its Tapir interceptors exist. Everything else fits in the slack around it: lane C
(config and resilience) and lane F's primitive work (UI-002 … UI-009) are the two largest
parallel blocks, and the whole of lane D (SVC-001 … SVC-004) sits off the path, joining it
only through GW-006 and AIO-001.

Three tasks are worth starting first even though they are not on the critical path, because
they answer open questions that could invalidate later work: **BUILD-006** (does long-lived
SSE survive on `tapir-netty-server-cats`?), **CFG-001** (does the Ciris/Iron/`Secret` model
compile under `-Werror -Wunused:all`?) and **KERN-006** (does nimbus JWS stay out of the
cross-compiled core?).

## 7. Test plan

Test kinds follow PLAN §32 and ADR-018. **MUnit is the only framework**; no mocking library;
fakes live in `libs/testkit`.

| Suite | Where | Runner | What it covers |
| --- | --- | --- | --- |
| Kernel unit and property | `libs.kernel.{jvm,js}.test` | MUnit + ScalaCheck + discipline-munit | id validation round-trips, `Secret` redaction, paging arithmetic, `ErrorCode` totality |
| Contract codec golden files | `libs.contractsCore.jvm.test` | MUnit | every DTO encodes to a committed golden JSON file under `libs/contracts-core/test/resources/golden/`; a shape change fails the build |
| Cross-platform parity | `libs.contractsCore.js.test` | MUnit under Node | the same golden files decode identically on Scala.js |
| Config | `libs.config.test` | MUnit + ScalaCheck | precedence CLI → env → YAML → default, accumulated errors, unknown-key rejection, `Secret` never printed. No store tests in M0; the Kafka store gets a Testcontainers suite in M1 (OT-004). |
| Observability | `libs.observability.test` | MUnit + `otel4s-oteljava-testkit` | spans carry `correlation.id` / `service.name` / `operation`; MDC bridge populates log context; metric names match the constant list |
| HTTP server | `libs.http.test` | MUnit + `munit-cats-effect` | error interceptor envelope shape and status per `ErrorCode`; CORS off by default; base path prefixing; health endpoints unauthenticated |
| Upstream resilience | `libs.http.test` | MUnit + `tapir-sttp-stub4-server` | failover order, grace period, retry only on idempotent reads, bulkhead cap, breaker open → half-open → closed, circuit-state stream emission, URL policy rejections (link-local, metadata, non-http scheme, cross-host redirect) |
| SSE | `libs.http.test` | MUnit + `munit-cats-effect` | heartbeat cadence, exactly one terminal event, cancellation closes the source stream, `id:` passthrough |
| Sample service contract | `services.cluster.api.test` | MUnit + Tapir stub interpreter | every endpoint's success and error paths against the contract, without a server socket |
| Principal | `libs.securityCore.jvm.test` | MUnit + ScalaCheck | sign/verify round-trip, wrong `aud`, expired, tampered body digest, unknown `kid`, in-process codec equivalence |
| Gateway registry | `services.gateway.application.test` | MUnit + `munit-cats-effect` + `TestControl` | state transitions from readiness / circuit / service report; `since` monotonicity; delta stream deduplication; probe forces a poll |
| Gateway API | `services.gateway.api.test` | MUnit + stub upstream | routing derived from contracts, CSRF rejection matrix, `X-Kui-*` stripping, OpenAPI merge shape, SPA fallback |
| Frontend unit | `frontend.uiKernel.test`, `frontend.uiShell.test` | MUnit under Node | `FeatureState` derivation table (all 5 × capability × RBAC inputs), `QueryCache` invalidation, `Page` codec round-trip, `ApiClient` header injection via sttp `BackendStub` |
| Frontend DOM | `frontend.uiKernel.test` with `JsEnvConfig.JsDom()` | MUnit + `scala-dom-testutils` | primitives render the documented ARIA roles; dialog focus trap; theme attribute switching |
| Frontend browser | `frontend.uiKernel.test` with `JsEnvConfig.Playwright` | MUnit | `Sse.eventSource` and `Sse.fetchStream` only — the two wrappers jsdom cannot exercise |
| All-in-one integration | `apps.allinone.test` | MUnit + `munit-cats-effect` | booting the whole graph, `GET /api/v1/capabilities` reports `Available`, an in-process failure reports `Unavailable` identically to a remote 5xx |
| E2E (all-in-one) | `e2e.test` | JVM Playwright + MUnit | shell loads, navigation renders, sample feature module is fetched on demand, 404 page |
| E2E (distributed, fault injection) | `e2e.test` | JVM Playwright + Testcontainers Compose | `docker stop kui-cluster` → dimmed entry, fallback panel with reason and `since`; `docker start` → recovery with no reload |

**Testcontainers in M0:** only `docker-compose` orchestration for E2E-002. No Kafka, Schema
Registry, Connect, ksqlDB or LDAP containers — those arrive with the milestones that need them.

**Fault-injection scenarios in M0:**

1. Sample service process stopped (E2E-002) — the milestone's headline scenario.
2. Sample service slow past the per-call timeout — breaker opens, capability goes `Degraded`
   then `Unavailable` (HTTP-003 + GW-004 unit tests).
3. Sample service returns 500 on `/capabilities` — registry records `Unavailable` with the
   upstream reason code, never crashes the poller (GW-004).
4. Gateway unreachable from the browser — shell shows the single full-screen state (UI-011).
5. SSE stream dropped mid-flight — `EventSource` reconnects and receives a full snapshot
   (GW-005 + UI-008).

## 8. Risk register

| ID | Risk | Impact | Mitigation | Mitigating task(s) |
| --- | --- | --- | --- | --- |
| R-1 | The Claude Design import never arrives (BLOCKERS.md B-001 is owned by someone outside this swarm) | Waiting would stall the whole frontend lane | **Do not wait.** KUI owns its token set: UI-002 derives it from the competitor evidence already gathered (Kafbat's `theme.ts` palette and three-state dark mode; Kouncil's single-palette SCSS with no dark mode) and locks it as the M0 design decision. Components read CSS custom properties only, so a later import is a one-file reconciliation, not a redesign. NX-007 closes as `DONE` on the KUI-owned token set | UI-002 (owns the tokens), UI-013 (optional reconciliation, never a blocker) |
| R-2 | Long-lived SSE on `tapir-netty-server-cats` may buffer or drop (open question in DEPENDENCY_MATRIX) | The capability stream and every future message stream break | Time-boxed spike before HTTP-004; documented fallback is http4s-ember, swappable at `app` level only | BUILD-006, HTTP-004 |
| R-3 | Scala 3.9.0 artifacts may not be published for every dependency | Nothing compiles | BUILD-001 verifies resolution of the whole DEPENDENCY_MATRIX before any code is written; documented fallback is 3.3.8 LTS with a one-line change in `Versions` | BUILD-001 |
| R-4 | Accidental static reference pulls the feature module into `main.js`, silently defeating ADR-012 | The fault-isolation property "never downloaded" is lost | Automated bundle-shape assertion in CI, not a review convention | BUILD-006, UI-012 |
| R-5 | Laminar 17 / Waypoint 9 are one release behind; upgrading later touches every component | Rework across the frontend | Pin 17.2.1 / 9.0.0 (ADR-011); write routes with explicit `endOfSegments`; keep `js.dynamicImport` behind a five-line kernel helper so Airstream 18's operator replaces one file | UI-007, UI-009 |
| R-6 | `mill-scalablytyped` may not work on Mill 1.1.x | ADR-025's facade generation path is unavailable | Time-boxed spike; facades are M2+, so a negative result costs nothing in M0 beyond a recorded finding | BUILD-006 |
| R-7 | Chimney 2.0.0-RC1 may fail under `-Werror -Wunused:all` (TD-001) | DTO ↔ domain mapping needs hand-written code | M0 has almost no mapping; the spike compiles one representative mapper so M1 is not surprised | BUILD-006 |
| R-8 | The gateway grows domain logic while nobody is looking | ADR-004's central rule dies in the first milestone | Architecture test forbids `services.gateway.*` depending on any service module other than `contract` | BUILD-005 |
| R-9 | Secrets leak through a path nobody tested (trace attribute, error envelope, `/api/v1/config`) | Security exit criterion fails late | One dedicated task asserts redaction across all four sinks with a single fixture | CFG-002 |
| R-10 | Playwright browser bundles are unpinned and CI drifts | Flaky E2E, unreproducible failures | Pin the Playwright JVM version and browser revision in `Versions`; `playwright install` runs with that pin in CI | BUILD-006, E2E-001 |
| R-11 | M0 quietly starts building M1 (cluster domain, Kafka) because the sample service is named `cluster` | Milestone slips, review load explodes | §3 non-goals are restated in every SVC task spec; SVC tasks name the exact files they may create | SVC-001 … SVC-004 |

## 9. Definition of done for M0

M0 is complete when all of the following are true and the evidence is committed:

1. **Every exit criterion in §2 is demonstrated by a command in CI**, not by a screenshot.
2. All 57 tasks are merged, each with an Implementation Report (PLAN §39, one screen).
3. `./mill __.compile` is clean with `-Werror -Wunused:all -source:future`;
   `./mill __.test` green on JVM and Scala.js; `./mill __.checkFormat` and
   `./mill __.fix --check` clean.
4. `./mill e2e.test` green against both shapes: the all-in-one JAR and the Compose stack,
   including the fault-isolation scenario.
5. `GET /api/v1/openapi.json` is regenerated and its snapshot committed under
   `docs/api/openapi.json`; the OpenAPI diff check passes.
6. `docs/api/error-codes.md` is generated from the `ErrorCode` enum and committed.
7. `docs/FEATURE_MATRIX.md` rows KU-001 … KU-009, MT-007, CW-001, NX-005, NX-006, NX-007 and
   OT-005 are `DONE`. NX-007 closes on the KUI-owned token set of UI-002; it is not held open
   for an external design import.
8. `TECH_DEBT.md` TD-007 is rewritten to reflect the decision taken: the token set is KUI's
   own, derived from competitor analysis, and the debt is the narrower "reconcile with the
   design project if and when it is ever imported". Any new debt taken during M0 has a row.
9. `BLOCKERS.md` B-001 is closed as **decided around**: the fallback became the decision, and
   nothing in M0 or M1 waits on it.
10. `ARCHITECTURE.md` is updated where an M0 task discovered a delta (§4 signatures are now
    real code, so the sketches are replaced by links to the implementing files).
11. `STATUS.md` records CEO acceptance with the CI run id that produced the evidence.
12. A developer who has never seen the repository can run `README.md`'s quick start and reach
    the shell in a browser in under ten minutes on a clean machine.

## 10. Decisions taken in this plan rather than escalated

Grooming produces decisions, not questions (PLAN §39: "if a worker must ask, the plan was
incomplete"). Where an ADR left a gap, this plan closed it — from the behaviour of the three
reference products already analysed in `research/`, not from opinion.

**This section is an index, not a source of truth.** The G6 gate review promoted the decisions
that constrain M1–M8 into ADRs (ADR-039 capability fold, ADR-040 edge header policy, ADR-041
machine-enforced layering, plus amendments to ADR-032 and ADR-034); the ADR is authoritative
and the "Where it lives" column says so. The rest live in the task that implements them and
reach `ARCHITECTURE.md` through INFRA-004.

| # | Gap | Decision | Evidence from the references | Where it lives |
| --- | --- | --- | --- | --- |
| D1 | ADR-032 lists the states but not what happens when several apply at once | Precedence `NotConfigured` > `Unavailable` > `Degraded` > `Available`; `since` is sticky across reason changes | Kafbat conflates "unconfigured" with "unhealthy" (both hide the entry), which is the exact confusion `research/kafbat/ui-analysis.md` DC-H2 flags; separating them requires a stated precedence | **ADR-039** §2–§3 (GW-003, UI-008) |
| D2 | What a feature shows before its first readiness poll | `Degraded(Starting)`, never `Unavailable` | Kafbat renders an empty sidebar during startup; users read that as "broken". A "starting" state costs nothing and removes the false alarm | **ADR-032** amendment 2 (GW-003, UI-008) |
| D3 | ADR-034's code table has no code for an unmatched route | Add `KUI-ROUTE-NOT-FOUND` (404) rather than overloading `KUI-INTERNAL` | Kouncil returns raw 500s for unknown paths (`research/kouncil/architecture.md` D11); Kafbat's numeric `5000` catch-all hides routing mistakes | **ADR-034** amendments 1–2 (HTTP-001, KERN-008) |
| D4 | Whether an inbound `X-Kui-Correlation-Id` from a browser is trusted | Never. The gateway generates it | A client-chosen id lets a caller poison log correlation; no reference does this either way, so the safe default is taken | **ADR-040** (GW-001) |
| D5 | Which failures feed the capability registry | Only `InfrastructureError`. An `ApplicationError` (a 404 for a missing resource) must not dim a sidebar | Kafbat has no registry at all; getting this wrong would make every user typo look like an outage | **ADR-039** §6 (GW-006) |
| D6 | `application` cannot depend on `contracts-core`, yet the capability report is a DTO | The use case returns an application-owned `CapabilityReport`; `api` maps it | PLAN §18's dependency rule, enforced by `checkArchitecture` A3 | **ADR-041** (SVC-001, SVC-003, BUILD-005) |
| D7 | A deep link to a feature route arrives before that feature's module is downloaded | Route *patterns* are static metadata beside the import thunk; only rendering is lazy | Kafbat's `React.lazy` per page has the same problem and solves it with static route declarations (`App.tsx`); the Scala.js equivalent must be explicit | UI-009, UI-007 |
| D8 | Interaction of RBAC and capability in the navigation | `Forbidden` wins over every health state, so the UI is never an oracle for what exists | Kouncil's route-level roles hide entries outright; ADR-032 wants them visible-but-disabled, which only works if health is not leaked through them | UI-008, UI-010 |
| D9 | ADR-012 assumed a dev proxy for `/api` | None needed: the gateway serves the linked assets itself, same origin | Kafbat runs a separate Vite dev server and a proxy; with no npm bundling in M0 that whole layer is unnecessary | INFRA-003, GW-008 |
| D10 | Design tokens with no design import (B-001) | KUI owns the token set. Three-state theming (Kafbat has it, Kouncil has none), a ~40-token vocabulary instead of Kafbat's 1 600-line theme object, WCAG AA enforced by a test | `research/scala/frontend-research.md` §6 and §2: Kafbat's theme file is a maintenance smell; Kouncil has no dark mode at all. Neither is copied; both inform the size and shape of the set | UI-002; BLOCKERS B-001 closed as decided-around |
| D11 | Spike outcomes (Netty SSE, ScalablyTyped, Playwright pin) | Each spike carries its own decision rule and fallback in the task spec, so a negative result changes the implementation without pausing the milestone | ADR-003 and ADR-025 already name the fallbacks; the plan binds them to a trigger | BUILD-006 |

**Standing rule for the rest of the project.** A blocker owned outside the execution loop is
not a reason to stop: propose the decision from the evidence available, take it, record it in
the artifact that owns it, and leave a cheap reconciliation path if the external input ever
arrives. B-001 (the design import) is the worked example — it is closed in `BLOCKERS.md` as
*decided around*, and nothing in M0 or M1 waits on it. Nothing in M0 is gated on a person
outside the execution loop.
