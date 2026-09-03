# CLADP-001 — `services/cluster/infrastructure`: the module and its first adapter test

- **ID:** CLADP-001
- **Title:** `services/cluster/infrastructure`: the module and its first adapter test
- **Milestone / Feature:** M1 / OT-001 (module layering), CL-001
- **Owner role:** Domain Architect (Cluster Registry context)
- **Size:** S
- **Dependencies / blocked by:** CLDOM-003 (the domain ports exist), KAFKA-007 (`ClusterAdmin[F]`
  exists in `libs/kafka` with `describeCluster` and the node list)

## Goal (user value)

The cluster service gets the layer that is allowed to touch Kafka. Until now `services/cluster`
had three modules and none of them could open a socket; M1's whole read path needs a fourth, and
this task creates it, wires it into the build, and proves with one test that a domain port can be
satisfied by an adapter without the domain learning anything about Kafka.

Nothing user-visible ships here. What ships is the boundary: after this task, exactly one module
in the cluster tree has `org.apache.kafka.*` on its classpath, and `./mill checkArchitecture`
starts caring about that (rule A10 lands in CFGOP-003, which depends on this task).

## Scope

1. The new Mill module `services.cluster.infrastructure` (JVM only) and its test module, declared
   in `build.mill` under the existing `object cluster` block, immediately after `application`.
2. The package skeleton `kui.cluster.infrastructure`, with:
   - `ClusterAdminAdapter` — a first, deliberately thin implementation of the domain's
     `ClusterAdmin[F]` port that satisfies `describeCluster` and `detectVersion` by delegating to a
     `kui.kafka.admin.ClusterAdmin[F]` handed to it in its constructor. The client *lifecycle*
     (creating one admin client per cluster, caching it, invalidating it) is **not** here — that
     is CLADP-002, and this task takes the `ClusterAdmin[F]` as a constructor parameter precisely
     so that the two concerns can be reviewed separately.
   - `ClusterProfileConnection` — the one function that projects a domain `ClusterProfile` onto
     the `kui.kernel.cluster` connection value `libs/kafka` consumes. It is a separate file, of
     about ten lines, because it is the single seam between the domain's naming and lane A's
     naming; if KAFKA-001 named a field differently, exactly one file changes.
3. `ClusterAdminContract` — an abstract MUnit suite in the test module that states, as executable
   assertions, what *any* `kui.cluster.domain.ClusterAdmin[F]` implementation must do. CLADP-002 runs it against
   a live broker; this task runs it against a stub `kui.kafka.admin.ClusterAdmin[F]`. This is the
   mechanism the DEVPLAN §7 test table means by "the adapter satisfies the same fake-port
   contract the application tests use".
4. `StubKafkaClusterAdmin` — the in-test stub of `kui.kafka.admin.ClusterAdmin[F]`, returning
   values it is constructed with. It lives in this module's test sources, not in `libs/testkit`:
   `libs/testkit` is on the classpath of modules that rule A10 forbids a Kafka client, so a stub
   of a `libs/kafka` type cannot live there.

## Non-goals

- **No client lifecycle, no `Resource`, no invalidation** (CLADP-002).
- **No store adapter** (CLADP-003), **no connectivity probe** (CLADP-004), **no change
  propagation** (CLADP-005).
- **No Testcontainers.** This task's test must run with no Docker daemon: its dependency list
  does not include CFGOP-004, and a first test that needs a container is a first test nobody runs
  locally.
- **No wiring into `services/cluster/app`.** `app` gains the `moduleDeps` edge in CLAPI-005. Do
  not edit `services/cluster/app` — CLAPI owns it (DEVPLAN §6.5).
- **No edit of `build.mill`'s architecture rule table.** CFGOP-003 owns rules A9 and A10. This
  task adds only the `object infrastructure` block and nothing else in that file.

## Design references

- ADR-041 (layering, machine-enforced) — the dependency rule points inward; `infrastructure` may
  see `domain`, and nothing in the service except `app` may see `infrastructure`.
- DEVPLAN §5.1 (the module's declared dependency set) and §5.2 (the new dependency edges and why
  each is legal), §10 decision D1 (the typed connection ADT lives in `libs/kernel`) and D3 (rules
  A9 and A10).
- ADR-006 (fs2-kafka and the admin ports) — `libs/kafka` owns the client; the service owns the
  port.
- `ARCHITECTURE.md` §3 (module layout), §4.2 (the `ClusterAdmin` shape the domain port mirrors).
- `research/kafka/admin-capabilities.md` §1, row "Describe cluster" — `controller()` is `null`
  during a controller failover and `authorizedOperations()` is `null` when ACLs are off. Both are
  asserted by `ClusterAdminContract` from day one, because both are the shape of a `NullPointer`
  in production and neither is reproducible on demand.

## Files to create

```
services/cluster/infrastructure/src/kui/cluster/infrastructure/ClusterAdminAdapter.scala
services/cluster/infrastructure/src/kui/cluster/infrastructure/ClusterProfileConnection.scala
services/cluster/infrastructure/test/src/kui/cluster/infrastructure/ClusterAdminContract.scala
services/cluster/infrastructure/test/src/kui/cluster/infrastructure/ClusterAdminAdapterSuite.scala
services/cluster/infrastructure/test/src/kui/cluster/infrastructure/StubKafkaClusterAdmin.scala
```

## Files to change

```
build.mill      (the `object infrastructure` block inside `object cluster`, nothing else)
```

## Public Scala signatures to implement

The project compiles with `-no-indent`: braces, not significant indentation. `-source:future`
means `if cond then ...`. Explicit result types on every public definition.

```scala
package kui.cluster.infrastructure

import cats.effect.kernel.Async
import kui.cluster.domain.{ClusterAdmin as ClusterAdminPort, ClusterDescription, ClusterProfile, KafkaVersion}
import kui.kernel.error.KuiError
import kui.observability.Telemetry
import org.typelevel.log4cats.StructuredLogger

/** The domain's `ClusterAdmin` port satisfied by `libs/kafka`'s `ClusterAdmin`.
  *
  * The two traits have the same name in different packages, which is unavoidable and harmless as
  * long as every file that sees both aliases one of them on import — as above. The domain port is
  * the one this class implements; the `libs/kafka` one is the one it calls.
  *
  * The port speaks `ClusterProfile`; `libs/kafka` speaks the pure `kui.kernel.cluster` connection
  * value. The projection between them is `ClusterProfileConnection.of`, and it is the only place
  * in the cluster tree that knows both names.
  */
final class ClusterAdminAdapter[F[_]: Async](
    admin: kui.kafka.admin.ClusterAdmin[F],
    telemetry: Telemetry[F],
    logger: StructuredLogger[F]
) extends ClusterAdminPort[F] {

  /** An absent controller and an absent Kafka cluster id are `Right`, not failures (CLDOM-002). */
  def describeCluster(profile: ClusterProfile): F[Either[KuiError, ClusterDescription]]

  /** `describeFeatures` with the `inter.broker.protocol.version` fallback (ADR-030). `None` — the
    * version could not be established — is a legitimate answer on a managed service.
    */
  def detectVersion(profile: ClusterProfile): F[Either[KuiError, Option[KafkaVersion]]]

  // describeQuorum, brokerConfigs, describeLogDirs and capabilities arrive in CLADP-002. Until
  // then this class does not compile as a complete `ClusterAdmin[F]`; declare the four remaining
  // methods with a single `???` body each and a `// CLADP-002` comment, so that the module
  // compiles and the contract suite can run against the two that are real. `-Werror` does not
  // object to `???`; a reviewer does, which is the point of the comment.
}

object ClusterAdminAdapter {

  /** The operation names that appear in the span name and in the `operation` metric attribute.
    * They are constants because a dashboard is built on them (`libs/observability`
    * `MetricNames.KafkaAdminDuration` carries `{cluster, operation, outcome}`).
    */
  object Operations {
    val DescribeCluster: String = "describeCluster"
    val DetectVersion: String   = "detectVersion"
  }
}
```

```scala
package kui.cluster.infrastructure

import kui.cluster.domain.ClusterProfile
import kui.kernel.cluster.ClusterConnection

/** Projects a domain profile onto the pure connection value `libs/kafka` accepts.
  *
  * There is no mapping and no lost information here on purpose: decision D1 of the M1 plan puts
  * `BootstrapServers`, `ClusterSecurity`, `AdminTuning` and the `properties` override map in
  * `libs/kernel`, and `ClusterProfile` composes them, so this is a projection of fields the
  * profile already holds — not a translation that could be wrong.
  */
object ClusterProfileConnection {
  def of(profile: ClusterProfile): ClusterConnection
}
```

**The one cross-lane naming assumption, and what to do if it is wrong.** KAFKA-001 creates the
`kui.kernel.cluster` package; the DEVPLAN names four types in it (`BootstrapServers`,
`ClusterSecurity`, `ClientProperties`, `AdminTuning`) but does not name the value that bundles
them for one cluster. This task calls it `ClusterConnection` and assumes it carries the cluster
id, the bootstrap servers, the security ADT, the admin tuning and the raw `properties` override
map. **If KAFKA-001 shipped a different name or shape, follow `libs/kafka` and change
`ClusterProfileConnection.scala` only** — that file exists so the blast radius of this assumption
is ten lines. Do not add a second ADT and do not ask; the rule of this plan is that the seam is
named here and reconciled in one file.

The same rule applies to the domain-side names. `ClusterAdmin`, `ClusterProfile`,
`ClusterDescription`, `Broker` and `KafkaVersion` are CLDOM-002/CLDOM-003's to define. Read those files before
writing this one and **follow them exactly**; the signatures above are the shape this task was
planned against, not a second definition of it.

## Library coordinates

From `DEPENDENCY_MATRIX.md`, restated in `build.mill`'s `Versions` object — use the `Versions`
fields, never a literal:

- `org.typelevel::cats-core::${Versions.cats}` (2.13.0)
- `org.typelevel::cats-effect::${Versions.catsEffect}` (3.7.1)
- `co.fs2::fs2-core::${Versions.fs2}` (3.13.0)
- `org.typelevel::log4cats-core::${Versions.log4cats}` (2.8.0)
- `org.typelevel::otel4s-core::${Versions.otel4s}` (1.1.0)

No new third-party coordinate is introduced by this task. `fs2-kafka` 4.0.0 and `kafka-clients`
4.3.1 arrive transitively through `libs.kafka`, which is exactly the point of A10's allow-list.

`moduleDeps`: `domain`, `libs.kafka`, `libs.config`, `libs.cache`, `libs.kernel.jvm`,
`libs.observability`. All six are declared now even though CLADP-001 only uses three, because the
module's dependency set is what CFGOP-003's rule test asserts and a set that grows task by task
makes that test a moving target. `libs.http`, `libs.contractsCore` and `services.cluster.contract`
are **not** dependencies and must never become ones: an adapter that could build a DTO would.

Test module: `ScalaTests with KuiTests`, plus `moduleDeps ++ Seq(libs.testkit.jvm)` and
`mvn"org.typelevel::munit-cats-effect::${Versions.munitCatsEffect}"` (2.2.0).

The `build.mill` block, to be inserted after `object application` inside `object cluster`:

```scala
    /** The adapters. The only module in the cluster tree with a Kafka client on its classpath
      * (rule A10), and the only one `app` is allowed to see (rule A9).
      */
    object infrastructure extends KuiJvmModule {
      def moduleDeps =
        Seq(domain, libs.kafka, libs.config, libs.cache, libs.kernel.jvm, libs.observability)

      def mvnDeps = Seq(
        mvn"org.typelevel::cats-core::${Versions.cats}",
        mvn"org.typelevel::cats-effect::${Versions.catsEffect}",
        mvn"co.fs2::fs2-core::${Versions.fs2}",
        mvn"org.typelevel::log4cats-core::${Versions.log4cats}",
        mvn"org.typelevel::otel4s-core::${Versions.otel4s}"
      )

      object test extends ScalaTests with KuiTests {
        def moduleDeps = super.moduleDeps ++ Seq(libs.testkit.jvm)
        def mvnDeps =
          super.mvnDeps() ++ Seq(mvn"org.typelevel::munit-cats-effect::${Versions.munitCatsEffect}")
      }
    }
```

## Acceptance criteria

```
$ ./mill services.cluster.infrastructure.compile
$ ./mill services.cluster.infrastructure.test
Test run kui.cluster.infrastructure.ClusterAdminAdapterSuite finished: 0 failed, 0 ignored, 6 total
SUCCESS

$ ./mill checkArchitecture
checkArchitecture: 36 modules, no layering violations

$ ./mill __.scalafmtCheckAll
$ ./mill __.fix --check
```

The module count printed by `checkArchitecture` is whatever the repository has at the time plus
one; the assertion is "no layering violations", not the number.

A second, negative acceptance check, run by hand once and recorded in the implementation report —
CFGOP-003 turns it into a build test:

```
$ ./mill show services.cluster.api.compileClasspath | grep -c kafka-clients
0
$ ./mill show services.cluster.infrastructure.compileClasspath | grep -c kafka-clients
1
```

## Tests required

`ClusterAdminContract` — an `abstract class ClusterAdminContract extends KuiIOSuite` with one
abstract member, `def port: Resource[IO, kui.cluster.domain.ClusterAdmin[IO]]`, and one abstract member giving
the profile to call it with. Its cases:

- `describeClusterReturnsTheClusterIdAndTheNodeList` — the brokers come from the description
  (CLDOM-002 puts the node list inside `ClusterDescription`; there is no separate `listBrokers`).
- `describeClusterWithNoControllerIsRepresentable` — the stub returns no controller (the KRaft
  failover window of `research/kafka/admin-capabilities.md` §1); the port must answer
  `Right(description)` with `controller = None`, not throw and not `Left`.
- `describeClusterWithNoAuthorizedOperationsIsRepresentable` — `authorizedOperations` absent
  because ACLs are off; same requirement.
- `aBrokerWithNoRackIsNoneAndNotAnEmptyString` — `Node.rack()` is nullable
  (`research/kafka/admin-capabilities.md` §1, "List brokers").
- `detectVersionReturnsNoneWhenNeitherSourceAnswers` — and that is a `Right(None)`, not a `Left`.
- `everyFailureIsALeftAndNeverAThrownException` — the stub is constructed to fail; the port
  returns `Left(_: KuiError)` and the effect does not raise.

`ClusterAdminAdapterSuite extends ClusterAdminContract` supplies `StubKafkaClusterAdmin` as the
`kui.kafka.admin.ClusterAdmin[IO]`. CLADP-002 adds `ClusterAdminLiveSuite extends
ClusterAdminContract` against a container, and the contract file does not change.

`StubKafkaClusterAdmin` is constructed from `Either[KuiError, *]` values per method, records the
calls it received in a `Ref`, and has **no** behaviour of its own. A stub with logic is a second
implementation to debug.

## Observability

Every port method is wrapped once, in `ClusterAdminAdapter`, in the same three things — a span, a
duration histogram and a failure log — so that CLADP-002 and CLADP-004 inherit them rather than
each inventing their own:

- **Span**: `kui.cluster.admin.<operation>` via `Telemetry`, with attributes `kui.cluster.id` and
  `kui.kafka.operation`. The operation names come from `ClusterAdminAdapter.Operations`.
- **Metric**: `MetricNames.KafkaAdminDuration` (`kui.kafka.admin.duration`) with attributes
  `{cluster, operation, outcome}`, `outcome` being `ok` or the `ErrorCode.wire` of the `KuiError`
  the call produced. Use the constant from `kui.observability.MetricNames`; never a literal.
- **Log**: a `Left` is logged once at WARN with the cluster id, the operation and the error code,
  and never with the profile. `ClusterProfile` holds secrets; the adapter must not log the
  profile, a rendered property map or a bootstrap string containing credentials.

## Degraded behavior

The adapter never degrades on its own — it has no cache and no fallback, and that is deliberate:
staleness is the snapshot's job (CLDOM-005 over `SnapshotCell`), not the adapter's. What the
adapter guarantees is that failure is *typed and total*: every path returns
`Either[KuiError, A]`, no method raises, and an exception escaping `libs/kafka` (which
`KafkaErrorMapper` should already have caught, KAFKA-005) is caught here as a last resort and
mapped to `InfrastructureError.Unreachable(upstream = "kafka:<clusterId>", cause = <class
name>)` — the class name, not the message, because a Kafka exception message can contain a
bootstrap string.

## Docs to update

None. `docs/domain/cluster.md` is CLDOM's (DEVPLAN §6.5); the module appears in
`ARCHITECTURE.md` §3 already as the planned fourth layer.
