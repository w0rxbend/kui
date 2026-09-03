# M1 — Cluster connectivity: technical development plan

**Status:** grooming step G5 output (PLAN §39, format PLAN §41), 2026-09-03.
**Owners:** Chief Architect (this document, module boundaries, the task graph), Principal Scala
Engineer (`libs/kafka`, `libs/kafka-auth`, `libs/cache`), Domain Architect — cluster (the
`services/cluster` lanes), Frontend Architect (the `ui-clusters` lane), Infrastructure Lead
(configuration, environments, operator documentation), QA Engineer (the Testcontainers matrix
and the fault-isolation E2E).

This plan is the only input an implementation worker gets, together with the task spec it picks
up (`tasks/<ID>.md`), the ADRs that task cites, and `CLAUDE.md`. If a worker has to ask a
question, the answer belongs in this plan or in the task spec — not in a private reply.

The individual task specs are written by seven area agents, one per task-id prefix, from the
boundaries in §5 and §6 of this document. §6.5 states, per area, exactly which files and modules
that area may create or change, so that two agents never write the same file.

---

## 1. Milestone goal

KUI connects to real Kafka clusters with production security settings and shows what they are
made of: a multi-cluster dashboard, a broker list, broker configurations and log directories,
against any SASL/SSL-secured cluster, with a cluster being down never taking the page down.

M0 proved the chain — contract → client → gateway → capability registry → shell → fallback
panel — on a sample service that talked to nothing. M1 is the first time that chain carries real
data, and it introduces the three things every later milestone will build on and must not have
to re-invent:

1. **`libs/kafka` and `libs/kafka-auth`** — the only modules in KUI that import
   `org.apache.kafka.*`, and the only place a JAAS string is ever assembled. Ten more services
   in M2–M8 reach Kafka through the port family this milestone establishes.
2. **The Kafka-backed metadata store (ADR-042)** — `__kui_config` and `__kui_files` on a
   statically configured store cluster, with replay, tail-following, optimistic versioning and
   envelope-encrypted secrets. Registering a cluster at runtime is possible from the moment KUI
   can talk to Kafka at all, so the store is built now rather than twice.
3. **The first real domain service** — `services/cluster` gains a `domain`, an `application` that
   does more than answer a constant, and its first `infrastructure` module. Whether the layering
   rules of ADR-041 survive contact with a Java SDK is decided here, once, for everyone.

`Ping` does not survive this milestone. It existed to give M0's layering something to carry
(`docs/domain/cluster.md` §"Status in M0"); M1 replaces it with the real model and deletes it.

## 2. Exit criteria (copied from `docs/ROADMAP.md`, M1)

- Testcontainers suite: PLAINTEXT, SASL_PLAINTEXT/SCRAM and SSL clusters; each yields the same
  broker list, configs and log dirs through the contract client.
- Manual acceptance against one real external cluster recorded in `STATUS.md`.
- Dashboard with three configured clusters, one unreachable: two rows populate, the third shows
  `Unavailable: <reason>` and remains clickable; response time is bounded by the per-service
  timeout, not by the dead cluster.
- Fault-isolation E2E: stopping `kui-cluster` leaves the shell, settings and the other clusters'
  cached rows (greyed, timestamped) usable.
- Configuration with an unknown key, a missing secret, or an invalid URL fails at startup with
  all errors accumulated in one message.
- Metadata store: with `kui.store.kafka.*` pointed at a Testcontainers broker, the service
  creates `__kui_config` and `__kui_files`, replays them at startup and serves clusters from the
  store; a pre-existing `__kui_config` with `cleanup.policy=delete` fails startup with a message
  naming the topic, the setting, the expected value and the found value.
- Two cluster-service replicas writing the same cluster key concurrently: one succeeds, the other
  gets `KUI-CONFIG-VERSION-CONFLICT`; both converge on the winner's record.
- A write returns 200 only after the writer has read its own record back from the log tail.
- Secret fields of a stored cluster are unreadable in the raw topic record: a console-consumer
  dump of `__kui_config` contains no plaintext password and no JAAS string.
- With `kui.store.kafka.*` unset, the file adapter is used, the store-backed write endpoints
  report `NotConfigured`, and everything else in M1 still passes.
- Store cluster stopped mid-run: clusters keep resolving from last known state, the affected
  capability reports `Degraded` with a reason, and writes are rejected rather than lost.

Inherited from PLAN §46 for every milestone: compiles with `-Werror`; all unit / property /
integration / contract tests pass; fault-isolation tests pass for every service introduced;
formatting and scalafix clean; OpenAPI regenerated and committed; docs and feature matrix
updated; ADRs Accepted; CEO acceptance recorded in `STATUS.md`.

Feature-matrix rows closed by M1: CL-001, CL-002, CL-003, CL-005, CL-007, CL-009, BR-001,
BR-002, BR-005, PA-003, AU-005, OT-001, OT-003, OT-004, OT-007, OT-008, OT-009, OT-010, KU-010,
KU-011, KU-012, KU-033 (first scenario).

## 3. Non-goals

Restating the roadmap, and adding the boundaries workers most often cross by accident:

- **No topics, messages, consumer groups, ACLs, quotas, schemas, Connect or ksqlDB.** Those are
  M2–M7. `libs/kafka` in M1 implements **`ClusterAdmin[F]` only**. `TopicAdmin`, `GroupAdmin`,
  `SecurityAdmin` and `MessageBrowsePort` are sketched in `ARCHITECTURE.md` §4.2–§4.3 and must
  **not** be declared as empty traits here: an empty trait is an invitation to fill it, and a
  port designed before its first caller exists is designed wrong. `services/topic` and the other
  service trees are not created.
- **No broker config *edits*.** `BR-002` is read-only in M1. `ClusterAdmin.alterBrokerConfig` and
  `alterReplicaLogDir` are not implemented; the inline edit affordance Kafbat has
  (`research/kafbat/ui-analysis.md` "Broker details") is not built. Mutations arrive in M5 with
  read-only mode and audit, and no destructive action ships before its safety net (ROADMAP
  ordering rationale §3).
- **No metrics columns.** Bytes in / bytes out, throughput and JMX-derived figures render as `—`
  with no tooltip promising a date. `services/metrics` does not exist until M8. Skew percentages
  are *not* metrics: they are computed from the partition assignment the broker list already has,
  and they do ship (BR-001).
- **No cluster CRUD screen.** CW-005 is M8. M1 builds the store and the `ConfigStore`-backed
  registry *underneath* the wizard, plus the one write endpoint the concurrency exit criteria
  require (§10, decision D6). There is no form, no validate/apply flow, no file upload UI.
- **No login, no RBAC evaluation.** `kui.auth.type` stays `disabled`; `services/identity` does
  not exist. The store's `rbac/roles` key is a documented section that nothing writes in M1.
  AU-005 ships theme and timezone only; the logout item appears in M6.
- **No audit events.** ADR-042 names `__kui_audit`, but audit is M5 (AU-001 … AU-004). M1 creates
  and validates `__kui_config` and `__kui_files` only. The audit topic's settings live in
  `docs/operations/metadata-store.md` and are created by the milestone that first writes a
  record (§10, decision D7).
- **No serdes, no Schema Registry client.** ADR-014 is listed among M1's ADRs in the roadmap
  because M3's serdes will need it; nothing in M1's scope calls a registry. It stays Accepted and
  unimplemented (§10, decision D8).
- **No virtualized tables.** SF-003 is M2. The broker list is a few dozen rows; the M0
  `DataTable` renders it.
- **No topic-count / partition-count scraping beyond what `describeCluster` and the broker set
  give.** CL-003's dashboard numbers come from the cluster topology snapshot. A full
  `describeTopics` sweep of a 10k-topic cluster belongs to `services/topic` (M2) and must not be
  added to the cluster service's refresh loop to fill a dashboard cell.
- **No new caching primitives beyond `SnapshotCell`.** `libs/cache` ships `SnapshotCell` in M1;
  `BoundedCache` (Caffeine) arrives with its first consumer in M2/M3 (ADR-016).

## 4. Architecture references

| Reference | Why M1 needs it |
| --- | --- |
| ADR-006 fs2-kafka 4 and admin ports | one adapter over `KafkaAdminClient[F]`, the raw `Admin` escape hatch, batching, invalidation, error mapping |
| ADR-013 Ciris | `kui.clusters[]` and `kui.store.*` as typed configuration with accumulated errors |
| ADR-016 caching | `SnapshotCell`, TTL / invalidation / bound / metrics / staleness contract per cache |
| ADR-022 typed cluster auth | the security ADT, JAAS rendering with quoting, the `properties` override layer, cloud handlers as optional runtime modules |
| ADR-027 per-context snapshots | `status: Initializing \| Online \| Offline(lastError)`, `scrapedAt`, atomic replacement |
| ADR-030 minimum broker version | 2.8 minimum, `describeFeatures` version detection with the broker-config fallback, capability gating rather than version assumptions |
| ADR-031 cluster id | `ClusterId` is a slug of the configured name; `KafkaClusterId` is recorded and paired with `BrokerId` in cache keys |
| ADR-032 navigation state model | how `Degraded` / `Unavailable` / `NotConfigured` render, and the stale-data rule DC-H3 |
| ADR-034 error envelope | `KUI-CONFIG-VERSION-CONFLICT`, the Kafka-error → `KuiError` mapping targets, `ErrorEnvelope.statusOf` |
| ADR-035 streaming envelope | `/internal/v1/clusters/stream` named SSE events |
| ADR-036 dynamic config ownership | single writer per section; the cluster service owns `kui.clusters[]`; profile distribution by ETag + SSE |
| ADR-037 upstream resilience | the per-upstream timeout that bounds the dashboard, the circuit feed into the registry |
| ADR-039 capability fold | four inputs, precedence, sticky `since`, asymmetric debounce; only `InfrastructureError` dims a capability |
| ADR-041 layering, machine-enforced | A1 (`domain` sees only `libs/kernel` and cats-core), A5 (no lib depends on a service), A8 (no Kafka on the gateway), and the two new rules M1 adds |
| ADR-042 metadata store | topics, bootstrap ordering, consistency, secrets at rest, who reads the log, failure behaviour |
| ADR-043 internal service calls | `/internal/v1` direct calls, one hop, cached last-known fallback, reported to the registry |

`ARCHITECTURE.md` sections: §2 service catalog and tiers, §3 module layout and the
`checkArchitecture` rule table, §4.1 kernel types, §4.2 the `KafkaAdminPort` family, §5 internal
contracts and headers, §6 the degraded-response envelope, §9 the cluster row of the state and
caching table, §10 configuration ownership, §10.1 the metadata store, §14 security boundaries.

`research/kafka/admin-capabilities.md` is the **behavioural source** for every admin call in this
milestone and it outranks any sketch in `ARCHITECTURE.md`: §0 (cross-cutting AdminClient facts
and the capability-probing table), §1 (cluster and broker operations, with the exact errors and
the reference workarounds), DC-D1 through DC-D5. `research/kafbat/ui-analysis.md` "Dashboard" and
"Brokers" decide what a row shows and what a page does when its data is missing;
`research/kafbat/feature-matrix.md` records which of those behaviours are worth copying.
`research/design/REFERENCE.md` decides how the screens look and nothing else — its broker hosts
and offsets are invented sample data.

`docs/domain/cluster.md` is the context's own document; the M1 tasks that add model types update
it in the same commit.

## 5. Module map

M1 creates four Mill modules and changes eleven. A cross-compiled module has `.jvm` and `.js`
children (`KuiModule` traits in `build.mill`).

### 5.1 New modules

| Path | Mill id | Platforms | Depends on | Purpose |
| --- | --- | --- | --- | --- |
| `libs/kafka-auth` | `libs.kafkaAuth` | JVM | `libs.kernel.jvm`, cats-core, fs2-io | renders `security.*` / `sasl.*` / `ssl.*` client properties and JAAS strings from the typed ADT, with correct quoting and escaping; materializes keystore bytes to a private tmpfs path; declares the cloud SASL handlers as **optional runtime** coordinates that are not on the default classpath |
| `libs/kafka` | `libs.kafka` | JVM | `libs.kafkaAuth`, `libs.kernel.jvm`, `libs.observability`, fs2-kafka 4, kafka-clients 4.3.1, snappy/lz4 (runtime) | `ClusterAdmin[F]` and its adapter over `KafkaAdminClient[F]`, `KafkaErrorMapper`, `BatchResult`, batching and parallelism helpers, client factory and invalidation, capability probing |
| `libs/cache` | `libs.cache` | JVM | `libs.kernel.jvm`, `libs.observability`, cats-effect, fs2 | `SnapshotCell[F, A]`: a `Ref`-backed single value with `status`, `scrapedAt`, atomic replacement, `refresh` under a `Supervisor` and `Stale` reads while the upstream fails (ADR-016) |
| `services/cluster/infrastructure` | `services.cluster.infrastructure` | JVM | `services.cluster.domain`, `libs.kafka`, `libs.config`, `libs.cache`, `libs.observability` | the adapters implementing the cluster domain's ports. The **only** module in the cluster tree with a Kafka client on its classpath |

`libs/kafka-auth` is a separate module from `libs/kafka` for one reason that is worth stating so
nobody merges them: the property renderer is pure, has no Kafka client dependency, and is unit
testable as string-in / string-out. Keeping it separate is what makes the JAAS-injection property
test cheap, and it is what will let the config validator render a candidate profile's properties
in M8's wizard without dragging a Kafka client into the validating process.

### 5.2 Changed modules

| Mill id | Change | Task area |
| --- | --- | --- |
| `libs.kernel.{jvm,js}` | gains the pure `kui.kernel.cluster` package: `BootstrapServers`, the `ClusterSecurity` ADT, `ClientProperties`, `AdminTuning`. Cross-compiled and dependency-free, so `domain` (A1), `libs/config`, `libs/kafka-auth` and `libs/contracts-core` share one definition instead of four (decision D1) | KAFKA |
| `libs.config` | gains the `kui.clusters[]` and `kui.store.*` slices, the `ConfigStore[F]` port, `StoreRecord`, `FieldCrypto`, the file adapter and the Kafka adapter. Gains a `libs.kafka` dependency **for the Kafka adapter only** | STORE, CFGOP |
| `libs.contractsCore.{jvm,js}` | gains the redacted cluster DTO fragments shared by the cluster contract and the gateway aggregation | CLAPI |
| `libs.testkit` | gains the Testcontainers Kafka topology (PLAINTEXT, SASL_PLAINTEXT/SCRAM, SSL) and cluster generators | CFGOP |
| `services.cluster.domain` | the real model replaces `Ping` | CLDOM |
| `services.cluster.application` | the real use cases replace `PingUseCase`; gains `libs.cache` | CLDOM |
| `services.cluster.contract.{jvm,js}` | the real endpoints replace `/internal/v1/ping` | CLAPI |
| `services.cluster.api` | server logic for the real endpoints, Kafka-error → envelope mapping, `Section` staleness | CLAPI |
| `services.cluster.app` | gains `services.cluster.infrastructure`; the bootstrap ordering of ADR-042 lives in the wiring | CLAPI |
| `services.gateway.{contract,api,application}` | the cluster proxy routes, `X-Kui-Cluster-Id` validation, the dashboard aggregation, per-cluster capability entries | CLAPI |
| `frontend.uiKernel`, `frontend.uiShell`, `frontend.uiClusters` | the stale-data overlay, the cluster switcher, the settings page, and the real cluster screens | CLUI |
| `apps.allinone`, `deployment.{docker,compose}` | the cluster service's new modules in the composition root; a broker and store settings in the development environment | CFGOP |

**New dependency edges, and why each is legal.** `libs.kafkaAuth → libs.kernel.jvm`;
`libs.kafka → libs.kafkaAuth`; `libs.kafka → libs.observability` and `libs.cache → libs.observability` (both publish the metric names `docs/operations/observability.md` and ADR-016 already promise; `libs.http → libs.observability` is the precedent, and A10 governs Kafka on a classpath, not metrics); `libs.config → libs.kafkaAuth` and `libs.config → libs.kafka` (the Kafka `ConfigStore` adapter — the
store *is* a Kafka client, so this edge is the point of ADR-042, not a leak); `libs.cache →
libs.kernel.jvm`; `services.cluster.infrastructure → {domain, libs.kafka, libs.config,
libs.cache}`; `services.cluster.application → libs.cache`; `services.cluster.app →
services.cluster.infrastructure`. No `libs` module depends on a service (A5). No `application`
module depends on `libs.kafka`, `libs.http`, `libs.contractsCore`, Tapir or Circe (A3). The
gateway gains no Kafka edge of any kind (A8), and its cluster knowledge is the cluster service's
`contract` module and nothing else (A4).

**Two rules M1 adds to `checkArchitecture`** (task CFGOP-003), because A1–A8 do not yet constrain
a module that did not exist when they were written:

| Rule | What it forbids | Why |
| --- | --- | --- |
| A9 | a service's `application`, `contract` or `api` module depending on that service's `infrastructure` module | the dependency rule points inward; an `api` that can see an adapter will eventually call one, and the port becomes decoration |
| A10 | `libs.kafka`, `libs.kafkaAuth`, fs2-kafka or kafka-clients on the classpath of any module that is not a service's `infrastructure`, `libs/kafka*` itself, `libs/config`, `libs/testkit` or an `app` | this is A8 generalised from the gateway to everyone. `org.apache.kafka.*` must be importable in exactly the places that adapt it |

`libs/config` is on A10's allow-list because the Kafka `ConfigStore` adapter lives there
(ADR-042 §5). That is the one exception, it is deliberate, and the rule names it explicitly so
that a second exception has to be argued in a commit that changes the rule.

## 6. Task graph

57 tasks. Sizes: **S** ≈ 1–2 h, **M** ≈ 2–4 h, **L** ≈ 4–6 h. Every task ends on a green `main`:
a task that adds a module also adds that module's first test, a task that changes a contract
regenerates the committed OpenAPI document in the same commit, and a task that deletes a type
(`Ping`) deletes its tests and its documentation paragraph in the same commit.

### 6.1 Parallel lanes

| Lane | Prefix | Owner role | Owns |
| --- | --- | --- | --- |
| **A — Kafka platform libraries** | `KAFKA-` | Principal Scala Engineer | `libs/kernel`'s cluster-connection package, `libs/kafka-auth`, `libs/kafka`, `libs/cache` |
| **B — Metadata store** | `STORE-` | Principal Scala Engineer | everything under `libs/config` that is the store: envelope, crypto, port, both adapters, topic bootstrap, the store's own Testcontainers suite |
| **C — Cluster domain and application** | `CLDOM-` | Domain Architect (cluster) | `services/cluster/domain`, `services/cluster/application` |
| **D — Cluster adapters** | `CLADP-` | Domain Architect (cluster) | `services/cluster/infrastructure` |
| **E — Cluster contract, API and edge** | `CLAPI-` | Chief Architect | `services/cluster/{contract,api,app}`, the cluster-shaped parts of `services/gateway/*`, `libs/contracts-core` cluster fragments |
| **F — Frontend** | `CLUI-` | Frontend Architect | `frontend/ui-clusters`, and the cluster-shaped additions to `frontend/ui-kernel` and `frontend/ui-shell` |
| **G — Configuration, environments, operations, E2E** | `CFGOP-` | Infrastructure Lead + QA Engineer | the `kui.clusters[]` Ciris slice, `build.mill` rules, `libs/testkit`'s Kafka topology, `deployment/*`, `apps/allinone`, `docs/operations/*`, the E2E suite, the milestone documentation |

Lane A unblocks B, C, D and G. Lane E can start as soon as CLDOM-002 exists (it needs the domain
types to shape DTOs, not the adapters that fill them). Lane F can start immediately on CLUI-001
(a kernel primitive with no backend dependency) and joins the graph at CLAPI-007.

**A sequencing constraint that is not a dependency.** Other agents are restyling
`frontend/ui-kernel` and `frontend/ui-shell` to the design of `research/design/REFERENCE.md`.
Lane F must not begin editing those two modules until that work has landed; `frontend/ui-clusters`
is untouched by the restyle and is available immediately. CLUI-001 and CLUI-007, the two tasks
that touch the kernel and the shell, are therefore scheduled after the restyle commits, and the
task specs say so.

### 6.2 Ordered task list

| ID | Title | Size | Depends on | Lane |
| --- | --- | --- | --- | --- |
| KAFKA-001 | `libs/kernel`: the typed cluster connection and security ADT | M | — | A |
| KAFKA-002 | `libs/kafka-auth`: client property and JAAS rendering with quoting | L | KAFKA-001 | A |
| KAFKA-003 | `libs/kafka-auth`: keystore materialization and optional cloud handlers | M | KAFKA-002 | A |
| KAFKA-004 | `libs/kafka`: module, client factory, `client.id`, timeouts, invalidation | M | KAFKA-003 | A |
| KAFKA-005 | `libs/kafka`: `KafkaErrorMapper` and `BatchResult`, total over the documented exceptions | M | KAFKA-004 | A |
| KAFKA-006 | `libs/kafka`: batching and bounded parallelism from `AdminTuning` | M | KAFKA-005 | A |
| KAFKA-007 | `ClusterAdmin` A: `describeCluster`, nodes, version detection | L | KAFKA-006, CFGOP-004 | A |
| KAFKA-008 | `ClusterAdmin` B: broker configs, log dirs, KRaft quorum | L | KAFKA-007 | A |
| KAFKA-009 | `ClusterAdmin` C: the `ClusterFeature` capability probe | M | KAFKA-007 | A |
| KAFKA-010 | `libs/cache`: `SnapshotCell` with status, `scrapedAt` and supervised refresh | M | — | A |
| STORE-001 | `StoreRecord` envelope, `StoreKey`, explicit codecs and golden files | M | — | B |
| STORE-002 | `FieldCrypto`: AES-GCM envelope encryption, `keyId`, rotation reads | M | STORE-001 | B |
| STORE-003 | `ConfigStore[F]` port and the file adapter | M | STORE-001 | B |
| STORE-004 | `kui.store.*` configuration slice | S | STORE-001, KAFKA-001 | B |
| STORE-005 | Store topic bootstrap: create if missing, validate if present, fail fast | M | STORE-004, KAFKA-004 | B |
| STORE-006 | `KafkaConfigStore`: replay to the end, then follow the tail | L | STORE-005, STORE-002 | B |
| STORE-007 | Store writes: optimistic `version`, read-your-writes, conflict detection | L | STORE-006 | B |
| STORE-008 | `StoreHealth`, the `changes` stream and the unreachable-store contract | M | STORE-007 | B |
| STORE-009 | Store integration suite against a Testcontainers broker | L | STORE-008, CFGOP-004 | B |
| CLDOM-001 | Cluster domain: `ClusterProfile` and `ClusterRef`; `Ping` scheduled for deletion | M | KAFKA-001 | C |
| CLDOM-002 | Cluster domain: the topology model and its invariants | M | CLDOM-001 | C |
| CLDOM-003 | Cluster domain ports: `ClusterAdmin`, `ClusterConfigStore`, `ConnectivityProbe` | S | CLDOM-002 | C |
| CLDOM-004 | `ClusterRegistry`: static configuration overlaid by the store | M | CLDOM-003 | C |
| CLDOM-005 | Topology snapshot use case: refresh, staleness, forced refresh | L | CLDOM-004, KAFKA-010 | C |
| CLDOM-006 | Broker detail use cases: configs, log dirs, per-partition sizes | M | CLDOM-005 | C |
| CLDOM-007 | The real `CapabilityReportUseCase`: per cluster, including store health | M | CLDOM-005 | C |
| CLADP-001 | `services/cluster/infrastructure`: the module and its first adapter test | S | CLDOM-003, KAFKA-007 | D |
| CLADP-002 | `ClusterAdmin` adapter and the per-cluster client lifecycle | M | CLADP-001, KAFKA-008, KAFKA-009, CFGOP-004 | D |
| CLADP-003 | `ClusterConfigStore` adapter over `ConfigStore[F]` | M | CLADP-001, STORE-007 | D |
| CLADP-004 | `ConnectivityProbe` adapter | S | CLADP-002 | D |
| CLADP-005 | Profile change propagation: store tail → registry reload → version bump | M | CLADP-003, CLDOM-004, STORE-008 | D |
| CLAPI-001 | Cluster contract DTOs, redaction and golden files | M | CLDOM-002 | E |
| CLAPI-002 | Cluster read endpoints: clusters, brokers, configs, log dirs, refresh | M | CLAPI-001 | E |
| CLAPI-003 | Internal profile contract: `{id}/profile` with ETag and `clusters/stream` | M | CLAPI-001 | E |
| CLAPI-004 | Cluster `api`: server logic, error envelope, `Section` staleness; the whole `Ping` family deleted | L | CLAPI-002, CLAPI-003, CLDOM-006 | E |
| CLAPI-005 | Cluster `app`: wiring and the ADR-042 bootstrap ordering | L | CLAPI-004, CLADP-005, STORE-006 | E |
| CLAPI-006 | Gateway: cluster routes and `X-Kui-Cluster-Id` validation | M | CLAPI-002 | E |
| CLAPI-007 | Gateway: the dashboard aggregation with per-row section status | M | CLAPI-006 | E |
| CLAPI-008 | Gateway: per-cluster capability entries in the registry | S | CLAPI-006, CLDOM-007 | E |
| CLAPI-009 | The one store-backed write endpoint, and `NotConfigured` without a store | M | CLAPI-004, CLADP-003 | E |
| CLAPI-010 | OpenAPI regeneration, error-code table, contract snapshot | S | CLAPI-007, CLAPI-009 | E |
| CLUI-001 | `ui-kernel`: `StaleDataOverlay` and stale retention in `QueryCache` | M | — | F |
| CLUI-002 | `ui-clusters`: typed clients derived from the cluster contract | S | CLAPI-001, CLAPI-002 | F |
| CLUI-003 | Dashboard: cluster rows, per-row status, unavailable rows stay clickable | L | CLUI-002, CLUI-001, CLAPI-007 | F |
| CLUI-004 | Brokers list: rack, leaders, replicas, skew; metric columns as `—` | L | CLUI-003 | F |
| CLUI-005 | Broker detail: log dirs and configs tabs | L | CLUI-004 | F |
| CLUI-006 | Shell: cluster switcher, status dot, per-cluster colour tag | M | CLUI-003 | F |
| CLUI-007 | Settings page: theme, timezone, refresh rate, table density | M | CLUI-001 | F |
| CLUI-008 | Force refresh action and its 202 handling | S | CLUI-004, CLUI-002 | F |
| CFGOP-001 | `kui.clusters[]`: typed security, the `properties` override layer, slug ids | L | KAFKA-001 | G |
| CFGOP-002 | `kui.clusters[].admin`: timeout, batching and concurrency knobs | S | CFGOP-001 | G |
| CFGOP-003 | `checkArchitecture` rules A9 and A10, with their build tests | S | CLADP-001 | G |
| CFGOP-004 | `libs/testkit`: the PLAINTEXT / SASL-SCRAM / SSL Testcontainers topology | L | KAFKA-002 | G |
| CFGOP-005 | The three-security-mode parity suite through the contract client | L | CFGOP-004, CLAPI-004 | G |
| CFGOP-006 | All-in-one and Compose: a broker, store settings, the encryption key | M | CLAPI-005 | G |
| CFGOP-007 | Fault-isolation E2E: the cluster service stopped, and a dead cluster row | L | CFGOP-006, CLUI-005 | G |
| CFGOP-008 | Milestone documentation, operator pages, feature matrix, ADR amendments | M | everything | G |

**The table is grouped by lane, not topologically sorted.** Four edges point backwards in the
listing — CFGOP-004 precedes KAFKA-007, CLADP-002 and STORE-009; CLAPI-004 precedes CFGOP-005.
Read the `Depends on` column, never the row order. (M1 gate review, F-14.)

### 6.3 Critical path

The longest chain of real dependencies in §6.2 — every arrow is an edge that table actually
declares, so nothing here can be reordered or parallelised:

```
KAFKA-001 → KAFKA-002 → KAFKA-004 → STORE-005 → STORE-006 → STORE-007
  → CLADP-003 → CLADP-005 → CLAPI-005 → CFGOP-006 → CFGOP-007 → CFGOP-008
```

12 tasks, roughly 45 working hours of single-threaded effort. It runs through the **metadata
store**, not through the admin client, which is the opposite of what the milestone's name
suggests and is the single most useful thing to know about this graph. The reason is that the
store sits between static configuration and everything else: nothing can be wired into
`services/cluster/app` until the bootstrap order of ADR-042 is real, and nothing can be tested
end to end until it is wired.

A **second chain of almost equal length** runs down the read path and must be worked in parallel
from day one, or it becomes the critical path by default:

```
KAFKA-001 → CLDOM-001 → CLDOM-002 → CLAPI-001 → CLAPI-002 → CLAPI-006
  → CLAPI-007 → CLUI-003 → CLUI-004 → CLUI-005 → CFGOP-007
```

11 tasks. It shares only its first task with the critical path, so lanes C, E and F can run at
full width behind lane A's first two tasks. The admin-client chain (KAFKA-005 … KAFKA-009 →
CLADP-002) has real slack: it joins at CLADP-002 and CLAPI-004, both of which sit off both
chains.

### 6.4 What to do first, and why

Four tasks are worth starting before anything else, not because they are on a path but because
each answers a question whose wrong answer invalidates work that has already been done by the
time the question surfaces.

1. **KAFKA-001 — where the typed connection ADT lives.** `ARCHITECTURE.md` §4.2 writes every
   admin port as `describeCluster(profile: ClusterProfile)`, and `ClusterProfile` is a value
   object of the cluster *domain*. That signature cannot be implemented: rule A5 forbids
   `libs/kafka` depending on a service, and rule A1 forbids the domain depending on
   `libs/kafka-auth`. Either the ADT moves somewhere both can see, or every port grows a second
   ADT and a mapper. This plan decides it (§10, D1) and KAFKA-001 executes the decision. If the
   decision is wrong, it is wrong before `libs/kafka`, `libs/config`, the domain and the
   contracts have all been written against it — and after, it is a rewrite of four modules.
2. **CFGOP-004 — does a secured Testcontainers cluster actually come up?** The milestone's first
   exit criterion is a three-mode security matrix. SASL_SSL against a container needs generated
   keystores, a JAAS file, listener configuration and a broker image that cooperates, and the
   time it takes to make that work is not knowable from a document. Discovering in week three
   that SCRAM cannot be provisioned in the pinned image would leave the adapter untestable with
   no time to react. It depends only on KAFKA-002, so it can start almost immediately.
3. **STORE-006 — does replay-then-tail terminate?** The roadmap's own risk section says a bug in
   the bootstrap ordering makes the service *hang* rather than fail, which is the worst failure
   shape a startup path can have. The replay's end-offset detection, its timeout and its named
   error are the whole mitigation, and everything downstream of `CLAPI-005` assumes they work.
4. **KAFKA-005 — is the error mapper total?** `research/kafka/admin-capabilities.md` §1 lists the
   exceptions a secured, managed or partly-authorized cluster throws for perfectly ordinary
   requests. Every later decision — which failures dim a capability (ADR-039 §6), which produce a
   404 rather than a 500, which mean "reconnect" — keys on this mapping. A property test that the
   mapper is total over the documented classes is cheap now and expensive to retrofit across ten
   services.

The first three are the questions the brief names: the metadata store's bootstrap ordering and
the admin client's behaviour against a secured cluster. The fourth is the one the research
document makes visible.

### 6.5 Area boundaries

Seven agents write the task specs. These are the boundaries; a file appears in exactly one row.

| Area | May create or change | Must not touch |
| --- | --- | --- |
| `KAFKA-` | `libs/kernel/src/kui/kernel/cluster/**`, `libs/kafka-auth/**`, `libs/kafka/**`, `libs/cache/**`, their `build.mill` module definitions | anything under `libs/config`, `services/`, `frontend/` |
| `STORE-` | `libs/config/src/kui/config/store/**` and its tests, the `kui.store.*` slice, `docs/operations/metadata-store.md` sections 2–6 | the `kui.clusters[]` slice (CFGOP-001 owns it), `libs/kafka` |
| `CLDOM-` | `services/cluster/domain/**`, `services/cluster/application/**`, `docs/domain/cluster.md` | `services/cluster/{infrastructure,contract,api,app}`, any `libs` module |
| `CLADP-` | `services/cluster/infrastructure/**` | every other module in the cluster tree; `build.mill`'s rule table (CFGOP-003 owns it) |
| `CLAPI-` | `services/cluster/{contract,api,app}/**`, `services/gateway/**`, `libs/contracts-core/src/kui/contracts/cluster/**`, `docs/api/*` | `services/cluster/{domain,application,infrastructure}`, `frontend/` |
| `CLUI-` | `frontend/ui-clusters/**`, and the named additions in `frontend/ui-kernel` (CLUI-001) and `frontend/ui-shell` (CLUI-006, CLUI-007) | any backend module; the restyle work in progress in `ui-kernel` and `ui-shell` |
| `CFGOP-` | the `kui.clusters[]` slice in `libs/config`, `libs/testkit/**`, `build.mill` rules, `apps/allinone/**`, `deployment/**`, `e2e/**`, `docs/operations/configuration.md`, `docs/ROADMAP.md`, `docs/FEATURE_MATRIX.md`, `STATUS.md`, `TECH_DEBT.md` | `libs/config/src/kui/config/store/**`, every service's own modules |

**One boundary exception, granted at the M1 gate review.** `Ping` spans six files in
`domain`/`application` and four in `contract`/`api`/`app`, and no area owns both halves; two
specs each deferred the deletion to the other, so as written `Ping` is never deleted (F-01).
**CLAPI-004 deletes the whole `Ping` family in one commit**, including the six
`services/cluster/{domain,application}` files, and may touch those two modules **for deletion
only**. It is the right task: it depends on CLDOM-006 and CLAPI-002, so every replacement exists,
and it is the task that removes the route. CLDOM-001 and CLAPI-002 leave `Ping` compiling.

Two shared files need a rule rather than an owner. **`build.mill`** is edited by five areas (each
new module declares itself); the rule is that a task edits only the `object` it creates plus the
`moduleDeps` line of the module it is wiring, and CFGOP-003 alone edits the architecture rule
table. **`docs/FEATURE_MATRIX.md`** is edited only by CFGOP-008, at the end, from the evidence
the other tasks left; no task flips its own row.

## 7. Test plan

Test kinds follow PLAN §32 and ADR-018. **MUnit is the only framework**; no mocking library;
fakes live in `libs/testkit`. A Scala.js test module and a JVM test module cannot run in one Mill
invocation (`CLAUDE.md`), so lane F's suites are always a separate command.

| Suite | Where | Runner | What it covers |
| --- | --- | --- | --- |
| Connection ADT | `libs.kernel.{jvm,js}.test` | MUnit + ScalaCheck | the security ADT round-trips, `Secret` fields redact in `toString`, the ADT compiles and runs on Scala.js (it is cross-compiled, so this is not theoretical) |
| Property rendering | `libs.kafkaAuth.test` | MUnit + ScalaCheck | every mechanism renders the documented `security.*` / `sasl.*` / `ssl.*` keys; **the JAAS property test**: for any password containing quotes, backslashes, spaces, newlines and `=`, the rendered JAAS string parses back to exactly the input (this is the test that closes Kouncil's `String.format` injection, `research/scala/security-research.md` §3); the `properties` override layer is applied last and wins |
| Error mapping | `libs.kafka.test` | MUnit + ScalaCheck | `KafkaErrorMapper` is **total** over every `org.apache.kafka.common.errors.*` class named in `research/kafka/admin-capabilities.md` §1 and §0, and the reconnect-class set is exactly `{Timeout, SaslAuthentication, SslAuthentication, BrokerNotAvailable}`; suppressible per-key errors become `Skipped(key, reason)`, never silent drops |
| Batching | `libs.kafka.test` | MUnit + `munit-cats-effect` + `TestControl` | chunk sizes come from `AdminTuning`, parallelism is bounded, a failed chunk does not fail the batch, results merge in a deterministic order |
| Admin adapter | `libs.kafka.test` | MUnit + Testcontainers (PLAINTEXT) | `describeCluster` against a live broker; a `null` controller during failover is `None` and not a crash; `describeLogDirs` returns per-directory errors rather than failing; version detection falls back from `describeFeatures` to `inter.broker.protocol.version` |
| Capability probe | `libs.kafka.test` | MUnit + Testcontainers | the probe table of `research/kafka/admin-capabilities.md` §0 against a real broker; `UnsupportedVersionException` and `InvalidRequestException` downgrade to "feature absent" rather than propagating (the managed-service behaviour) |
| Snapshot cell | `libs.cache.test` | MUnit + `munit-cats-effect` + `TestControl` | `Stale` reads while the refresh fails, `scrapedAt` monotonicity, atomic replacement under concurrent readers, refresh cancelled with the supervisor |
| Store envelope | `libs.config.test` | MUnit | `StoreRecord` encodes to a committed golden file; an unknown `envelopeVersion` is a named error, never a silent skip |
| Crypto | `libs.config.test` | MUnit + ScalaCheck | AES-GCM round-trip for arbitrary bytes; a record written under `keyId` A stays readable after a rotation to B; a wrong key produces a named error and never a partial plaintext; **no ciphertext or key appears in any `toString`** |
| Store, integration | `libs.config.test` | MUnit + Testcontainers | the full ADR-042 contract: topics created when missing; an existing `__kui_config` with `cleanup.policy=delete`, the wrong partition count or the wrong `max.message.bytes` fails startup with a message naming the topic, the setting, the expected and the found value; replay reaches the end offset; a replay that cannot reach the end fails with a named error inside its timeout instead of hanging; two writers racing on one key give one success and one `KUI-CONFIG-VERSION-CONFLICT` and both converge; a write returns only after read-back; **a raw consumer dump of the topic contains no plaintext password and no JAAS string** |
| File adapter | `libs.config.test` | MUnit | reads a mounted directory; writes report `NotConfigured`; the store-less configuration path passes every other cluster test |
| Configuration | `libs.config.test` | MUnit + ScalaCheck | `kui.clusters[]` precedence and accumulated errors; an unknown key, a missing secret and an invalid URL are reported **together, in one message**; two clusters whose names slug to the same id are rejected at validation with both names; the `properties` override map is redacted by key pattern in every rendering |
| Cluster domain | `services.cluster.domain.test` | MUnit + ScalaCheck | `ClusterProfile` invariants; ISR ⊆ replicas; a `Broker` with no rack is `None` rather than an empty string; a `ClusterDescription` with no controller is representable (KRaft failover) |
| Cluster application | `services.cluster.application.test` | MUnit + `munit-cats-effect` + `TestControl` + fake ports | the registry overlays store records on static configuration in the documented precedence; a store record for an unknown cluster is added, not ignored; the snapshot refresh loop keeps serving the previous value while the admin port fails; forced refresh is idempotent under concurrency; the capability report is `Degraded` when the store is unreachable and `Available` when only an optional probe failed |
| Cluster adapters | `services.cluster.infrastructure.test` | MUnit + Testcontainers | the adapter satisfies the same fake-port contract the application tests use, against a live broker; client invalidation recreates the client after a reconnect-class error and does not on a request-level one |
| Cluster contract | `services.cluster.api.test` | MUnit + Tapir stub interpreter | every endpoint's success and error paths without a socket; **no secret field appears in any response body**, asserted against a profile whose every secret is a distinctive token; stale responses carry `Section.Stale` with `scrapedAt` |
| Security-mode parity | `libs.testkit` driven, `services.cluster.api.test` | MUnit + Testcontainers | the milestone's headline criterion: PLAINTEXT, SASL_PLAINTEXT/SCRAM and SSL clusters yield **byte-identical** broker lists, configs and log dirs through the contract client, modulo the connection settings themselves |
| Gateway aggregation | `services.gateway.api.test` | MUnit + stub upstream | three configured clusters, one upstream failing: two sections populate, the third is `Unavailable` with a reason, and the whole response returns within the per-upstream timeout — asserted with `TestControl`, so a regression that serialises the calls fails the test rather than merely slowing it |
| Frontend unit | `frontend.uiClusters.test` | MUnit under Node | the dashboard row state table (available / degraded / unavailable / not-configured × cached / uncached); metric columns render `—` and not `0`; the stale overlay's timestamp formatting under the selected timezone |
| Frontend DOM | `frontend.uiKernel.test` with `JsEnvConfig.JsDom()` | MUnit + `scala-dom-testutils` | the stale overlay's ARIA semantics; an unavailable row is still focusable and clickable |
| All-in-one integration | `apps.allinone.test` | MUnit + `munit-cats-effect` + Testcontainers | the whole graph boots against one broker that is both the store cluster and the managed cluster; readiness flips only after replay completes; `GET /api/v1/clusters` reports the cluster |
| E2E fault isolation | `e2e.test` | JVM Playwright + Testcontainers Compose | `docker stop kui-cluster` leaves the shell, the settings page and the cached cluster rows usable, greyed and timestamped; `docker start` recovers with no page reload |

**Testcontainers in M1:** Kafka in three security configurations, and nothing else. No Schema
Registry, no Connect, no ksqlDB, no LDAP — those arrive with the milestones that need them. The
nightly job additionally runs the 2.8-compatible broker image required by ADR-030; the default CI
job runs the latest 4.x.

**Fault-injection scenarios in M1:**

1. The cluster service stopped (CFGOP-007) — the milestone's headline scenario.
2. One managed cluster unreachable, two healthy — the dashboard's partial-aggregation criterion.
3. The **store** cluster unreachable while managed clusters are fine — clusters keep resolving
   from the last replayed state, the capability is `Degraded`, writes are rejected (STORE-008).
4. A managed cluster that authenticates but authorizes nothing — `describeConfigs` and
   `describeLogDirs` return per-key errors and the page renders what it has (KAFKA-008).
5. A slow broker past the per-call timeout — the breaker opens, the capability degrades, and the
   dashboard still returns within its bound (CLAPI-007).

## 8. Risk register

| ID | Risk | Impact | Mitigation | Mitigating task(s) |
| --- | --- | --- | --- | --- |
| R-1 | JAAS generation for GSSAPI, OAUTHBEARER, AWS MSK IAM and Azure Entra cannot be integration-tested locally | A mechanism ships broken and is discovered by a user | PLAIN, SCRAM and SSL are integration-tested against containers; every other mechanism is unit-tested against known-good property strings taken from the vendor documentation, and `docs/operations/configuration.md` marks them "not covered by automated integration tests" in the same table that lists them. No mechanism is claimed as supported without at least the string-level test | KAFKA-002, KAFKA-003, CFGOP-004, CFGOP-008 |
| R-2 | The store's bootstrap ordering makes the service **hang** rather than fail | The worst startup failure shape: no error, no readiness, nothing to search for | The replay has an explicit timeout and a named error (`KUI-STORE-REPLAY-TIMEOUT`), the end-offset detection is tested against a broker, and readiness is reported only after replay completes so a hang is visible as "not ready" with a logged reason | STORE-006, STORE-009, CLAPI-005 |
| R-3 | Losing `kui.store.encryptionKey` makes every stored secret permanently unreadable | Unrecoverable operator data loss | `docs/operations/metadata-store.md` says so in the first paragraph of the key section; the file adapter remains a supported way to run with no such risk; a wrong key fails loudly with a named error rather than producing garbage | STORE-002, CFGOP-008 |
| R-4 | `ARCHITECTURE.md` §4.2's port signatures are unimplementable under rules A1 and A5 | Discovered late, it is a rewrite of `libs/kafka`, the domain, the config slice and the contracts | Decided in this plan (§10, D1) and executed first, before any of those four modules exists | KAFKA-001 |
| R-5 | The pinned fs2-kafka release does not wrap `describeMetadataQuorum`, `describeFeatures` or `describeLogDirs` (open question in ADR-006 and `admin-capabilities.md` open question 1) | A capability cannot be implemented as designed | KAFKA-004 verifies the surface against the pinned tag as its first act and records the result; anything missing goes through the raw `Admin` escape hatch fs2-kafka exposes, which is the documented fallback and costs one adapter method, not a redesign | KAFKA-004, KAFKA-008 |
| R-6 | A secured Testcontainers broker (SASL_SSL, SCRAM provisioning) turns out to be slow or impossible in the pinned image | The headline exit criterion cannot be demonstrated | Started as an early task rather than a late one, so a negative result has weeks of slack; documented fallback is a pre-generated keystore fixture committed under `libs/testkit/resources` and a broker started with a static JAAS file | CFGOP-004 |
| R-7 | `libs/config` gaining a Kafka dependency is read as licence for anything to have one | A9/A10 erode, and `org.apache.kafka` spreads | A10 names the allow-list explicitly and a build test asserts each entry, so a sixth exception must be argued in the commit that adds it | CFGOP-003 |
| R-8 | The dashboard serialises its per-cluster calls and a dead cluster stalls the page | The milestone's most visible criterion fails in production while passing a functional test | The gateway test asserts the bound with `TestControl`, so serialisation fails the suite rather than merely slowing it | CLAPI-007 |
| R-9 | The frontend restyle of `ui-kernel` and `ui-shell` collides with lane F | Merge conflicts in files two swarms are editing | Lane F starts in `ui-clusters`, which the restyle does not touch; CLUI-001, CLUI-006 and CLUI-007 are scheduled after the restyle lands and are the only three tasks that edit those modules | CLUI-001, CLUI-006, CLUI-007 |
| R-10 | The manual acceptance against a real external cluster has no owner inside the execution loop | The one exit criterion CI cannot produce | It is a recorded observation, not a gate on any task: CFGOP-008 prepares the acceptance script and the `STATUS.md` template, and the milestone's automated evidence is complete without it. Per the standing rule of the M0 plan §10, nothing waits on a person outside the loop | CFGOP-008 |
| R-11 | M1 quietly starts building M2 — a `describeTopics` sweep to fill a dashboard cell, an empty `TopicAdmin` trait "for later" | Milestone slips; the topic service inherits a port designed before its first caller | §3 non-goals are restated in every KAFKA and CLDOM task spec, and those specs name the exact files they may create | KAFKA-007 … KAFKA-009, CLDOM-002 |
| R-12 | Secrets leak through a path nobody tested: the profile SSE stream, the `/internal/v1` profile response, a log line during replay, a Kafka client property dump | A security exit criterion fails late, or worse, silently passes | Three separate assertions, in three layers: the contract test with a distinctive-token profile, the raw-topic-dump test, and the property-rendering test's redaction check | CLAPI-001, CLAPI-003, STORE-009 |

## 9. Definition of done for M1

M1 is complete when all of the following are true and the evidence is committed:

1. **Every exit criterion in §2 is demonstrated by a command in CI**, except the manual external
   cluster acceptance, which is recorded in `STATUS.md` as an observation.
2. All 57 tasks are merged, each with an Implementation Report (PLAN §39, one screen).
3. `./mill __.compile` is clean with `-Werror -Wunused:all -source:future`; `./mill __.test` is
   green on the JVM and, in a separate invocation, on Scala.js; `./mill __.checkFormat` and
   `./mill __.fix --check` are clean; `./mill checkArchitecture` passes with rules A9 and A10
   active.
4. `./mill e2e.test` is green against the Compose stack, including the cluster-service
   fault-isolation scenario and the dead-cluster dashboard row.
5. The three-security-mode parity suite is green in CI, and the ADR-030 nightly job against a 2.8
   broker is configured and has run at least once.
6. `GET /api/v1/openapi.json` is regenerated and its snapshot committed under
   `docs/api/openapi.json`; `docs/api/error-codes.md` includes the store and Kafka codes.
7. `docs/FEATURE_MATRIX.md` rows CL-001, CL-002, CL-003, CL-005, CL-007, CL-009, BR-001, BR-002,
   BR-005, PA-003, AU-005, OT-001, OT-003, OT-004, OT-007, OT-008, OT-009, OT-010, KU-010,
   KU-011, KU-012 are `DONE`, and KU-033 records its first scenario.
8. `docs/operations/metadata-store.md` and `docs/operations/configuration.md` describe what
   actually shipped, including the mechanism table of R-1 with its integration-test coverage
   column, and the backup / restore and file-to-Kafka migration sections.
9. `docs/domain/cluster.md` no longer says "scaffolded, not modelled": it documents the real
   aggregate, the ports and their invariants, and the `Ping` paragraph is gone.
10. ADR-022 carries the amendment recording where the connection ADT lives (§10, D1); ADR-042's
    consequences section records what the implementation learned about replay timing.
11. `ARCHITECTURE.md` §3, §4.2, §9 and §10.1 are updated where an M1 task found a delta —
    §4.2's sketch signatures are replaced by links to the implementing files.
12a. **Every long-running or cancellable path introduced in M1 has a named cancellation test.**
    The M0 review found cancellation systematically unconsidered; M1 repeats it in nine specs
    (F-07). The store's replay and tail follower, the write waiter, the health reconnect loop,
    the admin client pool, the profile change listener and the `app` bootstrap `Resource` chain
    each carry a "Cancellation and shutdown" requirement and at least one test that cancels the
    fiber and asserts the resource was released and nothing was left running.
12. `TECH_DEBT.md` records every debt taken during M1, and `STATUS.md` records CEO acceptance with
    the CI run id that produced the evidence.
13. A developer who has never seen the repository can follow `README.md`, bring up the Compose
    stack with one secured broker, and see a populated dashboard in under fifteen minutes.

## 10. Decisions taken in this plan rather than escalated

Grooming produces decisions, not questions (PLAN §39). Where the roadmap, an ADR or
`ARCHITECTURE.md` left a gap that a worker would otherwise have to ask about, this plan closes it
— from the research already gathered, not from opinion.

| # | Gap | Decision | Evidence | Where it lives |
| --- | --- | --- | --- | --- |
| D1 | `ARCHITECTURE.md` §4.2 writes the admin ports as taking a `ClusterProfile`, a **domain** type. Rule A5 forbids `libs/kafka` depending on a service and rule A1 forbids the domain depending on `libs/kafka-auth`, so the signature cannot be written as documented. ADR-022 says the ADT lives "in `libs/config` / `ClusterProfile`", which has the same problem one module over | **The typed connection and security ADT lives in `libs/kernel`**, in a new pure `kui.kernel.cluster` package: `BootstrapServers`, `ClusterSecurity`, `ClientProperties`, `AdminTuning`. The domain's `ClusterProfile` composes it (legal under A1, which allows `libs/kernel`); `libs/config` decodes it with Ciris; `libs/kafka-auth` renders it to client properties; `libs/contracts-core` derives the redacted DTO from it. One definition, no mapper, every rule satisfied | The alternative — a `libs`-level ADT plus a domain ADT plus a mapper in `infrastructure` — is the shape ADR-041 was written to avoid producing by accident, and it would mean the redaction rule was implemented twice. The kernel is already the shared-kernel home of `Secret[A]` and every id type (`ARCHITECTURE.md` §4.1), and the ADT is pure data, so it cross-compiles to Scala.js unchanged | KAFKA-001; **ADR-022 amendment** written by CFGOP-008 |
| D2 | The roadmap's "Introduces" list for M1 does not mention `libs/cache`, but `ARCHITECTURE.md` §9 requires the cluster snapshot to be a `SnapshotCell` with `status`, `scrapedAt` and `Stale` reads | `libs/cache` is created in M1 with **`SnapshotCell` only**. `BoundedCache` (the Caffeine wrapper) waits for its first consumer in M2/M3 | ADR-016 defines both primitives but ties each to a named consumer; building the Caffeine wrapper with no caller would be a cache with no TTL policy to test against | KAFKA-010; §5.1 |
| D3 | `services/cluster/infrastructure` did not exist when the `checkArchitecture` rules A1–A8 were written, so nothing constrains it or the modules that may see a Kafka client | Two new rules: **A9** (nothing in a service points at that service's `infrastructure` except `app`) and **A10** (`libs/kafka*`, fs2-kafka and kafka-clients are allowed only on `infrastructure`, `libs/kafka*`, `libs/config`, `libs/testkit` and `app` classpaths) | A8 already does exactly this for the gateway and is the precedent; ADR-041's argument — that nobody sets out to break layering, they add one edge for one type — applies identically to a Kafka client | CFGOP-003; §5.2 |
| D4 | The dashboard's `Unavailable` row must show a reason, but the roadmap does not say whether an unreachable *managed* cluster is the cluster service being unavailable or a section of a healthy response | **A cluster the service cannot reach is a `Section` in a healthy response, not an unavailable capability.** The `cluster` capability is `Unavailable` only when the cluster *service* is down. An unreachable managed cluster is a `Section.Unavailable(reason)` inside a 200 | ADR-039 §6: only transport failures *of the upstream service* feed the registry. A user typing a bad broker address into configuration must not dim the sidebar for everyone else, which is precisely the failure mode that rule exists to prevent | CLAPI-007, CLAPI-008, CLUI-003 |
| D5 | Where is the boundary between a "cluster stat" the dashboard shows (CL-003) and the topic sweep that belongs to M2? | The dashboard shows **only** what `describeCluster`, the broker set and `describeLogDirs` already produce: broker count, controller, version and total disk usage. **Corrected at the M1 gate review:** online/offline *partition* counts are **not** derivable from those three calls either — `research/kafka/admin-capabilities.md` §1 "Cluster stats" records that the reference product aggregates `describeTopics` + `describeLogDirs` + `listOffsets` to get them. Partition counts, topic counts and per-broker *leader* counts are therefore `Option`, always `None` in M1, and render `—`. Per-broker *replica* counts and the skew percentage **are** derivable from `describeLogDirs` replica entries and do ship (BR-001) | Kafbat's dashboard reads these from a periodic full scrape (`research/kafka/admin-capabilities.md` §1, "Cluster stats"), which is a topic-service concern; putting that sweep in the cluster service would make the one Core service the slowest one | CLDOM-005, CLUI-003; §3 non-goals |
| D6 | The exit criteria require concurrent writers, read-your-writes and version conflicts, but the cluster CRUD **screen** is explicitly M8. Something must write | One write endpoint ships with no UI: `PUT /internal/v1/clusters/{id}`, requiring `ApplicationConfig.Edit` (which nothing grants while auth is disabled, so it is reachable only by an internal caller and by tests). It reports `NotConfigured` when the file adapter is in use. It is the surface the M8 wizard will call, built once | The exit criteria cannot be demonstrated without a writer, and inventing a test-only write path would test something the product does not ship | CLAPI-009 |
| D7 | ADR-042 names three topics; M1 has no audit feature | M1 creates and validates `__kui_config` and `__kui_files` only. `__kui_audit` is created by the milestone that first writes a record (M5). `docs/operations/metadata-store.md` documents all three, and says which of them KUI creates today | Creating a retention-based topic that nothing produces to would leave an operator wondering why it is empty, and would fix its retention settings before the feature that needs them exists | STORE-005, CFGOP-008 |
| D8 | The roadmap lists ADR-014 (Schema Registry client strategy) among M1's ADRs, but nothing in M1's scope speaks to a registry | ADR-014 stays Accepted and unimplemented. Its first code arrives in M3 with the serdes | The ADR's own content is about the serde wire format; it appears in M1's list because M1 settles the *typed auth* model (ADR-022) that the registry client will reuse. Reading the list as an implementation obligation would build a client with no caller | §3 non-goals |
| D9 | "Response time is bounded by the per-service timeout" names no number | The bound is the existing per-upstream timeout already configured in `libs/http` (ADR-037); M1 adds no new timeout knob for it. The assertion is expressed against that configured value rather than a literal, so a later change to the default cannot silently invalidate the test | ADR-037 already owns per-upstream timeout, bulkhead and breaker; a second timeout for the same call would be two policies for one failure | CLAPI-007 |
| D10 | Kafbat polls brokers every 5 seconds (`research/kafbat/ui-analysis.md`); `ARCHITECTURE.md` §9 says the cluster snapshot refreshes every 30 s | **30 seconds server-side, and the browser does not poll at all**: it reads the snapshot and shows `scrapedAt`. The forced-refresh button (CL-005) is the user's control | Kafbat's 5-second poll multiplied by every open tab is load on the broker that no user asked for, and its broker-detail page shows a full-page loader on every refetch — a defect `research/kafbat/ui-analysis.md` records verbatim. A visible `scrapedAt` plus an explicit refresh is the staleness contract ADR-016 requires anyway | CLDOM-005, CLUI-004, CLUI-008 |

**Standing rule, restated from the M0 plan.** A blocker owned outside the execution loop is not a
reason to stop: propose the decision from the evidence available, take it, record it in the
artifact that owns it, and leave a cheap reconciliation path if the external input ever arrives.
In M1 that rule applies to R-10, the manual acceptance against a real external cluster: it is
prepared, it is documented, and no task waits on it.
