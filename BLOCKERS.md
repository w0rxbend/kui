# Blockers

Open items that stop a planned step. Each entry names an owner and the work it blocks; an entry
is removed (moved to "Resolved") only with a note on how it was resolved.

## Open

| ID | Opened | Blocker | Owner | Blocks | Impact | Proposed resolution |
| --- | --- | --- | --- | --- | --- | --- |
| — | — | — | — | — | — | — |

## Resolved

| ID | Resolved | How |
| --- | --- | --- |
| B-001 | 2026-09-03 | **Decided around, not waited on.** The Claude Design import is owned outside the execution loop and may never arrive; blocking the frontend lane on it was the larger cost. KUI now owns its design token set, decided in `docs/plans/M0/tasks/UI-002.md` from the competitor evidence already in `research/` (Kafbat's `theme.ts` three-state theming and palette; Kouncil's single palette with no dark mode): ~40 semantic CSS custom properties, no component-scoped tokens, WCAG AA enforced by a test. NX-007 closes on that set. If the design project is ever imported, `docs/plans/M0/tasks/UI-013.md` reconciles values in one file — an optional improvement, never a gate. |
