# Blockers

Open items that stop a planned step. Each entry names an owner and the work it blocks; an entry
is removed (moved to "Resolved") only with a note on how it was resolved.

## Open

| ID | Opened | Blocker | Owner | Blocks | Impact | Proposed resolution |
| --- | --- | --- | --- | --- | --- | --- |
| B-003 | 2026-09-03 | **A Scala.js test module and any other test module cannot run in the same Mill invocation.** `./mill libs.kernel.js.test build-tests.test` fails with `org.scalajs.testing.common.RPCCore$RPCException: java.lang.UnsupportedOperationException: build-tests.test` — the Scala.js test process is handed a test-module id that is not its own. Each module passes on its own (`./mill libs.kernel.js.test` and `./mill libs.kernel.jvm.test build-tests.test` are both green), and the failure reproduces with `-j 1`, so it is not a parallelism race in KUI's code. It looks like a Mill 1.1.8 / Scala.js test-runner interaction, not a defect in anything this repository owns. | Infrastructure Lead | The single `./mill __.test` command PLAN §49 specifies for the CI test stage. | None on coverage: every suite still runs. BUILD-004's CI test job runs the JVM suites in one invocation and each Scala.js suite in its own, so a task that adds a Scala.js test module has to add a line to `.github/workflows/ci.yml`. | Reproduce against a newer Mill and report upstream if it persists; re-check when the Mill 1.2.0 upgrade happens (already an open row in `DEPENDENCY_MATRIX.md`). Collapsing the three commands back into `./mill __.test` is a one-line change once it is fixed. |

## Resolved

| ID | Resolved | How |
| --- | --- | --- |
| B-002 | 2026-09-03 | **Node was installed and the wiring proved out.** Node 24.18.0 is on the development machine (under a version manager, so it needs the `PATH` line `docs/development/toolchain.md` documents), and `jsdom` is installed into a `node_modules` directory at the repository root. With those in place `./mill libs.kernel.js.test` and `./mill frontend.uiKernel.test` both pass. BUILD-004's CI workflow installs Node with `actions/setup-node` in every job and `jsdom` in the test job, so the runner has the same setup. One correction the blocker did not anticipate: a **global** `npm install -g jsdom` does not work even with `NODE_PATH` set — the generated test script `require`s jsdom and Node resolves that by walking up from the script's own directory, so the package has to be in a `node_modules` at the repository root. |
| B-001 | 2026-09-03 | **Decided around, not waited on.** The Claude Design import is owned outside the execution loop and may never arrive; blocking the frontend lane on it was the larger cost. KUI now owns its design token set, decided in `docs/plans/M0/tasks/UI-002.md` from the competitor evidence already in `research/` (Kafbat's `theme.ts` three-state theming and palette; Kouncil's single palette with no dark mode): ~40 semantic CSS custom properties, no component-scoped tokens, WCAG AA enforced by a test. NX-007 closes on that set. If the design project is ever imported, `docs/plans/M0/tasks/UI-013.md` reconciles values in one file — an optional improvement, never a gate. |
