# STORE-005 — Store topic bootstrap: create if missing, validate if present, fail fast

- **ID:** STORE-005
- **Title:** Store topic bootstrap: create if missing, validate if present, fail fast
- **Milestone / Feature:** M1 / OT-004, ADR-042 §2
- **Owner role:** Principal Scala Engineer
- **Context / service:** `libs/config`, package `kui.config.store`
- **Size:** M
- **Dependencies / blocked by:** STORE-004, KAFKA-003 (`ConnectionProperties`), KAFKA-004 (the
  `Versions` entries and `ClientPurpose`)

## Goal (user value)

KUI creates the two topics it needs on first start and never touches them again. If a topic is
already there with settings KUI cannot work with — `cleanup.policy=delete` on a topic whose whole
design assumes compaction, or three partitions where the total order that makes concurrent edits
safe requires one — KUI refuses to start and says exactly what is wrong, which setting, what it
expected and what it found. This is a named exit criterion of the milestone.

## Scope

1. `StoreTopics`: the topic names derived from `topicPrefix`, and the exact configuration KUI
   creates and validates, as data.
2. `StoreBootstrap.ensureTopics`: describe → create what is missing → validate what exists →
   fail with `KUI-STORE-TOPIC-INCOMPATIBLE` naming topic, setting, expected and found.
3. The store's own Kafka clients: an admin client, and the property assembly shared by the
   consumer and producer STORE-006 and STORE-007 build.
4. `libs/config`'s new module edges in `build.mill`.

## Non-goals

**No replay, no producer, no consumer loop** (STORE-006, STORE-007). **`__kui_audit` is not
created** — DEVPLAN §10 D7: M1 creates and validates `__kui_config` and `__kui_files` only, and
`StoreTopics` carries the audit topic's *name and intended settings* as data marked
`createdBy = Milestone.M5` so that M5 adds a list entry rather than a new mechanism.
**No topic reconfiguration, ever**: KUI never calls `incrementalAlterConfigs` on a topic it did
not create in this same call. Someone else's retention setting is someone else's decision, and
silently changing it is how a management tool loses trust.
**`libs/kafka` is not modified.** DEVPLAN §6.5 forbids it for this area; see the cross-area
contract below.

## Design references

ADR-042 §2 ("KUI creates missing topics and validates existing ones, failing fast with a named
error when settings are incompatible") and §7 (RF 1 with one broker).
`docs/operations/metadata-store.md` §2 — the topic table and the exact settings block are the
specification; the error message in that section is the message this task produces, word for word.
ADR-006 (fs2-kafka, the raw `Admin` escape hatch), ADR-030 (2.8 minimum broker version).
`research/kafka/admin-capabilities.md` §0 and §1 for `createTopics`, `describeTopics` and
`describeConfigs` failure modes — in particular that `createTopics` races and
`TopicExistsException` is a normal outcome, not an error.
DEVPLAN §7 row "Store, integration"; exit criterion *"a pre-existing `__kui_config` with
`cleanup.policy=delete` fails startup with a message naming the topic, the setting, the expected
value and the found value"*.

## Files to create

```
libs/config/src/kui/config/store/StoreTopics.scala
libs/config/src/kui/config/store/StoreBootstrap.scala
libs/config/src/kui/config/store/StoreClients.scala
libs/config/test/src/kui/config/store/StoreTopicsSuite.scala
```

## Files to change

```
build.mill                                            (libs.config: moduleDeps + fs2-kafka; test deps)
libs/config/src/kui/config/store/StoreError.scala     (TopicIncompatible)
docs/operations/metadata-store.md                     (§2: what M1 actually creates)
```

`build.mill` edit, and nothing more (DEVPLAN §6.5's shared-file rule): inside `object config`,
add `kafka` to `moduleDeps`, add `mvn"com.github.fd4s"`-**no** — add
`mvn"org.typelevel::fs2-kafka::${Versions.fs2Kafka}"` to `mvnDeps`, add `val fs2Kafka = "4.0.0"`
to `Versions` if KAFKA-004 has not already, and add the Testcontainers test coordinates to
`object test`'s `mvnDeps` (`com.dimafeng::testcontainers-scala-munit`,
`com.dimafeng::testcontainers-scala-kafka`). Do not touch the architecture rule table — CFGOP-003
owns it, and its rule A10 already names `libs/config` as an allowed holder of a Kafka client.

## Cross-area contract with KAFKA-003 and KAFKA-004

This task needs client properties rendered from the KAFKA-001 `ClusterSecurity` ADT, and must not
duplicate that rendering (that duplication is precisely what `libs/kafka-auth` exists to prevent).
**Corrected at the M1 gate review (F-04).** This spec previously pinned a
`kui.kafka.KafkaClientFactory.baseProperties(...)` that no KAFKA task creates. The function that
does exist is KAFKA-003's, in `libs/kafka-auth`, and it is the one every client factory in the
system calls:

```scala
package kui.kafka.auth

object ConnectionProperties {
  /** Renders `security.*` / `sasl.*` / `ssl.*` plus `client.id` from the typed ADT, materializes
    * any inline keystore for the lifetime of the `Resource`, and applies the `properties`
    * override layer last. A misconfiguration is a `Left`, not an exception. */
  def resource[F[_]: Async: Files](
      connection: ClusterConnection,
      purpose: ClientPurpose,
      clientId: String
  ): Resource[F, Either[KuiError, ClientProperties]]
}
```

The store builds its `ClusterConnection` from the `kui.store.kafka.*` slice (STORE-004) exactly
as a managed cluster's is built from `kui.clusters[]`, and passes `ClientPurpose.Admin`,
`ClientPurpose.Consumer` or `ClientPurpose.Producer`. `libs/config` therefore needs a
`libs.kafkaAuth` module edge as well as the `libs.kafka` one; both are on ADR-041 A10's
allow-list.

If KAFKA-003 has not landed when this task starts, **do not** write a local renderer: the task is
blocked on it. If it landed with a different shape, use what it shipped and record the difference
in the Implementation Report; do not add an adapter layer to preserve this sketch.

`libs/config` builds its consumer, producer and admin client with **fs2-kafka directly**, from
those properties. It does not go through `libs/kafka`'s `ClusterAdmin` port: that port is
`describeCluster` and friends for a *managed* cluster, and the store needs `createTopics`,
`describeConfigs`, `assign`/`seek` and a producer — a different set for a different purpose.
Adding them to `ClusterAdmin` would put the store's needs into the port ten services share.

## Public Scala signatures to implement

```scala
package kui.config.store

import cats.effect.{Async, Resource}
import fs2.kafka.{KafkaAdminClient, KafkaConsumer, KafkaProducer}

/** Which milestone creates a topic. Data, so that M5 adds a row and not a mechanism. */
enum CreatedBy { case M1, M5 }

/** One `__kui_*` topic: its name, its partition count, and the configuration KUI creates and
  * validates. */
final case class StoreTopic(
    name: String,
    partitions: Int,
    createdBy: CreatedBy,
    /** Settings KUI sets on create **and** refuses to differ on. */
    required: Map[String, String],
    /** Settings KUI sets on create and does **not** validate afterwards. */
    advisory: Map[String, String]
)

object StoreTopics:
  def of(config: StoreConfig): StoreTopics

final case class StoreTopics(config: StoreTopic, files: StoreTopic, audit: StoreTopic):
  /** Only the topics M1 creates: `List(config, files)`. */
  def managedNow: List[StoreTopic]

/** Creates and validates. Idempotent: running it against an already-correct cluster does
  * nothing and returns. */
object StoreBootstrap:
  def ensureTopics[F[_]: Async: LoggerFactory](
      admin: KafkaAdminClient[F],
      topics: StoreTopics,
      replicationFactor: Short
  ): F[Either[StoreError, Unit]]

/** The store's clients, as resources, built from `StoreKafkaConfig`. */
object StoreClients:
  def admin[F[_]: Async](config: StoreKafkaConfig, clientId: String): Resource[F, KafkaAdminClient[F]]
  def consumer[F[_]: Async](config: StoreKafkaConfig, clientId: String): Resource[F, KafkaConsumer[F, String, Option[String]]]
  def producer[F[_]: Async](config: StoreKafkaConfig, clientId: String): Resource[F, KafkaProducer[F, String, Option[String]]]
```

New `StoreError` case:

```scala
case TopicIncompatible(topic: String, setting: String, expected: String, found: String)
  // ErrorCode.StoreTopicIncompatible
```

## Behaviour, decided precisely

### Which settings are required and which are advisory

`metadata-store.md` §2 lists a block of settings without saying which of them KUI refuses to
differ on. Decided here, and the operator page must be updated to say so:

| Topic | Setting | Class | Expected | Why this class |
| --- | --- | --- | --- | --- |
| `__kui_config`, `__kui_files` | partition count | **required** | `1` | more than one partition destroys the total order the whole concurrency design rests on (ADR-042 §3). Non-negotiable |
| both | `cleanup.policy` | **required** | `compact` | `delete` silently loses records; this is the exit criterion's named example |
| both | `retention.ms` | advisory | `-1` | harmless if an operator has set something else, because `compact` is what keeps the data |
| both | `min.insync.replicas` | **required** | `kui.store.minInSyncReplicas` | a lower value than configured means `acks=all` does not mean what the operator thinks |
| both | `delete.retention.ms` | advisory | `86400000` | affects only how long a tombstone stays visible |
| both | `min.compaction.lag.ms` | advisory | `0` | |
| both | `segment.ms` | advisory | `604800000` | |
| both | `min.cleanable.dirty.ratio` | advisory | `0.1` | |
| `__kui_files` | `max.message.bytes` | **required** | `≥ maxFileBytes + 1 MiB` | a file that cannot be produced is a runtime failure with a confusing broker-side message; better to refuse at startup. Validated as **at least**, not equal, so an operator with a larger limit passes |
| `__kui_audit` | everything | not validated in M1 | — | D7: not created yet |

Advisory settings are logged at INFO when they differ, once, naming both values. A required
setting that differs is `TopicIncompatible` and the process does not start.

### The algorithm

1. `admin.describeTopics(managedNow.map(_.name))`. `UnknownTopicOrPartitionException` for a name
   means "missing"; any other failure means the store cluster is unusable and returns
   `StoreError.Unreachable` (STORE-006 adds that case — until then, propagate the failure and let
   CLAPI-005's startup report it; do not swallow it).
2. For every missing topic: `createTopics` with `partitions`, `replicationFactor` and
   `required ++ advisory`. A concurrent `TopicExistsException` is **success** — two KUI replicas
   starting together is the normal case — and is followed by a re-describe and a validation pass,
   because the topic that now exists may not be the one this replica asked for.
3. For every existing topic: compare the partition count from `describeTopics`, then
   `describeConfigs` for the topic resource and compare each required setting. The first
   difference found, in the table's order, is returned; not all of them. Rationale: an operator
   fixes one topic setting at a time anyway, and accumulating here would mean building a second
   accumulation mechanism next to CFG-001's for a case with at most a handful of entries.
   *(This is a deliberate departure from the accumulate-everything rule that governs
   configuration loading; it is stated here so a reviewer does not read it as an oversight.)*
4. `replicationFactor` is **not** validated on an existing topic. An operator who ran KUI with
   RF 1 and later grew the cluster has a valid RF-1 topic and a `replicationFactor: 3` setting,
   and refusing to start would punish the upgrade. A mismatch logs one WARN naming both.

### The message

Exactly the shape `metadata-store.md` §2 prints, on one logical line:

```
KUI-STORE-TOPIC-INCOMPATIBLE: topic __kui_config has cleanup.policy=delete, expected compact.
KUI will not change an existing topic's configuration. Fix the topic or point
kui.store.topicPrefix at a different prefix.
```

For the partition count the setting name is the literal string `partitions`.

## Library coordinates

```
org.typelevel::fs2-kafka::4.0.0            (new on libs/config; DEPENDENCY_MATRIX.md "Runtime core")
org.apache.kafka:kafka-clients:4.3.1       (transitive, pinned by libs/kafka's override)
com.dimafeng::testcontainers-scala-munit::0.44.1     (test)
com.dimafeng::testcontainers-scala-kafka::0.44.1     (test)
org.testcontainers:testcontainers-kafka:2.0.5        (test, transitive)
```

DEPENDENCY_MATRIX.md's `fs2-kafka` row says `Modules: libs/kafka`. **Update that row's Modules
column to `libs/kafka, libs/config`** in this task's commit, with ADR-042 §5 as the reason; a
dependency without a matrix row is a PLAN §13 violation, and the row exists but is now wrong.

## Acceptance criteria

```
$ ./mill libs.config.compile
$ ./mill libs.config.test
$ ./mill checkArchitecture         # A10 allows libs/config; this is the run that proves it
$ ./mill __.checkFormat && ./mill __.fix --check
```

The integration proof is STORE-009's; this task's own integration assertions run there. What must
be green here is the compile, the pure `StoreTopicsSuite`, and `checkArchitecture` — the last one
matters because this is the commit that puts `org.apache.kafka` on `libs/config`'s classpath for
the first time, and if CFGOP-003's A10 allow-list is wrong, this is where it shows.

## Tests required

- `StoreTopicsSuite` (unit, no broker):
  - `namesDeriveFromThePrefix` — `topicPrefix: "acme_"` gives `acme_config`, `acme_files`,
    `acme_audit`.
  - `managedNowExcludesAudit` — D7, asserted, with the decision cited in the test's comment.
  - `configAndFilesAreSinglePartition`.
  - `requiredSettingsAreExactlyTheDocumentedSet` — a golden list, so that adding a setting to
    `metadata-store.md` without deciding its class fails a test.
  - `filesMaxMessageBytesExceedsMaxFileBytes` — property over `maxFileBytes`.
  - `minInsyncReplicasFollowsTheConfiguredValue`.
  - `incompatibilityMessageNamesTopicSettingExpectedAndFound` — build a `TopicIncompatible` and
    assert the rendered message contains all four, and equals the documented text for the
    `cleanup.policy` case.
- Broker-backed assertions (`createTopics` idempotence, the `cleanup.policy=delete` refusal, the
  concurrent-create race) belong to **STORE-009**, which owns the Testcontainers fixture. Do not
  start a container from `libs.config.test` before STORE-009 adds the fixture; a second,
  differently configured container in the same module is how a suite becomes slow and flaky.

## Observability

- INFO on create: `store topic created` with `topic`, `partitions`, `replicationFactor`.
- INFO on validate-ok: one line listing the topics validated, not one per topic.
- INFO per advisory difference, with `setting`, `expected`, `found`.
- WARN on a replication-factor difference.
- ERROR, once, with the full incompatibility message, immediately before the failure propagates.
- Metric `kui.store.topics.created` (counter) — registered by STORE-008 with the rest of the
  store's metrics; named here so the two tasks agree.

No log line contains a client property map: it holds the SASL password. If a property dump is
ever wanted for diagnosis, it goes through `ClientPropertyOverrides.redact` (STORE-004).

## Degraded behavior

There is none at startup, by design: a store that cannot be bootstrapped is a startup failure
(ADR-042 §8, "unreachable at startup: the service fails to start with an error naming the store's
bootstrap servers"). The one nuance a worker must get right is *which* failure: a topic KUI
cannot create because the principal lacks `Create` is a `TopicAuthorizationException`, and its
message must say so and name the ACLs of `metadata-store.md` §4.1 rather than being reported as
"unreachable" — an operator debugging the wrong layer for an hour is a real cost, and the two
cases are trivially distinguishable at the point they are caught.

## Docs to update

`docs/operations/metadata-store.md` §2: add the required/advisory column to the settings block,
say that M1 creates `__kui_config` and `__kui_files` only and that `__kui_audit` arrives with
audit in M5, and record that replication factor is not validated on an existing topic.
`DEPENDENCY_MATRIX.md`: the `fs2-kafka` row's Modules column.
