# M3 — Message explorer: technical development plan

**Status:** grooming step G5 output (PLAN §39, format PLAN §41), 2026-09-04.
**Owners:** Chief Architect (this document, module boundaries, the task graph, the contract and
gateway lanes), Principal Scala Engineer (`libs/kafka` consume/produce, `libs/serde`,
`libs/serde-confluent`, `libs/filter`), Domain Architect — message (`services/message/domain`,
`application`, `infrastructure`), Frontend Architect (`frontend/ui-messages` and the two named
additions to `frontend/ui-kernel`), Infrastructure Lead + QA Engineer (configuration, the
Testcontainers fixtures, benchmarks, the E2E suites, the milestone documentation).

This plan is the only input an implementation worker gets, together with the task spec it picks up
(`tasks/MSG-NNN.md`), the ADRs that task cites, and `CLAUDE.md`. If a worker has to ask a question,
the answer belongs in this plan or in the task spec — not in a private reply.

---

## 1. Milestone goal

A person opens a topic, sees its records — decoded, filtered, paged in either direction, or live —
publishes one, republishes a range of them into another topic, and finds a single business event
across several topics inside a time window. Nothing in that sentence loads a whole partition into
memory, and every one of those streams stops at the Kafka consumer within one poll interval of the
browser tab closing.

M3 is the highest-risk milestone in the roadmap and the one that makes KUI worth running. It
introduces four things every later milestone reuses and must not re-invent:

1. **The consume and produce half of `libs/kafka`.** M1 built `ClusterAdmin`; M3 builds
   `MessageBrowsePort` — seek resolution, window walking, position-based termination, budgets and a
   cancellation path that closes the consumer. M4's lag screens, M5's analysis and M7's plugins all
   sit on the same offset math.
2. **`libs/serde` and `libs/serde-confluent` (ADR-028, ADR-014).** The first bytes-to-text boundary
   in KUI, with the rule that a decode failure is a *record annotation*, never a stream failure.
3. **`libs/filter` (ADR-017).** The only user-programmable predicate KUI will ever accept.
4. **The full streaming envelope (ADR-035).** M0's `libs/http` `Sse` proved the mechanics —
   heartbeats, one terminal event, a bounded buffer, cancellation. M3 is the first time real domain
   events (`phase`, `message`, `consumed`) travel through it, and the first time the browser parses
   them. The reference product has **no error event at all**: a mid-stream failure reaches a Kafbat
   user as a connection that simply stops (`research/kafbat/api-analysis.md` Finding 5.1). KUI has
   one; M3 is the milestone that has to actually emit it.

## 2. Exit criteria

Taken from `docs/ROADMAP.md` M3 and made executable. Each line names the command that demonstrates
it and the task that owns it.

1. **Every seek mode and every polling mode has a Testcontainers test.**
   `./mill libs.kafka.test` runs `MessageBrowseIntegrationSuite`, which covers
   `Beginning | Latest | Offset(one) | Offset(perPartition) | Timestamp` × `Forward | Backward` and
   `tail`, against a seeded 8-partition topic. (MSG-008)
2. **Backward browsing on a partition with 1 000 000 records never reads more than `limit` plus one
   window.** `MessageBackwardWindowSuite.readsAtMostLimitPlusOneWindow` asserts against a counting
   `PollEvent` observer, not against elapsed time: the assertion is
   `recordsFetched <= limit + ceil(limit / partitionCount) * partitionCount`. (MSG-005, MSG-008)
3. **Cancellation reaches the consumer.** `./mill services.message.api.test` runs
   `StreamCancellationSuite`: the SSE response body is cancelled mid-stream and within one poll
   interval `kui.message.consumers.active` is back to `0` and the fs2-kafka consumer `Resource` has
   been finalised. `./mill e2e.test` repeats it through a real browser
   (`MessagesCancellationSpec`). (MSG-044, MSG-046)
4. **Property tests on the seek/offset math shared by the stream and the page endpoint, and on the
   JSON flattener.** `./mill libs.kafka.test` (`SeekResolutionPropertySuite`) and
   `./mill frontend.uiMessages.test` (`JsonFlattenerPropertySuite`: depth cap, row cap, escaping,
   path round-trip). (MSG-003, MSG-021, MSG-037)
5. **Event tracking across three topics finds a planted header value inside the window, stops at the
   budget, and ends with `done`.** `./mill services.message.api.test`
   (`TrackStreamSuite.findsPlantedEventAcrossThreeTopics`). (MSG-023, MSG-029)
6. **Resend of an offset range lands byte-identical records in the destination topic, with headers
   stripped when asked.** `./mill services.message.infrastructure.test`
   (`ResendIntegrationSuite.copiesBytesExactly`) compares `Array[Byte]` key, value and header bytes.
   (MSG-022, MSG-025)
7. **Benchmarks recorded in `docs/benchmarks/M3-messages.md`**: small (≈200 B) and large (≈1 MiB)
   records, a 100-partition topic, tailing under a producer load of 5 000 rec/s, and a broker
   artificially slowed past the per-call timeout. (MSG-045)
8. **Fault isolation.** `./mill e2e.test`: stopping `kui-message` stops live mode with a toast,
   keeps the already-fetched rows on screen greyed with their fetch time, and leaves the topic and
   broker screens untouched. Stopping the Schema Registry keeps browsing working with non-SR serdes
   and renders `SR unavailable` in the serde chip. (MSG-046)
9. **The streaming envelope has an `error` event and it is emitted.** `SseWireSeamSuite` asserts a
   mid-stream Kafka failure produces `event: error` with a `KUI-*` code and terminates the stream,
   and that the browser parser turns those exact bytes into the typed event. (MSG-043)
10. **The wire seam is tested from both sides.** The service's encoder and the browser's decoder are
    asserted against **one** committed byte fixture per event type
    (`services/message/contract/test/resources/sse/*.txt`), read by a JVM suite and a Scala.js
    suite. (MSG-043)
11. **A cursor issued by one replica is honoured by another.** `CursorCodecSuite` and
    `MessagePageReplicaSuite`: a cursor minted with the configured `cursorKey` decodes in a second
    application instance, and an expired or foreign-cluster cursor is `KUI-CURSOR-EXPIRED` /
    `KUI-CURSOR-INVALID`. (MSG-018, MSG-030)
12. **Deserialization never fails a stream.** `SerdeFallbackSuite`: a record that no configured serde
    can decode is emitted with the `String` fallback text, `kind = Text`, and a
    `deserializeErrors[]` entry; the stream continues and ends with `done`. (MSG-009, MSG-019)
13. **Nothing unmasked leaves the service.** `MaskingBoundarySuite` browses a topic whose payload
    contains a distinctive token matched by a masking rule and asserts the token appears in no
    response body, including the table endpoint's `originalValue` field. (MSG-016, MSG-029)
14. **Purge, produce and resend are refused on a read-only cluster** with `KUI-READ-ONLY` before any
    Kafka client is touched, each carries a `Mutation` marker that a contract test enumerating
    `MessageEndpoints.all` asserts is present on every mutating endpoint and absent from every read,
    and each attempt — successful or failed — writes exactly one `MutationRecord` carrying no
    credential. Filter registration and testing are allowed. (ADR-047; MSG-016, MSG-022, MSG-024,
    MSG-029)

Inherited from PLAN §46 for every milestone: compiles with `-Werror`; all unit / property /
integration / contract tests pass; fault-isolation tests pass for every service introduced;
formatting and scalafix clean; OpenAPI regenerated and committed; docs and feature matrix updated;
ADRs Accepted; CEO acceptance recorded in `STATUS.md`.

Feature-matrix rows closed by M3: MS-001 … MS-014, MP-001 … MP-004, SD-001, SD-003, SD-004, DM-001,
ET-001, ET-002, ET-003, KU-014, KU-015, KU-016.

## 3. Non-goals

Restating the roadmap, and adding the boundaries workers most often cross by accident:

- **No consumer groups.** `GroupAdmin` is **not** created here. The browse consumer is
  assign-only with no `group.id` (`research/kafka/admin-capabilities.md` §4), so nothing in M3 needs
  it. `libs/kafka/PORT-INVARIANTS.md` §2 names M3 as the owner of `GroupAdmin.describeGroups`; that
  is wrong and this plan reassigns it to M4 (§10, D5). Leave the file's §2 in place.
- **No topic CRUD, no topic list, no partition or config screens.** Those are M2 and M5.
  `services/message` never calls `services/topic` (§10, D2).
- **No extended serdes.** `ProtobufFile`, `ProtobufRaw`, `AvroEmbedded`, `MessagePack`, `Struct`, the
  MirrorMaker2 topics and `__consumer_offsets` are KU-023 in M5, even though ADR-028 lists them among
  the built-ins. M3 ships exactly the SD-001 set (§10, D9). No serde jar loading (M7), no
  `libs/serde-kafbat-bridge`.
- **No UI-managed masking policies.** DM-001 is file-configured masking only; DM-002 needs identity
  and is M6. No `subjects[]` on a rule, no policy editor.
- **No audit *viewer*, no `__kui_audit` topic, no global read-only mode.** Those are M5 and M6.
  M3 does, however, ship the three parts **ADR-047** requires of any milestone that ships a
  mutation, because produce (MP-001), resend (MP-003) and purge (MS-008) are mutations and M3 is
  the first milestone to ship one: the `Mutation` marker on the endpoint, the per-cluster
  `readOnly` refusal before any Kafka client is touched, and one `MutationRecord` per attempt
  written through `AuditSink[F]` with a structured-log sink. `MutationKind`, `MutationRecord` and
  `AuditSink[F]` are declared once, in `libs/security-core` (ADR-023's home for the audit model),
  by lane C alongside the masking engine. M5 adds the Kafka sink behind the same port and the
  global policy over the same marker. This closes the contradiction between criterion 14 and this
  section that the gate review found.
- **No RBAC evaluation.** KU-016 fixes the *shape* of the permission check on the filter-test
  endpoint; enforcement arrives in M6. `kui.auth.type` stays `disabled`.
- **No correlation-key grouping beyond a single field.** ET's `correlationKey` adds a `group` string
  per event. No server-side join, no graph, no trace view — that is M9.
- **No schema-registry management screens.** `libs/serde-confluent` reads schemas to decode records.
  Subjects, versions and compatibility are `services/schema` in M7.
- **No `services/topic` edits and no `frontend/ui-topics` edits.** The messages tab reaches the topic
  page through the kernel `FeaturePanel` slot (KU-013, DC-H6), not by importing anything (§10, D3).
- **No new streaming primitives in `libs/http`.** `kui.http.sse.Sse` shipped in M0 and is used as it
  is. If it turns out to be missing something, that is a finding recorded in `TECH_DEBT.md`, not a
  rewrite inside this milestone.

## 3a. Entry preconditions — what M3 consumes from M2

M3 and M2 were groomed in parallel and M3's plan did not record what it takes from M2. It takes
four things. Each has a one-command check and a named fallback, so a missing prerequisite is a
scoped extra task rather than a blocked milestone.

| # | Precondition | Check | If absent |
| --- | --- | --- | --- |
| P1 | `services/cluster/client`, the shared credential-bearing profile consumer (ADR-046, M2 TOP-008/TOP-009) | `test -d services/cluster/client` | MSG-026 builds it **in that module, not in `services/message/infrastructure`**, and its size rises from L to XL. Under no circumstance does M3 write a second profile protocol |
| P2 | `frontend/ui-kernel`'s `FeatureSlots` and the `topic.tabs` guest host rendered by `ui-topics` (M2 TOP-027/TOP-030, KU-013) | `grep -rn "topic.tabs" frontend --include='*.scala'` | MSG-034 adds `FeatureSlots` to `ui-kernel` only. It still edits no file in `ui-topics`; the Messages tab is then reachable by URL and from the topic list's row action until M2 lands the host, recorded in `TECH_DEBT.md` |
| P3 | `libs/contracts-core`'s `PageDto` (M2 TOP-019) | `grep -rn "PageDto" libs/contracts-core --include='*.scala'` | MSG-027 declares it in `libs/contracts-core` with the same shape M2's TOP-019 specifies, and the two milestones must not both create it |
| P4 | `checkArchitecture` rule A11 (M2 TOP-010) | `grep -n "A11" build.mill` | MSG-047 adds A11 alongside A12 and A13, from ADR-041 Amendment 4's text |

M3 depends on M2 for **nothing else**. It does not use `TopicAdmin`, `NameIndex`,
`SnapshotRegistry` or any `services/topic` module, and §10 D2 forbids the service-to-service call.

## 4. Architecture references

| Reference | Why M3 needs it |
| --- | --- | 
| ADR-006 Amendment 1 | raw `Admin` for admin work, **fs2-kafka for consumers and producers** — the whole of lane A |
| ADR-014 Schema Registry client strategy | Confluent serializers behind `Serde[F]` in one isolated module; `libs/serde-confluent` is optional at runtime |
| ADR-016 caching | the serde registry `Resource` per cluster, the compiled-filter `BoundedCache` (10 000, TTL 1 h), the SR schema-by-id cache; **payloads are never cached** |
| ADR-017 CEL smart filters | the only user-programmable predicate; id scheme, budgets, the `filterSource` fallback that makes replicas work |
| ADR-023 audit and masking | the masking rule model, where masking runs, and that produce is never masked |
| ADR-026 paging cursors | the signed self-describing cursor and the page mode that needs no cursor at all |
| ADR-028 serde plugin API | the `Serde[F]` SPI, resolution order, the mandatory `Fallback` |
| ADR-029 event tracking | tracking semantics, the page mode, resend |
| ADR-030 minimum broker version | `offsetsForTimes` fallbacks and capability gating rather than version tests |
| ADR-032 navigation state model | how `Degraded` / `Unavailable` / `NotConfigured` render, and the stale-data rule DC-H3 |
| ADR-034 error envelope | which failures become which `KUI-*` code, and what an `error` SSE event carries |
| ADR-035 streaming envelope | the event names, the one-terminal-event rule, the heartbeat, the cancellation chain |
| ADR-037 upstream resilience | the gateway's per-upstream timeout, which must **not** apply to a stream body |
| ADR-039 capability fold | when the `message` capability is `Degraded` (SR unreachable) versus `Unavailable` (service down) |
| ADR-041 layering, machine-enforced | A1, A3, A5, A9, A10 and the two rules M3 adds (§5.3) |
| ADR-043 internal service calls | how the message service gets a `ClusterProfile`, with a cached last-known fallback |

`ARCHITECTURE.md` sections: §4.3 (`MessageBrowsePort`), §4.4 (`SerdeRegistry`), §7 (the SSE
envelope, byte for byte), §8 (cursors), §9 (the `message` row of the caching table), §15 (error
envelope), §16 (module layout).

`research/kafka/admin-capabilities.md` §4 is the **behavioural source** for every consumer call in
this milestone and it outranks any sketch in `ARCHITECTURE.md`: the `offsetsForTimes(0)` fallback for
compacted topics, "an empty poll does not mean end of partition", position-based termination,
pause/resume windowing, control records inflating `end − begin`, and `wakeup`/`InterruptException` on
cancellation. §2 "Purge" is the source for MS-008.

`research/kafbat/api-analysis.md` Finding 5 decides the request shape (polling modes, seek
resolution, clamping, limit defaults, `max.poll.records = limit`, the filter semantics) and Finding
10 decides Kouncil's page and track shapes. `research/kafbat/ui-analysis.md` "Messages" and
`research/kouncil/ui-analysis.md` (DC-H4, DC-H8, DC-H11) decide what the screens *do*.
`research/design/REFERENCE.md` decides how they *look*, and it overrides DC-H9 for the message
detail specifically (§10, D3).

`docs/domain/message.md` is created by this milestone (MSG-017) and updated by every task that adds
a model type.

## 5. Module map

M3 creates **twelve** Mill modules and changes **eleven**. A cross-compiled module has `.jvm` and
`.js` children (`KuiModule` traits in `build.mill`).

### 5.1 New modules

| Path | Mill id | Platforms | Depends on | Purpose |
| --- | --- | --- | --- | --- |
| `libs/serde` | `libs.serde` | JVM | `libs.kernel.jvm`, `libs.observability`, circe-core/parser | the `Serde[F]` SPI, the built-in primitive serdes, `SerdeRegistry`, resolution order, the mandatory `Fallback` |
| `libs/serde-confluent` | `libs.serdeConfluent` | JVM | `libs.serde`, `libs.kernel.jvm`, Confluent 8.3.1, avro, scalapb, json-schema-validator | the only module with Confluent, Jackson or Guava on its classpath; Schema-Registry-backed Avro / Protobuf / JSON Schema serdes and the SD-004 JSON Schema generator |
| `libs/filter` | `libs.filter` | JVM | `libs.kernel.jvm`, `libs.cache`, cel 0.14.0 | `MessageFilterPort[F]` over cel-java, the compiled-program cache, the string-contains filter |
| `services/message/domain` | `services.message.domain` | JVM | `libs.kernel.jvm`, cats-core | `BrowseRequest`, `SeekPlan`, `TrackQuery`, `ResendRequest`, `MaskingPolicy` reference, and the ports |
| `services/message/application` | `services.message.application` | JVM | `domain`, `libs.serde`, `libs.filter`, `libs.securityCore.jvm`, `libs.cache` | the browse / tail / page / produce / resend / track / purge use cases, the cursor codec, the envelope event stream |
| `services/message/infrastructure` | `services.message.infrastructure` | JVM | `domain`, `libs.kafka`, `libs.serde`, `libs.serdeConfluent`, `libs.http`, `libs.cache`, **`services.cluster.client`** | the adapters: `MessageBrowsePort`, the serde registry, and a thin `ClusterProfileSource` **over M2's shared `services/cluster/client`** — M3 writes no HTTP profile protocol of its own (ADR-046) |
| `services/message/contract` | `services.message.contract.{jvm,js}` | JVM + JS | `libs.contractsCore.*`, `libs.securityCore.*` | the endpoints, the DTOs and the SSE event payload types the browser decodes |
| `services/message/api` | `services.message.api` | JVM | `application`, `contract.jvm`, `libs.http`, `libs.observability` | server logic, the SSE wiring, `KuiError` → envelope |
| `services/message/app` | `services.message.app` | JVM | `api`, `infrastructure`, `libs.config`, `libs.kafka`, `libs.http` | the composition root and `main` |
| `frontend/ui-messages` | `frontend.uiMessages` | JS | `frontend.uiKernel`, `services.message.contract.js` | both views, the filter bar, the drawers, the track page |

`libs/serde` is a separate module from `libs/serde-confluent` for the reason ADR-014 gives and that
is worth restating so nobody merges them: the Confluent stack drags Jackson and Guava onto any
classpath it touches, and a deployment that has no Schema Registry must be able to run without it.
`libs/serde` alone is enough to browse a topic of JSON, strings and integers.

### 5.2 Changed modules

| Mill id | Change | Task area |
| --- | --- | --- |
| `libs.kernel.{jvm,js}` | gains the pure `kui.kernel.browse` package: `SeekMode`, `Direction`, `IsolationLevel`, `PollBudget`, `OffsetRange`, `PartitionId`, `Offset`. Cross-compiled and dependency-free, so the domain, `libs/kafka`, the contract and the browser share **one** definition (§10, D1) | MSG-001 |
| `libs.kafka` | gains `kui.kafka.consume`: the consumer and producer factories, `MessageBrowsePort` and its adapter, the window walker, `deleteRecords` | MSG-002 … MSG-008 |
| `libs.securityCore.{jvm,js}` | gains the masking engine (`MaskingRule`, `MaskingEngine`) as pure functions over `io.circe.Json` (ADR-023) | MSG-016 |
| `libs.cache` | gains `BoundedCache` (Caffeine), whose first consumers are the compiled-filter cache and the SR schema cache — the M1 plan (§10 D2) deferred it to exactly this point | MSG-015 |
| `libs.config` | gains the `kui.message.*` slice and the `kui.clusters[].serde` / `kui.clusters[].masking` sub-slices | MSG-011, MSG-041 |
| `libs.contractsCore.{jvm,js}` | gains the `Section`-free shared fragments the message contract and the gateway both use: `DecodedPayloadDto`, `MessageHeadersDto` | MSG-027 |
| `libs.testkit` | gains the seeded-topic fixtures (shapes, sizes, tombstones, non-JSON, a 1 M-record partition) and a Schema Registry container | MSG-042 |
| `services.gateway.{contract,api,application}` | the message proxy routes, the streaming fan-in, the `message` capability entry, the KU-016 permission hook | MSG-031 |
| `frontend.uiKernel` | gains `SseStream` (typed named-event client over an abortable fetch) and `Drawer` | MSG-033 |
| `frontend.uiShell` | the `ui-messages` routes, the drawer host, the CSS module list | MSG-034 |
| `apps.allinone`, `deployment.{docker,compose}`, `e2e` | the message service in the composition root, `kui.message.*` and `kui.streaming.cursorKey` in every environment, a Schema Registry in Compose, the two new E2E specs | MSG-041, MSG-046 |

**New dependency edges, and why each is legal.** `libs.serde → libs.kernel.jvm`,
`libs.serde → libs.observability`; `libs.serdeConfluent → libs.serde`; `libs.filter → libs.cache`;
`services.message.application → {domain, libs.serde, libs.filter, libs.securityCore.jvm, libs.cache}`
— none of those is a wire module, so rule A3 still holds, and none has a Kafka client, so A10 still
holds; `services.message.infrastructure → {libs.kafka, libs.serdeConfluent}` (an `infrastructure`
module, which is what A9/A10 allow); `services.message.app → infrastructure`;
`frontend.uiMessages → services.message.contract.js`. No `libs` module depends on a service (A5).
The gateway gains no Kafka, serde or CEL edge of any kind (A8, and the two new rules below).

### 5.3 Two rules M3 adds to `checkArchitecture`

Rule numbers are allocated in **ADR-041 Amendment 4** and nowhere else. M3's two rules were groomed
as "A11" and "A12" while M2 was independently defining a different A11; the gate review renumbered
M3's to **A12** and **A13**. A11 is M2's service-to-service rule, which M3 relies on (§10 D2).

| Rule | What it forbids | Why |
| --- | --- | --- |
| A12 | `libs.serdeConfluent`, `io.confluent.*`, Jackson or Guava on the classpath of any module other than `libs/serde-confluent` itself, a service's `infrastructure`, `libs/testkit` or an `app` | ADR-014 makes the Confluent stack an *optional runtime* dependency. The moment an `application` module can see a Confluent class, the deployment that runs without a Schema Registry stops compiling in someone's head and starts failing at runtime |
| A13 | `libs.filter`, `dev.cel.*` or `re2j` on the classpath of any module other than `libs/filter`, a service's `application`, `libs/testkit` or an `app` | CEL is user-supplied code. The set of modules that can evaluate it must be small enough to audit, and it must never include the gateway or a `contract` module |

`services/message/application` is on A13's allow-list because `MessageFilterPort[F]` is a pure port
consumed by the browse use case. That is the one exception, it is deliberate, and MSG-047's build
test names it, so a second exception has to be argued in the commit that changes the rule.

## 6. Task graph

48 tasks. Sizes: **S** ≈ 1–2 h, **M** ≈ 2–4 h, **L** ≈ 4–6 h. Every task ends on a green `main`: a
task that adds a module also adds that module's first test, and a task that changes a contract
regenerates the committed OpenAPI document in the same commit.

### 6.1 Parallel lanes

| Lane | Tasks | Owner role | Owns |
| --- | --- | --- | --- |
| **A — Kafka consume and produce** | MSG-001 … MSG-008 | Principal Scala Engineer | `libs/kernel/src/kui/kernel/browse/**`, `libs/kafka/src/kui/kafka/consume/**` |
| **B — Serdes** | MSG-009 … MSG-014 | Principal Scala Engineer | `libs/serde/**`, `libs/serde-confluent/**`, the `kui.clusters[].serde` slice |
| **C — Filters and masking** | MSG-015, MSG-016 | Principal Scala Engineer | `libs/filter/**`, `libs/security-core/src/kui/security/masking/**`, `libs/cache`'s `BoundedCache` |
| **D — Message domain and application** | MSG-017 … MSG-024 | Domain Architect (message) | `services/message/{domain,application}/**`, `docs/domain/message.md` |
| **E — Message adapters** | MSG-025, MSG-026 | Domain Architect (message) | `services/message/infrastructure/**` |
| **F — Contract, API, app and edge** | MSG-027 … MSG-032 | Chief Architect | `services/message/{contract,api,app}/**`, `services/gateway/**`, the message fragments of `libs/contracts-core`, `docs/api/*` |
| **G — Frontend** | MSG-033 … MSG-040 | Frontend Architect | `frontend/ui-messages/**` and the named additions in `frontend/ui-kernel` (MSG-033) and `frontend/ui-shell` (MSG-034) |
| **H — Configuration, fixtures, QA, operations** | MSG-041 … MSG-048 | Infrastructure Lead + QA Engineer | the `kui.message.*` slice, `libs/testkit/**`, `build.mill` rules, `apps/allinone/**`, `deployment/**`, `e2e/**`, `docs/benchmarks/**`, `docs/operations/*`, `docs/FEATURE_MATRIX.md`, `STATUS.md`, `TECH_DEBT.md` |

Lane A unblocks D and E. Lanes B and C are independent of A and can start on day one. Lane F can
start as soon as MSG-017 exists (it needs the domain types to shape DTOs, not the adapters that fill
them). Lane G can start immediately on MSG-033, which has no backend dependency, and joins the graph
at MSG-031.

### 6.2 Ordered task list

| ID | Title | Size | Depends on | Lane |
| --- | --- | --- | --- | --- |
| MSG-001 | `libs/kernel`: the browse vocabulary (`SeekMode`, `Direction`, `PollBudget`, `OffsetRange`) | M | — | A |
| MSG-002 | `libs/kafka`: consumer and producer factories with a per-request `Resource` | M | MSG-001 | A |
| MSG-003 | `resolveRanges`: seek resolution, leaderless and empty filtering, compacted fallback, clamping | L | MSG-002 | A |
| MSG-004 | `pollRanges`: assign / seek / pause / resume, position-based termination, budgets | L | MSG-003 | A |
| MSG-005 | The backward window walker | M | MSG-004 | A |
| MSG-006 | `tail`: seek-to-end, endless poll, throttle hook, cancellation | M | MSG-004 | A |
| MSG-007 | `produce` and `deleteRecords` (purge) | M | MSG-002 | A |
| MSG-008 | `MessageBrowsePort` Testcontainers matrix: every seek mode, both directions, 1 M records | L | MSG-005, MSG-006, MSG-007, MSG-042 | A |
| MSG-009 | `libs/serde`: the module, the SPI, `DeserializeResult` and the mandatory `Fallback` | M | — | B |
| MSG-010 | Built-in serdes: String, Int32/64, UInt32/64, UUID, Base64, Hex, JSON, with auto-detection | L | MSG-009 | B |
| MSG-011 | `SerdeRegistry` / `ClusterSerdes`: resolution order, suggestions, the `kui.clusters[].serde` slice | L | MSG-010 | B |
| MSG-012 | `libs/serde-confluent`: the registry client, the Avro / Protobuf / JSON Schema deserializers, the caches | L | MSG-011 | B |
| MSG-013 | `libs/serde-confluent`: serializers and the SD-004 JSON Schema for the produce form | M | MSG-012 | B |
| MSG-014 | Spring DLT and retry numeric header decoding | S | MSG-010 | B |
| MSG-015 | `libs/filter`: the CEL port, `BoundedCache`, the id scheme, the string filter, budgets | L | — | C |
| MSG-016 | The masking engine in `libs/security-core` | M | — | C |
| MSG-017 | Message domain: the model and its ports | M | MSG-001 | D |
| MSG-018 | The signed cursor codec | M | MSG-017 | D |
| MSG-019 | `BrowseUseCase`: the envelope event stream, merge-sort, filters, masking, budgets | L | MSG-017, MSG-018, MSG-011, MSG-015, MSG-016 | D |
| MSG-020 | `TailUseCase` with the 20 events/second throttle and no `done` | M | MSG-019 | D |
| MSG-021 | `PageUseCase`: Kouncil per-partition newest-first paging | M | MSG-019 | D |
| MSG-022 | `ProduceUseCase` (with `count`) and `ResendUseCase` | L | MSG-017, MSG-011 | D |
| MSG-023 | `TrackUseCase`: the bounded multi-topic scan | L | MSG-019 | D |
| MSG-024 | `PurgeUseCase` and the serde suggestion use case | S | MSG-017, MSG-011 | D |
| MSG-025 | `services/message/infrastructure`: the module and the browse / produce adapters | M | MSG-017, MSG-004, MSG-007 | E |
| MSG-026 | The cluster profile source and the serde registry adapter, with the SR probe | L | MSG-025, MSG-012 | E |
| MSG-027 | Message contract: DTOs, the SSE event payload types, golden files | L | MSG-017 | F |
| MSG-028 | Message endpoints: stream, page, produce, resend, purge, serdes, filters, track | M | MSG-027 | F |
| MSG-029 | Message `api`: server logic, the SSE wiring, the error envelope | L | MSG-028, MSG-019, MSG-020, MSG-021, MSG-022, MSG-023, MSG-024 | F |
| MSG-030 | Message `app`: the composition root and the cancellation-safe `Resource` chain | L | MSG-029, MSG-026, MSG-041 | F |
| MSG-031 | Gateway: message routes, the streaming fan-in, the capability entry, the KU-016 hook | L | MSG-028 | F |
| MSG-032 | OpenAPI regeneration, the error-code table, the contract snapshot | S | MSG-031, MSG-029 | F |
| MSG-033 | `ui-kernel`: the typed `SseStream` client and `Drawer` | M | — | G |
| MSG-034 | `ui-messages`: the module, the typed clients, the routes and the topic-tab seam | M | MSG-027, MSG-033 | G |
| MSG-035 | The filter bar: seek and polling modes, partitions, serdes, string filter, live toggle | L | MSG-034, MSG-031 | G |
| MSG-036 | The list view: rows, expand-in-place detail, the fallback-serde marker, copy and export | L | MSG-035 | G |
| MSG-037 | The table view and the JSON flattener | L | MSG-036 | G |
| MSG-038 | The produce and resend drawers, with bulk placeholders | L | MSG-036 | G |
| MSG-039 | The smart-filter editor and saved filters | M | MSG-035 | G |
| MSG-040 | The Track page | L | MSG-036 | G |
| MSG-041 | The `kui.message.*` configuration slice and every environment that carries it | M | MSG-001 | H |
| MSG-042 | `libs/testkit`: seeded topic fixtures and a Schema Registry container | L | — | H |
| MSG-043 | The wire seam suite: one byte fixture, two decoders | M | MSG-027, MSG-033 | H |
| MSG-044 | The cancellation suite: browser disconnect to consumer close | M | MSG-029, MSG-030 | H |
| MSG-045 | Benchmarks | M | MSG-030, MSG-042 | H |
| MSG-046 | Fault-isolation E2E: the message service stopped, the registry stopped | L | MSG-030, MSG-037, MSG-041 | H |
| MSG-047 | `checkArchitecture` rules A12 and A13, with their build tests | S | MSG-012, MSG-015 | H |
| MSG-048 | Milestone documentation, operator pages, feature matrix, ADR amendments | M | everything | H |

**The table is grouped by lane, not topologically sorted.** Four edges point backwards in the
listing — MSG-042 precedes MSG-008, MSG-041 precedes MSG-030, MSG-031 precedes MSG-035, MSG-033
precedes MSG-043. Read the `Depends on` column, never the row order.

### 6.3 Critical path

The longest chain of real dependencies in §6.2 — every arrow is an edge that table actually
declares:

```
MSG-001 → MSG-002 → MSG-003 → MSG-004 → MSG-005 → MSG-019 → MSG-029 → MSG-030
  → MSG-046 → MSG-048
```

10 tasks, roughly 42 working hours of single-threaded effort. It runs through the **offset math**,
not through the streaming plumbing, which is the most useful thing to know about this graph: the
part of the milestone that looks like the risk (SSE, cancellation, the browser) is downstream of the
part that actually is (resolving and walking offset windows correctly).

A **second chain of almost equal length** runs down the read path and must be worked in parallel from
day one, or it becomes the critical path by default:

```
MSG-017 → MSG-027 → MSG-028 → MSG-031 → MSG-034 → MSG-035 → MSG-036 → MSG-037 → MSG-046
```

It shares only its terminal task with the critical path. The serde chain
(MSG-009 → MSG-010 → MSG-011 → MSG-012 → MSG-013) has real slack: it joins at MSG-019 and MSG-026.

### 6.4 What to do first, and why

Five tasks are worth starting before anything else, not because they are on a path but because each
answers a question whose wrong answer invalidates work already done by the time the question
surfaces.

1. **MSG-001 — where the browse vocabulary lives.** `ARCHITECTURE.md` §4.3 writes
   `MessageBrowsePort` as taking a `ClusterProfile` and returning types declared inside
   `kui.kafka.consume`, and §7's SSE envelope shows the *browser* receiving offsets and a seek mode.
   Under rules A1 and A5 the domain cannot see `libs/kafka`, and a Scala.js module certainly cannot.
   Either the vocabulary moves somewhere all four can see, or it is declared four times. §10 D1
   decides it; MSG-001 executes the decision. Getting this wrong is a rewrite of the port, the
   domain, the contract and the browser.
2. **MSG-004 — does position-based termination actually terminate?** "An empty poll does not mean
   end of partition" and "control records occupy offsets so `end − begin` overstates the count"
   (`research/kafka/admin-capabilities.md` §4) are the two facts that make a naive loop hang on a
   transactional or compacted topic. A hang inside a stream is the worst failure shape in this
   milestone: the browser shows a spinner and the consumer stays open. It needs proving against a
   real broker early.
3. **MSG-042 — do the fixtures exist?** Every integration assertion in this milestone needs a topic
   with a known shape: a partition with a million records, tombstones, records that are not JSON,
   a compacted topic whose log start offset is far above zero, and a Schema Registry container.
   Discovering in week three that seeding a million records takes eleven minutes per suite run is
   discovering it too late.
4. **MSG-015 — how large is CEL, really?** ADR-017 records a ~10 MB transitive graph including
   protobuf-java and re2j. If `dev.cel:cel:0.14.0` does not resolve, does not compile under
   `-Werror`, or drags a protobuf version that collides with the one `libs/serde` pins, that is a
   dependency decision, not a coding task, and it must surface while there is time to take it.
5. **MSG-033 — can the kernel parse a named-event stream at all?** M0's spike measured Netty's
   *server* side (`docs/spikes/M0-netty-sse.md`). Nothing has yet parsed named events with `id:` in
   the browser through an abortable fetch, and the entire frontend lane sits on top of it.

### 6.5 Area boundaries

Eight agents write the task specs. These are the boundaries; a file appears in exactly one row.

| Area | May create or change | Must not touch |
| --- | --- | --- |
| Lane A | `libs/kernel/src/kui/kernel/browse/**`, `libs/kafka/src/kui/kafka/consume/**` and their tests, their `build.mill` module definitions | `libs/kafka/src/kui/kafka/admin/**` (M1's, and stable), `libs/serde`, `services/`, `frontend/` |
| Lane B | `libs/serde/**`, `libs/serde-confluent/**`, `libs/config/src/kui/config/serde/**` | `libs/kafka`, `libs/filter`, `services/`, `frontend/` |
| Lane C | `libs/filter/**`, `libs/security-core/src/kui/security/masking/**`, `libs/cache/src/kui/cache/BoundedCache.scala` | everything else |
| Lane D | `services/message/{domain,application}/**`, `docs/domain/message.md` | `services/message/{infrastructure,contract,api,app}`, any `libs` module |
| Lane E | `services/message/infrastructure/**` | every other module in the message tree; `build.mill`'s rule table (MSG-047 owns it) |
| Lane F | `services/message/{contract,api,app}/**`, `services/gateway/**`, `libs/contracts-core/src/kui/contracts/message/**`, `docs/api/*` | `services/message/{domain,application,infrastructure}`, `frontend/` |
| Lane G | `frontend/ui-messages/**`, and the named additions in `frontend/ui-kernel` (MSG-033) and `frontend/ui-shell` (MSG-034) | any backend module; `frontend/ui-topics` and `frontend/ui-clusters` |
| Lane H | the `kui.message.*` slice in `libs/config`, `libs/testkit/**`, `build.mill` rules, `apps/allinone/**`, `deployment/**`, `e2e/**`, `docs/benchmarks/**`, `docs/operations/*`, `docs/ROADMAP.md`, `docs/FEATURE_MATRIX.md`, `STATUS.md`, `TECH_DEBT.md` | `libs/serde*`, `libs/filter`, every service's own modules |

Two shared files need a rule rather than an owner. **`build.mill`** is edited by six areas (each new
module declares itself); the rule is that a task edits only the `object` it creates plus the
`moduleDeps` line of the module it is wiring, and MSG-047 alone edits the architecture rule table.
**`docs/FEATURE_MATRIX.md`** is edited only by MSG-048, at the end, from the evidence the other tasks
left; no task flips its own row.

**One cross-milestone boundary.** `frontend/ui-shell`'s route table is edited by M2's topic lane and
by MSG-034 in the same window. MSG-034 adds **only** its own route entries and the drawer host, in
one commit, and must rebase rather than reformat. It is the single file M2 and M3 share.

### 6.6 The seams, and the tasks that test them

The M0 review found that nothing tested the seams between components, and that is where the worst
defects lived. M1 found two more of exactly that kind: a configuration section parsed and silently
discarded, and a browser decoding a document nobody sends — both passed every unit test on both
sides. M3 has five seams and each one has a task whose only job is to test it:

| Seam | The failure it would hide | Task |
| --- | --- | --- |
| Service SSE encoder ↔ browser SSE parser | the M1 defect exactly: both sides green, no rows on screen | MSG-043 — one committed byte fixture per event type, read by a JVM suite and a Scala.js suite |
| Browser disconnect ↔ Kafka consumer close | a leaked consumer per abandoned tab; invisible until a broker runs out of connections | MSG-044, and again through a real browser in MSG-046 |
| Cursor minted by replica A ↔ consumed by replica B | works in development, breaks behind a load balancer | MSG-018's suite plus `MessagePageReplicaSuite` in MSG-030 |
| Configuration parsed ↔ configuration used | M1's `AllInOneConfig` defect: `kui.message.*` decoded, validated and then never handed to the use case | MSG-041 — an all-in-one test asserts a non-default `maxPageSize` changes the observed behaviour of `GET .../messages/page`, not merely that the value parsed |
| Serde decode failure ↔ the stream that must not fail | a topic of undecodable bytes shows an empty screen instead of the bytes | MSG-043's `deserializeErrors` fixture and MSG-019's `SerdeFallbackSuite` |

### 6.7 Every rule this plan states, and what enforces it

Documented rules that nothing enforces get violated. Each row is a rule stated in this plan or in an
ADR that M3 implements, and the check that fails when it is broken.

| Rule | Enforced by |
| --- | --- |
| No module outside the allow-list sees Confluent, Jackson or Guava | `./mill checkArchitecture` rule A12 (MSG-047) |
| No module outside the allow-list sees CEL | rule A13 (MSG-047) |
| A string that crosses a process boundary is declared once | `services/message/contract` is cross-compiled; the browser and the service compile against the same file. MSG-043's byte fixture fails if either side drifts |
| Exactly one terminal event per stream | `libs/http`'s `Sse` enforces it at runtime; `SseWireSeamSuite` asserts it for each terminating path |
| Deserialization failure never aborts a stream | `SerdeFallbackSuite` (MSG-019) and the `deserializeErrors` byte fixture (MSG-043) |
| Nothing unmasked leaves the service | `MaskingBoundarySuite` (MSG-029), asserted against a distinctive token |
| Backward browsing never loads a whole partition | `MessageBackwardWindowSuite` counts fetched records (MSG-005, MSG-008) |
| Every cancellable path has a cancellation test | §9 item 10, and the "Cancellation and shutdown" section that every lane A, D, E and F spec carries |
| Payloads are never cached | `MessageCacheSuite` (MSG-026) asserts the only caches present are the serde, filter and schema caches named in ADR-016 |
| No `services/message` → `services/topic` call | rule **A11** (M2, ADR-041 Amendment 4) forbids a service any edge to another service's modules but `contract` and `client`; MSG-047's build test additionally asserts `services.message.*` has no `services.topic.contract` edge either |

## 7. Test plan

Test kinds follow PLAN §32 and ADR-018. **MUnit is the only framework**; no mocking library; fakes
live in `libs/testkit`. A Scala.js test module and a JVM test module cannot run in one Mill
invocation (`CLAUDE.md`), so lane G's suites are always a separate command.

| Suite | Where | Runner | What it covers |
| --- | --- | --- | --- |
| Browse vocabulary | `libs.kernel.{jvm,js}.test` | MUnit + ScalaCheck | `SeekMode` round-trips; `OffsetRange` invariants (`from <= until`); `PollBudget` arithmetic never goes negative; it all compiles and runs on Scala.js |
| Seek resolution | `libs.kafka.test` | MUnit + ScalaCheck | the property suite shared by the stream and the page: clamping into `[begin, end)` is idempotent; an unresolved `offsetsForTimes` means "end" backward and "nothing" forward; empty and leaderless partitions are excluded; a compacted topic whose log start is above zero resolves through the `offsetsForTimes(0)` fallback |
| Window walking | `libs.kafka.test` | MUnit + ScalaCheck | windows tile `[begin, to)` without gaps or overlap; the walker terminates for every `(limit, partitionCount, range)`; **records fetched never exceed `limit + window`** |
| Browse adapter | `libs.kafka.test` | MUnit + Testcontainers | every seek mode against a live broker; an empty poll does not terminate a partition; a transactional topic under `read_committed` terminates on position; `tail` sees a record produced after it started |
| Cancellation, port level | `libs.kafka.test` | MUnit + `munit-cats-effect` | cancelling the fiber running `pollRanges` finalises the consumer `Resource`; asserted by a `Ref` the fixture's `Resource` sets on release, and by `kui.message.consumers.active` returning to zero |
| Produce and purge | `libs.kafka.test` | MUnit + Testcontainers | round-trip of key, value and headers as bytes; `deleteRecords` moves the low watermark and reports it per partition; a compacted topic without `delete` gives `PolicyViolationException` mapped to `KUI-VALIDATION` |
| Serde SPI | `libs.serde.test` | MUnit + ScalaCheck | resolution order (pattern → explicit → default → String); the `Fallback` always resolves; every built-in round-trips; auto-detection prefers JSON over String for a JSON payload and never claims a payload it cannot decode |
| Confluent serdes | `libs.serdeConfluent.test` | MUnit + Testcontainers (Kafka + Schema Registry) | magic-byte id extraction; an unknown schema id is a `DeserializeFailure`, not an exception; the registry being down degrades to the fallback within the configured timeout and is reported once, not per record |
| Filters | `libs.filter.test` | MUnit + ScalaCheck | the id is `sha256(source)[0,16)` and is stable across processes; a compile error is `KUI-FILTER-COMPILE` with the position; a non-boolean result and a runtime error both count as `filterErrors` and never throw; a program that loops is stopped by the per-record time budget; the source-size limit is enforced before compilation |
| Masking | `libs.securityCore.{jvm,js}.test` | MUnit + ScalaCheck | `remove` / `mask` / `replace` over arbitrary JSON; `keep{prefix,suffix}`; a non-JSON value takes the first policy's string form; masking is idempotent; **no rule can ever lengthen a value** (a mask that grows a payload is how a masked field leaks through a length side channel) |
| Cursor | `services.message.application.test` | MUnit + ScalaCheck | round-trip for arbitrary partition maps; a tampered payload fails the HMAC; expiry; cluster and topic binding; a 1 000-partition cursor is rejected with `KUI-CURSOR-TOO-LARGE` rather than truncated |
| Browse use case | `services.message.application.test` | MUnit + `munit-cats-effect` + `TestControl` + fake ports | the event order of ADR-035; merge-sort by timestamp with per-partition offset order preserved; the `limit` is honoured exactly; a filter that throws increments `filterErrors` and does not abort; a decode failure emits the fallback and a `deserializeErrors` entry; a budget exhaustion ends with `done{reason:"budget"}`; tailing emits no `done` and is throttled to 20 events/s under virtual time |
| Track use case | `services.message.application.test` | MUnit + `TestControl` + fake ports | topics are scanned smallest-range-first; the record cap ends the stream with `done{reason:"limit"}`; a regex that backtracks is stopped by the match timeout; `correlationKey` groups events without joining them |
| Message contract | `services.message.api.test` | MUnit + Tapir stub interpreter | every endpoint's success and error paths without a socket; `MaskingBoundarySuite`; a mid-stream failure produces `event: error` and terminates; validation failures **before** the stream starts are ordinary HTTP envelopes, not SSE errors |
| Wire seam | `services.message.contract.{jvm,js}.test` | MUnit, both platforms | the committed byte fixtures decode to the same values on the JVM and in the browser build |
| Adapters | `services.message.infrastructure.test` | MUnit + Testcontainers | the adapter satisfies the same fake-port contract the application suites use, against a live broker; the profile source serves the last known profile while `/internal/v1` is down and reports `Degraded` |
| Gateway | `services.gateway.api.test` | MUnit + stub upstream | the stream is proxied without buffering beyond the bounded queue; the per-upstream **request** timeout does not apply to a stream body; cancelling the client cancels the upstream request; the `message` capability entry follows ADR-039 |
| Frontend unit | `frontend.uiMessages.test` | MUnit under Node | the flattener's properties; the filter bar's parameter serialisation; the row state table (decoded / fallback / masked) |
| Frontend DOM | `frontend.uiMessages.test` with `JsEnvConfig.JsDom()` | MUnit + `scala-dom-testutils` | a row expands in place; the produce drawer traps focus and restores it on close; the live toggle stops the stream |
| All-in-one integration | `apps.allinone.test` | MUnit + `munit-cats-effect` + Testcontainers | the whole graph boots; a non-default `kui.message.maxPageSize` is observable through the API (the §6.6 configuration seam) |
| E2E | `e2e.test` | JVM Playwright + Testcontainers Compose | browse, produce, resend, track; `docker stop kui-message` stops live mode with a toast and leaves fetched rows greyed; stopping the registry leaves non-SR browsing working; closing the tab closes the consumer |

**Testcontainers in M3:** Kafka (PLAINTEXT is enough — M1 already proved the security matrix, and
re-running it here would double the suite time for no new information) plus a Schema Registry
container, used only by `libs.serdeConfluent.test` and the E2E stack.

**Fault-injection scenarios in M3:**

1. The message service stopped — the milestone's headline scenario (MSG-046).
2. The Schema Registry stopped while browsing continues on non-SR serdes (MSG-046).
3. A broker slowed past the per-call timeout mid-stream: the stream ends with `event: error`
   carrying `KUI-TIMEOUT`, not with a dropped connection (MSG-029).
4. A topic the principal may read but whose partitions are partly leaderless: the leaderless ones are
   excluded and named in the `phase` event rather than timing out the request (MSG-003).
5. The cluster profile source unreachable: browsing continues from the last known profile and the
   capability reports `Degraded` (MSG-026).

## 8. Risk register

| ID | Risk | Impact | Mitigation | Mitigating task(s) |
| --- | --- | --- | --- | --- |
| R-1 | A browse stream **hangs** rather than fails: an empty poll is read as "not done", the partition never reaches its `until`, the consumer stays open and the browser spins | The worst failure shape in this milestone, and invisible to a functional test that only checks the happy path | Termination is on `position >= until` per partition, never on record count or on empty polls; every stream carries a `deadline` from `PollBudget` and ends with `done{reason:"budget"}`; a Testcontainers case runs against a transactional topic where control records make `end − begin` overstate the count | MSG-004, MSG-008 |
| R-2 | Backward browsing loads a whole partition on a topic with a million records | The product is unusable on exactly the topics operators care about, and it is discovered in production | The window walker is a pure function tested by property; the integration suite counts fetched records against `limit + window` on a 1 M-record partition | MSG-005, MSG-008 |
| R-3 | A cancelled browser tab leaves a consumer open | Broker connection exhaustion after a day of use; nothing in the UI ever shows it | The consumer is an fs2-kafka `Resource` inside the stream; a gauge counts live consumers; three tests at three levels assert it returns to zero — port, service and real browser | MSG-002, MSG-044, MSG-046 |
| R-4 | The service's SSE bytes and the browser's parser disagree, both suites stay green | Precisely the M1 dashboard defect, on the milestone's headline screen | One committed byte fixture per event type, decoded by a JVM suite and a Scala.js suite from the same file | MSG-043 |
| R-5 | `dev.cel:cel:0.14.0` does not resolve, does not compile under `-Werror`, or collides with `libs/serde`'s protobuf-java | A P1 feature cannot be built as designed, discovered late | MSG-015 is one of the five first-movers and its first act is to resolve, compile and evaluate one program; the documented fallback is that MS-006's string filter ships and MS-007 moves to M5 with a `TECH_DEBT.md` row — the browse pipeline takes a `MessagePredicate`, so the CEL implementation is one of its instances rather than the shape of the pipeline | MSG-015, MSG-019 |
| R-6 | Confluent 8.3.1's Community License review (a `DEPENDENCY_MATRIX.md` open question due in M3) comes back negative | `libs/serde-confluent` cannot ship | The module is optional at runtime and its absence degrades to non-SR serdes by design; if the review fails, the module is excluded from the distributed image and the operator adds it — one packaging change, no code change | MSG-012, MSG-048 |
| R-7 | Masking is applied in one path and forgotten in another (the table endpoint's `originalValue`, the track results, the resend preview) | A security guarantee that silently does not hold | Masking is applied in **one** function in the application layer through which every record-bearing response passes; `MaskingBoundarySuite` asserts a distinctive token is absent from every endpoint's body, enumerated from `MessageEndpoints.all` so a new endpoint fails the test until it is listed | MSG-016, MSG-019, MSG-029 |
| R-8 | The gateway's per-upstream timeout (ADR-037) kills long streams | Tailing dies after 30 seconds and looks like a Kafka problem | The gateway distinguishes a request timeout from a stream body; the suite asserts a stream still delivering after twice the request timeout is not cancelled, under `TestControl` | MSG-031 |
| R-9 | Two large features — the table view and tracking — arrive late and unfinished | The parity checkpoint slips | The roadmap already allows both to slip to M3.1. The order in §6.2 puts the list view (MSG-036) before the table view (MSG-037) and the track page last, so a slip removes whole features rather than leaving half of each | MSG-037, MSG-040 |
| R-10 | The seeded 1 M-record fixture makes every CI run minutes longer | The suite gets disabled, and with it the milestone's second exit criterion | The fixture is produced once per container with a batched producer and reused by every case in the suite; if it exceeds 60 s to seed, the suite is tagged and runs in the nightly job, which is recorded in the task rather than discovered by a reviewer | MSG-042, MSG-008 |
| R-11 | M3 quietly starts building M4 or M7 — a `GroupAdmin` "for later", a subject-management endpoint because the registry client is already there | Milestone slips; a port designed before its first caller | §3 non-goals are restated in every lane A, B and F task spec, and those specs name the exact files they may create | MSG-002 … MSG-014 |
| R-12 | `frontend/ui-shell`'s route table is edited by M2 and M3 in the same window | Merge conflicts in the one file two milestones share | MSG-034 adds only its own entries, in one commit, and rebases rather than reformats; §6.5 records the rule | MSG-034 |

## 9. Definition of done for M3

M3 is complete when all of the following are true and the evidence is committed:

1. **Every exit criterion in §2 is demonstrated by a command in CI.**
2. All 48 tasks are merged, each with an Implementation Report (PLAN §39, one screen).
3. `./mill __.compile` is clean with `-Werror -Wunused:all -source:future`; `./mill __.test` is green
   on the JVM and, in a separate invocation, on Scala.js; `./mill __.checkFormat` and
   `./mill __.fix --check` are clean; `./mill checkArchitecture` passes with rules A12 and A13 active (A11 is M2's and must already be).
4. `./mill e2e.test` is green against the Compose stack, including both M3 fault-isolation scenarios.
5. `docs/benchmarks/M3-messages.md` records the four benchmark shapes of §2.7 with the machine they
   ran on and the commit that produced them.
6. `GET /api/v1/openapi.json` is regenerated and its snapshot committed under `docs/api/openapi.json`;
   `docs/api/error-codes.md` includes `KUI-CURSOR-EXPIRED`, `KUI-CURSOR-INVALID`,
   `KUI-CURSOR-TOO-LARGE`, `KUI-FILTER-COMPILE` and `KUI-SERDE-UNAVAILABLE`.
7. `docs/FEATURE_MATRIX.md` rows MS-001 … MS-014, MP-001 … MP-004, SD-001, SD-003, SD-004, DM-001,
   ET-001 … ET-003, KU-014, KU-015 and KU-016 are `DONE`.
8. `docs/domain/message.md` documents the aggregate, the ports and their invariants, including the
   two the port inherits from `libs/kafka/PORT-INVARIANTS.md`.
9. `docs/operations/serdes.md` and `docs/operations/masking.md` describe what actually shipped,
   including the Confluent licence position and the "not integration tested" column for any serde
   that has none.
10. **Every long-running or cancellable path introduced in M3 has a named cancellation test.** The
    browse stream, the tail stream, the track scan, the producer resource, the serde registry
    `Resource`, the profile SSE listener, the gateway fan-in and the `app` bootstrap chain each carry
    a "Cancellation and shutdown" requirement in their spec and at least one test that cancels the
    fiber and asserts the resource was released and nothing was left running.
11. ADR-017, ADR-026, ADR-029 and ADR-035 carry consequence notes recording what the implementation
    learned — in particular the measured cursor size, the CEL evaluation cost per record and the
    observed cancellation latency.
12. `ARCHITECTURE.md` §4.3, §4.4, §7 and §9 are updated where an M3 task found a delta; §4.3's sketch
    signatures are replaced by links to the implementing files.
13. `libs/kafka/PORT-INVARIANTS.md` §2 is amended to name M4 rather than M3 as its owner (§10, D5).
14. `TECH_DEBT.md` records every debt taken during M3, and `STATUS.md` records CEO acceptance with the
    CI run id that produced the evidence.
15. A developer who has never seen the repository can follow `README.md`, run the quickstart, open the
    seeded `orders` topic and see its 111 messages, publish one, and watch it arrive in live mode.

## 10. Decisions taken in this plan rather than escalated

Grooming produces decisions, not questions (PLAN §39). Where the roadmap, an ADR or `ARCHITECTURE.md`
left a gap that a worker would otherwise have to ask about, this plan closes it — from the research
already gathered, not from opinion.

| # | Gap | Decision | Evidence | Where it lives |
| --- | --- | --- | --- | --- |
| D1 | `ARCHITECTURE.md` §4.3 declares `SeekMode`, `Direction`, `IsolationLevel`, `PollBudget` and `OffsetRange` inside `kui.kafka.consume`. The domain must state its ports in those terms (A1 forbids it seeing `libs/kafka`), the contract must put a seek mode on the wire, and the **browser** must build one | **The browse vocabulary lives in `libs/kernel`**, in a new pure `kui.kernel.browse` package, cross-compiled. `libs/kafka` implements against it, the domain composes it, the contract encodes it, `frontend/ui-messages` constructs it. `RawRecord` and `PollEvent` stay in `libs/kafka`: they are adapter types no other layer names | Exactly M1's D1, one milestone later and for the same rule. The kernel is already the shared-kernel home of every id type; a seek mode is pure data and cross-compiles unchanged. The alternative is the same ADT written four times, which is the drift the M0 review named | MSG-001; **ADR-035 amendment** by MSG-048 |
| D2 | Browsing needs the topic's partition list. `services/topic` has one. ADR-043 permits a direct `/internal/v1` call | **The message service never calls the topic service.** Partition metadata comes from the browse consumer's own `partitionsFor`, which it must call anyway to find leaders and empty partitions | ADR-043's fourth condition is "no chains", and the gateway already aggregates the topic page. A call would add a failure mode to the milestone's hottest path to avoid one metadata request the consumer makes regardless. The Track page's topic multi-select is a **browser** call to the topic service through the gateway, which is not a service-to-service call at all | MSG-003, MSG-040; enforced by MSG-047's build test |
| D3 | `research/kouncil/ui-analysis.md` DC-H9 puts message detail in a right-hand drawer; `research/design/REFERENCE.md` says a message row expands in place | **Expand in place for message detail; drawers for produce, resend and the filter editor.** The design decides how a screen looks, and it is explicit about this one | The design outranks a decision candidate on appearance (the standing rule for `research/design/REFERENCE.md`). Detail is a *reading* action on a row in a list — an expansion keeps the row's context — while produce and resend are *composition* actions that need their own space and a focus trap. DC-H9's argument was against Kafbat's three different patterns; two consistent patterns chosen by role satisfies it | MSG-036, MSG-038 |
| D4 | ADR-029 says flattening is client-side with "depth 3, collapse thresholds, 1 000-row cap" but no single place owns those numbers | **`FlattenLimits` in `frontend/ui-messages` is the one definition**: `maxDepth = 3`, `maxRows = 1000`, `maxColumns = 120`, `maxArrayElements = 10`. They are constructor parameters of the flattener, so the property suite can drive it at other values, and they appear in no other file | Kouncil's numbers, kept because they are tuned by real users (DC-H8). One definition because a cap written twice is a cap that drifts — the rule the M0 review left | MSG-037 |
| D5 | `libs/kafka/PORT-INVARIANTS.md` §2 assigns `GroupAdmin.describeGroups` and its fabricated-dead-group rule to M3 | **M3 does not create `GroupAdmin`.** The browse consumer is assign-only with no `group.id`, so nothing here describes a group. The invariant's owner is **M4**; MSG-048 amends the file to say so and leaves the text otherwise untouched | `research/kafka/admin-capabilities.md` §4: all three reference products use manual assignment for browsing. Creating `GroupAdmin` here would be the empty-port mistake R-11 exists to prevent | MSG-048; §3 non-goals |
| D6 | The roadmap's cancellation criterion says "asserted via consumer-group membership or a metric". D5 removes the group, so membership cannot be asserted | **The assertion is a metric plus a resource probe**: `kui.message.consumers.active` (an otel4s up-down counter incremented on consumer acquire and decremented on release) returns to zero, **and** the test's own `Resource` finaliser flag is set. Two independent signals, because a gauge that is never decremented and a gauge that is never incremented look identical at zero | A gauge alone can pass for the wrong reason. The finaliser flag proves the `Resource` released; the gauge proves the code path that owns the count ran | MSG-002, MSG-044; §2 criterion 3 |
| D7 | The roadmap and ADR-035 name budgets but no numbers | **The defaults**: `limit` 100, max 500; `maxBytes` 50 MiB per request; `deadline` 60 s (300 s for track); tail throttle 20 events/s; poll timeout 1 s; heartbeat 15 s; `max.poll.records = limit`; cursor expiry 1 h; track cap 1 000 events. All live in `kui.message.*` and every one is overridable | `limit`, poll timeout and the tail throttle are Kafbat's measured values (`research/kafbat/api-analysis.md` Finding 5.2); the track cap is Kouncil's `EVENTS_SANITY_LIMIT`; `maxBytes` and `deadline` are KUI's own additions required by PLAN §22, sized so that the largest legal single page (500 × 1 MiB) is refused rather than buffered | MSG-041 |
| D8 | Where does `services/message` get a `ClusterProfile`? | `GET /internal/v1/clusters/{id}/profile` with the ETag, plus the `clusters/stream` SSE for change notifications, exactly as ADR-043 and ADR-036 describe — with a **last-known-profile cache** that keeps browsing alive while the cluster service is down, and a `Degraded` capability while it is | This is the pattern M1 built and the first service to consume it. Failing browse because the *cluster* service restarted would make the two services one | MSG-026; **ADR-046** |
| D9 | ADR-028 lists fourteen built-in serdes; the feature matrix splits them, putting seven in KU-023 (M5) | **M3 ships exactly the SD-001 set**: String, Int32/64, UInt32/64, UUID, Base64, Hex, JSON, and Schema-Registry Avro / Protobuf / JSON Schema, plus auto-detection by magic byte. Nothing else, and the SPI is designed so that adding one is a file, not a change | The feature matrix is the scheduling authority and it already made this split "so M3 stays bounded". ADR-028 lists the eventual set, not the M3 set | §3 non-goals; MSG-010 |
| D10 | Kafbat's v2 API lost per-partition seek positions; the KUI mapping calls `seekTo[]` "worth keeping as an optional extension" | **`seekTo[]` ships in M3.** `SeekMode.Offset` takes either one offset for all partitions or a per-partition map, and the query parameter accepts `p::offset` pairs | Cursors already carry per-partition offsets (ADR-026), so the machinery exists; without the parameter a user cannot express what a cursor expresses, which is the gap Kafbat's own v1→v2 migration left | MSG-001, MSG-028 |
| D11 | MS-008 (purge) has its UI entry point on the topic page in both reference products, and `frontend/ui-topics` belongs to M2 | **M3 ships the purge endpoint and the action on the messages screen only.** No `ui-topics` file is touched; the topic-page entry point arrives in M5 with audit and read-only mode, which is where every destructive action belongs anyway | ROADMAP ordering rationale §3: no destructive action ships before its safety net. Putting the button where the safety net will be is cheaper than moving it | MSG-024, MSG-038 |
| D12 | Kouncil's track matches a header *or* the whole value; ADR-029 adds `key` and `correlationKey` but does not say what an absent `field` means | **`match.source` is explicit and mandatory**: `value | key | header(name)`. There is no "empty means value" default | Kouncil's frontend sends an index into an operator array and an empty string for "value" (`research/kafbat/api-analysis.md` Finding 10); an API where an omitted parameter silently changes the meaning of another is the shape that produces the wrong results quietly. Making it explicit costs the caller four characters | MSG-023, MSG-028 |
| D13 | ADR-035 says a stream carries `error`, but not what happens to the records already sent when it fires | **`error` is terminal and everything before it stands.** The browser keeps the rows it received, marks the list stale with the reason, and offers a retry that re-issues from the last cursor it holds | ADR-032's stale-data rule DC-H3: data already on screen stays on screen, dimmed and timestamped. Discarding a half page because the last poll failed is the reference behaviour (`onerror` clears the cursor and stops) and it is the one the research records as a defect | MSG-029, MSG-036 |
| D14 | ADR-016 gives `libs/cache` two primitives; only `SnapshotCell` was built in M1 | **`BoundedCache` is built in M3**, by lane C, with its two first consumers in the same milestone: compiled CEL programs (10 000, TTL 1 h) and Schema-Registry schemas by id (size-bounded). It is not built speculatively — M1 deliberately deferred it to its first caller | ADR-016 ties each primitive to a named consumer, and M1's D2 deferred this one to "M2/M3". This is that point | MSG-015 |

**Standing rule, restated from the M0 and M1 plans.** A blocker owned outside the execution loop is
not a reason to stop: propose the decision from the evidence available, take it, record it in the
artifact that owns it, and leave a cheap reconciliation path if the external input ever arrives. In
M3 that rule applies to R-6, the Confluent licence review: the module is built, it is optional at
runtime, and the packaging decision is a one-line change in `deployment/` if the review goes the
other way.
