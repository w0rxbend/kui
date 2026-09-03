# M0 grooming gate review (PLAN §39, step G6)

- **Date:** 2026-09-03
- **Reviewer:** CTO
- **Gate:** G6 — the single written grooming review before implementation starts on M0.
- **Verdict:** **APPROVED WITH CONDITIONS** (see [Verdict](#verdict)).

---

## 1. What was reviewed

| Artifact | Extent |
| --- | --- |
| `PLAN.md` | Read as the constitution. Not modified — §3, §16, §18, §19 and Part VII were the yardstick. |
| `ARCHITECTURE.md` | All 16 sections. |
| `docs/domain/context-map.md` | Whole file. |
| `docs/adr/ADR-001` … `ADR-038` | All 38, in full. |
| `DECISIONS.md`, `DEPENDENCY_MATRIX.md`, `TECH_DEBT.md` | Whole files. |
| `docs/ROADMAP.md`, `docs/FEATURE_MATRIX.md` | Whole files, including all 183 feature rows. |
| `docs/plans/M0/DEVPLAN.md` | Whole file. |
| `docs/plans/M0/tasks/*.md` | All 57 task specs. |
| `research/` | Consulted where a document cited it as evidence. |

Checked for: internal contradictions; violations of the PLAN §3 constraints; gaps (P0 features
with no milestone, exit criteria with no task, unsatisfiable task ordering, ADR decisions with
no task, blocking TBDs); the ten decisions the G5 planner took that no ADR covered; and whether
the task graph keeps the build green after every task.

## 2. What the review did not find

Recording the clean results, because they are the load-bearing ones:

- **P0 coverage is complete.** All 70 P0 rows in `docs/FEATURE_MATRIX.md` carry a milestone,
  every milestone named exists in `docs/ROADMAP.md`, and the per-milestone P0 counts reconcile
  exactly with both files' own summary tables. Zero unassigned, zero dangling.
- **Every M0 exit criterion has at least one satisfying task.** One is only partially
  satisfiable (F-14, below), which was already known and accepted.
- **The task graph is a DAG with no dangling references,** and after the fixes below it is
  topologically consistent with the order §6.2 lists it in.
- **All 38 pre-existing ADRs are Accepted, correctly titled and correctly indexed** in
  `DECISIONS.md`; every link resolves; no title, status or date disagrees with the ADR file.
- **No version pin in any ADR contradicts `DEPENDENCY_MATRIX.md`.** Every one of ~55 pins
  matches exactly. The defects found were attribution and coverage, not disagreement.
- **The M0 module layout obeys the hexagon.** No proposed module dependency crosses the
  direction `ARCHITECTURE.md` §3 states, once F-01 is corrected.

## 3. Findings

Severity: **blocker** = implementation cannot start; **major** = will cause rework or a wrong
build if not resolved; **minor** = wrong on paper, cheap to fix, no build consequence.

| ID | Sev | Area | Finding | Evidence | Resolution |
| --- | --- | --- | --- | --- | --- |
| F-01 | blocker | layering | `DEVPLAN.md` §5.3 gave `services.gateway.application` a dependency on `libs.http` and `libs.contractsCore`, both of which `BUILD-005`'s architecture rule A3 forbids. Building the module map as written makes `./mill checkArchitecture` fail on the first gateway task, so the build cannot be green. The underlying question — may an `application` layer touch the wire? — was answered one way in `SVC-001` and the opposite way in the gateway's module map. | `docs/plans/M0/DEVPLAN.md` §5.3; `docs/plans/M0/tasks/BUILD-005.md` rule A3; `docs/plans/M0/tasks/SVC-001.md` ("`libs/contracts-core` **is not** a dependency of `application`"); `docs/plans/M0/tasks/GW-002.md` ("`application` stays free of Tapir") | **New ADR-041** + fixed here: the module map and rule A3 now agree, and `services.gateway.api` owns the mapping. |
| F-02 | major | ADR refs | Seven ADRs cite a research-document *candidate* number in their Evidence section that was never renumbered, so each points at a real but unrelated ADR — e.g. the CSRF ADR cites "ADR-017 candidate", and ADR-017 is CEL smart filters. `DECISIONS.md` documents the renumbering that these bodies never applied. | `docs/adr/ADR-019:34`, `ADR-020:36`, `ADR-021:43`, `ADR-022:44`, `ADR-023:39`, `ADR-024:37`, `ADR-025:35`; `DECISIONS.md:49-52` | Fixed here — each now self-references, matching the convention ADR-011 and ADR-015 already used. |
| F-03 | major | ADR refs | Five feature rows and three roadmap milestones cite ADR numbers from the same superseded scheme, mixing stale and correct numbers inside one table: CSS/facades cited as 019/020 instead of 024/025, typed cluster auth as 020 instead of 022, CSRF as 017 instead of 019, RBAC as 019 instead of 021, signed principal as 018 instead of 020. | `docs/ROADMAP.md:57-58, 95, 230`; `docs/FEATURE_MATRIX.md:50, 178, 204, 227, 339` | Fixed here. |
| F-04 | major | services | `kui-config-service` exists in three incompatible states at once. ADR-004 and `ARCHITECTURE.md` §2.2 dissolve it into three ownerships; `docs/ROADMAP.md` M8 still introduces a service `config`; five feature rows still name `config` as an owner; and DR-21 still calls the question open, with a *fourth* answer (merge into the gateway). | `docs/adr/ADR-004:23`; `ARCHITECTURE.md:79`; `docs/ROADMAP.md:286, 304`; `docs/FEATURE_MATRIX.md:49, 214-216, 310, 390` | Fixed here — ADR-004 is authoritative; roadmap, owner columns and DR-21 now match it. |
| F-05 | major | PLAN §3 | `docs/FEATURE_MATRIX.md` OT-004 schedules one PostgreSQL instance owned jointly by `config` and `identity`. PLAN §3 forbids a shared database outright, and ADR-036 rejects a relational store at all. Three documents disagree about something PLAN calls non-negotiable. | `docs/FEATURE_MATRIX.md:311`; `PLAN.md:75`; `ARCHITECTURE.md:561`; `docs/adr/ADR-036` | **Accepted risk for M0** (P1, M6). Row annotated with the conflict and the two ways out; `TECH_DEBT.md` **TD-014** opened with an exit condition before M6 grooming closes. **Resolved 2026-09-03 by [ADR-042](../../adr/ADR-042-kafka-backed-metadata-store.md)**: metadata lives in internal compacted Kafka topics, OT-004 rewritten and moved to M1, TD-014 closed. |
| F-06 | major | PLAN §16.6 | `ARCHITECTURE.md` §5 permits direct service→service calls on `/internal/v1` (every Kafka-facing service → cluster-service; metrics → topic/consumer snapshots). PLAN §16.6 says services never call each other "except through the gateway's contracts". `ARCHITECTURE.md` reads that as "the gateway-*visible* contracts" and never records the reinterpretation. PLAN §3's own wording ("all inter-service traffic uses the published Tapir contract of the callee") is satisfied either way, so this is a §16.6 wording question, not a §3 violation. | `ARCHITECTURE.md:421-424, 544`; `PLAN.md:426-428`; `docs/domain/context-map.md:36` | **Accepted risk for M0** — no service→service call exists before M1. Added to `STATUS.md` "Amendments to PLAN.md required" and to `DECISIONS.md` "not yet taken"; must be settled in M1 grooming. |
| F-07 | major | task order | `DEVPLAN.md` §6.2 listed CFG-002 before OBS-001, which CFG-002 depends on — the only forward reference in the table, and one that would leave a worker following the list unable to start. Two task specs also declared dependencies the DEVPLAN table dropped: HTTP-004 on BUILD-006, UI-013 on BLOCKERS B-001. | `docs/plans/M0/DEVPLAN.md` §6.2; `docs/plans/M0/tasks/HTTP-004.md:9`; `docs/plans/M0/tasks/UI-013.md:8-9` | Fixed here. The table is now a verified topological order: no task precedes a dependency. |
| F-08 | major | error codes | `HTTP-001` left an unresolved deliberation in its acceptance table, containing the same error code spelled two ways — `KUI-NOT-FOUND-ROUTE` in the body and `KUI-ROUTE-NOT-FOUND` in the decision clause — with the reasoning that produced it still on the page. Only the second spelling matches the `KUI-<AREA>-<NAME>` convention. Separately, `KUI-CURSOR-TOO-LARGE` is used by ADR-026 and by TD-005 but appears in no code table and no enum. | `docs/plans/M0/tasks/HTTP-001.md:113, 120`; `docs/adr/ADR-026:42`; `docs/adr/ADR-034:21-28` | **ADR-034 amendment 1** + fixed here. Both codes are now in ADR-034's table and in the `KERN-002` enum. |
| F-09 | major | contracts | `ARCHITECTURE.md` §4.5 and `KERN-005` describe the same capability types four ways: different package (`kui.gateway.capability` vs `kui.gateway.application.capability`), different key types (`ServiceId`/`ClusterId` vs bare `String`), different field names (`suggestedPollInterval` vs `suggestedPollIntervalMs`), and disagreement over whether they live in the gateway or in `contracts-core`. Bare `String` keys also lose the type safety PLAN §2.2 requires, for types the shared kernel already defines. | `ARCHITECTURE.md` §4.5; `docs/plans/M0/tasks/KERN-005.md:72, 85-87`; `docs/domain/context-map.md` shared-kernel list; `docs/plans/M0/tasks/SVC-003.md:22` (`ServiceId("cluster")`) | Fixed here in both directions: §4.5 states the two-layer split explicitly (ADR-041), and `KERN-005` uses the kernel ids, whose Tapir codecs `KERN-004` already delivers. |
| F-10 | minor | ADR refs | Two mis-cites in `ARCHITECTURE.md`: Kouncil-style table paging cites ADR-029 (event tracking) where it means ADR-026 (paging); `libs/contracts-core` cites ADR-006 (fs2-kafka), which has nothing to do with a Kafka-free cross-compiled DTO module. | `ARCHITECTURE.md:157, 505` | Fixed here (→ ADR-026; → ADR-003 + ADR-007). |
| F-11 | minor | roadmap | `docs/ROADMAP.md` M0 contradicted itself: its module list omitted `frontend/ui-clusters`, `deployment/docker` and `e2e`, while its own exit criteria require a separate feature bundle, Docker images and an E2E suite; and its "Introduces" line claimed "all of `libs/` except kafka, kafka-auth, serde", which is a different set from the seven libraries the same milestone lists. | `docs/ROADMAP.md:55-56, 82` vs `:69-70`; `docs/plans/M0/DEVPLAN.md` §5.2, §5.4, §5.5 | Fixed here. |
| F-12 | minor | services | DR-20 (merge the security service) was recorded as undecided in `docs/FEATURE_MATRIX.md` and `docs/ROADMAP.md` while `ARCHITECTURE.md` §2.1 and ADR-004 had already decided it: it stays separate. | `docs/FEATURE_MATRIX.md:389`; `docs/ROADMAP.md:274`; `ARCHITECTURE.md:73`; `docs/adr/ADR-004` | Fixed here — DR-20 now records the ADR-004 ruling and its reasoning. |
| F-13 | minor | dependencies | Four `DEPENDENCY_MATRIX.md` attribution errors: `tapir-iron`, `iron-circe` and `scala-java-time` omit `libs/contracts-core` from their module lists although `KERN-004` requires them there; `datasketches` cites ADR-006, which never mentions it; `fs2-data-csv` cites ADR-004, which admits no dependency at all. | `DEPENDENCY_MATRIX.md:41, 68, 108, 110, 134`; `docs/plans/M0/tasks/KERN-004.md:107-113` | Fixed here. `fs2-data-csv` still lacks a proper ADR — flagged in the row for M5 grooming. |
| F-14 | minor | exit criteria | The M0 exit criterion "design tokens … exist in light and dark themes" can only be met by `PLACEHOLDER`-marked tokens, because the Claude Design import is blocked (BLOCKERS B-001) and the closing task UI-013 is blocked with it. | `docs/ROADMAP.md:75-76`; `docs/plans/M0/DEVPLAN.md` R-1, §9.7; `TECH_DEBT.md` TD-007 | **Accepted risk** — already the plan's own position: NX-007 ships `PARTIAL`, no M0 task depends on the import, and every component reads CSS custom properties so only the token file changes later. |
| F-15 | minor | task specs | `DEVPLAN.md` §5.3 called the sample service's skeleton "the full six-layer skeleton" while creating five layers; `GW-003` described `CapabilityInputs` as "the three raw inputs" while defining four fields; §6.3's "critical path" was not a dependency chain (it skipped KERN-003 and UI-001…UI-009 and asserted an edge from SVC-004 to GW-001 that does not exist) and miscounted its own length; `INFRA-003` was titled "dev server, proxy and README" for a task whose scope says no proxy is needed; golden files were placed under an sbt-style `src/test/resources` in one place and Mill-style `test/resources` in another. | `docs/plans/M0/DEVPLAN.md` §5.3, §6.3, §7; `docs/plans/M0/tasks/GW-003.md:92`; `docs/plans/M0/tasks/INFRA-003.md:1` | Fixed here; §6.3 now states the verified 17-task longest chain. |
| F-16 | minor | status | `STATUS.md` recorded G5 as "Not started" while the DEVPLAN and all 57 task specs exist, and `docs/domain/context-map.md` declares itself "accepted (G2/G3)" while `STATUS.md` records G3 as in progress with no ADR accepted. | `STATUS.md:12-16`; `docs/domain/context-map.md:3` | Fixed here — `STATUS.md` now records G5 complete and G6 reviewed; the 38 ADRs are Accepted, so G3's precondition is satisfied. |
| F-17 | minor | naming | Every service has three spellings (`kui-cluster-service` / `kui-cluster` / `cluster`) and every module up to four (`libs/kernel` / `kui-kernel` / `libs.kernel` / and for frontend, PLAN's `kui-ui-kernel`), with no mapping rule written down anywhere. Nothing is wrong; nothing says which form belongs where. | `PLAN.md:517-529` vs `ARCHITECTURE.md:662-675` vs `docs/plans/M0/DEVPLAN.md` §5 | **Accepted** for M0 — the forms are consistent *within* each document and `ServiceId` values are unambiguous. A naming key in `ARCHITECTURE.md` §16 is worth adding when someone next edits it. |

**Counts: 1 blocker, 8 major, 8 minor.** The blocker and seven of the eight majors are fixed in
this pass; F-05 and F-06 are accepted risks with recorded exit conditions, and neither can bite
before M1.

## 4. The ten G5 decisions with no ADR

PLAN §39 requires that a decision which constrains later work is written down where later work
will look for it. Each of the ten was judged on one question: **would a worker in M1–M8 who has
not read the M0 task specs go wrong without it?**

| # | Decision | Judgment | Where it went |
| --- | --- | --- | --- |
| 1 | Capability-fold precedence (`NotConfigured > Unavailable > Degraded > Available`) and sticky `since` | **New ADR.** Eleven services join this registry in M1–M8; the precedence order is the definition of "is this feature usable", and it lives in a task spec for a task that is done once. | **ADR-039** §2, §3 |
| 2 | Asymmetric debounce — 10 s before publishing a failure, instant on recovery | **New ADR.** A reasonable engineer would make it symmetric; the asymmetry is the whole design and needs its reasoning attached. | **ADR-039** §4 |
| 3 | `Degraded(Starting)` for a not-yet-polled capability | **Fold into ADR-032.** It is a rendering-model question, and ADR-032 owns the state model. It appears in two places (gateway fold and browser store) that must agree, so it needs one home. | **ADR-032 amendment 2**, cross-referenced from ADR-039 §5 |
| 4 | `KUI-ROUTE-NOT-FOUND` | **Fold into ADR-034.** A new error code is an addition to the code table, not a new decision area. | **ADR-034 amendment 1** |
| 5 | `ErrorCode.description` as a constructor parameter | **Fold into ADR-034.** Same reason; it is how ADR-034's own "generated from the enum" promise is kept. | **ADR-034 amendment 2** |
| 6 | Gateway-generated correlation ids, never client-supplied | **New ADR.** This is a security boundary: it decides what a browser is allowed to assert about a request. It spans ADR-009, ADR-019 and ADR-020, so folding it into any one of them hides it from the other two, and `ARCHITECTURE.md` §5 stated the stripping rule without stating this one. | **ADR-040** |
| 7 | An `ApplicationError` never dims a capability | **Fold into ADR-039.** It is a rule about what feeds the fold, so it belongs beside the fold. Recorded as its own numbered section because it is the rule most likely to be got wrong per-service. | **ADR-039** §6 |
| 8 | `application` never depends on `contracts-core` | **New ADR.** It is a general layering rule, it contradicted the gateway module map (F-01), and it is enforced by CI — three reasons it cannot stay a note in one task spec. | **ADR-041** §1 |
| 9 | Static route patterns beside dynamic-import thunks | **Fold into ADR-012.** It is a constraint on ADR-012's own registry, and `UI-009` says outright that "the ADRs do not" settle it — exactly the gap this gate exists to close. | **ADR-012 amendment 2** |
| 10 | Dev loop needs no proxy | **Fold into ADR-012.** It corrects a bullet already in ADR-012; leaving the old text would have a worker build a proxy the design does not need. | **ADR-012 amendment 1** |
| 11 | Machine-enforced architecture rules (`./mill checkArchitecture`) | **New ADR** (folded with #8). PLAN §3 says boundaries are "enforced by module dependencies" without saying what enforces them; the rule table is the answer and needs to outlive `BUILD-005`. | **ADR-041** §2, §3 |
| — | `Forbidden` outranks every health state | **Fold into ADR-032.** A precedence rule inside the state model ADR-032 defines. It is an information-disclosure rule, so its reasoning matters more than the rule. | **ADR-032 amendment 1** |

Three new ADRs, six amendments to three existing ADRs, no decision left in a task spec alone.

## 5. Is the graph green after every task?

Yes, after F-01 and F-07 were fixed. Verified rather than assumed:

- The 57 declared dependency edges form a DAG. No cycles, no reference to a task that does not
  exist.
- After moving CFG-002 below OBS-002, **no task in `DEVPLAN.md` §6.2 appears before a task it
  depends on**, so a worker taking the list top to bottom is never blocked.
- Longest real chain: 17 tasks, `BUILD-001 → … → E2E-002`. §6.3 now states that chain instead
  of the illustrative one it had, which asserted two edges the table does not declare.
- Each task is self-contained in the way that keeps the tree compiling: a task that adds a
  module adds that module's first test, and a task that changes a contract regenerates the
  committed OpenAPI document in the same commit (`DEVPLAN.md` §6).
- The one remaining sequencing hazard was `ErrorCode`: `KERN-002` created the enum, `KERN-008`
  added a field to it and `HTTP-001` added a case, from two different lanes. All three now
  land in `KERN-002`, so the enum is complete when it is created and the two later tasks only
  consume it.

## 6. Fixes applied in this pass

**New files**

- `docs/adr/ADR-039-capability-fold.md`
- `docs/adr/ADR-040-edge-header-policy.md`
- `docs/adr/ADR-041-layering-rules-machine-enforced.md`
- `docs/plans/M0/GATE-REVIEW.md` (this file)

**Amendments to existing ADRs**

- `ADR-012` — amendment 1 (proxy-free dev loop, replacing the old dev-loop bullet), amendment 2
  (static route patterns beside dynamic imports).
- `ADR-032` — amendment 1 (`Forbidden` precedence), amendment 2 (`Degraded(Starting)`).
- `ADR-034` — amendment 1 (`KUI-ROUTE-NOT-FOUND`, `KUI-CURSOR-TOO-LARGE`, both added to the
  code table), amendment 2 (`ErrorCode.description`).
- `ADR-019`, `ADR-020`, `ADR-021`, `ADR-022`, `ADR-023`, `ADR-024`, `ADR-025` — Evidence-section
  candidate numbers corrected to the accepted numbering.

**`ARCHITECTURE.md`**

- §3 — gateway module set now includes `contract` (which the frontend depends on) and states
  that it has no `domain` and no `infrastructure`; added the ADR-041 enforcement paragraph.
- §4 — `libs/contracts-core` ADR column `ADR-006` → `ADR-003, ADR-007`.
- §4.5 — retitled; states the application/wire two-layer split; package corrected to
  `kui.gateway.application.capability`; DTO differences spelled out; inputs list completed with
  p95 and a pointer to ADR-039.
- §5 — correlation-id row and the strip paragraph now state the ADR-040 policy (prefix match,
  applied before routing; `traceparent` excluded).
- §8 — table-paging citation `ADR-029` → `ADR-026`.

**`docs/ROADMAP.md`** — M0 module list, "Introduces" line and library list made consistent with
its own exit criteria and with `DEVPLAN.md` §5; M0/M1/M6 ADR numbers corrected; M7 and M8
service lists aligned with ADR-004 (security separate, config dissolved).

**`docs/FEATURE_MATRIX.md`** — five stale ADR-candidate numbers corrected; `config` owner
columns rerouted to the contexts ADR-004 assigns; DR-20 and DR-21 recorded as settled with
their reasoning; OT-004 annotated with the PLAN §3 conflict.

**`DEPENDENCY_MATRIX.md`** — `libs/contracts-core` added to the `tapir-iron`, `iron-circe` and
`scala-java-time` module lists; `datasketches` and `fs2-data-csv` ADR attributions corrected;
the two M0-due open questions now name BUILD-006 as the task that closes them; the
`describeShareGroups` discrepancy with ADR-006 recorded.

**`DECISIONS.md`** — ADR-039 … ADR-041 indexed; a note recording which six G5 decisions became
amendments rather than new ADRs; two entries added to "deliberately not yet taken".

**`TECH_DEBT.md`** — TD-014 (OT-004 shared database vs PLAN §3), TD-015 (`ui-clusters` pattern
decided against a trivial page).

**`docs/plans/M0/DEVPLAN.md`** — §4 ADR table extended with ADR-039 … ADR-041; §5.3 gateway
`application` dependencies corrected and the ADR-041 rule stated beside the module map; §5.3
"six-layer" wording corrected; §6.2 CFG-002 reordered, HTTP-004 and UI-013 dependencies
completed, INFRA-003 retitled; §6.3 replaced with the verified longest chain; §7 golden-file
path corrected to the Mill layout.

**Task specs** — `HTTP-001` (error-code spelling and the leftover deliberation removed),
`KERN-002` (`description` field, `RouteNotFound`, `CursorTooLarge`), `KERN-008` (consumes the
field instead of adding it), `KERN-005` (kernel id types), `GW-001` (ADR-040 policy),
`GW-003` ("four raw inputs", ADR-039 and ADR-041 references), `BUILD-005` (rule A3 names
`libs.contractsCore`; ADR-041 reference), `UI-009` (route patterns cite ADR-012 amendment 2),
`INFRA-003` (title and ADR-012 reference).

## 7. Verdict

**APPROVED WITH CONDITIONS.** Implementation of M0 may start now, beginning with BUILD-001.

The plan is in good shape. The 57 task specs are unusually concrete — signatures, acceptance
commands, named files, per-task test lists — the fault-isolation property that justifies the
whole microservice decomposition is proven end to end by a single milestone, and the three
tasks the plan wants started first (BUILD-006, CFG-001, KERN-006) are exactly the three that
could invalidate later work. The one blocker was a contradiction between two task specs that
had each answered the same layering question correctly and separately; it is resolved.

Conditions, none of which gate the first commit:

1. **F-05 (shared database).** Before M6 grooming closes, an ADR must either supersede ADR-036
   with one store per owning context, or defer OT-004. `TECH_DEBT.md` TD-014 carries it. No M0
   or M1 work may introduce a store shared by two services in the meantime.
2. **F-06 (service→service calls).** Before the first M1 task that makes one, PLAN §16.6 must
   be amended to say plainly whether `/internal/v1` calls between services are permitted, and
   the answer recorded in ADR-004. `ARCHITECTURE.md` §5 currently assumes yes; nothing has been
   built on that assumption yet, which is why this is cheap now and expensive in M2.
3. **F-14 (design tokens).** M0 closes with NX-007 `PARTIAL` and TD-007 open. That is accepted,
   on the plan's own terms: no M0 task may take a dependency on the Claude Design import, and
   every kernel component must read CSS custom properties so that UI-013 changes one file.
4. **Standing.** `./mill checkArchitecture` (BUILD-005) must be proven to fail — a deliberate
   violating edge added, the failure observed, the edge reverted — and the message recorded in
   its Implementation Report. ADR-041 depends on that check actually working, and a check
   nobody has watched fail is a check nobody knows works.

Re-review is not required. Findings that surface during implementation follow the normal ADR
route (`PLAN.md` §39): new evidence, a superseding ADR, a row in `TECH_DEBT.md`.

---

## Addendum — 2026-09-03, after the gate

**F-14 is closed, not accepted.** The finding recorded the design-token exit criterion as only
partially satisfiable because BLOCKERS B-001 (the Claude Design import) is owned outside the
execution loop, and it accepted `NX-007 = PARTIAL` as the plan's own position.

That acceptance has been replaced by a decision, on the principle that grooming must decide
rather than wait: **KUI owns its design token set.** `docs/plans/M0/tasks/UI-002.md` now takes
the decision explicitly, from the competitor evidence already gathered — Kafbat's three-state
theming and ~1 600-line component-scoped `theme.ts` (adopted in spirit, rejected in shape) and
Kouncil's single palette with no dark mode (rejected) — yielding ~40 semantic CSS custom
properties, no component-scoped tokens, provenance comments per value, and WCAG AA enforced by
`ContrastSuite`. Neither reference enforces contrast; KUI does.

Consequences applied:

| Artifact | Change |
| --- | --- |
| `docs/plans/M0/tasks/UI-002.md` | Retitled "KUI design tokens"; carries the decision table and its evidence; `PLACEHOLDER` markers replaced by provenance comments |
| `docs/plans/M0/tasks/UI-013.md` | Demoted from blocked follow-up to optional, unscheduled reconciliation; a WCAG failure in an import is adjusted, not adopted |
| `docs/plans/M0/DEVPLAN.md` | R-1 rewritten as "do not wait"; §9.7 sets NX-007 `DONE`; §9.9 closes B-001; new §10 indexes every decision taken without escalation |
| `BLOCKERS.md` | B-001 moved to **Resolved** — "decided around, not waited on" |
| `TECH_DEBT.md` | TD-007 rewritten: the debt is now the optional reconciliation, not "placeholders pending an import"; the Shoelace half is closed by ADR-024 |
| `docs/FEATURE_MATRIX.md` | NX-007's note points at UI-002 instead of B-001 |
| `docs/plans/M0/tasks/BUILD-006.md` | Each of the three spikes now carries its own decision rule and pre-approved fallback, so a negative result changes the implementation without pausing the milestone |
| `docs/plans/M0/tasks/INFRA-004.md` | Records the standing rule: a blocker owned outside the loop is closed by deciding around it |

No other finding is affected. F-05 and F-06 remain accepted risks with their recorded exit
conditions, both of which fall inside M1 grooming and neither of which requires input from
outside the execution loop to settle.

## Addendum 2 — 2026-09-03, F-01's resolution revised

**F-01 identified a real contradiction; its resolution went the wrong way and has been
reversed.** The finding was correct that `DEVPLAN.md` §5.3 and rule A3 disagreed about whether
`services.gateway.application` may depend on `libs.http` and `libs.contractsCore`. It resolved
the disagreement by tightening the module map to match the rule. The reverse was right: the
rule was wrong for this module.

**The argument.** A3 keeps business rules away from the transport, so that the transport can be
replaced without touching the rules. It presupposes there are business rules to protect — a
`domain` module behind the `application`. `services/gateway` has none and never will, by
ADR-004 §3's explicit decision ("the gateway is application code only … no domain rules"). Its
subject matter *is* the composition of other services' published contracts; `CapabilityState`,
`Section[A]` and `ErrorEnvelope` are its vocabulary, and `ARCHITECTURE.md` §4.5 and §6 already
define them that way. Applying A3 there required an implementation the architecture document
does not describe, and bought nominal isolation — a duplicate type set plus a mapper, where
the two types are identical by construction — at the cost of making `CapabilityFoldSuite`, the
executable specification of KU-001, harder to read.

The generalisation, now recorded in ADR-041 §1a: **a module may depend on the wire when the wire
is its subject matter.** The gateway is the only such module in KUI, and it is one by explicit
decision rather than convenience, so this is a scoped rule and not an exception others can claim.

**What replaced the constraint.** Dropping A3 for the gateway would have left its real
boundaries implied rather than checked, so they are now stated positively: rule **A4**
(the gateway sees a service only through its `contract` module) is unchanged, and new rule
**A8** forbids any Kafka client on the gateway's classpath — ADR-004's other central
constraint, previously enforced only by prose. BUILD-005 additionally gains a negative test per
relaxed rule, so the scoping cannot drift later without a test failing.

| Artifact | Change |
| --- | --- |
| `docs/adr/ADR-041` | Retitled; §1 scoped to domain-owning services; new §1a admits the gateway with the full argument; A3 rescoped; A8 added; the rejected-alternatives section rewritten; Amendment 1 log appended |
| `docs/plans/M0/tasks/BUILD-005.md` | Rule table updated; the "domain-owning" test made mechanical (a `domain` module is declared or it is not); permitted-edge assertions and per-rule negative tests added |
| `docs/plans/M0/DEVPLAN.md` | §5.3 restores the gateway's `application` dependencies and explains why; §10 D6 rewritten as the split rule |
| `docs/plans/M0/tasks/GW-003.md` | The registry uses the contracts-core types directly — no duplicate types, no mapper — with kernel ids in the keys per F-09 |
| `docs/plans/M0/tasks/GW-002.md` | The `ServiceClient` port/implementation split is now recorded as a deliberate choice (two implementations, ADR-005), not a rule consequence |

**Unaffected:** every other finding, and the answer for domain-owning services — SVC-001's
`CapabilityReport` still belongs to `application` and is still mapped in `api`. F-01 remains a
correctly identified blocker; only its direction of fix changed.

