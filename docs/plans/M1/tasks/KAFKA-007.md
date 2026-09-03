# KAFKA-007 — `ClusterAdmin` A: `describeCluster`, nodes, version detection

- **ID:** KAFKA-007
- **Title:** `ClusterAdmin` A: `describeCluster`, nodes, version detection
- **Milestone / Feature:** M1 / CL-001, CL-002, CL-009, BR-001, OT-003
- **Owner role:** Principal Scala Engineer
- **Context / service:** `libs/kafka`
- **Size:** L
- **Dependencies / blocked by:** KAFKA-006

## Goal (user value)

KUI asks a Kafka cluster who it is and what it is made of, and gets back a cluster id, a broker
list with racks, a controller (or an honest "there isn't one right now"), and a version number
— on a KRaft cluster, on a ZooKeeper cluster, and on a managed service that answers half the
questions.

## Scope

1. The `ClusterAdmin[F]` **port trait** and the result types it returns, in `kui.kafka.admin`.
2. `describeCluster` — cluster id, nodes, controller, authorized operations.
3. `version` — the detection ADR-030 specifies: `describeFeatures`'s `metadata.version` first,
   falling back to the `inter.broker.protocol.version` broker config, falling back to
   `Unknown`. Plus the 2.8 minimum check as a *value*, not a refusal.
4. The `KafkaVersion` type, its ordering, and the `metadata.version` level table.
5. The adapter implementing exactly these methods over `AdminClientPool`.

## Non-goals

- **No broker configs, no log dirs, no quorum** — KAFKA-008. **No capability probe** —
  KAFKA-009. The trait declares those methods (so that KAFKA-008 and KAFKA-009 add
  implementations, not signatures) but this task's adapter may leave them as
  `F[Either[KuiError, ...]]` returning `ApplicationError.Unsupported("not yet implemented")`
  **only if** each is implemented within the same milestone by its own task, which it is. A
  worker who finds them still stubbed after KAFKA-008 and KAFKA-009 land has found a bug.
- **No topic count, no partition count, no `describeTopics` of any kind.** DEVPLAN §3 and risk
  R-11: a full topic sweep to fill a dashboard cell belongs to `services/topic` in M2, and
  putting it in the cluster service's refresh loop makes the one Core service the slowest one.
  Decision D5 says the dashboard renders those cells as an em dash until M2.
- No caching (KAFKA-010 and CLDOM-005), no per-cluster lifecycle (CLADP-002), no HTTP.
- No `alterBrokerConfig` or any other mutation. BR-002 is read-only in M1 (DEVPLAN §3).

## Design references

`research/kafka/admin-capabilities.md` §1, rows "Describe cluster", "List brokers" and
"Describe features", and §0 rows "Null results", "Version detection" and "Managed services" —
these are the behavioural source and they outrank `ARCHITECTURE.md` §4.2's sketch, which is a
shape, not a contract; ADR-030 (2.8 minimum, detection strategy, gate by capability not by
version); ADR-031 (`KafkaClusterId` is recorded, and is an `Option` because not every managed
service reports one); ADR-006; `ARCHITECTURE.md` §4.2 and §9; DEVPLAN §7 (the admin-adapter
suite row).

## Files to create

```
libs/kafka/src/kui/kafka/admin/ClusterAdmin.scala
libs/kafka/src/kui/kafka/admin/ClusterTypes.scala
libs/kafka/src/kui/kafka/admin/KafkaVersion.scala
libs/kafka/src/kui/kafka/admin/MetadataVersions.scala
libs/kafka/src/kui/kafka/admin/KafkaClusterAdmin.scala
libs/kafka/test/src/kui/kafka/admin/KafkaVersionSuite.scala
libs/kafka/test/src/kui/kafka/admin/MetadataVersionsSuite.scala
libs/kafka/test/src/kui/kafka/admin/KafkaClusterAdminSuite.scala
libs/kafka/test/src/kui/kafka/admin/ClusterAdminIntegrationSuite.scala
```

## Files to change

```
build.mill    # add the Testcontainers test dependencies to `libs.kafka.test`
```

```scala
    object test extends ScalaTests with KuiTests {
      def moduleDeps = super.moduleDeps ++ Seq(testkit.jvm)
      def mvnDeps = super.mvnDeps() ++ Seq(
        mvn"com.dimafeng::testcontainers-scala-munit::${Versions.testcontainers}",
        mvn"com.dimafeng::testcontainers-scala-kafka::${Versions.testcontainers}",
        mvn"org.testcontainers:kafka:${Versions.testcontainersJava}"
      )
    }
```

with `val testcontainersJava = "2.0.5"` added to `Versions` (`DEPENDENCY_MATRIX.md`,
`org.testcontainers:testcontainers-kafka` 2.0.5). Only the PLAINTEXT container is used here;
the three-mode secured topology is CFGOP-004's and lives in `libs/testkit`.

## Public Scala signatures to implement

```scala
package kui.kafka.admin

import kui.kernel.{BrokerId, KafkaClusterId}

final case class KafkaNode(id: BrokerId, host: String, port: Int, rack: Option[String])

/** What `describeCluster` reports.
  *
  * `controller` is an `Option` because Kafka returns `null` for it during a controller
  * failover, and because a KRaft controller may not be a broker at all. `kafkaClusterId` is an
  * `Option` because some managed services do not report one (ADR-031).
  * `authorizedOperations` is an `Option` because Kafka returns `null` when the cluster has no
  * authorizer configured — which means "ACLs are off", not "you may do nothing", and the two
  * must not be confused by an empty set.
  */
final case class ClusterDescription(
    kafkaClusterId: Option[KafkaClusterId],
    controller: Option[KafkaNode],
    nodes: List[KafkaNode],
    authorizedOperations: Option[Set[ClusterOperation]]
)

/** The cluster-scoped ACL operations, mapped from `org.apache.kafka.common.acl.AclOperation`
  * so that nothing above `libs/kafka` imports a Kafka enum. `Unknown` exists because a broker
  * newer than KUI can name an operation this enum does not have, and an unhandled value must
  * not be an exception. */
enum ClusterOperation {
  case Describe, DescribeConfigs, Alter, AlterConfigs, ClusterAction,
       Create, Delete, IdempotentWrite, All, Unknown
}

enum VersionSource { case Features, InterBrokerProtocol, Unknown }

final case class BrokerVersion(
    version: Option[KafkaVersion],
    /** Exactly what the broker said — `3.9-IV0`, `2.8-IV1`, a raw config value — kept so an
      * operator can see it when the parse produced nothing. */
    raw: Option[String],
    source: VersionSource
) {
  /** ADR-030's hard requirement, as a value. `None` when the version could not be detected:
    * "we could not tell" is not "too old", and refusing to serve a cluster because a managed
    * service hides its version would break clusters that work perfectly well. */
  def meetsMinimum: Option[Boolean]
}
```

```scala
package kui.kafka.admin

/** A Kafka release number, comparable. Parses `3.9`, `3.9.1`, `3.9.1-SNAPSHOT`, `2.8-IV1` and
  * `3.9-IV0`; the `-IVn` suffix is a metadata-version level, not a patch, and is dropped after
  * being recorded.
  */
final case class KafkaVersion(major: Int, minor: Int, patch: Int) extends Ordered[KafkaVersion]

object KafkaVersion {
  def parse(raw: String): Option[KafkaVersion]
  /** ADR-030's minimum. */
  val minimumSupported: KafkaVersion = KafkaVersion(2, 8, 0)
  given Ordering[KafkaVersion]
}

/** `metadata.version` levels to release numbers.
  *
  * There is no API that returns "the Kafka version". On a KRaft cluster the closest thing is
  * the finalized `metadata.version` feature level, which is an integer, and turning it into a
  * release number needs a table — the same hand-maintained table Kafbat carries
  * (`research/kafka/admin-capabilities.md` §0, "Version detection"). Ours is keyed by the level
  * integer, spans 2.8 (the ADR-030 minimum) to the pinned client's newest level, and answers
  * `None` above its highest entry rather than guessing.
  */
object MetadataVersions {
  def release(featureLevel: Short): Option[KafkaVersion]
  /** The highest level the table knows, so a log line can say "level 27 is newer than this
    * build of KUI knows about" instead of reporting an old version. */
  val highestKnownLevel: Short
  val table: Map[Short, KafkaVersion]
}
```

```scala
package kui.kafka.admin

import kui.kernel.KuiError
import kui.kernel.cluster.ClusterConnection

/** The cluster context's window onto a Kafka cluster. One narrow port, per
  * `research/kafka/admin-capabilities.md` DC-D1; the other contexts' ports arrive with the
  * services that call them and not before (DEVPLAN §3).
  *
  * The parameter is `ClusterConnection` (`libs/kernel`), not `ClusterProfile`.
  * `ARCHITECTURE.md` §4.2 writes the latter, which cannot compile: rule A5 forbids `libs/kafka`
  * from depending on a service. DEVPLAN §10 decision D1 moved the connection ADT into
  * `libs/kernel` for exactly this reason.
  */
trait ClusterAdmin[F[_]] {
  def describeCluster(connection: ClusterConnection): F[Either[KuiError, ClusterDescription]]
  def version(connection: ClusterConnection): F[Either[KuiError, BrokerVersion]]

  // Implemented by KAFKA-008
  def brokerConfigs(connection: ClusterConnection, broker: BrokerId, includeDocs: Boolean)
      : F[Either[KuiError, List[ConfigEntry]]]
  def describeLogDirs(connection: ClusterConnection, brokers: Set[BrokerId])
      : F[Either[KuiError, BatchResult[BrokerId, List[LogDir]]]]
  def describeQuorum(connection: ClusterConnection): F[Either[KuiError, Option[QuorumInfo]]]

  // Implemented by KAFKA-009
  def capabilities(connection: ClusterConnection): F[ClusterFeatures]
}

object KafkaClusterAdmin {
  def apply[F[_]: Async](pool: AdminClientPool[F]): ClusterAdmin[F]
}
```

### Behaviour `describeCluster` must have

- Issue `admin.describeCluster(new DescribeClusterOptions().includeAuthorizedOperations(true))`.
  On `UnsupportedVersionException` — a broker older than 2.3 — retry **once** without the
  option, and report `authorizedOperations = None`. This is the one retry in the whole module
  and it is a capability downgrade, not a failure retry.
- Read `clusterId`, `controller` and `nodes` as three separate futures, through
  `KafkaFutures.fromNullableFuture` for `controller` and `clusterId`.
- A controller whose id is negative (Kafka's "no controller" sentinel) is `None`, exactly like a
  `null` one.
- `Node.rack()` is nullable; an absent rack is `None`, never the empty string — the domain test
  in CLDOM-002 asserts the same thing one layer up, deliberately.
- Sort `nodes` by `BrokerId`. The broker list is rendered in this order, and an order that
  depends on the broker's response is an order that reshuffles between refreshes.

### Behaviour `version` must have

1. Call `describeFeatures`. If `finalizedFeatures` contains `metadata.version`, look its level
   up in `MetadataVersions` and return `BrokerVersion(release, raw = "level <n>", Features)`.
   A level above `highestKnownLevel` returns `version = None` with the raw level and a WARN,
   never a guess.
2. On `UnsupportedVersionException` (a broker older than 2.7) or a missing feature, fall back to
   `describeConfigs` for the controller broker — or, when there is no controller, the
   lowest-numbered node — and read `inter.broker.protocol.version`. Parse it, dropping any
   `-IVn` suffix.
3. If both fail, return `BrokerVersion(None, None, Unknown)`. This is a **success**, not an
   error: a managed service that reveals no version is not a broken cluster, and CL-009's
   version cell renders an em dash. Only a reconnect-class failure makes `version` return a
   `Left`.

## ADRs this task must obey

ADR-030 (detection order, the fallback, the 2.8 minimum as a warning rather than a refusal,
capability gating instead of version assumptions), ADR-031 (`KafkaClusterId` recorded and
optional), ADR-006, ADR-034 (a not-detectable version is not an error), ADR-039 §6 (a
`describeConfigs` refused by an ACL is an `ApplicationError` and must not dim the cluster
capability), ADR-041 A5/A10.

## Library coordinates

None new on the main classpath. Test scope adds
`com.dimafeng::testcontainers-scala-munit::0.44.1`,
`com.dimafeng::testcontainers-scala-kafka::0.44.1` and `org.testcontainers:kafka:2.0.5`, all
from `DEPENDENCY_MATRIX.md`.

## Acceptance criteria

```
$ ./mill libs.kafka.test
$ ./mill libs.kafka.compile          # clean under -Werror
```

Against the PLAINTEXT container the suite starts:

```scala
val d = admin.describeCluster(connection).unsafeRunSync().toOption.get
assert(d.kafkaClusterId.isDefined)
assertEquals(d.nodes.size, 1)
assertEquals(d.nodes.map(_.id), d.nodes.map(_.id).sorted)
assert(d.controller.isDefined)

val v = admin.version(connection).unsafeRunSync().toOption.get
assert(v.version.exists(_ >= KafkaVersion.minimumSupported))
assertEquals(v.source, VersionSource.Features)   // a 4.x KRaft container
```

And, with no broker at the configured address, within the configured `apiTimeout` and not
longer:

```scala
val e = admin.describeCluster(deadConnection).unsafeRunSync().swap.toOption.get
assert(e.isInstanceOf[InfrastructureError])
assertEquals(e.code.wire, "KUI-TIMEOUT")
```

## Tests required

- `KafkaVersionSuite` (unit + property): `parsesTheDocumentedForms` (a table: `3.9`, `3.9.1`,
  `2.8`, `2.8-IV1`, `3.9-IV0`, `4.0.0`, `3.9.1-SNAPSHOT`); `rejectsGarbageWithoutThrowing`;
  `orderingIsBySemanticFields` (property); `minimumSupportedIsTwoEight`.
- `MetadataVersionsSuite` (unit): `tableCoversTwoEightToThePinnedClient`;
  `tableIsMonotonic` — a higher level never maps to a lower release, which is the property that
  catches a mistyped row; `unknownLevelIsNoneNotAGuess`;
  `highestKnownLevelMatchesTheTable`.
- `KafkaClusterAdminSuite` (unit, with a fake `AdminClientPool` returning canned Kafka objects —
  no broker):
  - **`nullControllerIsNone`** and `negativeControllerIdIsNone` — the KRaft failover case
    DEVPLAN §7 names explicitly.
  - `nullClusterIdIsNone`.
  - `nullAuthorizedOperationsIsNoneNotAnEmptySet`.
  - `nullRackIsNone`.
  - `nodesAreSortedByBrokerId` (property).
  - `unsupportedVersionRetriesWithoutAuthorizedOperations` — asserts exactly two calls, the
    second without the option, and `authorizedOperations = None`.
  - `unknownAclOperationBecomesUnknown` rather than throwing.
  - **`versionFallsBackFromFeaturesToInterBrokerProtocol`** — `describeFeatures` throws
    `UnsupportedVersionException`, the config read succeeds, `source` is `InterBrokerProtocol`.
  - `versionIsUnknownWhenBothPathsFail` — and the result is a `Right`, not a `Left`.
  - `versionUsesTheControllerBrokerAndFallsBackToTheLowestNodeId`.
  - `aReconnectClassFailureIsALeft`.
- `ClusterAdminIntegrationSuite` (`munit` + Testcontainers, one PLAINTEXT broker, shared as a
  suite-local fixture so the container starts once):
  - `describeClusterAgainstALiveBroker` — id, one node, a controller.
  - `versionAgainstALiveBroker` — detected through `Features`, at or above 2.8.
  - `aDeadAddressTimesOutWithinTheConfiguredBound` — assert the elapsed time is under
    `apiTimeout` plus a small margin, and that the error is `KUI-TIMEOUT`. This is the test that
    keeps the dashboard's "bounded by the per-service timeout" criterion honest at the bottom of
    the stack.
  - `theClientIsInvalidatedAfterATimeoutAndTheNextCallSucceeds` — stop and restart nothing;
    point at a dead address, then at the live container, and assert recovery with no restart.

## Observability

Inherited: every call goes through `AdminClientPool.run`, so it is one span
(`kafka.admin.describeCluster`) and one `kui.kafka.admin.duration` sample with `cluster`,
`operation` and `outcome`. This task adds three log lines under `kui.kafka.admin`:

- INFO, once per detection, on `version`: the detected version, the source, and the raw string.
  This is the line an operator quotes in a bug report.
- WARN when the detected version is below `KafkaVersion.minimumSupported`: "cluster <id> reports
  Kafka <v>, below the supported minimum 2.8 (ADR-030); some features will be unavailable".
  ADR-030 requires the warning, and the cluster still works as far as it can.
- WARN when a `metadata.version` level is above `highestKnownLevel`: KUI is older than the
  cluster, which is a fact worth knowing before an unexplained missing feature is investigated.

## Degraded behavior

- **Controller absent** (failover, or a KRaft controller that is not a broker): `None`, and CL-002
  renders "electing". Not an error, not a retry.
- **Cluster id absent** (some managed services): `None`, the cell renders an em dash, and
  ADR-031's duplicate-cluster warning simply cannot be produced for that cluster.
- **Authorized operations absent** (no authorizer configured): `None`. KAFKA-009's `AclEdit`
  probe treats `None` as "cannot tell", never as "denied".
- **Version undetectable**: `BrokerVersion(None, ..., Unknown)`, a success. Every feature is then
  decided by KAFKA-009's probes, which is what ADR-030 means by "probed and gated, never
  assumed".
- **Broker unreachable**: `Left(InfrastructureError)`, bounded by `AdminTuning.apiTimeout`,
  client invalidated by the pool. `SnapshotCell` keeps serving the previous description with
  `Section.Stale` (KAFKA-010, CLDOM-005), which is what makes a dead cluster a grey row rather
  than a broken page.

## Docs to update

None. `docs/domain/cluster.md` is CLDOM's; `ARCHITECTURE.md` §4.2's sketch signatures are
replaced with links to the implementing files by CFGOP-008, from the definition of done item 11.

## Deviations

*(filled in by the implementer, in the same commit)*
