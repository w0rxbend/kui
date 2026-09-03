# KUI status

**Date:** 2026-09-03
**Phase:** G — grooming and analysis (PLAN §39), project-wide pass before M0.
**Repository:** no commits yet; `docs/` and `research/` are untracked working files.

## Grooming progress

| Step | Owner | Artifact | State |
| --- | --- | --- | --- |
| G1 Research | Research agents A–H | `research/**` | **Complete** (agent I blocked, see below) |
| G2 Domain model | Domain Architects | `docs/domain/*.md` | In progress: `docs/domain/kafka-glossary.md` drafted; per-context models not started |
| G3 Architecture | Chief Architect + CTO | `ARCHITECTURE.md`, `docs/adr/` | In progress: no ADR Accepted yet; candidates listed in every research report |
| G4 Roadmap | CEO + Program Lead | `docs/ROADMAP.md`, `docs/FEATURE_MATRIX.md` | **Complete** (this pass) |
| G5 Technical dev plan | Planner + Domain Architects + Principal Scala Engineer | `docs/plans/M0/DEVPLAN.md` + task specs | Not started |
| G6 Gate | CTO + CEO | sign-off in this file | Not started |

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

## Next step

G5: technical dev plan for M0 (`docs/plans/M0/DEVPLAN.md` and one task spec per task), which
requires G3 to produce Accepted ADR-001 … ADR-013, ADR-018, ADR-019, ADR-020 first. Details in
`NEXT.md`.

## Milestone acceptance log

| Milestone | Accepted on | Evidence |
| --- | --- | --- |
| — | — | — |
