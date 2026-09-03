# Next

Continuation file per PLAN §52. Updated 2026-09-03.

## Current milestone

Pre-M0: grooming phase G (project-wide). Execution has not started; there is no build file and
no source code. The repository has no commits.

## Current feature

None in execution. Current grooming focus: architecture (G3) and the M0 technical dev plan
(G5).

## Completed tasks

- G1 research: reports A–H complete (list in `STATUS.md`).
- G2 partial: `docs/domain/kafka-glossary.md`.
- G4 roadmap: `docs/ROADMAP.md` (M0..M9, exit criteria, parity checkpoint).
- G4 matrix: `docs/FEATURE_MATRIX.md` (183 rows, 21 CEO decisions DR-1 … DR-21).
- Continuation files: `STATUS.md`, `BLOCKERS.md`, this file.

## Incomplete tasks

- G1 Agent I (visual design import): blocked, `BLOCKERS.md` B-001.
- G2 domain models per bounded context (`docs/domain/<context>.md`, context map): not started.
- G3 `ARCHITECTURE.md` and ADRs: none written. Required before G5 for M0:
  ADR-001 … ADR-013, ADR-018 (PLAN §43) plus ADR-019 (CSS strategy) and ADR-020 (editor,
  JSON viewer, chart facades) from `research/scala/frontend-research.md`. Candidate texts exist
  in every research report's "Decision candidates" section.
- G3 merge decisions DR-20 (security service into cluster service?) and DR-21 (config service
  into gateway?) need an ADR each before M1 / M8 respectively.
- G3 follow-up done out of order, 2026-09-03: **ADR-042** (KUI metadata lives in Kafka, in
  internal compacted `__kui_*` topics) is Accepted. It amends ADR-036 and ADR-023, closes
  TD-014, rewrites `docs/FEATURE_MATRIX.md` OT-004 and moves it from M6 to M1, and adds
  `docs/operations/metadata-store.md`. M0 is unchanged and ships the static configuration
  only; the `ConfigStore[F]` port and both its adapters are M1 work.
- G5 `docs/plans/M0/DEVPLAN.md` and `docs/plans/M0/tasks/<ID>.md`: not started.
- G6 gate sign-off in `STATUS.md`: not started.
- PLAN.md amendments listed in `STATUS.md` (§16.5 wording, §45 M3/M5 notes, §9A corrections):
  not applied. PLAN.md is untracked by design; edit in place.
- Initial commit: `docs/` and `research/` are untracked. Commit them as the first commits once
  the owner confirms the research reports may be public (they cite reference-project file
  paths and line numbers but contain no copied source).

## Known failures

None (no code exists). Environment fact: `/design-login` authorization is absent (B-001).

## Exact next action

1. Chief Architect + CTO (G3): write `ARCHITECTURE.md` and the ADRs listed above, starting
   from the decision candidates in `research/scala/frontend-research.md`,
   `research/scala/security-research.md`, `research/kafbat/api-analysis.md` and
   `research/kafbat/feature-matrix.md` (D-1 … D-10). Mark each `Accepted`. Then flip the matrix
   rows whose ADRs are Accepted from `RESEARCHING` to `DESIGNED`.
2. Planner + Domain Architects + Principal Scala Engineer (G5): produce
   `docs/plans/M0/DEVPLAN.md` in the PLAN §41 format for the M0 scope in `docs/ROADMAP.md`
   (rows KU-001 … KU-009, MT-007, CW-001, NX-005, NX-006, NX-007, OT-005), one task spec per
   task (Appendix C), dependency graph without cycles, time-boxed spikes for: Laminar 17 vs 18
   pin, `mill-scalablytyped` on Mill 1.x, native `EventSource` with cookie auth.
3. Repository owner: resolve B-001 (`/design-login`), then re-run Research Agent I so the
   M0 kernel token task has its input.

## Recommended next agent role

Chief Architect (G3). Do not start G5 before the M0 ADRs are Accepted; a DEVPLAN written
against undecided architecture is rework.
