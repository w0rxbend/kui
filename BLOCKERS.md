# Blockers

Open items that stop a planned step. Each entry names an owner and the work it blocks; an entry
is removed (moved to "Resolved") only with a note on how it was resolved.

## Open

| ID | Opened | Blocker | Owner | Blocks | Impact | Proposed resolution |
| --- | --- | --- | --- | --- | --- | --- |
| B-001 | 2026-09-03 | Claude Design project "Kafka UI" (`a6db560c-c2e2-41f6-b144-bbe0dd850aa4`) cannot be imported: the `DesignSync` tool requires `/design-login` authorization, which has not been granted in this environment. Research Agent I cannot produce `research/design/{REFERENCE,tokens,components,screens,gap-analysis}.md`. | Repository owner | Kernel design tokens and component styling for M0 (`docs/FEATURE_MATRIX.md` NX-007, KU-002); screen-to-artboard mapping for every microfrontend. | M0 cannot implement `kui-ui-kernel` tokens from the design source of truth (PLAN §21). Without the import, M0 either waits or starts from Kafbat's palette (`frontend/src/theme/theme.ts`) as a placeholder and reconciles later, which risks rework across every kernel primitive. | Repository owner runs `/design-login` and re-triggers Agent I; the design import is a prerequisite of the M0 DEVPLAN. Fallback if not resolved before G5: M0 task `KERNEL-tokens` ships a placeholder token sheet clearly marked `PLACEHOLDER`, and a follow-up task re-derives tokens from the import; the matrix row NX-007 stays `BLOCKED` until then. |

## Resolved

| ID | Resolved | How |
| --- | --- | --- |
| — | — | — |
