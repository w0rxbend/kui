# Blockers

Open items that stop a planned step. Each entry names an owner and the work it blocks; an entry
is removed (moved to "Resolved") only with a note on how it was resolved.

## Open

| ID | Opened | Blocker | Owner | Blocks | Impact | Proposed resolution |
| --- | --- | --- | --- | --- | --- | --- |
| B-002 | 2026-09-03 | **Node.js is not installed on the development machine, so no Scala.js test suite can be executed.** Scala.js tests are run by handing the linked JavaScript to a JavaScript engine; Mill's default engine is `node`, and `./mill libs.kernel.js.test` fails with `org.scalajs.jsenv.ExternalJSRun$FailedToStartException: failed to start command List(node)`. This is purely a missing tool, not a build defect: `libs.kernel.js.compile`, `libs.kernel.js.test.compile` and `libs.kernel.js.test.fastLinkJS` all succeed, so the module cross-compiles and links correctly. The `jsdom` and Playwright test environments (`KuiJsDomTests`, `KuiBrowserTests`) are blocked for the same reason, plus the `jsdom` npm package itself. | Frontend Architect | The `js.test` half of BUILD-003 acceptance; every later frontend task that asserts behaviour rather than only compiling (UI-001 onward); BUILD-004 CI, which must install Node before it can run `__.test`. | JVM tests are unaffected and green. Frontend code can still be written, compiled and linked; it just cannot be test-run locally. | Install Node.js 22 LTS (and `npm install -g jsdom` for the DOM suites) on the development machine and in the CI image. BUILD-004 should install Node as a CI step. No build change is needed — the wiring is already in place and proven up to the point where the engine is invoked. |

## Resolved

| ID | Resolved | How |
| --- | --- | --- |
| B-001 | 2026-09-03 | **Decided around, not waited on.** The Claude Design import is owned outside the execution loop and may never arrive; blocking the frontend lane on it was the larger cost. KUI now owns its design token set, decided in `docs/plans/M0/tasks/UI-002.md` from the competitor evidence already in `research/` (Kafbat's `theme.ts` three-state theming and palette; Kouncil's single palette with no dark mode): ~40 semantic CSS custom properties, no component-scoped tokens, WCAG AA enforced by a test. NX-007 closes on that set. If the design project is ever imported, `docs/plans/M0/tasks/UI-013.md` reconciles values in one file — an optional improvement, never a gate. |
