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
cancellation delay. If any of these fail, the finding is written up and ADR-003's documented
fallback (http4s-ember) is scheduled as a new task before HTTP-004 starts.

### Spike 2 — `mill-scalablytyped` on Mill 1.1.x (risk R-6)

Question: does `lolgab/mill-scalablytyped` load and generate under Mill 1.1.8 and Scala 3.9?
Method: generate a facade for `@codemirror/state` only, in a scratch module, and delete it.
Record: yes/no, the plugin version tried, the error if it fails. A "no" costs nothing in M0
(facades are M2+) but must be written down so ADR-025 can be revisited with evidence.

### Spike 3 — Playwright JVM version pin (risk R-10)

Question: which `com.microsoft.playwright:playwright` version is current, and which browser
revision does it download? Method: resolve the artifact, run `playwright install chromium`,
record both versions. Deliverable: `Versions.playwright` and `Versions.playwrightBrowser` set
in `build.mill`, plus the exact CI install command for BUILD-004's e2e job.

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
consequence, confidence.

## Tests required

- Unit test for the bundle-shape assertion logic itself (given a fake linker output directory
  containing / not containing the expected files, the task passes / fails with the right
  message).

## Observability / Degraded behavior

Not applicable.

## Docs to update

`DEPENDENCY_MATRIX.md`: close the three "Open version questions" rows with the answers, or
restate them with the new evidence. `TECH_DEBT.md`: a row only if a spike forces an accepted
compromise.
