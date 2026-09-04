# KUI feature matrix

**Status:** living document, states corrected in full by the 2026-09-04 audit. Seeded 2026-09-03 from `research/kafbat/feature-matrix.md` (150 rows)
plus the screen inventory in `research/kafbat/ui-analysis.md` §IA.1 and the KUI-only rows the
research flagged. Updated by every task that changes a feature's state.

This is the product capability list. Every capability found in any reference project is here,
assigned to a milestone, or explicitly `DEFERRED(reason)` / `REJECTED(reason)`. Nothing is
dropped silently.

## How to read this file

- **ID** — stable identifier `AREA-NNN`. Numbers never change; a removed row keeps its ID with
  state `REJECTED`. Research rows keep their research number (`CL-1` became `CL-001`).
- **Source** — which reference has the behavior: `Kafbat`, `Provectus`, `Kouncil`, or `KUI-new`
  (no reference has it; KUI adds it because of the project's product properties or a research finding).
- **Priority** — `P0` core (must exist before parity can be claimed for that area), `P1` parity
  (needed to claim the union of the three references), `P2` valuable, `P3` marginal.
- **Owner** — the backend bounded context (short names: `gateway`, `cluster`,
  `topic`, `message`, `consumer`, `schema`, `connect`, `ksql`, `security`, `identity`,
  `metrics`, `config`). `—` means frontend-only.
- **MFE** — the microfrontend (`shell`, `kernel`, `ui-clusters`, `ui-topics`,
  `ui-messages`, `ui-consumers`, `ui-schemas`, `ui-connect`, `ui-ksql`, `ui-security`,
  `ui-metrics`, `ui-admin`). `—` means backend-only.
- **Milestone** — `M0`..`M9` from `docs/ROADMAP.md`. `—` only for rejected rows.
- **Cx** — implementation complexity, tests included: `S` (<2 d), `M` (2–5 d),
  `L` (1–2 w), `XL` (>2 w).
- **State** — the lifecycle a feature moves through:
  `NOT_RESEARCHED → RESEARCHING → DESIGNED → PLANNED → IMPLEMENTING → TESTING → REVIEW → COMPLETE`,
  plus `BLOCKED` and `DEFERRED(reason)` / `REJECTED(reason)`.
  A row becomes `DESIGNED` only when the ADRs and domain model it depends on are Accepted
  (during the architecture review). `RESEARCHING` in this file now means one thing only: **nothing is built.**
  Where a row has code that is unreachable, its state is `IMPLEMENTING` and its note says so.

A row reaches `COMPLETE` only when a person can do the thing from a browser against a running KUI.
Compiling, being unit-tested and being green is `IMPLEMENTING` or `TESTING`, not `COMPLETE`. That
distinction has now earned its keep three times, and the third time it cut in both directions.

The first two times, the same thing had happened: code that compiled, passed its suites and could
not be reached from a screen.

The third is the full audit of 2026-09-04, when the tree was read area by area and both running
deployments were driven by hand. **Every state in this file below was set by that audit**, and the notes
say what was checked and how. The audit found drift in both directions, which is why a stale matrix
is dangerous rather than merely untidy:

- **Rows that understated what is built.** Topic create, alter, delete and partition increase
  (`TP-005`…`TP-007`, `TP-010`), purge (`MS-008`), resend (`MP-003`), the topic → consumers tab
  (`TP-015`), CSRF (`AU-004`), read-only mode (`RB-005`), the audit sink (`AD-001`), the consumer
  lag poller (`CG-006`), fuzzy topic search (`TP-002`) and most of the M0 platform rows were all
  marked as not started or half done and are in fact working. A team reading this file would have
  rebuilt them.
- **Rows that overstated it.** Live tailing (`MS-005`) was `COMPLETE` and **is not implemented at
  all** — the server parses the flag and no code consumes it. The user menu (`AU-005`) was
  `COMPLETE` and is a theme button. Message CSV export (`MS-011`) was `TESTING` and does not exist.
  The fault-isolation e2e suite (`KU-033`) was `COMPLETE` and covers one of four services.
- **Rows whose note was simply wrong.** `CG-002` said consumer pace is never measured; it is
  measured and never *displayed*. `CG-006` said the list does not poll; it polls.

The recurring failure this project keeps repeating has a name in these notes: **code-only** — a
component that is built, tested, green and wired into nothing. `MS-007` (the CEL filter engine),
`DM-001` (the masking engine), `ET-001` (event tracking), `CL-012` (the KRaft quorum), `MS-004`
(the JSON flattener), `SD-003` (serde resolution) and `CL-006`'s connectivity probe are all in that
state today. They are marked `IMPLEMENTING`, never `COMPLETE`, because what they need is wiring
rather than a new implementation.

**Counts as of the 2026-09-04 audit** (188 capability rows): 53 `COMPLETE`, 12 `REVIEW`,
2 `TESTING`, 16 `IMPLEMENTING`, 93 `RESEARCHING` (not started), 1 `BLOCKED`, 7 `DEFERRED`,
4 `REJECTED`. Excluding the 11 deferred and rejected rows, **53 of 177 in-scope capabilities are
delivered — 30%.**

Behavior descriptions, edge cases and source citations are deliberately not repeated here; they
live in the research reports (`research/kafbat/feature-matrix.md` row of the same number,
`research/kafbat/ui-analysis.md`, `research/kouncil/ui-analysis.md`,
`research/kafbat/api-analysis.md`).

## Clusters (CL)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CL-001 | List clusters with status, counts, throughput, `lastError`, `readOnly` | Kafbat, Provectus, Kouncil | P0 | cluster (+gateway aggregation) | shell, ui-clusters | M1 | M | COMPLETE | Audited 2026-09-04. List, counts and `readOnly` verified live on four clusters. **Version fixed 2026-09-04** (`2d8d0ae`): the level table now runs to Kafka 4.4, a level newer than the table reports the highest known release as a lower bound ("4.4 or newer") rather than nothing, and `KafkaToDomain` no longer discards the resolved numbers to re-parse the broker's words (`level 30`, which cannot parse) — either fault alone emptied the cell. **Under-replicated** is still `None` by design in M1. The `DISK` column reported the host filesystem (468.8 GiB on the quickstart) until `147461d`; it now reports what Kafka's data occupies. How full the underlying disk is is not shown anywhere. |
| CL-002 | Per-cluster feature flags (SR, Connect, ksql, topic deletion, ACL view/edit, quotas, graphs) | Kafbat, Provectus | P0 | gateway (registry), fed by each service | kernel | M1 | M | COMPLETE | Audited 2026-09-04. The 'a dead cluster still reports `available`' defect is fixed. ACL, ACL-edit and client-quota flags are probed in `libs/kafka` and then dropped at `KafkaToDomain` (`AclManagement/AclEdit/ClientQuotas => None`), so those three flags never reach the wire — owed by AC-001/QU-001. |
| CL-003 | Cluster stats (dashboard numbers) served from a refreshed cache | Kafbat, Provectus | P0 | cluster | ui-clusters | M1 | M | COMPLETE | Stats stay servable while the cluster is momentarily unreachable. |
| CL-004 | Cluster-level aggregated metrics (JMX/Prometheus) | Kafbat, Provectus | P1 | metrics | ui-clusters | M8 | M | RESEARCHING | Dashboard cell shows `—` until M8. |
| CL-005 | Force statistics cache refresh | Kafbat, Provectus | P1 | cluster | ui-clusters | M1 | S | COMPLETE | Audited 2026-09-04. `POST /clusters/{id}/refresh`, 202. The button exists only on the brokers page; the dashboard has none. |
| CL-006 | Dynamic cluster CRUD from the UI, persisted, with connection test | Kouncil | P1 | cluster | ui-clusters | M8 | L | REVIEW | Delivered 2026-09-04. `ClusterWriteEndpoints` now carries `put`, `delete` and `probe`, all three marked as mutations with the CSRF header and an `ApplicationConfig.Edit` authorization declaration, and all three are in `ServiceContracts.byService` so the gateway derives public routes for them. `ClusterConfigStore.delete` and `ClusterWriteUseCase.delete` added (a cluster the configuration file also declares is refused by name, because the file would put it straight back). `ConnectivityProbeAdapter` is constructed in `ClusterBootstrap` behind `ClusterProbeUseCase`. The screen is `frontend/ui-clusters/.../admin` — a list with per-row edit and delete, a form, and a connection test whose verdict is cleared by any edit. **Remaining gap:** the form cannot upload a truststore or keystore, so a cluster behind a private certificate authority still needs the configuration file; the form says so.
| CL-007 | Typed cluster security config (SASL/SSL/SCRAM/IAM/OAUTHBEARER) with `properties` escape hatch | Kafbat, Provectus, Kouncil | P0 | cluster (typed config in `kui-config`) | ui-admin (form in M8) | M1 | M | COMPLETE | Decision D-7 / ADR-022. JAAS strings generated from typed fields, never accepted verbatim. |
| CL-008 | Failover across multiple SR / Connect / ksql URLs | Kafbat, Provectus | P2 | schema, connect, ksql (shared lib) | — | M7 | M | RESEARCHING | |
| CL-009 | Per-cluster colour tag and status dot in navigation | Kafbat | P2 | — | shell | M1 | S | COMPLETE | Audited 2026-09-04. The slug-vs-name defect is fixed: the capability entry carries the operator's name. |
| CL-010 | Favourites: pin topics and groups to the top of lists | Kouncil | P2 | — | kernel | M2 | S | COMPLETE | localStorage, keyed by cluster + name; the star column ships on the topic list. |
| CL-011 | Broker-address lookup helper endpoint (`/api/connection`) | Kouncil | P3 | cluster | — | — | S | REJECTED(folded into CL-001) | recorded decision DR-13. The first broker address is a field of the cluster DTO. |
| CL-012 | KRaft quorum panel: leader, epoch, high watermark, voters and observers with lag | KUI-new | P1 | cluster | ui-clusters | M1 | S | REVIEW | Delivered 2026-09-04. `QuorumDto`/`QuorumMemberDto` in `libs/contracts-core`, carried on `BrokersResponse` beside the broker section (same snapshot pass, so the high watermark and the offsets it is subtracted from are one moment). Every member's lag is computed server-side through `QuorumInfo.lagOf`, never by a client. `QuorumPanel` renders voters and observers separately, marks members that are behind, and states in words whether a strict majority is level with the leader — the sentence that says a metadata write may not be able to commit. A cluster with no quorum renders nothing at all. |

## Brokers (BR)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| BR-001 | List brokers with rack, leader counts, skew %, throughput | Kafbat, Provectus, Kouncil | P0 | cluster | ui-clusters | M1 | M | COMPLETE | Audited 2026-09-04. Broker, host, port, rack, disk and replica count render. `leaderCount`, `leaderSkewPercent`, `partitionCount` and `segmentCount` are hard-coded `None` (`BrokerViews`) and render `—`: leadership needs a topic sweep the cluster service does not do. Throughput needs M8. The `inSyncReplicaCount` → `replicaCount` rename (147461d) is source-only; no running image has it. The broker list's `DISK` column had the same defect and the same fix (`147461d`). |
| BR-002 | Broker configs with source, sensitivity, read-only flag, synonyms | Kafbat, Provectus, Kouncil | P0 | cluster | ui-clusters | M1 | S | COMPLETE | Audited 2026-09-04. 340 config entries with source, sensitivity, read-only and synonyms. Redaction is proved by `SecretLeakSuite`, never observed on a live broker (none reported a sensitive setting). Synonyms are carried and rendered nowhere (TD-019). |
| BR-003 | Update a single broker config (inline edit) | Kafbat, Provectus | P1 | cluster | ui-clusters | M5 | S | RESEARCHING | Audited; blocked in read-only mode (RB-005). |
| BR-004 | Per-broker metrics (JMX/Prometheus), Kouncil OS metrics | Kafbat, Provectus, Kouncil | P1 | metrics | ui-clusters (metrics tab via FeaturePanel) | M8 | M | RESEARCHING | Broker page is a partial aggregation: metrics tab falls back independently. |
| BR-005 | Log dirs per broker, per topic-partition size and lag | Kafbat, Provectus | P1 | cluster | ui-clusters | M1 | M | REVIEW | Directory-level figures ship: path, error, total and usable bytes, topic and partition counts. Per-topic-partition sizes do not — the wire carries no such field. `TECH_DEBT.md` TD-017 owes the contract field, TD-018 the virtualization. |
| BR-006 | Move a partition replica to another log dir | Kafbat, Provectus | P2 | cluster | ui-clusters | M5 | S | RESEARCHING | |
| BR-007 | Brokers CSV export | Kafbat | P2 | cluster | ui-clusters | M5 | S | RESEARCHING | Content negotiation (`Accept: text/csv`), not a `/csv` path. |

## Topics (TP)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| TP-001 | Topic list: paged, sorted, searched, internal-topic toggle | Kafbat, Provectus, Kouncil | P0 | topic | ui-topics | M2 | M | COMPLETE | Served end to end since the topic service was wired into the running process. Verified against the quickstart: eight seeded topics with counts, `__consumer_offsets` only with `showInternal=true`. |
| TP-002 | Full-text n-gram topic search (Lucene) | Kafbat | P2 | topic | ui-topics | M9 | L | DEFERRED(Lucene only; trigram n-gram search ships) | Audited 2026-09-04. **Row was misread as 'no fuzzy search'.** In-memory trigram search ships and is reachable: `NameIndex`, `mode=plain|fts` on the search box, `q=ordrs&mode=fts` finds `orders.v1` live. Only a Lucene index is deferred (DR-10, TD-008). No benchmark at 5k+ topics exists and `docs/benchmarks/` does not exist. |
| TP-003 | Topic details (partitions, replicas, ISR, segments, cleanup policy, throughput) | Kafbat, Provectus, Kouncil | P0 | topic | ui-topics | M2 | M | COMPLETE | Partitions, replicas, ISR and cleanup policy render. Segments and throughput are always `—`: both need `describeLogDirs`, a per-broker call over every partition, which is not worth a topic page (BR-005). Re-checked 2026-09-04 (closing pass): while the cluster is unreachable the partition table says so and the in-sync figure reads `—` rather than "0 of 0", which used to claim every replica had fallen behind. |
| TP-004 | Topic config list | Kafbat, Provectus | P0 | topic | ui-topics | M2 | S | COMPLETE | Settings tab, read live. Each entry's default is derived from the broker's own synonyms rather than from a table KUI keeps. Kafka's documentation strings contain HTML and are shown escaped, so `<a href="#compaction">` appears as literal text. |
| TP-005 | Create topic (name, partitions, RF, configs, retention quick buttons) | Kafbat, Provectus, Kouncil | P0 | topic | ui-topics | M5 | M | COMPLETE | Audited 2026-09-04. Create with partitions, RF and config entries, driven against a broker and on screen. Retention *quick buttons* do not exist — the dialog takes free-form key/value entries. |
| TP-006 | Update topic configs (diff + incremental alter) | Kafbat, Provectus | P0 | topic | ui-topics | M5 | M | COMPLETE | Audited 2026-09-04. `PATCH …/config` with `set`/`remove`; removing an override restores the broker default rather than freezing its current value. No diff preview: the dialog edits one key at a time. |
| TP-007 | Delete topic (gated by broker `delete.topic.enable`) | Kafbat, Provectus, Kouncil | P0 | topic | ui-topics | M5 | S | COMPLETE | Audited 2026-09-04. Plan-token gated (ADR-045): the plan reports records lost and `auto.create.topics.enable`; a missing or altered token is refused. The `delete.topic.enable=false` refusal is mapped but has never been driven against a broker configured that way. |
| TP-008 | Recreate topic (delete + create with same config) | Kafbat, Provectus | P1 | topic | ui-topics | M5 | M | RESEARCHING | |
| TP-009 | Clone topic to a new name | Kafbat, Provectus | P1 | topic | ui-topics | M5 | S | RESEARCHING | Body `{newName}` instead of query param. |
| TP-010 | Increase partition count | Kafbat, Provectus, Kouncil | P0 | topic | ui-topics | M5 | S | COMPLETE | Audited 2026-09-04. Plan-gated; decrease refused with a typed error; the count is re-resolved at apply time so a replayed token is refused. |
| TP-011 | Change replication factor (reassignment plan) | Kafbat, Provectus | P1 | topic | ui-topics | M5 | L | RESEARCHING | |
| TP-012 | Topic analysis (full scan: counts, sizes, percentiles, HLL uniques, hourly histogram) | Kafbat, Provectus | P1 | topic | ui-topics (Statistics tab) | M5 | L | RESEARCHING | Allowed in read-only mode. Progress over SSE is a later extension. |
| TP-013 | Active producers (idempotent / transactional state per partition) | Kafbat, Provectus | P1 | topic | ui-topics | M5 | S | RESEARCHING | |
| TP-014 | Topic → related connectors tab | Kafbat | P2 | connect (queried by gateway) | ui-topics (FeaturePanel) | M7 | S | RESEARCHING | Section of the topic overview aggregation (KU-011). |
| TP-015 | Topic → related consumer groups tab | Kafbat, Provectus | P0 | consumer | ui-topics (FeaturePanel) | M4 | S | COMPLETE | Audited 2026-09-04. `ConsumerGroupsSource` is registered in `GatewayWiring`; the tab is declared statically in `ConsumersRoutes.guestTabs`, so it no longer depends on browsing history. Live: the topic overview returns a populated `consumerGroups` section. Driven in a browser 2026-09-04 (closing pass): the tab shows `order-fulfilment` with its lag on this topic. Its URL was added in the same pass — the tab existed and `…/topics/{t}/consumers` was a 404. |
| TP-016 | Topic → ACLs tab | Kafbat | P2 | security | ui-topics (FeaturePanel) | M7 | S | RESEARCHING | Needs Topic VIEW and ACL VIEW. |
| TP-017 | Topics CSV export | Kafbat | P2 | topic | ui-topics | M5 | S | RESEARCHING | Content negotiation. |
| TP-018 | Batch actions on the topic list (multi-select delete / purge / copy) | Kafbat, Provectus | P1 | — (client composition) | ui-topics | M5 | M | RESEARCHING | Partial failure reported per topic. |
| TP-019 | Topic existence check helper endpoint | Kouncil | P3 | topic | — | — | S | REJECTED(folded into TP-003) | recorded decision DR-13. Forms use `GET /topics/{topic}` and map 404. |

## Partitions (PA)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| PA-001 | Partition table on the topic overview (leader, replicas, ISR, offsets, count, size) | Kafbat, Provectus, Kouncil | P0 | topic | ui-topics | M2 | S | COMPLETE | Audited 2026-09-04. Leader, replicas, ISR, offsets and message count render. `sizeBytes` is always `null` until TD-017. |
| PA-002 | Per-partition analysis statistics | Kafbat, Provectus | P1 | topic | ui-topics | M5 | — | RESEARCHING | Delivered by TP-012; tracked separately for the UI table. |
| PA-003 | Per-partition log-dir sizes | Kafbat, Provectus | P1 | cluster | ui-clusters | M1 | — | BLOCKED | **Blocked on TD-017.** BR-005 was to deliver this and cannot: `LogDirDto` carries no per-topic-partition data, so there is nothing behind "which topic is filling this disk". |
| PA-004 | Partition increase / replica move | Kafbat, Provectus, Kouncil | P0 | topic, cluster | ui-topics, ui-clusters | M5 | — | IMPLEMENTING | Audited 2026-09-04. Split needed. The partition-increase half (TP-010) is COMPLETE; the replica-move half (BR-006) does not exist. |

## Messages: browsing (MS)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| MS-001 | Stream messages over SSE with polling modes (from/to offset, from/to timestamp, latest, earliest, tailing) | Kafbat (v2), Provectus (v1) | P0 | message | ui-messages | M3 | XL | COMPLETE | Audited 2026-09-04. Forward and backward, `beginning`/`latest`/`offset::N`/`timestamp::ms` and per-partition `seekTo[]` all verified live. **Tailing is not among them — see MS-005.** |
| MS-002 | Cursor-based next page over the stream | Kafbat | P0 | message | ui-messages | M3 | M | COMPLETE | Audited 2026-09-04. The `done` event's signed cursor is sent back by `BrowseSession.loadMore` behind the `messages-load-more` button; a second page was read live. No e2e test covers it. |
| MS-003 | Table-style browsing with per-partition, newest-first offset paging | Kouncil | P0 | message | ui-messages | M3 | L | RESEARCHING | Second, non-streaming read path (D-1). Kouncil-defining UX. Not started; the M3 screen is the streaming list only. |
| MS-004 | JSON column flattening grid (headers / key / value columns, depth and row caps) | Kouncil | P0 | — (client-side) | ui-messages | M3 | L | COMPLETE | Delivered 2026-09-04. `FlatTable` renders the flattener's output: `H.*` / `K*` / `V*` columns in first-seen order (so a record arriving on a tail adds columns on the right and never reorders the ones being read), a `<details>` column picker holding what is *hidden* rather than what is chosen, a record with no value for a column leaving a gap rather than shifting its neighbours, and the row cap stated under the table. `RecordSource` is the `MessageDto` → `FlatSource` conversion, including the not-JSON degraded case. A `view=table` parameter puts the choice in the URL, so a table view is a link. 7 DOM tests. |
| MS-005 | Live / tailing mode with client throttle and play/pause | Kafbat, Provectus, Kouncil | P0 | message | ui-messages | M3 | M | COMPLETE | Delivered 2026-09-04. `BrowseRequest.live` now reaches the poll loop: a tail always reads forwards (the default direction of `Latest` is backwards, and backwards from the end of the log is the empty range that made the control deliver nothing), its window has no upper bound, and none of the three things that end a bounded read — the limit, consecutive empty polls, the budget deadline — ends a tail. It stops on cancellation only, which closes the consumer. Play/pause is client-side in `BrowseSession`: paused holds records back with the stream still open and the count still moving, resuming shows them in order, Stop releases what was held. 24 tests across the domain, application, infrastructure and `ui-messages` suites. Throttle (`PollBudget.throttleBytesPerSecond`) remains, with MS-014. |
| MS-006 | Simple string-contains filter over key, value, headers | Kafbat, Provectus, Kouncil | P0 | message | ui-messages | M3 | S | COMPLETE | The `Contains` box on the message screen; `q=OrderShipped` verified against the quickstart broker. |
| MS-007 | Smart filters: CEL scripts, registration with TTL, dry-run test, saved filters | Kafbat (CEL), Provectus (Groovy) | P1 | message | ui-messages | M3 | L | IMPLEMENTING | Audited 2026-09-04. **Code-only.** `CelFilterEngine`, `CelEnvironment`, `MessagePredicate`, `FilterMetrics`, `FilterDtos` and the `FilterSource` port are built and tested; no module depends on `libs.filter`, `FilterSource` has no implementation, the API always passes `filter = None`, and there is no register/test endpoint and no editor. |
| MS-008 | Purge messages (delete records per partition) | Kafbat, Provectus | P1 | message | ui-messages, ui-topics | M3 | S | COMPLETE | Audited 2026-09-04. Plan-gated purge, owned by the message service, with the control on the *topic* page (`TopicAdminPanel.purgeSection`). Plan and apply both driven against a broker. A compacted topic has never been purged on screen. |
| MS-009 | Serde suggestions per topic for serialize / deserialize | Kafbat, Provectus | P0 | message | ui-messages | M3 | S | IMPLEMENTING | Audited 2026-09-04. `ClusterSerdes.suggest` and `SerdeSuggestionDto` exist; no endpoint exposes them. |
| MS-010 | Message detail view (key / content / headers, copy, pre-masking raw, resend entry point) | Kafbat, Provectus, Kouncil | P0 | — | ui-messages | M3 | M | REVIEW | Audited 2026-09-04. Key, pretty value, headers, decode errors and row actions render. No copy-to-clipboard, no pre-masking raw view, no per-record serde picker. |
| MS-011 | Export rendered messages to CSV / JSON | Kafbat | P2 | — | ui-messages | M3 | S | RESEARCHING | Audited 2026-09-04. **Corrected downward: nothing exists.** No `csv`/`export`/`download` identifier anywhere in `frontend/ui-messages`. The viewer sandbox also blocks page-initiated downloads, so this needs a server-rendered response. |
| MS-012 | Decode Spring DLT / retry numeric headers | Kouncil | P2 | message | — | M3 | S | IMPLEMENTING | Audited 2026-09-04. `HeaderDecoding` is built and tested with no reference outside `libs/serde`; headers reach the browser as raw strings. |
| MS-013 | Polling throttle (bytes/s per cluster) with `consumed` stats event | Kafbat, Provectus | P1 | message | — | M3 | S | IMPLEMENTING | Audited 2026-09-04. The `consumed` event and its byte/record budget are live. `PollBudget.throttleBytesPerSecond` is never set and never read; there is no rate limiter and no configuration key. |
| MS-014 | Relative timestamps and user timezone in message tables | Kafbat | P3 | — | kernel | M3 | S | IMPLEMENTING | Audited 2026-09-04. Absolute timestamps render in the user's zone; `Timestamps.relative` exists and the message table does not call it. |

## Messages: production (MP)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| MP-001 | Produce a message (key, value, headers, partition, serde selection, keep-contents) | Kafbat, Provectus, Kouncil | P0 | message | ui-messages | M3 | M | COMPLETE | Publish drawer, plus Republish and Copy-to-another-topic on an open record. Driven in a browser against the quickstart broker on 2026-09-04: published a keyed JSON record with a header, got back `partition 3, offset 3`, and read it back from the browse screen. A read-only cluster is refused before a producer is asked for. Serde selection per MP-004 is not built. |
| MP-002 | Bulk send with `{{count}}`, `{{timestamp}}`, `{{uuid}}` placeholders | Kouncil | P1 | message | ui-messages | M3 | S | IMPLEMENTING | Audited 2026-09-04. Server-side `count` works (two records acked from one request, live). The `{{count}}`/`{{uuid}}`/`{{timestamp}}` placeholders are specified as a browser feature and are not implemented in the browser. |
| MP-003 | Resend (copy) an offset range between topics with header filtering | Kouncil | P1 | message | ui-messages | M3 | M | COMPLETE | Audited 2026-09-04. **Corrected upward from 'not built'.** `POST …/messages/resend` copied a three-record range between topics live. Kouncil's header *filtering* is absent: `ResendRequestDto` carries only `toTopic` and `ranges`. |
| MP-004 | Produce with per-serde parameters and schema validation before send | Kafbat | P1 | message | ui-messages | M3 | M | RESEARCHING | Uses SD-004. |

## Consumer groups (CG)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CG-001 | List consumer groups: paged, sorted, searched, state filter | Kafbat, Provectus, Kouncil | P0 | consumer | ui-consumers | M4 | M | COMPLETE | Paged, searched and state-filtered from a browser. Verified against the quickstart's three seeded groups. |
| CG-002 | Group details: members, assignments, offsets, lag, pace | Kafbat, Provectus, Kouncil | P0 | consumer | ui-consumers | M4 | M | COMPLETE | Audited 2026-09-04. Members, assignments, offsets and lag render. **The 'pace is null, nothing measures it' note was wrong**: `LagMath.pace` is computed and served on the list and lag-delta payloads (live: `-9.296…`). **Pace column delivered 2026-09-04** (`05287d2`): a non-sortable column on the group list, one decimal place, `< 0.1` rather than `0.0` for a slow group, a muted `0` with "committing nothing" for a stalled one, and a coloured negative for offsets moving backwards. The detail DTO still omits it. |
| CG-003 | Delete group | Kafbat, Provectus, Kouncil | P0 | consumer | ui-consumers | M4 | S | COMPLETE | Delivered 2026-09-04 (`05287d2`). `GroupDangerZone` on the group detail page: a confirmation dialogue whose default focus is Cancel, no plan token (a delete has no arithmetic to preview), and no client-side copy of Kafka's not-empty rule — the server re-checks it immediately before the write and a consumer can join between the click and the request, so the refusal is rendered as the sentence it is. |
| CG-004 | Reset offsets wizard (earliest / latest / timestamp / per-partition offset) | Kafbat, Provectus, Kouncil | P0 | consumer | ui-consumers | M4 | M | COMPLETE | Audited 2026-09-04. All six modes planned live and apply is token-only. Two parity gaps against the references: no partition-subset selection (every partition of the topic is always sent) and `OFFSET` mode replicates one offset to every partition. |
| CG-005 | Delete committed offsets for one topic | Kafbat | P1 | consumer | ui-consumers | M4 | S | COMPLETE | Delivered 2026-09-04 (`05287d2`). Offered per topic, on the topics the group actually holds offsets on, with a receipt that names how many partitions were forgotten — so 'the group held none there' and 'they are gone now' stay different sentences. |
| CG-006 | Incremental lag polling and lag trend sparkline | Kafbat | P1 | consumer | ui-consumers | M4 | M | REVIEW | Audited 2026-09-04. **The 'nothing calls it, the list does not poll' note was wrong.** `GroupListPage` mounts `LagPoller.binder`; the endpoint answers with a token and `nextPollMs` live. What remains is GRP-033 items 3–4: `LagTrend`, `LagSparkline`, the ▲/▼ arrows and the missing-sample rules — no `lag/` directory exists. |
| CG-007 | Consumer groups CSV export | Kafbat | P2 | consumer | ui-consumers | M5 | S | RESEARCHING | |
| CG-008 | Consumer group full-text n-gram search | Kafbat | P2 | consumer | — | M9 | S | DEFERRED(follows TP-002) | recorded decision DR-10. |
| CG-009 | `__consumer_offsets` decoding serde | Kafbat, Provectus | P2 | message | — | M5 | M | RESEARCHING | Part of the extended serde set (KU-023). |

## Schema registry (SR)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| SR-001 | List subjects: paged, sorted, searched | Kafbat, Provectus, Kouncil | P0 | schema | ui-schemas | M7 | M | SERVICE DONE, NO UI | Updated 2026-09-04. `GET /api/v1/clusters/{id}/schemas/subjects`, searched, sorted and paged in `SubjectCatalog` because the registry offers none of the three. Reachable through the gateway; no `ui-schemas` microfrontend yet. |
| SR-002 | Create schema / new version (Avro, JSON Schema, Protobuf) | Kafbat, Provectus, Kouncil | P0 | schema | ui-schemas | M7 | M | RESEARCHING | |
| SR-003 | Get latest / by version / all versions | Kafbat, Provectus, Kouncil | P0 | schema | ui-schemas | M7 | S | SERVICE DONE, NO UI | Updated 2026-09-04. Version list and one version's schema, with `latest` kept distinct from a number so a typo cannot silently return the newest. |
| SR-004 | Delete subject / latest / by version (soft and hard) | Kafbat, Provectus, Kouncil | P0 | schema | ui-schemas | M7 | S | RESEARCHING | |
| SR-005 | Compatibility: global get/set, per-subject set, compatibility check | Kafbat, Provectus, Kouncil | P0 | schema | ui-schemas | M7 | S | SERVICE DONE, NO UI | Updated 2026-09-04. Both writes are audited and refused on a read-only cluster (ADR-047); the subject read reports whether the level is inherited. The check is not a mutation and is answered on a read-only cluster. |
| SR-006 | Version diff viewer | Kafbat, Provectus | P1 | — | ui-schemas | M7 | M | RESEARCHING | Kernel `DiffViewer`. |
| SR-007 | SR auth: basic, OAuth client-credentials, SSL stores | Kafbat, Provectus, Kouncil | P1 | schema | ui-admin (form in M8) | M7 | M | PARTIAL | Updated 2026-09-04. `kui.clusters.<n>.schemaRegistry.auth` carries none, basic and OAuth client credentials, refusing a file that configures both. The schema service implements all three, with the token cached and fetched over its own upstream client. SSL stores are not implemented, and the serde path (ADR-014) still treats OAuth as unauthenticated. |
| SR-008 | Topic → subject suffix convention | Kafbat | P2 | schema | — | M7 | S | PARTIAL | Updated 2026-09-04. `SubjectCatalog.subjectFor` implements the `TopicNameStrategy` convention in the schema domain. Nothing consumes it yet: SR-009's section source on the topic overview is what makes it reachable. |
| SR-009 | Latest key/value schema for a topic (feeds the produce editor) | Kouncil | P1 | schema | ui-messages | M7 | S | RESEARCHING | `GET /topics/{topic}/schemas`; section of the topic overview aggregation. |

## Kafka Connect (KC)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| KC-001 | List Connect clusters with connector / task counts | Kafbat, Provectus | P0 | connect | ui-connect | M7 | S | RESEARCHING | Partial by design: per-connect `status`. |
| KC-002 | List all connectors across connects: sorted, searched, paged | Kafbat, Provectus | P0 | connect | ui-connect | M7 | M | RESEARCHING | Paging added (Kafbat has none). |
| KC-003 | Connector create / get / delete | Kafbat, Provectus | P0 | connect | ui-connect | M7 | M | RESEARCHING | 409 rebalance → `KUI-CONNECT-REBALANCING`. |
| KC-004 | Connector config editor (get / set) | Kafbat, Provectus | P0 | connect | ui-connect | M7 | S | RESEARCHING | |
| KC-005 | Connector state actions (restart, restart all/failed tasks, pause, resume, stop) | Kafbat, Provectus | P0 | connect | ui-connect | M7 | S | RESEARCHING | `POST .../actions/{action}`. |
| KC-006 | Tasks list with trace, restart task | Kafbat, Provectus | P0 | connect | ui-connect | M7 | S | RESEARCHING | |
| KC-007 | Reset connector offsets | Kafbat | P1 | connect | ui-connect | M7 | S | RESEARCHING | Connect ≥ 3.6. |
| KC-008 | Plugins list and connector config validation (guided create) | Kafbat, Provectus | P1 | connect | ui-connect | M7 | M | RESEARCHING | RBAC gap closed by KU-022. |
| KC-009 | Connector topics tab | Kafbat | P2 | connect | ui-connect | M7 | S | RESEARCHING | |
| KC-010 | Connect / connector CSV export and connect client cache | Kafbat | P2 | connect | ui-connect | M7 | S | RESEARCHING | The n-gram search part follows DR-10 (M9). |

## ksqlDB (KS)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| KS-001 | Execute statement → query id → SSE result stream | Kafbat, Provectus | P1 | ksql | ui-ksql | M7 | L | RESEARCHING | Two-step design kept (EventSource cannot POST); push queries stream until disconnect. |
| KS-002 | List tables / streams | Kafbat, Provectus | P1 | ksql | ui-ksql | M7 | S | RESEARCHING | |
| KS-003 | Query editor UI with streams properties and result table | Kafbat, Provectus | P1 | — | ui-ksql | M7 | M | RESEARCHING | CodeMirror SQL mode (ADR-025). |
| KS-004 | ksql auth (basic) and SSL | Kafbat, Provectus | P1 | ksql | ui-admin (form in M8) | M7 | S | RESEARCHING | |

## ACLs (AC)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| AC-001 | List ACL bindings with resource / name / pattern filters | Kafbat, Provectus | P1 | security | ui-security | M7 | M | RESEARCHING | `MATCH` exposed as a filter-only value. |
| AC-002 | Create / delete ACL binding | Kafbat, Provectus | P1 | security | ui-security | M7 | S | RESEARCHING | Delete via `POST /acls/delete` (no DELETE with body). |
| AC-003 | ACL CSV export and declarative CSV sync | Kafbat, Provectus | P1 | security | ui-security | M7 | M | RESEARCHING | Sync is destructive: confirmation with typed name. |
| AC-004 | Convenience creators: consumer, producer, stream app | Kafbat, Provectus | P1 | security | ui-security | M7 | M | RESEARCHING | |

## Client quotas (QU)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| QU-001 | List client quotas (user / client id / ip) | Kafbat | P1 | security | ui-security | M7 | S | RESEARCHING | Screen 26 of the IA proposal. |
| QU-002 | Upsert / delete quotas | Kafbat | P1 | security | ui-security | M7 | S | RESEARCHING | |

## Metrics, graphs, Prometheus (MT)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| MT-001 | JMX metrics scraping (SSL, auth) per broker | Kafbat, Provectus, Kouncil | P1 | metrics | — | M8 | L | RESEARCHING | |
| MT-002 | Prometheus endpoint scraping | Kafbat, Provectus | P1 | metrics | — | M8 | M | RESEARCHING | |
| MT-003 | Inferred metrics without JMX, IO rate scanner | Kafbat | P1 | metrics | — | M8 | M | RESEARCHING | Makes dashboards work on clusters without JMX. |
| MT-004 | Graph descriptions and Prometheus query proxy with templated PromQL | Kafbat | P2 | metrics | ui-metrics | M8 | L | RESEARCHING | Typed `GraphData` instead of raw PromQL passthrough. uPlot facade (ADR-025). |
| MT-005 | Prometheus exposition `/metrics`, `/metrics/clusters/{id}` | Kafbat | P2 | metrics | — | M8 | M | RESEARCHING | Outside `/api/v1`, allow-listed, no session auth. |
| MT-006 | Push-gateway / remote-write sinks | Kafbat | P3 | metrics | — | M9 | M | DEFERRED(pull-based MT-005 covers monitoring) | recorded decision DR-3. |
| MT-007 | KUI self-metrics (JVM, HTTP, per-service) | Kafbat, Provectus | P0 | all services | — | M0 | S | IMPLEMENTING | Audited 2026-09-04. **The only row in this area with running code, and drifting both ways.** 16 of 26 declared metric names have an emitter; 10 have none, including `kui.upstream.circuit.state`, which `docs/operations/observability.md` claims is live (transitions are logged, never metered). No JVM runtime instrumentation exists. Neither runnable deployment sets `prometheusPort`, so nothing has ever scraped it. |

## Config wizard and dynamic config (CW)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CW-001 | App info: build info, enabled features | Kafbat, Provectus, Kouncil | P1 | gateway | shell | M0 | S | COMPLETE | Audited 2026-09-04. `GET /api/v1/info` answers with build info and git state on both running deployments. |
| CW-002 | Read current config (redacted) and apply a new one with hot reload | Kafbat, Provectus | P1 | cluster + identity, aggregated by gateway | ui-admin | M8 | L | RESEARCHING | No process restart: config distribution to services. `/api/v1/config` is a gateway aggregation over the two owners (ADR-004, ADR-036). |
| CW-003 | Validate a config with per-component connectivity probes | Kafbat, Provectus, Kouncil | P1 | cluster | ui-admin | M8 | M | RESEARCHING | Probe URLs pass the SSRF policy (KU-024). |
| CW-004 | Upload related files (truststore, keystore, proto) | Kafbat, Provectus | P2 | cluster | ui-admin | M8 | S | RESEARCHING | Bytes go to the `__kui_files` topic, encrypted, size-capped by `kui.store.maxFileBytes` (ADR-042). |
| CW-005 | Cluster config wizard UI (bootstrap, auth, SR, Connect, ksql, metrics, serdes, masking) | Kafbat, Provectus, Kouncil | P1 | — | ui-admin | M8 | L | RESEARCHING | Screens 28–29 of the IA proposal. |
| CW-006 | First-launch onboarding: temporary admin, removed on logout | Kouncil | P2 | identity | shell | M6 | M | RESEARCHING | DC-H12. |

## Authentication (AU)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| AU-001 | Auth types: disabled, login form, OAuth2/OIDC (GitHub, GitLab, Google, Cognito, Azure Entra, generic), LDAP / AD | Kafbat, Provectus, Kouncil | P0 | identity (+gateway session) | shell | M6 | L | RESEARCHING | ADR-015. Sign-in screen 1 of the IA proposal. |
| AU-002 | In-memory users with default accounts and forced password change | Kouncil | P2 | identity | shell | M6 | M | RESEARCHING | Dev / demo installs. |
| AU-003 | SSO provider list and GitHub org/team role source | Kouncil | P2 | identity | — | M6 | M | RESEARCHING | Overlaps RB-002 GitHub extractor; implement once. |
| AU-004 | CSRF protection for the SPA | Kouncil | P0 | gateway | — | M6 | S | COMPLETE | Audited 2026-09-04. **Corrected upward.** `CsrfCheck` + `SessionMiddleware`, constant-time compare, `Sec-Fetch-Site` rejection, and the browser client sends the header. Verified live in three states. The header is `X-Csrf-Token`, not `X-KUI-CSRF` as this row previously said. |
| AU-005 | User menu: logout, theme auto/light/dark, timezone | Kafbat | P1 | — | shell | M1 | S | IMPLEMENTING | Audited 2026-09-04. **Corrected downward from COMPLETE.** Only a three-state theme toggle exists (`Header.scala`). There is no user menu, no logout control (the `POST /auth/logout` endpoint works and nothing calls it) and no timezone control anywhere in the frontend. |

## Authorization: RBAC and read-only (RB)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| RB-001 | Role model: subjects × clusters × resource pattern × actions, with action dependencies | Kafbat, Provectus | P0 | identity (evaluation) + gateway (enforcement) | ui-admin (view) | M6 | XL | RESEARCHING | Kafbat resource × action matrix is the canonical vocabulary (DR-14). Pure evaluation in `kui-security-core`. |
| RB-002 | Authority extractors: GitHub orgs/teams, Google hd, Cognito groups, OIDC claim, LDAP, AD | Kafbat, Provectus | P1 | identity | — | M6 | M | RESEARCHING | |
| RB-003 | Current user info and flattened permissions for UI gating | Kafbat, Provectus | P0 | gateway | kernel | M6 | S | IMPLEMENTING | Audited 2026-09-04. `GET /auth/me` answers with an identity, but `roles` is always empty and `AuthMeResponse` carries no permission list. `ActionPermissionWrapper` exists and is called from exactly one site, which passes no `permitted`. No write control anywhere is gated. |
| RB-004 | UI-managed user groups and function-permission matrix, persisted | Kouncil | P1 | identity | ui-admin | M6 | L | RESEARCHING | Persisted role store behind the same evaluation API; file wins, UI adds (D-5). Stored under the `rbac/roles` key of `__kui_config` through OT-004 (ADR-042), which is already available from M1. |
| RB-005 | Read-only cluster mode | Kafbat, Provectus | P0 | gateway (policy) | kernel | M5 | S | COMPLETE | Audited 2026-09-04. **Corrected upward.** Three independent `MutationGuard`s (topic, message, consumer) refuse before any Kafka client is touched and write a `Refused` audit record; verified against a `readOnly: true` cluster. Two gaps: no shipped deployment sets `readOnly`, and the UI pre-disables nothing, so buttons are live and fail at the server. |

## Audit (AD)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| AD-001 | Audit log of mutating operations to console and/or a Kafka topic (`ALL` / `ALTER_ONLY`) | Kafbat, Provectus | P1 | identity (sink), fed by gateway | ui-admin | M5 | M | REVIEW | Audited 2026-09-04. **Corrected upward for the console sink**, which is wired in all three services and was observed writing `topic.create on …: succeeded` on a live deployment. Missing: the `__kui_audit` Kafka sink, the `ALL`/`ALTER_ONLY` knob, and a real principal (all three services hard-code a placeholder, and two disagree on its wording). The consumer service duplicates the `AuditSink` port in its own domain instead of using `libs/security-core`. |
| AD-002 | Audit topic self-protection (browsing it requires `AUDIT:VIEW`) | Kafbat, Provectus | P1 | gateway | — | M6 | S | RESEARCHING | |
| AD-003 | Entry/exit AOP logger and HTTP trace actuator | Kouncil | P3 | — | — | — | S | REJECTED(replaced by OpenTelemetry tracing) | recorded decision DR-8. |

## Data masking (DM)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| DM-001 | Config-driven masking (`REMOVE` / `MASK` / `REPLACE`, field paths, topic patterns) | Kafbat, Provectus | P1 | message | — | M3 | M | IMPLEMENTING | Audited 2026-09-04. **Clearest instance of the project's failure mode.** `MaskingEngine` and `MaskingRule` are built and unit-tested with no caller in any service, there is no configuration that can define a rule, and `docs/operations/masking.md` does not exist. |
| DM-002 | UI-managed masking policies bound to user groups (`ALL` / `FIRST_5` / `LAST_5`) | Kouncil | P1 | message (apply) + identity (policy store) | ui-admin | M6 | L | RESEARCHING | Same engine as DM-001 with caller-group scoping (D-6). Needs identity, so M6 not M5. Stored under `masking/<clusterId>` in `__kui_config` (ADR-042, ADR-023). |

## Serdes (SD)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| SD-001 | Core built-in serdes: String, Schema Registry (Avro / JSON Schema / Protobuf), Int32/64, UInt32/64, Base64, Hex, UUID; auto-detection by magic byte | Kafbat, Provectus, Kouncil | P0 | message | ui-messages (picker) | M3 | XL | COMPLETE | Updated 2026-09-04. Wired: the message service's composition root now builds a `SchemaRegistrySerdeFactory` for every cluster that configures `schemaRegistry`, so registry payloads decode in the product and not only in a suite. **Protobuf now decodes too**: `ProtoSchema` parses the `.proto` text the registry returns and `ProtobufPayload` reads the record against it, with Confluent's message-index prefix handled — no dependency and no Confluent Community Licensed code (ADR-014 Amendment 2). Writing Protobuf is still refused by name; Avro and JSON Schema encode as well as decode. Verified live against Apicurio Registry with records written by the reference `confluent-kafka` producer. |
| SD-002 | Custom serde plugin loading (jar, isolated classloader) | Kafbat, Provectus | P2 | message | — | M7 | L | DEFERRED(needs a serde SPI ADR) | recorded decision DR-9. ADR written before M7 implementation begins. |
| SD-003 | Default key/value serde per cluster and resolution order | Kafbat, Provectus | P0 | message | — | M3 | S | COMPLETE | Updated 2026-09-04. `kui.clusters.<n>.serde` exists: `defaultKey`, `defaultValue`, an ordered `patterns` list and the two cache bounds, decoded into `SerdeResolution.Rules` by the message service's composition root. The resolution table that had been correct and unreachable since it was written is now fed by configuration. |
| SD-004 | Schema → JSON Schema conversion for produce-form validation | Kafbat, Provectus | P1 | message | ui-messages | M3 | M | RESEARCHING | |

## Search and filtering, cross-cutting (SF)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| SF-001 | Case-insensitive substring search on every list | Kafbat, Provectus, Kouncil | P0 | each service | kernel (search box) | M2 | S | COMPLETE | `q` parameter, URL-synced, exact and fuzzy modes, on the topic list. |
| SF-002 | Full-text n-gram index across topics, groups, schemas, connectors, ACLs | Kafbat | P2 | each service (shared index lib) | — | M9 | L | DEFERRED(follows TP-002) | recorded decision DR-10. |
| SF-003 | Virtualized, sortable data table with favourites pinning | Kafbat, Kouncil | P0 | — | kernel | M2 | M | REVIEW | Audited 2026-09-04. `VirtualizedTable` ships with its own suite and **has no caller** (TD-018). The topic and partition tables are unvirtualized. |

## Event tracking (ET)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| ET-001 | Track a business event across topics by header or value match within a time window (sync, capped) | Kouncil | P0 | message | ui-messages (Track page) | M3 | L | IMPLEMENTING | Audited 2026-09-04. `TrackQuery` and `TrackDtos` are complete and tested and referenced only by their own test. No use case, endpoint, route or screen. |
| ET-002 | Track asynchronously with results streamed as found | Kouncil | P0 | message | ui-messages | M3 | M | RESEARCHING | SSE, not STOMP (DR-12, D-4). Cancel on disconnect. |
| ET-003 | Track filter UI (field / operator / value, topic multi-select, time range) and results grid | Kouncil | P0 | — | ui-messages | M3 | M | RESEARCHING | Reuses the message grid and one-click "track this header value" from MS-010. |

## Notifications and miscellaneous UX (NX)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| NX-001 | Server push notifications (forced logout on permission change, no clusters defined) | Kouncil | P2 | gateway (SSE bus) | kernel (notification bus) | M6 | M | RESEARCHING | Rides the capability SSE channel (KU-001). |
| NX-002 | In-app survey popup | Kouncil | P3 | — | — | — | S | REJECTED(telemetry and consent concerns, no operator value) | recorded decision DR-5. |
| NX-003 | Version and latest-release banner | Kafbat, Provectus | P2 | gateway | shell | M8 | S | RESEARCHING | Release lookup only when OT-006 is opted in. |
| NX-004 | Demo mode with an in-memory fake backend | Kouncil | P3 | — | shell | M9 | M | DEFERRED(marketing aid, not a product capability) | recorded decision DR-6. |
| NX-005 | Custom context path / base path | Kafbat, Provectus, Kouncil | P1 | gateway | shell | M0 | S | RESEARCHING | Reverse-proxy deployments. Part of static asset serving. |
| NX-006 | CORS configuration | Kafbat, Provectus | P1 | gateway | — | M0 | S | RESEARCHING | Off by default; explicit origin list when enabled. |
| NX-007 | Shared UI primitives: confirmation modals, toasts, breadcrumbs, empty states, drawers, tabs, forms | Kafbat, Provectus, Kouncil | P0 | — | kernel | M0 | M | COMPLETE | Audited 2026-09-04. Dialogs, toasts, tabs, drawers, empty states, `DataTable` and `VirtualizedTable` all ship and are used. |

## MCP (MC)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| MC-001 | MCP server exposing API operations as tools, derived from Tapir endpoints | Kafbat | P2 | gateway | — | M8 | L | RESEARCHING | recorded decision DR-11. Read/write classification by method; read-only and RBAC pass through. |

## Data catalog (OD)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| OD-001 | OpenDataDiscovery exporter (topics, connectors, schema fields, lineage) | Kafbat, Provectus | P3 | topic, connect (exporter plugin) | — | M9 | L | DEFERRED(external platform integration, no core value) | recorded decision DR-4. No interface slot is reserved before M9. |

## Other cross-cutting knobs (OT)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| OT-001 | Admin-client timeout, batching and concurrency knobs | Kafbat | P1 | cluster, topic, consumer | — | M1 | S | COMPLETE | Shared `kui-kafka` settings. |
| OT-002 | CSV formatting knobs | Kafbat | P2 | shared lib | — | M5 | S | RESEARCHING | With the first CSV export. |
| OT-003 | Per-cluster consumer / producer / admin property overrides | Kafbat, Provectus | P0 | `libs/config` + cluster | — | M1 | S | COMPLETE | The escape hatch of CL-007. |
| OT-004 | Kafka-backed metadata store: `ConfigStore[F]` over internal compacted topics (`__kui_config`, `__kui_files`), replay + tail, optimistic `version`, read-your-writes | KUI-new (replaces Kouncil's D-10) | P0 | cluster, identity | — | M1 | L | COMPLETE | ADR-042. Replaces relational persistence: no database, ever. Core store is M1 because clusters become registrable at runtime there; UI-managed roles/groups (RB-004), masking policies (DM-002) and the wizard (CW-002 … CW-005) are consumers of it and keep their own milestones. File adapter stays for dev, bootstrap and read-only, and file-only mode must keep working (M0 ships the file adapter only). Closed `TECH_DEBT.md` TD-014. |
| OT-007 | Store topic creation and validation: create `__kui_*` if missing, validate if present, fail fast with the setting, expected and found values | KUI-new | P0 | cluster, identity | — | M1 | S | COMPLETE | ADR-042. Never rewrites operator topic settings silently. |
| OT-008 | Envelope encryption of secret fields at rest (AES-GCM, `keyId` in the envelope) and key rotation | KUI-new | P0 | cluster, identity | — | M1 | M | COMPLETE | ADR-042 §4; `research/scala/security-research.md` §5. Key from `kui.store.encryptionKey`, never stored. Rotation writes new records under a new `keyId` while old ones stay readable. |
| OT-009 | Store health as a capability: store unreachable means last known state, `Degraded(reason)`, writes rejected | KUI-new | P0 | cluster, identity (+gateway registry) | kernel | M1 | S | REVIEW | Health state machine, sticky `since` and rejected writes all ship and are unit-tested. Nothing yet stops the store's broker mid-run and asserts the three consequences together; M1 exit criterion 11 is open. |
| OT-010 | Operator guidance for the store: sizing, ACLs, encryption key handling, backup/restore, file-to-Kafka migration | KUI-new | P1 | — | — | M1 | S | COMPLETE | `docs/operations/metadata-store.md`. Ships with OT-004; revisited when RB-004 and DM-002 add sections. |
| OT-005 | Uniform error envelope with stable `KUI-*` codes and correlation id | Kouncil, Kafbat | P0 | all | kernel | M0 | S | COMPLETE | Audited 2026-09-04. Every live refusal in this audit carried a `KUI-*` code and a correlation id. |
| OT-006 | Release check phone-home and installation id | Kafbat, Provectus, Kouncil | P3 | gateway | — | M8 | S | RESEARCHING | Opt-in only, default off (recorded decision DR-7). |

## KUI-only capabilities (KU)

Rows no reference has. They come from KUI's own product properties (fault isolation, streaming-first, deployable two
ways), from the IA proposal in `research/kafbat/ui-analysis.md`, and from gaps the API and
security research flagged.

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| KU-001 | Capability registry: readiness polling, circuit-breaker state, `GET /capabilities`, `GET /capabilities/stream` (SSE) | KUI-new | P0 | gateway | kernel (`CapabilityState`) | M0 | M | COMPLETE | Audited 2026-09-04. `GET /capabilities` answers live with per-service and per-cluster entries; readiness polling and circuit state are wired. |
| KU-002 | Capability-driven navigation: `FeatureGate`, feature fallback panel with reason / since / retry, capability banner, `NotConfigured` hidden, `Unavailable` shown and clickable | KUI-new | P0 | — | shell, kernel | M0 | M | COMPLETE | Audited 2026-09-04. `FeatureGate` imports a feature module only when its state is not `NotConfigured`; `Sidebar` hides `NotConfigured` and dims `Unavailable`. Covered by `FeatureGateSuite` and `NavigationSuite`. |
| KU-003 | Structured degraded reason payload (`code`, `message`, `suggestedPollIntervalMs`, `p95Ms`) | KUI-new | P0 | gateway | kernel | M0 | S | REVIEW | Audited 2026-09-04. Reason codes and their sentences ship and were observed live (`UPSTREAM_TIMEOUT`, `UPSTREAM_UNAVAILABLE`). `suggestedPollIntervalMs` and `p95Ms` were not observed on any live payload. |
| KU-004 | `KuiFeature` contract and lazy feature loader (Scala.js module splitting, ADR-012) | KUI-new | P0 | — | kernel, shell | M0 | M | COMPLETE | Audited 2026-09-04. Four features are registered and lazily loaded. **TD-016 stands:** `main.js` still eagerly imports the clusters feature, and the e2e case that would catch it is marked `.fail`. |
| KU-005 | All-in-one deployment: gateway plus every service in one JVM through the same contracts | KUI-new | P0 | apps/allinone | — | M0 | M | COMPLETE | Audited 2026-09-04. The all-in-one image runs the gateway and every service in one JVM and serves the full API surface (31 paths). |
| KU-006 | Per-service `/health/live`, `/health/ready`, `/capabilities`; gateway OpenAPI aggregation | KUI-new | P0 | all, gateway | — | M0 | S | COMPLETE | Audited 2026-09-04. `/health/live`, `/health/ready` and `/capabilities` answer on both deployments; the gateway aggregates OpenAPI. |
| KU-007 | Typed, validated configuration with `Secret[A]` redaction in logs, traces, errors and API responses | KUI-new | P0 | `kui-config` | — | M0 | M | COMPLETE | Audited 2026-09-04. Nine shipped configuration files are loaded through the real loader under the real `UrlPolicy` by `ShippedConfigurationSuite`, which also proves `production.yaml`'s secrets resolve rather than storing their own `env:` reference. |
| KU-008 | Docker Compose dev environment and CI pipeline (compile `-Werror`, format, tests, link, OpenAPI diff) | KUI-new | P0 | deployment | — | M0 | M | COMPLETE | Re-audited 2026-09-04 after E7. The CI workflow now runs **every** test module through `./scripts/run-tests.sh` — 4140 test cases across 57 modules, counted from the JUnit reports rather than read off Mill's task count — plus a new `generated` job for `__.openApiCheck` (4 modules) and `docs.errorCodes --check`. `deployment/compose/smoke.sh` was fixed (it asserted `GET /api/v1/ping`, deleted in M1) and now runs in the end-to-end job; the four shipped artifacts that documented that endpoint were corrected. The root cause of the old figure was that `./mill a.test b.test` passes `b.test` to `a.test` as a test-name filter, so the JVM step had been running zero cases while reporting green. |
| KU-009 | `403`, `404`, feature fallback pages and the single full-screen "cannot reach gateway" state | KUI-new | P0 | — | shell | M0 | S | COMPLETE | Audited 2026-09-04. Feature fallback panels and the full-screen gateway-unreachable state are exercised by `ClusterServiceDownSuite`. |
| KU-010 | Stale data stays on screen (greyed, timestamped, actions disabled) when a feature becomes Unavailable | KUI-new | P1 | — | kernel (`StaleDataOverlay`, `QueryCache`) | M1 | M | COMPLETE | DC-H3, decided before M1 because every feature state depends on it. |
| KU-011 | Partial aggregation endpoints with per-section status: cluster dashboard (M1), topic overview (M2, sections added in M4/M7), consumer group page (M4), connects with stats (M7) | KUI-new | P0 | gateway | ui-clusters, ui-topics, ui-consumers | M1 | M | COMPLETE | Milestone is when the first one ships. |
| KU-012 | User settings page: theme, timezone, refresh rate, table density | KUI-new | P1 | — | shell | M1 | S | REVIEW | Audited 2026-09-04. Theme ships and a timezone signal is threaded to the message table, but there is no settings page and no control that sets timezone, refresh rate or density (see AU-005). |
| KU-013 | Cross-feature `FeaturePanel` slot (topic → consumers tab, broker → metrics tab) keyed by feature id, never by import | KUI-new | P1 | — | kernel | M2 | M | COMPLETE | The slot exists and the topic overview renders it: `consumerGroups`, `connectors`, `acls` and `schemas` each come back `not_configured`, which is what the criterion asks for. |
| KU-014 | SSE envelope with named events (`phase`, `message`, `consumed`, `done`, `error`, `heartbeat`) and `id:` for `Last-Event-ID` reconnect; kernel `SseStream` wrapper | KUI-new | P0 | message, gateway | kernel | M3 | M | COMPLETE | Audited 2026-09-04. `phase`/`message`/`consumed`/`done` with `id:` observed live on the browse stream; the kernel wrapper is used by the message screen. |
| KU-015 | Self-describing signed browse cursor (survives gateway restarts and multiple replicas) | KUI-new | P0 | message | — | M3 | M | COMPLETE | Audited 2026-09-04. The signed cursor round-trips and a second page was read from one live. TD-005's promised `KUI-CURSOR-TOO-LARGE` size refusal is asserted by no test. |
| KU-016 | Smart-filter test execution is cluster-scoped and requires `TOPIC:MESSAGES_READ` | KUI-new | P1 | gateway | ui-messages | M3 | S | RESEARCHING | RBAC gap: Kafbat lets any authenticated user execute arbitrary CEL. Enforced from M6 when RBAC exists; the endpoint shape is fixed in M3. |
| KU-017 | Signed principal header gateway → services (request-bound, short expiry, per-service audience); services reject unsigned requests except in all-in-one mode | KUI-new | P0 | gateway, all services | — | M6 | M | COMPLETE | Audited 2026-09-04. Minted per call by the gateway, verified by every service, with inbound `X-Kui-*` stripped at the edge. The principal it carries is always anonymous until AU-001. |
| KU-018 | Pluggable server-side session store (in-memory default, shared store adapter) | KUI-new | P1 | gateway | — | M6 | M | REVIEW | Audited 2026-09-04. `InMemorySessionStore` is the only adapter (TD-003), so sessions are single-replica. `rotate` exists and is called by nothing — the session-fixation defence is code-only until a login exists. |
| KU-019 | RBAC view: who can do what, per cluster and resource | KUI-new | P1 | gateway (read model) | ui-admin | M6 | M | RESEARCHING | Screen 30. Kafbat only exposes this through 403s. |
| KU-020 | Audit log viewer | KUI-new | P1 | identity | ui-admin | M6 | M | RESEARCHING | Screen 31. Reads the AD-001 sink. |
| KU-021 | Bearer-token API access for non-browser clients (JWKS or introspection) | KUI-new | P2 | gateway, identity | — | M6 | M | RESEARCHING | Mirrors Kafbat's resource-server mode; CSRF-exempt path. |
| KU-022 | Connector plugin config validation requires `CONNECTOR:CREATE` or `CONNECTOR:EDIT` | KUI-new | P1 | gateway | ui-connect | M7 | S | RESEARCHING | RBAC gap: Kafbat's validate endpoint has no permission check and no audit. |
| KU-023 | Extended built-in serdes: ProtobufFile, ProtobufRaw, AvroEmbedded, MessagePack, Struct, MirrorMaker2 heartbeat / offset-sync / checkpoint, `__consumer_offsets` | Kafbat | P1 | message | ui-messages (picker) | M5 | L | RESEARCHING | Split out of SD-001 so M3 stays bounded. Includes CG-009. |
| KU-024 | SSRF-safe outbound URL policy for every configured remote (http/https only, deny link-local and metadata ranges, optional allow-list, no cross-host redirects, upstream bodies never echoed) | KUI-new | P1 | `kui-config`, gateway | — | M8 | M | COMPLETE | Audited 2026-09-04. `UrlPolicy` is applied to every configured remote and is exercised against the nine shipped configuration files. |
| KU-025 | Helm chart, runbooks, production deployment docs | KUI-new | P1 | deployment | — | M8 | M | RESEARCHING | |
| KU-026 | Kafbat environment-variable migration tool (`KAFKA_CLUSTERS_0_*` → KUI keys) | KUI-new | P1 | tools | — | M8 | M | RESEARCHING | — |
| KU-027 | Performance budgets, load tests and recorded benchmarks (`docs/benchmarks/`) | KUI-new | P1 | benchmarks | — | M8 | M | RESEARCHING | First benchmarks are an M3 exit criterion; the regression gate is M8. |
| KU-028 | Dependency vulnerability scanning, SBOM, release process | KUI-new | P1 | ci | — | M8 | M | RESEARCHING | |
| KU-029 | Event-tracking correlation-key grouping (`groups[]` in the track response) | KUI-new | P2 | message | ui-messages | M9 | M | RESEARCHING | Contract reserves the field in M3. |
| KU-030 | Server-side column projection (`flatten=true`) for CSV export of the table view | KUI-new | P3 | message | — | M9 | S | RESEARCHING | Client-side flattening is enough until proven otherwise. |
| KU-031 | Plugin SDK for third-party microfrontends (Option C, web-component boundary) | KUI-new | P2 | — | kernel, shell | M9 | XL | RESEARCHING | ADR after M8. |
| KU-032 | Alerting on lag, offline partitions and capability transitions | KUI-new | P2 | metrics | ui-metrics | M9 | L | RESEARCHING | Research first. |
| KU-033 | Fault-isolation E2E suite: for every service, stop its container and assert the shell, the other features and the fallback panels still work | KUI-new | P0 | e2e | — | M1 | M | REVIEW | Audited 2026-09-04. **Corrected downward from COMPLETE.** The suite covers the cluster service only — 1 of 4 Kafka-facing services — and cannot cover the others, because `topic`, `message` and `consumer` have no `Main`, no client module and no image. |

## Decisions required

The research proposed thirteen defer/reject candidates and left several questions open. The
recorded decisions below are final for the planning phase; reopening one requires new evidence and a
superseding entry (the project's anti-waste rule against reopening settled decisions).

| # | Row(s) | Candidate | Decision | Reason |
| --- | --- | --- | --- | --- |
| DR-1 | MS-007 (Provectus variant) | Groovy script filters | **REJECTED** | Unsandboxed JVM scripting; CEL is Kafbat's successor and the only filter language KUI ships. |
| DR-2 | MS-001 (Provectus variant) | v1 seek API shape (`seekType`/`seekTo`/`seekDirection`) | **REJECTED(superseded)** | Kafbat itself returns "Not supported"; KUI's stream contract carries `seekTo[]` so nothing is lost. Rejected, not deferred: there is no future in which it returns. |
| DR-3 | MT-006 | Prometheus push-gateway / remote-write sinks | **DEFERRED → M9** | Pull-based exposition (MT-005) covers monitoring; push sinks are niche and add outbound-network surface. |
| DR-4 | OD-001 | OpenDataDiscovery exporter | **DEFERRED → M9, no interface slot before then** | External platform integration with no operator value; reserving an abstraction now would be speculative design. |
| DR-5 | NX-002 | In-app survey popup | **REJECTED** | Sends usage data to a third party; consent and telemetry concerns; no operator value. Rejected rather than deferred so nobody re-plans it. |
| DR-6 | NX-004 | Demo mode with fake backend | **DEFERRED → M9** | Marketing aid, not a capability. When it comes, it is a compose file with a seeded cluster, not a fake frontend backend. |
| DR-7 | OT-006 | GitHub release phone-home, installation id | **ACCEPTED as opt-in, default off, M8** | Useful for upgrade nudges (NX-003) but must never contact the network without an explicit `kui.updates.check=true`. |
| DR-8 | AD-003 | AOP entry/exit logger, HTTP trace actuator | **REJECTED** | Replaced by OpenTelemetry spans and structured logs. |
| DR-9 | SD-002 | Custom serde jars via isolated classloader | **DEFERRED → M7** | Needs an ADR on the KUI serde SPI (Scala trait vs Kafbat `serde-api` compatibility); written before M7 implementation, delivered in M7 with the other plugin surfaces. |
| DR-10 | TP-002, SF-002, CG-008, KC-010 (n-gram part) | Lucene full-text n-gram index | **DEFERRED → M9** | Adds a Lucene dependency for a problem substring search solves below ~5k topics. Revisit only with a benchmark on a real large cluster. |
| DR-11 | MC-001 | MCP server | **ACCEPTED, P2, M8** | Kafbat parity feature; tools derive automatically from Tapir endpoints, which are only stable once every service exists (end of M7). |
| DR-12 | ET-002 (transport) | STOMP / WebSocket transport for async tracking | **REJECTED (transport only)** | KUI streams over SSE; the async tracking feature stays P0 in M3. |
| DR-13 | CL-011, TP-019 | Kouncil helper endpoints (`/api/connection`, `is-topic-exist`) | **REJECTED (folded)** | Typed contracts already answer both (cluster DTO field; `GET /topics/{topic}` → 404). |

Rulings on the open questions that affect milestone scope:

| # | Question (source) | Ruling |
| --- | --- | --- |
| DR-14 | Canonical RBAC vocabulary: Kouncil's 33 function names or Kafbat's resource × action matrix? (feature-matrix Q1) | Kafbat's resource × action matrix, verbatim, because it is also the migration path for Kafbat users. Kouncil's UI-managed groups (RB-004) map onto it. |
| DR-15 | Unavailable sidebar entries: disabled links, or clickable to a fallback panel? (DC-H1) | Clickable to the fallback panel; `NotConfigured` hidden; `Forbidden` shown disabled with tooltip. This ruling amends the earlier "disabled links" wording. |
| DR-16 | Smart-filter test execution without RBAC (feature-matrix Q4, api-analysis) | Requires cluster scope and `TOPIC:MESSAGES_READ` (KU-016). |
| DR-17 | Connector plugin validation without RBAC (KC-008 research note) | Requires `CONNECTOR:CREATE` or `CONNECTOR:EDIT` and is audited (KU-022). |
| DR-18 | Async tracking sanity limit: per request or server knob? (feature-matrix Q2) | Both: a server-side hard maximum and a per-request `limit` that cannot exceed it. Same rule as browse. |
| DR-19 | UI-editable masking policies in M3 or later? (kouncil ui-analysis open question) | File-configured masking in M3 (DM-001); UI policies need user groups, so M6 (DM-002). |
| DR-20 | Merge `kui-security-service` into `kui-cluster-service`? (D-8) | **Settled by ADR-004: no.** It has its own capability gate, its own failure signature and functionality that is not cluster topology; merging a slow optional feature into the one Core service would break fault isolation. Owner stays `security`. |
| DR-21 | `kui-config-service` merged into the gateway? | **Settled by ADR-004: dissolved, not merged into one place.** Configuration is three ownerships — cluster configuration to `cluster`, auth and RBAC to `identity`, gateway configuration to the gateway — and `/api/v1/config` is a gateway aggregation over them. Owner columns above were updated accordingly during that review. |

## Counts

Totals by milestone and by state are recomputed when rows change. The initial seed:

| Milestone | Rows | Of which P0 | Of which P1 |
| --- | --- | --- | --- |
| M0 | 15 | 12 | 3 |
| M1 | 22 | 13 | 8 |
| M2 | 8 | 6 | 1 |
| M3 | 28 | 16 | 9 |
| M4 | 7 | 5 | 2 |
| M5 | 22 | 6 | 10 |
| M6 | 17 | 5 | 7 |
| M7 | 34 | 11 | 16 |
| M8 | 20 | 0 | 14 |
| M9 | 10 | 0 | 0 |
| — (rejected) | 4 | 0 | 0 |
| **Total** | **187** (150 from research + 37 KUI-new) | **74** | **70** |

States at seed time: 172 `RESEARCHING`, 7 `DEFERRED`, 4 `REJECTED`, 0 `DESIGNED`. ADR-042
(2026-09-03) rewrote OT-004 and added OT-007 … OT-010, so the totals above count 187 rows.

**After M1's integration pass (2026-09-04):** 154 `RESEARCHING`, 17 `COMPLETE`, 4 `REVIEW`,
1 `BLOCKED`, 7 `DEFERRED`, 4 `REJECTED`.

**After the second integration pass (2026-09-04), which made M3 and M4 reachable:** 133
`RESEARCHING`, 28 `COMPLETE`, 8 `TESTING`, 6 `REVIEW`, 1 `BLOCKED`, 7 `DEFERRED`, 4 `REJECTED`.

The eight `TESTING` rows are the honest middle of this pass and worth reading as a group. Every one
of them is a service that answers, verified against a real broker, with no screen driving it:
`MS-002` (the cursor is signed and round-trips; no "load more" control sends one back), `MS-004`
(the JSON flattener is property-tested; no grid renders it), `MS-009` (serdes decode; no picker),
`MS-010` (a record opens in place; no copy, no raw view, no resend), `CG-003`, `CG-004`, `CG-005`
(delete, offset reset and offset deletion are all served and all refuse a read-only cluster before
touching Kafka; nothing in the interface calls them) and `CG-006` (incremental lag polling answers
and its client is written and tested; nothing calls it).

`REVIEW` here means the feature ships and works, with a specific gap recorded in the row's own
notes rather than in somebody's memory: CL-002's per-cluster capability, CL-009's switcher label,
BR-005's missing per-partition data and OT-009's unproved mid-run store outage. `BLOCKED` is
PA-003, which BR-005 was supposed to deliver and cannot until the contract carries the field.
M0's own rows are not re-stated here; this pass moved M1's scope only.

Every P0 and P1 row has a milestone. Recount after editing rows with:

```
awk -F'|' '/^\| [A-Z]{2}-[0-9]{3} \|/ {gsub(/ /,"",$8); n[$8]++} END {for (m in n) print m, n[m]}' docs/FEATURE_MATRIX.md
```

If this table and the rows disagree, the rows win.
