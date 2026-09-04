# KUI backlog

**Written 2026-09-04, from the nine-area audit that re-set every state in `docs/FEATURE_MATRIX.md`.**
This file is the other half of that correction: the matrix says what is true today, and this file
says what is left, in the order it should be built.

Read `docs/FEATURE_MATRIX.md` first for per-capability evidence. This file does not repeat it.

## How to read this file

- **Size** uses the matrix's scale for one engineer including tests: `S` (< 2 days), `M` (2–5 days),
  `L` (1–2 weeks), `XL` (> 2 weeks). Wave totals below are the sum of the items in that wave, in
  engineer-weeks, and assume the shared edges in Wave 0 already exist.
- **What exists** matters more here than anywhere else. This project's characteristic failure is
  code that compiles, passes its suites and is wired into nothing: the CEL filter engine, the
  masking engine, the event-tracking query model, the KRaft quorum, the JSON flattener, the serde
  resolution rules and the cluster connectivity probe are all in that state right now. Those items
  are costed as **wiring**, not as features, and they are cheap. Anyone who plans them as new work
  will pay twice.
- **Waves** are units of parallelism, not of release. Items inside a wave can be built
  simultaneously by separate teams *once Wave 0's shared edges have landed*. Items in a later wave
  may not start before their dependencies.

## The scale of what is left, stated plainly

188 capability rows; 11 are deferred or rejected; **53 of the remaining 177 are delivered (30%)**.
124 capabilities remain. Full parity with the union of three reference products
(Kafbat, Provectus, Kouncil) is roughly **90–120 engineer-weeks** of work from here, and that
estimate carries the usual risk on the two areas nobody has touched at all (RBAC and metrics).

That number is not the useful one. The useful one is this:

> **A team could adopt KUI after Waves 0–3: about 34–44 engineer-weeks.** At that point it has a
> login, roles, Avro/Protobuf/JSON-Schema decoding, working tailing, and no control on screen that
> fails only at the server. Everything after that is parity, not adoptability.

Waves 4–6 — metrics, ACLs, quotas, Connect, ksqlDB, exports, analysis — are what turns an adoptable
product into a replacement for the references. They are real work and they are not on the critical
path to someone else using this.

---

## Wave 0 — Shared edges (build these first, once, by one team)

This project has twice had three teams independently build the same shared component. Wave 0 exists
so that cannot happen a third time. **Nothing in Waves 1–6 should start until the edge it needs is
named, owned and merged.** Every item here is small; the cost of skipping them is not.

| # | Shared edge | Why it is shared | What exists | What remains | Size |
| --- | --- | --- | --- | --- | --- |
| E1 | **`Rbac` vocabulary in `libs/security-core`** — `Resource`, `Action`, `implies` closure, resource-pattern matching, `decide`, `visible` | Every service's guard, the gateway's `/auth/me`, the browser's gating and the audit records all need the *same* enum. Three teams will otherwise invent three. ADR-021 already specifies it. | ADR-021 is Accepted and names the exact API. `libs/security-core` has `Principal`, `PrincipalClaims`, the audit port and the masking engine — and no `Resource`, no `Action`, no evaluator. | The whole file, plus the ScalaCheck laws ADR-021 lists. | M |
| E2 | **A single `AuditSink` port and a single principal source** | Three services already write audit records and two of them hard-code *different* placeholder principals; the consumer service has forked its own copy of the port in its own domain. That makes the trail unanswerable, which is the one thing an audit trail must not be. | `libs/security-core/audit/AuditSink.scala` with 7 `MutationKind`s; three `LoggingAuditSink` adapters. | Delete the consumer service's duplicate port; thread the verified `Principal` from `PrincipalVerification` into every `MutationGuard`; agree one placeholder string until E4 lands. | S |
| E3 ✅ | **`SerdeFactory` for Schema Registry, in a real `libs/serde-confluent`** — *delivered 2026-09-04.* | The registry client, its caches and its credential config are needed identically by browse, produce, resend, tracking and the future SR service. `build.mill` and `ARCHITECTURE.md` already describe this module as existing; it does not. | Built: `WireFormat`, `SchemaRegistry` (own REST client over `UpstreamClient`, basic/bearer auth, failover), `CachingSchemaRegistry` (schemas by immutable id, subjects on a TTL), `AvroPayload`, `JsonSchemaPayload`, `SchemaRegistrySerde` and `SchemaRegistrySerdeFactory`, with 40 tests. | Remaining: Protobuf decoding (licence decision, see ADR-014 Amendment 1) and SD-003's config slice, which is what makes the factory reachable from a cluster. | L |
| E4 | **Permission plumbing to the browser** — a `permissions` field on `AuthMeResponse`, a kernel permission store, and `ActionPermissionWrapper` actually used | Every write control in four microfrontends must gate the same way. Today `ActionPermissionWrapper` exists and is called from exactly one site, which passes no `permitted`. | The component; `GET /auth/me`; `roles: []` always empty. | The DTO field, the kernel store, and one worked example (topic delete) that the other teams copy. | M |
| E5 | **`Main` + client module + image for `topic`, `message` and `consumer`** | Without these three the product is not the distributed system the README describes, the fault-isolation e2e suite can only ever cover one of four services, and `deployment/compose` cannot demonstrate the product. Every operations and testing item downstream depends on them. | Only `gateway` and `cluster` have a `Main`; only `cluster` has an HTTP client module. All three services' wiring exists and runs inside the all-in-one. | **DONE 2026-09-04.** `kui.http.ServiceMain` is the shared process shell; `topic`, `message` and `consumer` each have a `Main`, a `KuiImage` and a place in the five-container compose topology; `smoke.sh` proves per-service fault isolation against real containers. No client module was needed: the gateway derives every upstream call from the contract each service publishes. The cluster service's `Main` was rewritten onto the same shell, and its `PrincipalCodecs` / `AppLoggerFactory` moved to `libs/http` as `ProcessPrincipalCodec` / `ProcessLoggerFactory` | **done** |
| E6 | **`LogDirDto` carries per-topic-partition entries** (TD-017) | Four screens are waiting on the same field: topic detail size, the partition table's size column, the broker log-dir tab and PA-003. | Directory-level log dirs work end to end. | **Contract half DONE 2026-09-04.** `LogDirDto.replicas` carries topic, partition, size, offset lag and the future-replica flag, sorted biggest-first by the cluster service, and decodes as empty when absent. The four screen consumers and `VirtualizedTable` (TD-018) remain | **S** (screens only) |
| E7 | **CI actually runs the test suites** | Every wave below adds tests that will not be run. This is the cheapest item in the document and it protects all the others. | A well-built workflow whose `test` job runs three commands over two of 47 test source trees. | **DONE 2026-09-04.** The finding was worse than the audit's: `./mill a.test b.test` passes `b.test` to `a.test` as a *test-name filter*, so the JVM step ran **zero** cases while reporting green. `./scripts/run-tests.sh` now resolves every test module from Mill, runs them all with selector syntax in one invocation, and counts `<testcase>` elements: **4140 cases across 57 modules, all passing**. A `generated` job runs `__.openApiCheck` and `docs.errorCodes --check`. `smoke.sh` is fixed and runs in CI. Blocker B-003 was the same argument confusion and is closed | **done** |

**Wave 0 total: ~5 engineer-weeks.** E1, E3, E5 and E7 can proceed in parallel; E2 follows E1; E4 follows E1.

**Landed 2026-09-04: E5, E6 (contract half) and E7.**

---

## Wave 1 — Adoptability: authentication and authorization

*Claimed by M6. This is the single reason no team can use KUI today.* An operator cannot put a tool
that can delete topics and purge records in front of colleagues when it has no login, no roles and
no attributable audit trail. Everything in this wave is absent, not partial.

| ID | Item | Why a user cares | Claimed | What exists | What remains | Size | Depends on |
| --- | --- | --- | --- | --- | --- | --- | --- |
| AU-001 | Auth types: `disabled`, login form, OAuth2/OIDC (GitHub, GitLab, Google, Cognito, Azure Entra, generic), LDAP/AD | Without it the product cannot leave one person's laptop. | M6 | `authType` is parsed and every value but `disabled` is refused; the parsed value is then discarded. Sessions, CSRF and the signed gateway→service principal are all built and working — the substrate is done. | An identity service, `OidcProviderPort`, `IdentityProviderPort`, the login screen, and the `authType` branch in the shell. No OIDC, LDAP or bcrypt dependency is in the build yet. | XL | E1 |
| AU-002 | In-memory users, default accounts, forced password change | The smallest credible deployment: a shared instance with three named people. | M6 | Nothing. | User store, bcrypt, the change-password flow. | M | AU-001 |
| AU-003 | SSO provider list; GitHub org/team as a role source | Enterprises will not hand-maintain a user list. | M6 | Nothing. | Provider registry and group resolvers. | M | AU-001, RB-002 |
| RB-001 | Role model: subjects × clusters × resource pattern × actions, with action dependencies | Decides who may read and who may delete. | M6 | ADR-021, Accepted and unimplemented. | E1's evaluator, plus per-service enforcement. | L | E1 |
| RB-002 | Authority extractors (GitHub orgs/teams, Google `hd`, Cognito groups, OIDC claim, LDAP, AD) | Roles must come from where the company already keeps them. | M6 | Nothing. | One extractor per provider. | L | AU-001, RB-001 |
| RB-003 | `/auth/me` permissions and UI gating | A button that is visible and then refused by the server is worse than a hidden one. This is the visible half of read-only mode too. | M6 | The endpoint; `ActionPermissionWrapper` with one no-op call site. | E4, then wrap **every** write control: topic create/alter/delete, produce, resend, purge, offset reset, group delete. | M | E4, RB-001 |
| RB-004 | UI-managed groups and a function-permission matrix, persisted | Lets an admin change access without a redeploy. | M6 | The store *key* is reserved (`StoreSection.Rbac`) and `kui.rbac.**` is accepted-and-ignored in config. Nothing reads or writes either. | The screens, and `ui-admin` to host them. | L | RB-001, CL-006 |
| AU-005 | User menu: logout, theme, timezone | There is currently no way to log out, because there is nothing to log out of — and no way to set a timezone at all. | M1, **wrongly marked COMPLETE** | A three-state theme toggle button. `POST /auth/logout` works and nothing calls it. | The menu, the logout item, the timezone selector; folds `KU-012`'s settings page. | S | AU-001 |
| AD-001 | Audit sink completion: `__kui_audit` topic, `ALL`/`ALTER_ONLY`, real principal | An audit line that says `system (authentication is not enabled)` cannot answer "who deleted it". | M5 | The console sink is wired in all three services and was observed writing a real record. | E2's principal threading, the Kafka sink, the level knob. | M | E2, AU-001 |
| AD-002, KU-020 | Audit topic self-protection and the audit log viewer | Reading the audit trail must itself be a permission, and someone must be able to read it. | M6 | Nothing. | Both, in `ui-admin`. | M | AD-001, RB-001 |
| DM-001 | Config-driven masking wired in | Anyone browsing a production topic will see PII. The engine to prevent that already exists and is called by nothing. | M3 | **`MaskingEngine` and `MaskingRule` are built and unit-tested with no caller in any service, no configuration that can define a rule, and no `docs/operations/masking.md`.** | A config slice, a call site in the message read path, the operator doc. This is small and high value. | S | — |
| DM-002 | UI-managed masking policies bound to groups | Lets a data-protection owner change masking without a deploy. | M6 | Nothing. | Screens in `ui-admin`. | M | DM-001, RB-004 |
| KU-021 | Bearer-token API access for non-browser clients | Scripts and CI need a way in that is not a cookie. | M6 | Nothing. | JWKS or introspection at the edge. | M | AU-001 |
| KU-018 | Shared session store | Two replicas today mean random logouts. | M6 | `InMemorySessionStore` only (TD-003); `rotate` exists and is called by nothing. | A Kafka-backed adapter over the existing store, plus calling `rotate` at login (session fixation). | M | AU-001 |
| NX-001 | Server push: forced logout on permission change | A revoked role must take effect without waiting for a session to expire. | M6 | The SSE machinery it needs is built and proven. | The events and the client handling. | S | AU-001, RB-001 |

**Wave 1 total: ~18–22 engineer-weeks.** AU-001 is the long pole and everything else in the wave
queues behind it or behind E1; plan two teams, not six.

---

## Wave 2 — Schema-aware data: Schema Registry

*Claimed by M7. It belongs here because most production Kafka traffic is Avro or Protobuf, and KUI
currently renders it as Base64.* A Kafka interface that cannot decode the payloads a company
actually produces is a demo, whatever else it does.

| ID | Item | Why a user cares | Claimed | What exists | What remains | Size | Depends on |
| --- | --- | --- | --- | --- | --- | --- | --- |
| SD-001 | Avro, JSON Schema and Protobuf serdes | Reading real traffic. | M3 | The nine registry-free serdes and magic-byte autodetection work live. **There is no `libs/serde-confluent`**, though the build file and the architecture document both describe it. | E3. Also fix the live defect where the picker offers `SchemaRegistry` and silently returns `Fallback` instead of `KUI-SERDE-UNAVAILABLE`. | L | E3 |
| SD-003 | Default key/value serde per cluster and resolution order | Operators must be able to say "this cluster is Avro" once. | M3 | `SerdeResolution.Rules` and its suite exist; every cluster is built `SerdeProfile.unconfigured` with no factories, and `libs/config` has **no serde slice at all**. | The config slice and its plumbing. Pure wiring. | S | E3 |
| MS-009 | Per-topic serde suggestions | Picks the right decoder without the user guessing. | M3 | `ClusterSerdes.suggest` and `SerdeSuggestionDto` are implemented; no endpoint exposes them. | Endpoint, gateway entry, picker. Pure wiring. | S | SD-003 |
| SR-001…SR-005 | Subjects list, create version, get by version, delete (soft/hard), compatibility get/set/check | The registry is a thing operators administer, not only read. | M7 | Nothing. The mutation machinery it needs (plan tokens ADR-045, read-only markers ADR-047) exists and works. | A `schema` service and a `ui-schemas` microfrontend. | XL | E3 |
| SR-006 | Version diff viewer | The single most-used registry screen: what changed between v3 and v4. | M7 | Nothing; the kernel has no `DiffViewer`. | Kernel component plus screen. | M | SR-001 |
| SR-007 | SR authentication (basic, OAuth client credentials, SSL stores) | Nobody runs an open registry. | M7 | `UpstreamClient`, `RetryPolicy` and `Failover` are built and tested, and `Failover`'s own comment names the registry as its motivating case. | The credential config and its wiring. | M | E3 |
| SR-008, SR-009 | Topic → subject convention; latest key/value schema on the topic page | Answers "what shape is this topic" without leaving it. | M7 | **The slot is fully built**: the `schemas` section of the topic overview exists, is fetched, and correctly reports `not_configured` because no source is registered. | Register a schema `SectionSource`. Everything else on the row is done. | S | SR-003 |
| SD-004 | Schema → JSON Schema for produce-form validation | Stops a malformed produce before it reaches the topic. | M3 | A doc comment naming the idea. | The conversion and the form validation. | M | SR-003 |
| CL-008 | Failover across multiple SR/Connect/ksql URLs | Registry HA. | M7 | `libs/http/upstream/Failover.scala`, built and tested. | Configuration and use. | S | E3 |
| SD-002 | Custom serde plugin jars | The escape hatch for in-house formats. | M7, deferred | The SPI shape; ADR-028. | Isolated classloader and jar loading. Keep deferred until someone asks. | L | E3 |
| KU-023 | Extended serdes (ProtobufFile/Raw, AvroEmbedded, MessagePack, MirrorMaker2, `__consumer_offsets`) | Decoding the internal topics operators are sent to inspect. | M5 | Nothing. | One serde each; independently parallelisable. | M | E3 |

**Wave 2 total: ~12–16 engineer-weeks**, of which the first ~3 (E3 + SD-001 + SD-003 + MS-009 +
SR-008/009) already deliver the user-visible win: real payloads decode, everywhere.

---

## Wave 3 — Finish what is already built

*Every item here is code that exists and cannot be reached, or a control that is missing from a
screen whose server side answers.* This wave is the cheapest user-visible value in the document, and
it is where the audit's findings pay for themselves. Nothing in it is blocked on Waves 1 or 2.

| ID | Item | Why a user cares | Claimed | What exists | What remains | Size |
| --- | --- | --- | --- | --- | --- | --- |
| MS-005 | **Live tailing** | It is on screen, it is checked, and it does nothing — the worst kind of defect. Watching a topic live is a top-three reason people open a Kafka UI. | M3, **wrongly marked COMPLETE** | `BrowseRequest.live` is validated and parsed; no code consumes it. A record produced into an open stream never arrives. | `TailUseCase` (MSG-020, never written), plus the client throttle and play/pause. | M |
| CG-003, CG-005 | Delete a consumer group; delete its committed offsets for a topic | Both routes answer through the public API today and there is no way to invoke them from the product. | M4 | Endpoints, use cases, `MutationGuard`, the not-empty refusal — all verified live. `frontend/ui-consumers` has no client and no control. | A typed client, a row/detail menu item, confirmation, optimistic removal with rollback, a toast. | S |
| CG-002, CG-006 | Show consumer lag pace and its trend sparkline | "Is it catching up?" is the question the group list is opened to answer, and KUI computes the answer and throws it away. | M4 | `LagMath.pace` is computed and served live; the poller is mounted and working. No pace column, no `LagTrend`, no `LagSparkline`. | The column, the two components, the missing-sample rules; add pace to the detail DTO. | S |
| CL-012 | KRaft quorum panel | Whether the controller quorum is healthy is a first-class operational question, and KUI already asks the broker every 30 seconds. | M1 | `QuorumInfo` — modelled, validated, adapted, capability-probed and stored on `ClusterTopology` every 30 s. Read by no contract, route or screen. | A DTO, a route, a panel. Pure wiring. | S |
| MS-004 | JSON flattening grid | Kouncil's defining feature: JSON payloads as columns you can scan. | M3 | `JsonFlattener`, `FlatPath` and `FlattenLimits`, property-tested, whose only caller is their own test. | A grid that consumes `JsonFlattener.columns`, column selection, depth and row caps. | L |
| MS-007 | CEL smart filters | Server-side filtering is the difference between finding one record and scrolling a million. | M3 | `CelFilterEngine`, `CelEnvironment`, `MessagePredicate`, `FilterMetrics`, the DTOs and the `FilterSource` port — all built and tested. **No module even depends on `libs.filter`**; the API always passes `filter = None`. | A `FilterSource` adapter, register/test/list endpoints, gateway entries, the editor UI. | L |
| ET-001…ET-003 | Event tracking across topics | Following one order id across six topics is the question a support engineer actually has. | M3 | `TrackQuery` and `TrackDtos`, complete and tested, referenced only by their own test. | Use case, sync endpoint, the async SSE variant (the streaming machinery is proven), the filter UI and results grid. | L |
| CL-006 | Cluster CRUD from the UI, with a connection test | Adding a cluster should not require editing YAML and restarting. | M8 | The write path is built and **deliberately unreachable** (`PUT /internal/v1/clusters/{id}` with mandatory `If-Match`, excluded from the gateway's contract map). `ConnectivityProbeAdapter` is constructed nowhere. There is no delete endpoint and no `ui-admin`. | A public route and permission, a delete endpoint, the probe wiring, and the `ui-admin` microfrontend (shared with RB-004 and DM-002). | L |
| CW-002…CW-005 | Config read/apply with hot reload, validation probes, file upload, the cluster wizard | The same screens as CL-006, generalised. | M8 | The encrypted Kafka-backed store, versioning and read-your-writes all work. | The endpoints and the wizard. | L |
| CL-001 | Detect the Kafka version | Every cluster in every deployment shows a blank Version column. | M1 | `MetadataVersions.table` stops at feature level 25 (Kafka 4.0); the project's own pinned broker is 4.3.1, and the documented `inter.broker.protocol.version` fallback is not reported by it at all. | Extend the table and add a second fallback. | S |
| BR-001 | Broker leader counts and leader skew | Two of the four columns the row promises are permanently `—`. | M1 | Hard-coded `None` in `BrokerViews`: leadership needs a topic sweep the cluster service does not do. | The sweep, or a documented removal of the columns. | M |
| E6 / BR-005, PA-003, TP-003 | Per-topic-partition log-dir sizes | "Which topic is filling this disk" has no answer today, and four screens show `—`. | M1 | Directory-level figures work. | E6, then the four consumers and `VirtualizedTable`'s first caller. | M |
| BR-002 | Render broker config synonyms | The data is already on the wire (TD-019). | M1 | Carried, not rendered. | A disclosure row. | S |
| MS-010, MS-012, MS-013, MS-014 | Message detail polish: copy to clipboard, pre-masking raw view, decoded Spring DLT/retry headers, poll throttle, relative timestamps | Small things, all of them noticed daily. | M3 | `HeaderDecoding` is built with no reference outside `libs/serde`; `PollBudget.throttleBytesPerSecond` is never set or read; `Timestamps.relative` exists and the message table does not call it. | Wiring, mostly. | M |
| MP-002 | Produce placeholders `{{count}}` / `{{uuid}}` / `{{timestamp}}` | Generating a hundred distinct test records is the reason bulk send exists. | M3 | Server-side `count` works; the placeholders are specified as a browser feature and are not implemented there. | Three substitutions in `ProduceDraft`. | S |
| MP-003 | Resend header filtering | Copying only the failed records out of a DLQ. | M3 | Resend works end to end; `ResendRequestDto` has no filter field. | The filter, contract to screen. | S |
| SF-003 | Use `VirtualizedTable` | A 5 000-topic list currently renders 5 000 rows. | M2 | The component ships with its own suite and **has no caller** (TD-018). | Adopt it on the topic list, the partition table and the message table. | S |
| RB-005 | Pre-disable write controls on a read-only cluster | The refusal is correct and arrives too late to be kind. | M5 | Three backend guards, verified. No shipped deployment even sets `readOnly`, so it cannot be demonstrated. | E4's gating applied to read-only, and a `readOnly` cluster in the demo. | S |

**Wave 3 total: ~11–14 engineer-weeks**, and it is the wave with the best value per week in the
whole document. Two teams can run it: one on the message service (tailing, filters, tracking, the
grid), one on clusters and consumers (quorum, pace, group controls, log dirs, `ui-admin`).

---

## Wave 4 — Operate it: metrics, ACLs, quotas

*Claimed by M7 and M8. Needed to run KUI as the primary tool, not to adopt it.*

| ID | Item | Why a user cares | What exists | What remains | Size |
| --- | --- | --- | --- | --- | --- |
| MT-001…MT-003 | JMX and Prometheus scraping per broker; inferred metrics without JMX | Every throughput column in the product is blank today. | Nothing. There is no `metrics` service and no JMX dependency anywhere. | A `metrics` service, the per-cluster `metrics` config block, both scrapers. | XL |
| MT-004 | Graph descriptions and a templated-PromQL query proxy | Charts on the cluster and broker pages. | Nothing; no charting facade. | The proxy plus a `ui-metrics` microfrontend and a chart facade. | L |
| MT-005 | Prometheus exposition `/metrics`, `/metrics/clusters/{id}` | Lets the operator monitor KUI and its clusters from their own stack. | Nothing serves `/metrics`. | The route and its edge policy (no session auth, allow-listed). | M |
| MT-007 | Finish KUI self-metrics | The observability document already claims things that are not true. | 16 of 26 declared metric names have an emitter. **No JVM runtime instrumentation**, and neither runnable deployment sets `prometheusPort`, so nothing has ever scraped it. | The 10 missing emitters (notably `kui.upstream.circuit.state`, which is logged and never metered), the JVM instrumentation dependency, and a scrape in the demo. | M |
| CL-004, BR-004 | Cluster and broker metrics tabs | The `—` cells. | The `FeaturePanel` slot machinery is built. | Screens over MT-001. | M |
| AC-001…AC-004 | ACL list with filters, create/delete, CSV export and declarative sync, convenience creators | Managing who may read a topic is a daily operator task in any secured cluster. | Nothing — no `security` service. **The capability probe is built, tested, and then discarded**: `KafkaToDomain` maps `AclManagement`, `AclEdit` and `ClientQuotas` to `None`, so no client can tell whether a cluster supports ACLs. | A `security` service, a `ui-security` microfrontend, the domain enum members and the two mapping cases, and the topic page's `acls` section source. | XL |
| QU-001, QU-002 | Client quota list and upsert/delete | Throttling a misbehaving client. | Nothing; the same discarded feature flag. | Part of the `security` service. | L |
| TP-016 | Topic → ACLs tab | Answers "who can read this topic" on the topic page. | The slot exists and reports `not_configured` correctly. | Register the section source. | S |

**Wave 4 total: ~18–24 engineer-weeks.** The `metrics` and `security` services are independent of
each other and of everything in Waves 5 and 6; two teams, cleanly separated.

---

## Wave 5 — Integrations: Kafka Connect and ksqlDB

*Claimed by M7. Nothing exists — not a service, not a microfrontend, not a config key, and not a
container in either deployment to point at.* The `FeatureId` enum's own comment records the gap.

| ID | Item | Why a user cares | What exists | What remains | Size |
| --- | --- | --- | --- | --- | --- |
| KC-001…KC-008 | Connect clusters, connectors across them, create/get/delete, config editor, state actions, tasks with traces, offset reset, plugins with validation | For teams that use Connect, this *is* the daily screen. | The reusable edges: `ConnectName` is a validated opaque type with a published codec and Tapir path codec; `RetryPolicy` has an explicit hook whose comment reserves Connect's 409 rebalance as its first entry; `BoundedCache` exists for the client cache. | A `connect` service, a `ui-connect` microfrontend, the `KUI-CONNECT-REBALANCING` error code, and a Connect worker in the demo. | XL |
| KC-009, TP-014 | Connector topics tab; topic → connectors tab | Both directions of "what is writing to this topic". | The topic-overview `connectors` slot is built and correctly reports `not_configured`. | Register the section source. | S |
| KC-010 | Connect CSV export and client caching | Bulk review. | `libs/cache`. | Both. | M |
| KS-001…KS-004 | Execute a statement, stream results by query id, list tables and streams, the SQL editor, auth and SSL | Ad-hoc querying without leaving the tool. | The two-step SSE design is already proven in the message browse stream, gateway relay included — cancellation and heartbeats and all. | A `ksql` service, a `ui-ksql` microfrontend, a CodeMirror facade, and a ksqlDB server in the demo. | XL |

**Wave 5 total: ~16–22 engineer-weeks.** Connect and ksqlDB are fully independent; two teams.

Note for whoever picks this up: the M7 exit criterion "the capability machinery reports SR, Connect
and ksqlDB as `not configured`" is **unverified, not satisfied**. The machinery is real and tested,
but it is driven by a *service report*, and a service that is absent from configuration entirely is
never polled and never appears. The browser receives silence, not `not_configured`. The observable
behaviour is the same; the mechanism has never been exercised for these three.

---

## Wave 6 — Parity tail and scale

Everything the references have that adoption does not require.

| ID | Item | Size | Note |
| --- | --- | --- | --- |
| TP-012, PA-002 | Topic analysis: full scan with counts, sizes, percentiles, HLL uniques, hourly histogram | L | No `Analysis` type, no HLL, no Statistics tab. Allowed in read-only mode. |
| TP-008, TP-009, TP-011 | Recreate, clone, change replication factor | L | Nothing exists. Note that no run has ever used more than one broker, so even the create-time RF path is untested above rf=1. |
| TP-013 | Active producers per partition | S | `describeProducers` appears nowhere in the tree. |
| TP-018 | Batch actions on the topic list | M | Partial failure must be reported per topic. |
| MS-003 | Kouncil-style table browsing: per-partition, newest-first offset paging | L | `PageRequest` is built in the message domain and referenced by nothing outside itself. The second read path — use case, endpoint, gateway entry, screen. |
| MS-011, BR-007, TP-017, CG-007, KC-010, KU-030 | CSV export everywhere, and server-side column projection | M | Content negotiation, not a `/csv` path. Note that the published-page sandbox blocks page-initiated downloads, so this must be a server-rendered response. |
| CG-009 | `__consumer_offsets` decoding serde | M | Folds into KU-023. |
| TP-002, CG-008, SF-002 | Lucene full-text search across topics, groups, schemas, connectors, ACLs | L | Deferred (DR-10). Trigram search already ships. Its exit condition names `docs/benchmarks/`, **which does not exist**, so this deferral can currently be neither discharged nor refuted (TD-008). |
| KU-025 | Helm chart, runbooks, production deployment docs | L | **No runbook exists for any service's `Unavailable` state**, which is an M8 exit criterion. |
| KU-026 | Kafbat environment-variable migration tool | S | The cheapest adoption aid in the document, for anyone already running Kafbat. |
| KU-027 | Performance budgets, load tests, recorded benchmarks | M | `docs/benchmarks/` does not exist; TD-008 depends on it. |
| KU-028 | Dependency scanning, SBOM, release process | M | The build passes `--sbom=false` today. |
| KU-032 | Alerting on lag, offline partitions and capability transitions | L | |
| MC-001 | MCP server derived from the Tapir endpoints | M | Cheap because the endpoints are already typed and complete. |
| KU-031 | Plugin SDK for third-party microfrontends | XL | |
| NX-003, NX-005, NX-006, OT-002, OT-006 | Release banner, base path, CORS, CSV knobs, release check | S each | Small, unblocked, good first tasks. |

**Wave 6 total: ~20–26 engineer-weeks.**

---

## Engineering debt that gates the waves above

Not capabilities, and not optional. Each of these makes the work above slower or unsafe.

| # | Item | Why it gates | Size |
| --- | --- | --- | --- |
| D1 | ~~**CI runs 2 of 47 test source trees**~~ — **fixed 2026-09-04 by E7.** The true figure was worse: the JVM step ran zero cases. Every module now runs; 4140 cases | Every wave adds suites nothing will run. | **done** |
| D2 | **No live-broker test for `topic`, `message` or `consumer`** | All three services' adapters are asserted against fakes. `PortContractSuite` says of itself that the live adapter runs it; **only the fake does**, and three of its cases `assume(false)` on every run. Waves 2 and 3 change these adapters heavily. | M |
| D3 | **Three promised test fixtures were never built**: `TopicSeed`/`TopicFixtures` (TOP-007), `SeededTopics`/`TopicShapes`/`SchemaRegistryContainer` (MSG-042), `ConsumerGroupFixture`/`GroupSeeder` (GRP-036) | GRP-036 was flagged "start this early, two lanes stop without it"; it was skipped and both lanes shipped against fakes. Wave 2 needs the `SchemaRegistryContainer` specifically. | M |
| D4 | **Architecture rules A12, A13 and A14 were allocated by an Accepted ADR and never written** | A13 and A14 are live conditions *now* (`dev.cel` on `libs/filter`; three wire vocabularies). The gate reports "10 rules" honestly, and the three missing ones are recorded as owed nowhere. | S |
| D5 | **M3 and M4 shipped 88 tasks with no Implementation Reports** — 0 of 48 and 0 of 40 specs have a Deviations section, against 38/38 in M2 | Every deviation this audit found in M0–M2 was discoverable *only* because someone wrote it down. Reinstate the requirement before Wave 1 starts. | S |
| D6 | **TD-016: `main.js` eagerly imports the clusters feature, and the e2e case that catches it is marked `.fail`** | A green build and a green suite both hide a live product defect. Four milestones have passed. | S |
| D7 | **Stale debt rows**: TD-007 is a decision, not a debt; TD-008's exit condition names a directory that has never existed; TD-013 describes facades that are not in the tree; TD-004's scrape count is out of date (four services now, not three) | A register nobody trusts stops being read. | S |
| D8 | ~~**`deployment/compose/smoke.sh` fails on a clean run**, and four shipped artifacts still document `GET /api/v1/ping`~~ — **fixed 2026-09-04 by E7.** The script asserts `/api/v1/clusters` instead, checks all four services, and runs in CI | It is the command the root README gives a new contributor. | **done** |
| D9 | ~~**The README's "one process or eleven" is false** (E5), and its "What CI runs" table is wrong in two rows~~ — **fixed 2026-09-04.** It is five processes and they all exist; the table now matches the workflow, including the `generated` and `e2e` jobs | Both are the first things a prospective contributor reads. | **done** |
| D10 | **`docs/operations/masking.md` is named by its own task spec and does not exist**; `observability.md` claims a circuit-state metric that is never recorded | Operator documentation that describes absent behaviour. | S |

---

## Recommended next build

**Wave 0's E7 and E1, then AU-001, then E3 — in that order, and then Wave 3 in parallel.**

The reasoning, plainly:

1. **E7 (CI) first, because it is two days and it protects everything after it.** There is no
   defensible reason to add 124 capabilities' worth of tests to a pipeline that runs two modules.
2. **Authentication before anything else that is new.** This is the difference between a tool one
   person runs and a tool a team uses. Every other feature in this document is worth more once
   somebody other than the author can safely open the product; none of them is worth much until
   then. AU-001 is the largest single item in the backlog and it should start now, because its
   long pole is real and everything in Wave 1 queues behind it.
3. **Schema Registry serdes immediately behind it**, because a Kafka interface that cannot decode
   Avro cannot read most production traffic — and because E3's seam is already designed, tested
   and waiting for an implementation.
4. **Run Wave 3 in parallel throughout**, with a second team. It is unblocked, it is cheap, and it
   removes the two things most likely to lose a trial user: a `Follow live` checkbox that does
   nothing, and controls that are missing from screens whose servers already answer.

What I would **not** build next, despite the roadmap: metrics (M8), Connect and ksqlDB (M7). They
are large, they are independent, and nobody abandons a Kafka UI for lacking them — people abandon
one for lacking a login and for showing them Base64 where their Avro should be.
