# KUI status

**Date:** 2026-09-04
**Phase:** M1 implemented and integrated. M2 (topic explorer) is next.
**Repository:** M0 and M1 on `main`; `./mill __.test` is green end to end.

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

- `docs/FEATURE_MATRIX.md` — 183 rows (150 from research + 33 KUI-only), all P0/P1 rows
  assigned to a milestone; 21 CEO decisions recorded (DR-1 … DR-21); states: 172
  `RESEARCHING`, 7 `DEFERRED`, 4 `REJECTED`, 0 `DESIGNED` (no ADR is Accepted yet).
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

- **No per-partition data on the log-directories tab** — `TECH_DEBT.md` TD-017 and TD-018.
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

## Milestone acceptance log

| Milestone | Accepted on | Evidence |
| --- | --- | --- |
| M0 Foundation | 2026-09-03 | the fault-isolation E2E recorded below |
| M1 Cluster connectivity | 2026-09-04, **with two criteria unmet** | the exit-criteria table below |

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
