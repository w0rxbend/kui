# KAFKA-008 — `ClusterAdmin` B: broker configs, log dirs, KRaft quorum

- **ID:** KAFKA-008
- **Title:** `ClusterAdmin` B: broker configs, log dirs, KRaft quorum
- **Milestone / Feature:** M1 / BR-002, BR-005, PA-003, CL-003
- **Owner role:** Principal Scala Engineer
- **Context / service:** `libs/kafka`
- **Size:** L
- **Dependencies / blocked by:** KAFKA-007

## Goal (user value)

A broker detail page shows what that broker is configured with, how much disk each of its log
directories is using, which partitions sit on which disk, and — on a KRaft cluster — how far
behind the quorum's followers are. On a cluster where KUI is allowed to see only some of that,
the page shows what it may see and says plainly what it may not, instead of failing.

## Scope

1. `brokerConfigs` — `describeConfigs` for one broker, with synonyms always and documentation
   when the cluster supports it, returning a config list in which a sensitive value is visibly
   absent rather than blank-looking.
2. `describeLogDirs` — **one call per broker**, bounded in parallel, with per-directory errors
   preserved and disk totals where the broker reports them.
3. `describeQuorum` — `Option[QuorumInfo]`, absent rather than failing on a ZooKeeper cluster.
4. The three managed-service downgrades the research documents, implemented and tested.

## Non-goals

- **No config edits.** `alterBrokerConfig` and `alterReplicaLogDir` are not implemented and not
  declared. BR-002 is read-only in M1; mutations arrive in M5 with read-only mode and audit, and
  DEVPLAN §3 is explicit that no destructive action ships before its safety net.
- No topic configs, no topic descriptions, no offsets (M2).
- No caching, no aggregation into cluster-wide totals — CLDOM-005 and CLDOM-006 do the summing;
  a port that pre-aggregates makes the numbers untestable.
- No JMX and no broker metrics. Bytes in / bytes out are `services/metrics` in M8 (DEVPLAN §3),
  and the columns render an em dash.

## Design references

`research/kafka/admin-capabilities.md` §1, rows "Broker configs", "Log dirs" and "Describe KRaft
quorum", and §0 rows "Managed services" and "Partial failure" — the behavioural source;
ADR-006; ADR-030 (`describeLogDirs` totals need 3.3, quorum needs 3.3, documentation needs 2.6 —
all gated by probe, not by version arithmetic); ADR-034; `ARCHITECTURE.md` §4.2; DEVPLAN §7
(fault-injection scenario 4: "a managed cluster that authenticates but authorizes nothing —
`describeConfigs` and `describeLogDirs` return per-key errors and the page renders what it
has").

## Files to create

```
libs/kafka/src/kui/kafka/admin/ConfigTypes.scala
libs/kafka/src/kui/kafka/admin/LogDirTypes.scala
libs/kafka/src/kui/kafka/admin/QuorumTypes.scala
libs/kafka/test/src/kui/kafka/admin/BrokerConfigsSuite.scala
libs/kafka/test/src/kui/kafka/admin/LogDirsSuite.scala
libs/kafka/test/src/kui/kafka/admin/QuorumSuite.scala
```

## Files to change

```
libs/kafka/src/kui/kafka/admin/KafkaClusterAdmin.scala   # implement the three stubbed methods
libs/kafka/test/src/kui/kafka/admin/ClusterAdminIntegrationSuite.scala   # add the live cases
```

## Public Scala signatures to implement

```scala
package kui.kafka.admin

/** Where a configuration value came from, mapped from `ConfigEntry.ConfigSource` so that
  * nothing above `libs/kafka` imports a Kafka enum. The distinction is what the UI uses to show
  * an operator which settings were actually changed on this cluster and which are defaults.
  */
enum ConfigSource {
  case DynamicBrokerConfig, DynamicDefaultBrokerConfig, DynamicTopicConfig,
       DynamicBrokerLoggerConfig, StaticBrokerConfig, DefaultConfig, Unknown
}

final case class ConfigSynonym(name: String, value: Option[String], source: ConfigSource)

/** One configuration entry.
  *
  * `value` is an `Option` and it is `None` for a sensitive setting, because that is exactly what
  * the broker sends: Kafka returns `null` for the value of a sensitive config rather than the
  * value. Modelling it as an empty string would render a password field that looks empty, and an
  * operator would conclude the setting is unset.
  */
final case class ConfigEntry(
    name: String,
    value: Option[String],
    source: ConfigSource,
    isSensitive: Boolean,
    isReadOnly: Boolean,
    isDefault: Boolean,
    documentation: Option[String],
    synonyms: List[ConfigSynonym]
)
```

```scala
package kui.kafka.admin

import kui.kernel.{PartitionId, TopicName}

final case class ReplicaInfo(
    topic: TopicName,
    partition: PartitionId,
    sizeBytes: Long,
    offsetLag: Long,
    isFuture: Boolean
)

/** One log directory on one broker.
  *
  * `error` is per directory, not per broker: a single offline disk answers
  * `KafkaStorageException` for itself while the broker's other directories answer normally, and
  * a model that could not express that would have to discard a healthy broker's data because one
  * of its disks is down.
  *
  * `totalBytes` and `usableBytes` are `Option` because brokers before 3.3 do not report them.
  */
final case class LogDir(
    path: String,
    error: Option[SkipReason],
    totalBytes: Option[Long],
    usableBytes: Option[Long],
    replicas: List[ReplicaInfo]
) {
  /** The sum of `replicas.sizeBytes` — what the broker actually holds here, as distinct from
    * what the filesystem reports. Both numbers are shown; they differ, and an operator chasing
    * a full disk needs to see the difference rather than one number chosen for them. */
  def usedByReplicasBytes: Long
}
```

```scala
package kui.kafka.admin

import kui.kernel.BrokerId

final case class QuorumVoter(
    replicaId: BrokerId,
    logEndOffset: Long,
    lastFetchTimestamp: Option[Long],
    lastCaughtUpTimestamp: Option[Long]
) {
  /** `highWatermark - logEndOffset`, floored at zero. Computed here because the subtraction is
    * the same everywhere and the floor is the part people forget: an observer can briefly report
    * an offset ahead of the watermark it was told about. */
  def lagFrom(highWatermark: Long): Long
}

final case class QuorumInfo(
    leaderId: BrokerId,
    leaderEpoch: Long,
    highWatermark: Long,
    voters: List[QuorumVoter],
    observers: List[QuorumVoter]
)
```

Implemented in `KafkaClusterAdmin`, replacing KAFKA-007's stubs:

```scala
def brokerConfigs(connection: ClusterConnection, broker: BrokerId, includeDocs: Boolean)
    : F[Either[KuiError, List[ConfigEntry]]]

def describeLogDirs(connection: ClusterConnection, brokers: Set[BrokerId])
    : F[Either[KuiError, BatchResult[BrokerId, List[LogDir]]]]

def describeQuorum(connection: ClusterConnection): F[Either[KuiError, Option[QuorumInfo]]]
```

### Behaviour `brokerConfigs` must have

- `describeConfigs(List(new ConfigResource(BROKER, broker.value.toString)),
  new DescribeConfigsOptions().includeSynonyms(true).includeDocumentation(includeDocs))`.
- `includeDocumentation` is passed as given; the caller decides from the capability set
  (`ClusterFeature.ConfigDocumentation`, KAFKA-009), so this method never probes.
- **The three managed-service downgrades**, from the research:
  `InvalidRequestException` (MSK Serverless), `UnknownTopicOrPartitionException` (Azure Event
  Hubs) and `ClusterAuthorizationException` (no `DESCRIBE_CONFIGS`) each produce
  `Right(Nil)` — an empty config list, not a failure — and one DEBUG line. Kafbat swallows these
  into an empty map and logs at WARN; KUI returns the same empty list but at DEBUG, because on a
  managed service this is the steady state and a WARN every thirty seconds is not a signal.
- Sort entries by `name`. A configuration table that reorders between refreshes is unusable.
- A sensitive entry keeps `isSensitive = true` and `value = None`, always. There is no path in
  this method that fabricates a placeholder.

### Behaviour `describeLogDirs` must have

- **One `describeLogDirs(List(brokerId))` per broker**, run through `AdminBatch.perBroker` with
  `AdminTuning.parallelism`. The research is explicit: a single call for all brokers is stalled
  by one slow disk, and the timeout then loses every broker's data.
- Read `descriptions()`, the per-key map — never `allDescriptions()`, which fails the lot.
- A broker-level failure becomes one `skipped` entry (`SkipReason` from
  `KafkaErrorMapper.suppressible`, or `Failed` when it is not suppressible). The other brokers
  still land. This is fault-injection scenario 4.
- A `LogDirDescription.error()` becomes that directory's `error` field, mapped through
  `KafkaErrorMapper.suppressible`. `KafkaStorageException` means the directory is offline; the
  directory is still listed, with its error, because "this disk is down" is the single most
  important thing this screen can say.
- `totalBytes` and `usableBytes` come from `OptionalLong`s; empty becomes `None`. `-1` also
  becomes `None` — some brokers report the sentinel rather than an empty optional.
- `UnsupportedVersionException` for the whole call (a broker before 1.0, or a managed service
  that hides log dirs) becomes `Right` of a `BatchResult` in which every requested broker is
  `SkipReason.Unsupported("logDirs")`. The page then says "not available on this cluster" per
  broker, which is true, rather than showing an error.
- An empty `brokers` set returns an empty complete result without calling anything.

### Behaviour `describeQuorum` must have

- `describeMetadataQuorum()`; on `UnsupportedVersionException` (a ZooKeeper cluster, or a broker
  before 3.3) return `Right(None)`. Absence is the answer, not a failure.
- On `ClusterAuthorizationException` also return `Right(None)`, with a DEBUG line: KUI may not
  ask, so KUI does not know, and dimming a page for a missing ACL on an optional panel is worse
  than an empty panel.
- Map `OptionalLong` timestamps to `Option[Long]`; a `-1` timestamp (never fetched) is `None`.

## ADRs this task must obey

ADR-006 (per-key partial results, bounded parallelism, no silent drops), ADR-030 (features
absent on old brokers degrade rather than fail; nothing here compares version numbers — the
capability set decides, KAFKA-009), ADR-034 (`SkipReason` carries the code; `message` carries no
broker text), ADR-039 §6 (an ACL refusal is an `ApplicationError` and must never dim the cluster
capability), ADR-016 (no caching here).

## Library coordinates

None new.

## Acceptance criteria

```
$ ./mill libs.kafka.test
$ ./mill libs.kafka.compile          # clean under -Werror
$ grep -rn "alterConfig\|alterReplicaLogDir" libs/kafka/src
# no output: M1 is read-only (DEVPLAN §3)
```

Against the PLAINTEXT container:

```scala
val cfgs = admin.brokerConfigs(conn, BrokerId.unsafe(1), includeDocs = true)
  .unsafeRunSync().toOption.get
assert(cfgs.exists(_.name == "log.dirs"))
assertEquals(cfgs.map(_.name), cfgs.map(_.name).sorted)
assert(cfgs.filter(_.isSensitive).forall(_.value.isEmpty))
assert(cfgs.exists(_.documentation.isDefined))
assert(cfgs.exists(_.synonyms.nonEmpty))

val dirs = admin.describeLogDirs(conn, Set(BrokerId.unsafe(1))).unsafeRunSync().toOption.get
assertEquals(dirs.skipped, Map.empty)
assert(dirs.values(BrokerId.unsafe(1)).exists(_.path.contains("/")))

val q = admin.describeQuorum(conn).unsafeRunSync().toOption.get
assert(q.isDefined)                       // the pinned container is KRaft
assert(q.get.voters.nonEmpty)
```

## Tests required

- `BrokerConfigsSuite` (unit, fake pool):
  - `sensitiveValuesAreNoneAndFlagged`.
  - `sourcesAreMappedTable` — one row per `ConfigSource`, plus an unknown source becoming
    `Unknown` rather than throwing.
  - `synonymsArePreservedInOrder`.
  - `entriesAreSortedByName` (property).
  - **`invalidRequestBecomesAnEmptyList`**, `unknownTopicOrPartitionBecomesAnEmptyList`,
    `clusterAuthorizationBecomesAnEmptyList` — the three managed-service rows, each asserting a
    `Right(Nil)` and no WARN.
  - `aTimeoutIsStillALeft` — the downgrades are for the three documented classes only; a
    timeout must not be laundered into "this cluster has no configuration".
  - `includeDocumentationIsPassedThroughAndNeverProbed`.
- `LogDirsSuite` (unit, fake pool, `TestControl`):
  - **`oneCallPerBroker`** — assert the call count equals the broker count. This is the test
    that fails if somebody "optimises" it back into a single request.
  - `parallelismIsBounded` — with `parallelism = 4` and eight brokers, at most four concurrent.
  - **`oneBrokerFailingDoesNotLoseTheOthers`** — seven values, one skipped with a reason.
  - **`aPerDirectoryErrorIsPreservedAndTheDirectoryIsStillListed`** — the offline-disk case.
  - `emptyOptionalTotalsAreNone`, and `minusOneTotalsAreAlsoNone`.
  - `unsupportedVersionSkipsEveryBrokerWithAReasonAndReturnsRight`.
  - `emptyBrokerSetCallsNothing`.
  - `usedByReplicasBytesSumsTheReplicas` (property).
  - `everySkippedBrokerHasAReason` (property) — ADR-006's "never silent drops".
- `QuorumSuite` (unit, fake pool):
  - `unsupportedVersionIsNoneNotAnError`, `clusterAuthorizationIsNoneNotAnError`.
  - `lagIsFlooredAtZero` (property).
  - `absentTimestampsAreNone`, including the `-1` sentinel.
  - `aTimeoutIsALeft`.
- `ClusterAdminIntegrationSuite` gains, against the live PLAINTEXT container:
  `brokerConfigsAgainstALiveBroker`, `logDirsAgainstALiveBroker`,
  `quorumAgainstALiveKraftBroker`, and
  `logDirsForAnUnknownBrokerIsSkippedNotFailed`.

## Observability

Inherited from `AdminClientPool.run`: one span and one `kui.kafka.admin.duration` sample per
call, with `operation` set to `describeConfigs`, `describeLogDirs` or `describeMetadataQuorum`.
`describeLogDirs` records one sample per broker call plus the batch's own DEBUG summary line
from KAFKA-006, so a single slow disk is visible as one slow sample rather than as a slow
cluster.

Log lines added here, all under `kui.kafka.admin`:

- DEBUG on each of the three managed-service downgrades, naming the exception class and the
  cluster. Not WARN: on MSK Serverless this happens on every refresh forever.
- WARN, at most once per broker per hour (a simple last-logged timestamp in the adapter), when a
  log directory reports an error. An offline disk is a real operational event and deserves a
  line, but not one every thirty seconds.

## Degraded behavior

- **No `DESCRIBE_CONFIGS` on the cluster:** an empty configuration list, per broker, and the UI
  renders "not available — KUI lacks DESCRIBE_CONFIGS on this cluster". Not an error, not a
  dimmed capability (ADR-039 §6).
- **A managed service that hides broker configs** (MSK Serverless, Event Hubs): the same empty
  list, by the same path, which is why the three downgrades share one code path and one test
  shape.
- **One offline disk:** listed, with its error, alongside that broker's healthy directories.
- **One unreachable broker:** one skipped entry; the other brokers render. The cluster's total
  disk usage is then a sum over fewer brokers, and `BatchResult.isComplete` is `false` — which is
  what CLDOM-006 uses to decide whether to show a total at all rather than a quietly wrong one.
- **A ZooKeeper cluster:** no quorum panel. `Right(None)`, rendered as an absent section, never
  as an outage.
- **Everything refused:** a `BatchResult` where every key is skipped, which is still a 200 and
  still a page.

## Docs to update

None. The operator-facing "which ACLs KUI needs to show a full broker page" table is CFGOP-008's
in `docs/operations/configuration.md`; the downgrade rules above are its source.

## Deviations

*(filled in by the implementer, in the same commit)*
