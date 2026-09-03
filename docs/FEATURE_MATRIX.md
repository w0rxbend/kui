# KUI feature matrix

**Status:** living document, seeded 2026-09-03 from `research/kafbat/feature-matrix.md` (150 rows)
plus the screen inventory in `research/kafbat/ui-analysis.md` §IA.1 and the KUI-only rows the
research flagged. Updated by every task that changes a feature's state (PLAN §46, §50).

This is the product capability list. Every capability found in any reference project is here,
assigned to a milestone, or explicitly `DEFERRED(reason)` / `REJECTED(reason)`. Nothing is
dropped silently (PLAN §44).

## How to read this file

- **ID** — stable identifier `AREA-NNN`. Numbers never change; a removed row keeps its ID with
  state `REJECTED`. Research rows keep their research number (`CL-1` became `CL-001`).
- **Source** — which reference has the behavior: `Kafbat`, `Provectus`, `Kouncil`, or `KUI-new`
  (no reference has it; KUI adds it because of PLAN §2 or a research finding).
- **Priority** — `P0` core (must exist before parity can be claimed for that area), `P1` parity
  (needed to claim the union of the three references), `P2` valuable, `P3` marginal.
- **Owner** — the backend bounded context from PLAN §15 (short names: `gateway`, `cluster`,
  `topic`, `message`, `consumer`, `schema`, `connect`, `ksql`, `security`, `identity`,
  `metrics`, `config`). `—` means frontend-only.
- **MFE** — the microfrontend from PLAN §21 (`shell`, `kernel`, `ui-clusters`, `ui-topics`,
  `ui-messages`, `ui-consumers`, `ui-schemas`, `ui-connect`, `ui-ksql`, `ui-security`,
  `ui-metrics`, `ui-admin`). `—` means backend-only.
- **Milestone** — `M0`..`M9` from `docs/ROADMAP.md`. `—` only for rejected rows.
- **Cx** — complexity for one implementing agent including tests: `S` (<2 d), `M` (2–5 d),
  `L` (1–2 w), `XL` (>2 w).
- **State** — PLAN §44:
  `NOT_RESEARCHED → RESEARCHING → DESIGNED → PLANNED → IMPLEMENTING → TESTING → REVIEW → COMPLETE`,
  plus `BLOCKED` and `DEFERRED(reason)` / `REJECTED(reason)`.
  A row becomes `DESIGNED` only when the ADRs and domain model it depends on are Accepted
  (grooming step G3). As of 2026-09-03 no ADR is Accepted, so every researched row is
  `RESEARCHING` even where the research report proposed a design.

Behavior descriptions, edge cases and source citations are deliberately not repeated here; they
live in the research reports (`research/kafbat/feature-matrix.md` row of the same number,
`research/kafbat/ui-analysis.md`, `research/kouncil/ui-analysis.md`,
`research/kafbat/api-analysis.md`).

## Clusters (CL)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CL-001 | List clusters with status, counts, throughput, `lastError`, `readOnly` | Kafbat, Provectus, Kouncil | P0 | cluster (+gateway aggregation) | shell, ui-clusters | M1 | M | RESEARCHING | Gateway returns configured clusters from cached config with `status: unavailable` when the cluster service is down (partial by design). |
| CL-002 | Per-cluster feature flags (SR, Connect, ksql, topic deletion, ACL view/edit, quotas, graphs) | Kafbat, Provectus | P0 | gateway (registry), fed by each service | kernel | M1 | M | RESEARCHING | Feeds `NotConfigured` in the capability registry (DC-H2). Registry infrastructure itself is KU-001 (M0). |
| CL-003 | Cluster stats (dashboard numbers) served from a refreshed cache | Kafbat, Provectus | P0 | cluster | ui-clusters | M1 | M | RESEARCHING | Stats stay servable while the cluster is momentarily unreachable. |
| CL-004 | Cluster-level aggregated metrics (JMX/Prometheus) | Kafbat, Provectus | P1 | metrics | ui-clusters | M8 | M | RESEARCHING | Dashboard cell shows `—` until M8. |
| CL-005 | Force statistics cache refresh | Kafbat, Provectus | P1 | cluster | ui-clusters | M1 | S | RESEARCHING | `POST /clusters/{id}/refresh`, 202 Accepted. |
| CL-006 | Dynamic cluster CRUD from the UI, persisted, with connection test | Kouncil | P1 | cluster | ui-admin | M8 | L | RESEARCHING | Merged with the config wizard (CW-002..005). Persists to the `cluster/<clusterId>` keys of `__kui_config` through OT-004 (ADR-042), which lands in M1. Owner is `cluster`, not a config service: ADR-004 dissolved `kui-config-service`. |
| CL-007 | Typed cluster security config (SASL/SSL/SCRAM/IAM/OAUTHBEARER) with `properties` escape hatch | Kafbat, Provectus, Kouncil | P0 | cluster (typed config in `kui-config`) | ui-admin (form in M8) | M1 | M | RESEARCHING | Decision D-7 / ADR-022. JAAS strings generated from typed fields, never accepted verbatim. |
| CL-008 | Failover across multiple SR / Connect / ksql URLs | Kafbat, Provectus | P2 | schema, connect, ksql (shared lib) | — | M7 | M | RESEARCHING | |
| CL-009 | Per-cluster colour tag and status dot in navigation | Kafbat | P2 | — | shell | M1 | S | RESEARCHING | Colour stored client-side (`LocalPrefs`). |
| CL-010 | Favourites: pin topics and groups to the top of lists | Kouncil | P2 | — | kernel | M2 | S | RESEARCHING | localStorage, keyed by cluster + name. |
| CL-011 | Broker-address lookup helper endpoint (`/api/connection`) | Kouncil | P3 | cluster | — | — | S | REJECTED(folded into CL-001) | CEO decision DR-13. The first broker address is a field of the cluster DTO. |

## Brokers (BR)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| BR-001 | List brokers with rack, leader counts, skew %, throughput | Kafbat, Provectus, Kouncil | P0 | cluster | ui-clusters | M1 | M | RESEARCHING | Throughput columns blank until M8 (metrics). |
| BR-002 | Broker configs with source, sensitivity, read-only flag, synonyms | Kafbat, Provectus, Kouncil | P0 | cluster | ui-clusters | M1 | S | RESEARCHING | Sensitive values redacted server-side. |
| BR-003 | Update a single broker config (inline edit) | Kafbat, Provectus | P1 | cluster | ui-clusters | M5 | S | RESEARCHING | Audited; blocked in read-only mode (RB-005). |
| BR-004 | Per-broker metrics (JMX/Prometheus), Kouncil OS metrics | Kafbat, Provectus, Kouncil | P1 | metrics | ui-clusters (metrics tab via FeaturePanel) | M8 | M | RESEARCHING | Broker page is a partial aggregation: metrics tab falls back independently. |
| BR-005 | Log dirs per broker, per topic-partition size and lag | Kafbat, Provectus | P1 | cluster | ui-clusters | M1 | M | RESEARCHING | |
| BR-006 | Move a partition replica to another log dir | Kafbat, Provectus | P2 | cluster | ui-clusters | M5 | S | RESEARCHING | |
| BR-007 | Brokers CSV export | Kafbat | P2 | cluster | ui-clusters | M5 | S | RESEARCHING | Content negotiation (`Accept: text/csv`), not a `/csv` path. |

## Topics (TP)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| TP-001 | Topic list: paged, sorted, searched, internal-topic toggle | Kafbat, Provectus, Kouncil | P0 | topic | ui-topics | M2 | M | RESEARCHING | Pagination after RBAC filtering; response carries `page.totalItems`. |
| TP-002 | Full-text n-gram topic search (Lucene) | Kafbat | P2 | topic | ui-topics | M9 | L | DEFERRED(no evidence substring search is insufficient) | CEO decision DR-10. Revisit with a benchmark at 5k+ topics. |
| TP-003 | Topic details (partitions, replicas, ISR, segments, cleanup policy, throughput) | Kafbat, Provectus, Kouncil | P0 | topic | ui-topics | M2 | M | RESEARCHING | Audit-topic guard applies from M6 (AD-002). |
| TP-004 | Topic config list | Kafbat, Provectus | P0 | topic | ui-topics | M2 | S | RESEARCHING | |
| TP-005 | Create topic (name, partitions, RF, configs, retention quick buttons) | Kafbat, Provectus, Kouncil | P0 | topic | ui-topics | M5 | M | RESEARCHING | |
| TP-006 | Update topic configs (diff + incremental alter) | Kafbat, Provectus | P0 | topic | ui-topics | M5 | M | RESEARCHING | |
| TP-007 | Delete topic (gated by broker `delete.topic.enable`) | Kafbat, Provectus, Kouncil | P0 | topic | ui-topics | M5 | S | RESEARCHING | |
| TP-008 | Recreate topic (delete + create with same config) | Kafbat, Provectus | P1 | topic | ui-topics | M5 | M | RESEARCHING | |
| TP-009 | Clone topic to a new name | Kafbat, Provectus | P1 | topic | ui-topics | M5 | S | RESEARCHING | Body `{newName}` instead of query param. |
| TP-010 | Increase partition count | Kafbat, Provectus, Kouncil | P0 | topic | ui-topics | M5 | S | RESEARCHING | Decrease rejected with a typed error. |
| TP-011 | Change replication factor (reassignment plan) | Kafbat, Provectus | P1 | topic | ui-topics | M5 | L | RESEARCHING | |
| TP-012 | Topic analysis (full scan: counts, sizes, percentiles, HLL uniques, hourly histogram) | Kafbat, Provectus | P1 | topic | ui-topics (Statistics tab) | M5 | L | RESEARCHING | Allowed in read-only mode. Progress over SSE is a later extension. |
| TP-013 | Active producers (idempotent / transactional state per partition) | Kafbat, Provectus | P1 | topic | ui-topics | M5 | S | RESEARCHING | |
| TP-014 | Topic → related connectors tab | Kafbat | P2 | connect (queried by gateway) | ui-topics (FeaturePanel) | M7 | S | RESEARCHING | Section of the topic overview aggregation (KU-011). |
| TP-015 | Topic → related consumer groups tab | Kafbat, Provectus | P0 | consumer | ui-topics (FeaturePanel) | M4 | S | RESEARCHING | |
| TP-016 | Topic → ACLs tab | Kafbat | P2 | security | ui-topics (FeaturePanel) | M7 | S | RESEARCHING | Needs Topic VIEW and ACL VIEW. |
| TP-017 | Topics CSV export | Kafbat | P2 | topic | ui-topics | M5 | S | RESEARCHING | Content negotiation. |
| TP-018 | Batch actions on the topic list (multi-select delete / purge / copy) | Kafbat, Provectus | P1 | — (client composition) | ui-topics | M5 | M | RESEARCHING | Partial failure reported per topic. |
| TP-019 | Topic existence check helper endpoint | Kouncil | P3 | topic | — | — | S | REJECTED(folded into TP-003) | CEO decision DR-13. Forms use `GET /topics/{topic}` and map 404. |

## Partitions (PA)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| PA-001 | Partition table on the topic overview (leader, replicas, ISR, offsets, count, size) | Kafbat, Provectus, Kouncil | P0 | topic | ui-topics | M2 | S | RESEARCHING | Size column needs BR-005 data. |
| PA-002 | Per-partition analysis statistics | Kafbat, Provectus | P1 | topic | ui-topics | M5 | — | RESEARCHING | Delivered by TP-012; tracked separately for the UI table. |
| PA-003 | Per-partition log-dir sizes | Kafbat, Provectus | P1 | cluster | ui-clusters | M1 | — | RESEARCHING | Delivered by BR-005. |
| PA-004 | Partition increase / replica move | Kafbat, Provectus, Kouncil | P0 | topic, cluster | ui-topics, ui-clusters | M5 | — | RESEARCHING | Cross-reference of TP-010 and BR-006. |

## Messages: browsing (MS)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| MS-001 | Stream messages over SSE with polling modes (from/to offset, from/to timestamp, latest, earliest, tailing) | Kafbat (v2), Provectus (v1) | P0 | message | ui-messages | M3 | XL | RESEARCHING | KUI implements the Kafbat v2 shape plus per-partition `seekTo[]`; v1 shape rejected (DR-2). Highest-risk path, PLAN §22. |
| MS-002 | Cursor-based next page over the stream | Kafbat | P0 | message | ui-messages | M3 | M | RESEARCHING | Cursor is self-describing and signed (KU-015), not a server cache id. |
| MS-003 | Table-style browsing with per-partition, newest-first offset paging | Kouncil | P0 | message | ui-messages | M3 | L | RESEARCHING | Second, non-streaming read path (D-1). Kouncil-defining UX. |
| MS-004 | JSON column flattening grid (headers / key / value columns, depth and row caps) | Kouncil | P0 | — (client-side) | ui-messages | M3 | L | RESEARCHING | Kouncil limits are the defaults (D-2, DC-H8). Same stream as the list view (DC-H4). |
| MS-005 | Live / tailing mode with client throttle and play/pause | Kafbat, Provectus, Kouncil | P0 | message | ui-messages | M3 | M | RESEARCHING | Stops cleanly when the capability flips to Unavailable. |
| MS-006 | Simple string-contains filter over key, value, headers | Kafbat, Provectus, Kouncil | P0 | message | ui-messages | M3 | S | RESEARCHING | |
| MS-007 | Smart filters: CEL scripts, registration with TTL, dry-run test, saved filters | Kafbat (CEL), Provectus (Groovy) | P1 | message | ui-messages | M3 | L | RESEARCHING | CEL only; Groovy rejected (DR-1, D-3). Test endpoint RBAC closed by KU-016. |
| MS-008 | Purge messages (delete records per partition) | Kafbat, Provectus | P1 | message | ui-messages, ui-topics | M3 | S | RESEARCHING | Audited from M5. |
| MS-009 | Serde suggestions per topic for serialize / deserialize | Kafbat, Provectus | P0 | message | ui-messages | M3 | S | RESEARCHING | Lists only serdes that do not need SR when SR is Unavailable. |
| MS-010 | Message detail view (key / content / headers, copy, pre-masking raw, resend entry point) | Kafbat, Provectus, Kouncil | P0 | — | ui-messages | M3 | M | RESEARCHING | Right-hand drawer (DC-H9). |
| MS-011 | Export rendered messages to CSV / JSON | Kafbat | P2 | — | ui-messages | M3 | S | RESEARCHING | Client-side. |
| MS-012 | Decode Spring DLT / retry numeric headers | Kouncil | P2 | message | — | M3 | S | RESEARCHING | |
| MS-013 | Polling throttle (bytes/s per cluster) with `consumed` stats event | Kafbat, Provectus | P1 | message | — | M3 | S | RESEARCHING | Protects brokers from UI load. |
| MS-014 | Relative timestamps and user timezone in message tables | Kafbat | P3 | — | kernel | M3 | S | RESEARCHING | Timezone setting lives in KU-012. |

## Messages: production (MP)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| MP-001 | Produce a message (key, value, headers, partition, serde selection, keep-contents) | Kafbat, Provectus, Kouncil | P0 | message | ui-messages | M3 | M | RESEARCHING | Produce drawer. Audited from M5. |
| MP-002 | Bulk send with `{{count}}`, `{{timestamp}}`, `{{uuid}}` placeholders | Kouncil | P1 | message | ui-messages | M3 | S | RESEARCHING | `count` parameter on MP-001. |
| MP-003 | Resend (copy) an offset range between topics with header filtering | Kouncil | P1 | message | ui-messages | M3 | M | RESEARCHING | Raw byte copy; range validated against partition bounds. |
| MP-004 | Produce with per-serde parameters and schema validation before send | Kafbat | P1 | message | ui-messages | M3 | M | RESEARCHING | Uses SD-004. |

## Consumer groups (CG)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CG-001 | List consumer groups: paged, sorted, searched, state filter | Kafbat, Provectus, Kouncil | P0 | consumer | ui-consumers | M4 | M | RESEARCHING | Describe batched with bounded concurrency (OT-001). |
| CG-002 | Group details: members, assignments, offsets, lag, pace | Kafbat, Provectus, Kouncil | P0 | consumer | ui-consumers | M4 | M | RESEARCHING | Kouncil pace column and last-seen assignment cache kept (DC-H10). |
| CG-003 | Delete group | Kafbat, Provectus, Kouncil | P0 | consumer | ui-consumers | M4 | S | RESEARCHING | |
| CG-004 | Reset offsets wizard (earliest / latest / timestamp / per-partition offset) | Kafbat, Provectus, Kouncil | P0 | consumer | ui-consumers | M4 | M | RESEARCHING | Refused for active groups. |
| CG-005 | Delete committed offsets for one topic | Kafbat | P1 | consumer | ui-consumers | M4 | S | RESEARCHING | |
| CG-006 | Incremental lag polling and lag trend sparkline | Kafbat | P1 | consumer | ui-consumers | M4 | M | RESEARCHING | Poll interval driven by the Degraded reason payload (KU-003). |
| CG-007 | Consumer groups CSV export | Kafbat | P2 | consumer | ui-consumers | M5 | S | RESEARCHING | |
| CG-008 | Consumer group full-text n-gram search | Kafbat | P2 | consumer | — | M9 | S | DEFERRED(follows TP-002) | CEO decision DR-10. |
| CG-009 | `__consumer_offsets` decoding serde | Kafbat, Provectus | P2 | message | — | M5 | M | RESEARCHING | Part of the extended serde set (KU-023). |

## Schema registry (SR)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| SR-001 | List subjects: paged, sorted, searched | Kafbat, Provectus, Kouncil | P0 | schema | ui-schemas | M7 | M | RESEARCHING | Subject sort pages before hydration (cheap path). |
| SR-002 | Create schema / new version (Avro, JSON Schema, Protobuf) | Kafbat, Provectus, Kouncil | P0 | schema | ui-schemas | M7 | M | RESEARCHING | |
| SR-003 | Get latest / by version / all versions | Kafbat, Provectus, Kouncil | P0 | schema | ui-schemas | M7 | S | RESEARCHING | |
| SR-004 | Delete subject / latest / by version (soft and hard) | Kafbat, Provectus, Kouncil | P0 | schema | ui-schemas | M7 | S | RESEARCHING | |
| SR-005 | Compatibility: global get/set, per-subject set, compatibility check | Kafbat, Provectus, Kouncil | P0 | schema | ui-schemas | M7 | S | RESEARCHING | |
| SR-006 | Version diff viewer | Kafbat, Provectus | P1 | — | ui-schemas | M7 | M | RESEARCHING | Kernel `DiffViewer`. |
| SR-007 | SR auth: basic, OAuth client-credentials, SSL stores | Kafbat, Provectus, Kouncil | P1 | schema | ui-admin (form in M8) | M7 | M | RESEARCHING | Serde path in M3 uses the same client config (ADR-014). |
| SR-008 | Topic → subject suffix convention | Kafbat | P2 | schema | — | M7 | S | RESEARCHING | |
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
| MT-006 | Push-gateway / remote-write sinks | Kafbat | P3 | metrics | — | M9 | M | DEFERRED(pull-based MT-005 covers monitoring) | CEO decision DR-3. |
| MT-007 | KUI self-metrics (JVM, HTTP, per-service) | Kafbat, Provectus | P0 | all services | — | M0 | S | RESEARCHING | otel4s, PLAN §30. |

## Config wizard and dynamic config (CW)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CW-001 | App info: build info, enabled features | Kafbat, Provectus, Kouncil | P1 | gateway | shell | M0 | S | RESEARCHING | `GET /info`. Release check is OT-006 (opt-in). |
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
| AU-004 | CSRF protection for the SPA | Kouncil | P0 | gateway | — | M6 | S | RESEARCHING | `X-KUI-CSRF` header + `Sec-Fetch-Site`, `POST` logout (ADR-019). |
| AU-005 | User menu: logout, theme auto/light/dark, timezone | Kafbat | P1 | — | shell | M1 | S | RESEARCHING | Theme tokens exist from M0; logout item appears in M6. |

## Authorization: RBAC and read-only (RB)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| RB-001 | Role model: subjects × clusters × resource pattern × actions, with action dependencies | Kafbat, Provectus | P0 | identity (evaluation) + gateway (enforcement) | ui-admin (view) | M6 | XL | RESEARCHING | Kafbat resource × action matrix is the canonical vocabulary (DR-14). Pure evaluation in `kui-security-core`. |
| RB-002 | Authority extractors: GitHub orgs/teams, Google hd, Cognito groups, OIDC claim, LDAP, AD | Kafbat, Provectus | P1 | identity | — | M6 | M | RESEARCHING | |
| RB-003 | Current user info and flattened permissions for UI gating | Kafbat, Provectus | P0 | gateway | kernel | M6 | S | RESEARCHING | `GET /auth/me`; `ActionPermissionWrapper` merges RBAC and capability state (DC-H5). |
| RB-004 | UI-managed user groups and function-permission matrix, persisted | Kouncil | P1 | identity | ui-admin | M6 | L | RESEARCHING | Persisted role store behind the same evaluation API; file wins, UI adds (D-5). Stored under the `rbac/roles` key of `__kui_config` through OT-004 (ADR-042), which is already available from M1. |
| RB-005 | Read-only cluster mode | Kafbat, Provectus | P0 | gateway (policy) | kernel | M5 | S | RESEARCHING | Enforced per operation via a `Mutation` marker in the domain, not by URL regex (security research §5). |

## Audit (AD)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| AD-001 | Audit log of mutating operations to console and/or a Kafka topic (`ALL` / `ALTER_ONLY`) | Kafbat, Provectus | P1 | identity (sink), fed by gateway | ui-admin | M5 | M | RESEARCHING | M5 ships the record shape and sinks with an anonymous principal; M6 adds the user. |
| AD-002 | Audit topic self-protection (browsing it requires `AUDIT:VIEW`) | Kafbat, Provectus | P1 | gateway | — | M6 | S | RESEARCHING | |
| AD-003 | Entry/exit AOP logger and HTTP trace actuator | Kouncil | P3 | — | — | — | S | REJECTED(replaced by OpenTelemetry tracing, PLAN §30) | CEO decision DR-8. |

## Data masking (DM)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| DM-001 | Config-driven masking (`REMOVE` / `MASK` / `REPLACE`, field paths, topic patterns) | Kafbat, Provectus | P1 | message | — | M3 | M | RESEARCHING | Applied after deserialization, before leaving the service. |
| DM-002 | UI-managed masking policies bound to user groups (`ALL` / `FIRST_5` / `LAST_5`) | Kouncil | P1 | message (apply) + identity (policy store) | ui-admin | M6 | L | RESEARCHING | Same engine as DM-001 with caller-group scoping (D-6). Needs identity, so M6 not M5. Stored under `masking/<clusterId>` in `__kui_config` (ADR-042, ADR-023). |

## Serdes (SD)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| SD-001 | Core built-in serdes: String, Schema Registry (Avro / JSON Schema / Protobuf), Int32/64, UInt32/64, Base64, Hex, UUID; auto-detection by magic byte | Kafbat, Provectus, Kouncil | P0 | message | ui-messages (picker) | M3 | XL | RESEARCHING | The P1 serdes of the research row are KU-023 (M5). |
| SD-002 | Custom serde plugin loading (jar, isolated classloader) | Kafbat, Provectus | P2 | message | — | M7 | L | DEFERRED(needs a serde SPI ADR) | CEO decision DR-9. ADR written in M7 grooming. |
| SD-003 | Default key/value serde per cluster and resolution order | Kafbat, Provectus | P0 | message | — | M3 | S | RESEARCHING | |
| SD-004 | Schema → JSON Schema conversion for produce-form validation | Kafbat, Provectus | P1 | message | ui-messages | M3 | M | RESEARCHING | |

## Search and filtering, cross-cutting (SF)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| SF-001 | Case-insensitive substring search on every list | Kafbat, Provectus, Kouncil | P0 | each service | kernel (search box) | M2 | S | RESEARCHING | `q` parameter; URL-synced. |
| SF-002 | Full-text n-gram index across topics, groups, schemas, connectors, ACLs | Kafbat | P2 | each service (shared index lib) | — | M9 | L | DEFERRED(follows TP-002) | CEO decision DR-10. |
| SF-003 | Virtualized, sortable data table with favourites pinning | Kafbat, Kouncil | P0 | — | kernel | M2 | M | RESEARCHING | Kernel-native windowing (frontend research §5). A non-virtualized `DataTable` ships in M0 (NX-007). |

## Event tracking (ET)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| ET-001 | Track a business event across topics by header or value match within a time window (sync, capped) | Kouncil | P0 | message | ui-messages (Track page) | M3 | L | RESEARCHING | Bounded multi-topic scan with the same budgets as browse (DC-H11). Screen 15. |
| ET-002 | Track asynchronously with results streamed as found | Kouncil | P0 | message | ui-messages | M3 | M | RESEARCHING | SSE, not STOMP (DR-12, D-4). Cancel on disconnect. |
| ET-003 | Track filter UI (field / operator / value, topic multi-select, time range) and results grid | Kouncil | P0 | — | ui-messages | M3 | M | RESEARCHING | Reuses the message grid and one-click "track this header value" from MS-010. |

## Notifications and miscellaneous UX (NX)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| NX-001 | Server push notifications (forced logout on permission change, no clusters defined) | Kouncil | P2 | gateway (SSE bus) | kernel (notification bus) | M6 | M | RESEARCHING | Rides the capability SSE channel (KU-001). |
| NX-002 | In-app survey popup | Kouncil | P3 | — | — | — | S | REJECTED(telemetry and consent concerns, no operator value) | CEO decision DR-5. |
| NX-003 | Version and latest-release banner | Kafbat, Provectus | P2 | gateway | shell | M8 | S | RESEARCHING | Release lookup only when OT-006 is opted in. |
| NX-004 | Demo mode with an in-memory fake backend | Kouncil | P3 | — | shell | M9 | M | DEFERRED(marketing aid, not a product capability) | CEO decision DR-6. |
| NX-005 | Custom context path / base path | Kafbat, Provectus, Kouncil | P1 | gateway | shell | M0 | S | RESEARCHING | Reverse-proxy deployments. Part of static asset serving. |
| NX-006 | CORS configuration | Kafbat, Provectus | P1 | gateway | — | M0 | S | RESEARCHING | Off by default; explicit origin list when enabled. |
| NX-007 | Shared UI primitives: confirmation modals, toasts, breadcrumbs, empty states, drawers, tabs, forms | Kafbat, Provectus, Kouncil | P0 | — | kernel | M0 | M | RESEARCHING | Kernel design system on KUI's own token set (UI-002; B-001 decided around, not waited on). Inventory in `research/kafbat/ui-analysis.md` §IA.4. |

## MCP (MC)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| MC-001 | MCP server exposing API operations as tools, derived from Tapir endpoints | Kafbat | P2 | gateway | — | M8 | L | RESEARCHING | CEO decision DR-11. Read/write classification by method; read-only and RBAC pass through. |

## Data catalog (OD)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| OD-001 | OpenDataDiscovery exporter (topics, connectors, schema fields, lineage) | Kafbat, Provectus | P3 | topic, connect (exporter plugin) | — | M9 | L | DEFERRED(external platform integration, no core value) | CEO decision DR-4. No interface slot is reserved before M9. |

## Other cross-cutting knobs (OT)

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| OT-001 | Admin-client timeout, batching and concurrency knobs | Kafbat | P1 | cluster, topic, consumer | — | M1 | S | RESEARCHING | Shared `kui-kafka` settings. |
| OT-002 | CSV formatting knobs | Kafbat | P2 | shared lib | — | M5 | S | RESEARCHING | With the first CSV export. |
| OT-003 | Per-cluster consumer / producer / admin property overrides | Kafbat, Provectus | P0 | `libs/config` + cluster | — | M1 | S | RESEARCHING | The escape hatch of CL-007. |
| OT-004 | Kafka-backed metadata store: `ConfigStore[F]` over internal compacted topics (`__kui_config`, `__kui_files`), replay + tail, optimistic `version`, read-your-writes | KUI-new (replaces Kouncil's D-10) | P0 | cluster, identity | — | M1 | L | RESEARCHING | ADR-042. Replaces relational persistence: no database, ever. Core store is M1 because clusters become registrable at runtime there; UI-managed roles/groups (RB-004), masking policies (DM-002) and the wizard (CW-002 … CW-005) are consumers of it and keep their own milestones. File adapter stays for dev, bootstrap and read-only, and file-only mode must keep working (M0 ships the file adapter only). Closed `TECH_DEBT.md` TD-014. |
| OT-007 | Store topic creation and validation: create `__kui_*` if missing, validate if present, fail fast with the setting, expected and found values | KUI-new | P0 | cluster, identity | — | M1 | S | RESEARCHING | ADR-042. Never rewrites operator topic settings silently. |
| OT-008 | Envelope encryption of secret fields at rest (AES-GCM, `keyId` in the envelope) and key rotation | KUI-new | P0 | cluster, identity | — | M1 | M | RESEARCHING | ADR-042 §4; `research/scala/security-research.md` §5. Key from `kui.store.encryptionKey`, never stored. Rotation writes new records under a new `keyId` while old ones stay readable. |
| OT-009 | Store health as a capability: store unreachable means last known state, `Degraded(reason)`, writes rejected | KUI-new | P0 | cluster, identity (+gateway registry) | kernel | M1 | S | RESEARCHING | ADR-042 §8 folded through ADR-039; rendered by ADR-032. |
| OT-010 | Operator guidance for the store: sizing, ACLs, encryption key handling, backup/restore, file-to-Kafka migration | KUI-new | P1 | — | — | M1 | S | RESEARCHING | `docs/operations/metadata-store.md`. Ships with OT-004; revisited when RB-004 and DM-002 add sections. |
| OT-005 | Uniform error envelope with stable `KUI-*` codes and correlation id | Kouncil, Kafbat | P0 | all | kernel | M0 | S | RESEARCHING | PLAN §26; code list in `research/kafbat/api-analysis.md`. |
| OT-006 | Release check phone-home and installation id | Kafbat, Provectus, Kouncil | P3 | gateway | — | M8 | S | RESEARCHING | Opt-in only, default off (CEO decision DR-7). |

## KUI-only capabilities (KU)

Rows no reference has. They come from PLAN §2 (fault isolation, streaming-first, deployable two
ways), from the IA proposal in `research/kafbat/ui-analysis.md`, and from gaps the API and
security research flagged.

| ID | Feature | Source | Priority | Owner | MFE | Milestone | Cx | State | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| KU-001 | Capability registry: readiness polling, circuit-breaker state, `GET /capabilities`, `GET /capabilities/stream` (SSE) | KUI-new | P0 | gateway | kernel (`CapabilityState`) | M0 | M | RESEARCHING | PLAN §16. |
| KU-002 | Capability-driven navigation: `FeatureGate`, feature fallback panel with reason / since / retry, capability banner, `NotConfigured` hidden, `Unavailable` shown and clickable | KUI-new | P0 | — | shell, kernel | M0 | M | RESEARCHING | DC-H1 and DC-H2 accepted (DR-15). |
| KU-003 | Structured degraded reason payload (`code`, `message`, `suggestedPollIntervalMs`, `p95Ms`) | KUI-new | P0 | gateway | kernel | M0 | S | RESEARCHING | DC-H7. Contract, so decided before M1. |
| KU-004 | `KuiFeature` contract and lazy feature loader (Scala.js module splitting, ADR-012) | KUI-new | P0 | — | kernel, shell | M0 | M | RESEARCHING | Option B with Option A as a config fallback. Bundle-shape CI check included. |
| KU-005 | All-in-one deployment: gateway plus every service in one JVM through the same contracts | KUI-new | P0 | apps/allinone | — | M0 | M | RESEARCHING | PLAN §2.6, ADR-005. |
| KU-006 | Per-service `/health/live`, `/health/ready`, `/capabilities`; gateway OpenAPI aggregation | KUI-new | P0 | all, gateway | — | M0 | S | RESEARCHING | |
| KU-007 | Typed, validated configuration with `Secret[A]` redaction in logs, traces, errors and API responses | KUI-new | P0 | `kui-config` | — | M0 | M | RESEARCHING | PLAN §23–24, ADR-013. |
| KU-008 | Docker Compose dev environment and CI pipeline (compile `-Werror`, format, tests, link, OpenAPI diff) | KUI-new | P0 | deployment | — | M0 | M | RESEARCHING | PLAN §49. |
| KU-009 | `403`, `404`, feature fallback pages and the single full-screen "cannot reach gateway" state | KUI-new | P0 | — | shell | M0 | S | RESEARCHING | Screen 33. |
| KU-010 | Stale data stays on screen (greyed, timestamped, actions disabled) when a feature becomes Unavailable | KUI-new | P1 | — | kernel (`StaleDataOverlay`, `QueryCache`) | M1 | M | RESEARCHING | DC-H3, decided before M1 because every feature state depends on it. |
| KU-011 | Partial aggregation endpoints with per-section status: cluster dashboard (M1), topic overview (M2, sections added in M4/M7), consumer group page (M4), connects with stats (M7) | KUI-new | P0 | gateway | ui-clusters, ui-topics, ui-consumers | M1 | M | RESEARCHING | PLAN §16.3. Milestone is when the first one ships. |
| KU-012 | User settings page: theme, timezone, refresh rate, table density | KUI-new | P1 | — | shell | M1 | S | RESEARCHING | Screen 32. Stored in `LocalPrefs`. |
| KU-013 | Cross-feature `FeaturePanel` slot (topic → consumers tab, broker → metrics tab) keyed by feature id, never by import | KUI-new | P1 | — | kernel | M2 | M | RESEARCHING | DC-H6. First consumer of the slot is TP-015 in M4. |
| KU-014 | SSE envelope with named events (`phase`, `message`, `consumed`, `done`, `error`, `heartbeat`) and `id:` for `Last-Event-ID` reconnect; kernel `SseStream` wrapper | KUI-new | P0 | message, gateway | kernel | M3 | M | RESEARCHING | Kernel wrapper is exercised by KU-001 in M0; the full envelope lands with MS-001. |
| KU-015 | Self-describing signed browse cursor (survives gateway restarts and multiple replicas) | KUI-new | P0 | message | — | M3 | M | RESEARCHING | Replaces Kafbat's process-local cursor cache. |
| KU-016 | Smart-filter test execution is cluster-scoped and requires `TOPIC:MESSAGES_READ` | KUI-new | P1 | gateway | ui-messages | M3 | S | RESEARCHING | RBAC gap: Kafbat lets any authenticated user execute arbitrary CEL. Enforced from M6 when RBAC exists; the endpoint shape is fixed in M3. |
| KU-017 | Signed principal header gateway → services (request-bound, short expiry, per-service audience); services reject unsigned requests except in all-in-one mode | KUI-new | P0 | gateway, all services | — | M6 | M | RESEARCHING | PLAN §31; ADR-020. |
| KU-018 | Pluggable server-side session store (in-memory default, shared store adapter) | KUI-new | P1 | gateway | — | M6 | M | RESEARCHING | Multi-replica gateway. |
| KU-019 | RBAC view: who can do what, per cluster and resource | KUI-new | P1 | gateway (read model) | ui-admin | M6 | M | RESEARCHING | Screen 30. Kafbat only exposes this through 403s. |
| KU-020 | Audit log viewer | KUI-new | P1 | identity | ui-admin | M6 | M | RESEARCHING | Screen 31. Reads the AD-001 sink. |
| KU-021 | Bearer-token API access for non-browser clients (JWKS or introspection) | KUI-new | P2 | gateway, identity | — | M6 | M | RESEARCHING | Mirrors Kafbat's resource-server mode; CSRF-exempt path. |
| KU-022 | Connector plugin config validation requires `CONNECTOR:CREATE` or `CONNECTOR:EDIT` | KUI-new | P1 | gateway | ui-connect | M7 | S | RESEARCHING | RBAC gap: Kafbat's validate endpoint has no permission check and no audit. |
| KU-023 | Extended built-in serdes: ProtobufFile, ProtobufRaw, AvroEmbedded, MessagePack, Struct, MirrorMaker2 heartbeat / offset-sync / checkpoint, `__consumer_offsets` | Kafbat | P1 | message | ui-messages (picker) | M5 | L | RESEARCHING | Split out of SD-001 so M3 stays bounded. Includes CG-009. |
| KU-024 | SSRF-safe outbound URL policy for every configured remote (http/https only, deny link-local and metadata ranges, optional allow-list, no cross-host redirects, upstream bodies never echoed) | KUI-new | P1 | `kui-config`, gateway | — | M8 | M | RESEARCHING | Security research §5. Typed URL parsing exists from M1; the policy becomes enforceable when config is UI-editable. |
| KU-025 | Helm chart, runbooks, production deployment docs | KUI-new | P1 | deployment | — | M8 | M | RESEARCHING | |
| KU-026 | Kafbat environment-variable migration tool (`KAFKA_CLUSTERS_0_*` → KUI keys) | KUI-new | P1 | tools | — | M8 | M | RESEARCHING | PLAN §24. |
| KU-027 | Performance budgets, load tests and recorded benchmarks (`docs/benchmarks/`) | KUI-new | P1 | benchmarks | — | M8 | M | RESEARCHING | First benchmarks are an M3 exit criterion; the regression gate is M8. |
| KU-028 | Dependency vulnerability scanning, SBOM, release process | KUI-new | P1 | ci | — | M8 | M | RESEARCHING | |
| KU-029 | Event-tracking correlation-key grouping (`groups[]` in the track response) | KUI-new | P2 | message | ui-messages | M9 | M | RESEARCHING | Contract reserves the field in M3. |
| KU-030 | Server-side column projection (`flatten=true`) for CSV export of the table view | KUI-new | P3 | message | — | M9 | S | RESEARCHING | Client-side flattening is enough until proven otherwise. |
| KU-031 | Plugin SDK for third-party microfrontends (Option C, web-component boundary) | KUI-new | P2 | — | kernel, shell | M9 | XL | RESEARCHING | PLAN §21; ADR after M8. |
| KU-032 | Alerting on lag, offline partitions and capability transitions | KUI-new | P2 | metrics | ui-metrics | M9 | L | RESEARCHING | Research first. |
| KU-033 | Fault-isolation E2E suite: for every service, stop its container and assert the shell, the other features and the fallback panels still work | KUI-new | P0 | e2e | — | M1 | M | RESEARCHING | First scenario in M1 (cluster service down); one new scenario per service afterwards. Exit criterion of every milestone. |

## Decisions required

The research proposed thirteen defer/reject candidates and left several questions open. The
CEO decisions below are final for the grooming phase; reopening one requires new evidence and a
superseding entry (PLAN §39, anti-waste rules).

| # | Row(s) | Candidate | Decision | Reason |
| --- | --- | --- | --- | --- |
| DR-1 | MS-007 (Provectus variant) | Groovy script filters | **REJECTED** | Unsandboxed JVM scripting; CEL is Kafbat's successor and the only filter language KUI ships. |
| DR-2 | MS-001 (Provectus variant) | v1 seek API shape (`seekType`/`seekTo`/`seekDirection`) | **REJECTED(superseded)** | Kafbat itself returns "Not supported"; KUI's stream contract carries `seekTo[]` so nothing is lost. Rejected, not deferred: there is no future in which it returns. |
| DR-3 | MT-006 | Prometheus push-gateway / remote-write sinks | **DEFERRED → M9** | Pull-based exposition (MT-005) covers monitoring; push sinks are niche and add outbound-network surface. |
| DR-4 | OD-001 | OpenDataDiscovery exporter | **DEFERRED → M9, no interface slot before then** | External platform integration with no operator value; reserving an abstraction now would be speculative design. |
| DR-5 | NX-002 | In-app survey popup | **REJECTED** | Sends usage data to a third party; consent and telemetry concerns; no operator value. Rejected rather than deferred so nobody re-plans it. |
| DR-6 | NX-004 | Demo mode with fake backend | **DEFERRED → M9** | Marketing aid, not a capability. When it comes, it is a compose file with a seeded cluster, not a fake frontend backend. |
| DR-7 | OT-006 | GitHub release phone-home, installation id | **ACCEPTED as opt-in, default off, M8** | Useful for upgrade nudges (NX-003) but must never contact the network without an explicit `kui.updates.check=true`. |
| DR-8 | AD-003 | AOP entry/exit logger, HTTP trace actuator | **REJECTED** | Replaced by OpenTelemetry spans and structured logs (PLAN §30). |
| DR-9 | SD-002 | Custom serde jars via isolated classloader | **DEFERRED → M7** | Needs an ADR on the KUI serde SPI (Scala trait vs Kafbat `serde-api` compatibility); written in M7 grooming, implemented in M7 with the other plugin surfaces. |
| DR-10 | TP-002, SF-002, CG-008, KC-010 (n-gram part) | Lucene full-text n-gram index | **DEFERRED → M9** | Adds a Lucene dependency for a problem substring search solves below ~5k topics. Revisit only with a benchmark on a real large cluster. |
| DR-11 | MC-001 | MCP server | **ACCEPTED, P2, M8** | Kafbat parity feature; tools derive automatically from Tapir endpoints, which are only stable once every service exists (end of M7). |
| DR-12 | ET-002 (transport) | STOMP / WebSocket transport for async tracking | **REJECTED (transport only)** | KUI streams over SSE (PLAN §28); the async tracking feature stays P0 in M3. |
| DR-13 | CL-011, TP-019 | Kouncil helper endpoints (`/api/connection`, `is-topic-exist`) | **REJECTED (folded)** | Typed contracts already answer both (cluster DTO field; `GET /topics/{topic}` → 404). |

Rulings on the open questions that affect milestone scope:

| # | Question (source) | Ruling |
| --- | --- | --- |
| DR-14 | Canonical RBAC vocabulary: Kouncil's 33 function names or Kafbat's resource × action matrix? (feature-matrix Q1) | Kafbat's resource × action matrix, verbatim, because it is also the migration path for Kafbat users. Kouncil's UI-managed groups (RB-004) map onto it. |
| DR-15 | Unavailable sidebar entries: disabled links (PLAN §16.5 wording) or clickable to a fallback panel? (DC-H1) | Clickable to the fallback panel; `NotConfigured` hidden; `Forbidden` shown disabled with tooltip. PLAN §16.5 wording is amended by this ruling. |
| DR-16 | Smart-filter test execution without RBAC (feature-matrix Q4, api-analysis) | Requires cluster scope and `TOPIC:MESSAGES_READ` (KU-016). |
| DR-17 | Connector plugin validation without RBAC (KC-008 research note) | Requires `CONNECTOR:CREATE` or `CONNECTOR:EDIT` and is audited (KU-022). |
| DR-18 | Async tracking sanity limit: per request or server knob? (feature-matrix Q2) | Both: a server-side hard maximum and a per-request `limit` that cannot exceed it. Same rule as browse. |
| DR-19 | UI-editable masking policies in M3 or later? (kouncil ui-analysis open question) | File-configured masking in M3 (DM-001); UI policies need user groups, so M6 (DM-002). |
| DR-20 | Merge `kui-security-service` into `kui-cluster-service`? (D-8) | **Settled by ADR-004: no.** It has its own capability gate, its own failure signature and functionality that is not cluster topology; merging a slow optional feature into the one Core service would break fault isolation. Owner stays `security`. |
| DR-21 | `kui-config-service` merged into the gateway? (PLAN §15) | **Settled by ADR-004: dissolved, not merged into one place.** Configuration is three ownerships — cluster configuration to `cluster`, auth and RBAC to `identity`, gateway configuration to the gateway — and `/api/v1/config` is a gateway aggregation over them. Owner columns above were updated accordingly at the G6 gate. |

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

Every P0 and P1 row has a milestone. Recount after editing rows with:

```
awk -F'|' '/^\| [A-Z]{2}-[0-9]{3} \|/ {gsub(/ /,"",$8); n[$8]++} END {for (m in n) print m, n[m]}' docs/FEATURE_MATRIX.md
```

If this table and the rows disagree, the rows win.
