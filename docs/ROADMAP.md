# KUI roadmap

**Status:** grooming step G4 output (PLAN §39), 2026-09-03. Refines PLAN §45. Feature IDs refer
to `docs/FEATURE_MATRIX.md`. Every milestone also inherits the common exit criteria of PLAN §46
(compiles with `-Werror`, all test kinds green, fault-isolation tests for every service
introduced, formatting and scalafix clean, OpenAPI regenerated, docs and matrix updated, ADRs
Accepted, CEO acceptance recorded in `STATUS.md`).

## Ordering rationale

The order follows PLAN §4 (correctness → fault isolation → domain clarity → ...) and one product
rule: **a user must be able to read real data as early as possible, and nothing that is built
later may be able to take that away.**

1. **M0 before any Kafka code.** Fault isolation (PLAN §2.1) is a property of the gateway, the
   capability registry and the shell. If those are built after the features, every feature has
   to be retrofitted. M0 proves the whole chain (contract → client → gateway → shell → fallback
   panel) on a sample service with no Kafka at all, so the pattern is fixed before it is copied
   eleven times.
2. **M1 is connectivity only.** Typed cluster security config, the wrapped `AdminClient`, and
   the first Testcontainers suite are the foundation every later service reuses. Brokers and
   the dashboard are the smallest real screens that exercise them.
3. **Read before write.** M2 (topics, read-only) and M3 (messages) deliver the two screens
   operators open most, before any mutating operation exists. Mutations arrive in M5 together
   with read-only mode and audit, so no destructive action ever ships without its safety net.
4. **M3 carries the Kouncil differentiators.** Table-style browsing, event tracking and resend
   share the message service's seek/offset machinery with the Kafbat-style stream. Building
   them together is cheaper than bolting them on, and it makes KUI a superset of Kafbat in its
   most-used area at the earliest possible point.
5. **Consumers (M4) before administration (M5).** Lag monitoring is read-only, high-value, and
   the second most-used screen. Offset reset is the one mutation that ships early because the
   wizard is the reason people open a consumer group page.
6. **Identity (M6) after the read/write surface exists.** RBAC needs the full resource
   vocabulary to be real; enforcing it against endpoints that do not exist yet is guesswork.
   Until M6 the product runs in `auth.type=DISABLED` mode, which is also Kafbat's default.
7. **Plugins (M7) after the core is fault-isolated.** Schema Registry, Connect, ksqlDB, ACLs
   and quotas are the services most likely to be down or unconfigured in real deployments.
   They must land on a shell that already handles Unavailable and NotConfigured gracefully.
8. **Metrics and production hardening (M8) last before parity**, because metrics collection
   touches every service's health model and load tests need the whole system to exist.
9. **M9 is research-first**: everything there is beyond the union of the three references.

## Milestones

### M0 — Foundation (no Kafka)

- **Goal.** One repository that compiles, tests, links, ships as containers and as one JVM,
  and renders a shell whose navigation is driven by a live capability registry. No Kafka
  client code.
- **User value.** None visible to an operator yet; the value is that every later feature
  inherits fault isolation, typed contracts, observability and the design system for free.
- **Scope.** KU-001 KU-002 KU-003 KU-004 KU-005 KU-006 KU-007 KU-008 KU-009, MT-007,
  CW-001, NX-005 NX-006 NX-007, OT-005.
  Modules: `libs/{kernel,contracts-core,http,observability,config,security-core(skeleton),
  testkit}`, `services/gateway`, `services/cluster` as an empty shell (health, capabilities,
  one `ping` endpoint), `frontend/{ui-kernel,ui-shell}`, `apps/allinone`, `deployment/compose`.
  ADRs: 001–013 and 018 (PLAN §43) plus the frontend ADRs 019 (CSS) and 020 (facades) proposed
  in `research/scala/frontend-research.md`; ADR-012 decides Option B.
- **Non-goals.** No Kafka `AdminClient`. No login. No real screens beyond the shell, the
  settings stub and the fallback panels. No metrics collection (self-metrics only).
- **Exit criteria.**
  - `./mill __.compile`, `./mill __.test`, `./mill __.checkFormat`, `./mill __.fix --check`
    all green in CI on a clean checkout.
  - `./mill apps.allinone.run` serves the shell; `GET /api/v1/capabilities` lists the sample
    service as `Available`.
  - Fault-isolation E2E: with the compose stack running, `docker stop kui-cluster` flips the
    capability to `Unavailable(reason, since)` within the readiness interval, the sidebar
    entry dims and clicking it renders the fallback panel with the reason; `docker start`
    flips it back with no page reload.
  - Bundle-shape check: after `fullLinkJS`, `main.js` does not contain the sample feature's
    classes and a separate module file exists for it (Option B works).
  - `GET /api/v1/openapi.json` aggregates the gateway and sample-service contracts.
  - A `Secret[String]` field logged, traced, or returned from any endpoint renders as `***`
    (unit + contract test).
  - Design tokens and the kernel primitives listed in `research/kafbat/ui-analysis.md` §IA.4
    "Layout and navigation" and "Actions and feedback" exist in light and dark themes.
- **Risks.** Claude Design import is blocked (BLOCKERS.md B-001): tokens may have to start
  from Kafbat's palette and be reconciled later. Laminar 18 / Waypoint 10 are pre-release:
  pin 17.2.1 / 9.0.0 and schedule the upgrade. Mill 1.x plugin compatibility for
  ScalablyTyped needs a time-boxed spike.
- **Introduces.** Services: `gateway`, `cluster` (shell). Microfrontends: `ui-kernel`,
  `ui-shell`. Libraries: all of `libs/` except `kafka`, `kafka-auth`, `serde`.

### M1 — Cluster connectivity

- **Goal.** KUI connects to real Kafka clusters with production security settings and shows
  what they are made of.
- **User value.** Multi-cluster dashboard, broker list, broker configs and log dirs against
  any SASL/SSL-secured cluster, with a cluster being down never taking the page down.
- **Scope.** CL-001 CL-002 CL-003 CL-005 CL-007 CL-009, BR-001 BR-002 BR-005, PA-003,
  AU-005 (theme, timezone; no logout yet), OT-001 OT-003, KU-010 KU-011 (dashboard) KU-012
  KU-033 (first scenario).
  Modules: `libs/{kafka,kafka-auth}`, `services/cluster` complete, `frontend/ui-clusters`.
  ADRs: 014 (schema registry client strategy, needed by M3 serdes), 015–017 as candidates
  from the security research, 020 (typed cluster auth), plus the merge decisions DR-20/21.
- **Non-goals.** No topics, messages, consumers. No broker config edits. No metrics columns
  (bytes in/out render as `—`). No login.
- **Exit criteria.**
  - Testcontainers suite: PLAINTEXT, SASL_PLAINTEXT/SCRAM and SSL clusters; each yields the
    same broker list, configs and log dirs through the contract client.
  - Manual acceptance against one real external cluster recorded in `STATUS.md`.
  - Dashboard with three configured clusters, one unreachable: two rows populate, the third
    shows `Unavailable: <reason>` and remains clickable; response time is bounded by the
    per-service timeout, not by the dead cluster.
  - Fault-isolation E2E: stopping `kui-cluster` leaves the shell, settings and the other
    clusters' cached rows (greyed, timestamped) usable.
  - Configuration with an unknown key, a missing secret, or an invalid URL fails at startup
    with all errors accumulated in one message.
- **Risks.** JAAS generation for every mechanism (GSSAPI, OAUTHBEARER, AWS MSK IAM, Azure)
  cannot all be integration-tested locally; PLAIN, SCRAM and SSL are tested, the rest are
  unit-tested against known-good property strings and flagged in docs.
- **Introduces.** Service: `cluster` (complete). Microfrontend: `ui-clusters`. Libraries:
  `kafka`, `kafka-auth`.

### M2 — Topic explorer (read-only)

- **Goal.** Browse topics at scale.
- **User value.** Paged, sorted, searched topic list that stays smooth at 10k topics; topic
  details with partitions and configs; favourites.
- **Scope.** TP-001 TP-003 TP-004, PA-001, SF-001 SF-003, CL-010, KU-013 (slot exists;
  first guest panel arrives in M4).
  Modules: `services/topic`, `frontend/ui-topics`.
- **Non-goals.** No create/edit/delete (M5). No messages tab (M3). No consumer groups tab
  (M4). No full-text index (deferred, DR-10).
- **Exit criteria.**
  - Property tests on paging and sorting (page count, stability, RBAC-filter-before-page).
  - Virtualized table renders a 10,000-row list with scroll frame time under 16 ms in the
    Playwright run (recorded in `docs/benchmarks/`).
  - Fault-isolation E2E: stopping `kui-topic` leaves brokers and dashboard working; the topic
    list shows cached rows greyed with the timestamp and disables Create (which does not
    exist yet, so the assertion targets the disabled state of the action bar).
  - Topic overview aggregation (`GET /topics/{topic}/overview`) returns the `topic` section
    and `Unavailable` placeholders for the sections whose services do not exist yet.
- **Risks.** The kernel virtualized table is hand-written; scope creep into a general grid.
  Mitigation: column set fixed to what topics need; dynamic columns come in M3 with MS-004.
- **Introduces.** Service: `topic`. Microfrontend: `ui-topics`.

### M3 — Message explorer

- **Goal.** The highest-value and highest-risk path (PLAN §22): stream, page, filter, produce,
  resend and track messages, in two views.
- **User value.** Everything Kafbat can do with messages plus Kouncil's table view with
  flattened JSON columns, cross-topic event tracking, resend and bulk send. After M3 KUI is
  already a superset of Kafbat for message exploration.
- **Scope.** MS-001 … MS-014, MP-001 … MP-004, SD-001 SD-003 SD-004, DM-001,
  ET-001 ET-002 ET-003, KU-014 KU-015 KU-016.
  Modules: `libs/serde`, `services/message`, `frontend/ui-messages`. ADR-017 (CEL) Accepted.
- **Non-goals.** Extended serdes (KU-023, M5). Custom serde jars (M7). UI-managed masking
  policies (M6). Correlation-key grouping in tracking (M9). Audit attribution (M5/M6).
- **Exit criteria.**
  - Every polling mode and seek mode has a Testcontainers test; backward browsing on a
    partition with 1M records never reads more than `limit` plus one window.
  - Cancellation test: closing the browser `EventSource` closes the consumer in the service
    within one poll interval (asserted via consumer-group membership or a metric).
  - Property tests on seek/offset math shared by the stream and the table endpoint, and on the
    JSON flattener (depth cap, row cap, escaping, round-trip of paths).
  - Event tracking across three topics finds a planted header value within the time window
    and stops at the configured budget; the SSE stream ends with `done`.
  - Resend of an offset range lands byte-identical records in the destination topic, with
    headers stripped when requested.
  - Benchmarks recorded in `docs/benchmarks/`: small/large messages, 100-partition topic,
    tailing under load, slow-broker simulation.
  - Fault-isolation E2E: stopping `kui-message` stops live mode with a toast, keeps fetched
    rows greyed, and leaves topics and brokers untouched. Stopping the schema registry keeps
    the browser working with non-SR serdes and shows "SR unavailable" in the serde chip.
- **Risks.** Largest milestone by rows (28) and complexity (two XL). Mitigation: the DEVPLAN
  splits it into lanes (stream, table+flatten, produce+resend, tracking, serdes+masking) that
  share only the domain and seek modules; tracking and resend can slip to an M3.1 without
  blocking M4, but the parity checkpoint below moves with them.
- **Introduces.** Service: `message`. Microfrontend: `ui-messages`. Library: `serde`.

### M4 — Consumer groups

- **Goal.** See who consumes what, how far behind, and fix it.
- **User value.** Group list with state filter, group details with per-partition lag and pace,
  lag trend, offset reset wizard, topic → consumers tab.
- **Scope.** CG-001 … CG-006, TP-015, KU-011 (group page aggregation, topic overview
  `consumerGroups` section).
  Modules: `services/consumer`, `frontend/ui-consumers`.
- **Non-goals.** CSV export (M5). Full-text search (deferred).
- **Exit criteria.**
  - Reset offsets against a Testcontainers group in every mode; active group is refused with
    `KUI-INVALID-STATE`.
  - Lag polling returns only changed groups since the last token; the UI's suggested interval
    follows the Degraded payload.
  - Topic details page renders with the consumers panel Unavailable when `kui-consumer` is
    stopped (FeaturePanel slot, KU-013), and the topic sections still load.
  - During a forced rebalance the details page keeps the last-seen assignments greyed with
    a stale badge (DC-H10).
- **Risks.** Describe-all sorts (members, lag, topic count) are expensive on clusters with
  thousands of groups; bounded concurrency from OT-001 and a documented cost are the M4
  answer, caching is M8.
- **Introduces.** Service: `consumer`. Microfrontend: `ui-consumers`.

### M5 — Cluster administration

- **Goal.** Every mutating operation on topics and brokers, with the safety net that must
  accompany them.
- **User value.** Create/edit/delete/clone/recreate topics, partitions and replication factor,
  topic analysis, active producers, batch actions, broker config edits, replica moves, CSV
  exports; read-only mode; audit trail of every mutation.
- **Scope.** TP-005 … TP-013, TP-017 TP-018, PA-002 PA-004, BR-003 BR-006 BR-007, CG-007
  CG-009, RB-005, AD-001, OT-002, KU-023.
- **Non-goals.** User attribution in audit records (M6). Connect/ACL tabs on topics (M7).
- **Exit criteria.**
  - Read-only mode: every mutating operation in every service introduced so far returns
    `KUI-READ-ONLY`; the check is on the operation's `Mutation` marker, verified by a test
    that enumerates all endpoints and asserts each is classified.
  - Every mutation produces an audit record (console sink and Kafka topic sink tested).
  - Replication-factor change produces a valid reassignment on a 3-broker Testcontainers
    cluster; insufficient brokers yield a typed validation error.
  - Topic analysis on a 1M-record topic completes, is cancellable, and its percentiles match
    an independently computed reference within tolerance.
  - Extended serdes round-trip property tests (KU-023).
- **Risks.** Destructive operations before RBAC exists. Mitigation: read-only mode ships in
  this milestone and is the recommended default for shared environments until M6.
- **Introduces.** No new service or microfrontend. Library: `security-core` gains the
  read-only policy.

### M6 — Identity and RBAC

- **Goal.** Who the user is and what they may do, enforced at the edge and re-checked in
  services.
- **User value.** Login form, OIDC providers, LDAP/AD; roles from file and from a UI-managed
  store; buttons and routes gated with a merged permission/capability tooltip; RBAC view,
  audit viewer with real principals, role-aware masking policies, first-launch onboarding.
- **Scope.** AU-001 … AU-004, RB-001 … RB-004, AD-002, DM-002, CW-006, NX-001, OT-004,
  KU-017 … KU-021.
  Modules: `services/identity`, `frontend/ui-admin`, `libs/security-core` complete, the
  persistence adapter of OT-004. ADRs: 015, 017, 018, 019 (RBAC) from the security research.
  Threat model written; `kui.internal.events` research (PLAN §45) done here.
- **Non-goals.** Bearer-token access is P2 and may slip. SAML/CAS out of scope.
- **Exit criteria.**
  - Testcontainers Keycloak (OIDC) and OpenLDAP logins; session cookie is `HttpOnly; Secure;
    SameSite=Lax`, id rotates on login; CSRF header required on every cookie-authenticated
    mutation; logout is `POST`.
  - Property tests on the pure RBAC evaluator (action dependency unnesting, regex resources,
    cluster scoping) shared with Scala.js.
  - A service reached directly with a forged `X-KUI-Principal` header rejects the request;
    the same request through the gateway succeeds.
  - Fault-isolation E2E: stopping `kui-identity` with `auth.type=DISABLED` changes nothing;
    with auth enabled, existing sessions keep working for their lifetime and new logins show
    the identity fallback panel (Core tier behavior per PLAN §15).
  - Permission change pushes a forced logout notification (NX-001).
- **Risks.** Two role sources (file and UI store) need a documented merge policy (file wins,
  UI adds). Persistence introduces the first stateful dependency: file-only mode must remain
  the default and be tested.
- **Introduces.** Service: `identity`. Microfrontend: `ui-admin`.

### M7 — Ecosystem plugins

- **Goal.** The optional and degradable services, proving the plugin model for backend
  services and microfrontends.
- **User value.** Schema Registry, Kafka Connect, ksqlDB, ACLs and client quotas, each fully
  usable and each able to be absent, misconfigured or down without touching the rest.
- **Scope.** SR-001 … SR-009, KC-001 … KC-010, KS-001 … KS-004, AC-001 … AC-004,
  QU-001 QU-002, TP-014 TP-016, CL-008, SD-002, KU-022.
  Modules: `services/{schema,connect,ksql,security}`, `frontend/{ui-schemas,ui-connect,
  ui-ksql,ui-security}`. ADR: serde SPI (DR-9).
- **Non-goals.** Metrics graphs (M8). Full-text search on any of these lists (deferred).
- **Exit criteria.**
  - Testcontainers suites for Schema Registry, Connect (with a file connector) and ksqlDB;
    ACL tests on a cluster with an authorizer enabled.
  - `NotConfigured` clusters hide the four sidebar entries; configured-but-down services show
    them dimmed with the reason; all four microfrontends are lazy modules that are never
    downloaded for a cluster where they are NotConfigured (network assertion in Playwright).
  - Fault-isolation E2E for each of the four services, plus the cross-feature case: with SR
    down, produce falls back to the raw editor and the message browser keeps working.
  - A custom serde jar loads in an isolated classloader and decodes a Testcontainers topic.
  - ksql push query streams until the client disconnects; disconnect stops the upstream
    query within one heartbeat.
- **Risks.** Largest milestone by rows (34) but the rows are shallow and the services are
  independent, so four parallel lanes. ksqlDB grammar classification is the one deep item.
- **Introduces.** Services: `schema`, `connect`, `ksql`, `security` (or merged per DR-20).
  Microfrontends: `ui-schemas`, `ui-connect`, `ui-ksql`, `ui-security`.

### M8 — Metrics and production hardening

- **Goal.** Everything an operator needs to run KUI in production, and the last Kafbat
  features.
- **User value.** Broker and cluster throughput, graphs, Prometheus exposition, config wizard
  with in-app cluster management and connection tests, MCP server, Helm chart, migration
  from Kafbat environment variables, release process.
- **Scope.** MT-001 … MT-005, CL-004 CL-006, BR-004, CW-002 … CW-005, NX-003, OT-006,
  MC-001, KU-024 … KU-028.
  Modules: `services/{metrics,config}`, `frontend/ui-metrics`, `deployment/helm`, `tools/`.
- **Non-goals.** Push sinks, ODD, alerting, plugin SDK (all M9).
- **Exit criteria.**
  - Metrics from JMX (mock), Prometheus text format and inferred sources agree on a
    Testcontainers cluster; dashboards render gaps as gaps.
  - Config wizard: a cluster added in the UI is usable without restart; validation probes
    report per component; every remote URL passes the SSRF policy tests (link-local and
    metadata ranges rejected).
  - Load test at the documented budget (concurrent browsers, topic count, message rate)
    passes with the performance regression gate in CI.
  - `helm lint` and `helm test` green; runbooks for every service's Unavailable state.
  - Migration tool converts a Kafbat `KAFKA_CLUSTERS_*` environment into a valid KUI config,
    verified against three real Kafbat compose examples.
  - MCP tools list equals the set of non-deprecated Tapir endpoints; write tools are blocked
    on read-only clusters.
  - SBOM published with the release; dependency scan has no unresolved critical findings.
- **Risks.** JMX over SSL is hard to test; metrics inference without JMX is the fallback that
  must always work.
- **Introduces.** Services: `metrics`, `config` (or gateway-hosted per DR-21). Microfrontend:
  `ui-metrics`.

### M9 — Beyond parity (research first)

- **Goal.** Features no reference has, each preceded by a research report and an ADR.
- **User value.** Correlation-grouped event tracking, alerting, third-party frontend plugins,
  server-side column projection; and the deferred items revisited with evidence: full-text
  search, push metrics sinks, ODD exporter, demo mode.
- **Scope.** KU-029 … KU-032, TP-002 SF-002 CG-008, MT-006, OD-001, NX-004.
- **Non-goals.** Nothing enters M9 without a research report; the deferred rows are
  re-decided, not automatically built.
- **Exit criteria.** Per feature, defined in its own ADR. The milestone has no fixed end.
- **Risks.** Scope creep. Mitigation: M9 items are groomed one at a time.
- **Introduces.** Plugin SDK boundary (Option C); no new backend service unless an ADR says so.

## Parity checkpoint

| Checkpoint | After | Evidence |
| --- | --- | --- |
| Superset of Kafbat **in message exploration** | M3 | Kafbat's stream/cursor/filters/produce plus table view, flattening, tracking, resend, bulk send (MS-003, MS-004, ET-*, MP-002, MP-003). |
| Kafbat core operations (clusters, brokers, topics, messages, consumers, administration) matched | M5 | All P0 rows of CL, BR, TP, PA, MS, MP, CG complete; read-only mode and audit exist. |
| **KUI matches Kafbat** (every Kafbat feature not rejected) | **M8** | All P0/P1 rows sourced from Kafbat complete, including identity/RBAC (M6), the four plugin services (M7), metrics, config wizard and MCP (M8). |
| **KUI exceeds Kafbat**: union of Kafbat, Provectus and Kouncil, plus the KUI-only rows | **M8** | Same milestone: the Kouncil rows are scheduled in M3, M6 (RB-004, DM-002, CW-006) and M8 (CL-006), so the union is complete when M8 closes. |
| Beyond the union | M9 | KU-029 … KU-032 and any deferred row re-decided with evidence. |

## Summary table

| Milestone | Rows | P0 | P1 | Services introduced | Microfrontends introduced |
| --- | --- | --- | --- | --- | --- |
| M0 | 15 | 12 | 3 | gateway, cluster (shell) | ui-kernel, ui-shell |
| M1 | 17 | 9 | 7 | cluster (complete) | ui-clusters |
| M2 | 8 | 6 | 1 | topic | ui-topics |
| M3 | 28 | 16 | 9 | message | ui-messages |
| M4 | 7 | 5 | 2 | consumer | ui-consumers |
| M5 | 22 | 6 | 10 | — | — |
| M6 | 18 | 5 | 8 | identity | ui-admin |
| M7 | 34 | 11 | 16 | schema, connect, ksql, security | ui-schemas, ui-connect, ui-ksql, ui-security |
| M8 | 20 | 0 | 14 | metrics, config | ui-metrics |
| M9 | 10 | 0 | 0 | — | plugin SDK |
