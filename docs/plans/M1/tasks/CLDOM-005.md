# CLDOM-005 — Topology snapshot use case: refresh, staleness, forced refresh

- **ID:** CLDOM-005
- **Title:** Topology snapshot use case: refresh, staleness, forced refresh
- **Milestone / Feature:** M1 / CL-003, CL-005, CL-007, CL-009, BR-001, OT-001, OT-008
- **Owner role:** Domain Architect (Cluster Registry context)
- **Size:** L
- **Dependencies / blocked by:** CLDOM-004, KAFKA-010

## Goal (user value)

The dashboard is fast and a dead cluster does not slow it down, because nothing on the request
path talks to a broker. One background loop per cluster keeps a snapshot fresh every thirty
seconds; a read returns whatever the snapshot holds, with an honest label saying how old it is and
whether the last attempt failed. This is the use case behind the milestone's most visible exit
criterion — *two rows populate, the third shows `Unavailable: <reason>` and remains clickable, and
the response time is bounded by the per-service timeout, not by the dead cluster.*

## Scope

1. `ClusterTopologyUseCase[F]` — read a cluster's topology snapshot; read every cluster's at once;
   force a refresh.
2. `TopologyView` — what a read returns: the topology (possibly absent), its freshness, and why it
   is not fresher.
3. `SnapshotFreshness` — the staleness verdict the API layer maps onto `Section.Fresh` /
   `Section.Stale` / `Section.Unavailable`.
4. `ClusterSnapshots[F]` — one `SnapshotCell` per cluster, created and destroyed as the registry's
   cluster set changes, all under one `Supervisor`.
5. The refresh function itself: the ordered set of `ClusterAdmin` calls that produce a
   `ClusterTopology`, and what each failure does.
6. `docs/domain/cluster.md` gains a "Snapshot and staleness" section, and
   `ARCHITECTURE.md` §9's cluster row is confirmed or corrected.

## Non-goals

- **No polling from the browser and no configurable cadence below 30 s.** DEVPLAN §10 D10: 30
  seconds server-side, the browser does not poll, `scrapedAt` is visible and an explicit refresh
  button is the user's control. The interval is a configuration value
  (`kui.cluster.refreshInterval`, default 30 s, ADR-027) and CFGOP-002 owns the Ciris slice; this
  task takes a `FiniteDuration` argument.
- **No topic sweep.** No `listTopics`, no `describeTopics`, ever, in this loop (DEVPLAN §3, §10
  D5, risk R-11). `ClusterTopology.partitions`, `.topics` and `BrokerLoad.leaders` stay `None`
  (CLDOM-002).
- **No broker-detail calls in the loop.** Per-broker configuration is fetched on demand by
  CLDOM-006, not scraped every 30 seconds for every broker of every cluster.
- **No caching primitive of its own.** `SnapshotCell` comes from `libs/cache` (KAFKA-010). If it
  does not do something this task needs, that is a KAFKA-010 conversation, not a second cell here.
- **No HTTP, no `Section`, no DTO.** `Section` is a `libs/contracts-core` type and rule A3 forbids
  `application` seeing it; this task produces `SnapshotFreshness` and CLAPI-004 maps it.

## What KAFKA-010 must provide (the contract this task compiles against)

```scala
package kui.cache

import java.time.Instant
import scala.concurrent.duration.FiniteDuration
import cats.effect.kernel.{Resource, Temporal}
import cats.effect.std.Supervisor
import kui.kernel.error.KuiError

enum SnapshotStatus:
  case Initializing
  case Online
  case Offline(lastError: KuiError, since: Instant)

final case class Snapshot[A](value: Option[A], status: SnapshotStatus, scrapedAt: Option[Instant])

trait SnapshotCell[F[_], A]:
  /** Never fails, never blocks on the upstream. */
  def get: F[Snapshot[A]]
  /** Runs one refresh now. Concurrent calls are deduplicated onto the in-flight one, so this is
    * idempotent under concurrency; completes when that refresh completes. */
  def refreshNow: F[Unit]

object SnapshotCell:
  def resource[F[_]: Temporal, A](
      name: String,
      interval: FiniteDuration,
      fetch: F[Either[KuiError, A]],
      supervisor: Supervisor[F]
  ): Resource[F, SnapshotCell[F, A]]
```

Three properties this task depends on and KAFKA-010's suite must assert, to be confirmed in
review of KAFKA-010: (a) a failed refresh leaves the previous `value` in place and moves `status`
to `Offline` — it never clears the value; (b) `scrapedAt` is the instant of the last *successful*
refresh, not of the last attempt, because a timestamp that updates on failure would tell a user
the data is fresh when it is not; (c) `refreshNow` deduplicates, so twenty users pressing the
refresh button produce one admin call.

## The refresh function — the exact call sequence

`refreshOne(profile)` produces `Either[KuiError, ClusterTopology]`. The order matters and the
partial-failure policy matters more, because most of the M1 degraded criteria are decided here.

```
1. describeCluster(profile)                     — REQUIRED. Left ⇒ the whole refresh fails.
2. capabilities(profile)                        — total, returns a Set (never fails)
3. detectVersion(profile)                       — OPTIONAL. Left or Right(None) ⇒ version = None
4. if features contains KRaftQuorum:
     describeQuorum(profile)                    — OPTIONAL. Left ⇒ quorum = None
   else                                         — quorum = None, no call made
5. if features contains LogDirs:
     describeLogDirs(profile, description.brokers.map(_.id))
                                                — OPTIONAL. Left ⇒ load = empty
                                                — Right(partial) ⇒ skipped brokers get no BrokerLoad
   else                                         — load = empty, no call made
6. BrokerLoad.withSkew(...) over whatever step 5 produced
```

**Only step 1 is required.** A cluster that answers `describeCluster` is reachable, and the page
must render: a broker list with no disk figures is far more useful than an `Unavailable` panel,
and it is exactly what a managed service (`admin-capabilities.md` §0, "Managed services") or a
cluster where KUI lacks `DESCRIBE_CONFIGS` looks like. Steps 3–5 each degrade to `None`/empty and
each one's failure is logged once at DEBUG with the error code — not WARN, because on a managed
service these fire every thirty seconds forever and a WARN that always fires is noise that trains
an operator to filter the log.

Steps 3, 4 and 5 run **in parallel** (`parTupled`), bounded by nothing further: they are three
calls to one already-bounded admin client. Steps 4 and 5 are skipped entirely when the capability
set says the cluster does not support them — this is what ADR-030's "probe, never assume" buys:
no per-refresh `UnsupportedVersionException` against a ZooKeeper cluster.

`capabilities` is *not* refreshed every 30 seconds. `ARCHITECTURE.md` §9 says capabilities refresh
hourly and on reconnect. Implementation: `ClusterSnapshots` holds a second, longer-interval
`SnapshotCell[F, Set[ClusterFeature]]` per cluster (interval `capabilityInterval`, default 1 hour)
and the topology refresh reads it with `get` rather than probing. A cluster whose capability cell
is still `Initializing` on the very first topology refresh gets `Set.empty`, which means steps 4
and 5 are skipped on that first pass and filled in on the next one — thirty seconds later. That is
a deliberate trade: the alternative is a first refresh that waits on a probe of six features
against a cluster that may be down.

**Reconnect re-probing:** when a topology refresh transitions the cell from `Offline` to `Online`,
the capability cell's `refreshNow` is triggered. That is the "and on reconnect" half of
`ARCHITECTURE.md` §9, and it matters because the usual reason a cluster was offline is that it was
being upgraded.

## The staleness contract

| Cell state | `SnapshotFreshness` | What the UI shows (ADR-032, CLUI-003) |
| --- | --- | --- |
| `Initializing`, no value yet | `Loading` | a skeleton row; the cluster is clickable |
| `Online`, value present | `Fresh(scrapedAt)` | the data, with "updated <n>s ago" |
| `Offline(err, since)`, value present | `Stale(scrapedAt, reason, since)` | the data, greyed, with the timestamp and the reason — the fault-isolation criterion |
| `Offline(err, since)`, **no** value | `Unavailable(reason, since)` | `Unavailable: <reason>`, row still clickable — the dashboard criterion |

`reason` is `KuiError.message` — display text, already free of hosts, bodies and credentials by
the construction of `KuiError` (ADR-034). Never the exception's `getMessage`.

There is no TTL beyond the refresh interval and there is no eviction: a snapshot is at most one
interval old when the cluster is up, and arbitrarily old when it is down — which is precisely why
`scrapedAt` is on every response instead of the value being discarded. Discarding it would turn
the fault-isolation criterion ("the other clusters' cached rows, greyed and timestamped, stay
usable") into an empty page.

## Design references

- ADR-027 — per-context snapshot; `status`, `scrapedAt`, atomic replacement; refresh under a
  `Supervisor`; a manual refresh endpoint per resource family.
- ADR-016 — every cache declares TTL, invalidation, bound, metrics and a staleness contract, and
  records them in `ARCHITECTURE.md` §9.
- ADR-030 — features are probed and gated, never inferred from a version.
- ADR-037 — the per-upstream timeout that bounds the gateway's dashboard call. This task's
  contribution to that bound is that a read never calls a broker at all.
- ADR-039 §6 / DEVPLAN §10 D4 — an unreachable *managed* cluster is a stale or unavailable
  section inside a 200, never an unavailable capability.
- DEVPLAN §10 D5 (no topic sweep), D10 (30 s, no browser polling).
- `research/kafka/admin-capabilities.md` §0 (single I/O thread, timeouts, managed services) and
  §1 (per-call errors).
- `research/kafbat/ui-analysis.md` "Dashboard", "Brokers" — including the recorded defect that
  Kafbat's broker page shows a full-page loader on every refetch, which is what a
  `Stale`-serving read exists to avoid.

## Files to create or change

```
services/cluster/application/src/kui/cluster/application/TopologyView.scala          (new)
services/cluster/application/src/kui/cluster/application/ClusterSnapshots.scala      (new)
services/cluster/application/src/kui/cluster/application/ClusterTopologyUseCase.scala (new)
services/cluster/application/test/src/kui/cluster/application/ClusterTopologyUseCaseSuite.scala (new)
services/cluster/application/test/src/kui/cluster/application/ClusterSnapshotsSuite.scala (new)
services/cluster/application/test/src/kui/cluster/application/fakes/FakeClusterAdmin.scala (new)
build.mill                                                                            (changed)
docs/domain/cluster.md                                                                (changed)
ARCHITECTURE.md                                                                       (§9 cluster row, if it differs)
```

`build.mill`: `services.cluster.application.moduleDeps += libs.cache` — the one edge DEVPLAN §5.2
adds to this module, and the only `build.mill` change this task makes.

## Public Scala signatures to implement

```scala
package kui.cluster.application

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

import cats.effect.kernel.{Resource, Temporal}
import cats.effect.std.Supervisor
import fs2.Stream
import org.typelevel.log4cats.StructuredLogger

import kui.cache.SnapshotCell
import kui.cluster.domain.*
import kui.kernel.ClusterId
import kui.kernel.error.KuiError

/** How old the data in a `TopologyView` is, and why it is not newer. */
enum SnapshotFreshness:
  /** No refresh has completed yet. Not an error: it is what the first two seconds of a process
    * look like, and rendering it as a failure is ADR-032's "Starting is not Unavailable". */
  case Loading
  case Fresh(scrapedAt: Instant)
  /** Data is present and the last refresh failed. `since` is when the failures started, and it is
    * sticky across a changing reason (ADR-039 §3): a user asks "how long has this been broken",
    * not "how long has it been broken in this particular way". */
  case Stale(scrapedAt: Instant, reason: String, since: Instant)
  /** Nothing has ever been fetched successfully and the last attempt failed. */
  case Unavailable(reason: String, since: Instant)

object SnapshotFreshness:
  given CanEqual[SnapshotFreshness, SnapshotFreshness] = CanEqual.derived

/** One cluster's topology as a reader sees it: what is known, and how well it is known.
  *
  * `cluster` is a `ClusterRef` and not a `ClusterProfile`, so a view can be logged, and so that
  * the API layer cannot reach a secret from a value it is about to serialise (CLDOM-002).
  */
final case class TopologyView(
    cluster: ClusterRef,
    topology: Option[ClusterTopology],
    freshness: SnapshotFreshness
):
  def isRenderable: Boolean = topology.isDefined

/** The per-cluster snapshot cells, kept in step with the registry.
  *
  * Owns a `Supervisor` (ADR-027: every refresh loop runs under one and is cancellable) and one
  * pair of cells per cluster — topology and capabilities. Subscribes to `ClusterRegistry.changes`
  * and, on each change, starts cells for clusters that appeared and cancels and drops cells for
  * clusters that disappeared. A profile whose *contents* changed has its cells replaced, because
  * a rotated password must not keep being used by a loop that captured the old profile.
  */
trait ClusterSnapshots[F[_]]:
  def topologyOf(id: ClusterId): F[Option[SnapshotCell[F, ClusterTopology]]]
  def capabilitiesOf(id: ClusterId): F[Option[SnapshotCell[F, Set[ClusterFeature]]]]
  /** Every cluster currently held, in registry order. */
  def all: F[List[(ClusterRef, SnapshotCell[F, ClusterTopology])]]

object ClusterSnapshots:
  def resource[F[_]: Temporal](
      registry: ClusterRegistry[F],
      admin: ClusterAdmin[F],
      refreshInterval: FiniteDuration,      // default 30 s (ADR-027)
      capabilityInterval: FiniteDuration,   // default 1 h  (ARCHITECTURE.md §9)
      logger: StructuredLogger[F]
  ): Resource[F, ClusterSnapshots[F]]

  /** The refresh function of the task's "exact call sequence" section, exposed so that it is
    * testable without a cell around it. */
  def refreshOne[F[_]: Temporal](
      admin: ClusterAdmin[F],
      profile: ClusterProfile,
      features: Set[ClusterFeature],
      logger: StructuredLogger[F]
  ): F[Either[KuiError, ClusterTopology]]

/** Reading cluster topology, and asking for it to be re-read.
  *
  * Every method is a memory read plus, at most, a `Ref` update. Nothing here calls a broker, which
  * is what makes the dashboard's response time a function of the gateway's fan-out and not of the
  * slowest configured cluster.
  */
trait ClusterTopologyUseCase[F[_]]:
  /** One cluster. `Left(ApplicationError.NotFound(..., ErrorCode.ClusterNotFound))` for an id that
    * is not configured — a 404, not a 500 (ADR-039 §6). A configured but unreachable cluster is a
    * `Right` whose `freshness` is `Unavailable`. That distinction is the milestone's dashboard
    * criterion in one line. */
  def view(id: ClusterId): F[Either[KuiError, TopologyView]]

  /** Every configured cluster, in registry order, each with its own freshness. Never fails and
    * never partially fails: an unreachable cluster contributes an `Unavailable` view. */
  def viewAll: F[List[TopologyView]]

  /** Triggers a refresh and returns as soon as it has been *requested*, not when it completes —
    * the endpoint answers 202 (CLUI-008). Idempotent under concurrency: twenty presses produce
    * one admin call, because `SnapshotCell.refreshNow` deduplicates. `Left(NotFound)` for an
    * unknown id. */
  def forceRefresh(id: ClusterId): F[Either[KuiError, Unit]]

object ClusterTopologyUseCase:
  val Operation: String = "kui.cluster.topology"

  def make[F[_]: Temporal](
      registry: ClusterRegistry[F],
      snapshots: ClusterSnapshots[F],
      logger: StructuredLogger[F]
  ): ClusterTopologyUseCase[F]

  /** Pure: maps a cell's `Snapshot` onto the staleness table above. Public so the table is
    * asserted directly rather than through four effectful scenarios. */
  def freshnessOf[A](snapshot: kui.cache.Snapshot[A]): SnapshotFreshness
```

### `forceRefresh` and the 202

`forceRefresh` returns after *starting* the refresh. It must not await completion: a forced
refresh against a dead cluster would otherwise block for the full admin timeout and the button
would hang, which is the failure the milestone is built to avoid. Implementation: start
`cell.refreshNow` on the `Supervisor` and return. The browser re-reads the snapshot and sees the
new `scrapedAt` when it lands (CLUI-008).

## Library coordinates

```
services.cluster.application
  moduleDeps  = Seq(domain, libs.cache)          // libs.cache is new in this task
  mvnDeps     = cats-core 2.13.0, cats-effect 3.7.1, fs2-core 3.13.0,
                log4cats-core 2.8.0, otel4s-core 1.1.0     (unchanged)

services.cluster.application.test
  moduleDeps += services.cluster.domain.test      (fixtures; added in CLDOM-004)
  munit 1.3.6, munit-scalacheck 1.3.1, scalacheck 1.20.0,
  munit-cats-effect 2.2.0, cats-effect-testkit 3.7.1 (TestControl)
```

`libs.cache` depends on `libs.kernel.jvm`, cats-effect and fs2 (DEVPLAN §5.1) and carries no Kafka
dependency, so rule A10 is satisfied and rule A3 is unaffected — `libs.cache` is not `libs.http`,
`libs.contractsCore`, tapir, circe or an `infrastructure` module.

## Acceptance criteria

```
$ ./mill services.cluster.application.test
Test run kui.cluster.application.ClusterTopologyUseCaseSuite finished: 0 failed, 0 ignored, 12 total
Test run kui.cluster.application.ClusterSnapshotsSuite finished: 0 failed, 0 ignored, 10 total
SUCCESS

$ ./mill checkArchitecture
checkArchitecture: <n> modules, no layering violations
```

Two greps for the Implementation Report, both enforcing DEVPLAN §3:

```
$ grep -rn "describeTopics\|listTopics\|listOffsets" services/cluster/application   # (no output)
$ grep -rn "org.apache.kafka" services/cluster/application                          # (no output)
```

And one timing assertion, which is the point of the whole task and belongs in the report:
`ClusterTopologyUseCaseSuite.viewAllReturnsWithoutCallingTheAdminPort` runs under `TestControl`
with a `FakeClusterAdmin` whose every method sleeps for one hour, and asserts `viewAll` completes
at virtual time zero.

## Tests required

`ClusterSnapshotsSuite` (MUnit + `munit-cats-effect` + `TestControl` + `FakeClusterAdmin` +
`FakeClusterConfigStore` from CLDOM-004):

1. `refreshOneNeedsOnlyDescribeCluster` — an admin that answers `describeCluster` and fails
   everything else yields a `Right` topology with `version = None`, `quorum = None`, `load` empty.
   *The managed-service case.*
2. `refreshOneFailsWhenDescribeClusterFails` — `Left`, and the error is passed through unchanged
   (not wrapped, not re-classified: the adapter already classified it).
3. `quorumIsNotCalledWithoutTheKRaftQuorumFeature` — assert on `FakeClusterAdmin.calls` that
   `describeQuorum` never appears. This is ADR-030's "probe, never assume" as an assertion.
4. `logDirsAreNotCalledWithoutTheLogDirsFeature`.
5. `aSkippedBrokerGetsNoBrokerLoadAndTheOthersDo` — `describeLogDirs` returns a `PartialResult`
   skipping broker 2 of 3; `load` has two entries and skew is computed over those two.
6. `optionalFailuresAreLoggedAtDebugNotWarn` — assert the `FakeStructuredLogger` recorded DEBUG.
   Explicitly asserted because the natural instinct is WARN and it would make a healthy managed
   cluster produce two warnings every thirty seconds.
7. `theLoopRefreshesOnTheInterval` — under `TestControl`, advance 90 s, assert exactly three
   refreshes (t=0, 30, 60) — asserting the count, not "at least one", so a duplicated loop fails.
8. `aClusterAddedToTheRegistryGetsACell` — change the registry, assert `topologyOf` becomes
   `Some`.
9. `aClusterRemovedFromTheRegistryHasItsLoopCancelled` — remove it, advance 60 s, assert
   `FakeClusterAdmin.calls` for that cluster stopped growing. This is the leak test: a cell whose
   cluster was deleted but whose fiber survives keeps authenticating to a cluster the operator
   removed.
10. `aChangedProfileReplacesTheCell` — rotate the password in the registry; assert the next
    refresh used the **new** profile (the fake records the profile it was called with).

`ClusterTopologyUseCaseSuite`:

1–4. `freshnessOf` table: the four rows of the staleness table, asserted on the pure function.
5. `staleKeepsThePreviousValue` — refresh once successfully, then fail; `view` is `Right` with
   `topology.isDefined` and `freshness = Stale`. *The fault-isolation criterion.*
6. `scrapedAtDoesNotMoveOnAFailedRefresh` — the timestamp after the failure equals the one before.
7. `unavailableWhenNothingEverSucceeded` — `topology = None`, `freshness = Unavailable(reason,_)`,
   and `reason` is the `KuiError.message`.
8. `sinceIsStickyAcrossAChangingReason` — fail with `Timeout`, then with `Unreachable`; `since` is
   unchanged (ADR-039 §3).
9. `viewOfAnUnknownIdIsNotFound` — `ApplicationError`, `ErrorCode.ClusterNotFound`.
10. `viewAllReturnsWithoutCallingTheAdminPort` — the virtual-time-zero assertion above.
11. `viewAllMixesFreshAndUnavailableRows` — three clusters, one never reachable: two `Fresh`, one
    `Unavailable`, all three present in the result and in registry order. *The dashboard exit
    criterion, at the use-case layer.*
12. `forceRefreshIsIdempotentUnderConcurrency` — `List.fill(20)(forceRefresh(id)).parSequence`
    under `TestControl`; exactly one `describeCluster` on the fake.

`FakeClusterAdmin` (in `application/test/.../fakes`), the fixture CLDOM-006, CLDOM-007 and
CLADP-002's contract test all reuse:

```scala
final class FakeClusterAdmin[F[_]: Temporal] private (state: Ref[F, FakeClusterAdmin.State])
    extends ClusterAdmin[F]
object FakeClusterAdmin:
  final case class State(
      description: Either[KuiError, ClusterDescription],
      version: Either[KuiError, Option[KafkaVersion]],
      quorum: Either[KuiError, Option[QuorumInfo]],
      configs: Map[BrokerId, Either[KuiError, List[ConfigEntry]]],
      logDirs: Either[KuiError, PartialResult[BrokerId, List[LogDir]]],
      features: Set[ClusterFeature],
      delay: FiniteDuration,                 // sleeps before answering; drives the TestControl tests
      calls: List[(ClusterId, String)]       // every method invocation, in order
  )
  def make[F[_]: Temporal](healthy: ClusterDescription): F[FakeClusterAdmin[F]]
```

## Observability

Per ADR-016 and `ARCHITECTURE.md` §13, all emitted by `ClusterSnapshots`:

| Signal | Name | Attributes |
| --- | --- | --- |
| Counter | `kui.cache.hits` / `kui.cache.misses` | `cache = "cluster.topology"`, `cluster.id` — a "miss" is a read that found no value |
| Histogram | `kui.cluster.refresh.duration` | `cluster.id`, `outcome = success \| failure` |
| Gauge | `kui.cluster.snapshot.age` | `cluster.id` — seconds since `scrapedAt`; the one number an operator alerts on |
| Counter | `kui.cluster.refresh.failures` | `cluster.id`, `error.code` |
| Span | `kui.cluster.refresh` | `cluster.id`; started per refresh, not per read |

Logging: refresh success at DEBUG with `cluster.id`, `broker.count`, `duration.ms`. The
**transition** into failure at WARN once (`cluster.id`, `error.code`, `error.message`) and then
silence until it recovers — a per-refresh WARN against a cluster that has been down all weekend is
twenty thousand identical lines. Recovery at INFO with `outage.duration.ms`. This
log-on-transition rule is asserted in `ClusterSnapshotsSuite` test 6's sibling assertion: two
consecutive failures produce one WARN.

Never logged: the `ClusterProfile`, the bootstrap string, any `Secret`. Log `cluster.id`.

## Degraded behavior

| Condition | Behaviour |
| --- | --- |
| Cluster unreachable, never was reachable | `TopologyView(topology = None, Unavailable(reason, since))`; the row renders as `Unavailable: <reason>` and stays clickable (DEVPLAN §10 D4); the capability stays `Available` |
| Cluster unreachable, was reachable | previous topology served with `Stale(scrapedAt, reason, since)`; greyed and timestamped |
| Cluster reachable, `describeLogDirs` refused | topology renders; disk cells `—`; `LogDirs` absent from `features` |
| Cluster reachable, version undetectable | `version = None`; the UI shows "unknown", not a guess; no minimum-version banner |
| ZooKeeper cluster | `quorum = None`; the quorum panel is not rendered at all |
| Registry empty | `viewAll` returns `Nil`; the dashboard shows its empty state, not an error |
| Process just started | `Loading` for at most one refresh; a skeleton row, never `Unavailable` (ADR-032 amendment 2) |
| Store unreachable | **irrelevant to this use case.** The registry keeps resolving profiles (CLDOM-004) and refresh loops keep running against the clusters those profiles name |

## Docs to update

- `docs/domain/cluster.md`: a "Snapshot and staleness" section carrying the refresh call sequence,
  the required/optional split with its rationale, the staleness table, and the two intervals.
- `ARCHITECTURE.md` §9, the `cluster` row: confirm it, or correct it if the implementation
  diverged. Two things must end up true in that row and are worth checking against what shipped:
  "metadata every 30 s; capabilities every 1 h and on reconnect" and "reads ≤ 30 s old". Record in
  the Implementation Report either "§9's cluster row is accurate" or the exact edit made — this is
  the row the milestone's caching-discipline requirement (PLAN §29) is audited against.
