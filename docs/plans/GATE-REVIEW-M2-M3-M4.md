# Gate review — M2 (topics), M3 (messages), M4 (consumer groups)

**Reviewer:** CTO. **Date:** 2026-09-04. **Inputs:** `docs/plans/M2/DEVPLAN.md` + 38 specs
(`890a504`), `docs/plans/M3/DEVPLAN.md` + 48 specs (`0ad91e8`), `docs/plans/M4/DEVPLAN.md` + 40
specs (`9f8973c`), against `docs/ROADMAP.md`, `docs/FEATURE_MATRIX.md`, `ARCHITECTURE.md`,
`DECISIONS.md`, `DEPENDENCY_MATRIX.md`, `libs/kafka/PORT-INVARIANTS.md`, the M0 and M1 gate
reviews and the shipped code.

**Verdict: APPROVED WITH CONDITIONS.** 4 blockers, 9 majors, 7 minors. All 4 blockers and all 9
majors are fixed in this review. Three ADRs written, one amended.

This is the first gate at which three plans were written in parallel by agents who could not see
each other, and the findings are exactly the shape that predicts: not one plan being wrong, but
three plans each being locally right about a thing they share. Every blocker is a shared edge.

---

## 1. Findings

| # | Severity | Area | Finding | Evidence | Resolution |
| --- | --- | --- | --- | --- | --- |
| F-01 | **blocker** | build rules | **Three different rules are called `A11`.** M2 §5.3 defines A11 as "a service may see another service's `contract` and `client` and nothing else". M3 §5.3 defines A11 as the Confluent/Jackson/Guava classpath confinement and A12 as the CEL confinement. M4 §6.3 defines A11 as "a wire vocabulary may be declared only in `libs/kernel` or `libs/contracts-core`". `checkArchitecture` names the rule in its failure message and the developer reads the reason attached to that number; whichever milestone landed second would have silently redefined the other's rule. | M2 §5.3; M3 §5.3, §6.7; M4 §6.3, §2.8 | **Fixed.** Rule numbers are now allocated centrally in **ADR-041 Amendment 4** and nowhere else: A11 = M2's service-to-service rule, A12 = Confluent confinement, A13 = CEL confinement, A14 = wire vocabulary. All three DEVPLANs, MSG-047, GRP-040 and TOP-010 renumbered; each plan now states that the amendment is the only allocator. |
| F-02 | **blocker** | shared module | **The cluster-profile client is designed three times.** M2 TOP-009 builds `services/cluster/client` as *the* shared consumer, and its own §5.1 says so in as many words ("four services … a protocol implemented four times is a protocol implemented four different ways"). M3 MSG-026 then specifies `HttpClusterProfileSource.scala` inside `services/message/infrastructure`, with its own ETag handling, SSE subscription and last-known cache. M4 GRP-022 specifies a third, inside `services/consumer/infrastructure`. This is the M0 review's second process finding with a distributed-systems failure mode attached, and it is the single most expensive defect in the three plans. | M2 §5.1 and D1; M3 §5.1, `tasks/MSG-026.md:19,47`; M4 §3 P1, `tasks/GRP-022.md` | **Fixed.** **ADR-046** written: credentials travel on `/internal/v1`, and exactly one module — `services/cluster/client` — consumes them. M3's §5.1 dependency row and MSG-026 rewritten to adapt that module rather than reimplement it; if it is absent, both M3 and M4 build it *in that module* and raise their own task size. M4's P1 rewritten the same way. GRP-022 no longer proposes its own ADR-046 for the same decision. |
| F-03 | **blocker** | safety net | **M3 ships three mutations with no safety net, and its own plan contradicts itself about it.** Produce, resend and purge change a cluster; purge is irreversible. M3 §2 criterion 14 requires all three refused with `KUI-READ-ONLY`, while M3 §3 says "no read-only mode" and "no audit records" until M5. Nothing in M3 defines the mechanism the criterion names. Meanwhile M4 §10 D2 independently invents exactly that mechanism (a `Mutation` marker, a per-cluster `readOnly` refusal, an `AuditSink[F]`), calls the roadmap's ordering a contradiction, and describes itself as "the milestone where KUI first changes something" — which is false, because M3 lands first. M5's exit criterion is an *enumeration* over `Mutation` markers; three unmarked endpoints would make its first run a retrofit across a shipped service. | M3 §2.14 vs §3; `tasks/MSG-022.md:27,105`, `tasks/MSG-024.md:18,59`; M4 §10 D2, §1 | **Fixed.** **ADR-047** written and made an inherited rule rather than an M4 invention: any milestone shipping a mutation ships the marker, the per-cluster refusal and the audit record with it. M3's non-goals rewritten to carry the three parts (declared once in `libs/security-core`, ADR-023's home) and criterion 14 made executable. M4's framing corrected. |
| F-04 | **blocker** | port invariant | **Both milestones claim `PORT-INVARIANTS.md` §1 and would implement the leaderless filter twice.** M2 TOP-002/TOP-005 move §1 into `TopicAdmin.listOffsets`' scaladoc and **delete** the section in the same commit. M4 §5 says "both invariants land here" and GRP-006 builds a separate `OffsetLookup`, **annotating** §1 rather than deleting it, while M4's own lane-A boundary forbids touching any file M2 owns. Run in parallel, the sixty-second-timeout filter exists in two places, the file is both deleted and annotated, and the rule the file exists to preserve is stated twice — the exact condition the file warns about in its closing paragraph. | M2 §4, §6.5 "boundary exception", `tasks/TOP-002.md`; M4 §5 references table, `tasks/GRP-006.md:23,33,65,96` | **Fixed.** Ownership split explicitly: **§1 is M2's, §2 is M4's.** M4's reference table and DoD item 9 rewritten — GRP-006 reuses M2's helper; if M2 has not landed, GRP-006 creates it as the shared `OffsetLookup` that `TopicAdmin.listOffsets` then calls. One implementation either way, and a `grep` for the rule must find it in exactly one place. |
| F-05 | major | frontend seam | **The topic page's guest-tab host is claimed by nobody.** M3's Messages tab and M4's Consumers tab are both "a registration against a kernel `FeaturePanel` slot", and both plans forbid themselves from editing `frontend/ui-topics`. M2 owns `ui-topics` and its D13 lists only the four *overview-aggregation* slots — no generic host, and no `messages` slot. M3 MSG-034 would declare `FeatureSlots.scala` in `ui-kernel` itself, which M2's TOP-027 would then declare a second time. Result: the Messages tab has no host and never renders. | M2 D13, `tasks/TOP-024.md:20`; `tasks/MSG-034.md:26,34,62`; M4 D13, §3 P3 | **Fixed.** M2 D13 now requires `TopicDetail` to render a **generic `topic.tabs` guest host** for every registered guest, and `FeatureSlots` — the one declaration of every cross-feature slot id — is added to `ui-kernel` by TOP-027. M3 §3a P2 and M4 §3 P3 record it as a consumed precondition with a fallback. `FeaturePanel` itself already exists from M0 (`FeaturePanelSuite` already uses the id `topic.tabs`). |
| F-06 | major | dependency order | **M3 records no dependency on M2 at all**, though it takes four things from it: `services/cluster/client`, the `topic.tabs` host, `PageDto`, and rule A11. M4 records three (P1–P3) and misses two more: `libs/contracts-core`'s `PageDto` (its group list pages per ADR-026 and nothing says where the wire shape comes from) and `libs/kernel`'s `NameIndex` (its §10 D14 search is stated in terms of it). A milestone that does not know what it consumes cannot be scheduled against another. | M3 (absent); M4 §3; M4 §10 D14; `tasks/GRP-024.md` | **Fixed.** M3 gains a new **§3a Entry preconditions** with four rows, each a one-command check and a named fallback, in M4's format. M4 gains **P4**. M2 gains a matching statement of the five deliverables consumed by the other two, so the list exists on both sides. |
| F-07 | major | shared library | **M2 assigns a decision to M4 that M4 does not take.** M2 D12 defers unifying `SnapshotRegistry` and `services/cluster`'s `ClusterSnapshots` "with M4's consumer service — the third caller — as the milestone that decides whether one abstraction fits three". M4 §4 says "no caching primitive of its own … `SnapshotCell` holds the group snapshot, exactly as `ClusterSnapshots` does" and never mentions `SnapshotRegistry`. The debt's exit condition names a milestone that has not agreed to it. | M2 §10 D12; M4 §4, §6.1 | **Fixed as a scope statement, not a code change.** The decision is genuinely M4's and M4's answer is now on record: the consumer service holds **one cell per cluster** and is a `SnapshotRegistry` caller like the topic service, or it states in GRP-013's Implementation Report why one cell per key does not fit and the debt rolls to M5. Recorded in this review's conditions rather than as a plan edit, because either answer is defensible and neither blocks a task. |
| F-08 | major | ADR ownership | **One decision, two ADR homes.** M2 D1 records the credential-bearing profile channel as an **ADR-036 amendment** written by TOP-038; M4 GRP-022 records the same decision as a **new ADR-046** written by GRP-040. Whichever ran second would find the decision already recorded somewhere else, or record it twice. | M2 §10 D1; `tasks/GRP-022.md:67` | **Fixed.** Written once here as **ADR-046**, Accepted, indexed. M2 D1 and GRP-022 both now cite it; TOP-038 cross-references it from ADR-036 and closes `ARCHITECTURE.md` §14's open question. |
| F-09 | major | rule citation | **M3 justifies a build rule with a rule that does not say what it claims.** M3 §6.7 asserts "rule A4 already forbids a service seeing another service's non-`contract` module". A4 is scoped to `services.gateway.*` — M2's own §5.3 says so explicitly ("A4 says this for the gateway. Nothing said it for service-to-service calls, because until M2 there were none"). Without M2's A11, M3's stated enforcement of its central non-goal (no message → topic call) does not exist. | M3 §6.7; ADR-041 §2 rule table; M2 §5.3 | **Fixed.** M3 §6.7 now cites A11 (M2, ADR-041 Amendment 4) and keeps MSG-047's additional assertion that `services.message.*` has no `services.topic.contract` edge either. |
| F-10 | major | cross-feature mechanism | **Two different mechanisms for "a tab on the topic page", neither aware of the other.** M4 D13 makes the Consumers tab a **section of the gateway's topic-overview aggregation** rendered in a `FeaturePanel`. M3 MSG-034 makes the Messages tab a **pure client-side registration** with no gateway section and no aggregation entry. Both are correct for their own data and the difference is invisible in either plan. | M4 §10 D13, `tasks/GRP-029.md`; `tasks/MSG-034.md:23-27` | **Fixed by making it explicit rather than by unifying it.** M2 D13 now states the shared rule: **rendering** is always the `topic.tabs` guest host; **data** comes from wherever the guest's own feature gets it — the gateway aggregation when the host page needs a summary before the guest loads (consumers), the guest's own route when it does not (messages). Both remain registrations; neither imports `ui-topics`. |
| F-11 | major | shared file | **`frontend/ui-shell`'s route table is edited by all three milestones**, and only M3 noticed — its §6.5 names M2 as "the single file M2 and M3 share". M4 GRP-030 edits it too. Same for `build.mill`'s module list (all three), `docs/api/openapi.json` (all three) and `docs/FEATURE_MATRIX.md` (all three, each at the end). | M3 §6.5; M2 §6.5; M4 §6.4 | **Fixed by rule, stated in this review's §4.** Each milestone adds only its own entries, in one commit, and rebases rather than reformats; the OpenAPI snapshot is regenerated, never hand-merged; the feature matrix is edited only by each milestone's final task, and each milestone's rows are disjoint (verified — see F-13). No plan text needed changing beyond M3's existing rule, which now covers M4 by generalisation. |
| F-12 | major | mutations before M4 | **M3's mutations have no `MutationKind` home and M4 would declare one.** M4 D2 places the `Mutation` marker and `AuditSink[F]` in the consumer service's application layer with no statement of where the types live; three services will write through them. | M4 §10 D2, `tasks/GRP-018.md`; M3 `tasks/MSG-022.md` | **Fixed.** ADR-047 §3 places `MutationKind`, `MutationRecord` and `AuditSink[F]` in **`libs/security-core`** once — ADR-023's declared home for the audit model — declared by whichever milestone ships the first mutation (M3, lane C, beside the masking engine). M4 consumes them. |
| F-13 | major | coverage | Roadmap exit criteria and feature-matrix rows checked one by one. **No gaps.** All four M2 criteria, all seven M3 criteria and all four M4 criteria have at least one owning task, and every row each milestone claims is assigned to that milestone in `docs/FEATURE_MATRIX.md`. Three corrections of the roadmap by the plans (M2 D10 placeholder semantics, M2 D7's 10 000-vs-500, M4 D11's missing CG-003/CG-005 criteria) are each argued from the matrix or an ADR and are accepted. KU-013 is claimed `PARTIAL` by M2 and `DONE` by M4, which is correct and is what the matrix's own note describes. | `docs/ROADMAP.md`; `docs/FEATURE_MATRIX.md` | **No action.** Recorded because a clean result here is load-bearing. |
| F-14 | minor | citations | ~40 cited ADRs, files, research sections and version pins spot-checked. All resolve: `dev.cel:cel:0.14.0`, Confluent `8.3.1` and Caffeine `3.2.4` match `DEPENDENCY_MATRIX.md` exactly; `docs/spikes/M0-netty-sse.md`, `TECH_DEBT.md`, `DECISIONS.md`, `docs/api/error-codes.md` and `libs/kernel`'s `Page.of` all exist as cited. Two forward references are correct as written: `docs/benchmarks/` does not exist yet and is created by M2 TOP-037. | — | **No action.** |
| F-15 | minor | citations | **`DEPENDENCY_MATRIX.md` puts `tapir-apispec-docs_3` and `jsonschema-circe_3` on `services/message/infrastructure`.** M3 is right that they belong on `libs/serde-confluent`, where the schema types live and where rule A12 keeps them. | `DEPENDENCY_MATRIX.md:49,56` | **Accepted as M3's fix.** MSG-013 corrects the two rows, as its plan already says. Left to the task because the module does not exist yet. |
| F-16 | minor | internal consistency | Two cross-references in M2 point at the wrong decision: §2 criterion 2 cites "§10 D8" for the 10 000-vs-500 argument, which is D7; §9 item 10 attributes "no mutation endpoints" to D7, which is §3's non-goal. | M2 §2, §9.10 | **Fixed.** Both corrected. |
| F-17 | minor | ADR numbering | M4 proposes ADR-045 (plan token, in §11) and ADR-046 (profile seam, in GRP-022) with no allocation authority; nothing stops M5's grooming from taking the same numbers. | M4 §11; `tasks/GRP-022.md:67` | **Fixed.** ADR-045, ADR-046 and ADR-047 written and indexed in `DECISIONS.md` at this gate; the next free number is 048. |
| F-18 | minor | test infrastructure | Three milestones each seed a Kafka Testcontainers topology in `libs/testkit` (TOP-007, MSG-042, GRP-036). M1's F-11 was this exact finding. These are **additive fixtures on one existing `KafkaFixture`**, not three container definitions, so the finding does not repeat — but no plan says so. | M2 §5.2; M3 §5.2; M4 §6.2 | **Condition, not a fix.** Each fixture task must extend M1's `KafkaFixture`/`KafkaTopology` and must not declare a container of its own. Recorded in §4. |
| F-19 | minor | duplication debt | The `libs/kafka`-versus-domain type duplication M1 accepted (`ClusterFeature`, `BatchResult`, `SkipReason`) becomes ten pairs across these three milestones. M2 keeps the duplication and states the mitigation (a named conversions object, a compiler-checked exhaustive match, a `PortContractSuite` holding fake and live adapter to one contract). M3 and M4 do the same without saying so. | M2's grooming report; M1 gate review §4 | **Accepted, unchanged.** A1 and A5 leave no alternative that does not break the layering. M3 and M4 inherit M2's mitigation; `TECH_DEBT.md` carries one row, not three. |
| F-20 | minor | benchmark policy | M2 R-6 gates its 16 ms figure on regression rather than an absolute, records a machine fingerprint, and keeps the 10 000-topic case nightly. M3 MSG-045 keeps benchmarks out of the CI gate entirely. The two policies are compatible but are stated independently. | M2 §8 R-6; M3 §10, MSG-045 | **No action.** Both are right and the difference is honest: M2 has one measured criterion the roadmap names, M3 has four shapes the roadmap gave no thresholds for. |

**Counts: 4 blockers, 9 majors, 7 minors. All blockers and all majors resolved in this review.**

---

## 2. Decisions the grooming agents took that no ADR covered

Forty-one decisions across the three plans (M2 D1–D13, M3 D1–D14, M4 D1–D14), judged one by one:
promote to an ADR, fold into an existing one, or leave as a task detail.

| Decision | Judgement |
| --- | --- |
| M2 D1 / M3 D8 / M4 P1 — the profile channel carries credentials; one shared consumer module | **Promoted — new ADR-046.** It changes what travels on a channel, creates a new *class* of module (`client`), and three milestones depend on it. It cannot live in a DEVPLAN table that only one of them reads. |
| M4 D2 — the `Mutation` marker, the per-cluster `readOnly` refusal, the `AuditSink[F]` | **Promoted — new ADR-047**, and widened from M4 to a standing rule, because M3 ships the first mutation. It resolves a contradiction in the roadmap's own ordering rationale; that is never a task-level detail. |
| M4 D3 / D4 — two-phase, server-computed confirmation with an HMAC'd plan token | **Promoted — new ADR-045.** M4's own plan already asked for this ("a decision of that reach does not belong only in a DEVPLAN table"). M5's five other mutations reuse it. |
| M2 §5.3 A11, M3 §5.3 A12/A13, M4 §6.3 A14 | **Folded — ADR-041 Amendment 4**, which is that ADR's whole subject, and which now also holds the number allocation that F-01 showed nothing was holding. |
| M3 D1 — the browse vocabulary lives in `libs/kernel`; M4 D1 — the group vocabulary likewise | **Folded** into ADR-041 Amendment 4 via rule A14, which makes the pattern a rule instead of a repeated decision. This is M1's D1 for the third time; the third repetition is when it stops being a decision. |
| M2 D2 — the filter → sort → page order, enforced by a differential property test | **Left task-level.** `libs/kernel`'s `Page.of` doc comment already carries the reasoning; TOP-014/TOP-036 make it enforced. Nothing outside M2 can observe the ordering except through the total, which is tested. |
| M2 D3 — "internal topic" is the union of Kafka's flag and the configured prefix | **Left task-level.** Correctly decided, and `__kui_config` is the case that proves it. |
| M2 D6 / M4 D6 / M4 D8 — an undefined count or lag is `None`, never a partial sum or a zero | **Left task-level**, but noted as the strongest shared instinct in the three plans: a wrong number is worse than no number, because only one of the two starts an investigation. |
| M2 D7 — the 10 000-row benchmark drives the kernel component, not the product API | **Left task-level** and right. Raising ADR-026's page cap to match the roadmap's benchmark number would have been an outage, which `PageSize`'s own doc comment says. |
| M2 D9 — favourites never reach the server | **Left task-level.** The matrix row already says `localStorage`, and the argument (two tabs disagreeing about page 3) is decisive. |
| M2 D10 — an absent service's section is `NotConfigured` and hidden, not `Unavailable` | **Left task-level**, and it corrects the roadmap. ADR-032 already draws the line; four permanent red panels would train operators to ignore the colour that matters. |
| M2 D12 — `SnapshotRegistry` built, `ClusterSnapshots` not refactored onto it | **Left task-level, with F-07's condition attached.** |
| M3 D3 — detail expands in place, drawers for composition | **Left task-level.** Split by role, and `research/design/REFERENCE.md` is explicit. |
| M3 D5 — M3 does not create `GroupAdmin`; the invariant's owner is M4 | **Left task-level and now enacted here** as part of F-04's ownership split. |
| M3 D7 / D9 / D10 / D12 / D13 / D14 — budget numbers, the SD-001 set, `seekTo[]`, mandatory `match.source`, a terminal `error` that keeps its rows, `BoundedCache` | **Left task-level.** Each is sourced from measured reference behaviour or from an existing ADR's own consequence; none changes a boundary. D13 gets a consequence note on ADR-035 at M3's close, as M3 already plans. |
| M4 D5 — existence by listing; `GroupNotFound` and `GroupNotEmpty` | **Folded** into ADR-034's error table by GRP-040, as M4 already plans. The split (fabricate for reads, refuse for writes) is the invariant file's own reading. |
| M4 D9 / D12 / D14 — server-issued lag token, pace defined, search over group ids | **Left task-level.** D9 in particular is a straightforward correction of a reference-product defect. |
| M4 D11 — CG-003 and CG-005 ship in M4 with their own criteria | **Left task-level.** The matrix is the scheduling authority and it already assigns them. |
| M4 D13 / M3 MSG-034 — the cross-feature tab mechanism | **Left task-level, after F-10 and F-05 made the shared half explicit.** |

---

## 3. The four M0 process problems, checked against all three plans

| M0 finding | M2 | M3 | M4 |
| --- | --- | --- | --- |
| **Nothing tested the seams** | **Answered.** Four tasks exist for seams alone (TOP-034…037); every cross-process document has a recorded golden file both sides decode in one suite. | **Answered, best of the three.** §6.6 enumerates five seams and names the task that tests each; one committed byte fixture per SSE event type, read by a JVM suite and a Scala.js suite — the artifact M1's defect 2 lacked. | **Answered.** GRP-037 is three seam suites on three boundaries, all on recorded documents, plus a config-to-composition field sweep that names M1's defect 1 as its target. |
| **A string typed twice in two files** | **Repeated and fixed here.** F-02 (the profile client, three times), F-04 (the leaderless filter, twice), F-05 (`FeatureSlots`, twice), F-12 (`MutationKind`, twice). This is the dominant failure mode of parallel grooming and it produced every blocker in §1. The accepted `libs/kafka`-versus-domain duplication (F-19) is a separate, argued case. | | |
| **Documented rules went unenforced** | **Answered.** §9 item 10 is a table of every rule the plan states and its enforcer, and says plainly that "a rule with no enforcer named here is a rule this milestone does not claim". | **Answered.** §6.7 is the same table, with ten rows. | **Answered** through the rule/test pairing in §2 and §9, though it has no single table; F-01's numbering collision was the one place where an enforcement mechanism was itself unowned. |
| **Cancellation systematically unconsidered** | **Answered.** §9 item 9 makes a named cancellation test a condition of done and lists the five paths. | **Answered.** §9 item 10, eight named paths. | **Answered.** §11 item 10, five named paths. |

The second finding is the one that repeated, and it repeated because of *how* these plans were
produced, not because of who wrote them. The lesson is recorded in §4 as a standing rule for the
next parallel grooming.

---

## 4. Fixes applied, and standing conditions

**ADRs written** (indexed in `DECISIONS.md`):

- **ADR-045** — a destructive operation is confirmed against a server-computed plan carried by an
  HMAC'd plan token, not against a form.
- **ADR-046** — the cluster profile seam: credentials travel on `/internal/v1`, and exactly one
  shared module, `services/cluster/client`, consumes them. Closes `ARCHITECTURE.md` §14.
- **ADR-047** — every mutation ships with a `Mutation` marker, a per-cluster read-only refusal and
  an audit record, from the first one. Resolves the roadmap's own ordering contradiction.
- **ADR-041 Amendment 4** — rules A11–A14 allocated centrally; rule numbers are allocated in that
  amendment and nowhere else.

**Plan edits:** M2 §2 (cross-milestone deliverables, D7/D8 citation fixes), §5.2 (`FeatureSlots`),
§5.3 (number authority), §9.10, §10 D1, §10 D13. M3 §2.14, §3 (ADR-047's three parts), new §3a
(entry preconditions), §5.1, §5.3 (A12/A13), §6.2, §6.7, §9.3, §10 D8. M4 §1, §3 (P1, P3, P4), §2.8,
§5 (`PORT-INVARIANTS` ownership), §6.3 (A14), §7.1, §9 R-6, §10 D1/D2/D3, §11.3, §11.9, §11.11.

**Task specs edited:** TOP-010, MSG-026, MSG-047, GRP-022, GRP-040.

**Standing conditions** — none gates the first commit:

1. **F-07.** GRP-013's Implementation Report must state whether the consumer snapshot uses
   `SnapshotRegistry` or one bare `SnapshotCell`, and if the latter, why one cell per key does not
   fit. M2 D12's `TECH_DEBT.md` row closes or rolls to M5 on that answer.
2. **F-18.** TOP-007, MSG-042 and GRP-036 each extend M1's `KafkaFixture`/`KafkaTopology`. No task
   may declare a Testcontainers Kafka container of its own. This is M1's F-11, standing.
3. **Shared files.** `frontend/ui-shell`'s route table, `build.mill`'s module list,
   `docs/api/openapi.json` and `docs/FEATURE_MATRIX.md` are touched by all three milestones. Each
   adds only its own entries, in one commit, and rebases rather than reformats; the OpenAPI snapshot
   is regenerated, never hand-merged.
4. **Standing rule for the next parallel grooming.** Every blocker at this gate was a shared edge
   that each plan solved locally and correctly. Before parallel grooming starts again, the
   orchestrator must fix in advance the things the plans will otherwise each invent: build-rule
   numbers, ADR numbers, the ownership of every file two milestones can reach, and the module that
   owns any protocol more than one milestone consumes.

---

## 5. Parallelism verdict

**They cannot be implemented as three fully parallel milestones. M2 must land a defined subset
first; after that, M3 and M4 are genuinely independent of each other and can run in parallel at
full width.**

### The shared edges

M3 and M4 are, as the architecture predicts, well separated from each other: different services,
different microfrontends, different Kafka APIs (a consumer with manual assignment versus the
`AdminClient`'s group calls), different domains, and no call between them. Checked directly:
`services/message` never touches `services/consumer`, no `libs` module is co-owned in a way that
conflicts (M3 adds `BoundedCache` to `libs/cache`; M4 uses only the existing `SnapshotCell`; each
adds a disjoint package to `libs/kernel`), and their feature-matrix rows are disjoint. **M3 ⟂ M4
holds.**

M2 is not symmetric with them. Five of its deliverables are inputs to the other two:

| M2 deliverable | Task | Consumed by |
| --- | --- | --- |
| `services/cluster/client` — the credential-bearing profile consumer (ADR-046) | TOP-008, TOP-009 | M3 MSG-026, M4 GRP-022 — **on both their critical paths** |
| `checkArchitecture` rule A11 | TOP-010 | M3 (its central non-goal's only enforcement), M4 (the profile edge's legality) |
| `libs/contracts-core`'s `PageDto` | TOP-019 | M4 GRP-024/GRP-025; M3's page endpoint |
| `libs/kernel`'s `NameIndex` | TOP-001 | M4 GRP-014 |
| `ui-kernel`'s `FeatureSlots` + the `topic.tabs` guest host in `ui-topics` | TOP-027, TOP-030 | M3 MSG-034, M4 GRP-035 |

Plus one soft edge: M3's Track page needs a topic multi-select, which is a browser call to M2's
topic list through the gateway — a degraded control, not a compile failure.

Everything else that looks shared is not. Both M3 and M4 extend `libs/kafka`, but in different
packages against a stable M1 `AdminClientPool`. All three touch `services/gateway`, `ui-shell` and
the OpenAPI snapshot, but additively — that is merge coordination (condition 3), not sequencing.

### The verdict

**Land M2's lane B and the four shared primitives first — TOP-001, TOP-008, TOP-009, TOP-010,
TOP-019, TOP-027 and the `topic.tabs` host in TOP-030 — then run M3 and M4 in parallel with the
remainder of M2.**

That is seven tasks, none of which is on M2's own critical path except lane B, and lane B is the
work M2's own §6.4 already says to start on day one because it is the milestone's riskiest unknown.
The sequencing this review asks for is therefore almost free: it is the order M2 was already going
to be worked in.

Two things make the alternative — starting all three at once — unsafe rather than merely untidy:

1. **The profile client is on all three critical paths.** M2's runs `TOP-018 → TOP-022`, M3's
   reaches it at MSG-026 → MSG-030, M4's at `GRP-022 → GRP-028`. Three teams reaching the same
   unbuilt module at their own midpoints is how F-02's three implementations get written despite
   the fix, because at that moment each team has a blocked path and a plausible local answer.
2. **ADR-046 might be wrong.** M2's §6.4 is right that if carrying credentials over `/internal/v1`
   turns out to be unacceptable, the alternative changes the deployment model, the operator
   documentation and four services' wiring. Discovering that with three milestones in flight costs
   three times what discovering it with one costs.

The fallbacks written into M3 §3a and M4 §3 exist for the case where this sequencing is not
honoured — a plan should survive its own schedule slipping. They are not a licence to skip it: every
one of them ends "build it in `services/cluster/client`, and raise this task's size", which is the
same work done under worse conditions.

**Recommended shape.** M2 starts alone. Once lane B is green and `services/cluster/client` has a
passing seam suite (TOP-034's first assertion), M3 and M4 start together and run to completion in
parallel with each other and with the rest of M2. M4's remaining preconditions (P2, P3, P4) come
from M2 tasks that land well inside that window, and each has a fallback if they do not.

---

## 6. Overall verdict

**APPROVED WITH CONDITIONS.** Implementation may start now, with M2's lane B.

The three plans are strong individually. Each is more concrete than M1's was at the same stage;
each answers all four M0 process findings inside its own boundary; each takes its decisions from
the research rather than from opinion, and several correct the roadmap on evidence — M2's
placeholder semantics, M2's separation of the component benchmark from the API's page cap, M3's
reassignment of a port invariant, M4's identification of the roadmap's destructive-ordering
contradiction. That last one is the single best piece of judgement in the three documents, and the
only thing wrong with it is that M4 thought the contradiction was its own to solve.

Every blocker was a shared edge, and no plan could have found any of them alone. That is the cost of
parallel grooming, and it is worth paying once more: the alternative — grooming three milestones
serially — would have cost more calendar time than these four fixes cost.

The conditions are §4's four. None gates the first commit. Re-review is not required; findings that
surface during implementation follow the normal route (PLAN §39): new evidence, a superseding ADR, a
row in `TECH_DEBT.md`.
