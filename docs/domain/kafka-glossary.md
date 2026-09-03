# KUI Kafka glossary (ubiquitous language)

Owner: Research Agent D (Kafka domain). Date: 2026-09-03.
Status: grooming-phase draft; every term here is the *only* name KUI uses for the concept
in code, contracts, UI copy, docs and commits (PLAN.md §25).

Conventions used below:

- **Definition** — what the thing is in Apache Kafka's own words, expanded for a newcomer.
- **Invariants** — facts that are always true; if KUI ever holds data that violates one, the
  data is wrong, not the invariant. These become `from(...)` validation rules and test cases.
- **KUI model** — how the concept is represented in Scala 3 domain modules:
  `opaque type` for identifiers and scalar quantities, `case class` (private constructor +
  smart constructor) for entities/value objects with invariants, `enum` for closed sets.
  Wire codecs live in the contract layer, never in the domain module.

Sources: Apache Kafka 4.0 `Admin` javadoc, Kafka protocol docs, KIP-122 / KIP-848 / KIP-405 /
KIP-500 / KIP-546, Confluent Schema Registry, Kafka Connect and ksqlDB REST docs, and the
reference clones in `/tmp/kui-ref` (Kafbat `fa485c2`, Kouncil `6e2fb85`, Provectus `83b5a60`).

---

## 1. Cluster topology

### Cluster
**Definition.** A set of brokers that share one metadata log and one *cluster id* (a random
22-character base64 string generated once, e.g. `MkU3OEVBNTcwNTJENDM2Qk`). Clients reach it
through *bootstrap servers* (any subset of brokers); the client then discovers the rest.
**Invariants.** Cluster id is stable for the cluster's lifetime; a KUI *cluster* (config entry)
maps to exactly one Kafka cluster. KUI's cluster *name* is a KUI-side label, not a Kafka
concept, and must be unique across the registry.
**KUI model.**
```scala
opaque type ClusterName = String           // KUI label, non-empty, url-safe
opaque type KafkaClusterId = String        // value reported by describeCluster
final case class ClusterDescription(
  id: Option[KafkaClusterId],              // None on very old brokers / some managed services
  controller: Option[Broker],              // None when no controller is known
  brokers: NonEmptyList[Broker],
  authorizedOperations: Option[Set[AclOperation]] // None when ACLs are disabled
)
```

### Broker
**Definition.** One Kafka server process. Identified by an integer `node.id`/`broker.id`, plus
host, port and optional *rack*. Holds replicas of partitions and serves clients.
**Invariants.** Broker ids are unique inside a cluster; the same id may be reused after a broker
is decommissioned (so id alone is not a permanent identity — pair it with cluster id in KUI
caches). A broker may be *fenced* (KRaft) or *offline* and still appear in metadata.
**KUI model.** `opaque type BrokerId = Int` (≥ 0); `final case class Broker(id: BrokerId,
host: Host, port: Port, rack: Option[Rack])`. Never model a broker as a bare `Int` in domain
code.

### Controller (ZooKeeper vs KRaft)
**Definition.** The broker (or dedicated node) that owns cluster metadata: partition leader
election, topic creation/deletion, ISR changes.
- *ZooKeeper mode* (Kafka ≤ 3.x): one broker wins a ZK election and becomes controller;
  `describeCluster().controller()` returns it.
- *KRaft mode* (KIP-500, default since 3.3, only mode in 4.0): a Raft quorum of
  *controller nodes* (`process.roles=controller` or `broker,controller`) replicates the
  `__cluster_metadata` log; one is the *active controller* (quorum leader), the rest are
  *voters*, and brokers are *observers*. `describeMetadataQuorum()` exposes leader id, epoch,
  high watermark, voters and observers with their replicated offsets and lag.
**Invariants.** At most one active controller at any instant. In KRaft the active controller
may not be a broker at all; `describeCluster().controller()` may return a node that is not in
`nodes()`.
**KUI model.**
```scala
enum ControllerMode { case ZooKeeper, KRaft, Unknown }
final case class QuorumInfo(leaderId: BrokerId, leaderEpoch: Long, highWatermark: Long,
  voters: List[ReplicaState], observers: List[ReplicaState])
final case class ReplicaState(replicaId: BrokerId, logEndOffset: Long,
  lastFetch: Option[Instant], lastCaughtUp: Option[Instant])
```

### Log directory (log dir)
**Definition.** A filesystem directory (`log.dirs`) on a broker where partition segments live.
`describeLogDirs` returns, per broker, per log dir: an error (`None` when healthy, otherwise
e.g. `KafkaStorageException` for an offline dir), total/usable bytes (Kafka ≥ 3.3, KIP-827),
and per-replica `size`, `offsetLag` and `isFuture` (a *future replica* is one being moved into
this dir by `alterReplicaLogDirs`).
**Invariants.** A partition replica lives in exactly one non-future log dir per broker at a
time; `offsetLag` ≥ 0.
**KUI model.** `opaque type LogDirPath = String`; `final case class LogDir(path: LogDirPath,
error: Option[LogDirError], totalBytes: Option[Long], usableBytes: Option[Long],
replicas: List[ReplicaInfo])`; `ReplicaInfo(partition: TopicPartition, sizeBytes: Long,
offsetLag: Long, isFuture: Boolean)`.

### Features / metadata version
**Definition.** KRaft clusters carry named *features* with a finalized version level
(`describeFeatures`), most importantly `metadata.version` (e.g. level 22 = `4.0-IV0`).
In ZK clusters the `inter.broker.protocol.version` broker config plays the equivalent role.
**KUI model.** `final case class KafkaVersion(major: Int, minor: Int, raw: String)` derived
from either source (Kafbat `ReactiveAdminClient.java:154-196`); `enum ClusterFeature`
(what KUI can do against this cluster — see admin-capabilities §1).

---

## 2. Topics and partitions

### Topic
**Definition.** A named, append-only, partitioned log. Names are ≤ 249 chars from
`[a-zA-Z0-9._-]`, not `.` or `..`; `.` and `_` collide in metric names so brokers reject mixing
them in one *new* name. *Internal topics* start with `__` (`__consumer_offsets`,
`__transaction_state`, `__cluster_metadata`) and are hidden by default.
**Invariants.** Partition count only grows (`createPartitions`); never shrinks. Replication
factor is a *creation* parameter; changing it later is a reassignment (§Replica). A topic
has ≥ 1 partition. Deleting a topic is asynchronous — the name may still be visible for a
while and re-creating it immediately can fail with `TopicExistsException` (Kafbat retries with
delay, `TopicsService.java:193-231`).
**KUI model.**
```scala
opaque type TopicName = String             // validated as above
opaque type TopicId   = String             // UUID string, Kafka ≥ 2.8; Option in the model
final case class Topic(name: TopicName, id: Option[TopicId], internal: Boolean,
  partitions: NonEmptyList[Partition], config: TopicConfig)
// derived: replicationFactor = partitions.head.replicas.size (may differ per partition mid-reassignment)
```
`replicationFactor` is *derived*, not stored, because partitions can legitimately disagree
during reassignment; KUI reports `min`/`max` when they differ.

### Partition
**Definition.** One ordered, immutable sequence of records inside a topic, numbered
`0..n-1`. Records within a partition are totally ordered by *offset*; there is no order across
partitions.
**Invariants.** `partition ≥ 0`; `leader ∈ replicas` when a leader exists; `isr ⊆ replicas`;
`beginningOffset ≤ endOffset`; empty partition ⇔ `beginningOffset == endOffset`.
**KUI model.**
```scala
opaque type PartitionId = Int              // ≥ 0
final case class TopicPartition(topic: TopicName, partition: PartitionId)
final case class Partition private (id: PartitionId, leader: Option[BrokerId],
  replicas: NonEmptyList[BrokerId], isr: List[BrokerId], eligibleLeaderReplicas: List[BrokerId],
  lastKnownElr: List[BrokerId])
object Partition { def from(...): Either[DomainError, Partition] } // enforces isr ⊆ replicas
```

### Replica, Leader, Follower, ISR
**Definition.** Each partition is copied to *replication-factor* brokers (*replicas*). One is
the *leader* (serves all reads/writes); others are *followers* that fetch from it. The
*in-sync replicas* (ISR) are replicas caught up within `replica.lag.time.max.ms`. The
*high watermark* is the highest offset replicated to all ISR members; consumers only see
records below it. `min.insync.replicas` + `acks=all` gives the durability guarantee.
*Under-replicated partition* (URP): `isr.size < replicas.size`. *Offline partition*: no
leader (`leader == None`) — unavailable for reads and writes; AdminClient `listOffsets`
retries against such partitions until timeout instead of failing fast (Kafbat
`ReactiveAdminClient.java:653-657`), so KUI must filter them out first.
*Preferred replica*: `replicas.head`; a leader that is not the preferred one is "unbalanced".
**Invariants.** `isr ⊆ replicas`, `leader ∈ isr` when present (ELR/unclean election aside).
**KUI model.** Fields of `Partition` above; derived booleans `underReplicated`, `offline`,
`preferredLeader`.

### Replication factor change
KUI implements it as a *partition reassignment* (`alterPartitionReassignments`) computed from
the current assignment and online brokers (Kafbat `TopicsService.java:252-407`): add
least-loaded online brokers to grow, remove non-leader replicas to shrink. This is a
long-running broker-side data move; KUI must report progress via `listPartitionReassignments`.

### Compacted topic
**Definition.** `cleanup.policy=compact` keeps only the latest record per key (plus a
*tombstone* — a record with `null` value — until `delete.retention.ms` elapses). Offsets are
**not** contiguous after compaction, and `beginningOffsets()` may return 0 although the first
live record has a much higher offset (Kafbat works around it with `offsetsForTimes(0)` —
`OffsetsInfo.java:30-49`).
**Invariants.** Any offset gap is legal; "number of messages = end − begin" is only an upper
bound. Purge (`deleteRecords`) is rejected on compacted topics unless policy also includes
`delete` (`PolicyViolationException`).
**KUI model.** `enum CleanupPolicy { case Delete, Compact, CompactDelete }` parsed from the
config entry; message counts are `OffsetRange(begin, end)` with a derived `approxCount`,
never a "messages" integer.

### Tiered storage (KIP-405, GA in 3.9)
**Definition.** With `remote.storage.enable=true` older segments are moved to object storage.
Local retention (`local.retention.ms/bytes`) differs from total retention. `OffsetSpec`
gained `earliestLocal()` (first offset still on local disk) and `latestTiered()` (last
offset uploaded to remote storage). Reads of tiered data are slower and may time out.
**KUI model.** `OffsetSpec` enum below includes `EarliestLocal` and `LatestTiered`;
`TopicConfig` exposes `remoteStorageEnabled: Boolean`. Message browsing warns when a seek
lands below `earliestLocal`.

---

## 3. Records and offsets

### Record
**Definition.** One message: optional key (bytes), optional value (bytes, `null` = tombstone),
headers, timestamp, timestamp type, offset, partition, leader epoch, and — for transactional
producers — producer id / epoch / sequence. Keys route records to partitions (murmur2 hash
of key bytes by default).
**Invariants.** Offset is unique within a partition; `serializedKeySize == -1` for null key.
**KUI model.** `final case class Record(topicPartition: TopicPartition, offset: Offset,
timestamp: Instant, timestampType: TimestampType, key: Option[Bytes], value: Option[Bytes],
headers: List[Header], leaderEpoch: Option[Int])`. Deserialized views (`DecodedRecord`) live
in `kui-serde` results, not the domain module, because their payload is dynamic.

### Header
**Definition.** Ordered list of `(key: String, value: Option[Array[Byte]])`; duplicate keys
are allowed and order matters.
**KUI model.** `final case class Header(key: String, value: Option[Bytes])`; a `List`, never a
`Map`.

### Timestamp types
**Definition.** `message.timestamp.type` per topic: `CreateTime` (set by producer; may be
arbitrary/skewed) or `LogAppendTime` (set by broker on append; monotonic per partition).
`NoTimestampType` appears for pre-0.10 message formats (timestamp = -1).
**Invariants.** `offsetsForTimes` and `OffsetSpec.forTimestamp` return the *first* offset whose
timestamp ≥ target — reliable only with `LogAppendTime`; with `CreateTime` the search uses the
segment's max timestamp index and can return surprising positions.
**KUI model.** `enum TimestampType { case CreateTime, LogAppendTime, NoTimestampType }`.

### Offset
**Definition.** A 64-bit position of a record in a partition. Named positions:
- *beginning / earliest* (`beginningOffsets`, `OffsetSpec.earliest()`): lowest offset still
  retained (log start offset).
- *end / latest / log end offset (LEO)* (`endOffsets`, `OffsetSpec.latest()`): offset the
  *next* record will get, i.e. one past the last record. With `read_committed` it is the
  *last stable offset* (LSO) instead.
- *high watermark*: highest fully replicated offset; what consumers can read.
- *committed offset*: the position a consumer group has stored for a partition — by
  convention the *next offset to read*, so lag = end − committed.
- *position*: the in-memory next-fetch offset of a running consumer.
- *max timestamp* (`OffsetSpec.maxTimestamp()`): offset of the record with the largest
  timestamp.
**Invariants.** `earliest ≤ committed ≤ latest` for a healthy group; committed may be
*below* earliest after retention expiry (the consumer will reset per `auto.offset.reset`) or
*above* latest after an unclean leader election / topic recreation. A committed offset of
`-1` / absent means "never committed". `listOffsets` returning `offset < 0` means "not found"
(e.g. `forTimestamp` past the end) — Kafbat filters these (`ReactiveAdminClient.java:674`).
**KUI model.**
```scala
opaque type Offset = Long                  // ≥ 0
final case class OffsetRange(begin: Offset, end: Offset)   // begin ≤ end, end exclusive
enum OffsetSpec { case Earliest, Latest, ForTimestamp(at: Instant), MaxTimestamp,
  EarliestLocal, LatestTiered }
final case class CommittedOffset(offset: Offset, metadata: Option[String], leaderEpoch: Option[Int])
```
"Lag" and "count" are never raw `Long`s in signatures: `opaque type Lag = Long` (≥ 0 after
clamping, with the unclamped value logged).

### Transactional markers / control records
**Definition.** Transactional producers write *control batches* (COMMIT/ABORT markers) into
each partition they touched. They occupy offsets but carry no user payload. A consumer with
`isolation.level=read_committed` never sees them or aborted data and stops at the LSO; with
`read_uncommitted` (default) it sees aborted records but *still* not the markers themselves —
markers are filtered by the client. Consequence: offset gaps of size 1 after every transaction,
and `end − begin` over-counts messages.
**Invariants.** A message browse that "reads to end offset" must terminate on
`position ≥ end`, never on "received record with offset == end − 1" (Kouncil's 5-empty-polls
loop, `TopicService.java:171-185`, exists because of this).
**KUI model.** `enum IsolationLevel { case ReadUncommitted, ReadCommitted }` on every browse
request; `ProducerState(producerId, producerEpoch, lastSequence, lastTimestamp,
coordinatorEpoch: Option[Int], currentTransactionStartOffset: Option[Offset])` for
"active producers" (`describeProducers`); `TransactionDescription(transactionalId,
state: TransactionState, producerId, producerEpoch, timeoutMs, startTime, topicPartitions)`
with `enum TransactionState { Empty, Ongoing, PrepareAbort, PrepareCommit, CompleteAbort,
CompleteCommit, Dead, PrepareEpochFence, Unknown }`.

---

## 4. Consumer groups

### Consumer group
**Definition.** A named set of consumers that share the partitions of their subscribed
topics; the group *coordinator* broker stores committed offsets in `__consumer_offsets`
and drives rebalances. A group id is also used by non-consumers (Connect sink workers,
Streams apps, share groups in 4.x).
**Invariants.** Each partition of a subscribed topic is assigned to at most one member at a
time. A group "exists" if it has members *or* committed offsets; `describeConsumerGroups`
returns a `DEAD` description for unknown ids rather than failing, so existence must be checked
via `listConsumerGroups` (Kafbat `OffsetsResetService.java:66-92`). Offsets can be altered
only when the group has no active members (`EMPTY`/`DEAD`); otherwise the broker raises
`UnknownMemberIdException` / the operation is rejected.
**KUI model.** `opaque type GroupId = String`; `final case class ConsumerGroup(id: GroupId,
state: GroupState, groupType: GroupType, protocolType: Option[String], partitionAssignor:
Option[String], coordinator: Option[Broker], members: List[Member], offsets:
Map[TopicPartition, CommittedOffset], authorizedOperations: Option[Set[AclOperation]])`.

### Group states
**Definition.** *Classic* protocol (`ConsumerGroupState`): `Unknown`, `PreparingRebalance`
(join phase), `CompletingRebalance` (sync phase), `Stable`, `Dead` (no members, no offsets, or
being deleted), `Empty` (no members but offsets retained; coordinator eventually expires the
offsets after `offsets.retention.minutes`). *Consumer* protocol (KIP-848, `GroupState` in
Kafka 4.0): `Empty`, `Assigning`, `Reconciling`, `Stable`, `Dead`, `Unknown`.
**Invariants.** Offset reset allowed only in `Empty`/`Dead`; lag is meaningful in every state.
**KUI model.** One enum covering both: `enum GroupState { Unknown, PreparingRebalance,
CompletingRebalance, Assigning, Reconciling, Stable, Dead, Empty }` plus `enum GroupType {
Classic, Consumer, Share, Unknown }` (Kafka ≥ 3.8 reports the type via `listGroups`).

### Member
**Definition.** One consumer instance in a group: `memberId` (assigned by coordinator),
optional `groupInstanceId` (static membership, KIP-345), `clientId`, `host`, and its
*assignment*.
**KUI model.** `final case class Member(memberId: MemberId, instanceId: Option[GroupInstanceId],
clientId: ClientId, host: Host, assignment: Set[TopicPartition], targetAssignment:
Option[Set[TopicPartition]], memberEpoch: Option[Int])`.

### Assignment
**Definition.** The map member → set of partitions produced by the assignor (classic:
`range`, `roundrobin`, `sticky`, `cooperative-sticky`; KIP-848: server-side `uniform`, `range`).
**Invariants.** Partitions in assignments are disjoint across members; assigned partitions
need not have committed offsets yet, and committed offsets may exist for partitions nobody is
assigned (Kafbat unions both sets when computing lag, `ConsumerGroupService.java:317-337`).

### Lag
**Definition.** Per partition: `endOffset − committedOffset`. Per topic / group: the sum.
It is an estimate — end offsets and committed offsets are read at different instants, and
transactional markers inflate it slightly.
**Invariants.** Clamp negative results to 0 but flag them (`committed > end` indicates
recreation/unclean election). Partitions with no committed offset have *undefined* lag, not 0
(Kafbat treats missing as absent then sums with `orElse(0)` — KUI keeps `Option[Lag]` per
partition and only sums defined values).
**KUI model.** `final case class PartitionLag(tp: TopicPartition, committed: Option[Offset],
end: Option[Offset], lag: Option[Lag], anomaly: Option[LagAnomaly])` with `enum LagAnomaly {
CommittedAheadOfEnd, CommittedBelowBeginning, PartitionOffline }`.

### Rebalance protocols
**Definition.** How partitions are (re)distributed when membership changes.
- *Classic eager*: every rebalance revokes all partitions, then reassigns (stop-the-world).
- *Classic cooperative* (`cooperative-sticky`, KIP-429): incremental, only moved partitions
  are revoked.
- *Consumer protocol* (KIP-848, GA in 4.0): coordinator computes assignments, members
  heartbeat; no join/sync barrier, states `Assigning`/`Reconciling`.
- *Share groups* (KIP-932, early access 4.0): queue-like, records acknowledged individually.
**KUI model.** `enum RebalanceProtocol { ClassicEager, ClassicCooperative, Consumer, Share,
Unknown }` derived from `groupType` + `partitionAssignor`.

---

## 5. Security

### Principal
**Definition.** The authenticated identity of a client, written `Type:name`, almost always
`User:alice` (`User:*` = everyone). Type is `User` by default; custom `KafkaPrincipalBuilder`
implementations can add others (e.g. `Group:`).
**KUI model.** `final case class Principal(principalType: String = "User", name: String)` with
`render = s"$principalType:$name"`; wildcard `Principal.Anyone`.

### ACL
**Definition.** An *ACL binding* = `(ResourcePattern, AccessControlEntry)`:
- **ResourcePattern**: `resourceType` + `name` + `patternType`.
  - `ResourceType`: `UNKNOWN`, `ANY` (filter only), `TOPIC`, `GROUP`, `CLUSTER` (name is
    always the literal `kafka-cluster`), `TRANSACTIONAL_ID`, `DELEGATION_TOKEN`, `USER`
    (Kafka ≥ 3.x, for SCRAM credential ops).
  - `PatternType`: `LITERAL` (exact name, `*` = all), `PREFIXED` (name is a prefix; KIP-290,
    Kafka ≥ 2.0), plus filter-only `ANY` and `MATCH`.
- **AccessControlEntry**: `principal`, `host` (`*` or a literal IP — no CIDR), `operation`,
  `permissionType`.
  - `AclOperation`: `UNKNOWN`, `ANY`, `ALL`, `READ`, `WRITE`, `CREATE`, `DELETE`, `ALTER`,
    `DESCRIBE`, `CLUSTER_ACTION`, `DESCRIBE_CONFIGS`, `ALTER_CONFIGS`, `IDEMPOTENT_WRITE`,
    `CREATE_TOKENS`, `DESCRIBE_TOKENS`.
  - `AclPermissionType`: `ALLOW`, `DENY` (DENY wins over ALLOW), filter-only `ANY`/`UNKNOWN`.
**Invariants.** `ANY`/`MATCH`/`UNKNOWN` never appear in a *stored* binding — only in filters.
Implications: `READ`/`WRITE`/`DELETE`/`ALTER`/`ALTER_CONFIGS` imply `DESCRIBE`;
`ALTER_CONFIGS`… `ALL` implies everything. `describeAcls` fails with
`SecurityDisabledException` when no authorizer is configured — KUI treats that as
"ACL feature unavailable", not as an error (Kafbat `ReactiveAdminClient.java:206-214`).
**KUI model.**
```scala
enum ResourceType { Topic, Group, Cluster, TransactionalId, DelegationToken, User }
enum PatternType  { Literal, Prefixed }
enum AclOperation { All, Read, Write, Create, Delete, Alter, Describe, ClusterAction,
  DescribeConfigs, AlterConfigs, IdempotentWrite, CreateTokens, DescribeTokens }
enum PermissionType { Allow, Deny }
final case class ResourcePattern(resourceType: ResourceType, name: String, patternType: PatternType)
final case class AccessControlEntry(principal: Principal, host: HostPattern, operation: AclOperation, permission: PermissionType)
final case class AclBinding(pattern: ResourcePattern, entry: AccessControlEntry)
final case class AclFilter(resourceType: Option[ResourceType], name: Option[String], patternType: Option[PatternType | Match],
  principal: Option[Principal], host: Option[HostPattern], operation: Option[AclOperation], permission: Option[PermissionType])
```
Filters are a *separate* type from bindings so the "ANY" states cannot leak into stored data.

### Quota
**Definition.** Per-client throttling configured on a *client quota entity* — a map of entity
type → name where type ∈ `user`, `client-id`, `ip` and name is a literal or `<default>`
(represented as `null` name in the API). Quota keys: `producer_byte_rate`,
`consumer_byte_rate` (bytes/s), `request_percentage` (% of I/O+network thread time),
`controller_mutation_rate` (partition mutations/s), `connection_creation_rate` (ip only).
Values are `Double`. Precedence: `(user, client-id)` > `user` > `client-id` > defaults.
Requires Kafka ≥ 2.6 (`describeClientQuotas`, KIP-546).
**Invariants.** An entity has 0..n keys; an entity with no keys does not exist (deleting all
keys deletes it — Kafbat `ClientQuotaService.java:45-56`). `ip` cannot be combined with
`user`/`client-id`.
**KUI model.** `enum QuotaEntityType { User, ClientId, Ip }`; `final case class QuotaEntity
private (user: Option[QuotaName], clientId: Option[QuotaName], ip: Option[QuotaName])` with a
`from` that rejects the empty and `ip+other` combinations; `enum QuotaName { Default,
Named(value) }`; `enum QuotaKey { ProducerByteRate, ConsumerByteRate, RequestPercentage,
ControllerMutationRate, ConnectionCreationRate }`; `Map[QuotaKey, Double]`.

---

## 6. Configuration

### Config entry and config source
**Definition.** A `(name, value, source, isSensitive, isReadOnly, isDefault, synonyms,
type, documentation)` tuple returned by `describeConfigs` for a `ConfigResource` of type
`BROKER` (name = broker id, `""` = cluster-wide default), `TOPIC`, `BROKER_LOGGER`,
`CLIENT_METRICS` (3.7+), `GROUP` (4.0+). **Sources** (`ConfigEntry.ConfigSource`):
`DYNAMIC_TOPIC_CONFIG`, `DYNAMIC_BROKER_LOGGER_CONFIG`, `DYNAMIC_BROKER_CONFIG`,
`DYNAMIC_DEFAULT_BROKER_CONFIG`, `STATIC_BROKER_CONFIG` (server.properties),
`DEFAULT_CONFIG` (hard-coded default), `DYNAMIC_CLIENT_METRICS_CONFIG`,
`DYNAMIC_GROUP_CONFIG`, `UNKNOWN`. *Synonyms* list the same value at every precedence level
(e.g. topic `retention.ms` → broker `log.retention.ms` → `log.retention.hours`).
**Invariants.** Sensitive entries (`password`, SASL JAAS…) have `value == null` in responses
and must never be logged or echoed. `isReadOnly` entries are rejected by `alterConfigs`.
"Update topic config" semantics in Kafbat replace the *whole* dynamic set: anything with
source `DYNAMIC_TOPIC_CONFIG` missing from the new map is `DELETE`d
(`ReactiveAdminClient.java:747-763`). `incrementalAlterConfigs` needs Kafka ≥ 2.3;
`alterConfigs` is deprecated and non-incremental.
**KUI model.**
```scala
opaque type ConfigKey = String
enum ConfigSource { DynamicTopic, DynamicBrokerLogger, DynamicBroker, DynamicDefaultBroker,
  StaticBroker, Default, DynamicClientMetrics, DynamicGroup, Unknown }
enum ConfigType { Boolean, String, Int, Short, Long, Double, List, Class, Password, Unknown }
final case class ConfigEntry(name: ConfigKey, value: Option[ConfigValue], source: ConfigSource,
  sensitive: Boolean, readOnly: Boolean, isDefault: Boolean, tpe: ConfigType,
  documentation: Option[String], synonyms: List[ConfigSynonym])
enum ConfigOp { case Set(k, v), Delete(k), Append(k, v), Subtract(k, v) }  // AlterConfigOp.OpType
```
`ConfigValue` is an opaque `String` whose `toString` redacts when `sensitive`.

---

## 7. Schema Registry (Confluent-compatible)

### Subject
**Definition.** A named, versioned lineage of schemas. Naming strategies bind subjects to
topics: `TopicNameStrategy` → `<topic>-key` / `<topic>-value` (KUI default suffix `-value`,
Kafbat `ClustersProperties.java:83`), `RecordNameStrategy`, `TopicRecordNameStrategy`.
Subjects can be *soft-deleted* (hidden, restorable, versions kept) and then *permanently
deleted* (`?permanent=true`); a permanently deleted subject must be soft-deleted first
(error 40405).
**KUI model.** `opaque type Subject = String`; `enum SubjectState { Active, SoftDeleted }`.

### Schema version / schema id
**Definition.** Registering a schema under a subject yields a per-subject *version* (1, 2, …,
or `latest`) and a *global schema id* (integer; the same schema text registered under two
subjects gets the same id). Wire format: magic byte `0`, 4-byte big-endian id, payload.
Schema types: `AVRO` (default), `PROTOBUF`, `JSON`. Schemas may carry *references* to other
subject/versions and (newer registries) *metadata* and *rule sets*.
**Invariants.** Versions are immutable; ids are stable and cluster-wide (per registry). A
schema type cannot change across versions of a subject (Kafbat contract note,
`kafka-sr-api.yaml:337`).
**KUI model.** `opaque type SchemaId = Int`; `opaque type SchemaVersion = Int`;
`enum SchemaType { Avro, Protobuf, Json }`; `final case class SchemaVersionRecord(subject:
Subject, version: SchemaVersion, id: SchemaId, schemaType: SchemaType, schema: SchemaText,
references: List[SchemaReference])`.

### Compatibility
**Definition.** Per-registry (`/config`) or per-subject (`/config/{subject}`) rule checked on
register: `NONE`, `BACKWARD` (new can read data written by previous), `BACKWARD_TRANSITIVE`
(…by all previous), `FORWARD`, `FORWARD_TRANSITIVE`, `FULL`, `FULL_TRANSITIVE`. Subject
level overrides global; absent subject level ⇒ 40408 → fall back to global.
**KUI model.** `enum Compatibility { None, Backward, BackwardTransitive, Forward,
ForwardTransitive, Full, FullTransitive }`; `final case class CompatibilityCheck(isCompatible:
Boolean, messages: List[String])`.

---

## 8. Kafka Connect

### Connect cluster, connector, task
**Definition.** A Connect *cluster* is a group of *workers* (REST endpoint) sharing a
`group.id`. A *connector* is a named configuration (`connector.class`, `tasks.max`, …) of
type `source` or `sink`; the worker splits it into *tasks* (numbered `0..n-1`) spread across
workers. States (both connector and task): `UNASSIGNED`, `RUNNING`, `PAUSED`, `FAILED`
(with `trace`), `RESTARTING` (KIP-745, 3.0+), `STOPPED` (KIP-875, 3.5+, connector only —
tasks are removed). *Plugins* (`/connector-plugins`) are the installed connector classes;
`/config/validate` returns per-field validation with recommended values.
**Invariants.** Connector state and task states are independent (a `RUNNING` connector may
have all tasks `FAILED`). Task ids are only meaningful with their connector name. Stopping is
required before altering offsets (`/offsets`, KIP-875).
**KUI model.** `opaque type ConnectName = String` (KUI label); `opaque type ConnectorName =
String`; `final case class TaskId(connector: ConnectorName, task: Int)`; `enum ConnectorType
{ Source, Sink, Unknown }`; `enum ConnectorState { Unassigned, Running, Paused, Failed,
Restarting, Stopped }`; `enum TaskState { Unassigned, Running, Paused, Failed, Restarting }`;
`final case class ConnectorStatus(name, connector: WorkerState[ConnectorState], tasks:
List[WorkerState[TaskState]], tpe: ConnectorType)` where `WorkerState(state, workerId,
trace: Option[String])`.

---

## 9. ksqlDB

### Stream, table, query
**Definition.** A *stream* is an unbounded, append-only view over a topic (each record is an
event); a *table* is the latest value per key (changelog semantics). *Persistent queries*
(`CREATE … AS SELECT`) run forever server-side; *push queries* (`SELECT … EMIT CHANGES`)
stream results to one client until closed; *pull queries* return a point-in-time answer.
Statements go to `/ksql` (`application/vnd.ksql.v1+json`); queries stream from `/query`
(chunked JSON array, legacy) or `/query-stream` (HTTP/2, header object then delimited rows).
**Invariants.** Only one statement per request in KUI (Kafbat `KsqlApiClient.java:170-183`);
a push query must be terminated (`/close-query` with `queryId`) or it leaks server resources.
**KUI model.** `enum KsqlSourceKind { Stream, Table }`; `final case class KsqlSource(name,
topic: TopicName, keyFormat, valueFormat, isWindowed)`; `enum KsqlResponse { Header(queryId:
Option[QueryId], columns: List[KsqlColumn]) | Row(values: List[KsqlValue]) |
StatementResult(...) | Error(code, message) | Done }` streamed as `Stream[F, KsqlResponse]`.
`KsqlValue` is the one place a JSON-ish dynamic value is allowed, and it lives in the
ksql contract module, not the domain.

---

## 10. Cross-cutting KUI-side terms

| Term | Meaning in KUI |
| --- | --- |
| **Seek mode** | Where browsing starts: `Beginning`, `Latest`, `Offset(Map[TopicPartition, Offset])`, `Timestamp(Instant)` (PLAN §22). Timestamp seeks use `offsetsForTimes`; unresolved partitions fall back to end offsets in backward mode (Kafbat `SeekOperations.java:80-99`). |
| **Polling mode** | `Forward(limit)`, `Backward(limit)`, `Tailing`. Backward walks each partition in windows of `ceil(limit / partitions)` records (Kafbat `BackwardEmitter.java:20-41`). |
| **Non-empty partition** | `begin < end`; only these are assigned for non-tailing browse (`OffsetsInfo.java:17-29`). |
| **Fully polled** | `position(tp) ≥ end(tp)` for every assigned partition — the termination condition for "read to end". |
| **Event tracking** | Kouncil feature: correlate records across topics by key/header value inside a time window (`consdata/kouncil-backend/.../track/*`). |
| **Topic analysis** | Full scan of a topic computing counts, size percentiles (DataSketches quantiles), distinct key/value estimates (HLL), hourly histogram (Kafbat `TopicAnalysisStats.java`). |
| **Capability** | A `ClusterFeature` KUI has verified it can perform on a cluster (see `research/kafka/admin-capabilities.md` §1). |

---

## Naming rules for code

- Use Kafka's noun exactly: `Broker` (not Node/Server), `Partition`, `Replica`, `Isr`,
  `ConsumerGroup`, `Member`, `Subject`, `Connector`, `Task`, `Lag`, `Offset`.
- Suffix `Id` only for opaque identifiers (`BrokerId`, `GroupId`, `SchemaId`); do not use
  `Id` for names (`TopicName`, `ConnectorName`, `Subject`).
- `*Spec` = a request to compute/change something (`OffsetSpec`, `ResetSpec`); `*Description`
  = a snapshot returned by Kafka; `*Filter` = a query with optional/ANY fields.
- Enum cases are PascalCase in Scala (`ReadCommitted`); wire form is Kafka's own string
  (`read_committed`, `PREPARING_REBALANCE`) via explicit codecs in the contract layer.
