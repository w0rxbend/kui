# KERN-007 — `libs/testkit`: generators, fakes and golden files

- **ID:** KERN-007
- **Title:** `libs/testkit`: generators, fakes and golden files
- **Milestone / Feature:** M0 / supports every row
- **Owner role:** QA Engineer
- **Context / service:** `libs/testkit`
- **Size:** M
- **Dependencies / blocked by:** KERN-004

## Goal (user value)

Test code stops being copy-pasted. A worker writing a suite in M3 finds a generator for
`TopicName` and a fake for `ServiceClient` already written, tested and documented.

## Scope

1. ScalaCheck `Arbitrary`/`Gen` instances for every kernel identifier and value object
   (valid values by default, plus explicitly named invalid generators such as
   `Generators.invalidClusterId`).
2. Golden-file helper: `Golden.assertJson(actual: Json, path: String)` that compares against a
   committed file and, when `KUI_UPDATE_GOLDEN=1` is set, rewrites it — so updating a contract
   is one environment variable and a reviewable diff, never hand-editing JSON.
3. Hand-written fakes over the ports M0 introduces (no mocking framework, ADR-018):
   `FakeClock`, `FakeStructuredLogger` (records entries for assertions),
   `FakeCapabilityRegistry`, `FakeServiceClient` (programmable per-endpoint responses,
   including failures and delays).
4. Fault-injection stub helpers over `tapir-sttp-stub4-server`: a stub upstream that can be
   switched at runtime between `ok`, `slow(d)`, `status(n)`, `connectionRefused`.
5. `MUnitSuites`: shared base classes — `KuiSuite` (MUnit + ScalaCheck config: 100 samples,
   deterministic seed printed on failure) and `KuiIOSuite` (adds `munit-cats-effect`).

## Non-goals

No Testcontainers topology (M1 introduces the Kafka container; M0's only container use is the
Compose stack driven from `e2e`). No Playwright helpers (E2E-001 owns them). No fakes for
ports that do not exist yet.

## Design references

ADR-018 (MUnit only, fakes not mocks, `tapir-sttp-stub4-server`), PLAN §32, `ARCHITECTURE.md`
§4 row for `libs/testkit`.

## Files to create

```
libs/testkit/src/kui/testkit/Generators.scala
libs/testkit/src/kui/testkit/Golden.scala
libs/testkit/src/kui/testkit/KuiSuite.scala
libs/testkit/src/kui/testkit/fakes/FakeClock.scala
libs/testkit/src/kui/testkit/fakes/FakeStructuredLogger.scala
libs/testkit/src/kui/testkit/fakes/FakeServiceClient.scala
libs/testkit/src/kui/testkit/stubs/StubUpstream.scala
libs/testkit/test/src/kui/testkit/GoldenSuite.scala
libs/testkit/test/src/kui/testkit/GeneratorsSuite.scala
```

## Public Scala signatures to implement

```scala
package kui.testkit

object Generators:
  given Arbitrary[ClusterId]
  given Arbitrary[TopicName]
  // ... one per identifier and value object from KERN-001 and KERN-003
  val invalidClusterId: Gen[String]
  val invalidTopicName: Gen[String]
  def pageOf[A: Arbitrary]: Gen[Page[A]]

object Golden:
  /** Compares `actual` with the file at `src/test/resources/golden/<name>`, pretty-printed
    * with sorted keys so diffs are readable. Set KUI_UPDATE_GOLDEN=1 to rewrite. */
  def assertJson(actual: io.circe.Json, name: String)(using munit.Location): Unit
  def read(name: String): io.circe.Json

abstract class KuiSuite   extends munit.ScalaCheckSuite
abstract class KuiIOSuite extends munit.CatsEffectSuite with munit.ScalaCheckEffectSuite

package kui.testkit.stubs

enum UpstreamBehaviour:
  case Ok(body: io.circe.Json)
  case Slow(delay: FiniteDuration, then: UpstreamBehaviour)
  case Status(code: Int, body: io.circe.Json)
  case ConnectionRefused

trait StubUpstream[F[_]]:
  def backend: sttp.client4.Backend[F]
  def set(behaviour: UpstreamBehaviour): F[Unit]
  def requests: F[List[RecordedRequest]]
```

## Library coordinates

```
org.scalameta::munit::1.3.6
org.scalameta::munit-scalacheck::1.3.1
org.scalacheck::scalacheck::1.20.0
org.typelevel::munit-cats-effect::2.2.0
org.typelevel::discipline-munit::2.0.0
com.softwaremill.sttp.tapir::tapir-sttp-stub4-server::1.13.31
com.dimafeng::testcontainers-scala-munit::0.44.1        (declared now, used from E2E-002)
org.bouncycastle:bcpkix-jdk18on:1.85                    (declared now, used from M1 TLS tests)
```

All in `test` scope of the consuming modules; `libs/testkit` itself declares them as `main`
because it *is* test infrastructure.

## Acceptance criteria

```
$ ./mill libs.testkit.compile
$ ./mill libs.testkit.test
$ KUI_UPDATE_GOLDEN=1 ./mill libs.contractsCore.jvm.test && git diff --stat   # rewrites goldens
```

## Tests required

- `GoldenSuite`: a mismatched document fails with a readable diff naming the file; the update
  flag rewrites it; a missing file fails with "run with KUI_UPDATE_GOLDEN=1 to create".
- `GeneratorsSuite`: every valid generator produces values that pass the corresponding
  `from` constructor (property, 500 samples); every invalid generator produces values that
  fail it.

## Observability

`FakeStructuredLogger` exposes `entries: F[List[LogEntry]]` with the structured context map,
so later tasks can assert observability requirements ("the span carries `cluster.id`") instead
of describing them.

## Degraded behavior

Not applicable.

## Docs to update

`README.md` (testing section): how to run one suite, how to update a golden file, the rule
that fakes live here and never in a service's test sources.
