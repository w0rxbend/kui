# KUI status

**Date:** 2026-09-04 (closing integration pass)
**Phase:** M0 through M4 are implemented, integrated and used from a browser against a real broker,
and M5's topic administration with them: create a topic, change a setting, add partitions, empty it
and delete it, the last three confirmed against a server-computed plan and applied against a signed
token. No row of the delivery bar is outstanding.
**Repository:** on `main`; every gate the CI runs is green — `__.checkFormat`, `__.fix --check`,
`checkArchitecture`, `__.openApiCheck`, `docs.errorCodes --check`, `__.checkBundleShape` and
`./scripts/run-tests.sh`.

**Wave 0 progress (2026-09-04).** E7 (CI), E5 (three service processes and images) and the contract
half of E6 (per-partition log directories) are done. E7 turned out to be the most important of the
three: `./mill a.test b.test` hands `b.test` to `a.test` as a *test-name filter*, so the CI test
job's first command had been running **zero** test cases while reporting green. Every suite now runs
— 4140 cases across 57 modules — and the number CI reports is a count of test cases rather than of
Mill build tasks.
**Read next:** `docs/DELIVERY.md`, whose *closing integration pass* section is the honest account of
what works, what does not, and what was never tested.

## What the closing pass changed

**The one gate that had been red on `main` is green.** `__.openApiCheck` compares the gateway's Tapir
endpoints against the committed `docs/api/openapi.json` byte for byte, and three passes in a row had
added endpoints without regenerating it, each deferring because doing so would sweep in another
pass's in-flight work. `20f7e35` regenerates it; the whole topic-administration surface and the
message resend endpoint are in the published document for the first time.

**Six defects were found by using the product and fixed.** All six were invisible to a green
`./mill __.test`:

- both "Disk" columns reported the host filesystem rather than Kafka — 468.8 GiB and 184.2 GiB for a
  broker holding a hundred records, now 78.9 KiB and correct (`147461d`);
- the topic page's Consumers tab, contributed by another feature, had no URL: clicking it left the
  address bar on Overview and typing its obvious address gave "That page does not exist" (`6e42c8c`);
- the dashboard's first tile said `1 of 1 Clusters online` above three panels saying the cluster was
  not responding (`feb8655`);
- a stale topic page read `IN SYNC REPLICAS 0 of 0`, which says every replica has fallen out of sync,
  computed from an empty list (`4d3b0ce`);
- a request to a dead broker was answered by **Netty** with a bodyless `503` — no code, no
  correlation id, not KUI's envelope — because the all-in-one's service client had no call timeout at
  all while the distributed one has had one all along; it is now `KUI-TIMEOUT` with a sentence on
  screen (`33c438a`, `165707d`);
- four confirmation sentences on irreversible operations began in lower case (`feb8655`).

**Point 6 of the delivery bar was re-checked against a stopped broker and holds.** Every screen kept
its numbers behind its own freshness marker, each section of the dashboard card marked separately,
and the consumer-group list — the gap that stopped point 6 being met two passes ago — carries the
envelope and says `Stale: the cluster is not answering`.

**What is still wrong is written down** rather than left to be rediscovered: the dashboard and the
topic list count topics differently, `/ui/clusters/{id}` is a 404, the capability registry's reason
still carries `kafka answered with status 502` under the code `UNKNOWN`, Kafka's own configuration
documentation renders as escaped HTML, a group detail page is blank for its first ten seconds against
a dead broker, and there is still no authentication of any kind. `docs/DELIVERY.md` has the detail.

## Grooming progress

| Step | Owner | Artifact | State |
| --- | --- | --- | --- |
| G1 Research | Research agents A–H | `research/**` | **Complete** (agent I blocked, see below) |
| G2 Domain model | Domain Architects | `docs/domain/*.md` | In progress: `docs/domain/kafka-glossary.md` drafted; per-context models not started |
| G3 Architecture | Chief Architect + CTO | `ARCHITECTURE.md`, `docs/adr/` | **Complete**: ADR-001 … ADR-043 Accepted and indexed in `DECISIONS.md` |
| G4 Roadmap | CEO + Program Lead | `docs/ROADMAP.md`, `docs/FEATURE_MATRIX.md` | **Complete** (this pass) |
| G5 Technical dev plan | Planner + Domain Architects + Principal Scala Engineer | `docs/plans/M0/DEVPLAN.md` + 57 task specs | **Complete** for M0 |
| G6 Gate | CTO + CEO | `docs/plans/M0/GATE-REVIEW.md`, sign-off in this file | **Complete** for M0: approved, and **all conditions discharged** — see "G6 conditions" below |

## Research reports (G1)

| Agent | Report | Notes |
| --- | --- | --- |
| A — Reference architecture | `research/kafbat/architecture.md`, `research/provectus/diff.md`, `research/kouncil/architecture.md` | Complete |
| B — Feature inventory | `research/kafbat/feature-matrix.md` | Complete, 150 rows; seeded `docs/FEATURE_MATRIX.md` |
| C — API and contract | `research/kafbat/api-analysis.md` | Complete, includes the KUI `/api/v1` mapping |
| D — Kafka domain | `docs/domain/kafka-glossary.md`, `research/kafka/admin-capabilities.md` | Complete |
| E — Scala ecosystem | `research/scala/ecosystem-mapping.md` | Complete |
| F — Frontend | `research/scala/frontend-research.md` | Complete; recommends ADR-012 Option B, ADR-019, ADR-020 |
| G — Security | `research/scala/security-research.md` | Complete; ADR-015, 017, 018, 019, 020, 021 candidates |
| H — UI/UX inventory | `research/kafbat/ui-analysis.md`, `research/kouncil/ui-analysis.md` | Complete; 33-screen IA proposal and degraded-state UX |
| I — Visual design import | `research/design/*` | **Blocked** (BLOCKERS.md B-001) |

Reference clones: `research/REFERENCES.md` (Kafbat `fa485c2`, Provectus `83b5a60`, Kouncil
`6e2fb85`, all cloned 2026-09-03 to `/tmp/kui-ref`).

## Product artifacts

- `docs/FEATURE_MATRIX.md` — 188 capability rows (150 from research + 38 KUI-only), all P0/P1
  rows assigned to a milestone; 21 CEO decisions recorded (DR-1 … DR-21). States after the
  2026-09-04 audit: 53 `COMPLETE`, 12 `REVIEW`, 2 `TESTING`, 16 `IMPLEMENTING`, 93 `RESEARCHING`,
  1 `BLOCKED`, 7 `DEFERRED`, 4 `REJECTED`. (The historical counts below are left as written; they
  record what was believed at the time.)
- `docs/BACKLOG.md` — the 124 undelivered capabilities, grouped into six waves with sizes,
  dependencies and the shared edges that must land before parallel work starts.
- `docs/ROADMAP.md` — M0..M9 with goals, scope by feature ID, non-goals, executable exit
  criteria, risks, services and microfrontends introduced, parity checkpoint (Kafbat parity and
  union superset both at the end of M8; message-exploration superset already at M3).

## Decisions taken in this pass

See `docs/FEATURE_MATRIX.md` "Decisions required". Headline rulings: CEL only (no Groovy);
messages v1 API rejected; STOMP rejected in favour of SSE; Lucene full-text, ODD exporter,
push metrics sinks and demo mode deferred to M9; survey popup and AOP logger rejected; release
check accepted as opt-in default off; custom serde jars deferred to M7 behind an SPI ADR; MCP
accepted for M8; Kafbat's resource × action matrix is the canonical RBAC vocabulary; Unavailable
sidebar entries are clickable and lead to a fallback panel (amends PLAN §16.5 wording);
smart-filter test execution and connector plugin validation gain RBAC checks.

## Amendments to PLAN.md required

- §16.5: "shown disabled with the reason" → "shown dimmed, clickable, leading to the feature's
  fallback panel; `NotConfigured` entries hidden" (DR-15).
- §45 M3: add "table-style browsing, event tracking, resend and bulk send" explicitly (already
  implied); M5: audit records carry an anonymous principal until M6.
- §9A profile: neither Kafbat nor Provectus ships an AWS Glue serde; Kafbat still ships the ODD
  exporter (research B).
- §16.6: "except through the gateway's contracts" → "except through the **published**
  contract of the callee". Settled by **ADR-043**: direct service→service calls on `/internal/v1`
  are permitted under four conditions (published contract, cached last-known fallback, capability
  reporting, no chains). Relaying through the gateway was rejected because it would make the
  gateway a mandatory dependency of every service pair — spreading the failure the rule exists
  to contain.

## Gate review

**G6, 2026-09-03, reviewer: CTO.** Full record in `docs/plans/M0/GATE-REVIEW.md`.

Reviewed `ARCHITECTURE.md`, `docs/domain/context-map.md`, all 38 ADRs, `DECISIONS.md`,
`DEPENDENCY_MATRIX.md`, `TECH_DEBT.md`, `docs/ROADMAP.md`, `docs/FEATURE_MATRIX.md`,
`docs/plans/M0/DEVPLAN.md` and its 57 task specs, against `PLAN.md` and the `research/`
reports.

**Verdict: APPROVED WITH CONDITIONS.** M0 implementation may start at BUILD-001.

17 findings: 1 blocker, 8 major, 8 minor. The blocker (the gateway's `application` module was
given dependencies that the architecture test forbids, so the build could not have stayed
green) and seven of the eight majors are fixed. Three ADRs were written for decisions the M0
plan had taken that no ADR covered — **ADR-039** (capability fold), **ADR-040** (edge header
policy), **ADR-041** (machine-enforced layering) — and six further such decisions were folded
into ADR-012, ADR-032 and ADR-034 as amendments. The task graph was verified to be a DAG whose
stated order never places a task before its dependencies.

Conditions, none of which gate the first commit:

1. ~~An ADR settles the OT-004 shared-database conflict with PLAN §3 before M6 grooming closes
   (`TECH_DEBT.md` TD-014); no store shared by two services in the meantime.~~
   **Met, 2026-09-03: [ADR-042](docs/adr/ADR-042-kafka-backed-metadata-store.md).** KUI stores
   its own metadata in Kafka, in internal compacted topics prefixed `__kui_` on a statically
   configured store cluster. No relational database is introduced, ever. ADR-036 is amended
   (the store is those topics, not a versioned YAML file; the Kubernetes Secret/ConfigMap
   adapter is dropped because a mounted Secret is a path the file adapter already reads);
   ADR-023's audit topic is renamed to `__kui_audit` for consistency. OT-004 is rewritten from
   "relational persistence" to the Kafka-backed store and **moves from M6 to M1**, because
   clusters become registrable at runtime in M1 and the store must exist by then; OT-007 …
   OT-010 were added for topic creation and validation, envelope encryption and key rotation,
   store health as a capability, and operator guidance. `TECH_DEBT.md` TD-014 is closed.
   M0 is unaffected: it ships static configuration only, and CFG-001, SVC-001 and AIO-001 now
   say so explicitly with a forward reference to M1.
2. PLAN §16.6 is amended and ADR-004 updated before the first M1 service→service call.
3. M0 closes with NX-007 `PARTIAL` and TD-007 open; no M0 task depends on the design import.
4. `./mill checkArchitecture` is proven to fail on a deliberate violating edge, with the
   message recorded in BUILD-005's Implementation Report.


## M1 integration and acceptance — 2026-09-04

Seven agents implemented M1 in parallel. This section records what an integration pass found when
the whole thing was built, tested and run for the first time, and how each of the milestone's exit
criteria actually stands. It is deliberately specific about what is *not* proved: a criterion nobody
checked is not a criterion that passed.

### Repository state

```
./mill __.compile              3498/3498  SUCCESS
./mill __.checkFormat           108/108   SUCCESS
./mill __.fix --check          2351/2351  SUCCESS
./mill checkArchitecture       75 modules, no layering violations
./mill __.test                 4649/4649  SUCCESS   (2383 tests, 0 failed, 0 ignored)
./mill services.cluster.api.openApiCheck    SUCCESS
./mill services.gateway.api.openApiCheck    SUCCESS
./mill docs.errorCodes --check              SUCCESS
./mill frontend.uiShell.checkBundleShape    1 feature module split out, main.js 966079 B of 1500000 B
```

Scala.js modules were also run in an invocation of their own with Node on `PATH`, per `CLAUDE.md`;
all green. Note that naming two Scala.js test modules on one `./mill` command line fails inside the
Scala.js test adapter — it passes the second module's selector to the first module's MUnit runner,
which rejects it. That is a harness limitation, not a project failure, and it is the reason the
convention exists.

### Six defects found by integrating, none visible inside any one module

1. **The all-in-one dropped `kui.clusters[]`.** `AllInOneConfig` carried three sections and handed the
   cluster service `ClusterServiceConfig.Default`, so the configured cluster list was parsed,
   validated and then discarded. The startup log said "resolved 0 configured cluster(s)" against a
   file that named one. Fixed in `d62295f`; this was CFGOP-006's all-in-one half, blocked all
   milestone on a cluster service that did not compile.
2. **The dashboard decoded a document nobody sends.** `GET /api/v1/clusters` is aggregated by the
   gateway and answers with `ClusterOverviewDto` (`{"clusters": …}`); the browser client declared the
   cluster service's `ClustersResponse` (`{"items": …}`), whose decoder defaults a missing `items` to
   `Nil`. Every response decoded *successfully* into zero rows, and the page rendered "No clusters
   yet" under a "last updated just now" timestamp. Fixed in `5978d61`, with four recorded gateway
   responses now guarding the seam.
3. **The end-to-end suite tested the previous milestone.** `./mill e2e.test` depended on the
   all-in-one jar but not on the container images the Compose topology runs, so
   `ClusterServiceDownSuite` — the suite that proves the milestone's headline claim — ran against the
   M0 images for the whole of M1. Fixed in `15bad4f`.
4. **An unresolvable bootstrap address was reported as "kafka answered with status 502".** Nothing
   answered, and 502 is a status no broker can return. Fixed in `7f02e2d`.
5. **`libs/testkit`'s container suite broke the whole repository's compile**, because it sat in the
   cross-compiled test tree and imports the Kafka client. Fixed in `bb42426`.
6. **Three flaky suites**, all asserting on sleeps rather than on conditions. Fixed in `31d502e` and
   `6cd253b`.

### M1 exit criteria, one by one

| # | Criterion | Verdict | Evidence |
| --- | --- | --- | --- |
| 1 | Testcontainers suite: PLAINTEXT, SASL/SCRAM and SSL each yield the same broker list, configs and log dirs | **Confirmed** — but it did not exist until integration | `ClusterAdminLiveSuite` (`c35d413`): the shared `ClusterAdminContract` run three times against three real brokers, 33 tests, 0 failed, ~70 s |
| 2 | Manual acceptance against one real external cluster recorded in `STATUS.md` | **Not confirmed** | No external cluster is reachable from this environment. The quickstart's containerised broker is not a substitute and is not being claimed as one. **Still owed.** |
| 3 | Dashboard with three clusters, one unreachable: two populate, the third shows `Unavailable: <reason>` and stays clickable; response bounded by the per-service timeout | **Confirmed** | Three clusters configured in the running quickstart. `GET /api/v1/clusters` returned two `ok` rows and one `unavailable` carrying `UPSTREAM_UNAVAILABLE`, a message and a sticky `since`, still holding `id`, `name` and `bootstrapServers`. Three consecutive calls took 37 ms, 8.5 ms and 7.9 ms — the dead cluster is scraped on a background loop and is not on the request path |
| 4 | Fault-isolation E2E: stopping `kui-cluster` leaves the shell, settings and cached rows usable | **Confirmed** — for the first time against M1 images | `ClusterServiceDownSuite`, 8 tests, 0 failed, against images built from the current commit |
| 5 | Configuration with an unknown key, a missing secret or an invalid URL fails at startup with all errors accumulated | **Confirmed by inspection of the suites, not re-run by hand** | `ValidationSuite.reportsEveryProblemNotJustTheFirst`, `rejectsUnknownKeys`, "a `file:` secret reference that cannot be read", and `UrlPolicySuite` |
| 6 | Metadata store: creates `__kui_config` and `__kui_files`, replays them, serves clusters from the store; a pre-existing topic with `cleanup.policy=delete` fails startup naming topic, setting, expected and found | **Confirmed** — did not exist until integration | `KafkaConfigStoreLiveSuite` (`7c05a31`), against a real broker |
| 7 | Two replicas writing the same key concurrently: one succeeds, the other gets `KUI-CONFIG-VERSION-CONFLICT`; both converge | **Confirmed** | Same suite: two independent stores over one topic, both writing from the same base version with `parTupled` |
| 8 | A write returns 200 only after the writer has read its own record back from the log tail | **Confirmed** | Same suite: five writes, each immediately readable with no sleep, no retry and no polling |
| 9 | Secret fields unreadable in the raw topic record: a console-consumer dump contains no plaintext password and no JAAS string | **Confirmed** | Same suite, read with a plain `KafkaConsumer` and a byte deserializer, with no KUI code between the partition and the assertion |
| 10 | With `kui.store.kafka.*` unset, the file adapter is used, store-backed writes report `NotConfigured`, everything else still passes | **Confirmed** | `FileConfigStoreSuite.writesReportNotConfigured` and `emptyStoreSatisfiesTheSameContract`; the quickstart itself runs in this shape and logs "no metadata store is configured" |
| 11 | Store cluster stopped mid-run: clusters keep resolving from last known state, the capability reports `Degraded`, writes are rejected rather than lost | **Not confirmed** | `StoreHealthSuite` proves the health state machine and its sticky `since`; nothing stops a broker mid-run and asserts the three consequences together. **Still owed.** |

Nine of eleven confirmed. Two are honestly open: criterion 2 needs a real external cluster, and
criterion 11 needs a suite that kills the store's broker while KUI is running.

### What the quickstart shows now

`deployment/quickstart/quickstart.sh` from a clean state: broker up, seeded, KUI healthy, one URL
printed. `/ui/main.js` is served as `text/javascript` (966079 bytes), so the M0 packaging blocker is
gone. In a headless Chromium the dashboard renders one row — *Quickstart (local)*, Online, 1 broker,
controller 1, 468.8 GiB — and the brokers page renders the broker itself: id 1, `kafka:9092`,
controller, 170.9 GiB, 83 in-sync replicas.

### Known problems that are not fixed

- **No per-partition data on the log-directories tab** — `TECH_DEBT.md` TD-017 and TD-018. The wire
  half of TD-017 landed on 2026-09-04: `LogDirDto.replicas` now carries each topic-partition's size
  and lag, sorted biggest-first. No screen reads it yet.
- **No per-broker WARN rate limit** on the log-directory fallback — owed to CLDOM-006.
- **`KafkaConfigStoreLiveSuite` is flaky when the whole repository's tests run at once.** It passes
  every time on its own (`./mill libs.config.test`) and failed in three of five full `./mill __.test`
  runs on 2026-09-04, with `UnknownTopicOrPartitionException` during bootstrap and with a replica
  that had not converged. It is a live-Kafka Testcontainers suite competing with three other
  Testcontainers suites for one machine, not a defect in the code under test — but it is a test
  asserting on timing under contention, which is the same class of problem as the flake fixed below,
  and it is owed the same treatment.

### The six defects the integration pass left open — all fixed, 2026-09-04

Each was reproduced first, as a test that failed against the code as it stood, and the observed
failure is recorded here beside its fix.

1. **A dead cluster's per-cluster capability reported `available`.** `CapabilityReportUseCase.stateOf`
   reported `Available` for every managed cluster whatever its snapshot said, so
   `/api/v1/capabilities` called a cluster available whose own dashboard row said `Unavailable`. The
   dashboard was right because it reads the row's own section; the sidebar and the cluster switcher,
   which read the capability registry, were wrong. The invariant that rule protected — one dead
   cluster must not dim the cluster feature for everybody, DEVPLAN D4 and ADR-039 §6 — is now
   enforced only where it belongs, on the *service's* key, which `ReadinessPoller.summarise` builds
   from readiness and the circuit and never from a cluster's status. A cluster that is not answering
   is reported `Degraded` on its own key, carrying the failure's own message. The seam is tested in
   `apps/allinone/test`'s new `CapabilitySeamSuite`, the only module that compiles against both the
   cluster service's mapping and the gateway's fold — which is exactly why the defect survived: every
   test on both sides passed, because each side was consistent with what it believed the other did.
   **Observed before the fix:** `document.clusters(dead).status` was `available` and the fold returned
   `Available`.
2. **The cluster switcher rendered the slug, not the display name.** The capability stream carried no
   name, so `ClusterEntry.of` had nothing but the id to label a row with. `ClusterCapability` now
   carries the operator's own name (and the reason a cluster is not available), `CapabilityEntry`
   carries it to the browser, and the store remembers it across frames that do not repeat it.
   **Observed before the fix:** the switcher rendered `prod-eu-1` where the configuration said
   `Production EU (primary)`.
3. **`SnapshotFreshness` lost the error's code.** It flattened the `KuiError` into a sentence written
   for a person, so `SectionMapping` had nothing left to classify and reported every failing scrape as
   `UPSTREAM_UNAVAILABLE`. It carries the error itself now. **Observed before the fix:** a
   `Timeout` produced `Section.Unavailable(UpstreamUnavailable, …)` instead of `UpstreamTimeout`.
4. **Timestamps disagreed between screens.** `Timestamps.Fields.offsetSeconds` subtracted an instant's
   *milliseconds* from a wall clock measured in whole seconds, so an instant a few hundred
   milliseconds past the second gave 10799 seconds instead of 10800 — rendered `UTC+02:59`.
   **Observed before the fix:** `offsetSeconds("Europe/Warsaw", …)` returned 7199 rather than 7200 for
   an instant 501 ms past the second.
5. **`HEAD` on the frontend bundle returned 400 while `GET` returned 200.** Two causes, both fixed:
   the static routes declared no `HEAD` endpoint, and `libs/http`'s `ErrorInterceptor.shouldRespond`
   treated a *method* mismatch as a request to answer rather than as a routing question, so the first
   endpoint declaring the path answered `400 KUI-VALIDATION` and no later endpoint was ever tried.
   A path served for one method and not another now falls through to the reject handler and answers
   `404 KUI-ROUTE-NOT-FOUND` naming the method — a documented behaviour change, asserted in
   `SessionMiddlewareSuite`. **Observed before the fix:** `HEAD /ui/main.js` → 400, `GET` → 200.
6. **One rarely flaky test.** `BrokerDetailUseCaseSuite.logDirsFallsBackToTheSnapshotWhenTheLiveCallFails`
   depended on a race between two fibers: a cluster's topology cell and its capability cell load
   independently, and a topology built before the probe answers skips `describeLogDirs` entirely, so
   there was nothing to fall back to. `ClusterRig.settled` now means the stronger thing — the probe has
   answered *and* the topology in the cells was built from it — and it asserts that as a condition
   rather than waiting a duration. One forced refresh was not enough either: `SnapshotCell` publishes
   its new state before clearing the in-flight slot, and `refresh` joins an in-flight load, so it
   refreshes until the topology's feature set matches the probe's. **Observed before the fix:** with a
   20 ms delay on the admin port the fallback found no directories on every run, deterministically.

Two contract fields were added for defects 1 and 2 — `ClusterCapability.name` and `.reason`, and
`CapabilityEntry.name` — all optional and defaulted, so an older service or an older browser still
decodes. Both OpenAPI documents and the golden documents were regenerated.

### Next step

M2 grooming. Before it starts, the gate's own condition applies: re-examine the `libs/kafka`-versus-domain
dual type definitions before `TopicAdmin` and `GroupAdmin` turn three pairs into ten.

## Second integration pass — 2026-09-04

The pass below left M3 and M4 "part built, nothing reachable". Three further agents then landed the
message service, the consumer API and both screens. This section records what a second integration
pass found by driving the assembled product.

### Repository state after the pass

```
./mill __.compile                        6166/6166, SUCCESS
./mill __.checkFormat                      189/189, SUCCESS
./mill __.fix --check                    4164/4164, SUCCESS
./mill checkArchitecture                 129 modules, 10 rules, no layering violations
./scripts/run-tests.sh                   4140 test cases across 57 modules, all passing (235s)
./mill __.openApiCheck                   1307/1307, SUCCESS
./mill build-tests.test                    137/137, SUCCESS
./mill frontend.uiShell.checkBundleShape   4 feature modules split out, main.js 929,950 B of 1,500,000 B
./mill frontend.css                            7/7, SUCCESS
```

`__.checkFormat` and `__.fix --check` were both **red** when the pass began — three agents had
committed unformatted sources, and both commands are CI gates, so `main` would have failed on a push
while every test passed. Fixed in `81ea011`, which is formatter output and nothing else.

### Seven defects found by using the product

Full detail, with the reasoning behind each fix, is in `docs/DELIVERY.md` under "Where this stands,
2026-09-04 (integration pass)". In brief, and worst first:

1. **Browsing a nonexistent topic created it** (`4d4820b`). A Kafka consumer's
   `allow.auto.create.topics` and a broker's `auto.create.topics.enable` both default to true, so
   asking for a missing topic's metadata creates it. A mistyped name answered `KUI-TOPIC-NOT-FOUND`
   and left an empty topic behind. Verified by `kafka-topics.sh --list` before and after.
2. **The message browser was unreachable** (`97e63e9`). The topic page's tab route decoded any fifth
   URL segment and is registered first, so it claimed `/clusters/c/topics/t/messages`.
3. **Every cluster-scoped sidebar entry was a dead link** (`b24d293`). Topics, Messages and Consumers
   all pointed at a URL with an empty cluster segment in it, which collapses to a path matching no
   route. `NavEntry.requiresCluster` existed and was read by nothing.
4. **The message browser crashed on mount** (`f9d109e`). A Laminar `controlled` input paired with the
   `change` event, which Laminar rejects at run time. The screen rendered "Something went wrong".
5. **`kui.topics.internalPrefix` did nothing** (`dc135cd`). Two functions implemented the rule and
   neither was called from production code.
6. **A browse that finished left its Stop button up for ever** (`631a641`).
7. **A browse against an unreachable broker said "Internal error"** (`0e4b23d`) where every other
   screen said `KUI-UPSTREAM-UNAVAILABLE`. The consumer's constructor was outside the error mapper.

There was also no link anywhere into the message browser; the topic page now carries one
(`c92b38f`).

### What was verified in a browser

Headless Chromium against a quickstart built from a clean `docker rmi`:

- Seven topics by default, nine with "show internal topics" on.
- `orders.v1` opens on six partitions with per-partition offsets, and its Settings tab lists the
  Kafka configuration.
- 16 records browse from `orders.v1` with their JSON readable; `audit.log.raw` renders its non-JSON
  values as text.
- `seekTo=offset::2` and `seekTo=timestamp::<ms>` both work from the URL.
- Three consumer groups with their states, `order-fulfilment` showing a real lag of 9.
- With the broker stopped: "Degraded: cluster too slow to answer" on the cluster row, "Stale:
  UPSTREAM_UNAVAILABLE" over a topic list that keeps its rows, and a browse that ends with the
  reason rather than a 500.

### Still not done

- **No publishing.** No produce endpoint, no resend. Bar point 3's second half is unmet.
- **No offset-reset screen.** The plan/apply endpoints are served and were verified against a real
  broker; nothing in the interface drives them.
- **No serde picker and no "load more".** One browse reads up to its limit and stops.
- **`services/consumer/{api,app}` ship with empty test modules.** The layers below are covered and
  the surface is verified against a live broker, but there is no route-level suite pinning the
  `lag`-before-`detail` ordering or the body-digest binding — the two things that broke there.
- **The apply receipt reports `current: null`** for every partition, because the plan token's payload
  carries only the proposed offsets.
- **`ConsumerWiring` generates its plan-signing key per process**, so a wizard left open across a
  restart must re-plan. ADR-026's cursor key does not exist in `libs/config` yet.
- **ADR-020 has no answer for a proxied endpoint with a body.** `ConsumerApi.Securing.withBody` is a
  local workaround; M3's publish endpoint will hit the same wall. This wants an ADR amendment before
  a second service invents a second answer.
- **Bar point 5 is unexercised.** The secured example configuration has never been run against a
  SASL/TLS cluster.

## M2, M3 and M4 integration — 2026-09-04

Ten agents implemented M2, M3 and M4 in parallel. This section records what an integration pass
found: what it fixed, what it verified by driving the product, and what is still not done.

### Repository state after the pass

```
./mill __.compile          5577/5577, SUCCESS
./mill __.checkFormat        171/171, SUCCESS
./mill __.fix --check      3764/3764, SUCCESS
./mill checkArchitecture   117 modules, 10 rules, no layering violations
```

### The defect that mattered: M2 compiled and did not run

Four topic modules were landed, tested and green — `services/topic/{domain,application,api,contract}`
— and nothing constructed any of them. There was no Kafka adapter, no composition root, and no entry
in the all-in-one deployment's service list, so the running product had no topic routes at all:

```
$ curl http://localhost:8080/api/v1/clusters/quickstart/topics
{"code":"KUI-ROUTE-NOT-FOUND","message":"No route for GET /api/v1/clusters/quickstart/topics"}
```

This is the same class of failure as M1's two worst — a configuration section parsed and discarded, a
browser decoding a document nobody sends. Every unit test passed; the product did not work. It was
visible in under a minute of using the quickstart and invisible from inside any module.

Fixed in `65bf9ef`, which adds `services/topic/infrastructure` (a `TopicAdmin` over the raw Kafka
`Admin` client through the shared `AdminClientPool`, a `ClusterProfiles` over the loaded
configuration, and one background-scraping `SnapshotCell` per cluster), `services/topic/app`
(`TopicWiring.make`, shaped like `ClusterWiring.make` and binding no port), and the all-in-one
wiring that publishes it. The startup log now reads `services=cluster,topic`.

### Two more defects found by looking at the screen

1. **The partition table denied partitions it never had** (`3b04f28`). With the broker stopped, the
   detail page correctly fell back to the topic-list snapshot and correctly showed a stale badge —
   and then the empty partition table underneath announced "The broker reported no partitions for
   this topic, which is unusual — a topic always has at least one." No broker had reported anything;
   the snapshot holds counts, not partition assignments, by design. A confident false statement about
   a six-partition topic, shown to an operator in the middle of an outage.
2. **`_schemas` rendered as `schemas`** (`a1d2859`). A browser draws a link's underline exactly where
   an underscore glyph sits. Two different topics, one of them conventionally infrastructure's.

And one in the tests rather than the product: `CelFilterEngineSuite` built its engines from the
production `FilterLimits.default`, whose evaluation deadline is 10 milliseconds. That is the right
budget for a filter that runs once per Kafka record and the wrong one for a test whose first CEL
evaluation pays for class loading and JIT warm-up. Under a full parallel `__.test` two of its tests
observed `Left(Timeout)` instead of the answer they assert. Fixed in `061bc60`; the three tests that
are actually about the limits pass their own and are untouched.

### What the quickstart shows now

Driven with Playwright 1.62.1 / Chromium against images built from this commit, screenshots read.

| Question | Answer |
| --- | --- |
| Eight seeded topics with partition counts, internal hidden until asked for | **Yes.** The list shows the eight seeded topics; `__consumer_offsets` (50 partitions) appears only with "Show internal topics" ticked |
| Open a topic, see partitions and configuration | **Yes.** Six partitions with leader, replicas, first/next offset and message count; the Settings tab shows every broker-reported setting with its default |
| Browse messages, see the JSON, seek to an offset and a timestamp | **No.** There is no messages tab and no message endpoint. See M3 below |
| The non-JSON topic renders as bytes rather than failing | **Not answerable.** `audit.log.raw` is listed and openable; there is no message browser to render its payloads |
| Three consumer groups with states and lag, including the one left behind | **No.** `/ui/clusters/quickstart/consumer-groups` is the 404 page and no consumer-group endpoint is served |
| A cluster that is down stays navigable, with the reason shown | **Yes.** With Kafka stopped the topic list still showed all eight rows under "Last updated 2 minutes ago — Stale: UPSTREAM_UNAVAILABLE", the cluster capability moved to `degraded`, and every screen stayed navigable |

### M2 exit criteria, one by one

| # | Criterion | Verdict |
| --- | --- | --- |
| 1 | Property tests on paging and sorting | **Confirmed.** `PagingLawsSuite` and `PagingSuite` in `libs/kernel`, `TopicOrderingSuite` in `services/topic/application`; all green |
| 2 | Virtualized table renders 10 000 rows under 16 ms, recorded in `docs/benchmarks/` | **False.** `docs/benchmarks/` does not exist. The virtualized table is built and unit-tested; no timing was measured and none is recorded |
| 3 | Fault-isolation E2E: stopping `kui-topic` leaves brokers and dashboard working, topic list greyed with timestamp | **Partly.** The *behaviour* is confirmed against a stopped broker, by screenshot. The *criterion as written* is not: there is no `kui-topic` image and no topic service in `deployment/compose`, so a separate topic container cannot be stopped |
| 4 | `GET /topics/{topic}/overview` returns the `topic` section and `Unavailable` placeholders for absent services | **Confirmed.** Observed live: `topic: ok`, and `consumerGroups`, `connectors`, `acls`, `schemas` each `not_configured` |

### M3 exit criteria

**Not verifiable — none of the milestone is reachable.** `libs/serde` (nine serdes, autodetect,
Spring DLT headers), `libs/filter` (CEL), `libs/security-core` (masking), `services/message/domain`,
the cursor codec and `services/message/contract` are built, tested and green. There is no
`services/message/{api,app,infrastructure}`, no route, and no messages screen, so every criterion —
the seek modes against Testcontainers, the cancellation test, the tracking stream, the resend, the
benchmarks, the fault-isolation E2E — is untested against anything a user can reach.

### M4 exit criteria

**Not verifiable — none of the milestone is reachable.** The consumer service is the most complete
of the three: `libs/kafka`'s `GroupAdmin` with `listGroups`/`describeGroups`/`committedOffsets`/
`OffsetLookup`/mutations, the domain with `LagMath` and the reset planner, the application layer with
the snapshot, five reads and three mutations, `services/consumer/infrastructure` with a live Kafka
adapter, and the whole contract. What is missing is the last hop: no `services/consumer/api`, no
composition root, no gateway route, no `frontend/ui-consumers`. The offset-reset criterion, the lag
poll's changed-groups token, the Unavailable consumers panel and the rebalance staleness are all
untested end to end.

### Known problems, plainly

1. **M3 and M4 are not usable.** Everything above. This is the largest single fact about the
   milestone and no amount of green test output changes it.
2. **No `docs/benchmarks/`.** M2's timing criterion, M3's message benchmarks and M4's cost
   documentation all name it. Nothing has ever been measured.
3. **No topic service image and no topic service in the distributed Compose topology.** The
   all-in-one deployment serves topics; the three-container one does not, so the two shapes now
   disagree about what the product is. `deployment/docker` has `gateway` and `cluster` only.
4. **Sizes and segment counts are always absent on the topic screens.** Both come from
   `describeLogDirs`, a per-broker call over every partition on the cluster. Rendering `—` is honest;
   it is still a column that never has a value.
5. **Kafka's own configuration documentation is rendered with its HTML showing.** The Settings tab
   prints `<a href="#compaction">log compaction</a>` as literal text, because the broker's doc
   strings contain markup and KUI escapes them. Safe, and ugly.
6. **The quickstart's seed and KUI disagree about what "internal" means.** `seed/topics.tsv` calls
   `_schemas` "an internal topic … user interfaces hide these behind a switch"; KUI's
   `kui.topics.internalPrefix` defaults to `__`, so `_schemas` is listed as a normal topic. Only
   `__consumer_offsets` is hidden. The eight-topic list is the right answer for the criterion, and
   the two files should still be made to agree.
7. **Long topic names truncate in the list while most of the table's width is empty.** The
   virtualized table uses `table-layout: fixed` with even columns, so `analytics.pageviews…` is
   clipped with 500 px of unused space to its right.
8. **`libs.config`'s `KafkaConfigStoreLiveSuite` is flaky under a full parallel `__.test`.** Its
   two-replicas-one-winner test failed once in a whole-repository run and passes on its own. Not
   touched by this milestone; recorded because it will happen again.

### Next step

Finish the last hop for M4 and then M3, in that order: the consumer service is one `api` module, one
composition root, one gateway route entry and one microfrontend away from being usable, and the
message service needs its `application` and `api` layers as well. Neither needs new domain work.

## Milestone acceptance log

| Milestone | Accepted on | Evidence |
| --- | --- | --- |
| M0 Foundation | 2026-09-03 | the fault-isolation E2E recorded below |
| M1 Cluster connectivity | 2026-09-04, **with two criteria unmet** | the exit-criteria table below |
| M2 Topic explorer | 2026-09-04, **with two criteria unmet** | the M2 exit-criteria table above |
| M3 Message explorer | **not accepted** | nothing is reachable from a browser |
| M4 Consumer groups | **not accepted** | nothing is reachable from a browser |

## M0 exit criterion: fault-isolation E2E (E2E-002)

The milestone's central claim, checked by a real browser against two real containers. Run on
2026-09-03 with `./mill e2e.test -v`, Playwright 1.62.0 / Chromium 151.0.7922.34 (build 1234),
against `deployment/compose/docker-compose.yml` + `docker-compose.e2e.yml`:

```
kui.e2e.ClusterServiceDownSuite:
  + entry is normal while the service is up 0.988s
  + stopping the service dims the entry within the readiness interval 12.799s
  + the capability API reports unavailable with a reason and a since 0.01s
  + the fallback panel shows reason, since, retry and what-still-works 0.026s
  + settings and the shell keep working while the service is down 0.052s
  + retry while down probes and reports still-unavailable 0.074s
  + starting the service restores the entry with no page reload 31.35s
  + ping works again after recovery 0.181s
kui.e2e.ClusterServiceDownSuite finished: 0 failed, 0 ignored, 8 total 73.634s

kui.e2e.CircuitBreakerSuite:
  + a hanging service dims the entry instead of hanging the UI 18.478s
  + unpausing the service brings the entry back 1.932s
kui.e2e.CircuitBreakerSuite finished: 0 failed, 0 ignored, 2 total 48.108s

kui.e2e.ShellSmokeSuite:      0 failed, 0 ignored, 7 total 28.453s
kui.e2e.ThemeSuite:           0 failed, 0 ignored, 1 total 22.363s
```

The suite was also run with its `docker compose stop` deliberately removed, to establish that the
assertions are not vacuous: four of the eight steps then failed, each with the state it had been
waiting for named in the message — for example `timed out after 9 seconds waiting for: the Clusters
entry to be dimmed after kui-cluster stopped`. The other four passed, correctly, because they do not
depend on the outage.

## G6 conditions

The gate approved M0 with conditions. All are discharged; implementation may start.

| Condition | Discharged by |
| --- | --- |
| F-01 layering contradiction (gateway `application` vs rule A3) | **ADR-041 Amendment 1** — A3 is scoped to services that own a `domain`; the gateway, which owns none by ADR-004 §3, may use `libs/contracts-core` and `libs/http`; new rule A8 forbids any Kafka client on the gateway. Addendum 2 in `GATE-REVIEW.md` records the reversal and its argument. |
| F-05 shared relational store (OT-004 vs PLAN §3) | **ADR-042** — metadata lives in internal compacted Kafka topics; no database, ever. `TECH_DEBT.md` TD-014 closed. |
| F-06 PLAN §16.6 ambiguity | **ADR-043** — direct service→service calls permitted on the callee's published contract under four conditions. Removed from `DECISIONS.md` "not yet taken"; the PLAN amendment is listed above. |
| F-14 design tokens with no import | **Decided, not accepted as a risk** — KUI owns its token set (`docs/plans/M0/tasks/UI-002.md`), derived from competitor analysis and contrast-tested. `BLOCKERS.md` B-001 closed as decided-around; NX-007 closes `DONE`. |
| F-17 naming key | Added to `ARCHITECTURE.md` §16. |

Nothing in the M0 plan now waits on input from outside the execution loop.

## M0 readiness

- `docs/plans/M0/DEVPLAN.md` + 57 task specs, gate-reviewed and amended.
- 8 parallel lanes; critical path 17 tasks (`DEVPLAN` §6.3).
- Every M0 exit criterion maps to at least one task and to a command that proves it.
- **Next action:** begin Phase E with BUILD-001. Lane A unblocks lanes B–H; BUILD-006,
  CFG-001 and KERN-006 are the three off-critical-path tasks worth starting early, because
  each answers a question that could invalidate later work.


## M1 gate (G6 for Milestone 1) — 2026-09-03

**Verdict: APPROVED WITH CONDITIONS.** Full review: `docs/plans/M1/GATE-REVIEW.md`.

Reviewed: `docs/plans/M1/DEVPLAN.md` and all 57 task specs, written by seven area agents in
parallel, against the M1 roadmap entry, the feature matrix, `ARCHITECTURE.md`, ADR-001 … ADR-043,
`DEPENDENCY_MATRIX.md`, `build.mill` and the executable rule table in `ArchitectureRules.scala`.

**3 blockers, 10 majors, 9 minors.** All blockers and majors are fixed in the review commit; 5
minors are fixed and 4 are assigned to a named task.

The three blockers were each the same shape — two lanes believing the other owned a shared edge:
`Ping` deleted by nobody because two specs deferred to each other (F-01); a domain port needing
`fs2.Stream` whose enabling architecture rule was scheduled to land *after* the code (F-02); and
`libs/kafka`'s client factory declaring the wrong upstream task, leaving the critical path's
first library task with no property renderer (F-03).

**ADRs written at this gate**, all Accepted and indexed in `DECISIONS.md`:

| ADR | What it settles |
| --- | --- |
| ADR-006 Amendment 1 | Admin work uses the raw `Admin` client; fs2-kafka is for consumers and producers. Reverses what the original Decision implies, with the four pieces of evidence. |
| ADR-022 Amendment 1 | The typed connection and security ADT lives in `libs/kernel`, not `libs/config`. Executes DEVPLAN D1 before the four modules are written against it. |
| ADR-041 Amendment 3 | Rules A9 and A10 added; rule A1 explicitly **not** widened to allow fs2 in a `domain` module. |
| **ADR-044** (new) | The metadata-store record envelope is versioned; secrets are marked by JSON convention (`$secret`/`$enc`) rather than a per-section field registry; each ciphertext is AES-GCM-bound to `key\|fieldPath`. A persisted format on a compacted topic is not a task-level detail. |

**Against the four M0 process findings:** the seam-testing gap is *not* repeated (CLADP-001's
shared port contract, CFGOP-005's parity suite, the all-in-one boot and the E2E are four seam
suites); rule enforcement is stronger than M0's; "the same string typed twice" recurred twice,
once fixed and once accepted with a stated reason and a `TECH_DEBT.md` entry; **cancellation was
again systematically unconsidered** — 52 of 57 specs never mentioned it — and is fixed by adding
a "Cancellation and shutdown" requirement with a named test to the eight specs that own a
long-running fiber, plus a new condition of done in `DEVPLAN` §9.

**Conditions:** (1) CFGOP-004 runs in the first week — three lanes now depend on it and it
settles the Testcontainers artifact-id question; (2) all eight cancellation requirements ship
with their tests; (3) CFGOP-008 completes the four assigned documentation items; (4) the
`libs/kafka`-versus-domain dual type definitions are re-examined at M2 grooming, before
`TopicAdmin` and `GroupAdmin` turn three pairs into ten.

**Next action:** begin M1 with KAFKA-001, and start CFGOP-004, STORE-001 and CLUI-001 in
parallel.

---

## Gate review — M2, M3 and M4 (2026-09-04)

Full report: [`docs/plans/GATE-REVIEW-M2-M3-M4.md`](docs/plans/GATE-REVIEW-M2-M3-M4.md).

**Verdict: APPROVED WITH CONDITIONS.** 4 blockers, 9 majors, 7 minors. All blockers and all
majors fixed in the review. Implementation may start now, with M2's lane B.

The first gate at which three plans were groomed in parallel by agents who could not see each
other. Every blocker was a shared edge — a thing all three plans needed, each solved locally and
correctly, in three incompatible ways:

- **Three different rules called `A11`** (M2's service-to-service rule, M3's Confluent
  confinement, M4's wire-vocabulary rule). `checkArchitecture` names the rule in its failure
  message, so the second one to land would silently redefine the first.
- **The cluster-profile client designed three times** — M2's shared `services/cluster/client`,
  M3's `HttpClusterProfileSource`, M4's `ClusterProfileSource` — one distributed protocol, three
  implementations. The M0 review's second process finding with a distributed-systems blast radius.
- **M3 ships three mutations with no safety net**, and says so twice in opposite directions: its
  criterion 14 requires `KUI-READ-ONLY` on produce, resend and purge; its non-goals say read-only
  mode is M5. M4 independently invented the answer and believed it was the product's first
  mutation. It is not; M3 lands first, and purge is irreversible.
- **Both M2 and M4 claim `PORT-INVARIANTS.md` §1** and would each implement the
  leaderless-partition filter, one deleting the section and one annotating it.

**ADRs written at this gate**, all Accepted and indexed in `DECISIONS.md`:

| ADR | What it settles |
| --- | --- |
| **ADR-045** (new) | A destructive operation is confirmed against a server-computed plan carried by an HMAC'd plan token, never against a form. M5's five other mutations reuse it. |
| **ADR-046** (new) | The cluster profile seam: Kafka credentials travel on `/internal/v1` only, and exactly one shared module — `services/cluster/client` — consumes them. Closes `ARCHITECTURE.md` §14's open question and M1's CLAPI-003 debt. |
| **ADR-047** (new) | Every mutation ships with a `Mutation` marker, a per-cluster read-only refusal and an audit record, from the first one. Resolves the contradiction in the roadmap's own ordering rationale. |
| ADR-041 Amendment 4 | Rules A11–A14 allocated centrally: A11 service-to-service (M2), A12 Confluent confinement (M3), A13 CEL confinement (M3), A14 wire vocabulary (M4). Rule numbers are allocated there and nowhere else. |

**Against the four M0 process findings:** seam testing is answered by all three plans and is M3's
strongest area (one committed byte fixture per SSE event, decoded by a JVM suite and a Scala.js
suite); rule enforcement is answered by M2's and M3's rule-and-enforcer tables; cancellation is
answered — every plan makes a named cancellation test a condition of done, which M1's gate had to
add by hand. **"The same string typed twice" recurred, and it produced every blocker**: the
profile client, the leaderless filter, `FeatureSlots` and `MutationKind`. It recurred because of
how the plans were produced, not who produced them, and the standing rule for the next parallel
grooming is in the review's §4.

**Parallelism verdict: not three ways. M2 partially first, then M3 ∥ M4.** M3 and M4 are
genuinely independent of each other — different services, different microfrontends, different
Kafka APIs, disjoint matrix rows, no call between them. M2 is not symmetric with them: five of its
deliverables are inputs to both (`services/cluster/client`, rule A11, `PageDto`, `NameIndex`, and
`ui-kernel`'s `FeatureSlots` with the `topic.tabs` guest host in `ui-topics`), and the profile
client sits on all three critical paths. Land TOP-001, TOP-008, TOP-009, TOP-010, TOP-019, TOP-027
and TOP-030's guest host first — seven tasks, six of which M2's own plan already says to start on
day one — then run M3 and M4 in parallel with the rest of M2. M3 §3a and M4 §3 carry a
one-command check and a named fallback for each, so a slip is a scoped extra task rather than a
blocked milestone.

**Conditions** (none gates the first commit): (1) GRP-013 states whether the consumer snapshot
uses `SnapshotRegistry` or a bare `SnapshotCell`, closing M2 D12's debt; (2) TOP-007, MSG-042 and
GRP-036 each extend M1's `KafkaFixture`/`KafkaTopology` and declare no container of their own;
(3) the four files all three milestones touch — `ui-shell`'s route table, `build.mill`'s module
list, the OpenAPI snapshot and the feature matrix — are appended to, never reformatted, and the
snapshot is regenerated rather than merged; (4) before the next parallel grooming, build-rule
numbers, ADR numbers, shared-file ownership and any protocol more than one milestone consumes are
fixed in advance.

**Next action:** begin M2 with TOP-008 and TOP-009 (the profile seam), alongside TOP-001, TOP-005,
TOP-013 and TOP-026.
