# M2 — Topic explorer: technical development plan

**Status:** grooming step G5 output (PLAN §39, format PLAN §41), 2026-09-04.
**Owners:** Chief Architect (this document, module boundaries, the task graph, the contract and
edge lane), Principal Scala Engineer (`libs/kafka`'s `TopicAdmin`, `libs/kernel`'s search index,
`libs/cache`), Domain Architect — topics (`services/topic` domain, application and adapters),
Frontend Architect (`frontend/ui-topics` and the kernel table), Infrastructure Lead
(configuration, environments, operator documentation), QA Engineer (the Testcontainers matrix,
the seam suites and the fault-isolation E2E).

This plan is the only input an implementation worker gets, together with the task spec it picks
up (`tasks/TOP-NNN.md`), the ADRs that task cites, and `CLAUDE.md`. If a worker has to ask a
question, the answer belongs in this plan or in the task spec — not in a private reply.

---

## 1. Milestone goal

Browse topics at scale. A paged, sorted, searched topic list that stays usable on a cluster with
ten thousand topics; a topic detail page with its partitions, their leaders, replicas and in-sync
replicas; and the topic's configuration. Read-only, from end to end: nothing in M2 creates,
changes or deletes anything on a Kafka cluster.

M1 proved that KUI can connect to real clusters and render one service's data. M2 is the first
time KUI has **two** domain-owning services at once, and that changes what the milestone is
actually about. Three things are established here that every milestone from M3 on inherits:

1. **`TopicAdmin` in `libs/kafka`** — the second port in the family, and the one that finally
   lands the leaderless-partition invariant recorded in `libs/kafka/PORT-INVARIANTS.md`. Every
   later service that needs an offset lookup goes through the rule this milestone writes down in
   a doc comment on the method that owns it.
2. **The cluster-profile seam** — how a Kafka-facing service that is *not* the cluster service
   learns how to connect to a cluster. M1 built the server half
   (`GET /internal/v1/clusters/{id}/profile`, `GET /internal/v1/clusters/stream`) and shipped it
   with no consumer and with every credential stripped out, because there was nothing to consume
   it. `services/topic` is the first consumer, and it needs the credentials. Getting this seam
   right, once, in a shared module both sides compile against, is what stops the next four
   services from each writing their own profile client.
3. **The list machinery** — paging, sorting, substring and trigram search, and the internal-topic
   filter, in the order that makes the totals correct. The reference product computes its page
   count before applying the internal filter and therefore overstates it
   (`research/kafbat/api-analysis.md` §3.3). KUI does not reproduce that bug, and §10 D2 says what
   stops it from being reintroduced.

M2 is also where the M0 and M1 process findings are answered rather than repeated. Three of the
six defects M1's integration found lived in a seam nobody tested: a configuration section parsed
and discarded, and a browser decoding a document nobody sends. This plan has four tasks whose
entire purpose is to test a seam (TOP-034, TOP-035, TOP-036, TOP-037), and §7 says which
assertion belongs to which of them.

## 2. Exit criteria

Taken from `docs/ROADMAP.md` (M2) and made executable. Each line names the command that
demonstrates it.

1. **Property tests on paging and sorting.** `./mill services.topic.application.test` proves, over
   generated topic sets, that the page count is computed **after** every filter, that sorting is
   stable on ties, that `items.size <= pageSize`, that the union of every page equals the filtered
   set exactly once, and that the internal-topic filter runs before the page is cut. (TOP-036.)
2. **A virtualized table renders 10 000 rows with a scroll frame time under 16 ms**, measured in
   the Playwright run and recorded in `docs/benchmarks/m2-virtualized-table.md`. The harness page
   drives the kernel component with 10 000 rows directly; the product's own page size is capped at
   500 by ADR-026, and §10 D7 explains why both numbers are correct. (TOP-026, TOP-037.)
3. **Fault-isolation E2E.** With the Compose stack running, `docker stop kui-topic` leaves the
   brokers page and the dashboard working; the topic list shows its cached rows greyed with the
   time they were fetched; the action bar is disabled. `docker start` heals it with no page
   reload. `./mill e2e.test` runs it. (TOP-037.)
4. **Topic overview aggregation.** `GET /api/v1/clusters/{id}/topics/{topic}/overview` returns the
   `topic` section populated and a placeholder section for each of `consumerGroups`, `connectors`,
   `acls` and `schemas`. §10 D10 decides which placeholder each one gets and why the roadmap's
   wording is corrected. (TOP-024.)
5. **The topic list is correct against a real cluster.** A Testcontainers suite seeds a broker with
   internal topics, a topic whose partition has no leader, and 10 000 topics, and asserts: the
   internal filter, the search modes, every sort field, page boundaries, and that a topic with a
   leaderless partition reports no message count rather than a wrong one. (TOP-035.)
6. **The profile seam carries what a Kafka client needs, and nothing else leaks.** The topic
   service builds a working admin client from a profile fetched over `/internal/v1`, rebuilds it
   when the version changes without a restart, and no credential appears in any `/api/v1`
   response, log line or span attribute. (TOP-008, TOP-009, TOP-018, TOP-034.)
7. **Every long-running path introduced in M2 has a named cancellation test** — the snapshot
   refresh loops, the profile subscriber, the SSE connection, the admin client pool and the `app`
   bootstrap `Resource` chain. (§9 item 9.)

Inherited from PLAN §46 for every milestone: compiles with `-Werror`; all unit / property /
integration / contract tests pass; fault-isolation tests pass for every service introduced;
formatting and scalafix clean; OpenAPI regenerated and committed; docs and feature matrix updated;
ADRs Accepted; CEO acceptance recorded in `STATUS.md`.

Feature-matrix rows closed by M2: TP-001, TP-003, TP-004, PA-001, SF-001, SF-003, CL-010, and
KU-013 (the `FeaturePanel` slot gains its first real host screen; its first *guest* panel is M4).

**Five of M2's deliverables are consumed by M3 and M4, which are groomed to start in parallel.**
They are named here because a change to any of them is a cross-milestone change, not a local one:
`services/cluster/client` (ADR-046), `libs/contracts-core`'s `PageDto` (TOP-019), `libs/kernel`'s
`NameIndex` (TOP-001), `frontend/ui-kernel`'s `FeatureSlots` plus the `topic.tabs` guest host in
`ui-topics` (TOP-027, TOP-030), and `checkArchitecture` rule A11 (TOP-010). M3's §3a and M4's §3
record the same list from the consuming side, each with a check and a fallback.

## 3. Non-goals

Restating the roadmap, and adding the boundaries workers most often cross by accident:

- **No mutations of any kind.** No create, edit, delete, clone, recreate, purge, partition
  increase or replication-factor change. Those are M5, together with read-only mode and the audit
  trail, and the ordering rationale of `docs/ROADMAP.md` §3 is explicit that no destructive action
  ships before its safety net. The contract must not *declare* the endpoints either: an endpoint
  declared before its safety net exists is one somebody will implement. `TopicAdmin` gains no
  `createTopic`, `deleteTopic`, `alterConfigs` or `createPartitions` method, not even an
  unimplemented one.
- **No messages tab.** M3. `services/message` is not created, `libs/serde` is not created, and no
  consumer is constructed anywhere in `services/topic`. The topic detail page has no Messages tab
  and no produce affordance.
- **No consumer groups tab.** M4. The topic detail page has the `FeaturePanel` *slot* where that
  tab will go, and in M2 the slot renders its own not-configured state (§10 D10).
- **No topic analysis, no active producers, no CSV export, no batch actions.** TP-012, TP-013,
  TP-017 and TP-018 are M5.
- **No Lucene, no persistent index.** ADR-038 defers it: `mode=fts` is the in-memory trigram
  `NameIndex` in `libs/kernel`, and Lucene is adopted only against a benchmark that shows the
  in-memory index is too slow. TP-002 and SF-002 are M9.
- **No metrics columns.** Bytes in and out, throughput and any JMX-derived figure render as an em
  dash. `services/metrics` does not exist until M8. Segment size and message count are **not**
  metrics — they come from `describeLogDirs` and `listOffsets` and they do ship.
- **No RBAC filtering.** `kui.auth.type` is still `disabled` and `services/identity` does not
  exist. The list use case takes a visibility predicate as a parameter and M2 passes the one that
  admits everything; the predicate exists now because the ordering rule ("filter before you page")
  is the thing M6 must not have to retrofit, and §10 D2 makes the ordering a property test rather
  than a comment.
- **No refactor of `services/cluster`'s snapshot machinery.** `libs/cache` gains
  `SnapshotRegistry` and the topic service is its only caller in M2. §10 D12 and `TECH_DEBT.md`
  record why the cluster service is not moved onto it here.
- **No second `Page` implementation.** `libs/kernel`'s `Page`, `PageRequest`, `PageSize`, `Sort`
  and `SortOrder` already exist and already count after filtering. Nothing in M2 may define a
  page type of its own; TOP-019 puts the *wire* shape in `libs/contracts-core` over those types
  and nowhere else.

## 4. Architecture references

| Reference | Why M2 needs it |
| --- | --- |
| ADR-006 **Amendment 1** | raw `org.apache.kafka.clients.admin.Admin` for admin work through `AdminClientPool`, fs2-kafka for consumers and producers (M3). `TopicAdmin` is admin work: it uses the raw client, the `KafkaFutures` bridge and per-key futures, because `describeTopics` must report per-topic failures rather than failing the batch |
| ADR-016 caching | `SnapshotCell` and the TTL / invalidation / bound / metrics / staleness contract every cache must publish; `SnapshotRegistry` is a keyed collection of cells, not a new policy |
| ADR-026 paging | `page`/`pageSize` (default 25, max 500), `sort=<field>:<asc\|desc>`, `Page{items, page, pageSize, totalItems}` computed **after all filters** |
| ADR-027 per-context snapshots | `status: Initializing \| Online \| Offline(lastError)`, `scrapedAt`, atomic replacement — the topic snapshot is one per cluster |
| ADR-030 minimum broker version | capability gating, never version branching; `describeTopics` topic ids need 2.8, ELR needs 4.0, and both degrade to absent rather than to an error |
| ADR-032 navigation state model | how `Degraded` / `Unavailable` / `NotConfigured` render, and the stale-data rule DC-H3 |
| ADR-033 Chimney | domain → DTO mapping happens in `api` and nowhere else |
| ADR-034 error envelope | `KUI-TOPIC-NOT-FOUND` already exists in `ErrorCode`; the topic service adds no new code without adding its row to `docs/api/error-codes.md` |
| ADR-036 dynamic config ownership | the cluster service is the single writer of `kui.clusters[]`; **every other Kafka-facing service receives the resolved profile over the internal contract**, keeps the last one it saw, polls as a fallback and rebuilds its clients when the version moves. This is the sentence TOP-008 and TOP-009 implement |
| ADR-037 upstream resilience | the per-upstream timeout, bulkhead and breaker the profile client and the gateway's topic routes inherit |
| ADR-038 search in memory first | `NameIndex` lives in `libs/kernel`, built inside each snapshot; `q` plus `mode = plain \| fts`; Lucene deferred behind a benchmark |
| ADR-039 capability fold | only an `InfrastructureError` of the *upstream service* dims a capability. A Kafka cluster the topic service cannot reach is a `Section`, not a dimmed sidebar entry |
| ADR-041 **Amendments 1–3** | A1 (domain sees `libs/kernel` and cats-core only), A3, A4, A5, A9, A10; M2 adds A11 (§5.3) |
| ADR-042 metadata store | the store exists and the cluster service owns it. The topic service is **not** a store client (§10 D1) |
| ADR-043 internal service calls | `/internal/v1` direct calls, one hop, cached last-known fallback, reported to the registry |

`ARCHITECTURE.md` sections: §2 service catalog and tiers (the topic service is **Degradable**),
§3 module layout and the `checkArchitecture` rule table, §4.2 the `KafkaAdminPort` family, §5
internal contracts and headers, §6 the degraded-response envelope, §8 paging, §9 the state and
caching table, §12 the frontend shell and microfrontends.

`research/kafka/admin-capabilities.md` §2 is the **behavioural source** for every admin call in
this milestone and it outranks any sketch elsewhere: the chunk sizes, the per-key failure shapes,
the "visible topic without `DESCRIBE_CONFIGS` is an empty config, not an error" rule, and the
critical `listOffsets` gotcha. DC-D3 and DC-D4 are the two decision candidates it lands.

`research/kafbat/api-analysis.md` §3.3 and §4 decide the list semantics — the parameters, the
defaults, the sort enum, and the `pageCount` bug **not** to reproduce.
`research/kafbat/ui-analysis.md` "Topics list", "Topic details shell", "Overview tab" and
"Settings tab" decide what each screen shows and what it does when its data is missing.

`research/design/REFERENCE.md` decides how the screens look and nothing else. Its topic names,
offsets and payloads are invented sample data; the fields on the row come from the research
above.

`libs/kafka/PORT-INVARIANTS.md` §1 is **owned by this milestone**: TOP-002 moves it into a doc
comment on `TopicAdmin.listOffsets`, deletes it from that file, and leaves §2 (the `GroupAdmin`
invariant, owned by M4) behind.

## 5. Module map

M2 creates nine Mill modules and changes thirteen. A cross-compiled module has `.jvm` and `.js`
children (`KuiModule` traits in `build.mill`).

### 5.1 New modules

| Path | Mill id | Platforms | Depends on | Purpose |
| --- | --- | --- | --- | --- |
| `services/cluster/client` | `services.cluster.client` | JVM | `services.cluster.contract.jvm`, `libs.http`, `libs.kernel.jvm`, `libs.observability` | the **shared** cluster-profile consumer: fetch with `If-None-Match`, subscribe to `clusters/stream`, poll as a fallback, hold the last known profile, expose `profiles: F[Map[ClusterId, ClusterConnection]]` and a change callback. One implementation, compiled against the cluster service's own contract, used by every Kafka-facing service from M2 on |
| `services/topic/domain` | `services.topic.domain` | JVM | `libs.kernel.jvm`, cats-core | the topic model and its invariants, and the ports the use cases are stated in |
| `services/topic/application` | `services.topic.application` | JVM | `services.topic.domain`, `libs.cache` | the snapshot, the list pipeline, the detail and config use cases, the capability report |
| `services/topic/infrastructure` | `services.topic.infrastructure` | JVM | `services.topic.domain`, `services.cluster.client`, `libs.kafka`, `libs.cache`, `libs.observability` | the adapters. The **only** module in the topic tree with a Kafka client on its classpath (A10) |
| `services/topic/contract` | `services.topic.contract.{jvm,js}` | JVM + JS | `libs.contractsCore.*`, `libs.securityCore.*` | the endpoints, their DTOs and codecs. Cross-compiled, because the browser decodes exactly what the service encodes |
| `services/topic/api` | `services.topic.api` | JVM | `application`, `contract.jvm`, `libs.{kernel,contractsCore,securityCore,observability,http}` | server logic, the error envelope, `Section` staleness, and the OpenAPI document |
| `services/topic/app` | `services.topic.app` | JVM | `api`, `infrastructure`, `libs.{cache,config,kafka,observability,http}` | the composition root and `main` |
| `frontend/ui-topics` | `frontend.uiTopics` | JS | `frontend.uiKernel`, `services.topic.contract.js`, `services.gateway.contract.js` | the topics microfrontend |
| `deployment/docker` image | `deployment.docker.topic` | — | `services.topic.app` | the `kui-topic` container image |

**Why `services/cluster/client` is a module and not a copied file.** Four services (topic in M2,
message in M3, consumer in M4, security in M7) each need to turn a cluster id into a live Kafka
connection, and each needs to rebuild its clients when the profile's version moves. That is a
protocol — a conditional GET, an SSE subscription, a fallback poll, a last-known cache and a
cancellation path — and a protocol implemented four times is a protocol implemented four
different ways. The M0 review's second process finding was one string typed twice in two files;
this would be that finding with a distributed-systems failure mode attached. The module is on the
JVM only, holds no domain, and is the second module (after `contract`) that another service is
allowed to depend on — which is what rule A11 in §5.3 makes explicit rather than incidental.

### 5.2 Changed modules

| Mill id | Change | Task |
| --- | --- | --- |
| `libs.kernel.{jvm,js}` | gains `kui.kernel.search`: `NameIndex`, `SearchMode`, and the ranking contract of ADR-038. Cross-compiled, because the browser filters favourites with the same matcher | TOP-001 |
| `libs.kafka` | gains `TopicAdmin[F]`, its result types, and `KafkaTopicAdmin` over `AdminClientPool`: `listTopics`, `describeTopics`, `topicConfigs`, `listOffsets`, `partitionSizes` | TOP-002 … TOP-005 |
| `libs.cache` | gains `SnapshotRegistry[F, K, A]`: a keyed, on-demand collection of `SnapshotCell`s with supervised refresh and release-on-removal | TOP-006 |
| `libs.testkit` | gains the seeded topic topology: internal topics, a topic with a leaderless partition, and a bulk-create helper for the 10 000-topic case | TOP-007 |
| `libs.contractsCore.{jvm,js}` | gains `PageDto` — the wire shape of `libs.kernel`'s `Page` — and the topic DTO fragments shared by the topic contract and the gateway's overview aggregation | TOP-019 |
| `services.cluster.contract.{jvm,js}` | `ClusterProfileDto` carries the connection credentials on the internal channel; the public cluster DTOs are unchanged and still redacted | TOP-008 |
| `services.cluster.api` | serves the credential-carrying profile, and asserts by test that no public endpoint can | TOP-008 |
| `services.gateway.{contract,api,application}` | `ServiceContracts` gains the topic entry; the topic-overview aggregation; the topic service's capability entry | TOP-023, TOP-024 |
| `frontend.uiKernel` | gains `VirtualizedTable` (SF-003), `SearchBox` (SF-001), `Pagination`, the `Favourites` store (CL-010), and `FeatureSlots` — the one declaration of every cross-feature slot id, `topic.tabs` first (KU-013) | TOP-026, TOP-027 |
| `frontend.uiShell` | `FeatureId.Topics`, the registry thunk, the static routes and the nav entry | TOP-028 |
| `apps.allinone` | the topic service in the composition root, with an in-process profile client | TOP-033 |
| `deployment.{docker,compose}` | the `kui-topic` image and container | TOP-033 |
| `e2e` | `TopicServiceDownSuite` and the virtualized-table benchmark harness | TOP-037 |
| `build.mill` | the new modules, and rule A11 in the architecture rule table | every lane; A11 by TOP-010 alone |

**New dependency edges, and why each is legal.**
`services.cluster.client → services.cluster.contract.jvm` (a service's client compiled against its
own contract);
`services.topic.infrastructure → services.cluster.client` (**the new shape** — A11 permits a
service to see another service's `contract` and `client` and nothing else);
`services.topic.infrastructure → libs.kafka` (A10 allows Kafka on an `infrastructure` classpath);
`services.topic.application → libs.cache`;
`services.topic.app → services.topic.infrastructure` (A9 allows `app` alone);
`frontend.uiTopics → {frontend.uiKernel, services.topic.contract.js, services.gateway.contract.js}`
— the gateway's contract for exactly the reason `ui-clusters` needs it: the overview endpoint is a
gateway aggregation and answers with the gateway's type, and M1's second integration defect was a
browser decoding the wrong one of those two.

No `libs` module depends on a service (A5). No `application` module depends on `libs.kafka`,
`libs.http`, `libs.contractsCore`, Tapir or Circe (A3). The gateway gains no Kafka edge (A8) and
sees the topic service only through its `contract` (A4).

### 5.3 The rule M2 adds to `checkArchitecture`

| Rule | What it forbids | Why |
| --- | --- | --- |
| A11 (allocated by ADR-041 Amendment 4) | `services.<a>.*` → any module of `services.<b>` other than `services.<b>.contract.*` and `services.<b>.client` | A4 says this for the gateway. Nothing said it for service-to-service calls, because until M2 there were none. The first one is the moment to write the rule: a topic service that could see `services.cluster.application` would call a use case in process in the all-in-one build and over HTTP in the distributed one, and the two shapes would diverge without either being wrong at its own call site |

A11's allow-list names `client` explicitly so that a second such module has to be argued in the
commit that adds it, exactly as A10 names `libs/config`. TOP-010 owns the rule and its build test,
and no other task edits the rule table.

**Rule numbers are allocated in ADR-041 Amendment 4 and nowhere else.** M3 and M4 were groomed in
parallel and each also proposed an "A11"; the gate review renumbered theirs to A12/A13 (M3) and A14
(M4). A11 is M2's, and a milestone that wants a new rule takes the next free number from that
amendment in the commit that adds the check.

## 6. Task graph

38 tasks. Sizes: **S** ≈ 1–2 h, **M** ≈ 2–4 h, **L** ≈ 4–6 h. Every task ends on a green `main`:
a task that adds a module also adds that module's first test, a task that changes a contract
regenerates the committed OpenAPI document in the same commit, and a task that moves a document
(the port invariant) deletes the original in the same commit.

### 6.1 Parallel lanes

| Lane | Task range | Owner role | Owns |
| --- | --- | --- | --- |
| **A — Kafka platform libraries** | TOP-001 … TOP-007 | Principal Scala Engineer | `libs/kernel`'s search package, `libs/kafka`'s topic port, `libs/cache`, `libs/testkit` |
| **B — The profile seam** | TOP-008 … TOP-010 | Chief Architect | `services/cluster/{contract,api}`'s profile half, the new `services/cluster/client`, rule A11 |
| **C — Topic domain and application** | TOP-011 … TOP-016 | Domain Architect (topics) | `services/topic/{domain,application}`, `docs/domain/topic.md` |
| **D — Topic adapters** | TOP-017 … TOP-018 | Domain Architect (topics) | `services/topic/infrastructure` |
| **E — Contract, API and edge** | TOP-019 … TOP-025 | Chief Architect | `services/topic/{contract,api,app}`, the topic-shaped parts of `services/gateway/*`, `libs/contracts-core`'s topic fragments, `docs/api/*` |
| **F — Frontend** | TOP-026 … TOP-031 | Frontend Architect | `frontend/ui-topics`, and the named additions to `frontend/ui-kernel` and `frontend/ui-shell` |
| **G — Configuration, environments, operations, tests** | TOP-032 … TOP-038 | Infrastructure Lead + QA Engineer | the `kui.topics.*` slice, `build.mill` wiring, `apps/allinone`, `deployment/*`, `e2e/*`, `docs/benchmarks/`, the milestone documentation |

Lane A unblocks C, D and G. Lane B is independent of A and should start on day one: it is the
milestone's riskiest unknown and it blocks lane D's second task. Lane E can start as soon as
TOP-011 exists. Lane F can start immediately on TOP-026, which is a kernel component with no
backend dependency.

### 6.2 Ordered task list

| ID | Title | Size | Depends on | Lane |
| --- | --- | --- | --- | --- |
| TOP-001 | `libs/kernel`: `NameIndex`, `SearchMode` and the ranking contract | M | — | A |
| TOP-002 | `libs/kafka`: the `TopicAdmin[F]` port, its types, and the leaderless invariant moved into it | M | — | A |
| TOP-003 | `libs/kafka`: `listTopics` and `describeTopics` with chunking and per-key skips | L | TOP-002 | A |
| TOP-004 | `libs/kafka`: `topicConfigs` with synonyms, documentation and sensitivity | M | TOP-003 | A |
| TOP-005 | `libs/kafka`: `listOffsets` — leaderless filtering, whole-topic refusal, chunking | L | TOP-003 | A |
| TOP-006 | `libs/cache`: `SnapshotRegistry`, a keyed collection of supervised cells | M | — | A |
| TOP-007 | `libs/testkit`: seeded topics, an internal topic, a leaderless partition, a 10k bulk create | M | — | A |
| TOP-008 | The profile carries credentials on `/internal/v1`, and nothing else does | M | — | B |
| TOP-009 | `services/cluster/client`: the shared profile consumer, with its cancellation path | L | TOP-008 | B |
| TOP-010 | `checkArchitecture` rule A11 and its build test | S | TOP-009 | B |
| TOP-011 | Topic domain: the model and its invariants | M | — | C |
| TOP-012 | Topic domain ports: `TopicAdmin`, `ClusterProfiles`, `ClockPort` | S | TOP-011 | C |
| TOP-013 | Topic snapshot use case: per-cluster scrape, staleness, forced refresh, capability report | L | TOP-012, TOP-006 | C |
| TOP-014 | The list pipeline: filter, search, sort, page — in that order | L | TOP-013, TOP-001 | C |
| TOP-015 | Topic detail and partition use cases | M | TOP-013 | C |
| TOP-016 | Topic configuration use case | S | TOP-013 | C |
| TOP-017 | `services/topic/infrastructure`: the module and the `TopicAdmin` adapter | L | TOP-012, TOP-005, TOP-004 | D |
| TOP-018 | `ClusterProfiles` adapter over `services/cluster/client`, and client rebuild on version change | M | TOP-017, TOP-009 | D |
| TOP-019 | `libs/contracts-core`: `PageDto` and the topic DTO fragments | M | TOP-011 | E |
| TOP-020 | Topic contract: list, detail, config, partitions, refresh | M | TOP-019 | E |
| TOP-021 | Topic `api`: server logic, envelope, `Section` staleness, query validation | L | TOP-020, TOP-014, TOP-015, TOP-016 | E |
| TOP-022 | Topic `app`: wiring, bootstrap ordering, readiness, teardown | L | TOP-021, TOP-018 | E |
| TOP-023 | Gateway: topic routes, `ServiceContracts` entry, capability entry | M | TOP-020 | E |
| TOP-024 | Gateway: the topic overview aggregation with placeholder sections | M | TOP-023 | E |
| TOP-025 | OpenAPI regeneration, error-code table, contract snapshot | S | TOP-024, TOP-021 | E |
| TOP-026 | `ui-kernel`: `VirtualizedTable` | L | — | F |
| TOP-027 | `ui-kernel`: `SearchBox`, `Pagination`, and the `Favourites` store | M | — | F |
| TOP-028 | `ui-topics`: the module, its registration, its routes and its typed clients | M | TOP-020, TOP-023 | F |
| TOP-029 | The topics list screen | L | TOP-028, TOP-026, TOP-027 | F |
| TOP-030 | The topic detail screen: overview, partitions, configuration, panel slots | L | TOP-029, TOP-024 | F |
| TOP-031 | `docs/frontend/features.md`: the microfrontend pattern, decided against a real screen | S | TOP-030 | F |
| TOP-032 | The `kui.topics.*` configuration slice | S | TOP-013 | G |
| TOP-033 | All-in-one, Compose and the `kui-topic` image | M | TOP-022 | G |
| TOP-034 | The profile seam suite: two processes, one contract | L | TOP-033 | G |
| TOP-035 | The Testcontainers topic suite: internal filter, leaderless partition, 10 000 topics | L | TOP-017, TOP-007 | G |
| TOP-036 | Property suites for paging, sorting and search | M | TOP-014, TOP-001 | G |
| TOP-037 | Fault-isolation E2E and the virtualized-table benchmark | L | TOP-033, TOP-030 | G |
| TOP-038 | Milestone documentation, feature matrix, ADR amendments, `PORT-INVARIANTS.md` | M | everything | G |

**The table is grouped by lane, not topologically sorted.** Read the `Depends on` column, never
the row order.

### 6.3 Critical path

Every arrow is an edge the table above declares, so nothing here can be reordered or
parallelised:

```
TOP-002 → TOP-003 → TOP-005 → TOP-017 → TOP-018 → TOP-022
  → TOP-033 → TOP-037 → TOP-038
```

Nine tasks, roughly 38 working hours of single-threaded effort. It runs through the **admin
adapter**, not through the contract — the opposite of M1, whose critical path ran through the
metadata store. The reason is that TOP-018 is the join point of the two riskiest pieces of work
in the milestone (the offsets invariant and the profile seam), and nothing can be wired, deployed
or E2E-tested until it exists.

A **second chain of almost equal length** runs down the read path and must be worked in parallel
from day one or it becomes the critical path by default:

```
TOP-011 → TOP-019 → TOP-020 → TOP-021 → TOP-022 → TOP-033 → TOP-037 → TOP-038
```

Eight tasks. It shares nothing with the first chain until TOP-022, so lanes C, E and F can run at
full width from the start. The frontend chain (TOP-026 → TOP-029 → TOP-030 → TOP-037) is seven
tasks long and joins only at the end, which is exactly why TOP-026 must not wait for a backend.

### 6.4 What to do first, and why

Four tasks are worth starting before anything else, not because they are on a path but because
each answers a question whose wrong answer invalidates work already done by the time the question
surfaces.

1. **TOP-008 and TOP-009 — can a service other than the cluster service build a Kafka client?**
   The whole milestone assumes yes. M1 shipped the endpoint with its credentials stripped and a
   description saying "M1 has no consumer that builds a Kafka client from this". If carrying
   credentials over `/internal/v1` turns out to be unacceptable, the alternative is that every
   Kafka-facing service becomes a store client with its own decryption key, which changes the
   deployment model, the operator documentation and four services' wiring. Discovering that in
   week three is a milestone, not a task. It depends on nothing and can start immediately.
2. **TOP-005 — is the leaderless-partition rule implementable at the port?**
   `libs/kafka/PORT-INVARIANTS.md` §1 says an offset lookup against a leaderless partition retries
   until the sixty-second API timeout. The rule requires a `describeTopics` round trip before
   every `listOffsets`, per DC-D3, and that extra trip is on the hot path of a 10 000-topic
   scrape. Whether the cost is acceptable is a measurement, not an opinion, and TOP-035's
   10 000-topic case is where it is taken.
3. **TOP-013 — what does one topic snapshot cost?** The dashboard's cluster snapshot is a handful
   of admin calls. A topic snapshot is `listTopics` plus a chunked `describeTopics` plus a chunked
   `describeConfigs` plus a chunked `listOffsets` plus `describeLogDirs`, and the roadmap's
   headline number is ten thousand topics. If a full scrape cannot finish inside the refresh
   interval, the answer is a longer interval and a documented cost, and both are cheap to decide
   now and expensive to retrofit into a screen that promises freshness.
4. **TOP-026 — does a hand-written virtualized table hit 16 ms?** It is the milestone's one
   measured frontend criterion, the roadmap's own risk names scope creep in it, and it depends on
   no backend at all. A negative result needs weeks of slack, not days.

### 6.5 Area boundaries

A file appears in exactly one row. Two agents never write the same file.

| Lane | May create or change | Must not touch |
| --- | --- | --- |
| A | `libs/kernel/src/kui/kernel/search/**`, `libs/kafka/**`, `libs/cache/**`, `libs/testkit/**`, and their own `build.mill` objects | `services/**`, `frontend/**`, `libs/config/**` |
| B | `services/cluster/contract/**`'s profile files, `services/cluster/api/**`'s profile route, `services/cluster/client/**`, `build.mill`'s architecture rule table | every other part of `services/cluster`; `services/topic/**` |
| C | `services/topic/{domain,application}/**`, `docs/domain/topic.md` | `services/topic/{infrastructure,contract,api,app}`, any `libs` module |
| D | `services/topic/infrastructure/**` | every other module in the topic tree; the rule table |
| E | `services/topic/{contract,api,app}/**`, `services/gateway/**`, `libs/contracts-core/src/kui/contracts/topic/**` and `.../paging/**`, `docs/api/*` | `services/topic/{domain,application,infrastructure}`, `frontend/**` |
| F | `frontend/ui-topics/**`, and the named additions in `frontend/ui-kernel` (TOP-026, TOP-027) and `frontend/ui-shell` (TOP-028), `docs/frontend/*` | any backend module |
| G | `libs/config`'s `kui.topics.*` slice, `apps/allinone/**`, `deployment/**`, `e2e/**`, `docs/benchmarks/**`, `docs/operations/*`, `docs/ROADMAP.md`, `docs/FEATURE_MATRIX.md`, `STATUS.md`, `TECH_DEBT.md`, `libs/kafka/PORT-INVARIANTS.md`'s final trim | every service's own modules; `libs/config/src/kui/config/store/**` |

Two shared files need a rule rather than an owner. **`build.mill`**: a task edits only the
`object` it creates plus the `moduleDeps` line of the module it is wiring; TOP-010 alone edits the
architecture rule table. **`docs/FEATURE_MATRIX.md`**: edited only by TOP-038, at the end, from
the evidence the other tasks left; no task flips its own row.

One boundary exception. `libs/kafka/PORT-INVARIANTS.md` §1 is **deleted by TOP-002**, in the same
commit that pastes it into the doc comment on `TopicAdmin.listOffsets`. TOP-038 checks that the
file now contains only §2 and its framing, and deletes the file entirely if M4's grooming has
already claimed §2. Moving text and deleting the original must be one commit or the text exists
twice, which is the condition the file itself warns about.

## 7. Test plan

Test kinds follow PLAN §32 and ADR-018. **MUnit is the only framework**; no mocking library;
fakes live in `libs/testkit` or in a module's own test tree. A Scala.js test module and a JVM test
module cannot run in one Mill invocation (`CLAUDE.md`), so lane F's suites are always a separate
command.

| Suite | Where | Runner | What it covers |
| --- | --- | --- | --- |
| Name index | `libs.kernel.{jvm,js}.test` | MUnit + ScalaCheck | `plain` is exactly a case-insensitive substring filter (property, against a naive implementation); `fts` ranking is stable for equal scores; an empty query matches everything; the index and the matcher agree; it links and runs on Scala.js |
| Topic admin, unit | `libs.kafka.test` | MUnit + ScalaCheck | conversions from Kafka objects: a `null` leader is `None`; `isInternal`; ISR ⊆ replicas as reported; an unknown topic between `listTopics` and `describeTopics` is a `SkipReason.NotFound`, not a failed batch; a topic without `DESCRIBE_CONFIGS` is an empty config, not an error; chunk sizes come from `AdminTuning` |
| **The leaderless invariant** | `libs.kafka.test` | MUnit + ScalaCheck | `listOffsets` never passes a leaderless partition to the client (asserted against a recording fake that fails the test if it sees one); every filtered partition appears as `SkipReason.NoLeader`; a topic with **any** leaderless partition yields no aggregate count rather than a partial one; the call returns inside the API timeout with a leaderless partition present |
| Snapshot registry | `libs.cache.test` | MUnit + `munit-cats-effect` + `TestControl` | a cell is created on first use and reused; removal releases the cell and cancels its refresh; `Stale` reads while the upstream fails; cancelling the registry's resource leaves no fiber running |
| Topic domain | `services.topic.domain.test` | MUnit + ScalaCheck | ISR ⊆ replicas; a leaderless partition is representable and its leader is `None`; a topic with no partitions is refused; message count is `Option` and is `None` when any partition's offsets are missing |
| **The list pipeline** | `services.topic.application.test` | MUnit + ScalaCheck | the milestone's headline property: `totalItems` equals the size of the set after **every** filter (visibility, internal, search) and before the page is cut; the union of all pages is the filtered set, each element once; sorting is stable; an out-of-range page is empty, not an error; `showInternal=false` changes the total, and the reference product's bug — a total computed before that filter — fails this suite |
| Topic application | `services.topic.application.test` | MUnit + `munit-cats-effect` + `TestControl` + fake ports | the snapshot serves the previous value while the admin port fails; forced refresh is idempotent under concurrency and returns immediately; a cluster removed from the profile set has its snapshot released; the capability report is `Degraded` when the profile client has no profile and `Available` when only an optional call failed |
| Topic adapters | `services.topic.infrastructure.test` | MUnit + Testcontainers | the adapter satisfies the same fake-port contract the application suites use, against a live broker; a profile version change rebuilds the admin client and the old one is closed |
| **Topics, integration** | `services.topic.infrastructure.test` | MUnit + Testcontainers | internal topics are excluded by default and included on request; the union rule of §10 D3; a topic with a leaderless partition lists with an offline marker and no count; every sort field orders correctly against a broker; **10 000 topics**: one full scrape completes inside the configured budget, and its duration is recorded in `docs/benchmarks/` |
| Topic contract | `services.topic.api.test` | MUnit + Tapir stub interpreter | every endpoint's success and error paths without a socket; a malformed `sort` or `mode` is a 400 naming the field, never a silently ignored parameter; `pageSize` above the ADR-026 maximum is a 400; stale responses carry `Section.Stale` with `scrapedAt`; **no credential appears in any response body**, asserted against a profile whose every secret is a distinctive token |
| **Profile seam** | `apps.allinone.test` and `services.cluster.api.test` | MUnit + `munit-cats-effect` | the seam M1's integration would have caught: the cluster service's real profile response is decoded by `services/cluster/client`'s real decoder in the same suite, from a recorded document; an `If-None-Match` round trip answers 304 and the client keeps its cached value; a version bump on the stream causes exactly one refetch; closing the client cancels the SSE subscription within one heartbeat |
| **Gateway seam** | `services.gateway.api.test` | MUnit + stub upstream | recorded topic-service responses decoded by the gateway's client and re-encoded to the browser's expected shape; the overview aggregation's placeholder sections; a topic-service failure produces a `Section`, never a 500, and never dims the *cluster* capability |
| Frontend unit | `frontend.uiTopics.test` | MUnit under jsdom | the row model as a table (available / degraded / unavailable × internal / not); message count renders an em dash and not `0` when it is absent; favourites pin to the top of the current page without changing the total; the search box debounces and resets the page to 1 |
| Frontend DOM | `frontend.uiKernel.test` (jsdom) | MUnit + `scala-dom-testutils` | `VirtualizedTable` renders only the visible window plus its overscan; its ARIA row indices are the *absolute* ones, so a screen reader announces "row 4 000 of 10 000"; sorting a column keeps focus; keyboard paging works |
| E2E fault isolation | `e2e.test` | JVM Playwright + Testcontainers Compose | `docker stop kui-topic` leaves the dashboard and brokers usable and greys the topic rows with a timestamp; `docker start` heals with no reload; the topics module is **not** downloaded on first paint (the TD-016 regression guard) |
| Benchmark | `e2e.test` | Playwright | 10 000 rows in the harness page: scroll frame time p95 under 16 ms, written to `docs/benchmarks/m2-virtualized-table.md` |

**Testcontainers in M2:** Kafka only, PLAINTEXT for the topic suites — the three-security-mode
matrix is M1's and is not re-run per milestone. The 10 000-topic case runs in the nightly job, not
in the default CI job, and the default job runs a 500-topic variant of the same suite so the code
path is covered on every commit.

**Fault-injection scenarios in M2:**

1. The topic service stopped — the milestone's headline scenario (TOP-037).
2. The **cluster** service stopped while the topic service is running — the topic service keeps
   serving from the last profile it holds, its capability reports `Degraded`, and it does not
   crash-loop (TOP-034). This is the scenario the new seam introduces and nothing else covers.
3. One Kafka cluster unreachable, another healthy — a `Section` per cluster, not a dimmed feature.
4. A cluster that authenticates but authorizes nothing — `describeConfigs` returns per-topic
   errors and the list renders what it has, with the configuration tab showing "not authorized"
   rather than an empty table.
5. A topic with a leaderless partition on an otherwise healthy cluster — the list responds in
   milliseconds, not in sixty seconds, and the count cell is an em dash.

## 8. Risk register

| ID | Risk | Impact | Mitigation | Mitigating task(s) |
| --- | --- | --- | --- | --- |
| R-1 | Carrying Kafka credentials over `/internal/v1` is judged unacceptable late | The deployment model changes and four services' wiring with it | Decided in §10 D1 from ADR-036's own distribution sentence, executed first, and bounded by three assertions: the channel is signed and internal-only, the public edge is tested against distinctive tokens, and the profile is never logged or put on a span | TOP-008, TOP-009, TOP-034 |
| R-2 | A full topic scrape does not finish inside the refresh interval on a large cluster | The screen promises a freshness it cannot deliver, or the broker is hammered | The scrape is measured before the screen is built (TOP-013, TOP-035), the interval and every chunk size are configuration (TOP-032), and the snapshot publishes `scrapedAt` so a slow scrape is visible rather than silent | TOP-013, TOP-032, TOP-035 |
| R-3 | The leaderless-partition rule is implemented in the caller instead of the port | The sixty-second timeout returns in M3 and M4 through a different caller | The rule is a doc comment on the method that owns it, and a recording fake fails the suite if a leaderless partition ever reaches the client. `PORT-INVARIANTS.md` §1 is deleted in the same commit so there is one statement of the rule, next to the code | TOP-002, TOP-005 |
| R-4 | The `pageCount` bug is reintroduced at the `api` layer by paging after mapping | The exact defect the milestone was told not to copy | `Page` is cut once, in `libs/kernel`, from a list that is already filtered; the `api` layer may only `map` a `Page`, never rebuild one; the property suite asserts the total against the filtered set | TOP-014, TOP-021, TOP-036 |
| R-5 | Scope creep in the virtualized table towards a general grid | The roadmap names this risk itself | The column set is fixed to what the topic list needs; dynamic columns arrive in M3 with MS-004; the component takes a row height and a row renderer and knows nothing about topics | TOP-026 |
| R-6 | The 16 ms criterion is measured on a machine and is therefore flaky in CI | A green milestone criterion that fails on a loaded runner, or one nobody trusts | The benchmark records p95 and a machine fingerprint into `docs/benchmarks/` and gates only on a large regression against the committed baseline, not on an absolute number; the absolute 16 ms figure is asserted once, on the reference machine, and recorded as an observation | TOP-037 |
| R-7 | Two services now resolve cluster connections, and the two answers drift | A cluster works on one screen and not another | There is exactly one resolver: the cluster service. The topic service holds no static `kui.clusters[]` slice and is not a store client. TOP-034 asserts that the ids the topic service serves are exactly the ids the cluster service publishes | TOP-009, TOP-018, TOP-034 |
| R-8 | The seam is tested on each side and not across it — the M0 and M1 finding, repeated | The worst defects live here and unit tests on both sides pass | Four tasks exist for the seam alone (TOP-034 … TOP-037), and every cross-process document has recorded golden files that **both** sides decode in the same suite | TOP-034, TOP-035, TOP-037 |
| R-9 | The profile subscriber, the SSE connection or a refresh loop leaks on shutdown | A stopped service still holding an admin client and re-authenticating every thirty seconds | Every one of them is a `Resource`, and §9 item 9 makes a named cancellation test a condition of done for each | TOP-006, TOP-009, TOP-013, TOP-018, TOP-022 |
| R-10 | The topics microfrontend is statically imported by `main.js`, as `ui-clusters` is today (TD-016) | ADR-012's promise is broken for a second feature and the debt doubles | TOP-028 adds the feature only through the dynamic-import thunk, and TOP-037's E2E asserts the module is not fetched on first paint. Fixing `ui-clusters`' existing breakage is TD-016 and is **not** in this milestone; the assertion for topics is written so that it does not depend on the clusters fix | TOP-028, TOP-037 |
| R-11 | M2 quietly starts building M5 — a delete button "behind a flag", a `createTopic` on the port | Mutations ship without read-only mode or an audit trail | §3's non-goals are restated in every lane-A, lane-C and lane-E task spec, and each names the exact files it may create. `TopicAdmin` declares read methods only | TOP-002, TOP-011, TOP-020 |
| R-12 | The `FeaturePanel` slot on the topic detail page is built for guests that do not exist yet and is wrong when M4 arrives | KU-013 is delivered as decoration | The slot renders the real not-configured state for a real feature id, and M4's consumer tab is the test of it. §10 D10 fixes the placeholder semantics now so M4 is a registration, not a redesign | TOP-024, TOP-030 |

## 9. Definition of done for M2

M2 is complete when all of the following are true and the evidence is committed:

1. **Every exit criterion in §2 is demonstrated by a command in CI**, except the absolute 16 ms
   frame-time figure, which is recorded in `docs/benchmarks/` as an observation on a named
   machine, with CI gating on regression instead (R-6).
2. All 38 tasks are merged, each with an Implementation Report (PLAN §39, one screen).
3. `./mill __.compile` is clean with `-Werror -Wunused:all -source:future`; `./mill __.test` is
   green on the JVM and, in a separate invocation, on Scala.js; `./mill __.checkFormat` and
   `./mill __.fix --check` are clean; `./mill checkArchitecture` passes with rule A11 active.
4. `./mill e2e.test` is green against the Compose stack, including `TopicServiceDownSuite` and the
   first-paint assertion that the topics module is not downloaded.
5. `GET /api/v1/openapi.json` is regenerated and its snapshot committed under
   `docs/api/openapi.json`; `docs/api/error-codes.md` lists every code the topic service can
   return.
6. `docs/FEATURE_MATRIX.md` rows TP-001, TP-003, TP-004, PA-001, SF-001, SF-003 and CL-010 are
   `DONE`, and KU-013 records the slot's first host screen.
7. `docs/domain/topic.md` exists and documents the aggregate, its ports and their invariants.
8. `libs/kafka/PORT-INVARIANTS.md` no longer contains §1: the rule lives in a doc comment on
   `TopicAdmin.listOffsets`, and a `grep` for the phrase finds it in exactly one place.
9. **Every long-running or cancellable path introduced in M2 has a named cancellation test.** The
   snapshot registry, each snapshot refresh loop, the profile client's SSE subscription and
   fallback poll, the admin client rebuild, and the `app` bootstrap `Resource` chain each carry a
   "Cancellation and shutdown" requirement in their spec and at least one test that cancels the
   fiber and asserts the resource was released and nothing was left running.
10. **Every rule this plan states is enforced by something.** §10 D2's ordering is a property
    test; A11 is a build rule with a build test; D3's internal-topic union is a Testcontainers
    assertion; §3's "no mutation endpoints" is a contract test that enumerates the endpoint list;
    D9's favourites rule is a frontend suite. A rule with no enforcer named here is a rule this
    milestone does not claim.
11. ADR-036 carries the amendment recording that the profile channel carries credentials and where
    the shared consumer lives (§10 D1); ADR-038's consequences record what the in-memory index
    actually cost at 10 000 names; ADR-041 carries Amendment 4 for rule A11.
12. `TECH_DEBT.md` records every debt taken during M2 — including D12's un-refactored cluster
    snapshots — and `STATUS.md` records CEO acceptance with the CI run id that produced the
    evidence.
13. A developer who has never seen the repository can follow `README.md`, bring up the quickstart,
    and browse the seeded topics — list, search, sort, page and open one — in under fifteen
    minutes.

## 10. Decisions taken in this plan rather than escalated

Grooming produces decisions, not questions (PLAN §39). Where the roadmap, an ADR or
`ARCHITECTURE.md` left a gap a worker would otherwise have to ask about, this plan closes it —
from the research already gathered, not from opinion.

| # | Gap | Decision | Evidence | Where it lives |
| --- | --- | --- | --- | --- |
| D1 | A Kafka-facing service that is not the cluster service has to build a Kafka client, and M1 shipped `GET /internal/v1/clusters/{id}/profile` with every credential stripped, with the note "M1 has no consumer that builds a Kafka client from this" | **The profile channel carries the credentials**, on `/internal/v1` only, and the shared consumer is a new JVM module `services/cluster/client` that every Kafka-facing service depends on. The topic service is **not** a metadata-store client and holds no `kui.clusters[]` slice of its own | ADR-036 says it in as many words: the section owners consume `__kui_config` directly "while every other Kafka-facing service keeps receiving the resolved `ClusterProfile` over the internal contract", and "keystore bytes travel inside the signed inter-service channel". The alternative — four services each holding the store's decryption key — multiplies the blast radius of that key by four and gives four processes write-capable credentials for a topic only one of them owns | TOP-008, TOP-009, TOP-018; recorded as **ADR-046** (written at the M2/M3/M4 gate), which TOP-038 cross-references from ADR-036 and from `ARCHITECTURE.md` §14 |
| D2 | The reference product computes `pageCount` before applying the internal-topic filter and overstates it. "Do not reproduce it" is a negative instruction, and negative instructions are not enforceable | The order is fixed and is the *only* order: **visibility filter → internal filter → search → sort → page**, expressed as one pure function in `services/topic/application`, cutting the page with `libs/kernel`'s `Page.of`, which counts what it is given. The `api` layer may `map` a `Page` and may not construct one. A ScalaCheck property asserts `totalItems` equals the filtered size for every generated combination of filters | `libs/kernel`'s `Page.of` already carries this reasoning in its own doc comment — "the implementation this project is modelled on counts before filtering … the only reliable fix is to make the arithmetic impossible to get wrong by doing it in one place". M2 is the first caller, and the fix is only real once something enforces it | TOP-014, TOP-021, TOP-036 |
| D3 | "Internal topic" has two definitions that disagree: Kafka's own `isInternal` flag from `listTopics().listings()`, and the reference products' name prefix (`__` by default, a regex in Kouncil). `__kui_config` is internal by prefix and **not** by Kafka's flag | A topic is internal if **either** is true: the union, not the intersection. The prefix is configurable (`kui.topics.internalPrefix`, default `__`) and the flag is read from the listing | KUI's own metadata topics are the exact case that breaks the flag-only rule: `__kui_config` and `__kui_files` are ordinary topics to Kafka and are noise to every operator. Choosing the intersection would show them by default; choosing the flag alone would show them always. The union hides both and hides `__consumer_offsets` too, which is what both reference products do | TOP-014, TOP-032, TOP-035 |
| D4 | Kafbat's search is a boolean `fts` flag whose default comes from two cluster-level settings; `research/kafbat/api-analysis.md` §2 records the three-way precedence | `mode = plain \| fts`, defaulting to `plain`, replacing the boolean, exactly as `research/kafbat/api-analysis.md`'s proposed `/api/v1` mapping already says. `plain` is a case-insensitive substring match; `fts` is `NameIndex`'s trigram score. Relevance ordering applies **only** when no `sort` is given; an explicit sort always wins | A tri-state flag whose meaning depends on two server settings is a parameter a client cannot reason about, and the reference exposes both settings in its cluster document precisely so the UI can reconstruct the meaning. An explicit two-value mode removes the reconstruction | TOP-001, TOP-014, TOP-020 |
| D5 | Where does the topic list come from — a live `listTopics` per request, or a snapshot? | **A per-cluster snapshot**, refreshed on a timer (`kui.topics.refreshInterval`, default 60 s — twice the cluster service's interval, because a topic scrape is an order of magnitude more expensive), served with `scrapedAt`, with an explicit forced refresh. A list request costs no admin call | The reference product does the same and for the same reason (`research/kafbat/api-analysis.md` §3.3: "source is the in-memory statistics cache, not a live `listTopics` call"). A live scrape per request on a 10 000-topic cluster would put the broker's load in the hands of whoever holds the page open | TOP-013, TOP-032 |
| D6 | The list has a message-count and a size column, and both can be partly unknowable | Message count is `Option[Long]`, computed as the sum of `latest − earliest` over partitions, and is **`None` for the whole topic if any partition is leaderless or its offsets were skipped** — never a partial sum. Size is `Option[Long]` from `describeLogDirs` and is `None` when the broker refused it. Both render as an em dash, and a topic with an offline partition additionally shows an "offline" marker | This is the port invariant surfacing at the pixel: `PORT-INVARIANTS.md` §1 says the reference product "skips a whole topic when any of its partitions is leaderless, because a per-topic message count computed from a partial set of partitions would be wrong rather than merely incomplete". A wrong number is worse than no number, because only one of the two starts an investigation | TOP-005, TOP-014, TOP-029 |
| D7 | ADR-026's page maximum is 500; the roadmap's benchmark says 10 000 rows | Both are right and they are about different things. **The product pages at 25 by default and 500 at most**; the virtualized table exists because 500 rows of 9 px padding still janks on a slow machine, because the M5 batch-selection screen will need it, and because TD-018's per-partition table needs it. The **10 000-row benchmark drives the kernel component directly from a harness page** in `e2e`, not through the product's API | Raising the page cap to 10 000 would mean a single request materialising, sorting and serialising ten thousand rows per browser tab — `libs/kernel`'s `PageSize` doc comment already calls that "not a big page, it is an outage". The component's capability and the API's policy are separate claims and are tested separately | TOP-026, TOP-037 |
| D8 | The roadmap says the topic-list fallback "disables Create (which does not exist yet, so the assertion targets the disabled state of the action bar)" | The action bar exists in M2 with exactly one control — the forced-refresh button — and the stale state disables it. No Create button is rendered, not even disabled: a disabled control for a feature that does not exist promises a date | ADR-032's rule is that `NotConfigured` is hidden and `Unavailable` is shown; a feature that has not been built is neither, it simply is not there. The E2E assertion targets the refresh control's disabled state, which is a real control with a real reason to be disabled | TOP-029, TOP-037 |
| D9 | Favourites (CL-010) pin topics to the top of a list that is **server-paged**. Pinning across pages and paging are incompatible without server-side knowledge of the favourites | Favourites are a browser preference (`localStorage`, keyed by cluster id and topic name) and pin **within the page currently displayed**. They do not change `totalItems`, do not change which topics are on which page, and are not sent to the server. The star toggles on the row and the list re-sorts locally | Sending the favourite set to the server would make the page composition depend on a per-browser preference, so two tabs of the same user would disagree about what page 3 contains, and a shared link would not reproduce. The feature-matrix row already says "localStorage, keyed by cluster + name" | TOP-027, TOP-029 |
| D10 | The roadmap says the topic overview returns "`Unavailable` placeholders for the sections whose services do not exist yet". `Unavailable` means "configured and down"; these services are not configured and are not down — they have not been built | **A section whose service is absent from this deployment is `Section.NotConfigured`**, and the browser hides that panel rather than showing an error. `Unavailable` is reserved for a service the deployment has and cannot reach. In M2 that means `consumerGroups`, `connectors`, `acls` and `schemas` are all `NotConfigured` | ADR-032 draws exactly this line — `NotConfigured` means "this deployment has no such thing" and is hidden, `Unavailable` means "down" and is shown with its reason — and the M1 dashboard already renders it that way. Rendering four red "unavailable" panels on every topic page would train operators to ignore the colour that matters | TOP-024, TOP-030 |
| D11 | Which of the two `Section` levels does a Kafka cluster the topic service cannot reach land in? | The same answer M1 gave for the dashboard, restated because a second service now makes it a pattern: **an unreachable Kafka cluster is a `Section` inside a 200**, and the `topic` capability is `Unavailable` only when the topic *service* is down | ADR-039 §6: only transport failures of the upstream service feed the registry. A bad broker address in one cluster's configuration must not dim the Topics entry for every other cluster | TOP-021, TOP-023, TOP-029 |
| D12 | `libs/cache` gains `SnapshotRegistry`, and `services/cluster` already has `ClusterSnapshots`, which is the same idea with a different shape | The topic service is `SnapshotRegistry`'s only caller in M2. `ClusterSnapshots` is **not** refactored onto it | The two differ where it matters: the cluster service holds two cells per key on two refresh schedules, driven by a registry stream; the topic service holds one cell per key, created on demand and released when a cluster disappears. Unifying them would be a refactor of a shipped Core service whose only benefit is code shape, in the milestone that is already introducing a service, a microfrontend and a service-to-service seam. Recorded in `TECH_DEBT.md` with the condition that M4's consumer service — the third caller — is the milestone that decides whether one abstraction fits three | TOP-006; `TECH_DEBT.md` by TOP-038 |
| D13 | The topic detail page has tabs for Messages, Consumers, ACLs and Connectors in the reference, and none of those services exist | The tab strip renders only the tabs M2 has — Overview and Settings — plus a **generic `topic.tabs` guest host**: `TopicDetail` renders `FeaturePanel` for every guest registered against the `topic.tabs` slot id, and registers none itself. The slot ids are declared once in `frontend/ui-kernel`'s `FeatureSlots` (TOP-027). A guest that is not configured renders nothing. **This host is a cross-milestone deliverable**: M3's Messages tab and M4's Consumers tab are registrations against it and edit no file in `ui-topics` (M3 §3, M4 D13). No tab is rendered disabled and no tab promises a milestone | The same reasoning as D8, and it is the shape KU-013 asks for: the slot is keyed by feature id and never by import, so M4 adds a registration and touches no file in `ui-topics` | TOP-030 |

**Standing rule, restated from the M0 and M1 plans.** A blocker owned outside the execution loop
is not a reason to stop: propose the decision from the evidence available, take it, record it in
the artifact that owns it, and leave a cheap reconciliation path if the external input ever
arrives.
