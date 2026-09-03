# KUI status

**Date:** 2026-09-03
**Phase:** G — grooming and analysis (PLAN §39), project-wide pass before M0.
**Repository:** no commits yet; `docs/` and `research/` are untracked working files.

## Grooming progress

| Step | Owner | Artifact | State |
| --- | --- | --- | --- |
| G1 Research | Research agents A–H | `research/**` | **Complete** (agent I blocked, see below) |
| G2 Domain model | Domain Architects | `docs/domain/*.md` | In progress: `docs/domain/kafka-glossary.md` drafted; per-context models not started |
| G3 Architecture | Chief Architect + CTO | `ARCHITECTURE.md`, `docs/adr/` | **Complete**: ADR-001 … ADR-041 Accepted and indexed in `DECISIONS.md` |
| G4 Roadmap | CEO + Program Lead | `docs/ROADMAP.md`, `docs/FEATURE_MATRIX.md` | **Complete** (this pass) |
| G5 Technical dev plan | Planner + Domain Architects + Principal Scala Engineer | `docs/plans/M0/DEVPLAN.md` + 57 task specs | **Complete** for M0 |
| G6 Gate | CTO + CEO | `docs/plans/M0/GATE-REVIEW.md`, sign-off in this file | **Complete** for M0: APPROVED WITH CONDITIONS |

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
- §16.6: "except through the gateway's contracts" is ambiguous. `ARCHITECTURE.md` §5 reads it
  as "through the gateway-*visible* contracts" and permits direct service→service calls on
  `/internal/v1` (every Kafka-facing service → cluster-service; metrics → topic and consumer
  snapshots). PLAN §3's own wording is satisfied either way, but §16.6 should say plainly which
  it means. Raised at the G6 gate (finding F-06); must be settled before the first M1 task that
  makes such a call, and recorded in ADR-004.
- §18: `domain` is listed as depending on "nothing but Scala stdlib and cats-core", which would
  forbid `libs/kernel` — the shared kernel that `ARCHITECTURE.md` §3, `docs/domain/context-map.md`
  and every M0 task spec give it. §18 should read "Scala stdlib, cats-core and `kui-kernel`
  (which is itself pure, and depends only on cats-core and Iron)".

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

## Next step

Implementation of M0, starting at BUILD-001. `docs/plans/M0/DEVPLAN.md` §6.2 is the order;
BUILD-006, CFG-001 and KERN-006 are worth pulling forward because they answer the open
questions that could invalidate later work.

## Milestone acceptance log

| Milestone | Accepted on | Evidence |
| --- | --- | --- |
| — | — | — |
