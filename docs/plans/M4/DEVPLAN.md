# M4 — Consumer groups: technical development plan

**Status:** grooming step G5 output (PLAN §39, format PLAN §41), 2026-09-04.
**Owners:** Chief Architect (this document, module boundaries, the task graph), Principal Scala
Engineer (`libs/kafka`'s group port), Domain Architect — consumer (the `services/consumer` domain
and application lanes), Frontend Architect (the `ui-consumers` lane), Infrastructure Lead
(configuration, quickstart seeding, operator documentation), QA Engineer (the Testcontainers
matrix, the seam suites and the fault-isolation E2E).

This plan is the only input an implementation worker gets, together with the task spec it picks up
(`tasks/GRP-NNN.md`), the ADRs that task cites, and `CLAUDE.md`. If a worker has to ask a question,
the answer belongs in this plan or in the task spec — not in a private reply.

M4 is the milestone where KUI first changes something an operator cares about. Everything before it
reads. `POST /clusters/{id}/consumer-groups/{group}/offsets` decides what a running application
will read next, and it does so on a system whose read-only mode, RBAC and audit trail do not exist
yet — those are M5 and M6. §10 decisions D2, D3 and D4 are how M4 ships that operation without its
safety net having been built, and they are not optional.

---

## 1. Milestone goal

See who consumes what, how far behind they are, and fix it.

Concretely: a consumer-group list with state filter, search, sorting and paging that stays cheap on
a cluster with thousands of groups; a group detail page with members, their assignments, committed
offsets, per-partition lag, a total, and the pace at which the group is moving; lag that keeps
updating without re-fetching everything; the topic page's "Consumers" tab; and an offset reset that
supports the beginning, the end, an explicit offset, a timestamp, a relative shift and a duration —
refusing to run while the group is active, and leaving a record of what it did.

M4 introduces three things later milestones build on and must not have to re-invent:

1. **`GroupAdmin[F]` in `libs/kafka`** — the second admin port family after `ClusterAdmin`, and the
   place where the second invariant of `libs/kafka/PORT-INVARIANTS.md` finally lands. M5 (CSV
   export, `__consumer_offsets` serde) and M8 (metrics inference) reach consumer-group data through
   it.
2. **The first mutation in the product**, with the three-part safety substitute of §10 D2/D3/D4: a
   `Mutation` marker on the endpoint that M5's read-only enumeration test will find already
   present, a server-computed plan the operator confirms rather than a form the operator submits,
   and a structured mutation record written through an `AuditSink[F]` port whose Kafka sink is M5's
   to add.
3. **The first cross-feature `FeaturePanel` guest** (KU-013): the topic page's Consumers tab is a
   section of the gateway's topic-overview aggregation, rendered by `ui-consumers` inside a slot
   `ui-topics` owns and cannot import from. Whether the microfrontend seam of ADR-012 and DC-H6
   survives contact with a second feature is decided here, once.

## 2. Exit criteria

The four from `docs/ROADMAP.md`, M4, each made executable, plus the five that the milestone's scope
rows (CG-001 … CG-006, TP-015, KU-011) require and the roadmap left implicit.

1. **Reset in every mode against a live group.**
   `./mill services.consumer.infrastructure.test` runs `OffsetResetLiveSuite` against a
   Testcontainers broker seeded with a real consumer group, and each of `ToEarliest`, `ToLatest`,
   `ToOffsets`, `ToTimestamp`, `ShiftBy` and `ByDuration` moves the committed offsets to the value
   an independently computed reference says it should.
2. **An active group is refused.** The same suite starts a consumer, waits for the group to reach
   `Stable`, resets, and gets `ErrorCode.InvalidState` (`KUI-INVALID-STATE`), with the group's
   committed offsets unchanged when read back afterwards.
3. **Lag polling returns only what changed.** `GET /clusters/{id}/consumer-groups/lag?since=<token>`
   called twice with no consumer activity in between returns an empty `changed` array and the same
   token; after one group commits, exactly that group appears. Asserted in
   `services.consumer.api.test` and again through the gateway in the seam suite (GRP-037).
4. **The UI's poll interval follows the Degraded payload.** When the consumer capability is
   `Degraded` with `suggestedPollIntervalMs`, `frontend.uiConsumers.test` asserts the lag poller
   uses that interval and not its own default.
5. **The topic page survives a dead consumer service.** With `kui-consumer` stopped, the topic
   details page renders, its own sections load, and the Consumers tab is present, dimmed, and shows
   the fallback card with a reason (`e2e.test`, `ConsumerServiceDownSuite`).
6. **A forced rebalance keeps the last-seen assignments on screen.** The detail page under a
   rebalance shows the previous assignment greyed with a stale badge and a timestamp rather than an
   empty table (DC-H10) — asserted at the use-case layer and again in the E2E.
7. **Lag is never fabricated.** A partition with no committed offset renders `—`, contributes
   nothing to the total, and the response says how many partitions were excluded. A property test
   asserts `total = Σ defined lags` and that no `0` is ever substituted for an undefined one.
8. **Nothing crosses the process boundary twice.** `./mill checkArchitecture` passes with rule A11
   (§5.3) active: no consumer-group state name, reset-mode name or lag-anomaly name is declared
   outside `libs/kernel`, and a build test proves the rule fails on a deliberate duplicate.
9. **Every mutation leaves a record.** `POST` of a reset, a group delete and an offsets delete each
   produce exactly one `MutationRecord` on the audit sink, carrying cluster, group, operation, the
   before and after offsets, the outcome, and no credential — asserted in
   `services.consumer.application.test` and in the E2E against the running log.

Inherited from PLAN §46 for every milestone: compiles with `-Werror`; all unit / property /
integration / contract tests pass; fault-isolation tests pass for every service introduced;
formatting and scalafix clean; OpenAPI regenerated and committed; docs and feature matrix updated;
ADRs Accepted; CEO acceptance recorded in `STATUS.md`.

Feature-matrix rows closed by M4: CG-001, CG-002, CG-003, CG-004, CG-005, CG-006, TP-015, KU-013,
and the `consumerGroups` section of KU-011.

## 3. Entry preconditions

M4 sits behind M2 and M3 in the roadmap and consumes three things they deliver. Each is checked by
a command, and each has a named fallback owned by an M4 task, so that a missing prerequisite is a
scoped extra task rather than a blocked milestone.

| # | Precondition | Check | If absent |
| --- | --- | --- | --- |
| P1 | A credential-bearing cluster profile source that a non-cluster service can consume (ADR-036/ADR-043). M1 shipped `GET /internal/v1/clusters/{id}/profile` with **every credential removed**, because M1 had no consumer; `ARCHITECTURE.md` §14 says "M2's first consumer decides how it receives credentials" | `grep -rn "ClusterProfileSource" libs services --include=*.scala` | GRP-022 builds it, and its spec carries both branches. Its size rises from S to L |
| P2 | `services/topic` exists and its overview aggregation has a section slot the consumer service can fill (TP-015, KU-011) | `test -d services/topic/contract` | GRP-029 ships the consumer half of the aggregation regardless; the topic half is a two-line change recorded as a cross-milestone note in `TECH_DEBT.md`, and the Consumers tab is reachable from the group list instead |
| P3 | `frontend/ui-topics` exists and hosts a kernel `FeaturePanel` slot (KU-013) | `grep -rn "FeaturePanel" frontend --include=*.scala` | GRP-035 ships `ui-consumers`' guest panel and its registration; the slot itself is added by GRP-035 to `ui-kernel` with the same signature DC-H6 describes |

A worker running the check gets a yes or a no in one command and never has to ask.

## 4. Non-goals

Restating the roadmap, and adding the boundaries workers most often cross by accident:

- **No CSV export.** CG-007 is M5. There is no download button, not even a disabled one.
- **No full-text n-gram search.** CG-008 is deferred to M9 (DR-10). The list's search is the
  substring match `libs/kernel`'s `NameIndex` already provides, over group ids only.
- **No `__consumer_offsets` serde.** CG-009 is M5 with the extended serde set (KU-023). M4 reads
  offsets through the AdminClient, never by consuming the internal topic.
- **No removal of individual members.** `removeMembersFromConsumerGroup` (KIP-345) appears in
  `research/kafka/admin-capabilities.md` §3 as a candidate no reference product ships. It is not in
  the feature matrix and is not built. Do not add the method to the port "while we are in there".
- **No read-only mode, no RBAC evaluation, no audit *viewer*.** M5 and M6. M4 ships the
  `Mutation` marker, the per-cluster `readOnly` refusal and the `AuditSink[F]` port with a log
  sink; it does not ship the global read-only policy, the `__kui_audit` topic sink, or a screen
  that reads audit records back.
- **No metrics, no charts beyond the lag sparkline.** The sparkline (CG-006) is drawn from lag
  samples the browser already holds, in inline SVG. `services/metrics` does not exist until M8 and
  `uplot` is not pulled in for this.
- **No share groups, no KIP-932.** `listGroups`/`describeShareGroups` can see them on Kafka 4.x;
  M4 lists **consumer** groups (classic and KIP-848 consumer protocol) and nothing else. A share
  group that appears in a listing is filtered out at the port with a counted, logged reason.
- **No cross-service call for end offsets.** `ARCHITECTURE.md` §9's consumer row is explicit: the
  consumer service gets end offsets from its own `listOffsets`, not from `services/topic`. A
  service-to-service call here would put a second service on the lag path and make the group list
  fail when the topic service is down.
- **No caching primitive of its own.** `SnapshotCell` from `libs/cache` holds the group snapshot,
  exactly as `ClusterSnapshots` does. If it does not do something M4 needs, that is a `libs/cache`
  conversation.
- **No new streaming endpoint.** Lag updates are a polled delta endpoint (CG-006 and Kafbat's own
  shape), not SSE. ADR-035's envelope is for message browsing, tracking, ksql and capabilities.

## 5. Architecture references

| Reference | Why M4 needs it |
| --- | --- | 
| ADR-006 (+ Amendment 1) | raw `Admin` for admin work, fs2-kafka for consumers and producers. Every call in §3 of the research is an `AdminClient` call, so `GroupAdmin` is a raw-`Admin` adapter behind `AdminClientPool`, not an fs2-kafka wrapper |
| ADR-016 | the group snapshot declares TTL, invalidation, bound, metrics and a staleness contract, and records them in `ARCHITECTURE.md` §9 |
| ADR-026 | offset paging for the group list (`page`/`pageSize`/`sort`/`q`), and the `pageCount`-computed-before-filter bug that is not reproduced |
| ADR-027 | per-context snapshot: `status`, `scrapedAt`, atomic replacement, refresh under a `Supervisor` |
| ADR-030 | 2.8 minimum; the state filter (2.6), multi-group offsets (3.3), type filter (3.8) and KIP-848 target assignments are **probed**, never inferred from a version |
| ADR-032 | how `Degraded` / `Unavailable` render, the stale rule DC-H3, and the rebalance stale badge DC-H10 |
| ADR-033 | Chimney maps application types to DTOs in `api`; the domain never sees a DTO |
| ADR-034 | the error envelope; the two new codes of §10 D5; `ErrorEnvelope.statusOf` |
| ADR-036 / ADR-043 | how the consumer service learns a cluster profile: `/internal/v1` direct call, one hop, ETag, SSE change notification, cached last-known fallback |
| ADR-037 | the per-upstream timeout that bounds the gateway's group-page aggregation |
| ADR-039 | the capability fold; only `InfrastructureError` dims a capability; `suggestedPollIntervalMs` in the Degraded reason is what exit criterion 4 reads |
| ADR-041 (+ Amendments 1–3) | A1, A3, A5, A9, A10 all apply unchanged to the new service tree; M4 adds A11 (§5.3) |
| `libs/kafka/PORT-INVARIANTS.md` | both invariants land here: §1's leaderless filter on the offset lookup lag needs, and §2's fabricated dead group. The file's own instruction is to move each section into the implementing method's scaladoc and delete it (GRP-002, GRP-006, GRP-040) |

`ARCHITECTURE.md` sections: §2 service catalog (`kui-consumer-service`, Degradable), §3 module
layout and the `checkArchitecture` rule table, §4.1 kernel types, §4.2 the `KafkaAdminPort` family,
§5 internal contracts and headers, §6 the `Section` envelope and the aggregation list that names
`GET /consumer-groups/{groupId}`, §8 offset paging, §9 the `consumer` row of the caching table, §12
the `FeaturePanel` slot.

`research/kafka/admin-capabilities.md` §3 is the **behavioural source** for every admin call in this
milestone and it outranks any sketch in `ARCHITECTURE.md`: the chunk sizes, the `Optional` state,
the `null` committed offset, the per-coordinator partial listing, KIP-122's clamping and
timestamp-miss rule, and the exact exception each mutation throws. DC-D6 (reset), DC-D7 (lag) and
DC-D3 (no-leader filtering) are its decisions and this plan implements them without re-deciding
them. `research/kafbat/ui-analysis.md` "Consumer groups" decides what each screen shows and what it
does when data is missing, including the six state tooltips and the reset form's field-by-field
behaviour; `research/kafbat/feature-matrix.md` records which of those are worth copying.
`research/design/REFERENCE.md` decides how the screens look and nothing else — its group names,
lag figures and member hosts are invented sample data.

`docs/domain/consumer.md` is the context's own document; it does not exist yet and GRP-010 creates
it. Tasks that add model types update it in the same commit.

## 6. Module map

M4 creates nine Mill modules and changes ten.

### 6.1 New modules

| Path | Mill id | Platforms | Depends on | Purpose |
| --- | --- | --- | --- | --- |
| `services/consumer/domain` | `services.consumer.domain` | JVM | `libs.kernel.jvm`, cats-core | `ConsumerGroup`, `GroupMember`, `Assignment`, `PartitionLag`, `LagMath`, `ResetSpec`, `ResetPlan`, and the ports they are stated in |
| `services/consumer/application` | `services.consumer.application` | JVM | `domain`, `libs.cache` | the snapshot, the five read use cases, the three mutation use cases, the mutation guard and the capability report |
| `services/consumer/infrastructure` | `services.consumer.infrastructure` | JVM | `domain`, `libs.kafka`, `libs.cache`, `libs.http`, `libs.observability`, `libs.kernel.jvm` | the `GroupAdmin` adapter, the profile source client, the audit sink. The **only** module in the consumer tree with a Kafka client on its classpath |
| `services/consumer/contract` | `services.consumer.contract.{jvm,js}` | JVM + JS | `libs.contractsCore`, `libs.securityCore` | the endpoints and DTOs the browser and the gateway both compile against |
| `services/consumer/api` | `services.consumer.api` | JVM | `application`, `contract.jvm`, `libs.http`, `libs.observability`, `libs.contractsCore.jvm` | server logic, error envelope, `Section` staleness, OpenAPI |
| `services/consumer/app` | `services.consumer.app` | JVM | `api`, `application`, `infrastructure`, `libs.config`, `libs.http`, `libs.observability` | the composition root and its `Resource` chain |
| `frontend/ui-consumers` | `frontend.uiConsumers` | JS | `uiKernel`, `services.consumer.contract.js`, `services.gateway.contract.js` | the list, the detail page, the reset wizard, the lag poller, and the topic-page guest panel |

`services/consumer/contract` is cross-compiled for the same reason `services/cluster/contract` is:
the browser decodes exactly what the service encodes, from the same source (ADR-003), and rule A2
forbids it any edge to `domain` or `application`.

### 6.2 Changed modules

| Mill id | Change | Task |
| --- | --- | --- |
| `libs.kernel.{jvm,js}` | gains the pure `kui.kernel.group` package: `GroupState`, `GroupProtocol`, `ResetTarget`, `LagAnomaly`. Cross-compiled and dependency-free, so the domain (A1), `libs/kafka`, the contract and the frontend share one definition instead of four (§10 D1) | GRP-001 |
| `libs.kafka` | gains `kui.kafka.admin.GroupAdmin` and its adapter, `GroupTypes`, `GroupFeature`, `OffsetLookup` | GRP-002 … GRP-009 |
| `libs.kernel.error.ErrorCode` | gains `GroupNotFound` and `GroupNotEmpty` (§10 D5) | GRP-001 |
| `libs.contractsCore.{jvm,js}` | gains the shared lag and group fragments the consumer contract and the gateway aggregation both use | GRP-024 |
| `libs.config` | gains the `kui.consumer.*` slice | GRP-036 |
| `libs.testkit` | gains `ConsumerGroupFixture`: a broker seeded with an active group, an empty group, a group with a partial commit and a group on a topic with a leaderless partition | GRP-036 |
| `services.gateway.{contract,api,application}` | the consumer proxy routes, the group-page aggregation, the topic-overview `consumerGroups` section, per-cluster consumer capability entries | GRP-029 |
| `frontend.uiKernel` | the `FeaturePanel` slot and `LagSparkline` (only if P3 says the slot is absent) | GRP-035 |
| `frontend.uiShell` | the Consumers destination and its route | GRP-030 |
| `apps.allinone`, `deployment.{docker,compose,quickstart}` | the consumer service in the composition root and the Compose topology; the quickstart seeds groups it can already show | GRP-039 |

**New dependency edges, and why each is legal.** `services.consumer.domain → libs.kernel.jvm` (A1);
`services.consumer.application → {domain, libs.cache}` (A3 unaffected — `libs.cache` is not a wire
module); `services.consumer.infrastructure → {domain, libs.kafka, libs.http, libs.cache,
libs.observability}` (A9, A10); `services.consumer.api → {application, contract.jvm, libs.http,
libs.contractsCore.jvm}`; `services.consumer.app → {api, application, infrastructure, libs.config}`
(A9's one permitted edge); `frontend.uiConsumers → {uiKernel, services.consumer.contract.js,
services.gateway.contract.js}`. No `libs` module depends on a service (A5). The gateway gains no
Kafka edge (A8) and knows the consumer service only through its `contract` module (A4).

`services.consumer.infrastructure → libs.http` is new for this shape and is the P1 edge: the
profile source is an HTTP client to the cluster service (ADR-043), and `libs/http` is where the
typed sttp client, the per-upstream timeout and the breaker live. It is an `infrastructure` module,
so A3 does not apply to it.

### 6.3 One rule M4 adds to `checkArchitecture`

| Rule | What it forbids | Why |
| --- | --- | --- |
| A11 | declaring a **wire vocabulary** — an enum or a set of string constants that is serialised across the process boundary — anywhere but `libs/kernel` or `libs/contracts-core`. Enforced by a build test that scans for a second declaration of each name in `kui.kernel.group` and for string literals equal to any of their `wire` values outside those two modules | The M0 review's second process finding, verbatim: *a string typed twice in two files drifts*. M1's integration found it again — a browser decoding a document nobody sends. Six names in M4 (`STABLE`, `EMPTY`, `DEAD`, `PREPARING_REBALANCE`, `COMPLETING_REBALANCE`, `UNKNOWN`) appear in a Kafka enum, a domain enum, a DTO, a query parameter and a CSS class. A documented rule that nothing enforces gets violated |

GRP-040 owns the rule and its build test; no other task edits the rule table.

### 6.4 Area boundaries

Six agents write the task specs. A file appears in exactly one row.

| Lane | Ids | May create or change | Must not touch |
| --- | --- | --- | --- |
| **A — Kafka platform** | GRP-001 … GRP-009 | `libs/kernel/src/kui/kernel/group/**`, `libs/kernel/src/kui/kernel/error/ErrorCode.scala`, `libs/kafka/src/kui/kafka/admin/Group*`, `OffsetLookup.scala`, their tests and `build.mill` module lines | anything under `services/`, `frontend/`, `libs/config`; **`TopicAdmin*` and any file M2 owns** |
| **B — Consumer domain and application** | GRP-010 … GRP-020 | `services/consumer/{domain,application}/**`, `docs/domain/consumer.md` | `services/consumer/{infrastructure,contract,api,app}`, any `libs` module |
| **C — Consumer adapters** | GRP-021 … GRP-023 | `services/consumer/infrastructure/**` | every other module in the consumer tree; the `checkArchitecture` rule table |
| **D — Contract, API, edge** | GRP-024 … GRP-029 | `services/consumer/{contract,api,app}/**`, `services/gateway/**`, `libs/contracts-core/src/kui/contracts/consumer/**`, `docs/api/*` | `services/consumer/{domain,application,infrastructure}`, `frontend/` |
| **E — Frontend** | GRP-030 … GRP-035 | `frontend/ui-consumers/**`, and the named additions in `frontend/ui-kernel` (GRP-035) and `frontend/ui-shell` (GRP-030) | any backend module |
| **F — Configuration, environments, tests, docs** | GRP-036 … GRP-040 | the `kui.consumer.*` slice in `libs/config`, `libs/testkit/**`, `build.mill` rules, `apps/allinone/**`, `deployment/**`, `e2e/**`, `docs/operations/*`, `docs/FEATURE_MATRIX.md`, `STATUS.md`, `TECH_DEBT.md`, `libs/kafka/PORT-INVARIANTS.md` | every service's own modules; `libs/kafka/src` |

Two shared files need a rule rather than an owner. **`build.mill`**: a task edits only the `object`
it creates plus the `moduleDeps` line of the module it is wiring; GRP-040 alone edits the
architecture rule table. **`docs/FEATURE_MATRIX.md`**: edited only by GRP-040, at the end, from the
evidence the other tasks left. No task flips its own row.

## 7. Task graph

40 tasks. Sizes: **S** ≈ 1–2 h, **M** ≈ 2–4 h, **L** ≈ 4–6 h. Every task ends on a green `main`: a
task that adds a module also adds that module's first test, a task that changes a contract
regenerates the committed OpenAPI document in the same commit, and a task that moves a paragraph
out of `PORT-INVARIANTS.md` deletes it there in the same commit.

### 7.1 Ordered task list

| ID | Title | Size | Depends on | Lane |
| --- | --- | --- | --- | --- |
| GRP-001 | `libs/kernel`: the shared consumer-group wire vocabulary and two error codes | M | — | A |
| GRP-002 | `libs/kafka`: `GroupAdmin[F]` port, result types, and the fabricated-dead-group invariant | M | GRP-001 | A |
| GRP-003 | `GroupAdmin.listGroups`: state and type filters, per-coordinator partial listing | M | GRP-002 | A |
| GRP-004 | `GroupAdmin.describeGroups`: chunked, members, classic and KIP-848 assignments | L | GRP-003 | A |
| GRP-005 | `GroupAdmin.committedOffsets`: multi-group, `requireStable`, absent commits | M | GRP-004 | A |
| GRP-006 | `OffsetLookup`: end and beginning offsets with the leaderless-partition filter | M | GRP-002 | A |
| GRP-007 | `GroupAdmin` mutations: alter offsets, delete offsets, delete groups | L | GRP-005, GRP-006 | A |
| GRP-008 | `GroupFeature`: the capability probe for the four version-gated group calls | M | GRP-003 | A |
| GRP-009 | The group port's live suite against a seeded Testcontainers cluster | L | GRP-007, GRP-008, GRP-036 | A |
| GRP-010 | `services/consumer/domain`: the module, the model and `docs/domain/consumer.md` | M | GRP-001 | B |
| GRP-011 | `LagMath`: per-partition lag, anomalies, and totals that skip what is undefined | M | GRP-010 | B |
| GRP-012 | Domain ports and the pure reset planner: `ResetSpec`, `ResetPlan`, clamping | L | GRP-011 | B |
| GRP-013 | `GroupSnapshots`: the per-cluster 30 s snapshot under a supervisor | L | GRP-012 | B |
| GRP-014 | `GroupListUseCase`: filter, search, sort, page, over the snapshot | M | GRP-013 | B |
| GRP-015 | `GroupDetailUseCase`: live describe, per-partition lag, last-seen assignments | L | GRP-013 | B |
| GRP-016 | `LagPollUseCase`: the incremental delta and its server-issued token | M | GRP-013 | B |
| GRP-017 | `GroupsForTopicUseCase`: the topic page's Consumers tab data | M | GRP-013 | B |
| GRP-018 | `MutationGuard` and `AuditSink[F]`: the pre-M5 safety net | M | GRP-012 | B |
| GRP-019 | `OffsetResetUseCase`: plan, confirm, precondition, apply | L | GRP-018, GRP-015 | B |
| GRP-020 | `DeleteGroupUseCase` and `DeleteOffsetsUseCase` | M | GRP-019 | B |
| GRP-021 | `services/consumer/infrastructure`: the module and the `GroupAdmin` adapter | L | GRP-012, GRP-007 | C |
| GRP-022 | `ClusterProfileSource` adapter: ETag, SSE change, cached last known | L | GRP-021 | C |
| GRP-023 | `AuditSink` log adapter, and the consumer service's observability surface | M | GRP-021, GRP-018 | C |
| GRP-024 | Consumer contract DTOs, redaction and golden files | M | GRP-010 | D |
| GRP-025 | Read endpoints: list, detail, lag delta, groups-for-topic | M | GRP-024 | D |
| GRP-026 | Mutation endpoints: plan, apply, delete group, delete offsets | L | GRP-025 | D |
| GRP-027 | Consumer `api`: server logic, error envelope, `Section` staleness | L | GRP-026, GRP-020, GRP-017 | D |
| GRP-028 | Consumer `app`: wiring, the profile-source bootstrap, readiness | M | GRP-027, GRP-022, GRP-023 | D |
| GRP-029 | Gateway: consumer routes, group-page aggregation, topic-overview section, capabilities, OpenAPI | L | GRP-025, GRP-020 | D |
| GRP-030 | `ui-consumers`: the module, the typed clients, the route and the shell destination | M | GRP-024, GRP-025 | E |
| GRP-031 | Group list screen: state filter, search, sort, paging, empty states | L | GRP-030 | E |
| GRP-032 | Group detail screen: members, assignments, per-partition lag, stale badge | L | GRP-031 | E |
| GRP-033 | Lag polling and the trend sparkline, driven by the Degraded payload | M | GRP-032 | E |
| GRP-034 | Reset-offsets wizard: six modes, the plan preview, the confirmation | L | GRP-032, GRP-026 | E |
| GRP-035 | Delete actions, and the topic page's Consumers guest panel | L | GRP-032, GRP-029 | E |
| GRP-036 | `kui.consumer.*` configuration and `libs/testkit`'s consumer-group fixture | L | GRP-001 | F |
| GRP-037 | The seam suites: recorded documents on all three boundaries | L | GRP-029, GRP-031 | F |
| GRP-038 | Fault-isolation and rebalance E2E | L | GRP-039, GRP-035 | F |
| GRP-039 | All-in-one, Compose and quickstart: the consumer service and seeded groups | M | GRP-028, GRP-029 | F |
| GRP-040 | Rule A11, the milestone documentation, the matrix, and PORT-INVARIANTS' deletion | M | everything | F |

**The table is grouped by lane, not topologically sorted.** Two edges point backwards in the
listing — GRP-036 precedes GRP-009, and GRP-039 precedes GRP-038. Read the `Depends on` column,
never the row order.

### 7.2 Critical path

Every arrow is an edge the table above declares:

```
GRP-001 → GRP-002 → GRP-003 → GRP-004 → GRP-005 → GRP-007
  → GRP-021 → GRP-022 → GRP-028 → GRP-029 → GRP-035 → GRP-038 → GRP-040
```

13 tasks, roughly 55 working hours of single-threaded effort. It runs through the **admin port and
the service's wiring**, not through the reset wizard, which is the opposite of what the milestone's
headline feature suggests. The reason is P1: the consumer service is the first service in KUI that
has to *obtain* a cluster profile rather than being handed one by configuration, and nothing can be
wired or run end to end until that hop works.

A **second chain of almost equal length** runs down the domain and must be worked in parallel from
day one or it becomes the critical path by default:

```
GRP-001 → GRP-010 → GRP-011 → GRP-012 → GRP-013 → GRP-015 → GRP-019
  → GRP-026 → GRP-027 → GRP-029
```

10 tasks. It shares only its first task with the critical path, so lanes B and D can run at full
width behind GRP-001. The frontend chain (GRP-030 → GRP-035) joins at GRP-029 and has real slack
until then; lane E's first three tasks depend only on the contract module (GRP-024).

### 7.3 What to do first, and why

Four tasks are worth starting before anything else, not because they sit on a path but because each
answers a question whose wrong answer invalidates work already done by the time the question
surfaces.

1. **GRP-001 — where the wire vocabulary lives.** Six group-state names, six reset-mode names and
   three lag-anomaly names each appear in a Kafka enum, a domain type, a DTO, a query parameter and
   a CSS class. Deciding late means the same rename in five modules; deciding wrong means the
   drift the M0 review found and M1's integration found again. It also carries the two new
   `ErrorCode` cases every later task's error mapping keys on.
2. **GRP-022 — does the credential-bearing profile hop actually work?** Precondition P1 is the one
   thing in this milestone that may not exist, and it is on the critical path. Start it early
   enough that a "no" has weeks of slack: it needs only GRP-021's module skeleton, so it can start
   in the first days.
3. **GRP-036 — can a Testcontainers broker be seeded with a *stable* consumer group?** Exit
   criterion 2 needs a group that is genuinely `Stable` with a live member, criterion 1 needs one
   that is genuinely `Empty`, and criterion 6 needs one that can be forced into a rebalance on
   demand. Making a container do all three reliably is not knowable from a document, and every
   integration suite in lanes A and C depends on it.
4. **GRP-012 — is the reset planner right *before* anything can run it?** KIP-122's rules are
   subtle (a timestamp with no match resolves to the end offset, not to the beginning; explicit
   offsets clamp; a leaderless partition aborts the whole reset) and they are pure functions. Every
   one of them is a property test that costs an hour now and costs a corrupted consumer group
   later.

## 8. Test plan

Test kinds follow PLAN §32 and ADR-018. **MUnit is the only framework**; no mocking library; fakes
live in the module's own test tree or in `libs/testkit`. A Scala.js test module and a JVM test
module cannot run in one Mill invocation, and two Scala.js test modules cannot be named on one
command line either (`STATUS.md`, M1 integration) — lane E's suites are always their own command.

| Suite | Where | Runner | What it covers |
| --- | --- | --- | --- |
| Wire vocabulary | `libs.kernel.{jvm,js}.test` | MUnit + ScalaCheck | every `GroupState`, `ResetTarget` and `LagAnomaly` round-trips through its `wire` string; the set of wire strings is exactly the documented set; the enum compiles and runs on Scala.js |
| Group port unit | `libs.kafka.test` | MUnit + ScalaCheck | chunking is 50 ids × 4 concurrent and is driven by `AdminTuning`; a failed chunk does not fail the batch; `GroupIdNotFoundException` never escapes the adapter; a share group in a listing is filtered and counted |
| Offset lookup | `libs.kafka.test` | MUnit | the **leaderless filter**: a partition with no leader is removed before the request and returned as `SkipReason.NoLeader`; a request whose every partition is leaderless makes no Kafka call at all |
| Group port live | `libs.kafka.test` | MUnit + Testcontainers | §3 of the research against a real broker: an unknown group id describes as `Dead` with no members; a group with a partial commit reports `null` for the uncommitted partitions; `requireStable` behaves; each mutation throws the documented exception under the documented precondition |
| Lag math | `services.consumer.domain.test` | MUnit + ScalaCheck | `lag = end − committed`; `None` when either side is missing; **the total equals the sum of the defined lags and never substitutes zero**; a committed offset beyond the end is `LagAnomaly.CommittedBeyondEnd` with lag `None`, not a negative number; a committed offset below the log start is `LagAnomaly.CommittedBeforeStart` |
| Reset planner | `services.consumer.domain.test` | MUnit + ScalaCheck | KIP-122: a timestamp with no match resolves to the **end** offset; explicit offsets clamp into `[earliest, latest]` and the clamp is reported as a warning rather than applied silently; `ShiftBy` clamps at both ends; `ByDuration` is `ToTimestamp(now − d)`; a leaderless partition aborts the plan |
| Snapshot and use cases | `services.consumer.application.test` | MUnit + `munit-cats-effect` + `TestControl` + fakes | the snapshot refreshes on its interval and not more often; a read never calls the port (virtual-time-zero, asserted on the fake's call log); a failed refresh serves the previous value as `Stale`; `scrapedAt` does not move on failure; the lag delta returns only changed groups and a stable token; the last-seen assignment survives a rebalance and carries its timestamp |
| Mutation guard | `services.consumer.application.test` | MUnit + `munit-cats-effect` | a mutation on a `readOnly` cluster is `KUI-READ-ONLY` **before** any Kafka call; every mutation emits exactly one `MutationRecord`; a failed mutation emits one too, with `outcome = Failed`; **no `MutationRecord` field contains a credential**, asserted against a profile whose every secret is a distinctive token |
| Reset preconditions | `services.consumer.application.test` | MUnit + `munit-cats-effect` | existence is checked with a listing before anything else (§10 D5); a group in any state but `Empty` or `Dead` is `KUI-INVALID-STATE`; the precondition is re-checked between plan and apply and a group that became active in between is refused; an expired or tampered plan token is `KUI-VALIDATION` |
| Adapter contract | `services.consumer.infrastructure.test` | MUnit + Testcontainers | the adapter satisfies the same fake-port contract the application suites use, against a live broker — the one suite that proves the fake is not lying |
| Reset live | `services.consumer.infrastructure.test` | MUnit + Testcontainers | exit criteria 1 and 2: every mode moves offsets to an independently computed reference; an active group is refused and its offsets are unchanged when read back |
| Profile source | `services.consumer.infrastructure.test` | MUnit + stub upstream | 304 keeps the cached profile; a version bump rebuilds the client; the cluster service being down serves the last known profile and reports `Degraded`; **cancellation**: the SSE subscriber's fiber is cancelled and the connection is closed |
| Contract | `services.consumer.api.test` | MUnit + Tapir stub interpreter | every endpoint's success and error paths without a socket; **no secret appears in any response body**; stale responses carry `Section.Stale` with `scrapedAt`; a `Mutation`-marked endpoint is refused on a read-only cluster |
| Gateway aggregation | `services.gateway.api.test` | MUnit + stub upstream + `TestControl` | the group page's sections populate independently; the consumer service failing leaves the other sections intact; the whole response returns within the per-upstream timeout, asserted with virtual time so a serialising regression fails rather than merely slows |
| **Seam — client vs server** | `services.consumer.api.test` | MUnit, recorded documents | the exact bytes the server produces for each endpoint are committed, and the browser's client decodes **those bytes**, not a hand-written sample. This is the suite that would have caught M1's defect 2 |
| **Seam — gateway vs service** | `services.gateway.api.test` | MUnit, recorded documents | the same, one hop up: the document the consumer service emits is the document the gateway parses, and the document the gateway emits is the one `ui-consumers` decodes |
| **Seam — config vs composition** | `apps.allinone.test` | MUnit + `munit-cats-effect` | every field of `kui.consumer.*` that is parsed reaches a component that reads it, asserted field by field against the wired graph. This is the suite that would have caught M1's defect 1 |
| Frontend unit | `frontend.uiConsumers.test` | MUnit under Node | the state-badge table (six states × tooltip); lag cells render `—` and never `0`; the sparkline with one sample, with a gap, and with an undefined total; the poller's interval follows `suggestedPollIntervalMs`; the wizard's mode-dependent field visibility |
| Frontend DOM | `frontend.uiConsumers.test` with `JsEnvConfig.JsDom()` | MUnit + `scala-dom-testutils` | the reset dialog traps focus, names the group in its confirmation, and its submit button is disabled until the plan has been fetched and acknowledged; the stale badge's ARIA semantics |
| All-in-one integration | `apps.allinone.test` | MUnit + `munit-cats-effect` + Testcontainers | the whole graph boots against one seeded broker; `GET /api/v1/clusters/{id}/consumer-groups` lists the seeded groups; readiness flips only after the first snapshot or its named failure |
| E2E fault isolation | `e2e.test` | JVM Playwright + Testcontainers Compose | `docker stop kui-consumer` leaves the shell, the dashboard, the topic page and the topic page's other tabs usable; the Consumers tab is dimmed with a reason; `docker start` recovers with no page reload; a forced rebalance leaves the last assignments greyed with a badge |

**Testcontainers in M4:** one Kafka topology (PLAINTEXT) plus the seeded groups of GRP-036. The
three-security matrix is M1's and is not re-run here; the group port uses the same
`AdminClientPool`, so nothing about SASL or SSL is new.

**Fault-injection scenarios in M4:**

1. The consumer service stopped — the milestone's headline scenario (GRP-038).
2. One coordinator broker down — the listing is partial; the response says so and the list renders
   what it has rather than failing (GRP-003, GRP-014).
3. A rebalance in progress while the detail page is open — last-seen assignments, stale badge
   (GRP-015, GRP-038).
4. The cluster service stopped while the consumer service runs — the last known profile keeps
   working and the capability is `Degraded`, not `Unavailable` (GRP-022).
5. A group that authenticates but has no `DESCRIBE` on the group — the row renders with a
   `Forbidden` section rather than disappearing (GRP-004, GRP-014).
6. A topic with a leaderless partition in a group's assignment — lag for that partition is `—`, the
   total says one partition was excluded, and a reset targeting it is refused rather than hanging
   for `default.api.timeout.ms` (GRP-006, GRP-012).

## 9. Risk register

| ID | Risk | Impact | Mitigation | Mitigating task(s) |
| --- | --- | --- | --- | --- |
| R-1 | The credential-bearing profile hop (P1) does not exist and is discovered late | The consumer service cannot build a Kafka client at all; the critical path stalls at its midpoint | Precondition P1 has a one-command check; GRP-022's spec carries both branches and is scheduled in the first days rather than when it is needed | GRP-022 |
| R-2 | An offset reset ships before read-only mode, RBAC and audit exist | A destructive operation with no safety net, on a product whose default is an unauthenticated session | Three substitutes, each with a test: the `Mutation` marker and the per-cluster `readOnly` refusal (§10 D2), the server-computed plan the operator confirms (§10 D3), and the `MutationRecord` written through `AuditSink[F]` (§10 D2). M5 replaces the log sink with the Kafka one and adds the global policy; nothing else moves | GRP-018, GRP-019, GRP-023, GRP-026 |
| R-3 | The reset's precondition is checked once, and the group becomes active between the check and the write | KUI resets a running consumer's offsets — the exact failure the precondition exists to prevent | The precondition is checked twice, at plan and immediately before the write, and the Kafka-side rejection is mapped as a third line of defence. All three are separately tested | GRP-012, GRP-019 |
| R-4 | Describing every group is expensive: 4 000 groups on one cluster is a real number | The list page becomes the slowest screen in KUI and hammers the coordinators | Bounded concurrency from `AdminTuning` (50 ids × 4, the reference's own numbers), the list served entirely from a 30 s snapshot with no describe on the request path, and a documented cost in `docs/operations/`. Caching beyond the snapshot is M8 | GRP-004, GRP-013, GRP-014 |
| R-5 | Lag is quietly wrong: a `null` committed offset summed as zero, a negative lag rendered, an end offset from a stale scrape paired with a fresh commit | An operator makes a capacity decision from a fabricated number, which is worse than no number | `Option[Lag]` end to end, sums that skip and report what they skipped, anomaly flags for both out-of-range cases, and a property test that no zero is ever substituted. The list's lag and its end offsets come from the *same* snapshot, never from two | GRP-011, GRP-013, GRP-014 |
| R-6 | Six group-state names get typed a second time in a DTO, a query parameter or a CSS class | The M0 review's second finding, repeated | Rule A11 and its build test (§6.3); one cross-compiled declaration in `libs/kernel` that the domain, the port, the contract and the browser all compile against | GRP-001, GRP-040 |
| R-7 | Every side unit-tests cleanly and the seam is still broken — M1's defects 1 and 2 | The milestone passes CI and fails on screen | Three seam suites, one per boundary, all built on **recorded documents**: the bytes the producer emits are committed and the consumer is tested against those bytes. Plus the config-to-composition field sweep | GRP-037 |
| R-8 | The snapshot's refresh fibers, the SSE profile subscriber and the lag poller are never cancelled | Fibers authenticating to clusters an operator removed; the M0 review's fourth finding | Every long-running path in M4 carries a "Cancellation and shutdown" section in its spec and at least one test that cancels the fiber and asserts the resource was released and nothing kept running | GRP-013, GRP-022, GRP-033 |
| R-9 | The topic page's Consumers tab is implemented by importing `ui-consumers` from `ui-topics` | The microfrontend boundary of ADR-012 collapses at its first real test, and the two features can never be deployed or degraded independently | The panel is registered through the kernel slot; a Playwright network assertion proves `ui-consumers`' module is not downloaded for a cluster where the consumer capability is `NotConfigured`, and a build test asserts `frontend.uiTopics` has no `moduleDeps` edge to `frontend.uiConsumers` | GRP-035, GRP-038 |
| R-10 | KIP-848 consumer groups report assignments through `targetAssignment()` and classic ones through `assignment()`; a port that reads one shows an empty member list for the other | Half of a modern cluster's groups render as having no assignment, which reads as a bug in the operator's application | Both are read, the protocol is a field on the member (`GroupProtocol`), and the live suite runs a classic and a consumer-protocol group side by side on a Kafka 4.x broker | GRP-004, GRP-009 |
| R-11 | M4 quietly starts building M5 — a CSV export "while we are in the table", a `__consumer_offsets` serde, a read-only policy for every service | Milestone slips; M5 inherits half-built work with no plan around it | §4's non-goals are restated in every lane B and D spec, and those specs name the exact files they may create | GRP-014, GRP-018, GRP-026 |
| R-12 | The lag delta's token is a client-supplied timestamp, as in the reference | Clock skew between browser and server silently drops or duplicates updates | The token is server-issued and opaque, carries the snapshot version it was cut from, and an unrecognised token means "send everything" rather than an error | GRP-016 |

## 10. Decisions taken in this plan rather than escalated

Grooming produces decisions, not questions (PLAN §39). Where the roadmap, an ADR or
`ARCHITECTURE.md` left a gap a worker would otherwise have to ask about, this plan closes it — from
the research already gathered, not from opinion.

| # | Gap | Decision | Evidence | Where it lives |
| --- | --- | --- | --- | --- |
| D1 | Six group-state names, six reset-mode names and three lag-anomaly names each cross the process boundary and each has a natural home in four different modules. Nothing says which one | **The wire vocabulary lives in `libs/kernel`**, in a new pure cross-compiled `kui.kernel.group` package: `GroupState`, `GroupProtocol`, `ResetTarget`, `LagAnomaly`, each with a `wire: String` and a `from(wire)`. The domain, `libs/kafka`, the contract module and the browser all compile against that one declaration; the Kafka enum is mapped to it inside `libs/kafka` and nowhere else | This is M1's decision D1 applied to a second vocabulary, for the same reason and with the same shape. The M0 review's second finding and M1's integration defect 2 are both the failure this prevents. `libs/kernel` is already the home of `Secret[A]`, the id types and `Section`-adjacent vocabulary, and pure data cross-compiles unchanged | GRP-001; enforced by rule A11 (GRP-040) |
| D2 | Offset reset is the first destructive operation and it ships in M4, while read-only mode and audit ship in M5 and RBAC in M6 | **Three substitutes ship with the operation, not after it.** (a) Every mutating endpoint carries a `Mutation` marker in its Tapir description and a `MutationKind` in the application layer, so M5's "enumerate every endpoint and assert each is classified" test finds them already classified. (b) A cluster whose profile says `readOnly` refuses every `Mutation` with `KUI-READ-ONLY` **before** the Kafka client is touched. (c) Every mutation writes one `MutationRecord` through an `AuditSink[F]` port; M4 ships the structured-log sink and M5 adds the `__kui_audit` Kafka sink behind the same port | The roadmap's own ordering rationale says "no destructive action ever ships without its safety net" and then schedules the reset wizard in M4 and read-only mode in M5. That is a contradiction the roadmap does not resolve; resolving it by delaying the wizard would remove the reason people open a consumer group page, which the same rationale gives as the justification for the schedule. Building the three cheap halves now, behind the ports M5 fills in, satisfies both | GRP-018, GRP-019, GRP-023, GRP-026 |
| D3 | "How is the reset confirmed?" A form submission carries what the operator typed; it does not carry what the cluster will actually do | **Two phases, server-computed.** `POST .../offsets/plan` resolves the `ResetSpec` against live offsets and returns a `ResetPlan`: per partition, the current committed offset, the proposed offset, whether it was clamped, and any warning. The plan carries an opaque `planToken` — an HMAC over `(clusterId, groupId, resolved offsets, expiry)` using the existing cursor key (ADR-026), valid five minutes. `POST .../offsets` accepts **only** a `planToken`, never a raw spec, re-verifies the precondition, and writes exactly the offsets the token names. The browser shows the plan as a table and the confirm button is disabled until it has been rendered | Kafbat's wizard submits a spec and the operator never sees the resulting offsets; Kouncil's does not clamp at all (`admin-capabilities.md` §3, called "a foot-gun"). The number an operator needs to see before a destructive action is the one that will be written, and it can only be computed on the server. Re-using the cursor key means no new secret and no new configuration | GRP-012, GRP-019, GRP-026, GRP-034 |
| D4 | What exactly counts as "the group is active", and when is it checked | **`state ∈ {Empty, Dead}` and `members.isEmpty`, both, checked at plan time and again immediately before the write.** Kafka's own rejection (`UnknownMemberIdException` and the group-not-empty family) is mapped to `KUI-INVALID-STATE` as a third line of defence rather than being relied on as the first | Kafbat checks the state and Kouncil checks the member list; the two disagree on a group that reports `Empty` while a member is joining, and the union is the safe reading. A single check at plan time is a race the two-phase flow of D3 makes wider, not narrower, which is precisely why the second check exists | GRP-012, GRP-019 |
| D5 | `libs/kafka/PORT-INVARIANTS.md` §2 requires an unknown group to describe as a fabricated `Dead` group, so `describeGroups` cannot be used to answer "does this group exist". Nothing says what does, and `ErrorCode` has no group-not-found case | **Existence is confirmed with `listGroups` before any offset operation**, exactly as the reference product does, and **`ErrorCode` gains `GroupNotFound` (`KUI-GROUP-NOT-FOUND`, 404) and `GroupNotEmpty` (`KUI-GROUP-NOT-EMPTY`, 409)**. `GroupIdNotFoundException` is caught inside the adapter and never reaches `KafkaErrorMapper`, which is what the invariant's closing paragraph asks for. Read paths keep the fabricated dead group: an operator who follows a stale link gets an empty group page, not a 404 | The invariant file states the rule and names the missing error code; `admin-capabilities.md` §3 records `OffsetsResetService.java:66-92` doing the listing check. Splitting the behaviour by path — fabricate for reads, refuse for writes — is the only reading under which both the empty-group page and the safe reset are correct | GRP-001, GRP-002, GRP-019 |
| D6 | Lag with a missing committed offset, a missing end offset, or a commit outside the log | **`PartitionLag` is `Option[Long]` plus a `LagAnomaly` set.** No commit ⇒ `None` + `NoCommit`. Committed past the end ⇒ `None` + `CommittedBeyondEnd` (never a negative number). Committed before the log start ⇒ the lag is still computed but flagged `CommittedBeforeStart`, because the consumer really is that far behind and will start from the earliest available record. Totals sum only the defined values and the response carries `excludedPartitions` so the UI can say "of 12 partitions, 3 have no committed offset" | DC-D7 decides `Option[Lag]` with anomaly flags and sums that skip; `ConsumerGroupUtil.java:28-34` shows the reference summing with `orElse(0)`, which is exactly the fabrication a capacity decision must not be made from | GRP-011 |
| D7 | Where the end offsets that lag is computed against come from | **The consumer service's own `listOffsets`, over the union of committed and assigned partitions, inside the same 30 s snapshot pass that fetched the commits.** No call to `services/topic`, and never a fresh commit paired with an end offset from a different pass | `ARCHITECTURE.md` §9's consumer row states it. A cross-service call would put a second service on the lag path and make the group list fail whenever the topic service is down, which is the fault-isolation property the whole architecture exists to provide | GRP-006, GRP-013 |
| D8 | What a leaderless partition does to lag and to a reset | **Lag: the partition is filtered at the port (`SkipReason.NoLeader`), renders `—`, and is counted in `excludedPartitions`. Reset: the whole reset is refused** with `KUI-INVALID-STATE` naming the partition | `PORT-INVARIANTS.md` §1 (the sixty-second timeout) forces the filter; DC-D6 records the reference's `failOnUnknownLeader=true`, and a partial reset that silently skipped a partition would leave a group in a state no operator asked for | GRP-006, GRP-012 |
| D9 | CG-006 says "incremental lag polling"; Kafbat sends the browser's own `lastUpdate` timestamp back to the server | **The token is server-issued and opaque**, carrying the snapshot version it was cut from. An unrecognised or expired token is answered with a full payload and a fresh token, never with an error | A client clock is not a version. Skew in either direction silently drops updates or replays them, and the failure is invisible. An opaque server token also lets the shape change later without a contract break | GRP-016 |
| D10 | DC-D6 adds `ShiftBy` and `ByDuration`, which no reference product ships. Do they reach the UI, or only the API? | **Both, in M4.** They are pure transformations over the offsets the other modes already resolve, they cost one case each in the planner and one radio each in the wizard, and `ByDuration` ("rewind two hours") is the mode an operator reaches for during an incident | DC-D6 already decided to build them and called the cost "cheap: pure math over the same offsets". Shipping an API mode with no UI would leave a capability only a curl user can reach | GRP-012, GRP-034 |
| D11 | The roadmap's M4 exit criteria mention only the reset, but the scope row list includes CG-003 (delete group) and CG-005 (delete offsets for a topic) | **Both ship in M4**, under exactly the same three substitutes as the reset (D2) and the same precondition rule (D4), and each gets its own exit-criterion line (§2.9) | `docs/FEATURE_MATRIX.md` assigns CG-003 and CG-005 to M4 and the roadmap's scope line lists CG-001 … CG-006. The exit criteria are incomplete, not the scope | GRP-020, GRP-026, GRP-035 |
| D12 | Kouncil's group page has a "pace" column; the roadmap names it and nothing defines it | **Pace is the change in a group's total committed offset per second, measured between two consecutive snapshot passes.** It is `None` until two passes exist, `None` for a group whose partition set changed between passes, and it is a rate, not a lag | A pace computed across a changed partition set is arithmetic on two different quantities. Rendering `—` for one refresh interval after a rebalance is honest; rendering a spike is not | GRP-011, GRP-013 |
| D13 | Who serves the topic page's Consumers tab — the browser calling the consumer service directly, or the gateway's topic-overview aggregation | **The gateway's topic-overview aggregation carries a `consumerGroups` section, and `ui-consumers` renders it inside the kernel's `FeaturePanel` slot.** `ui-topics` never imports `ui-consumers` and never learns the consumer service's routes | KU-011 names the topic overview's sections as "added in M4/M7"; DC-H6 forbids the direct import; ADR-012's split-bundle property is only real if a feature can be absent. R-9's network assertion is the proof | GRP-029, GRP-035 |
| D14 | The list's search: server-side or client-side, and over what | **Server-side substring match over the group id only**, through `libs/kernel`'s `NameIndex` inside the snapshot, with `page`/`pageSize`/`sort` per ADR-026. Not over member hosts, not over topic names | ADR-038 says in-memory first and Lucene only on a benchmark; DR-10 defers the n-gram index. Kafbat searches group ids and offers an FTS toggle KUI does not have yet, so the toggle is absent rather than present and inert | GRP-014 |

**Standing rule, restated from the M0 and M1 plans.** A blocker owned outside the execution loop is
not a reason to stop: propose the decision from the evidence available, take it, record it in the
artifact that owns it, and leave a cheap reconciliation path if the external input ever arrives. In
M4 that rule applies to preconditions P1–P3: each has a check, a fallback and an owning task, and
no task waits on another milestone's agent.

## 11. Definition of done for M4

M4 is complete when all of the following are true and the evidence is committed:

1. **Every exit criterion in §2 is demonstrated by a command in CI.**
2. All 40 tasks are merged, each with an Implementation Report (PLAN §39, one screen).
3. `./mill __.compile` is clean with `-Werror -Wunused:all -source:future`; `./mill __.test` is
   green on the JVM and, in separate invocations, on each Scala.js test module;
   `./mill __.checkFormat` and `./mill __.fix --check` are clean; `./mill checkArchitecture` passes
   with rule A11 active and has been proven to fail on a deliberate duplicate declaration.
4. `./mill e2e.test` is green against the Compose stack, including the consumer-service
   fault-isolation scenario, the rebalance scenario, and the network assertion that
   `ui-consumers`' module is not downloaded when the consumer capability is `NotConfigured`.
5. The three seam suites of GRP-037 are green, and each one's recorded documents are committed
   files that the producing side regenerates and the consuming side parses.
6. `GET /api/v1/openapi.json` is regenerated and its snapshot committed under
   `docs/api/openapi.json`; `docs/api/error-codes.md` includes `KUI-GROUP-NOT-FOUND` and
   `KUI-GROUP-NOT-EMPTY`.
7. `docs/FEATURE_MATRIX.md` rows CG-001 … CG-006, TP-015 and KU-013 are `DONE`, and KU-011 records
   its `consumerGroups` section.
8. `docs/domain/consumer.md` documents the aggregate, the ports, the lag rules and the reset
   preconditions; `ARCHITECTURE.md` §9's `consumer` row is confirmed or corrected against what
   shipped; `docs/operations/consumer-groups.md` states the describe-all cost on a large cluster
   and what an operator can do about it.
9. `libs/kafka/PORT-INVARIANTS.md` §2 has been **moved into `GroupAdmin.describeGroups`' scaladoc
   and deleted from the file**, and §1's rule is recorded on `OffsetLookup` with a note that
   `TopicAdmin.listOffsets` must use the same helper. The file is deleted entirely if nothing
   homeless remains.
10. **Every long-running or cancellable path introduced in M4 has a named cancellation test**: the
    snapshot refresh loop, the profile-source SSE subscriber, the lag poller in the browser, the
    reset's apply step, and the `app` bootstrap `Resource` chain each carry a "Cancellation and
    shutdown" requirement and at least one test that cancels the fiber and asserts the resource was
    released and nothing was left running.
11. ADR-006 records the group port in its consequences; ADR-016 and `ARCHITECTURE.md` §9 record the
    group snapshot's cache discipline; ADR-034 records the two new codes; a new ADR-045 records the
    plan-token confirmation model of D3, because it is a mechanism M5's other mutations will reuse
    and a decision of that reach does not belong only in a DEVPLAN table.
12. `TECH_DEBT.md` records every debt taken during M4 — including anything precondition P1, P2 or
    P3 forced — and `STATUS.md` records CEO acceptance with the CI run id that produced the
    evidence.
13. A developer who has never seen the repository can run the quickstart, open the Consumers
    screen, see the three seeded groups with their lag, and reset one of them to the beginning.
