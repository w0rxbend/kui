# BUILD-004 — CI pipeline

- **ID:** BUILD-004
- **Title:** CI pipeline (PLAN §49)
- **Milestone / Feature:** M0 / KU-008
- **Owner role:** Principal Scala Engineer
- **Context / service:** build root
- **Size:** M
- **Dependencies / blocked by:** BUILD-003

## Goal (user value)

Contributors get the same verdict the maintainers get, on every push, without asking anyone.
A green check on a pull request means the milestone exit criteria still hold.

## Scope

A GitHub Actions workflow implementing PLAN §49 in stages, each a separate job so a failure
names the stage:

```
checkout → setup (JDK 21 temurin, Mill cache, coursier cache)
  → compile            ./mill __.compile
  → style              ./mill __.checkFormat && ./mill __.fix --check
  → unit + property    ./mill __.test
  → contract           ./mill __.test  (same task; contract suites live in api modules)
  → frontend link      ./mill frontend.__.fullLinkJS
  → bundle shape       ./mill frontend.uiShell.checkBundleShape       (added by BUILD-006)
  → openapi diff       ./mill services.gateway.api.openApiCheck       (added by GW-007)
  → docker build       ./mill deployment.docker.__.build              (added by INFRA-001)
  → e2e                ./mill e2e.test                                (added by E2E-001)
```

Stages that do not exist yet are added by the task that creates them; this task's workflow
lists only the stages whose tasks are already merged, and each later task adds its own line.
Caching: `~/.cache/coursier` and `out/` keyed on `build.mill` + `.mill-version`.
Concurrency: cancel in-progress runs for the same ref.

## Non-goals

No release job, no publishing, no SBOM, no vulnerability scan, no performance gate (all M8).
No self-hosted runners.

## Design references

PLAN §49, PLAN §46, ADR-001 (JDK 21), ADR-018 (test runner).

## Files to create

```
.github/workflows/ci.yml
```

## Acceptance criteria

```
$ act -j compile          # or: push a branch and observe the run
```

- A pull request that introduces a formatting violation fails the `style` job and no other.
- A pull request that introduces an unused import fails the `compile` job.
- Total wall-clock for a warm cache is under 15 minutes; record the observed time in the
  Implementation Report so later milestones can see the trend.

## Tests required

None. The workflow is the test; verify by pushing a deliberately broken branch once and
deleting it.

## Observability

The workflow uploads `out/**/test-report.xml` as an artifact on failure so a contributor can
read the failing assertion without re-running locally.

## Degraded behavior

If Docker is unavailable on the runner, the `docker build` and `e2e` jobs are skipped with an
explicit `if:` condition and the run is marked as such — they are never silently passed.

## Docs to update

`README.md`: what CI runs and how to reproduce each stage locally.

---

## Implementation report (2026-09-03)

`.github/workflows/ci.yml` plus a composite action, `.github/actions/setup-build`, that installs
JDK 21 (Temurin), Node and the caches once instead of five times.

Five jobs, one per stage that has a build task today: `compile`, `style`, `architecture`, `test`,
`frontend`. Integration tests, the OpenAPI diff, the Docker build and E2E are absent on purpose —
this specification says the workflow lists only merged stages, and each later task adds its own job.

### Verification

`actionlint` reports no problems, and every command the workflow runs was executed locally against
a clean checkout of the commit under test, in a separate git worktree so that another agent's
in-flight edits could not flatter the result:

```
### compile
472/472, SUCCESS] ./mill __.compile 9s
### checkFormat
21/21, SUCCESS] ./mill __.checkFormat 1s
### fix --check
358/358, SUCCESS] ./mill __.fix --check 1s
### architecture
checkArchitecture: 8 modules, no layering violations
### jvm tests
137/137, SUCCESS] ./mill libs.kernel.jvm.test build-tests.test
### js kernel
159/159, SUCCESS] ./mill libs.kernel.js.test
### js frontend
221/221, SUCCESS] ./mill frontend.uiKernel.test
### frontend link
245/245, SUCCESS] ./mill frontend.__.fullLinkJS
### bundle shape
checkBundleShape: no feature packages configured, nothing to assert yet (UI-012 adds the first feature module)
```

The wall-clock figure this specification asks for cannot be recorded yet: no run has happened on a
GitHub runner, and a local warm-cache time on a developer machine is not the number the trend is
about. The first real run supplies it.

### Deviations from this specification

1. **The test stage is three commands, not `./mill __.test`.** Running a Scala.js test module in the
   same Mill invocation as any other test module fails inside Mill's own Scala.js test runner with
   `UnsupportedOperationException`, with `-j 1` as well as in parallel. Every module passes alone.
   Recorded as blocker **B-003**; the workflow runs the JVM suites together and each Scala.js suite
   on its own, which is the same coverage. Collapsing it back is a one-line change.
2. **`jsdom` is installed into the repository root, not globally.** The README's `npm install -g
   jsdom` advice does not work: the generated test script `require`s jsdom and Node resolves that by
   walking up from the script's directory, so `NODE_PATH` is ignored and the run fails with
   `Cannot find module 'jsdom'`. Verified both ways; the README is corrected.
3. **An `architecture` job was added.** PLAN §49 does not list it, but `checkArchitecture` exists
   (BUILD-005) and is exactly the kind of rule that decays without a gate.
4. **`act -j compile` was not used.** `act` is not installed in this environment. The workflow was
   validated with `actionlint` and by running every command it contains against a clean worktree,
   which is the stronger of the two checks — `act` proves the YAML runs, not that the build is green.
5. **The deliberately-broken-branch experiment is left to the first real push.** It needs a GitHub
   remote to observe, and this repository has no runs yet.

Blocker **B-002** (no Node, so no Scala.js tests) is resolved by this task together with the local
setup: CI installs Node in every job, and the JS suites were observed passing.
