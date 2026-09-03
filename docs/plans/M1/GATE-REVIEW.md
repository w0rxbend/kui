# M1 grooming gate review (PLAN §39, step G6)

- **Date:** 2026-09-03
- **Reviewer:** CTO
- **Gate:** G6 — the single written grooming review before implementation starts on M1.
- **Verdict:** **APPROVED WITH CONDITIONS** (see [Verdict](#7-verdict)).

---

## 1. What was reviewed

| Artifact | Extent |
| --- | --- |
| `docs/plans/M1/DEVPLAN.md` | Whole file, all ten sections. |
| `docs/plans/M1/tasks/*.md` | All 57 specs, written by seven agents in parallel. |
| `docs/ROADMAP.md` M1 | Goal, scope, non-goals, all eleven exit criteria, risks. |
| `docs/FEATURE_MATRIX.md` | The 22 rows M1 claims. |
| `ARCHITECTURE.md` | §2, §3, §4.1–§4.3, §5, §6, §9, §10, §10.1, §14. |
| `docs/adr/` | ADR-006, 013, 014, 016, 022, 027, 030, 031, 032, 034, 035, 036, 037, 039, 041, 042, 043 in full; the rest checked for citation validity. |
| `DECISIONS.md`, `DEPENDENCY_MATRIX.md`, `TECH_DEBT.md`, `STATUS.md` | Whole files. |
| `build.mill`, `build-tests/src/kui/build/ArchitectureRules.scala` | The module graph and the executable form of rules A1–A8. |
| `research/kafka/admin-capabilities.md`, `research/kafbat/*`, `research/design/REFERENCE.md` | Consulted wherever a spec cited them as evidence. |

Checked for: contradictions between the DEVPLAN and a spec, or between two specs; whether the
task graph is a DAG whose every edge exists and whose every task ends on a green `main`; whether
every exit criterion and every claimed feature row has an owner; citations of ADRs, library
versions and files that will not exist when the task runs; the decisions the seven agents had to
take that no ADR covers; and whether the plan repeats any of the four process failures the M0
review found.

## 2. What the review did not find

The clean results are the load-bearing ones.

- **Coverage is complete.** All eleven M1 exit criteria have at least one satisfying task, and
  all 22 claimed feature-matrix rows have an owner. OT-010 (store operator guidance) initially
  looked unclaimed and is not: CFGOP-008 owns it by name.
- **No spec cites an ADR that does not exist.** 34 distinct ADR numbers appear across the 57
  specs; every one resolves to a real, Accepted ADR.
- **No library version disagrees with `DEPENDENCY_MATRIX.md`.** All 32 coordinates in the specs
  match the matrix exactly. One *artifact id* disagrees (F-15, minor) — a naming question, not a
  version question.
- **The dependency graph is a DAG.** No cycle, and after the fixes below every declared
  dependency names a task that exists and produces what the dependent task consumes.
- **The seam problem the M0 review found is not repeated.** CLADP-001's `ClusterAdminContract` —
  one abstract suite run against both the fake port the application tests use and a live broker —
  is precisely the missing artifact M0 was criticised for. CFGOP-005 (three security modes
  through the *contract client*), the `apps.allinone` integration suite and CFGOP-007's E2E add
  three more seam-level suites above it.
- **Rule enforcement is better than M0's, not worse.** A9 and A10 arrive with build tests that
  assert each allow-list entry individually, so a sixth Kafka exception has to be argued in the
  commit that adds it.

## 3. Findings

Severity: **blocker** = implementation cannot start; **major** = will cause rework or a wrong
build if not resolved; **minor** = wrong on paper, cheap to fix, no build consequence.

| ID | Sev | Area | Finding | Evidence | Resolution |
| --- | --- | --- | --- | --- | --- |
| F-01 | blocker | task graph | **`Ping` is never deleted.** CLDOM-001 says "the whole `Ping` family is deleted in one commit by CLAPI-002". CLAPI-002 says "`PingUseCase`, `Ping` and `PingMapping` are **not** deleted here — CLDOM-001 deletes the domain and application halves, CLAPI-004 deletes `PingMapping` and the route." Each defers to the other, so nothing deletes it — and any partial deletion leaves `main` red, because `Ping` is referenced from five modules across three areas. DEVPLAN §1 makes `Ping` surviving a milestone failure. | `tasks/CLDOM-001.md:49,59`; `tasks/CLAPI-002.md:75-76`; `DEVPLAN` §1, §6.2 (CLDOM-001's title), §6.5 | **Fixed here.** CLAPI-004 deletes the entire family in one commit and is granted a stated area-boundary exception (deletion only) for the six `domain`/`application` files. It depends on CLAPI-002 and CLDOM-006, so every replacement exists by then. The exact file list, and a `grep -ri ping` acceptance criterion, are now in CLAPI-004; CLDOM-001 and CLAPI-002 are told to delete nothing. DEVPLAN §6.2 and §6.5 updated. |
| F-02 | blocker | layering | **A task would leave `checkArchitecture` red for several tasks.** CLDOM-003 puts `fs2.Stream` in a `domain` port signature and asks CFGOP-003 to widen rule A1's allow-list. Rule A1 is executable and hard-codes `cats-core` (`ArchitectureRules.scala:145`), and CFGOP-003 depends on CLADP-001, which depends on CLDOM-003 — so the rule change lands *after* the code that needs it. CLADP-003 and CLADP-005 were written assuming the widening lands. | `tasks/CLDOM-003.md:286-308`; `build-tests/src/kui/build/ArchitectureRules.scala:135-148`; `DEVPLAN` §6.2 (CFGOP-003 ← CLADP-001 ← CLDOM-003) | **Fixed here, by refusing the widening.** [ADR-041 Amendment 3](../../adr/ADR-041-layering-rules-machine-enforced.md) records that A1 stays `libs.kernel` + cats-core: a port over an abstract `F[_]` needs no runtime, and `fs2.Stream` is a concrete type from a concrete runtime. `ClusterConfigStore.changes` becomes `onChange(handler): F[F[Unit]]`; `ClusterRegistry`, in `application`, owns the stream. CLDOM-003, CLDOM-004, CLADP-003, CLADP-005 amended. |
| F-03 | blocker | task graph | **KAFKA-004 declares the wrong dependency.** Its design body calls `ConnectionProperties.resource`, which KAFKA-003 creates, but it declares KAFKA-002. Nothing in the graph depends on KAFKA-003 at all, so a worker following the plan would start KAFKA-004 with no property renderer and no keystore materialization — on the milestone's critical path. | `tasks/KAFKA-004.md:9,308,336,350`; `tasks/KAFKA-003.md:163-169`; `DEVPLAN` §6.2 | **Fixed here.** KAFKA-004 now depends on KAFKA-003 in both the spec and `DEVPLAN` §6.2. The critical path lengthens by one M-sized task. |
| F-04 | major | cross-lane API | **STORE-005 pins an API no task creates.** It specifies `kui.kafka.KafkaClientFactory.baseProperties(...)` as a cross-area contract and tells the worker to block on KAFKA-004 for it. KAFKA-004 has no such object; it has `ConsumerFactory`/`ProducerFactory` over `ClusterConnection`, and the property rendering lives in KAFKA-003's `ConnectionProperties`. STORE-009 repeats the phantom name. | `tasks/STORE-005.md:80-103`; `tasks/STORE-009.md:26`; `tasks/KAFKA-004.md:171-330` | **Fixed here.** STORE-005's contract section now names `ConnectionProperties.resource` with its real signature, its `ClientPurpose` argument and the `libs.config → libs.kafkaAuth` edge it implies; its dependency line names KAFKA-003. |
| F-05 | major | model | **The feature probe's third outcome is thrown away at the port.** KAFKA-009 deliberately returns `ClusterFeatures(present, absent, unknown, probedAt)` — a timed-out probe recorded as "absent" hides a screen for an hour for a reason that was never true. The domain port then returns `Set[ClusterFeature]`, and the snapshot caches that for an hour. The decision and the bug it prevents are both erased one module later. | `tasks/KAFKA-009.md:92-113`; `tasks/CLDOM-003.md`, `CLDOM-005.md:248`, `CLADP-002.md:195`, `CLDOM-002.md:376` | **Fixed here.** The domain gains its own three-set `ClusterFeatures` with the same partition invariant asserted on both sides; `ClusterAdmin.capabilities`, `ClusterSnapshots.capabilitiesOf`, `ClusterDescription.features` and `CapabilityReportUseCase.stateOf` all carry it. Amendment added to CLDOM-002/003/005/007, CLADP-002, CLAPI-008. |
| F-06 | major | error codes | **CFGOP-005 asserts an error code that does not exist.** Its D-6 negative-parity case expects `KUI-CLUSTER-AUTHENTICATION-FAILED` "mapped by `KafkaErrorMapper` (KAFKA-005)". `ErrorCode` has no such case, no task adds one, and KAFKA-005's table maps both authentication exceptions to `KUI-UPSTREAM-AUTH`. The suite would fail on a milestone exit criterion for a reason that is a typo. | `tasks/CFGOP-005.md:188`; `tasks/KAFKA-005.md:192-193`; `libs/kernel/.../ErrorCode.scala` | **Fixed here** — CFGOP-005 now expects `KUI-UPSTREAM-AUTH` (502). |
| F-07 | major | process (M0 repeat) | **Cancellation is again systematically unconsidered.** 52 of 57 specs never use the word. The five that do are not the five that need it most: the store's replay and tail-following fibers, the write read-back waiter, the health reconnect loop, the admin client pool's creation window, the profile change listener and the `app` bootstrap `Resource` chain are all uncovered. A cancelled write that leaves a waiter in a map, or a client created between `Admin.create` and the `Ref` update, is a leak that only appears under load. | `tasks/*.md` (grep); M0 gate review process finding 4 | **Fixed here.** A "Cancellation and shutdown" requirement with a named test was added to STORE-006, STORE-007, STORE-008, STORE-009, CLADP-002, CLADP-005, CLAPI-005 and KAFKA-010, each stating the `uncancelable` window, the release order and the assertion. `DEVPLAN` §9 gains item 12a making it a condition of done. |
| F-08 | major | ADRs | **ADR-006 is contradicted for a whole milestone before it is amended.** KAFKA-004 chooses the raw `Admin` for all admin work and fs2-kafka only for consumers and producers — the reverse of ADR-006's Decision — and defers the ADR edit to CFGOP-008, the last task. Ten tasks would be written against an Accepted ADR that says the opposite. | `tasks/KAFKA-004.md:37-80`; `docs/adr/ADR-006`; `DEVPLAN` §9 item 10 | **Fixed here.** [ADR-006 Amendment 1](../../adr/ADR-006-fs2-kafka-and-admin-ports.md) written and Accepted at the gate, with the four pieces of evidence (option objects, the `null` KRaft controller, `all()`-shaped batch futures, one bridge instead of two). |
| F-09 | major | ADRs | **Same shape for ADR-022.** DEVPLAN D1 moves the typed connection ADT to `libs/kernel`, contradicting ADR-022's "in `libs/config` / `ClusterProfile`", and schedules the amendment for CFGOP-008. KAFKA-001 is task number one. | `DEVPLAN` §10 D1; `docs/adr/ADR-022`; `tasks/KAFKA-001.md` | **Fixed here.** [ADR-022 Amendment 1](../../adr/ADR-022-typed-kafka-cluster-auth.md) written and Accepted at the gate. |
| F-10 | major | ADRs | **The store's on-disk format was decided by a task, not an ADR.** STORE-001/002 settle three things no ADR covers and none of which can be changed after the first release without a data migration: the versioned `StoreRecord` envelope, the `$secret`/`$enc` JSON marking convention (as against a per-section registry of secret field paths), and AES-GCM AAD bound to `key\|fieldPath`. ADR-042 §4 says only "envelope-encrypted". Seven specs assumed answers; two assumed different ones. | `tasks/STORE-001.md`, `STORE-002.md`, `STORE-003.md`, `STORE-009.md`; `docs/adr/ADR-042` §4, §7 | **New [ADR-044](../../adr/ADR-044-store-record-envelope-and-field-encryption.md)**, Accepted, indexed in `DECISIONS.md`. It also records the consequence the specs found and buried: renaming a secret field is a migration. |
| F-11 | major | process (M0 repeat) | **The Kafka container topology is typed twice.** CFGOP-004 owns `KafkaFixture`/`KafkaTopology` in `libs/testkit` including PLAINTEXT; KAFKA-007 declares its own Testcontainers coordinates on `libs.kafka.test` and its own PLAINTEXT container, with no dependency on CFGOP-004. Both depend only on KAFKA-002, so whichever lands first defines a broker fixture the other duplicates. This is the M0 review's second process finding with a Docker image attached. | `tasks/KAFKA-007.md:70-84,245-250`; `tasks/CFGOP-004.md:22,112-160` | **Fixed here.** KAFKA-007 depends on CFGOP-004 and uses `KafkaFixture(KafkaTopology.Plaintext)`; the container topology exists in one file. `DEVPLAN` §6.2 updated. |
| F-12 | major | scope | **Decision D5 is partly unimplementable.** It promises the dashboard shows "online/offline partition counts" from `describeCluster` + the broker set + `describeLogDirs`. No Kafka API produces them from those three calls; `admin-capabilities.md` §1 records that the reference product aggregates `describeTopics` + `describeLogDirs` + `listOffsets` — the topic sweep DEVPLAN §3 puts in M2. Per-broker *leader* counts are underivable for the same reason. Replica counts and skew **are** derivable and do ship. | `DEVPLAN` §10 D5; `research/kafka/admin-capabilities.md` §1; `tasks/CLDOM-002.md` (which already models them as `Option`, always `None`) | **Fixed here.** D5 corrected in the DEVPLAN; CLUI-003 given an explicit amendment saying which three numbers have no field on the wire and render `—`. CLAPI-001's "no permanently-null fields" rule already agrees. |
| F-13 | major | ADRs | **A9 and A10 exist only in a plan.** DEVPLAN D3 introduces two machine-enforced layering rules and CFGOP-003 implements them, but ADR-041 — the ADR whose entire subject is the enforced rule table — says nothing about them. A rule whose rationale lives in a milestone plan is a rule the next milestone deletes. | `DEVPLAN` §5.2, §10 D3; `docs/adr/ADR-041` §"rule table" | **Fixed here** — [ADR-041 Amendment 3](../../adr/ADR-041-layering-rules-machine-enforced.md) records A9, A10 and the refusal to widen A1. |
| F-14 | minor | task graph | `DEVPLAN` §6.2 is titled "Ordered task list" but is grouped by lane; four edges point backwards in the listing (CFGOP-004 before KAFKA-007, CLADP-002 and STORE-009; CLAPI-004 before CFGOP-005). A worker reading it as an order stalls. This is M0 finding F-07 in a milder form. | `DEVPLAN` §6.2 | **Fixed here** — a paragraph under the table says the column, not the row order, is authoritative. |
| F-15 | minor | dependencies | `DEPENDENCY_MATRIX.md` pins `org.testcontainers:testcontainers-kafka` 2.0.5; CFGOP-004, KAFKA-007 and CFGOP-005 write `org.testcontainers:kafka:2.0.5`. One of them does not resolve. | `DEPENDENCY_MATRIX.md:153`; `tasks/CFGOP-004.md:318`, `KAFKA-007.md:79,249` | Assigned: CFGOP-004 is the first task to resolve it, settles it, and corrects the matrix in its own commit. An open-questions row added to `DEPENDENCY_MATRIX.md`. |
| F-16 | minor | docs | `docs/operations/metadata-store.md` §4.2 step 3 documents `POST /internal/v1/store/rekey`. No M1 task builds it — D6 ships one write endpoint. The operator page describes a 404. | `docs/operations/metadata-store.md` §4.2; `DEVPLAN` §10 D6; `tasks/STORE-002.md` | Assigned to CFGOP-008: replace step 3 with STORE-002's manual rotation procedure and record the endpoint in `TECH_DEBT.md`. |
| F-17 | minor | ownership | `DEVPLAN` §6.5 gives the STORE area `metadata-store.md` §2–6 and names no owner for §1 or §7 onward, while STORE-004 edits §1 and OT-010's operator guidance has to live somewhere. | `DEVPLAN` §6.5; `tasks/STORE-004.md` | Assigned to CFGOP-008 (§7 onward, including OT-010); STORE-004 keeps §1's key table. |
| F-18 | minor | module map | `DEVPLAN` §5.1's dependency lists for `libs/kafka` and `libs/cache` omit `libs.observability`, while `docs/operations/observability.md` already publishes `kui.kafka.admin.duration` as an M1 metric and ADR-016 mandates `kui.cache.*`. Both specs add the edge and justify it; the plan should say so. | `DEVPLAN` §5.1; `tasks/KAFKA-004.md:164`, `KAFKA-010.md:92` | **Fixed here** — both edges added to §5.1 and to the "why each is legal" paragraph. A10 governs Kafka on a classpath, not metrics; `libs.http → libs.observability` is the precedent. |
| F-19 | minor | module map | `libs.config` needs `libs.kafkaAuth` as well as `libs.kafka` (F-04), and §5.1 lists only the latter. | `DEVPLAN` §5.1; `tasks/STORE-005.md` | **Fixed here** — edge added; already inside A10's allow-list. |
| F-20 | minor | error codes | `ErrorCode` has no group-not-found code and `ErrorCode.scala` is outside the KAFKA lane's file boundary, so KAFKA-005 maps `GroupIdNotFoundException` to `KUI-INVALID-STATE` (409). | `tasks/KAFKA-005.md:354`; `DEVPLAN` §6.5 | **Accepted for M1.** M1 has no consumer-group feature, so the mapping is unreachable in shipped code. M2's consumer lane adds the code and the row; KAFKA-005 already records it as a deviation. STORE-001 owns `ErrorCode.scala` for M1's additions, stated so no other area edits it. |
| F-21 | minor | test plan | `DEVPLAN` §7's JAAS property test asks a password containing a newline to round-trip. `StreamTokenizer` terminates a quoted JAAS value at a line break and there is no escape, so the test as written is unsatisfiable. | `DEVPLAN` §7 "Property rendering"; `tasks/KAFKA-002.md` | **Accepted.** KAFKA-002 narrows it correctly: a password containing a line break is **refused at validation** with a named error, and the round-trip property covers quotes, backslashes, spaces and `=`. Refusing is the right behaviour; the plan's wording is the thing that was wrong. |
| F-22 | minor | test coverage | The store's own integration suite runs PLAINTEXT only; the store cluster is never exercised under SASL or SSL, though operators will run it that way. | `tasks/STORE-009.md` | **Accepted, deliberate.** The three-mode matrix covers *managed* clusters, which is the exit criterion; the store uses the same `ConnectionProperties` renderer, so the risk is configuration wiring rather than rendering. STORE-009 records it and CFGOP-008 enters it in `TECH_DEBT.md` with M2 as the exit condition. |

**Counts:** 3 blockers, 10 majors, 9 minors. All 13 blockers and majors are resolved in this
review; 5 of the 9 minors are resolved here and 4 are assigned to a named task.

## 4. The four M0 process problems, checked

| M0 finding | Repeated in M1? | Where |
| --- | --- | --- |
| **Nothing tested the seams between components** | **No.** This is the plan's strongest area. CLADP-001's `ClusterAdminContract` is one abstract suite run against both the fake port the application tests use and a live broker — the exact artifact M0 lacked. CFGOP-005 drives three security modes through the *contract client*; `apps.allinone.test` boots the whole graph against one broker; CFGOP-007 drives a real browser against separate processes. | — |
| **Strings typed twice in two files** | **Yes, twice.** The Kafka container topology (F-11, fixed — one fixture, one file). And three type pairs deliberately defined in both `libs/kafka` and the cluster domain: `ClusterFeature`, `BatchResult`/`PartialResult`, `SkipReason`. | The duplication is **accepted**: rule A5 forbids `libs/kafka` depending on a service and A1 forbids the domain depending on `libs/kafka`, so one definition is not available without breaking the layering the milestone exists to prove. The mitigation is that each pair is bridged by an *exhaustive match* (which the compiler checks in one direction) plus, now, the partition invariant asserted on **both** sides (F-05). CFGOP-008 records it in `TECH_DEBT.md`. |
| **Documented rules went unenforced** | **No — improved.** A9 and A10 ship with build tests that assert each allow-list entry individually (CFGOP-003), and the review refused the one request to widen an existing rule without an enforcing task landing first (F-02). | ADR-041 Amendment 3 |
| **Cancellation paths systematically unconsidered** | **Yes.** 52 of 57 specs never mention cancellation, including every long-running fiber in the milestone. | F-07, fixed: eight specs amended, and `DEVPLAN` §9 item 12a makes a named cancellation test a condition of done. |

## 5. Decisions the spec writers took that no ADR covered

Judged one by one: promote, fold, or leave.

| Decision | Judgement |
| --- | --- |
| Raw `Admin` for admin work, fs2-kafka for consumers and producers (KAFKA-004) | **Promoted** — ADR-006 Amendment 1. It reverses an Accepted ADR's Decision; that is never a task-level detail. |
| The connection ADT lives in `libs/kernel` (DEVPLAN D1) | **Promoted** — ADR-022 Amendment 1. Same reason, and four modules are written against it. |
| Store envelope, `$secret`/`$enc` marking, AAD = `key\|fieldPath`, `keyId` rotation (STORE-001/002) | **Promoted** — new ADR-044. It is a persisted format on a compacted topic; it outlives the code and cannot be changed without a migration. |
| A9, A10, and A1 not widened (DEVPLAN D3, CLDOM-003) | **Promoted** — ADR-041 Amendment 3. ADR-041's whole subject is the enforced rule table. |
| `ClusterFeatures` has three sets, not two (KAFKA-009) | **Folded** into ADR-030's consequences by CFGOP-008, and enforced now as a type across both modules (F-05). It is a refinement of "capability gating rather than version assumptions", not a new decision. |
| A password containing a line break is refused, not rendered (KAFKA-002) | **Left task-level**, and the DEVPLAN's contradicting sentence corrected (F-21). It is an implementation consequence of the JAAS grammar, not a choice. |
| Inline keystores carried as base64 `Secret[String]`, not `Array[Byte]` (KAFKA-001) | **Left task-level.** Driven by cross-compilation and case-class equality; no downstream module can observe the difference. |
| `ClusterConnection` as a fifth kernel type (KAFKA-001) | **Left task-level**, now named in ADR-022 Amendment 1's package list so the other lanes have one name to use. |
| `ProducersAndTransactions` and `TieredStorage` are version-derived, not probed (KAFKA-009) | **Left task-level.** Both real probes need a topic and M1 has no topic port; the alternative is exactly the M2 scope creep R-11 warns about. |
| Managed-service `describeConfigs` downgrades log at DEBUG (KAFKA-008) | **Left task-level.** On MSK Serverless this is the permanent steady state; WARN would be noise. |
| `StoreState.apply` accepts a record only at `version == current + 1` (STORE-003/006) | **Left task-level**, but it is the correctness guarantee for concurrent writers rather than a detail — STORE-006 states it as an invariant with a property test, which is the right home. |
| A refused call is a typed `Left`, never `Right(Nil)` (CLADP-002) | **Left task-level**, correctly decided against the reference product: an empty table reads as "a broker with no settings". Paired with ADR-039 §6 — it must not dim a capability. |
| Two independent `Section` levels in the dashboard response (CLAPI-004/007) | **Left task-level.** It implements D4, which the DEVPLAN already owns. |
| Removals suppressed while decode failures are non-zero (CLADP-005) | **Left task-level.** A good instinct — one briefly-undecodable record must not delete a cluster from every replica — and contained in one adapter. |
| Registry overlay is whole-profile replacement keyed on `ClusterId` (CLDOM-004) | **Left task-level.** Field-level merge would let removing `security` silently inherit the YAML's credentials; that argument belongs in the spec, where it is. |
| `/internal/v1/clusters/{id}/profile` is redacted in M1 (CLAPI-003) | **Left task-level**, with a `TECH_DEBT.md` entry: M2's first consumer decides what it actually needs. R-12 already names this endpoint as a leak path to test. |
| `StoreHealth` is undebounced (STORE-008) | **Left task-level** and right: ADR-039's asymmetric debounce belongs to the capability fold, not to its input. |

## 6. Fixes applied in this review

**ADRs** — [ADR-006 Amendment 1](../../adr/ADR-006-fs2-kafka-and-admin-ports.md),
[ADR-022 Amendment 1](../../adr/ADR-022-typed-kafka-cluster-auth.md),
[ADR-041 Amendment 3](../../adr/ADR-041-layering-rules-machine-enforced.md), and new
[ADR-044](../../adr/ADR-044-store-record-envelope-and-field-encryption.md), indexed in
`DECISIONS.md`.

**`DEVPLAN.md`** — §5.1 and the legality paragraph gain the `libs.observability` and
`libs.kafkaAuth` edges; §6.2 corrects eight dependency cells and CLDOM-001's title, and gains a
warning that the table is lane-grouped rather than topological; §6.5 grants CLAPI-004 the `Ping`
deletion exception; §9 gains item 12a (cancellation tests as a condition of done); §10 D5 is
corrected to say which dashboard numbers M1 cannot produce.

**Task specs** — CLDOM-001, CLAPI-002, CLAPI-004 (F-01); CLDOM-003, CLDOM-004, CLADP-003,
CLADP-005 (F-02); KAFKA-004, STORE-005 (F-03, F-04); CLDOM-002, CLDOM-005, CLDOM-007, CLADP-002,
CLAPI-008 (F-05); CFGOP-005 (F-06); STORE-006 … STORE-009, CLADP-002, CLADP-005, CLAPI-005,
KAFKA-010 (F-07); KAFKA-007, CFGOP-004 (F-11, F-15); CLUI-003 (F-12); CFGOP-008 (F-16, F-17, and
the ADR work already done here).

**`DEPENDENCY_MATRIX.md`** — an open-questions row for the Testcontainers Kafka artifact id.

## 7. Verdict

**APPROVED WITH CONDITIONS.**

The plan is sound and unusually well evidenced. Its research-to-spec traceability is better than
M0's, its seam coverage fixes M0's worst process failure, and the seven agents caught more real
problems in each other's upstream documents than this review added. The three blockers were all
of one kind — two lanes each believing the other owned a shared edge — which is the expected
failure mode of parallel authoring and is exactly what this gate is for. All three are fixed.

Four conditions, none of which blocks the first task:

1. **CFGOP-004 runs in the first week**, as `DEVPLAN` §6.4 already argues, and settles F-15 in
   its own commit. It is now a dependency of KAFKA-007, CLADP-002 and STORE-009, so three lanes
   wait on it; a negative result on secured containers must surface with weeks of slack, not
   days.
2. **Every one of the eight cancellation requirements added under F-07 ships with its test.**
   `DEVPLAN` §9 item 12a is a gate on the milestone, not a suggestion. If a spec's author
   concludes a path genuinely cannot be cancelled, that reasoning goes in the Implementation
   Report.
3. **CFGOP-008 completes the four documentation items assigned to it** (F-16, F-17, the ADR-042
   and ADR-030 consequences sections, and the `TECH_DEBT.md` entries for F-20, F-22 and the
   dual-definition duplication in §4).
4. **The dual definitions in `libs/kafka` and the cluster domain are re-examined at M2 grooming.**
   Three type pairs today is a defensible price for the layering; ten pairs, once `TopicAdmin`
   and `GroupAdmin` arrive, is not. The question to answer then is whether a
   `libs/kafka-model`-shaped pure module that both sides may see is cheaper than the mappers.

Implementation may start. KAFKA-001 first, with CFGOP-004, STORE-001 and CLUI-001 in parallel.
