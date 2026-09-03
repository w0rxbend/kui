# CLADP-002 — `ClusterAdmin` adapter and the per-cluster client lifecycle

- **ID:** CLADP-002
- **Title:** `ClusterAdmin` adapter and the per-cluster client lifecycle
- **Milestone / Feature:** M1 / CL-001, CL-002, BR-001, BR-002, BR-005, PA-003
- **Owner role:** Domain Architect (Cluster Registry context)
- **Size:** M
- **Dependencies / blocked by:** CLADP-001, KAFKA-008 (broker configs, log dirs, KRaft quorum),
  KAFKA-009 (the `ClusterFeature` probe), **CFGOP-004** (the Testcontainers topology — see
  "Corrected dependency" below)

> **Corrected dependency.** DEVPLAN §6.2 lists CLADP-002's dependencies as CLADP-001, KAFKA-008
> and KAFKA-009, but DEVPLAN §7 requires this task's adapter suite to run against a live broker,
> and the container topology is created by CFGOP-004 in `libs/testkit`. The dependency is real
> and this spec declares it. If CFGOP-004 has not landed when this task is picked up, implement
> everything below, ship the unit-level suite (`ClusterAdminAdapterSuite`, from CLADP-001), mark
> `ClusterAdminLiveSuite` as the remaining work in the implementation report, and **do not**
> invent a second container fixture in this module — a duplicated Kafka container is how a
> project ends up with two broker images and one of them unpinned.

## M1 gate review amendment — `ClusterFeatures` keeps its third set

**F-05, major, fixed.** KAFKA-009 produces a three-valued `ClusterFeatures` — `present`,
`absent`, **`unknown`**, plus `probedAt` — precisely so that a probe which timed out is not
recorded as "this cluster cannot do that". The domain then collapsed it back to a
`Set[ClusterFeature]` at the port, which throws the third set away and reintroduces the exact
bug KAFKA-009's decision exists to prevent: a one-hour cache of a lie.

**The domain gains its own `ClusterFeatures`**, in
`services/cluster/domain/src/kui/cluster/domain/ClusterFeatures.scala`, with the same three sets:

```scala
final case class ClusterFeatures(
    present: Set[ClusterFeature],
    absent:  Set[ClusterFeature],
    unknown: Set[ClusterFeature],
    probedAt: Instant
) {
  def has(f: ClusterFeature): Boolean = present.contains(f)
  def isUnknown(f: ClusterFeature): Boolean = unknown.contains(f)
}
object ClusterFeatures {
  def unprobed(at: Instant): ClusterFeatures   // everything unknown
}
```

with the same invariant KAFKA-009 asserts as a property: `present ++ absent ++ unknown ==
ClusterFeature.All`, always, and the three sets are pairwise disjoint. Assert it here too — the
two enums are defined in two modules (CLDOM-002 decision 2), and a shared invariant checked on
one side only is half a check.

**Signature changes that follow, everywhere in this spec:**

- `ClusterAdmin.capabilities(profile): F[ClusterFeatures]` (not `F[Set[ClusterFeature]]`).
- `ClusterSnapshots.capabilitiesOf(id): F[Option[SnapshotCell[F, ClusterFeatures]]]`.
- `ClusterDescription.features: ClusterFeatures`; `ClusterDescription.has(f)` is
  `features.has(f)`, unchanged in meaning.
- `CapabilityReportUseCase.stateOf` distinguishes the third case: a feature in `unknown` is
  **not** reported as unsupported. It renders as `Degraded` with the probe's reason where the
  screen depends on it, and as "not determined" otherwise — never as `absent`.
- `CLADP-002`'s adapter maps `libs/kafka`'s `ClusterFeatures` onto the domain's field for field,
  by exhaustive match on `ClusterFeature`, preserving all three sets and `probedAt`.

## Goal (user value)

This is the task after which KUI can actually read a Kafka cluster. It completes
`ClusterAdminAdapter` over the whole domain port — brokers, broker configs, log directories, the
KRaft quorum and the capability set — and it gives each configured cluster its own admin client
with a defined life: created on first use, reused, closed and recreated when the connection is
the thing that broke, and thrown away when the cluster's profile changes.

The lifecycle is the part that decides whether a dead cluster stays dead. Kafbat recreates its
admin client on *any* `org.apache.kafka.common.errors.*` failure from `describeCluster`
(`research/kafka/admin-capabilities.md` §0, "Invalidation"), which means one unauthorized topic
read can cost every user a reconnect. KUI splits the two: reconnect-class errors invalidate,
request-level errors do not.

## Scope

1. **The rest of the port.** `ClusterAdminAdapter` gains `brokerConfigs`, `describeLogDirs`,
   `describeQuorum` and `capabilities` — the four methods CLADP-001 left as `???` — each
   delegating to `kui.kafka.admin.ClusterAdmin[F]` and each carrying the span/metric/log wrapper
   CLADP-001 established. After this task `ClusterAdminAdapter` is a complete
   `kui.cluster.domain.ClusterAdmin[F]` with no `???` left in it.
2. **`ClusterAdminClients`** — the per-cluster client registry. A `Resource`-scoped component
   holding `Ref[F, Map[ClusterId, Entry]]` where an entry is the allocated
   `kui.kafka.admin.ClusterAdmin[F]`, the `ProfileVersion` it was built from, and its finalizer.
   Responsibilities:
   - `get(profile)` returns the client for that cluster, creating it if absent **or** if the
     entry's `ProfileVersion` is older than the profile's — a changed profile is a changed
     connection, and reusing the old client would keep talking to the old brokers with the old
     credentials.
   - `invalidate(clusterId)` closes and drops the entry.
   - creation is serialized per cluster id (a `Semaphore` per entry, or a single `MapRef`-guarded
     mutex), so that ten concurrent first requests to one cluster create one client and not ten.
     Ten admin clients against a cluster that is down is ten sets of retry loops.
   - the whole registry is a `Resource` whose release closes every remaining client. A leaked
     admin client keeps a network thread and a metrics registry alive for the life of the JVM.
3. **Invalidation policy.** A helper `ReconnectPolicy.shouldInvalidate(error: KuiError): Boolean`,
   returning `true` for `InfrastructureError.Unreachable`, `InfrastructureError.Timeout` and
   `InfrastructureError.AuthFailed`, and `false` for every `ApplicationError` and every
   `DomainError`. The adapter calls it after every `Left` and invalidates when it says so. This
   is the mapping of `research/kafka/admin-capabilities.md` §0 row "Invalidation"
   (`TimeoutException`, `SaslAuthenticationException`, `SslAuthenticationException`,
   `BrokerNotAvailableException` are reconnect; the rest are request-level) expressed once, at the
   `KuiError` level, so the adapter never re-examines a Kafka exception class that
   `KafkaErrorMapper` (KAFKA-005) has already classified.
4. **Managed-service downgrades.** `brokerConfigs` and `describeLogDirs` must not fail a page
   because a managed cluster refuses the call. Per `research/kafka/admin-capabilities.md` §1:
   - **broker configs**: `InvalidRequestException` (MSK Serverless),
     `UnknownTopicOrPartitionException` (Azure Event Hubs) and `ClusterAuthorizationException`
     arrive from `KafkaErrorMapper` as `ApplicationError.Unsupported` or
     `ApplicationError.Forbidden`, and the adapter **returns that `Left` unchanged**. CLDOM-003 is
     explicit about this and it is the opposite of Kafbat's behaviour: `Left(Unsupported)` lets
     the UI say "this cluster does not expose broker configuration", while `Right(Nil)` shows an
     empty table that looks like a broker with no settings. Do not swallow to empty here. The
     adapter's job is to log it once at WARN with the cluster id and the code, and to make sure
     `ReconnectPolicy` does not treat it as a reconnect.
   - **log dirs**: `UnsupportedVersionException` and `ClusterAuthorizationException` likewise
     become a `Left(ApplicationError.Unsupported | Forbidden)` for the whole call — "this cluster
     has no log-directory information" is a fact, not an empty disk. `TimeoutException` (one slow
     disk stalls the request) is also a `Left`, an `InfrastructureError.Timeout`, because it is
     retryable and the user should be told to retry.
   - per-directory errors inside a successful response (`LogDirDescription.error()`, a
     `KafkaStorageException` meaning an offline directory) are **data**, not failures: they arrive
     as part of `libs/kafka`'s result and are carried into the domain's log-dir model, not
     dropped.
5. **Partial results are preserved.** `describeLogDirs` takes a `NonEmptyList[BrokerId]` and
   returns `Either[KuiError, PartialResult[BrokerId, List[LogDir]]]`. `libs/kafka` produces
   `BatchResult[BrokerId, List[LogDir]]`; the adapter maps it onto the domain's `PartialResult`
   (CLDOM-003, `kui.cluster.domain.PartialResult`, same two fields) preserving both halves, and
   builds it with `PartialResult.from(requested, values, skipped)` so that a broker which is in
   neither map is accounted for rather than silently absent. A `Left` means the whole call failed;
   a `skipped` entry means one broker did not answer while others did, which is the normal shape
   when one broker of five is down. DC-D5 of the research is explicit that silent drops are the
   defect being avoided.
6. **`ClusterAdminLiveSuite`** — `ClusterAdminContract` (CLADP-001) run against the PLAINTEXT
   container from `libs/testkit`, plus the lifecycle assertions listed under "Tests required".

## Non-goals

- **No writes.** `alterBrokerConfig` and `alterReplicaLogDir` are not implemented and not
  declared (DEVPLAN §3: BR-002 is read-only in M1; mutations arrive in M5 with read-only mode and
  audit).
- **No topic sweep.** No `describeTopics`, no `listOffsets`, no partition-count scrape. Decision
  D5 of the plan: the dashboard shows only what `describeCluster`, the broker set and
  `describeLogDirs` produce.
- **No snapshot, no cache, no refresh loop.** `SnapshotCell` and the 30-second cadence are
  CLDOM-005's. This adapter answers one call per call.
- **No retry loop of its own.** `default.api.timeout.ms` inside the client is the retry budget
  (ADR-006, `research/kafka/admin-capabilities.md` §0 "Timeouts"); an adapter-level retry on top
  of it multiplies the two budgets and turns a 30-second failure into a two-minute one.
- **No new container fixture** — see the corrected-dependency note above.

## Design references

- `research/kafka/admin-capabilities.md` §0 (single I/O thread, exception wrapping, null results,
  timeouts, client id, invalidation, batching, partial failure, version detection, managed
  services) and §1 (every row: describe cluster, describe KRaft quorum, list brokers, broker
  configs, log dirs, describe features). **This document outranks `ARCHITECTURE.md` §4.2 wherever
  they differ** (DEVPLAN §4).
- ADR-006 (adapter invariants: chunked batching, per-key `BatchResult` with explicit `Skipped`,
  client invalidation on reconnect-class errors, unique `client.id` per cluster and purpose).
- ADR-030 (minimum broker version 2.8; capability gating rather than version assumptions —
  the adapter never branches on a version number, it branches on a `ClusterFeature`).
- ADR-031 (`KafkaClusterId` recorded from `describeCluster`, paired with `BrokerId` in cache keys).
- ADR-034 (`KuiError` and `ErrorCode`), ADR-039 §6 (only `InfrastructureError` dims a capability —
  which is why a downgraded managed-service call must not produce one).
- ADR-041 rules A9/A10; DEVPLAN §10 decisions D1, D3, D4, D5.

## Files to create

```
services/cluster/infrastructure/src/kui/cluster/infrastructure/ClusterAdminClients.scala
services/cluster/infrastructure/src/kui/cluster/infrastructure/ReconnectPolicy.scala
services/cluster/infrastructure/test/src/kui/cluster/infrastructure/ClusterAdminClientsSuite.scala
services/cluster/infrastructure/test/src/kui/cluster/infrastructure/ReconnectPolicySuite.scala
services/cluster/infrastructure/test/src/kui/cluster/infrastructure/ClusterAdminLiveSuite.scala
```

## Files to change

```
services/cluster/infrastructure/src/kui/cluster/infrastructure/ClusterAdminAdapter.scala
services/cluster/infrastructure/test/src/kui/cluster/infrastructure/ClusterAdminContract.scala
services/cluster/infrastructure/test/src/kui/cluster/infrastructure/StubKafkaClusterAdmin.scala
build.mill      (the test module's `mvnDeps` only: the Testcontainers coordinates)
```

## Public Scala signatures to implement

```scala
package kui.cluster.infrastructure

import cats.effect.kernel.{Async, Resource}
import cats.data.NonEmptyList
import kui.cluster.domain.{ClusterAdmin as ClusterAdminPort, ClusterFeature, ClusterProfile, ConfigEntry, LogDir, PartialResult, QuorumInfo}
import kui.kernel.{BrokerId, ClusterId}
import kui.kernel.error.KuiError

final class ClusterAdminAdapter[F[_]: Async](
    clients: ClusterAdminClients[F],
    telemetry: kui.observability.Telemetry[F],
    logger: org.typelevel.log4cats.StructuredLogger[F]
) extends ClusterAdminPort[F] {

  // describeCluster and detectVersion: unchanged from CLADP-001, except that the client now comes
  // from `clients.get(profile)` instead of a constructor parameter.

  /** `None` on a ZooKeeper cluster or when the call is unauthorized — both mean "there is no
    * quorum information here", which is what the caller needs to know, and neither is a failure.
    */
  def describeQuorum(profile: ClusterProfile): F[Either[KuiError, Option[QuorumInfo]]]

  /** One broker's configuration, sorted by name. `docs` is honoured only when the cluster reports
    * `ClusterFeature.ConfigDocumentation` (Kafka ≥ 2.6); on a cluster without it the call is made
    * without documentation rather than failing. A cluster that refuses the call is a
    * `Left(ApplicationError.Unsupported)`, never `Right(Nil)` — see scope item 4.
    */
  def brokerConfigs(
      profile: ClusterProfile,
      broker: BrokerId,
      docs: Boolean
  ): F[Either[KuiError, List[ConfigEntry]]]

  /** Per broker, with bounded parallelism from `AdminTuning`. A broker whose call failed is a
    * skipped key with a reason, never a missing key.
    */
  def describeLogDirs(
      profile: ClusterProfile,
      brokers: NonEmptyList[BrokerId]
  ): F[Either[KuiError, PartialResult[BrokerId, List[LogDir]]]]

  /** Never fails, and returns a set rather than an `Either`: "the probe failed" and "the feature
    * is absent" are indistinguishable to the caller, and an unreachable cluster yields the empty
    * set (ADR-030, DC-D2).
    */
  def capabilities(profile: ClusterProfile): F[Set[ClusterFeature]]
}
```

```scala
package kui.cluster.infrastructure

/** One admin client per configured cluster, created lazily, reused, and replaced when the
  * connection or the profile changes.
  *
  * Held as a `Resource` so that shutting the service down closes every client. An admin client
  * that outlives its owner keeps a network thread and a metrics registry alive.
  */
trait ClusterAdminClients[F[_]] {

  /** The client for this profile, creating it if there is none or if the cached one was built
    * from an older `ProfileVersion`. Concurrent callers for one cluster create one client.
    */
  def get(profile: ClusterProfile): F[kui.kafka.admin.ClusterAdmin[F]]

  /** Closes and forgets the client for this cluster. The next `get` builds a new one. */
  def invalidate(clusterId: ClusterId): F[Unit]

  /** For tests and for the readiness endpoint: how many clients are currently open. */
  def openClients: F[Int]
}

object ClusterAdminClients {
  def resource[F[_]: Async](
      factory: kui.kafka.KafkaClientFactory[F],
      logger: org.typelevel.log4cats.StructuredLogger[F]
  ): Resource[F, ClusterAdminClients[F]]
}
```

```scala
package kui.cluster.infrastructure

import kui.kernel.error.{ApplicationError, DomainError, InfrastructureError, KuiError}

/** Which failures mean "the connection is broken" and which mean "that request was refused".
  *
  * Kafbat recreates its admin client on any Kafka exception from `describeCluster`
  * (`research/kafka/admin-capabilities.md` §0). KUI does not: an authorization failure on one
  * resource says nothing about the socket, and reconnecting on it makes an unauthorized user
  * into a denial of service.
  */
object ReconnectPolicy {
  def shouldInvalidate(error: KuiError): Boolean
}
```

`KafkaClientFactory` is KAFKA-004's; follow its actual name and signature, and if it differs,
change only the `ClusterAdminClients.resource` constructor line. The `kui.cluster.domain.*` names
above are taken from CLDOM-003's committed spec (`ClusterAdmin`, `PartialResult`, `ConfigEntry`,
`LogDir`, `QuorumInfo`, `ClusterFeature`); read the committed domain files and follow them if they
moved.

## Library coordinates

No new main-scope coordinate. The test module gains, from `DEPENDENCY_MATRIX.md`:

- `com.dimafeng::testcontainers-scala-munit::${Versions.testcontainers}` (0.44.1)
- `com.dimafeng::testcontainers-scala-kafka::${Versions.testcontainers}` (0.44.1)

The broker image itself is pinned by `libs/testkit` (CFGOP-004); this module must not name an
image tag. `moduleDeps` of the test module gains `libs.testkit.jvm` (already present from
CLADP-001) — the Kafka topology is exposed from there.

## Acceptance criteria

```
$ ./mill services.cluster.infrastructure.test
Test run kui.cluster.infrastructure.ClusterAdminAdapterSuite finished: 0 failed, 0 ignored, 5 total
Test run kui.cluster.infrastructure.ClusterAdminClientsSuite finished: 0 failed, 0 ignored, 6 total
Test run kui.cluster.infrastructure.ReconnectPolicySuite finished: 0 failed, 0 ignored, 4 total
Test run kui.cluster.infrastructure.ClusterAdminLiveSuite finished: 0 failed, 0 ignored, 9 total
SUCCESS

$ ./mill checkArchitecture
checkArchitecture: 36 modules, no layering violations
```

The live suite needs a Docker daemon. With none available it must **fail to start with a clear
message**, not silently pass zero tests — use `libs/testkit`'s container fixture, which owns that
behaviour, and do not add an `assume`-style skip here.

Behavioural acceptance, asserted by the named tests below rather than by hand:

- against a container that is stopped mid-suite, the next `describeCluster` returns
  `Left(InfrastructureError.*)` inside the configured request timeout, and `openClients` drops to
  zero;
- after the container is restarted, the next call succeeds without any code having called
  `invalidate` explicitly.

## Tests required

`ClusterAdminLiveSuite extends ClusterAdminContract` — same contract file as CLADP-001, a live
broker instead of a stub, plus:

- `brokerConfigsReturnsTheBrokersConfigurationSortedByName` — a known key (`log.retention.hours`) is present,
  and a sensitive value is `None`/redacted rather than a plaintext string
  (`research/kafka/admin-capabilities.md` §1: "Sensitive values are `null`").
- `logDirsReportPerDirectorySizes` — at least one directory, with a path and a size.
- `logDirsForAnUnknownBrokerIsASkippedKeyNotAMissingOne` — built through
  `PartialResult.from(requested, …)`, so the requested-but-absent broker cannot vanish.
- `quorumIsSomeOnAKRaftBrokerAndNoneIsNotAFailure`.
- `capabilitiesContainsLogDirsAndDoesNotThrowOnAnUnprobeableFeature`.
- `describeClusterRecordsTheKafkaClusterId` — the `KafkaClusterId` of ADR-031 is carried into the
  description.

`ClusterAdminClientsSuite` (no container; a stub factory that counts allocations and releases):

- `oneClientIsCreatedPerCluster`
- `tenConcurrentCallsForOneClusterCreateOneClient` — `parTraverse` of ten `get`s; the factory
  recorded exactly one allocation. This is the test that stops a dead cluster from being hammered
  by a page refresh.
- `aNewerProfileVersionReplacesTheClient` — and the old one's finalizer ran.
- `invalidateClosesTheClientAndTheNextGetCreatesANewOne`
- `releasingTheResourceClosesEveryOpenClient`
- `aFailedClientCreationLeavesNoEntryBehind` — the factory raises; `openClients` is 0 and a
  second `get` tries again rather than returning a poisoned entry.

`ReconnectPolicySuite`:

- `unreachableTimeoutAndAuthFailedInvalidate`
- `applicationErrorsDoNotInvalidate`
- `domainErrorsDoNotInvalidate`
- `thePolicyIsTotal` — a ScalaCheck property over a generator of every `KuiError` case, asserting
  the function returns without raising for all of them. Cheap now; it is the test that fails when
  someone adds a `KuiError` case and forgets this file.

`ClusterAdminAdapterSuite` gains, still with the stub:

- `aReconnectClassFailureInvalidatesTheClient`
- `aRequestLevelFailureDoesNotInvalidateTheClient`
- `unsupportedBrokerConfigsStayALeftAndAreNotSwallowedToAnEmptyList` — the assertion that KUI
  does not repeat Kafbat's swallow-to-empty defect
- `aTimeoutOnLogDirsStaysALeft`
- `capabilitiesNeverFails`

## Observability

Everything CLADP-001 established, extended to the new methods, plus two lifecycle signals — the
client registry is invisible otherwise, and "why is this cluster slow" is usually "it reconnects
on every request":

- **Log, INFO, once per creation**: `kafka admin client created` with `kui.cluster.id` and the
  `client.id` the factory assigned (`kui-admin-<cluster>-<seq>`, ADR-006). Never the profile,
  never the rendered properties, never the bootstrap string if it can carry credentials.
- **Log, WARN, once per invalidation**: `kafka admin client invalidated` with the cluster id and
  the `ErrorCode.wire` that caused it.
- **Log, WARN, once per downgrade**: `broker configs unavailable on this cluster` /
  `log dirs unavailable on this cluster`, with the cluster id and the error code. Once per
  *occurrence* is acceptable here; these calls happen at the snapshot cadence (30 s), not per
  request.
- **Metric**: `MetricNames.KafkaAdminDuration` with `outcome` = `ok`, `downgraded`, or the
  `ErrorCode.wire`. `downgraded` is a distinct outcome on purpose: an operator must be able to
  see that a managed cluster is silently answering nothing.
- **Span**: `kui.cluster.admin.<operation>`, with `kui.cluster.id`, `kui.kafka.operation` and, for
  `describeLogDirs`, `kui.kafka.broker.count`.

## Degraded behavior

- A cluster that cannot be reached produces `Left(InfrastructureError.Unreachable |
  Timeout | AuthFailed)` per call, promptly, bounded by the client's own
  `request.timeout.ms`/`default.api.timeout.ms`. It never blocks, never retries on top of the
  client's retries, and never holds a lock that another cluster's call needs — the per-cluster
  creation mutex is per cluster id exactly so that a dead cluster cannot serialize a healthy one.
  This is the mechanism behind the milestone's "response time is bounded by the per-service
  timeout, not by the dead cluster" exit criterion.
- A cluster that answers but refuses a call (managed services) degrades **that section only**:
  the call returns `Left(ApplicationError.Unsupported | Forbidden)`, the section renders as "not
  available on this cluster", and per ADR-039 §6 it does **not** dim a capability — only
  `InfrastructureError` does. This is why the distinction between the two error families is worth
  the care: it is the difference between one panel saying "unsupported" and the whole sidebar
  greying out.
- The registry itself has no fallback. If it cannot build a client, `get` fails with the mapped
  error and leaves no entry, so the next call is a clean attempt.

## Docs to update

None in this task. The managed-service downgrade table and the invalidation policy are described
for operators by CFGOP-008 in `docs/operations/`; record the exact behaviour in the implementation
report so that task has the evidence.

## Cancellation and shutdown (added at the M1 gate review, F-07)

The M0 review found cancellation systematically unconsidered across the milestone. This task
owns the per-cluster admin client pool, so it owns the answer here. State it in the spec's own words in the
Implementation Report, and ship the tests below.

- Client creation is `uncancelable` between `Admin.create` succeeding and the entry being
  recorded in the `Ref`. A cancellation in that window would leak a live `Admin` with its
  network thread and nobody holding its finalizer.
- The per-cluster creation `Semaphore` is released on cancellation (`permit` as a `Resource`,
  not `acquire`/`release` around a body), or one cancelled first caller deadlocks every later
  one for that cluster.
- `invalidate` and `evict` run each entry's finalizer even when the caller is cancelled.
- **Test:** cancel a `run` during client creation and assert (a) no `Admin` is left unclosed,
  (b) the semaphore is free, (c) the next `run` for the same cluster creates a client and
  succeeds.

---

## Deviations

Recorded by the implementing agent, 2026-09-04. Commit `5b1aaf4`.

1. **`ClusterAdminClients` is a version registry, not a client registry.** KAFKA-004 shipped
   `kui.kafka.AdminClientPool`, which already is everything scope item 2 asks for: one client per
   cluster, created behind a per-cluster `Semaphore`, `uncancelable` from `Admin.create` to the
   entry being in the `Ref`, generation-guarded invalidation so two simultaneous failures cost one
   reconnect, and `closeAll` on release. It also invalidates automatically on a reconnect-class
   `Throwable` inside `run`. Building a second registry here would be two pools racing to open
   clients against the same brokers — the finding this spec's own "do not invent a second
   container fixture" note exists to prevent, one layer down.

   What the pool cannot know is that a `ClusterProfile` has a `ProfileVersion`, and that an edited
   profile is a different connection wearing the same cluster id. `ClusterAdminClients` owns
   exactly that: `connectionFor(profile)` evicts the pooled client when the profile version has
   moved, `invalidate(id)` delegates, and `openClients` counts registered clusters. Both effects
   are `uncancelable` around the `Ref` update and the eviction, so the registry cannot come to
   believe a client matches a profile it does not.

   Consequently `ClusterAdminClients.resource` takes an `AdminClientPool[F]`, not a
   `KafkaClientFactory[F]` (which does not exist — see the M1 gate review's F-04 for the same
   phantom name in another lane), and `get(profile): F[ClusterAdmin[F]]` is
   `connectionFor(profile): F[ClusterConnection]`, because `libs/kafka`'s `ClusterAdmin` is one
   pool-backed instance rather than one per cluster.

2. **Test names follow the design.** `ClusterAdminClientsSuite` has eight cases, not the spec's
   six: `theFirstCallRegistersTheClusterAndEvictsNothing`,
   `tenConcurrentCallsForOneClusterEvictNothing` (the spec's
   `tenConcurrentCallsForOneClusterCreateOneClient`, which is `AdminClientPoolSuite`'s to assert),
   `aNewerProfileVersionEvictsTheClient` (the spec's `aNewerProfileVersionReplacesTheClient`),
   `anOlderOrEqualProfileVersionDoesNotEvict`, `everyClusterIsTrackedSeparately`,
   `invalidateAsksThePoolToRebuildAndKeepsTheRegistration`,
   `releasingTheResourceEvictsEveryRegisteredCluster`, and
   `aCancelledConnectionForLeavesTheRegistryAndThePoolInStep` — the F-07 cancellation test.
   `aFailedClientCreationLeavesNoEntryBehind` is `libs/kafka`'s, where creation happens.

3. **`capabilities` returns the domain's three-set `ClusterFeatures`**, per this spec's own gate
   amendment, and the port signature in the "Public Scala signatures" block above (which still
   says `F[Set[ClusterFeature]]`) is superseded by it. `KafkaToDomain.features` maps through
   `ClusterFeatures.of`, so the partition invariant holds by construction — including for
   `ClusterFeature.BrokerConfigs`, which the domain models and `libs/kafka` never probes, and
   which therefore lands in `unknown` rather than in `absent`.

4. **`describeLogDirs` takes `NonEmptyList` at the port and `Set` at `libs/kafka`.** The
   conversion is in the adapter and the requested set is what `PartialResult.from` is given, so a
   broker in neither map is filled in as a failure rather than vanishing.

5. **A log directory's per-directory error becomes `LogDirError.Offline`.** `libs/kafka`'s
   `SkipReason` carries an `ErrorCode` and not the Java exception class, and the domain's
   `LogDirError.Other` accepts only a class name — a wire code passed through it renders
   "unknown", which says strictly less than `Offline` does. Per
   `research/kafka/admin-capabilities.md` §1 the only error a broker reports for a directory is a
   storage failure, so `Offline` is the honest rendering. If a future task wants the distinction,
   `libs/kafka`'s `SkipReason.Failed` needs to carry the class name.

6. **`ClusterAdminLiveSuite` is not written.** CFGOP-004 has not landed: `libs/testkit` has no
   `KafkaFixture`/`KafkaTopology`, and this spec's own corrected-dependency note forbids inventing
   a second container fixture here. The mitigation is deliberate and was designed for: every
   assertion the live suite would make about *mapping* is already made by `KafkaToDomainSuite`
   against the same values a broker produces, and every assertion it would make about the *port*
   is in `ClusterAdminContract`, which is written so that the live subclass adds one file and
   changes nothing else.

   **Owed, exactly:** `ClusterAdminLiveSuite extends ClusterAdminContract` with
   `port = ClusterAdminClients.resource(pool, logger).flatMap(adapter over KafkaClusterAdmin)` and
   `profile` addressing `KafkaFixture(KafkaTopology.Plaintext)`, plus
   `brokerConfigsReturnsTheBrokersConfigurationSortedByName`, `logDirsReportPerDirectorySizes`,
   `quorumIsSomeOnAKRaftBrokerAndNoneIsNotAFailure` (asserted here as a contract case that a live
   broker will strengthen), `capabilitiesContainsLogDirsAndDoesNotThrowOnAnUnprobeableFeature`,
   `describeClusterRecordsTheKafkaClusterId`, and the two behavioural acceptance criteria — a
   container stopped mid-suite, and the same container restarted.
