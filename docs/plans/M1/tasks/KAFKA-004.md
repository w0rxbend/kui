# KAFKA-004 — `libs/kafka`: module, client factory, `client.id`, timeouts, invalidation

- **ID:** KAFKA-004
- **Title:** `libs/kafka`: module, client factory, `client.id`, timeouts, invalidation
- **Milestone / Feature:** M1 / CL-001, KU-010
- **Owner role:** Principal Scala Engineer, reviewed by the Chief Architect
- **Context / service:** `libs/kafka` (new module)
- **Size:** M
- **Dependencies / blocked by:** **KAFKA-003** (this task calls `ConnectionProperties.resource`,
  which KAFKA-003 creates; KAFKA-003 in turn depends on KAFKA-002). Corrected at the M1 gate
  review — DEVPLAN §6.2 said KAFKA-002, which would have left this task with no property
  renderer. On the milestone's critical path — STORE-005 cannot start until this task lands.

## Goal (user value)

KUI opens a connection to a Kafka cluster, keeps it, attributes it in the broker's own logs and
quotas, bounds every call it makes, and throws the connection away and rebuilds it when the
failure it just saw was the kind that means the connection is dead. All of that happens once,
in one module, so that ten services in M2–M8 inherit it instead of each getting it slightly
wrong.

## Scope

1. Create the Mill module `libs.kafka` (JVM only).
2. **Record the R-5 verification.** The first act of this task is to check what the pinned
   fs2-kafka 4.0.0 actually wraps, and to write the result down. See "The client decision"
   below: the decision itself is already made — this step records the evidence, it does not
   reopen the question.
3. `KafkaFutures` — the bridge from `KafkaFuture[A]` to `F[A]`: unwrap `CompletionException`
   and `ExecutionException` to their cause, and get off the admin client's single I/O thread
   before any KUI code runs.
4. `AdminClientPool` — one admin client per cluster, created lazily, shared, and rebuilt on
   demand. This is where `client.id`, `request.timeout.ms`, `default.api.timeout.ms` and
   invalidation live.
5. Consumer and producer factories over fs2-kafka, so that STORE-006 and M3's message browsing
   have one place that turns a `ClusterConnection` into `KafkaConsumer.resource` /
   `KafkaProducer.resource` settings.

## The client decision (read this before writing a line of code)

**`libs/kafka` builds its admin calls on the raw `org.apache.kafka.clients.admin.Admin`, behind
a `KafkaFuture` bridge. It uses fs2-kafka for consumers and producers.**

ADR-006 assumed the opposite default — "one adapter over `KafkaAdminClient[F]` … using the raw
`Admin` escape hatch where fs2-kafka lags" — and its own consequences section carries the open
question of how much lagging there is. The answer, from
`research/kafka/admin-capabilities.md` §0 and §1, is: enough that the escape hatch would be the
normal path rather than the exception. Four reasons, each of which is a required behaviour of
this milestone and not a matter of taste:

1. **The option objects.** `DescribeClusterOptions.includeAuthorizedOperations`,
   `DescribeConfigsOptions.includeSynonyms(true).includeDocumentation(bool)` and the per-call
   `timeoutMs` overrides are all required by KAFKA-007, KAFKA-008 and KAFKA-009.
   fs2-kafka's wrappers do not surface them.
2. **The null controller.** fs2-kafka models the controller as an effect producing a `Node`.
   Kafka returns `null` for it during a KRaft controller failover
   (`research/kafka/admin-capabilities.md` §1, "Describe cluster"), and DEVPLAN §7's admin
   adapter suite requires "a `null` controller during failover is `None` and not a crash". A
   wrapper that cannot express absence cannot satisfy that test.
3. **Per-key partial results.** `BatchResult` (KAFKA-005) exists because
   `describeConfigs().values()` and `describeLogDirs().descriptions()` let one failed key be
   skipped while the rest succeed. The convenient wrappers return the `all()`-shaped future,
   which fails the whole batch on one bad key — the exact behaviour the milestone's
   "authenticates but authorizes nothing" fault-injection scenario forbids.
4. **One layer, not two.** The bridge is roughly forty lines in one file. Wrapping a wrapper and
   then reaching past it for most calls means two abstractions to debug and a reader who cannot
   tell which one a given method went through.

fs2-kafka stays a pinned dependency and is not decorative: consumers and producers are where
its value actually is — streaming, cancellation, resource safety and commit semantics — and
those are what STORE-006 and M3 need.

**Record it.** Write `libs/kafka/CLIENT-CHOICE.md` (about a page) with the four reasons above,
the verification table below filled in against the pinned tag, and the sentence "ADR-006 needs
an amendment recording this; CFGOP-008 writes it." Verification table, one row per call, three
columns — call, wrapped by fs2-kafka 4.0.0 (yes/no), options KUI needs available (yes/no):
`describeCluster`, `describeConfigs`, `describeLogDirs`, `describeFeatures`,
`describeMetadataQuorum`, `listOffsets`, `listTopics`, `describeTopics`, `createTopics`,
`listGroups`, `describeProducers`. Fill it from the actual jar
(`./mill show libs.kafka.resolvedMvnDeps`, then read the class), not from the ADR.

## Non-goals

- **No `ClusterAdmin` methods.** `describeCluster` and friends are KAFKA-007 to KAFKA-009.
  This task's admin surface is `run` and `invalidate` and nothing else.
- **No error mapping.** `KafkaErrorMapper` is KAFKA-005. This task defines only the
  *reconnect-class predicate*, because invalidation cannot wait for the mapper and the mapper
  will be asserted against this predicate rather than restating it.
- **No batching.** KAFKA-006.
- **No `TopicAdmin`, `GroupAdmin`, `SecurityAdmin` or `MessageBrowsePort` traits, not even
  empty ones.** DEVPLAN §3 and risk R-11: a port designed before its first caller is designed
  wrong, and an empty trait is an invitation to fill it.
- No connection pooling across clusters, no shared client, no global registry. One cluster, one
  client, keyed by `ClusterId`.

## Design references

ADR-006 (the module's remit, the adapter invariants, and the open question this task closes),
`research/kafka/admin-capabilities.md` §0 — every row of it is a requirement here: single I/O
thread, exception wrapping, null results, timeouts, client id, invalidation — ADR-030 (2.8
minimum), ADR-041 rules A5 and A10, `ARCHITECTURE.md` §4.2 and §13 (the observability
standard), DEVPLAN §5.1, §6.4 and §8 risk R-5.

## Files to create

```
libs/kafka/CLIENT-CHOICE.md
libs/kafka/src/kui/kafka/ClientId.scala
libs/kafka/src/kui/kafka/KafkaFutures.scala
libs/kafka/src/kui/kafka/AdminClientPool.scala
libs/kafka/src/kui/kafka/AdminInvalidation.scala
libs/kafka/src/kui/kafka/ConsumerFactory.scala
libs/kafka/src/kui/kafka/ProducerFactory.scala
libs/kafka/test/src/kui/kafka/ClientIdSuite.scala
libs/kafka/test/src/kui/kafka/KafkaFuturesSuite.scala
libs/kafka/test/src/kui/kafka/AdminClientPoolSuite.scala
libs/kafka/test/src/kui/kafka/AdminInvalidationSuite.scala
```

## Files to change

```
build.mill    # add `object kafka` inside `object libs`; add two entries to `object Versions`
```

```scala
  /** The only module in KUI that opens a Kafka connection, plus `libs/config`'s metadata-store
    * adapter and each service's `infrastructure` (rule A10).
    *
    * Admin calls go through the raw `Admin` client behind a `KafkaFuture` bridge; consumers and
    * producers go through fs2-kafka. `libs/kafka/CLIENT-CHOICE.md` records why, with the
    * verification against the pinned fs2-kafka release.
    */
  object kafka extends KuiPureModule with KuiJvmModule {
    def moduleDeps = Seq(kernel.jvm, kafkaAuth, observability)

    def mvnDeps = Seq(
      mvn"org.typelevel::cats-core::${Versions.cats}",
      mvn"org.typelevel::cats-effect::${Versions.catsEffect}",
      mvn"co.fs2::fs2-core::${Versions.fs2}",
      mvn"org.typelevel::fs2-kafka::${Versions.fs2Kafka}",
      // Pinned above fs2-kafka's own transitive 4.2.0, per DEPENDENCY_MATRIX.md.
      mvn"org.apache.kafka:kafka-clients:${Versions.kafkaClients}",
      mvn"org.typelevel::log4cats-core::${Versions.log4cats}",
      mvn"org.typelevel::otel4s-core::${Versions.otel4s}"
    )

    // Named by neither the compiler nor this module's code: the Kafka client loads them
    // reflectively when a topic is compressed. On the compile classpath they would be an
    // invitation to import a codec class directly.
    def runMvnDeps = Seq(
      mvn"org.xerial.snappy:snappy-java:${Versions.snappy}",
      mvn"at.yawk.lz4:lz4-java:${Versions.lz4}"
    )

    object test extends ScalaTests with KuiTests {
      def moduleDeps = super.moduleDeps ++ Seq(testkit.jvm)
    }
  }
```

New `Versions` entries: `fs2Kafka = "4.0.0"`, `snappy = "1.1.10.8"`, `lz4 = "1.11.2"`
(`kafkaClients = "4.3.1"` was added by KAFKA-002). All four numbers are the rows
`DEPENDENCY_MATRIX.md` records; change both files or neither.

**The `libs.observability` edge** is new and is not in DEVPLAN §5.1's dependency list. It is
legal and it is precedented: `libs.http` already depends on `libs.observability` for exactly
this reason, and rule A10 constrains who may see a *Kafka* client, not who may see a metric.
Without it, `kui.kafka.admin.duration` — a metric `docs/operations/observability.md` already
publishes as arriving in M1 — would have to be threaded in through a callback from every
caller, which is more coupling, not less.

## Public Scala signatures to implement

```scala
package kui.kafka

import kui.kernel.ClusterId
import kui.kafka.auth.ClientPurpose

/** `kui-admin-<clusterId>-<seq>`, `kui-consumer-<clusterId>-<seq>`, ...
  *
  * Unique per client, not per cluster: a broker's request log and its quota accounting are
  * keyed by `client.id`, and two clients sharing one make both unreadable
  * (`research/kafka/admin-capabilities.md` §0). `seq` comes from a process-wide counter, so a
  * client recreated by invalidation is distinguishable in the broker log from the one it
  * replaced — which is exactly the moment an operator is reading that log.
  */
opaque type ClientId = String

object ClientId {
  def next[F[_]: Sync](purpose: ClientPurpose, cluster: ClusterId): F[ClientId]
  def unsafe(raw: String): ClientId
  extension (id: ClientId) def value: String
}
```

```scala
package kui.kafka

import cats.effect.Async
import org.apache.kafka.common.KafkaFuture

/** The bridge from Kafka's own future to a cats-effect one.
  *
  * Two things go wrong if this is written at a call site, and both are recorded in
  * `research/kafka/admin-capabilities.md` §0. A `KafkaFuture` completes exceptionally with a
  * `CompletionException` or an `ExecutionException` wrapping the error that matters, so code
  * that matches on the thrown type matches on the wrapper and falls through to a generic
  * branch. And every callback runs on the admin client's single network thread, where blocking
  * stalls every other in-flight request until `request.timeout.ms`.
  */
object KafkaFutures {

  /** Runs `make` (which issues the request), then completes when the future does — with the
    * cause unwrapped and execution shifted off the Kafka I/O thread. Cancelling the resulting
    * effect cancels the `KafkaFuture`.
    */
  def fromFuture[F[_]: Async, A](make: F[KafkaFuture[A]]): F[A]

  /** As `fromFuture`, but `null` becomes `None` rather than a `NullPointerException` three
    * frames away. Kafka returns `null` for a missing committed offset and for the controller
    * during a failover; both are normal. */
  def fromNullableFuture[F[_]: Async, A](make: F[KafkaFuture[A]]): F[Option[A]]

  /** Unwraps `CompletionException` and `ExecutionException`, recursively, to the first cause
    * that is neither. Public because `KafkaErrorMapper` (KAFKA-005) calls it before it
    * classifies anything. */
  def unwrap(t: Throwable): Throwable
}
```

```scala
package kui.kafka

/** Which failures mean "this connection is finished" rather than "this request failed".
  *
  * The set is closed and it is small: timeout, SASL authentication, SSL authentication, broker
  * not available (`research/kafka/admin-capabilities.md` §0, "Invalidation"; Kafbat closes and
  * recreates its client on any admin error, which throws away a working connection every time
  * a user asks about a topic they are not authorized for). KAFKA-005's mapper is asserted
  * against this predicate rather than restating the set, so the two cannot drift.
  */
object AdminInvalidation {
  def isReconnectClass(t: Throwable): Boolean
  val reconnectClasses: Set[Class[? <: Throwable]]
}
```

```scala
package kui.kafka

import cats.effect.{Async, Resource}
import fs2.io.file.Files
import kui.kernel.KuiError
import kui.kernel.cluster.ClusterConnection
import org.apache.kafka.clients.admin.Admin

/** One admin client per cluster: created on first use, shared by every caller, replaced when a
  * reconnect-class failure says the old one is finished.
  */
trait AdminClientPool[F[_]] {

  /** Runs one admin call.
    *
    * `operation` is the metric and log label (`describeCluster`, `describeLogDirs`, ...): a
    * short fixed name from a closed set, never a value that varies per request, because it
    * becomes a metric attribute.
    *
    * Failures arrive as `Throwable` here — this task does not map them. KAFKA-005 layers the
    * mapping on top, and `ClusterAdmin` (KAFKA-007) is the first thing callers actually see.
    */
  def run[A](connection: ClusterConnection, operation: String)(
      call: Admin => F[A]
  ): F[A]

  /** Closes the current client for this cluster, if any; the next `run` builds a new one.
    * Idempotent and safe to call concurrently: two invalidations of the same generation close
    * one client, not two. */
  def invalidate(id: ClusterId): F[Unit]

  /** Closes and forgets a cluster entirely — for a profile that was removed from configuration
    * (CLADP-005). */
  def evict(id: ClusterId): F[Unit]
}

object AdminClientPool {
  def resource[F[_]: Async: Files](
      metrics: AdminMetrics[F]
  ): Resource[F, AdminClientPool[F]]
}

/** The measurement hook, so that `libs/kafka` records `kui.kafka.admin.duration` without every
  * caller remembering to. Implemented over otel4s in this module; `libs/testkit` gets a
  * counting fake. */
trait AdminMetrics[F[_]] {
  def timed[A](cluster: ClusterId, operation: String)(fa: F[A]): F[A]
}
```

```scala
package kui.kafka

import cats.effect.{Async, Resource}
import fs2.io.file.Files
import fs2.kafka.{ConsumerSettings, ProducerSettings}
import kui.kernel.cluster.ClusterConnection

/** Turns a connection into fs2-kafka settings. The security half comes from
  * `ConnectionProperties.resource` (KAFKA-003), so a consumer and an admin client cannot end
  * up authenticating differently. */
object ConsumerFactory {

  /** `enable.auto.commit=false`, no `group.id` unless one is given, `Array[Byte]` key and
    * value deserializers, `auto.offset.reset=none` — every one of those is a decision the
    * references made and documented (`research/kafka/admin-capabilities.md` §4), and defaults
    * that differ per call site are how two browse endpoints end up behaving differently.
    */
  def settings[F[_]: Async: Files](
      connection: ClusterConnection,
      groupId: Option[String]
  ): Resource[F, ConsumerSettings[F, Array[Byte], Array[Byte]]]
}

object ProducerFactory {
  /** `acks=all`, `enable.idempotence=true`, `Array[Byte]` serializers. */
  def settings[F[_]: Async: Files](
      connection: ClusterConnection
  ): Resource[F, ProducerSettings[F, Array[Byte], Array[Byte]]]
}
```

### Behaviour the pool must have

- **Creation** is `Resource.make(Async[F].blocking(Admin.create(props)))(client =>
  Async[F].blocking(client.close(closeTimeout)))`. `Admin.create` reads the properties and
  starts a network thread; it is blocking work and belongs on the blocking pool.
- **Properties** come from `ConnectionProperties.resource` (KAFKA-003) plus
  `request.timeout.ms` = `admin.requestTimeout`, `default.api.timeout.ms` = `admin.apiTimeout`,
  and the generated `client.id`. The materialized keystore files must live at least as long as
  the client, so the pool holds each client's `Resource` finalizer and runs it on invalidation.
- **Sharing** is a `Ref[F, Map[ClusterId, Entry]]` where `Entry` holds the client, its
  generation number and its finalizer, guarded by a per-cluster `Semaphore` so that ten
  concurrent first calls create one client rather than ten.
- **Invalidation is generation-checked.** `run` remembers the generation it used; on a
  reconnect-class failure it invalidates *that* generation. Two requests failing at once on the
  same dead client therefore replace it once. This is the difference between a reconnect and a
  reconnect storm.
- **Invalidation does not retry.** The failed call still fails, with its original error. The
  caller sees one failure and the next call gets a fresh client. Retrying inside the pool would
  double every timeout and hide the failure from the metric.
- **A properties failure is not a client failure.** If `ConnectionProperties` returns `Left`,
  `run` fails with that `KuiError` and no client is created or cached — a misconfigured cluster
  must not occupy a pool slot.
- **`evict` on profile change.** CLADP-005 calls it when a cluster's connection details change,
  which is the only correct response: the old client is bound to the old bootstrap list and
  credentials.

## ADRs this task must obey

ADR-006 (module remit, adapter invariants, `client.id`, invalidation, timeouts — and the
amendment this task's `CLIENT-CHOICE.md` requests), ADR-030 (no version branching in the
factory; capability probing is KAFKA-009), ADR-041 A5/A10, ADR-009 and `ARCHITECTURE.md` §13
(one measurement per call to another system), ADR-016 (the pool is a resource cache, not a data
cache: it holds no cluster data and therefore needs no TTL).

## Library coordinates

`org.typelevel::fs2-kafka::4.0.0`, `org.apache.kafka:kafka-clients:4.3.1` (explicitly pinned
over fs2-kafka's transitive 4.2.0), `org.xerial.snappy:snappy-java:1.1.10.8` and
`at.yawk.lz4:lz4-java:1.11.2` at **runtime** scope, `org.typelevel::cats-effect::3.7.1`,
`co.fs2::fs2-core::3.13.0`, `org.typelevel::cats-core::2.13.0`,
`org.typelevel::log4cats-core::2.8.0`, `org.typelevel::otel4s-core::1.1.0`. Every number is a
`DEPENDENCY_MATRIX.md` row.

Note the forbidden neighbour: `DEPENDENCY_MATRIX.md`'s banned list includes Confluent's
`kafka-clients -ccs` and `com.github.fd4s:fs2-kafka` 3.x. If a resolution report shows either,
the pin is wrong.

## Acceptance criteria

```
$ ./mill libs.kafka.compile          # clean under -Werror
$ ./mill libs.kafka.test
$ ./mill libs.kafka.checkFormat
$ ./mill libs.kafka.fix --check
$ ./mill show libs.kafka.resolvedMvnDeps | grep kafka-clients
# exactly one kafka-clients, at 4.3.1
$ ./mill show libs.kafka.resolvedMvnDeps | grep -c "fd4s"
# 0
$ test -f libs/kafka/CLIENT-CHOICE.md && grep -q "describeMetadataQuorum" libs/kafka/CLIENT-CHOICE.md
```

`libs/kafka/CLIENT-CHOICE.md` exists, its verification table has a filled row for each of the
eleven calls listed above, and it names the ADR-006 amendment CFGOP-008 must write.

## Tests required

- `ClientIdSuite` (unit): `formatIsPurposeClusterSeq`; `sequenceIncreasesAcrossCalls`;
  `twoClustersGetDistinctIds`.
- `KafkaFuturesSuite` (unit, `munit-cats-effect`):
  - `unwrapsCompletionException` and `unwrapsExecutionException`, including a nested pair
    (`CompletionException(ExecutionException(TimeoutException))`).
  - `unwrapLeavesAnUnwrappedThrowableAlone`.
  - `nullBecomesNone`.
  - `cancellationCancelsTheKafkaFuture` — assert on a `KafkaFutureImpl` the test completes
    itself; this is the test that keeps a cancelled request from holding an I/O thread.
  - `theCallerDoesNotRunOnTheCompletingThread` — complete the future from a named thread and
    assert the continuation observes a different one.
- `AdminInvalidationSuite` (unit): a table over the four reconnect classes and over eight
  request-level classes (`TopicAuthorizationException`, `UnknownTopicOrPartitionException`,
  `InvalidRequestException`, `ClusterAuthorizationException`, `UnsupportedVersionException`,
  `InvalidConfigurationException`, `PolicyViolationException`, `SecurityDisabledException`) —
  `true` for the first four, `false` for the rest, and each wrapped in a `CompletionException`
  as well, because that is how they actually arrive.
- `AdminClientPoolSuite` (unit, `munit-cats-effect` + `TestControl`, with a fake client factory
  injected — no broker; the live-broker test is KAFKA-007's):
  - `oneClientIsCreatedForTenConcurrentCalls`.
  - `aReconnectClassFailureReplacesTheClientExactlyOnce` — two concurrent failures on the same
    generation, one new client.
  - `aRequestLevelFailureDoesNotReplaceTheClient` — the regression guard against Kafbat's
    "invalidate on any error" behaviour.
  - `theFailingCallStillFails` — invalidation does not retry and does not swallow.
  - `invalidationRunsTheClientsFinalizer` — the materialized keystore is deleted.
  - `evictClosesAndForgets`, and a later `run` builds a fresh client.
  - `aPropertyRenderingFailureCachesNothing`.
  - `everyRunIsMeasured` — the `AdminMetrics` fake records one entry per call, with the
    operation label, for both the success and the failure path.
  - `closeIsCalledOnResourceRelease` for every cluster in the pool.

`libs/testkit` gains `FakeAdminMetrics` for the last of these; it counts calls per
`(cluster, operation, outcome)`, which is what lets a suite assert measurement without an
OpenTelemetry SDK.

## Observability

- **Metric** `kui.kafka.admin.duration` (`MetricNames.KafkaAdminDuration`, already declared in
  `libs/observability`), attributes `cluster`, `operation`, `outcome`. `outcome` uses the
  existing `UpstreamOutcome` vocabulary — `success`, `timeout`, `unreachable`, `client_error`,
  `server_error` — so that a Kafka call and an HTTP call can be read on one dashboard. The
  histogram is recorded by `AdminMetrics`, once, in the pool, so no port method can forget it.
- **Span** per admin call, kind `client`, name `kafka.admin.<operation>`, attributes `cluster`
  and `client.id`.
- **Logs**, under `kui.kafka`: INFO on client creation ("admin client <clientId> created for
  cluster <id> at <bootstrap>"), INFO on invalidation with the reason's exception class name
  and the generation, DEBUG on `evict`. The bootstrap list is not a secret and is the first
  thing an operator checks. Never log the property map — KAFKA-002 already logs the redacted
  one at DEBUG.

## Degraded behavior

- **Broker unreachable:** the call fails with the Kafka exception; the pool invalidates and
  logs once. Nothing here retries or backs off — that policy belongs to the caller
  (`SnapshotCell`, KAFKA-010, keeps serving the previous value while refreshes fail), and two
  layers of retry multiply into a timeout nobody planned.
- **Authentication failure:** reconnect-class, so the client is rebuilt on the next call. This
  is deliberate even though credentials rarely become valid on their own: a rotated SCRAM
  password or a renewed keytab must be picked up without restarting KUI, and a rebuilt client
  is the only way that happens.
- **Slow broker:** bounded by `request.timeout.ms` and `default.api.timeout.ms` from
  `AdminTuning` — never unbounded, never a caller-supplied deadline only. This is what makes
  the dashboard's response-time exit criterion achievable at all.
- **Pool shutdown:** `Resource` release closes every client with a bounded close timeout (5
  seconds) so a broker that has stopped answering cannot delay process shutdown indefinitely.
- **A cluster that never becomes reachable** still occupies one map entry and no thread: the
  client is created on first use, so a dead cluster's client is created and immediately fails,
  and the next call recreates it. That is the price of not caching a failure, and it is the
  right one at 30-second refresh intervals.

## Docs to update

`libs/kafka/CLIENT-CHOICE.md` (created here, and it is the deliverable that closes risk R-5).
`docs/operations/observability.md` already lists `kui.kafka.admin.duration` as arriving in M1;
this task makes that true and does not need to edit it. The ADR-006 amendment is CFGOP-008's;
this task states the request in `CLIENT-CHOICE.md` so CFGOP-008 has text to work from.

## Deviations

Recorded by the implementer, in the same commit.

1. **`AdminClientPool.resourceWith` takes the client factory as a parameter**, and `resource` is
   `resourceWith(metrics, defaultFactory, log)`. The spec's own test list asks for "a fake client
   factory injected — no broker", and there was no seam in the published signature to inject one
   through. `Factory[F] = (ClusterConnection, ClientId, ClientProperties) => Resource[F, Admin]` is
   that seam. It is also the reason the suite can assert what properties actually reached the
   client, which is how `theAdminTuningTimeoutsAndTheClientIdReachTheClient` and
   `anOperatorOverrideBeatsTheAdminTuningDefault` exist at all.

2. **`AdminMetrics` lives in its own file and gained `noop`, `otel` and `outcomeOf`.** The spec
   declares the trait beside `AdminClientPool`; splitting it out keeps the pool file about the pool.
   `outcomeOf` is public because it is the mapping from a Kafka throwable to the `UpstreamOutcome`
   vocabulary, and KAFKA-005's error mapper should agree with it rather than restate it.

3. **`FakeAdminMetrics` is in `libs/kafka`'s test sources, not in `libs/testkit`.** The spec puts it
   in the testkit. It cannot go there: `AdminMetrics` is a `libs/kafka` type, `libs/testkit` is on
   the test classpath of modules that layering rule A10 forbids from seeing a Kafka client, and
   putting it in the testkit would make `libs/testkit → libs/kafka` an edge that A10's build test
   will reject when CFGOP-003 lands.

4. **A `KafkaClientConfigurationFailure` wrapper was added.** `run` returns `F[A]` and can only fail
   with a `Throwable`, but the failure it has to report for a bad keystore or a missing login module
   is a `KuiError`. Flattening it to a message would lose the error code that decides the HTTP
   status; the wrapper keeps the original, and KAFKA-005's mapper can unwrap it.

5. **`ConsumerFactory.settings` and `ProducerFactory.settings` return
   `Resource[F, Either[KuiError, Settings]]`**, not `Resource[F, Settings]`. They call
   `ConnectionProperties.resource`, whose result is an `Either` for the reasons KAFKA-003 records;
   the alternative was to raise inside the resource, which would make a misconfigured cluster an
   exception at a point where every other configuration failure in M1 is a value.

6. **All three factories take an optional `log: Option[Logger[F]] = None`**, for the same reason
   KAFKA-003's `ConnectionProperties` does: `libs/kafka` has a `Logger` type available but no
   `LoggerFactory` and no SLF4J binding of its own, and making the logger a required parameter would
   change the signature every caller — including ones other agents are writing right now — has to
   use.

7. **`withAdminSettings` re-applies `connection.overrides` after the tuning defaults.** The spec's
   assembly order puts the operator's `properties` last, and `AdminTuning`'s timeouts are applied
   after the renderer has already finished, so without the re-application a cluster that set
   `request.timeout.ms` by hand would silently lose it. A test pins it.

8. **`snappy-java` and `lz4-java` are declared at runtime scope as the spec says, but the lz4
   coordinate `at.yawk.lz4:lz4-java:1.11.2` from `DEPENDENCY_MATRIX.md` was not verified to
   resolve** — `runMvnDeps` are not fetched by `compile` or by `test`, and nothing in M1 reads a
   compressed topic yet. CFGOP-006, which builds the container images, is the first task that will
   actually fetch them; if the coordinate is wrong it will find out there. Recorded here rather than
   left silent.

9. **`AdminMetrics.otel` has no test.** Asserting on a recorded histogram needs the OpenTelemetry
   SDK and its in-memory exporter, which `libs/observability`'s own suite already carries and this
   module does not. `everyRunIsMeasured` asserts through `FakeAdminMetrics` that the pool measures
   every call on both the success and the failure path, which is the behaviour that can regress; the
   otel4s call itself is four lines with no branching.

10. **A cancellation test was added** (`aCancelledPoolStillClosesEveryClientItOpened`), following the
    gate review's F-07 condition. The pool's `Resource` release is the only thing between a
    cancelled startup and a process that keeps a Kafka network thread alive for its lifetime, and
    `create` is `uncancelable` from the moment the keystore files exist to the moment the entry is
    in the map — which is exactly the window F-07 named.
