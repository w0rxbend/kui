# CLDOM-006 — Broker detail use cases: configs, log dirs, per-partition sizes

- **ID:** CLDOM-006
- **Title:** Broker detail use cases: configs, log dirs, per-partition sizes
- **Milestone / Feature:** M1 / BR-001, BR-002, BR-005, PA-003
- **Owner role:** Domain Architect (Cluster Registry context)
- **Size:** M
- **Dependencies / blocked by:** CLDOM-005

## Goal (user value)

The broker detail page: one broker's rack and address, its log directories with their sizes and
health, the partitions living on each directory, and its configuration with sources, synonyms and
documentation — read live, per request, so an operator who changed a setting a moment ago sees the
new value instead of a thirty-second-old one.

## Scope

1. `BrokerDetailUseCase[F]` with three reads: the broker list, one broker's log directories, one
   broker's configuration.
2. `BrokerListRow` and `BrokerDetail` — the application-owned shapes the API maps to DTOs.
3. `BrokerConfigView` — configuration entries with the filtering and grouping the UI needs, and
   the `Unsupported` verdict when the cluster will not answer.
4. `PartitionSizes` — per-partition sizes on one broker, derived from its log directories.
5. The live-vs-snapshot rule (below), which is the design decision this task exists to make.
6. `docs/domain/cluster.md` gains a "Broker detail" section.

## Non-goals

- **No configuration edits.** No `alterBrokerConfig`, no validate-and-apply, no dry run. DEVPLAN
  §3: `BR-002` is read-only in M1 and the mutation arrives in M5 with read-only mode and audit.
  The port has no method for it (CLDOM-003) and this task must not add one.
- **No replica moves.** `alterReplicaLogDir` and its progress polling are not in M1.
- **No topic-level anything.** `PartitionSizes` names `TopicPartition`s because
  `describeLogDirs` reports them; it must not call `describeTopics` to decorate them with
  leadership, ISR or a topic's configuration. That is M2's page.
- **No metrics.** Bytes in / out per broker, request rates and JMX figures render `—`
  (DEVPLAN §3).
- **No new port methods.** Everything here is `ClusterAdmin.brokerConfigs` and
  `ClusterAdmin.describeLogDirs` from CLDOM-003, plus the snapshot from CLDOM-005.

## The live-vs-snapshot rule — decided

ADR-027 says list screens are served from the snapshot and "detail pages re-describe the single
resource live". `ARCHITECTURE.md` §9's cluster row lists log dirs among the snapshot's contents.
Both are right about different things and the boundary has to be drawn.

**Decision:**

| Read | Source | Why |
| --- | --- | --- |
| Broker **list** (`brokers`) | the topology snapshot (CLDOM-005) | it is the list screen ADR-027 describes; it must render for a dead cluster from cached data; and a page listing thirty brokers must not make thirty admin calls |
| Broker **log directories** (`logDirs`) | **live**, one `describeLogDirs` for that broker | a directory that went offline three seconds ago is the reason the operator opened the page; and it is one call for one broker, not a fan-out |
| Broker **configuration** (`configs`) | **live**, one `describeConfigs` for that broker | it is never in the snapshot at all — scraping every broker's full config every 30 s is `brokers × ~200 entries` of pointless traffic — and an operator who just changed a dynamic setting expects to see it |
| Per-partition **sizes** (`partitionSizes`) | derived from the same live `describeLogDirs` call as `logDirs` | it is the same data, reshaped; issuing a second call for it would double the cost of one page |

**A live read that fails falls back to the snapshot rather than failing the page**, for log
directories only — the snapshot already holds `BrokerLoad.logDirs` from the last successful
refresh. The result carries `SnapshotFreshness.Stale` so the UI greys it and shows the timestamp.
Configuration has no snapshot to fall back to, so its failure is an honest `Left`. This is the
same "serve what you have, say how old it is" contract as CLDOM-005 and it is what makes the
broker page survive a broker restart.

## Design references

- ADR-027 — list screens from the snapshot; detail pages live.
- ADR-016 — a fallback read must state its staleness; nothing new is cached by this task.
- ADR-034 — `Left` is a `KuiError` value; a Kafka exception never crosses this layer.
- ADR-039 §6 — `ApplicationError.Unsupported` for "this cluster will not answer" must not dim a
  capability; only `InfrastructureError` may.
- ADR-041 A3 — no wire types in `application`.
- `research/kafka/admin-capabilities.md` §1, rows "Broker configs" and "Log dirs": the exact
  errors (`InvalidRequestException` on MSK Serverless, `UnknownTopicOrPartitionException` on Event
  Hubs, `ClusterAuthorizationException` without `DESCRIBE_CONFIGS`, `UnsupportedVersionException`
  and a per-directory `KafkaStorageException`), and the note that a single slow disk stalls the
  request — which is why `describeLogDirs` is issued per broker with bounded parallelism and never
  as one call for all brokers.
- `research/kafbat/ui-analysis.md` "Broker details" — the tabs the reference product has, and the
  inline edit affordance M1 deliberately does not build.
- `research/design/REFERENCE.md` — how the two tabs look. It decides nothing about which fields
  exist.

## Files to create or change

```
services/cluster/application/src/kui/cluster/application/BrokerViews.scala          (new)
services/cluster/application/src/kui/cluster/application/BrokerDetailUseCase.scala  (new)
services/cluster/application/test/src/kui/cluster/application/BrokerDetailUseCaseSuite.scala (new)
docs/domain/cluster.md                                                              (changed)
```

No `build.mill` change. Everything needed arrived with CLDOM-004 and CLDOM-005.

## Public Scala signatures to implement

```scala
package kui.cluster.application

import cats.data.NonEmptyList
import cats.effect.kernel.Temporal
import org.typelevel.log4cats.StructuredLogger

import kui.cluster.domain.*
import kui.kernel.{BrokerId, ClusterId, TopicPartition}
import kui.kernel.error.KuiError

/** One row of the broker list.
  *
  * Everything on it comes from the topology snapshot, so the whole list is one memory read.
  * `leaders` is `None` in M1 and the column renders `—` (CLDOM-002, "What M1 cannot fill").
  */
final case class BrokerListRow(
    broker: Broker,
    isController: Boolean,
    replicas: Option[Int],
    leaders: Option[Int],
    skewPercent: Option[Double],
    totalBytes: Option[Long],
    usableBytes: Option[Long],
    offlineDirCount: Int
)

/** The broker list plus how fresh it is. */
final case class BrokerList(
    cluster: ClusterRef,
    brokers: List[BrokerListRow],
    freshness: SnapshotFreshness
)

/** One broker's log directories, with the freshness of the read that produced them: `Fresh` for a
  * live call, `Stale` when the live call failed and the snapshot answered instead.
  */
final case class BrokerLogDirs(
    cluster: ClusterRef,
    broker: BrokerId,
    dirs: List[LogDir],
    freshness: SnapshotFreshness
):
  def totalBytes: Option[Long]
  def usableBytes: Option[Long]
  def offline: List[LogDir] = dirs.filterNot(_.isHealthy)

/** Where one partition's data sits on this broker and how much space it takes. */
final case class PartitionSize(
    partition: TopicPartition,
    path: LogDirPath,
    sizeBytes: Long,
    offsetLag: Long,
    isFuture: Boolean
)

/** Every partition hosted on one broker, largest first — which is the order the question "what is
  * filling this disk?" is asked in, and the only ordering the page ever needs. */
final case class PartitionSizes(
    cluster: ClusterRef,
    broker: BrokerId,
    partitions: List[PartitionSize],
    freshness: SnapshotFreshness
):
  def totalBytes: Long = partitions.map(_.sizeBytes).sum

/** One broker's configuration.
  *
  * `entries` is always sorted by name. The UI groups by `ConfigSource` and hides defaults behind a
  * toggle (CLUI-005); the grouping is the UI's, but the *sort* is here so that two requests never
  * disagree about row order and a diff between two brokers is readable.
  */
final case class BrokerConfigView(
    cluster: ClusterRef,
    broker: BrokerId,
    entries: List[ConfigEntry],
    /** True when the cluster answered with documentation strings — that is, when the
      * `ConfigDocumentation` feature is present. The UI shows the help affordance only then,
      * rather than rendering an empty tooltip on every row of an older broker. */
    hasDocumentation: Boolean
):
  def dynamic: List[ConfigEntry]   // source is DynamicBroker or DynamicDefaultBroker
  def nonDefault: List[ConfigEntry] = entries.filterNot(_.isDefault)
  /** Entries whose value the broker withheld because they are sensitive. They are *shown*, with no
    * value: hiding the row entirely would let an operator conclude a setting is unset when it is
    * set to something they are not allowed to read. */
  def sensitive: List[ConfigEntry] = entries.filter(_.isSensitive)

/** The broker detail reads. */
trait BrokerDetailUseCase[F[_]]:
  /** From the snapshot. Never calls a broker. `Left(NotFound)` only for an unknown *cluster*. */
  def brokers(cluster: ClusterId): F[Either[KuiError, BrokerList]]

  /** Live, with a snapshot fallback. `Left(ApplicationError.NotFound(..., ClusterNotFound))` for
    * an unknown cluster; `Left(ApplicationError.NotFound("broker", ...))` for a broker id that is
    * not in the cluster's current description — checked against the snapshot before any call is
    * made, so a typo'd broker id costs nothing and produces a 404 rather than an admin timeout. */
  def logDirs(cluster: ClusterId, broker: BrokerId): F[Either[KuiError, BrokerLogDirs]]

  /** Derived from the same live `describeLogDirs` as `logDirs`. Callers that need both should call
    * `logDirsAndSizes` rather than both of these, to avoid a second admin call. */
  def partitionSizes(cluster: ClusterId, broker: BrokerId): F[Either[KuiError, PartitionSizes]]

  /** One call, both shapes. This is what CLAPI-004 wires the log-dirs endpoint to. */
  def logDirsAndSizes(cluster: ClusterId, broker: BrokerId)
      : F[Either[KuiError, (BrokerLogDirs, PartitionSizes)]]

  /** Live, no fallback.
    *
    * `Left(ApplicationError.Unsupported("broker configuration"))` when the cluster refuses the
    * call — an MSK Serverless `InvalidRequestException`, an Event Hubs
    * `UnknownTopicOrPartitionException`, or a missing `DESCRIBE_CONFIGS`. The adapter classifies
    * it (CLADP-002) and this use case passes it through unchanged. It must **not** become
    * `Right(Nil)`: an empty configuration table and "this cluster does not expose broker
    * configuration" look identical to a user and mean opposite things, which is the defect
    * `admin-capabilities.md` §1 records in the reference product. */
  def configs(cluster: ClusterId, broker: BrokerId, includeDocs: Boolean)
      : F[Either[KuiError, BrokerConfigView]]

object BrokerDetailUseCase:
  val Operation: String = "kui.cluster.broker"

  def make[F[_]: Temporal](
      registry: ClusterRegistry[F],
      snapshots: ClusterSnapshots[F],
      admin: ClusterAdmin[F],
      logger: StructuredLogger[F]
  ): BrokerDetailUseCase[F]
```

### `includeDocs` and the capability

`configs` passes `includeDocs && features.contains(ClusterFeature.ConfigDocumentation)` to the
port. Asking a 2.5 broker for documentation raises `UnsupportedVersionException` and loses the
whole call, so the capability set decides and the caller's flag can only narrow it. `hasDocumentation`
on the result reports what was actually asked for, so the UI never promises a tooltip that will
be empty.

### Broker existence

`logDirs`, `partitionSizes` and `configs` all check the broker id against the snapshot's
`ClusterDescription` first. Three consequences worth stating so nobody removes the check as
redundant: a bad id costs no network call; the 404 is an `ApplicationError` and so cannot dim a
capability; and a cluster whose snapshot is `Unavailable` (never reached) returns
`InfrastructureError` from the snapshot's own recorded error rather than a misleading "broker not
found", because the correct answer to "does broker 3 exist" on an unreachable cluster is "I cannot
tell you".

## Library coordinates

Unchanged from CLDOM-005:
`services.cluster.application` = `moduleDeps Seq(domain, libs.cache)`; cats-core 2.13.0,
cats-effect 3.7.1, fs2-core 3.13.0, log4cats-core 2.8.0, otel4s-core 1.1.0. Test module:
`libs.testkit.jvm`, `services.cluster.domain.test`, munit 1.3.6, munit-scalacheck 1.3.1,
scalacheck 1.20.0, munit-cats-effect 2.2.0, cats-effect-testkit 3.7.1.

## Acceptance criteria

```
$ ./mill services.cluster.application.test
Test run kui.cluster.application.BrokerDetailUseCaseSuite finished: 0 failed, 0 ignored, 15 total
SUCCESS

$ ./mill checkArchitecture
checkArchitecture: <n> modules, no layering violations

$ ./mill services.cluster.application.checkFormat && ./mill services.cluster.application.fix --check
```

For the Implementation Report:

```
$ grep -rn "alterBrokerConfig\|alterReplicaLogDir\|incrementalAlter" services/cluster/application  # (none)
$ grep -rn "describeTopics" services/cluster/application                                            # (none)
```

## Tests required

`BrokerDetailUseCaseSuite` (MUnit + `munit-cats-effect` + `TestControl`, over `FakeClusterAdmin`
from CLDOM-005 and `FakeClusterConfigStore` from CLDOM-004):

1. `brokerListComesFromTheSnapshotAndMakesNoAdminCall` — under `TestControl` with a fake whose
   delay is one hour, `brokers` completes at virtual time zero and `FakeClusterAdmin.calls` is
   unchanged.
2. `brokerListMarksTheController` — exactly one row has `isController = true`; a topology with no
   controller has none, and does not fail.
3. `brokerListRendersLeadersAsNone` — the M1 contract; the test M2 must delete deliberately.
4. `brokerListOfAStaleClusterCarriesStaleFreshness`.
5. `logDirsIsALiveCall` — assert `describeLogDirs` appears in `calls` exactly once.
6. `logDirsFallsBackToTheSnapshotWhenTheLiveCallFails` — the live call fails, the snapshot holds
   directories: the result is `Right`, the directories are the snapshot's, and `freshness` is
   `Stale` with the snapshot's `scrapedAt`.
7. `logDirsFailsWhenTheLiveCallFailsAndTheSnapshotHasNothing` — `Left`, and the error is the live
   call's.
8. `unknownBrokerIsNotFoundWithNoAdminCall` — `ApplicationError`, `calls` unchanged.
9. `unknownClusterIsClusterNotFound` — `ErrorCode.ClusterNotFound`.
10. `brokerLookupOnAnUnreachableClusterReportsTheClusterFailureNotBrokerNotFound` — the
    "I cannot tell you" case above; assert the branch is `InfrastructureError`.
11. `partitionSizesAreSortedLargestFirstAndIncludeFutureReplicas` — a future replica appears, and
    is flagged; the operator needs to see the disk it is currently occupying.
12. `logDirsAndSizesIssuesOneAdminCall` — `calls.count(_._2 == "describeLogDirs") == 1`.
13. `configsAreLiveSortedAndKeepSensitiveRowsWithNoValue` — a sensitive entry is present in
    `entries`, its `value` is `None`, and it appears in `sensitive`.
14. `configsReturnUnsupportedAndNotAnEmptyList` — the fake refuses with
    `ApplicationError.Unsupported`; assert the result is `Left` and specifically **not**
    `Right(view)` with an empty `entries`. The named managed-service defect.
15. `documentationIsNotRequestedWithoutTheCapability` — features lack `ConfigDocumentation`;
    assert the fake recorded `docs = false` and `hasDocumentation` is `false`.

## Observability

| Signal | Name | Attributes |
| --- | --- | --- |
| Span | `kui.cluster.broker.logDirs`, `kui.cluster.broker.configs` | `cluster.id`, `broker.id`; request-scoped, so these carry the correlation id from the MDC bridge |
| Histogram | `kui.kafka.admin.duration` | emitted by the adapter (CLADP-002), not here |
| Counter | `kui.cluster.broker.fallback` | `cluster.id`, `broker.id` — incremented when `logDirs` served from the snapshot. The one number that says "live broker reads are failing but the page still works", which is otherwise invisible |

Log lines: a live-call failure that fell back at WARN once per occurrence with `cluster.id`,
`broker.id`, `error.code` — WARN and not ERROR, because the page succeeded. `brokers` logs
nothing; it is a memory read on a hot path. Never log a `ConfigEntry` list: a broker's
configuration contains `advertised.listeners`, and a sensitive entry's *name* is enough to reveal
which authentication is configured.

## Degraded behavior

| Condition | `brokers` | `logDirs` | `configs` |
| --- | --- | --- | --- |
| Cluster fresh | snapshot, `Fresh` | live, `Fresh` | live |
| Cluster stale (unreachable now, reachable before) | snapshot, `Stale` | live fails → snapshot, `Stale` | `Left(InfrastructureError)`; the tab shows an inline error, the rest of the page renders |
| Cluster never reachable | `Right` with `Unavailable` freshness and an empty broker list? **No** — `Left` is wrong and an empty list is wrong. The snapshot has no `ClusterDescription`, so `brokers` returns `Right(BrokerList(ref, Nil, Unavailable(reason, since)))`: an empty list *labelled* unavailable, which the UI renders as the `Unavailable: <reason>` panel and not as "this cluster has no brokers" | `Left(InfrastructureError)` from the snapshot's recorded error | same |
| `describeLogDirs` unsupported (managed service) | snapshot rows with `—` for disk | `Left(ApplicationError.Unsupported)`; the tab says the cluster does not expose it | unaffected |
| `describeConfigs` unsupported | unaffected | unaffected | `Left(ApplicationError.Unsupported)` |
| One log directory offline | `offlineDirCount ≥ 1` on the row | that `LogDir` has `error = Some(Offline)` and still lists its replicas | unaffected |
| Broker being restarted | still listed from the snapshot | live call times out → snapshot, `Stale` | `Left(InfrastructureError.Timeout)` |

Note the third row: it is the one place where `Right` with an empty collection is correct, and it
is correct *because* the freshness field carries the reason. That is the whole argument for
`SnapshotFreshness` being part of every one of these types rather than a separate lookup.

## Docs to update

`docs/domain/cluster.md` gains a "Broker detail" section: the live-vs-snapshot table with its
rationale, the log-dirs fallback rule and why configuration has none, the "empty list labelled
unavailable" case, and a note that broker configuration is read-only in M1 with a pointer to M5.
