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
