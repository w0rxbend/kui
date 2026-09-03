# BUILD-006 — Toolchain spikes: Netty SSE, ScalablyTyped, Playwright pin

- **ID:** BUILD-006
- **Title:** Toolchain spikes: Netty SSE, ScalablyTyped, Playwright pin
- **Milestone / Feature:** M0 / KU-008
- **Owner role:** Principal Scala Engineer (spikes 1 and 2), QA Engineer (spike 3)
- **Context / service:** build root
- **Size:** M (time-boxed: 90 minutes per spike, hard stop)
- **Dependencies / blocked by:** BUILD-003

## Goal (user value)

Three open questions in `DEPENDENCY_MATRIX.md` are marked "due M0". Each one can invalidate a
later task. Answering them now costs four hours; discovering them in E2E costs a week.

Per PLAN §39 "anti-waste rules": spike code is deleted, and the finding is recorded. This
task's deliverable is **findings plus one permanent build task**, not spike code.

## Scope

### Spike 1 — long-lived SSE on `tapir-netty-server-cats` (risk R-2)

Question: does a `serverSentEventsBody` response stay open, flush per event, and honour fs2
backpressure for at least 10 minutes on Netty 4.2, and does client disconnect cancel the fs2
stream?

Method: a throwaway `app` that serves an fs2 stream emitting one event per second for 600
seconds with a 15-second heartbeat; `curl -N` and a browser `EventSource` both attached;
kill the client and assert the server-side fiber is cancelled (observed by a `guarantee`
log line).

Record: latency to first byte, whether events arrive individually or in a buffered burst,
cancellation delay.

**Decision rule, applied by the worker without escalation:**

| Observation | Action |
| --- | --- |
| events flush individually, connection survives 10 min, cancellation within 1 s | keep Netty. No further work |
| events flush but cancellation is slower than 5 s | keep Netty; HTTP-004 adds an explicit idle-timeout guard and the finding is noted in ADR-003's consequences |
| buffering, dropped connections, or no cancellation at all | **switch to http4s-ember immediately**, in this task, by changing `KuiServer` only (ADR-003 pre-approved this and the swap is confined to one file); add `org.http4s::http4s-ember-server` and `tapir-http4s-server` to `DEPENDENCY_MATRIX.md`, and write the superseding note into ADR-003 |

Both branches are already decided; the spike chooses between them, it does not open a
discussion.

### Spike 2 — `mill-scalablytyped` on Mill 1.1.x (risk R-6)

Question: does `lolgab/mill-scalablytyped` load and generate under Mill 1.1.8 and Scala 3.9?
Method: generate a facade for `@codemirror/state` only, in a scratch module, and delete it.
Record: yes/no, the plugin version tried, the error if it fails.

**Decision rule:** a "no" is not a blocker and does not go back to anyone. ADR-025 already
specifies hand-written, vendored facades (~150 lines for CodeMirror, ~60 for uPlot);
ScalablyTyped was only ever a labour-saving generator for the first draft. If it does not run
on Mill 1.1.x, record that the facades will be written by hand in M2 from the upstream
`.d.ts` files, remove the `mill-scalablytyped` row from `DEPENDENCY_MATRIX.md`, and note it in
ADR-025's consequences. The competitors are no help here — Kafbat uses plain JS Ace and
Kouncil loads Monaco as a prebuilt asset, so neither has a typed facade to borrow.

### Spike 3 — Playwright JVM version pin (risk R-10)

Question: which `com.microsoft.playwright:playwright` version is current, and which browser
revision does it download? Method: resolve the artifact, run `playwright install chromium`,
record both versions. Deliverable: `Versions.playwright` and `Versions.playwrightBrowser` set
in `build.mill`, plus the exact CI install command for BUILD-004's e2e job.

**Decision rule:** take the newest stable release that resolves and runs the smoke navigation;
pin it and its browser revision. Do not evaluate alternatives — ADR-018 already rejected
TypeScript Playwright with reasons, and Kafbat's own e2e suite (`e2e-playwright/`) confirms
Playwright is the right tool for this product; only the version number was open.

### Permanent deliverable — bundle-shape check task

`./mill frontend.uiShell.checkBundleShape`: after `fullLinkJS`, assert that

1. a module file matching `kui.ui.clusters*.js` (or the configured output pattern) exists in
   the linker output directory, and
2. `main.js` does not contain the string `kui.ui.clusters.ClustersFeature` (the linked class
   name; assert on the emitted symbol, not on a source string), and
3. `main.js` is under a configurable size budget, initially 1.5 MB uncompressed.

The task is written now and starts asserting for real in UI-012, when the feature module
exists. Until then it skips with a clear "no feature packages configured" message rather than
passing vacuously.

## Non-goals

No production code. No decision to change ADR-003 or ADR-025 inside this task — a spike
produces evidence, a superseding ADR produces a decision (PLAN §43).

## Design references

ADR-003 (Netty fallback), ADR-012 (bundle shape as a CI check), ADR-018 (Playwright),
ADR-025 (ScalablyTyped as a one-off tool), `DEPENDENCY_MATRIX.md` "Open version questions",
PLAN §39 anti-waste rules.

## Files to create or change

```
build.mill                                     (Versions.playwright*, checkBundleShape task)
docs/spikes/M0-netty-sse.md
docs/spikes/M0-scalablytyped.md
docs/spikes/M0-playwright-pin.md
```

Spike scratch modules live in the scratchpad directory and are **not** committed.

## Acceptance criteria

```
$ ./mill frontend.uiShell.checkBundleShape     # skips with a message (no features yet)
$ ./mill show Versions.playwright              # prints the pinned version
$ ls docs/spikes/                              # three findings documents
```

Each findings document follows PLAN Appendix D's short form: question, method, finding,
**decision taken** (per the rules above), consequence, confidence. A findings document that
ends in a question rather than a decision has not completed this task.

## Tests required

- Unit test for the bundle-shape assertion logic itself (given a fake linker output directory
  containing / not containing the expected files, the task passes / fails with the right
  message).

## Observability / Degraded behavior

Not applicable.

## Docs to update

`DEPENDENCY_MATRIX.md`: close the three "Open version questions" rows with the decisions —
closed, not restated. `TECH_DEBT.md`: a row only if a spike forced an accepted compromise.
ADR-003 or ADR-025 gain a consequence note if their fallback was taken.

---

## Implementation report (2026-09-03)

All three spikes ran to a decision. Findings are in `docs/spikes/`; the three "Open version
questions" rows in `DEPENDENCY_MATRIX.md` are closed, not restated.

| Spike | Decision rule row taken | Outcome |
| --- | --- | --- |
| 1 — Netty SSE | "events flush individually, connection survives 10 min, cancellation within 1 s" → keep Netty | **Netty stays.** 612 events over 10+ minutes on one connection, each flushed ~2 ms after emission, stream cancelled 8 ms after the client left. Confirmed with `curl -N` *and* a real headless-Chromium `EventSource` (329 events, 0 errors, still OPEN at the end). No http4s-ember, no dependency change, no HTTP-004 idle-timeout guard, no ADR-003 consequence note. |
| 2 — ScalablyTyped | the "yes" case: the plugin runs, so the row stays | **`mill-scalablytyped` 0.4.1 works** on Mill 1.1.8 / Scala 3.9 / Scala.js 1.22: 42 generated files, 3163 lines for `@codemirror/state`, compiled. The matrix row keeps its place and gains a version. ADR-025 needs no consequence note — its plan was proven, not contradicted. |
| 3 — Playwright pin | "newest stable release that resolves and runs the smoke navigation" | **1.62.0**, browser build **1234** (Chrome for Testing 151.0.7922.34). Smoke navigation passed. Both numbers pinned in `build.mill`; the exact CI install command is in the findings document for E2E-001. |

No `TECH_DEBT.md` row: no spike forced an accepted compromise.

### Deviations from this specification

1. **`./mill show Versions.playwright` cannot work.** `Versions` is a plain Scala object evaluated
   while the build definition compiles; Mill's `show` only prints tasks. The two Playwright numbers
   are re-exported by a small `versions` module, so the working commands are
   `./mill show versions.playwright` and `./mill show versions.playwrightBrowser`. The pinned values
   are still declared once, in `Versions`.
2. **The bundle-shape task is `frontend.uiKernel.checkBundleShape`, not `frontend.uiShell.…`.**
   There is no `uiShell` module in this build; `frontend.uiKernel` is the shell module BUILD-003
   created. The task is defined on the `KuiFrontendModule` trait, so every future frontend module
   inherits it.
3. **Spike 1 used a real browser rather than Node for the `EventSource` half.** Node 24 has no
   `EventSource` global, and the question was about browser behaviour anyway, so the spike drove
   headless Chromium through the Playwright build that spike 3 had just pinned.
