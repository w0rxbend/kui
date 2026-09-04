# Blockers

Open items that stop a planned step. Each entry names an owner and the work it blocks; an entry
is removed (moved to "Resolved") only with a note on how it was resolved.

## Open

| ID | Opened | Blocker | Owner | Blocks | Impact | Proposed resolution |
| --- | --- | --- | --- | --- | --- | --- |

## Resolved

| ID | Resolved | How |
| --- | --- | --- |
| B-003 | 2026-09-04 | **Not a Mill defect: a command-line misreading, in both directions.** Mill's `.test` is a *command* that takes arguments, so `./mill libs.kernel.js.test build-tests.test` never asked for two modules — it asked for the Scala.js one and handed it `build-tests.test` as a test-name argument, which the Scala.js test runner rejects with `UnsupportedOperationException: build-tests.test`. The same mistake in the other order is silent and worse: `./mill libs.kernel.jvm.test build-tests.test` was reported green while MUnit matched no test by that name and ignored every suite, which is what the CI test job had been doing. Naming several modules is Mill's selector syntax — `./mill '{a.test,b.test}'` or `./mill __.test` — and with it every test module in the repository, JVM and Scala.js together, runs in one invocation and passes. `./scripts/run-tests.sh` does exactly that; the three-command workaround and the rule about adding a line to `ci.yml` are both gone. |
| B-002 | 2026-09-03 | **Node was installed and the wiring proved out.** Node 24.18.0 is on the development machine (under a version manager, so it needs the `PATH` line `docs/development/toolchain.md` documents), and `jsdom` is installed into a `node_modules` directory at the repository root. With those in place `./mill libs.kernel.js.test` and `./mill frontend.uiKernel.test` both pass. BUILD-004's CI workflow installs Node with `actions/setup-node` in every job and `jsdom` in the test job, so the runner has the same setup. One correction the blocker did not anticipate: a **global** `npm install -g jsdom` does not work even with `NODE_PATH` set — the generated test script `require`s jsdom and Node resolves that by walking up from the script's own directory, so the package has to be in a `node_modules` at the repository root. |
| B-001 | 2026-09-03 | **Decided around, not waited on.** The Claude Design import is owned outside the execution loop and may never arrive; blocking the frontend lane on it was the larger cost. KUI now owns its design token set, decided in `docs/plans/M0/tasks/UI-002.md` from the competitor evidence already in `research/` (Kafbat's `theme.ts` three-state theming and palette; Kouncil's single palette with no dark mode): ~40 semantic CSS custom properties, no component-scoped tokens, WCAG AA enforced by a test. NX-007 closes on that set. If the design project is ever imported, `docs/plans/M0/tasks/UI-013.md` reconciles values in one file — an optional improvement, never a gate. |
