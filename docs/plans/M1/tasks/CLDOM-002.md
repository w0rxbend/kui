# CLDOM-002 — Cluster domain: the topology model and its invariants

- **ID:** CLDOM-002
- **Title:** Cluster domain: the topology model and its invariants
- **Milestone / Feature:** M1 / CL-003, CL-007, CL-009, BR-001, BR-002, BR-005, PA-003
- **Owner role:** Domain Architect (Cluster Registry context)
- **Size:** M
- **Dependencies / blocked by:** CLDOM-001

## Goal (user value)

What a cluster *is made of*, as types that cannot hold a contradiction: the brokers, the
controller, the KRaft quorum, the log directories, the broker configuration entries, the detected
version, and the set of things KUI has established it can do there. Every cluster screen in M1
renders a value defined in this task, and CLAPI-001 shapes its DTOs from it, so it is the type
that decides what the product can show.

## Scope

1. `Broker`, `BrokerRack`, and the broker set with its ordering.
2. `ControllerMode`, `QuorumInfo`, `ReplicaState`.
3. `KafkaVersion` and the ADR-030 minimum-version verdict.
4. `LogDir`, `LogDirPath`, `LogDirError`, `ReplicaInfo`, and the per-broker roll-up.
5. `ConfigEntry`, `ConfigSource`, `ConfigSynonym` — read-only broker configuration.
6. `ClusterFeature` — the closed set of optional things KUI can do against a cluster.
7. `ClusterDescription` — controller, cluster id, brokers, authorized operations.
8. `ClusterTopology` — the aggregate the snapshot cell holds: a description plus what was learned
   about each broker, plus the fields M1 deliberately cannot fill.
9. `docs/domain/cluster.md` gains a "Topology" section.

## Non-goals

- **No topics, no partitions-as-entities, no consumer groups.** `Topic`, `Partition`, `TopicConfig`
  and everything in `docs/domain/kafka-glossary.md` §2–§3 belongs to `services/topic` in M2. The
  only partition-shaped value in this task is `ReplicaInfo`, and it exists because
  `describeLogDirs` returns one per replica per directory — it is a *disk* fact, not a topic fact.
- **No mutation types.** `ConfigOp`, `AlterConfigOp`, `ReassignmentPlan` and
  `alterReplicaLogDir`'s input are not declared. M1's broker configuration is read-only
  (DEVPLAN §3), and a type declared for an operation that does not ship is an invitation
  (risk R-11).
- **No metrics.** Bytes in / out, throughput and anything JMX-derived have no field here. See
  "What M1 cannot fill" below for the two fields that *are* declared as `Option` and why.
- **No `libs/kafka` types.** See "Who owns the topology types" below.
- **No codecs, no redaction rules.** CLAPI-001 owns the DTO and its redaction.

## Who owns the topology types — decided, and it is a correction to `ARCHITECTURE.md` §4.2

`ARCHITECTURE.md` §4.2 writes `libs/kafka`'s port as
`def describeCluster(profile: ClusterProfile): F[Either[KuiError, ClusterDescription]]`. DEVPLAN
§10 D1 fixed the *argument* by moving the connection ADT into `libs/kernel`. It did not fix the
*result*: `ClusterDescription`, `Broker`, `LogDir`, `ConfigEntry` and `QuorumInfo` are cluster
**domain** types in `docs/domain/kafka-glossary.md`, and rule A5 forbids `libs/kafka` depending on
a service just as firmly for a return type as for a parameter.

**Decision.** The topology model lives in `services/cluster/domain` (this task).
`libs/kafka` returns its own transport-shaped records in `kui.kafka.admin` — flat, invariant-free
carriers of exactly what the AdminClient produced, `null`s already turned into `Option` — and
`services/cluster/infrastructure` (CLADP-002) maps them into these domain types, running the
smart constructors as it goes.

That mapper is not the duplication D1 rejected. D1 was about the *input*: one operator-authored
ADT that four modules must agree on, where a second copy means a second parser, a second
redaction rule and a second place to leak a password. This is the opposite direction — data
flowing in from a Java SDK — and turning a foreign, nullable, invariant-free structure into a
validated domain value is the single most standard job an adapter has (ADR-041's own rationale;
`clean-architecture`'s dependency rule). Refusing to write it would mean either the domain
imports `org.apache.kafka.*` transitively, or the domain has no invariants at all.

**Consequence for other lanes, to be raised in review of KAFKA-007/008/009:** `libs/kafka`'s
result types must be *strictly* less structured than the ones here — no `NonEmptyList`, no smart
constructors, no rejection of a broker with a negative id. `libs/kafka`'s job is "faithfully
report what the broker said"; deciding that what the broker said is impossible is this module's
job, and doing it twice means two different verdicts.

## What M1 cannot fill, and how the model says so

`ClusterTopology` declares three fields that are always `None` in M1. They are declared rather
than omitted so that CLAPI-001's DTO, the OpenAPI document and CLUI-003/CLUI-004's columns have
their final shape now and M2 fills a value instead of changing a contract. Each carries a
scaladoc saying which milestone fills it.

| Field | Why it cannot be filled in M1 |
| --- | --- |
| `BrokerLoad.leaders` | The number of partitions a broker leads comes from `describeTopics(...).partitions.leader`. DEVPLAN §3 forbids a `describeTopics` sweep in the cluster service's refresh loop, and `describeCluster` and `describeLogDirs` do not report leadership. Fills in M2 from the topic service. |
| `ClusterTopology.partitions` (`Option[PartitionSummary]`: online, offline, under-replicated) | Same source, same reason. **This is a delta against DEVPLAN §10 D5**, which lists "online/offline partition counts" among the things the dashboard shows in M1 from `describeCluster` + brokers + `describeLogDirs`. No Kafka API produces them from those three calls — `research/kafka/admin-capabilities.md` §1 "Cluster stats" says explicitly that there is *no single API* and that Kafbat aggregates `describeTopics` + `describeLogDirs` + `listOffsets` on a schedule. The dashboard therefore renders `—` for these two cells in M1, which is exactly the treatment §3 already prescribes for metric columns. |
| `ClusterTopology.topics` (`Option[Int]`) | Requires `listTopics`. §3 non-goal. Fills in M2. |

What M1 *can* fill, and does: broker count, controller identity, KRaft quorum, detected version,
per-broker rack, per-broker **replica** count and replica skew, per-broker and total disk usage,
log-directory health, and the capability set. Replica counts come from `describeLogDirs`, which
returns one `ReplicaInfo` per replica hosted on each directory — a disk-level fact that needs no
topic sweep. Replica skew is computed from those counts (see `BrokerLoad.skewPercent`), which is
why DEVPLAN §3's "skew percentages are not metrics" holds.

## Design references

- `docs/domain/kafka-glossary.md` §1 in full — every type below is named there, and the names
  here must match it exactly (PLAN §25: the glossary is the only vocabulary).
- `research/kafka/admin-capabilities.md` §0 (the capability-probe table, version detection,
  `null` results) and §1 (per-operation errors and the reference workarounds). **This is the
  behavioural source and it outranks `ARCHITECTURE.md` §4.2's sketch.**
- ADR-030 — minimum broker version 2.8; features are *probed*, never inferred from a version.
- ADR-031 — `KafkaClusterId` is recorded and paired with `BrokerId` in cache keys.
- ADR-041 A1 — `libs.kernel` and cats-core only.
- `research/kafbat/ui-analysis.md` "Brokers" — which columns a broker row has and what the
  reference product shows when a value is missing.

## Files to create or change

```
services/cluster/domain/src/kui/cluster/domain/Broker.scala                (new)
services/cluster/domain/src/kui/cluster/domain/Quorum.scala                (new)
services/cluster/domain/src/kui/cluster/domain/KafkaVersion.scala          (new)
services/cluster/domain/src/kui/cluster/domain/LogDir.scala                (new)
services/cluster/domain/src/kui/cluster/domain/ConfigEntry.scala           (new)
services/cluster/domain/src/kui/cluster/domain/ClusterFeature.scala        (new)
services/cluster/domain/src/kui/cluster/domain/ClusterTopology.scala       (new)
services/cluster/domain/test/src/kui/cluster/domain/BrokerSuite.scala      (new)
services/cluster/domain/test/src/kui/cluster/domain/LogDirSuite.scala      (new)
services/cluster/domain/test/src/kui/cluster/domain/KafkaVersionSuite.scala (new)
services/cluster/domain/test/src/kui/cluster/domain/ClusterTopologySuite.scala (new)
services/cluster/domain/test/src/kui/cluster/domain/TopologyFixtures.scala (new)
docs/domain/cluster.md                                                     (changed)
```

## Public Scala signatures to implement

```scala
package kui.cluster.domain

import java.time.Instant
import cats.data.NonEmptyList
import kui.kernel.{BrokerId, Host, KafkaClusterId, PartitionId, Port, TopicName, TopicPartition, ValidationError}
import kui.kernel.error.DomainError

/** The rack a broker is placed in, when the operator configured `broker.rack`.
  *
  * `Option[BrokerRack]` and never `""`. `Node.rack()` is nullable in the Java client, and an empty
  * string reaching the UI renders as a blank cell that looks like a rendering bug rather than like
  * "this cluster is not rack-aware". The smart constructor rejects blank input for the same reason.
  */
opaque type BrokerRack = String
object BrokerRack:
  def from(raw: String): Either[ValidationError, BrokerRack]
  def unsafe(raw: String): BrokerRack
  extension (r: BrokerRack) def value: String
  given Ordering[BrokerRack]; given CanEqual[BrokerRack, BrokerRack]

/** One Kafka server process, as cluster metadata reports it. */
final case class Broker(id: BrokerId, host: Host, port: Port, rack: Option[BrokerRack])
object Broker:
  given Ordering[Broker] = Ordering.by(_.id.value)
  given CanEqual[Broker, Broker] = CanEqual.derived

/** How this cluster manages its metadata. `Unknown` is a real answer, not a placeholder: a cluster
  * that refused `describeMetadataQuorum` with `ClusterAuthorizationException` has a mode KUI is
  * not allowed to learn, and reporting `ZooKeeper` because the call failed would be a guess.
  */
enum ControllerMode:
  case ZooKeeper, KRaft, Unknown

/** One member of the KRaft metadata quorum. `lag` is derived, never stored. */
final case class ReplicaState(
    replicaId: BrokerId,
    logEndOffset: Long,
    lastFetch: Option[Instant],
    lastCaughtUp: Option[Instant]
)

final case class QuorumInfo private (
    leaderId: BrokerId,
    leaderEpoch: Long,
    highWatermark: Long,
    voters: NonEmptyList[ReplicaState],
    observers: List[ReplicaState]
):
  /** How far behind the leader's high watermark this member is. Never negative: a follower
    * reporting an LEO above the leader's HWM is a racing read, not a negative lag. */
  def lagOf(state: ReplicaState): Long = math.max(0L, highWatermark - state.logEndOffset)
  def leader: Option[ReplicaState] = voters.find(_.replicaId == leaderId)

object QuorumInfo:
  /** Fails when `leaderId` is not among the voters, or when an offset is negative. */
  def from(
      leaderId: BrokerId, leaderEpoch: Long, highWatermark: Long,
      voters: List[ReplicaState], observers: List[ReplicaState]
  ): Either[DomainError, QuorumInfo]

/** The broker version KUI detected, and how. `raw` is kept verbatim because the operator has to be
  * able to match it against what the broker itself reports.
  */
final case class KafkaVersion private (major: Int, minor: Int, raw: String, source: VersionSource):
  def isAtLeast(major: Int, minor: Int): Boolean
  /** ADR-030: KUI supports 2.8 and newer. `false` makes the UI show a warning; it never blocks. */
  def meetsMinimum: Boolean = isAtLeast(KafkaVersion.MinimumMajor, KafkaVersion.MinimumMinor)

enum VersionSource:
  /** `describeFeatures().finalizedFeatures()["metadata.version"]`, mapped through the level table. */
  case MetadataVersion
  /** The `inter.broker.protocol.version` broker config (ZooKeeper-mode fallback). */
  case InterBrokerProtocol

object KafkaVersion:
  val MinimumMajor: Int = 2
  val MinimumMinor: Int = 8
  /** Parses `"3.9-IV0"`, `"3.9.1"`, `"4.0"`. Fails on anything with no leading `<int>.<int>`. */
  def parse(raw: String, source: VersionSource): Either[DomainError, KafkaVersion]
  given Ordering[KafkaVersion] = Ordering.by(v => (v.major, v.minor))

opaque type LogDirPath = String
object LogDirPath:
  def from(raw: String): Either[ValidationError, LogDirPath]   // non-blank, ≤ 4096
  def unsafe(raw: String): LogDirPath
  extension (p: LogDirPath) def value: String
  given Ordering[LogDirPath]; given CanEqual[LogDirPath, LogDirPath]

/** Why one log directory is not usable. A closed set, because the UI renders a sentence per case
  * and an open `String` would put a Java exception's `getMessage` on the screen (ADR-034).
  */
enum LogDirError:
  /** `KafkaStorageException` — the directory is offline; the broker has failed it out. */
  case Offline
  /** The broker reported an error KUI does not have a case for. `detail` is a *class name*, never
    * a message: an exception message routinely carries a path and sometimes a host. */
  case Other(exceptionClass: String)

/** One replica living in one log directory on one broker. */
final case class ReplicaInfo(
    partition: TopicPartition,
    sizeBytes: Long,
    offsetLag: Long,
    isFuture: Boolean
)

final case class LogDir private (
    path: LogDirPath,
    error: Option[LogDirError],
    totalBytes: Option[Long],
    usableBytes: Option[Long],
    replicas: List[ReplicaInfo]
):
  def isHealthy: Boolean = error.isEmpty
  /** The sum of the replica sizes actually reported. Distinct from `totalBytes - usableBytes`,
    * which is the *filesystem's* view and includes everything that is not Kafka. */
  def usedByKafkaBytes: Long = replicas.map(_.sizeBytes).sum
  /** Only the current replicas. A future replica is a second copy being moved in and counting it
    * would double-count the partition (`docs/domain/kafka-glossary.md` §1 "Log directory"). */
  def currentReplicas: List[ReplicaInfo] = replicas.filterNot(_.isFuture)

object LogDir:
  /** Fails on a negative size or lag, or on `usableBytes > totalBytes`. */
  def from(
      path: LogDirPath, error: Option[LogDirError],
      totalBytes: Option[Long], usableBytes: Option[Long], replicas: List[ReplicaInfo]
  ): Either[DomainError, LogDir]

/** Where a broker configuration value came from. Kafka's own `ConfigSource` enum, named the way
  * the glossary names it, so the UI can say "default" rather than "DEFAULT_CONFIG".
  */
enum ConfigSource:
  case DynamicBroker, DynamicDefaultBroker, StaticBroker, DynamicTopic, Default, Unknown

final case class ConfigSynonym(name: String, value: Option[String], source: ConfigSource)

/** One entry of a broker's configuration, as `describeConfigs` reports it.
  *
  * `value` is `Option` and **must be `None` for a sensitive entry**: Kafka returns `null` for
  * `sensitive` values and KUI never invents one. `documentation` is `Option` because
  * `includeDocumentation` needs a 2.6 broker (`research/kafka/admin-capabilities.md` §0).
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
object ConfigEntry:
  given Ordering[ConfigEntry] = Ordering.by(_.name)

/** The optional things KUI has established it can do against one cluster.
  *
  * A closed enum, probed at connect time and re-probed hourly (ADR-030, ADR-016), never inferred
  * from a version number: managed services advertise a modern version and then refuse
  * `describeConfigs` with `InvalidRequestException` (`admin-capabilities.md` §0, "Managed
  * services"). Only the M1 members are declared; M2+ adds its own in the milestone that gates on
  * them, because a member nothing reads cannot be wrong in a way anyone notices.
  */
enum ClusterFeature:
  case AuthorizedOperations   // describeCluster(includeAuthorizedOperations) — 2.3
  case ConfigDocumentation    // DescribeConfigsOptions.includeDocumentation — 2.6
  case BrokerConfigs          // describeConfigs(BROKER, id) answers at all
  case LogDirs                // describeLogDirs is neither Unsupported nor ClusterAuthorization
  case KRaftQuorum            // describeMetadataQuorum succeeds — 3.3
  case IncrementalAlterConfigs// 2.3; probed now, first used in M5

object ClusterFeature:
  /** Every member, so a caller cannot forget one when it folds. */
  val All: Set[ClusterFeature] = ClusterFeature.values.toSet
  /** The stable wire token: the enum name, lowercase-hyphenated (`authorized-operations`). It is
    * a *contract* — CLAPI-001 encodes it and CLUI reads it — so it is defined once, here. */
  extension (f: ClusterFeature) def token: String
  def fromToken(raw: String): Either[ValidationError, ClusterFeature]
  given CanEqual[ClusterFeature, ClusterFeature] = CanEqual.derived

/** What `describeCluster` reported. */
final case class ClusterDescription private (
    kafkaClusterId: Option[KafkaClusterId],
    controller: Option[Broker],
    controllerMode: ControllerMode,
    brokers: NonEmptyList[Broker],
    authorizedOperations: Option[Set[String]]
):
  def brokerCount: Int = brokers.length
  def broker(id: BrokerId): Option[Broker] = brokers.find(_.id == id)

object ClusterDescription:
  /** Fails only on a duplicate broker id. Everything else that looks wrong here is legal Kafka:
    *
    *  - `controller = None` — `describeCluster().controller()` is `null` during a failover.
    *  - a `controller` that is **not** in `brokers` — in KRaft the active controller can be a
    *    dedicated node with `process.roles=controller`, which never appears in `nodes()`.
    *  - `kafkaClusterId = None` — some managed services do not report one (ADR-031).
    *  - `authorizedOperations = None` — ACLs are disabled, or the broker predates 2.3.
    *
    * Each of those is a case the reference products got wrong at least once
    * (`admin-capabilities.md` §1), so each has a test below.
    */
  def from(
      kafkaClusterId: Option[KafkaClusterId], controller: Option[Broker],
      controllerMode: ControllerMode, brokers: NonEmptyList[Broker],
      authorizedOperations: Option[Set[String]]
  ): Either[DomainError, ClusterDescription]

/** What is known about one broker beyond its address: its disk and its share of the replicas. */
final case class BrokerLoad(
    replicas: Int,
    /** Partitions this broker leads. **Always `None` in M1** — see the task's "What M1 cannot
      * fill". Filled in M2 from the topic service's snapshot. */
    leaders: Option[Int],
    /** `(replicas - mean) / mean * 100`, rounded to one decimal, or `None` when the cluster has
      * no replicas at all. Not a metric: it is arithmetic on the replica counts above. */
    skewPercent: Option[Double],
    logDirs: List[LogDir]
):
  def totalBytes: Option[Long]     // sum, None when no directory reported one
  def usableBytes: Option[Long]
  def usedByKafkaBytes: Long
  def offlineDirs: List[LogDir] = logDirs.filterNot(_.isHealthy)

object BrokerLoad:
  /** Computes `skewPercent` for every broker from the replica counts of the whole set, so that a
    * caller cannot compute one broker's skew against a different denominator than its neighbour's.
    */
  def withSkew(perBroker: Map[BrokerId, BrokerLoad]): Map[BrokerId, BrokerLoad]

/** Cluster-wide counts that need a topic sweep. Always `None` in M1. */
final case class PartitionSummary(online: Int, offline: Int, underReplicated: Int)

/** Everything the cluster service knows about one cluster at one instant.
  *
  * This is the value the `SnapshotCell` holds (CLDOM-005). It is a *finding*, never configuration:
  * it holds a `ClusterRef` and not a `ClusterProfile`, so that no code path can reach a bootstrap
  * string or a password by starting from a snapshot. That is a deliberate structural barrier and
  * not merely a preference — it is what makes the "no secret in any response body" test of
  * CLAPI-001 an assertion about a type rather than about a code path.
  */
final case class ClusterTopology(
    cluster: ClusterRef,
    description: ClusterDescription,
    version: Option[KafkaVersion],
    quorum: Option[QuorumInfo],
    features: Set[ClusterFeature],
    load: Map[BrokerId, BrokerLoad],
    /** M2. See "What M1 cannot fill". */
    partitions: Option[PartitionSummary],
    /** M2. See "What M1 cannot fill". */
    topics: Option[Int]
):
  def brokerCount: Int = description.brokerCount
  /** Sum over every broker's directories; `None` when no broker reported a size, which is what a
    * cluster without the 3.3 `totalBytes`/`usableBytes` fields (KIP-827) looks like. */
  def totalDiskBytes: Option[Long]
  def usableDiskBytes: Option[Long]
  def offlineLogDirCount: Int
  def has(feature: ClusterFeature): Boolean = features.contains(feature)
  /** True when a broker is present whose detected version is below ADR-030's minimum. Drives the
    * banner CL-009 asks for. */
  def belowMinimumVersion: Boolean = version.exists(!_.meetsMinimum)
```

## Library coordinates

No new dependencies. `cats-core` 2.13.0 provides `NonEmptyList` and `Validated`; `libs.kernel.jvm`
provides `BrokerId`, `Host`, `Port`, `KafkaClusterId`, `TopicPartition`, `PartitionId`,
`ValidationError` and `DomainError`. Test module unchanged (`libs.testkit.jvm`, MUnit 1.3.6,
munit-scalacheck 1.3.1, ScalaCheck 1.20.0).

## Acceptance criteria

```
$ ./mill services.cluster.domain.test
Test run kui.cluster.domain.BrokerSuite finished: 0 failed, 0 ignored, 7 total
Test run kui.cluster.domain.LogDirSuite finished: 0 failed, 0 ignored, 8 total
Test run kui.cluster.domain.KafkaVersionSuite finished: 0 failed, 0 ignored, 9 total
Test run kui.cluster.domain.ClusterTopologySuite finished: 0 failed, 0 ignored, 10 total
SUCCESS

$ ./mill checkArchitecture
checkArchitecture: <n> modules, no layering violations
```

Grep assertions to run once and paste into the Implementation Report — each is a rule this task
exists to keep:

```
$ grep -rn "org.apache.kafka" services/cluster/domain/src        # (no output)
$ grep -rn "import kui.kafka"  services/cluster/domain/src        # (no output)
$ grep -rn "describeTopics\|listTopics" services/cluster/domain   # (no output)
```

## Tests required

`BrokerSuite`:
1. `rackIsNoneRatherThanBlank` — `BrokerRack.from("")` and `.from("  ")` both fail.
2. `brokersAreOrderedById`.
3. `descriptionRejectsDuplicateBrokerIds` — two brokers with id 1 fail with one `FieldError`.
4. `descriptionAcceptsAControllerThatIsNotAmongTheBrokers` — the KRaft dedicated-controller case.
   It must be `Right`. This is the test that stops someone "tightening" the invariant later.
5. `descriptionAcceptsNoController` — controller failover; `Right`, `controller == None`.
6. `descriptionAcceptsNoKafkaClusterId`.
7. `descriptionAcceptsNoAuthorizedOperations` — ACLs disabled.

`LogDirSuite`:
1. `rejectsNegativeSize`, 2. `rejectsNegativeOffsetLag`,
3. `rejectsUsableAboveTotal`,
4. `futureReplicasAreExcludedFromCurrentReplicas`,
5. `usedByKafkaSumsEveryReplicaIncludingFuture` — deliberately different from (4): the disk really
   does hold both copies during a move, so `usedByKafkaBytes` counts both while `currentReplicas`
   does not. Asserting both pins the distinction.
6. `offlineDirIsNotHealthyAndKeepsItsReplicas` — an offline directory still lists what it held.
7. `otherErrorCarriesAClassNameAndNoMessage` (property) — for any string containing `/` or `@`,
   `LogDirError.Other(...)` construction from a *message* is impossible: the only constructor
   takes a class name, and the suite asserts the rendered value matches `^[A-Za-z0-9.$]+$`.
8. `totalBytesIsNoneWhenNoDirectoryReportedOne` — the pre-3.3 broker case.

`KafkaVersionSuite`:
1–4. `parses` `"3.9-IV0"`, `"4.0"`, `"3.9.1"`, `"2.8.2"`.
5. `rejectsGarbage` — `"unknown"`, `""`, `"IV0"`.
6. `meetsMinimumBoundary` — `2.7` is below, `2.8` is not (both sides asserted).
7. `orderingIsMajorThenMinor`.
8. `sourceIsPreserved` — a version parsed from `inter.broker.protocol.version` reports
   `InterBrokerProtocol`, so the UI can say where the number came from.
9. `parseIsTotalOverArbitraryStrings` (property) — never throws; always `Left` or a `Right` whose
   `raw` is the input verbatim.

`ClusterTopologySuite`:
1. `skewIsZeroWhenReplicasAreEven` — three brokers, 10 replicas each.
2. `skewIsPositiveForTheOverloadedBrokerAndNegativeForTheOthers` — 20/5/5.
3. `skewIsNoneWhenThereAreNoReplicas` — an empty cluster does not divide by zero.
4. `skewUsesOneDenominatorForEveryBroker` — the property that `withSkew` cannot be replaced by a
   per-broker computation: the sum of `replicas` implied by the skews equals the real sum.
5. `totalDiskIsNoneWhenNoBrokerReported`, 6. `totalDiskSumsWhatWasReported`.
7. `offlineLogDirCountCountsAcrossBrokers`.
8. `partitionsAndTopicsAndLeadersAreNoneInM1` — build a topology through the fixtures and assert
   all three are `None`. This test is the executable form of the "What M1 cannot fill" table: when
   M2 fills them it must delete this test deliberately, rather than discover the fields by accident.
9. `belowMinimumVersionDrivesTheBanner` — `2.7` yes, `3.9` no, `None` no (an undetected version is
   not a warning; it is an unknown, and warning about it would fire on every managed service).
10. `topologyHoldsNoProfileAndNoSecret` (property) — generate a topology from a profile whose
    secrets are `"S3CR3T-CANARY"`; assert `topology.toString` does not contain it and that
    `ClusterTopology` has no `ClusterProfile`-typed member (asserted structurally by the fact that
    it compiles with only `ClusterRef` — state this in the test's comment).

`TopologyFixtures` (test module): `broker(id, rack)`, `description(n)`, `logDir(path, replicas)`,
`topology(ref, brokers, features)` and ScalaCheck `Arbitrary` instances for `Broker`, `LogDir` and
`ClusterTopology`. CLDOM-005, CLDOM-006, CLDOM-007 and CLADP-002's contract test all use it.

## Observability

None directly. Two constraints this task sets for the layers above:

- `ClusterFeature.token` is the string that appears in the capability report, in the OpenAPI
  document and in the `kui.cluster.features` span attribute. It is defined here so the three
  cannot drift.
- `ClusterTopology` is safe to log whole (test 10), which is what makes a "snapshot refreshed"
  debug line legal.

## Degraded behavior

The model is what *makes* the degraded behaviour expressible, and every `Option` above is load
bearing:

| Cluster condition | What the model holds |
| --- | --- |
| Controller failover in progress | `description.controller = None` — a valid topology, not an error |
| Managed service hiding broker configs | `features` lacks `BrokerConfigs`; the configs tab shows "not available on this cluster" |
| `describeLogDirs` unsupported or unauthorized | `features` lacks `LogDirs`; `load` is empty; disk cells render `—` |
| ZooKeeper-mode cluster | `controllerMode = ZooKeeper`, `quorum = None`, `features` lacks `KRaftQuorum` |
| One disk failed | that `LogDir` has `error = Some(Offline)`; the broker still renders, with a badge |
| Broker older than 2.8 | `belowMinimumVersion` is true; a banner, never a refusal |

A cluster that cannot be reached at all produces no `ClusterTopology` — that is
`SnapshotStatus.Offline` in CLDOM-005, not a half-empty topology here. Constructing a topology
with an empty broker list is impossible by type (`NonEmptyList`), which is the point: "reachable
but with zero brokers" is not a state Kafka has, and allowing it would put an empty table on the
screen where an `Unavailable` section belongs.

## Docs to update

`docs/domain/cluster.md` gains a "Topology" section: the type list above with one sentence each,
the four "legal but surprising" cases of `ClusterDescription.from`, the `ClusterFeature` table with
its probe and minimum version (copied from `admin-capabilities.md` §0 so the domain page is
self-contained), and the "what M1 cannot fill" table verbatim — an operator reading `—` in the UI
must be able to find out why in one hop.
