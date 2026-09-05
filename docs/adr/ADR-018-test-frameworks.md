# ADR-018 — Test frameworks: MUnit only, ScalaCheck, Testcontainers, JVM Playwright

- Status: Accepted; **amended by [ADR-048](ADR-048-solidjs-typescript-vite-frontend.md)** (2026-09-05)
- Date: 2026-09-03

> **Amended by ADR-048 for the frontend only.** MUnit, ScalaCheck, Testcontainers and the JVM
> Playwright driver are unchanged, and so is every JVM decision below. What changes is that there
> are no browser-side Scala tests any more: the frontend is TypeScript and its unit and component
> suites run under **Vitest** in the pnpm workspace (`pnpm test` in `frontend/`). References below
> to a Scala.js test runner, to MUnit covering "JVM and Scala.js modules", and to a Scala.js
> Playwright facade describe a build that no longer exists. See ADR-048 §5.

## Context

The project's technology plan chose MUnit and rejected Weaver pending research. The frontend needs a Scala.js
test runner and an E2E strategy; the plan had an open "Scala.js Playwright facade" item.

## Decision

- **MUnit 1.3.6** is the single test framework for JVM and Scala.js modules;
  `munit-cats-effect` 2.2.0 for `IO` suites; `munit-scalacheck` 1.3.1 + ScalaCheck 1.20.0 for
  properties; `discipline-munit` 2.0.0 for type class laws in `libs/kernel`. Weaver is not
  added.
- No mocking framework: hand-written fakes over ports live in `libs/testkit`.
- Integration: `testcontainers-scala` 0.44.1 (munit, kafka) with `org.testcontainers:testcontainers-kafka`
  2.0.5 explicitly; one static topology per test JVM (Kafka KRaft with JMX, Schema Registry,
  Connect, ksqlDB, Prometheus, an AD/LDAP container) with deliberately broken endpoints
  (a dead registry URL before the live one, an unreachable second Connect, a read-only
  cluster) so failover and degradation are always exercised.
- HTTP fakes: `tapir-sttp-stub4-server` and sttp backend stubs (no WireMock).
- Frontend unit tests: MUnit under Node; `JsEnvConfig.JsDom()` for DOM tests with
  `scala-dom-testutils`; `JsEnvConfig.Playwright` only for the kernel's `EventSource`/`fetch`
  wrappers.
- E2E: **JVM Playwright** (`com.microsoft.playwright`) in a Mill `e2e` module with MUnit and
  Testcontainers against the all-in-one JAR and the distributed Compose; fault-isolation
  scenarios stop a container and assert the shell keeps working. The Scala.js Playwright
  facade research item is closed.
- Test naming and layering follow the project's testing conventions; TLS test certificates via BouncyCastle 1.85.

## Evidence

- `research/scala/ecosystem-mapping.md` F10 (versions; MUnit-only rationale; Weaver group move
  and breaking changes), F9 (Testcontainers 2.x artifacts).
- `research/kafbat/architecture.md` F15, D13 (static topology with broken endpoints).
- `research/scala/frontend-research.md` §7 and "ADR-018 addendum".

## Consequences

- One runner, one reporter, Metals/BSP support everywhere.
- Parallelism via `ResourceSuiteLocalFixture` and Mill per-module test parallelism.

## Alternatives rejected

- Weaver as a second framework: violates the project's rule against two libraries for the same responsibility; its advantages are reachable in MUnit.
- TypeScript Playwright: a second toolchain in CI; loses shared Scala models and Testcontainers.

## Reversibility

High.
