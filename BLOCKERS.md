# Blockers

Open items that stop a planned step. Each entry names an owner and the work it blocks; an entry
is removed (moved to "Resolved") only with a note on how it was resolved.

## Open

| ID | Opened | Blocker | Owner | Blocks | Impact | Proposed resolution |
| --- | --- | --- | --- | --- | --- | --- |

## Resolved

| ID | Resolved | How |
| --- | --- | --- |
| B-002 | 2026-09-03 | **Node.js is installed and the Scala.js suites run.** `node --version` reports v24.18.0 from `~/.nvm/versions/node/v24.18.0/bin`, which is not on a non-interactive `PATH`; export it before invoking a `js` test task. One build change was needed on top of installing Node: test binaries were linked as ES modules, and Node's ES-module loader cannot give the Scala.js test adapter's bootstrap script the `require` function it uses, so every run died with `ReferenceError: require is not defined` before the first test. `KuiJsTests` now overrides `moduleKind` to `CommonJSModule`, which affects test binaries only — the shipped frontend still links as ES modules for ADR-012's lazy loading. `./mill libs.kernel.js.test` is green. The `jsdom` and Playwright environments are still unproven: they need the `jsdom` npm package and a Playwright browser download, which the first task that uses them must install. |
| B-001 | 2026-09-03 | **Decided around, not waited on.** The Claude Design import is owned outside the execution loop and may never arrive; blocking the frontend lane on it was the larger cost. KUI now owns its design token set, decided in `docs/plans/M0/tasks/UI-002.md` from the competitor evidence already in `research/` (Kafbat's `theme.ts` three-state theming and palette; Kouncil's single palette with no dark mode): ~40 semantic CSS custom properties, no component-scoped tokens, WCAG AA enforced by a test. NX-007 closes on that set. If the design project is ever imported, `docs/plans/M0/tasks/UI-013.md` reconciles values in one file — an optional improvement, never a gate. |
