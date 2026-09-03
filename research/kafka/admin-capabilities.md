# Kafka admin capabilities for KUI

**Title:** Kafka AdminClient / consumer / Schema Registry / Connect / ksqlDB capability map
**Agent:** Research Agent D (Kafka domain)
**Date:** 2026-09-03

## Questions

1. For every KUI operation in the PLAN §15 service catalog, which Kafka client API implements
   it, what broker version it needs, what it can throw, and what the reference projects had
   to work around.
2. Which consumer-side primitives message browsing and topic analysis rest on, and what their
   semantics are on compacted / transactional / offline partitions.
3. Which external REST APIs (Schema Registry, Connect, ksqlDB) KUI must speak, and their
   shape.
4. How KUI's ports (`kui-kafka-admin`, `kui-kafka-consumer`, …) should be shaped so services
   never touch `org.apache.kafka.*` directly.

## Method and sources

- Local clones (`/tmp/kui-ref`): Kafbat `fa485c2bd45cac713cd994c62bc2d458abd3f328`
  (2026-09-03), Kouncil `6e2fb85e6ceac813c39f762eecd2f4bce1b31faf` (2026-08-04),
  Provectus `83b5a60cc08501b570a0c4d0b4cdfceb1b88d6b7` (2024-04-08). Kafbat pins
  `kafka-clients 7.9.5-ccs` (= Apache 3.9 line) and `datasketches-java 3.1.0`
  (`gradle/libs.versions.toml:12,19,79`).
- Apache Kafka 4.0 `Admin` javadoc
  (https://kafka.apache.org/40/javadoc/org/apache/kafka/clients/admin/Admin.html).
- fs2-kafka `main` `KafkaAdminClient.scala`
  (https://raw.githubusercontent.com/fd4s/fs2-kafka/main/modules/core/src/main/scala/fs2/kafka/KafkaAdminClient.scala).
- ksqlDB REST docs (`/ksql`, `/query-stream`), Confluent Schema Registry `Errors.java`.
- KIPs: 122 (offset reset tooling), 290 (prefixed ACLs), 345 (static membership),
  405 (tiered storage), 500 (KRaft), 546 (client quotas API), 664 (describeProducers /
  transactions), 745/875 (Connect restart/stop/offsets), 848 (new consumer protocol),
  932 (share groups).

Citations use `<ref>/<path>:<line>`; `kafbat/` = `api/src/main/java/io/kafbat/ui/`,
`kouncil/` = `consdata/kouncil-backend/src/main/java/com/consdata/kouncil/`.

---

## Findings

### 0. Cross-cutting AdminClient facts

| Fact | Detail | Evidence |
| --- | --- | --- |
| Single I/O thread | All `KafkaFuture` callbacks run on the admin client's one network thread; blocking there stalls every other request until `request.timeout.ms`. Kafbat hops to another scheduler right after every future. | `kafbat/service/ReactiveAdminClient.java:233-238` |
| Exception wrapping | `KafkaFuture` completes exceptionally with `CompletionException` *or* `ExecutionException`; the meaningful error is `getCause()`. | `:216-231` |
| Null results | Some futures resolve to `null` (e.g. missing committed offset). | `:216,565-569` |
| Timeouts | `request.timeout.ms` default 30 s (Kafbat sets 30 000 explicitly); `default.api.timeout.ms` 60 s bounds the whole retry loop. Per-call `*Options.timeoutMs` overrides. | `kafbat/service/AdminClientServiceImpl.java:23,50` |
| Client id | Give each admin client a unique `client.id` (`kui-admin-<cluster>-<seq>`) so broker logs and quotas can attribute it. | `:51-54` |
| Invalidation | Any `org.apache.kafka.common.errors.*` failure on `describeCluster` makes Kafbat close and recreate the client. KUI should treat `TimeoutException`, `SaslAuthenticationException`, `SslAuthenticationException`, `BrokerNotAvailableException` as "reconnect", the rest as request-level. | `AdminClientServiceImpl.java:65-77` |
| Batching | Large clusters time out `describeTopics` / `describeConfigs` / `describeConsumerGroups` / `listConsumerGroupOffsets` if sent as one request; Kafbat chunks by 200 topics or 50 groups with concurrency 4. | `ReactiveAdminClient.java:287-296,356-364,529-537,541-572,775-808`; `config/ClustersProperties.java:61-69` |
| Partial failure | Per-key result maps (`describeTopics().topicNameValues()`, `describeConfigs().values()`, `listOffsets().partitionResult()`) let one failed key be dropped instead of failing the batch. | `:383-419` |
| Version detection | No API returns "Kafka version". Kafbat reads broker config `inter.broker.protocol.version` (ZK) and falls back to `describeFeatures().finalizedFeatures()["metadata.version"]` mapped through a hand-maintained table (KRaft). Cached 1 h. | `:147-196`; `util/MetadataVersion.java` |
| Managed services | MSK Serverless throws `InvalidRequestException`, Azure Event Hubs throws `UnknownTopicOrPartitionException` on broker `describeConfigs`; `describeLogDirs` may be `UnsupportedVersionException`. All are downgraded to "feature unavailable". | `:316-343,421-430` |
| fs2-kafka coverage | `fs2.kafka.KafkaAdminClient` (main) wraps: alterConfigs/incrementalAlterConfigs, alterConsumerGroupOffsets, alter/describeClientQuotas, alterPartitionReassignments, alterReplicaLogDirs, create/delete/describeAcls, createPartitions, create/delete/describe/listTopics, delete/describeConsumerGroups, deleteConsumerGroupOffsets, deleteRecords, describeCluster, describeConfigs, describeFeatures/updateFeatures, describeLogDirs/describeReplicaLogDirs, describeMetadataQuorum, describeProducers, describeTransactions/listTransactions/abortTransaction/fenceProducers, listOffsets, listConsumerGroupOffsets, listConsumerGroups, listGroups, listPartitionReassignments, electLeaders, SCRAM and delegation-token ops. Still verify on the exact release KUI pins; anything missing is reachable through the escape hatch `KafkaAdminClient` exposes (raw `Admin`). | fs2-kafka source (above) |

**Capability probing (Kafbat `SupportedFeature`, `:106-131`).** KUI should compute a
`Set[ClusterFeature]` at connect time and re-check hourly:

| Feature | Probe | Min Kafka |
| --- | --- | --- |
| IncrementalAlterConfigs | version ≥ 2.3 | 2.3 |
| ConfigDocumentation | version ≥ 2.6 (`DescribeConfigsOptions.includeDocumentation`) | 2.6 |
| AuthorizedOperations | version ≥ 2.3 (`DescribeClusterOptions.includeAuthorizedOperations`) | 2.3 |
| AclManagement | `describeAcls(ANY)` succeeds; `SecurityDisabledException` / `InvalidRequestException` / `UnsupportedVersionException` ⇒ absent | 0.11 |
| AclEdit | AclManagement ∧ authorizedOperations ∋ `ALTER` or `ALL` | — |
| ClientQuotas | version ≥ 2.6 | 2.6 |
| TopicDeletion | broker config `delete.topic.enable=true` (default true) | — |
| LogDirs | `describeLogDirs` not `UnsupportedVersionException`/`ClusterAuthorizationException` | 1.0 |
| KRaftQuorum | `describeMetadataQuorum` succeeds | 3.3 |
| ProducersAndTransactions | `describeProducers` succeeds | 2.8 (KIP-664) |
| TieredStorage | topic config `remote.storage.enable` or feature `kraft.version`/`remote.log` | 3.6 |
| NewGroupProtocol | `listGroups` returns `GroupType.CONSUMER` | 3.8 / 4.0 |

Sources: `kafbat/service/FeatureService.java:7-68`.

### 1. Cluster and broker operations (`kui-cluster-service`)

| KUI op | AdminClient call(s) | Min Kafka | Errors (meaning) | Gotchas / reference workarounds |
| --- | --- | --- | --- | --- |
| Describe cluster | `describeCluster(DescribeClusterOptions.includeAuthorizedOperations(bool))` → `clusterId`, `controller`, `nodes`, `authorizedOperations` | 0.10; authorized ops 2.3 | `TimeoutException` (unreachable), `SaslAuthenticationException`, `SslAuthenticationException`, `UnsupportedVersionException` (authorized ops on old broker) | `controller()` is `null` during controller failover and may be a non-broker KRaft controller; `authorizedOperations()` is `null` when ACLs are off. Kafbat: `ReactiveAdminClient.java:432-456`, `FeatureService.java:53-60`. |
| Describe KRaft quorum | `describeMetadataQuorum()` → `QuorumInfo` (leaderId, leaderEpoch, highWatermark, voters, observers with `logEndOffset`, `lastFetchTimestamp`, `lastCaughtUpTimestamp`) | 3.3 | `UnsupportedVersionException` (ZK cluster), `ClusterAuthorizationException` | Optional fields are `OptionalLong`; lag = leader HWM − replica LEO. `:727-729`. |
| List brokers | from `describeCluster().nodes()` | 0.10 | as above | `Node.rack()` nullable. Kafbat enriches with partition distribution + JMX metrics: `service/BrokerService.java:30-41`. |
| Broker configs | `describeConfigs(List(ConfigResource(BROKER, id)), DescribeConfigsOptions.includeSynonyms(true).includeDocumentation(bool))` | 0.11; doc 2.6 | `InvalidRequestException` (MSK Serverless), `UnknownTopicOrPartitionException` (Event Hubs), `ClusterAuthorizationException` (no `DESCRIBE_CONFIGS` on cluster) | Kafbat swallows all of these into an empty map and logs at WARN for unknown ones: `:316-343`. Sensitive values are `null`. |
| Update broker config | `incrementalAlterConfigs(Map(ConfigResource(BROKER,id) → List(AlterConfigOp(SET))))` | 2.3 | `InvalidRequestException` (read-only/unknown key), `InvalidConfigurationException`, `ClusterAuthorizationException` (needs `ALTER_CONFIGS`) | `:702-706`; `BrokerService.java:61-70` maps `InvalidRequestException` → 400. Only dynamic broker configs (KIP-226 list) accept updates. |
| Log dirs | `describeLogDirs(brokerIds).allDescriptions()` → `Map[brokerId, Map[path, LogDirDescription]]` | 1.0; `totalBytes/usableBytes` 3.3 | `UnsupportedVersionException`, `ClusterAuthorizationException`, `TimeoutException` (a single slow disk stalls the request) | Kafbat returns empty on every error and separately catches `TimeoutException` at the service layer: `:421-430`, `BrokerService.java:71-88`. `LogDirDescription.error()` is per-dir (`KafkaStorageException` = offline dir). Use `describeLogDirs` per broker with a bounded parallelism rather than one call for all brokers. |
| Move replica between log dirs | `alterReplicaLogDirs(Map(TopicPartitionReplica → path))` | 1.1 | `LogDirNotFoundException`, `UnknownTopicOrPartitionException`, `ReplicaNotAvailableException`, `KafkaStorageException` | `BrokerService.java:48-59`. Move is async — poll `describeLogDirs` (`isFuture`) to show progress. |
| Cluster stats (topic/partition/URP counts, bytes) | Aggregation of `describeTopics` + `describeLogDirs` + `listOffsets`; no single API | — | — | Kafbat scrapes on a schedule (`ClustersStatisticsScheduler`, `StatisticsCache`) and serves lists from cache, then patches missing entries live (`ConsumerGroupService.java:82-123`). KUI: cache in `kui-cluster-service` with explicit `scrapedAt`. |
| Describe features | `describeFeatures().featureMetadata()` → finalized + supported version ranges | 2.7 (KIP-584) | `UnsupportedVersionException` | Used only for version detection; `updateFeatures` is out of scope (destructive). |
| Elect leaders | `electLeaders(ElectionType.PREFERRED/UNCLEAN, Set[TopicPartition])` | 2.2 (preferred), 2.4 (unclean) | `ElectionNotNeededException`, `PreferredLeaderNotAvailableException`, `EligibleLeadersNotAvailableException`, `ClusterAuthorizationException` | Not in any reference UI; candidate for M2+. |

### 2. Topic operations (`kui-topic-service`)

| KUI op | AdminClient call(s) | Min Kafka | Errors | Gotchas / workarounds |
| --- | --- | --- | --- | --- |
| List topics | `listTopics(ListTopicsOptions.listInternal(bool)).names()` (or `.listings()` for `topicId`/`isInternal`) | 0.10 | `TimeoutException` | Names only; details need `describeTopics`. Kafbat lists internal too and filters in memory: `:254-256`. |
| Describe topics | `describeTopics(names).topicNameValues()` (by id: `describeTopics(TopicCollection.ofTopicIds)`) → `TopicDescription(partitions: List[TopicPartitionInfo(leader, replicas, isr, elr, lastKnownElr)], isInternal, topicId, authorizedOperations)` | 0.10; ids 2.8; ELR 4.0 | `UnknownTopicOrPartitionException` (deleted between list and describe), `TopicAuthorizationException` (no `DESCRIBE`), `InvalidTopicException` | Kafbat drops unknown/unauthorized topics per key (`:366-374`) and chunks by 200 (`:356-364`). `leader()` is `null` for offline partitions. |
| Topic configs | `describeConfigs(List(ConfigResource(TOPIC,name)), includeSynonyms(true).includeDocumentation(doc))` | 0.11 | `UnknownTopicOrPartitionException`, `UnknownServerException` (seen on some managed services), `TopicAuthorizationException` (no `DESCRIBE_CONFIGS`) | Kafbat treats a visible topic without `DESCRIBE_CONFIGS` as "empty config", not an error: `service/TopicsService.java:164-171`; chunk 200 (`:287-296`). |
| Create topic | `createTopics(List(NewTopic(name, Optional(partitions), Optional(rf)).configs(map)))` — or `NewTopic(name, replicasAssignments)` | 0.10.1 | `TopicExistsException`, `InvalidTopicException` (bad name), `InvalidPartitionsException`, `InvalidReplicationFactorException` (rf > brokers), `InvalidConfigurationException` (bad key/value), `PolicyViolationException` (`create.topic.policy.class.name`), `TopicAuthorizationException` (no `CREATE`) | Pass `Optional.empty()` for partitions/rf to inherit broker defaults (`num.partitions`, `default.replication.factor`). `CreateTopicsOptions.validateOnly(true)` gives a dry run — KUI should expose it for the form. Kafbat: `:487-497`. |
| Delete topic | `deleteTopics(List(name)).all()` | 0.10.1 | `UnknownTopicOrPartitionException`, `TopicDeletionDisabledException` (`delete.topic.enable=false`), `TopicAuthorizationException` (no `DELETE`) | Async: topic stays listed briefly. Kafbat pre-checks `delete.topic.enable` from broker config and refuses in the UI: `TopicsService.java:442-449`. |
| Recreate (purge-by-recreate) / clone | delete → wait → `createTopics` with the same partitions/rf/dynamic configs | — | `TopicExistsException` while deletion is still in flight | Kafbat retries create with fixed delay/`maxRetries` (`TopicsService.java:193-231`). Clone = read description + `DYNAMIC_TOPIC_CONFIG` entries, create under new name (`:451-468`); it does *not* copy data. |
| Update topic config | `incrementalAlterConfigs(Map(ConfigResource(TOPIC) → SET new ∪ DELETE (dynamic − new)))`; pre-2.3 fallback `alterConfigs` (deprecated, replaces all) | 2.3 / 0.11 | `InvalidConfigurationException`, `InvalidRequestException`, `TopicAuthorizationException` (`ALTER_CONFIGS`), `PolicyViolationException` (`alter.config.policy.class.name`) | Kafbat's semantics are "replace the dynamic set": `:509-519,747-773`. `AlterConfigsOptions.validateOnly(true)` for dry-run. |
| Increase partitions | `createPartitions(Map(name → NewPartitions.increaseTo(n[, assignments])))` | 1.0 | `InvalidPartitionsException` (n ≤ current), `ReassignmentInProgressException`, `InvalidReplicaAssignmentException`, `TopicAuthorizationException` (`ALTER`) | Never shrinks. Breaks key→partition mapping for existing keys — KUI must warn. Kafbat validates n > current first: `TopicsService.java:408-440`. |
| Change replication factor | compute new assignment → `alterPartitionReassignments(Map(tp → Optional(NewPartitionReassignment(replicas))))`; monitor with `listPartitionReassignments(Set)` | 2.4 (KIP-455) | `InvalidReplicaAssignmentException`, `NoReassignmentInProgressException`, `ReassignmentInProgressException`, `ClusterAuthorizationException` (`ALTER` on cluster), `UnknownTopicOrPartitionException` | Kafbat algorithm: only online brokers; grow by least-loaded broker; shrink by removing the most-loaded non-leader replica; validates 1 ≤ rf ≤ brokers and rf ≠ current: `TopicsService.java:252-407`. Data copy is async; passing `Optional.empty()` cancels an in-flight reassignment. |
| Topic offsets / message counts | `listOffsets(Map(tp → OffsetSpec.earliest/latest/forTimestamp/maxTimestamp/earliestLocal/latestTiered))` per partition result | 2.5 (KIP-396); `maxTimestamp` 3.0; tiered specs 3.9 | `UnknownTopicOrPartitionException` (partition not yet initialised after create), `LeaderNotAvailableException` (in theory), `TopicAuthorizationException` | **Critical gotcha:** if any target partition has no leader the AdminClient retries metadata until timeout instead of failing. Kafbat filters no-leader partitions first (and skips the *whole topic* if one partition is leaderless) and chunks 200 partitions per call: `:582-684`. `offset < 0` ⇒ not found. |
| Purge (delete records) | `listOffsets(earliest)` + `listOffsets(latest)` → `deleteRecords(Map(tp → RecordsToDelete.beforeOffset(latest)))` for non-empty partitions | 0.11 | `PolicyViolationException` (compacted topic without `delete` policy), `OffsetOutOfRangeException`, `TopicAuthorizationException` (`DELETE` on topic), `NotLeaderOrFollowerException` | Kafbat: `service/MessagesService.java:132-154`, `:708-713`. Result carries per-partition new `lowWatermark`. |
| Active producers | `describeProducers(List(tp for all partitions)).all()` → `ProducerState(producerId, producerEpoch, lastSequence, lastTimestamp, coordinatorEpoch, currentTransactionStartOffset)` | 2.8 (KIP-664) | `UnsupportedVersionException`, `TopicAuthorizationException` (`READ`), `NotLeaderOrFollowerException` | Kafbat drops partitions with no producers: `:732-745`. `DescribeProducersOptions.brokerId` targets a follower. |
| Transactions | `listTransactions(ListTransactionsOptions.filterStates/filterProducerIds)`, `describeTransactions(ids)`, `abortTransaction(AbortTransactionSpec)` | 2.8 | `TransactionalIdNotFoundException`, `TransactionalIdAuthorizationException`, `TransactionCoordinatorFencedException` | Not in references; propose read-only listing in M2, abort behind RBAC in M3. |
| Topic analysis | plain consumer full scan (see §4) | — | — | — |

### 3. Consumer group operations (`kui-consumer-service`)

| KUI op | AdminClient call(s) | Min Kafka | Errors | Gotchas / workarounds |
| --- | --- | --- | --- | --- |
| List groups | `listConsumerGroups(ListConsumerGroupsOptions.inGroupStates(Set).withTypes(Set))` → `ConsumerGroupListing(groupId, isSimple, state: Optional, type: Optional)`; 4.0: `listGroups(ListGroupsOptions)` covers classic + consumer + share groups | 0.10.1; state filter 2.6; type 3.8 | `TimeoutException`, `CoordinatorNotAvailableException` (one coordinator down ⇒ partial listing) | Listing is per coordinator broker; a down broker hides its groups. Kafbat filters state in memory (`ConsumerGroupService.java:181-192`) and paginates after listing (`:125-150`). `state()` is `Optional` — treat missing as `Unknown`. |
| Describe groups | `describeConsumerGroups(ids, DescribeConsumerGroupsOptions.includeAuthorizedOperations(bool)).all()` → `ConsumerGroupDescription(groupId, isSimpleConsumerGroup, members, partitionAssignor, type, state/groupState, coordinator, authorizedOperations)`; 4.0 adds `describeClassicGroups`, `describeShareGroups` | 0.10.1 | `GroupAuthorizationException` (no `DESCRIBE` on group), `GroupIdNotFoundException` (only on newer brokers — older ones return a `DEAD` description) | Kafbat chunks 50 ids ×4 concurrent (`:529-537`). Existence must be confirmed with `listConsumerGroups` (`OffsetsResetService.java:66-92`). `MemberDescription.assignment()` for classic; `targetAssignment()` (4.0) for KIP-848. |
| Committed offsets | `listConsumerGroupOffsets(Map(groupId → ListConsumerGroupOffsetsSpec().topicPartitions(list or null)), ListConsumerGroupOffsetsOptions.requireStable(bool)).all()` → `Map[group, Map[tp, OffsetAndMetadata]]` | 0.10.1; multi-group 3.3 (KIP-709); `requireStable` 2.6 | `GroupAuthorizationException`, `GroupIdNotFoundException` | Value is `null` for partitions without a commit (`:562-571`). `null` partition list = all. Chunk 50 groups (`:541-572`). |
| Lag | committed (above) + `listOffsets(latest)` for the union of committed and assigned partitions | 2.5 | as above | Kafbat: `ConsumerGroupService.java:60-80,312-379`; `util/ConsumerGroupUtil.java:28-34` (`lag = end − committed`, `None` when either side missing, summed with `orElse(0)`). Kouncil uses a throw-away `KafkaConsumer.endOffsets` instead (`kouncil/consumergroup/ConsumerGroupService.java:47-51`). Reading end offsets from the periodic cluster scrape is what makes Kafbat's group list cheap (`:82-123,276-310`). |
| Groups for a topic | describe all groups → keep those with a member assigned to the topic *or* a committed offset on it | — | — | Kafbat `:205-274`; it needs cached committed offsets for `EMPTY`/`DEAD` groups because describing every group is expensive. |
| Reset offsets (earliest/latest/timestamp/explicit) | precondition: group in `EMPTY`/`DEAD` (Kafbat) or `members.isEmpty` (Kouncil); then `listOffsets(spec)` → clamp → `alterConsumerGroupOffsets(groupId, Map(tp → OffsetAndMetadata(offset))).all()` | 2.5 (KIP-396) | `UnknownMemberIdException` / `GroupNotEmptyException`-like rejection when active members exist, `GroupAuthorizationException` (`READ` on group), `GroupIdNotFoundException`, `IllegalGenerationException`, `TopicAuthorizationException` | KIP-122 semantics implemented in `kafbat/service/OffsetsResetService.java:12-127`: timestamp with no match ⇒ end offset (`:93-99`), explicit offsets clamped to `[earliest, latest]` (`:100-122`), `failOnUnknownLeader=true` so a leaderless partition aborts the reset. Kouncil equivalent: `kouncil/consumergroup/ConsumerGroupService.java:61-97` (explicit offset is *not* clamped there — KUI keeps Kafbat's clamp). Kafka's own tool also supports `shift-by`, `by-duration`, `to-current`, `from-file`; KUI should add `ShiftBy(n)` and `ByDuration(d)` as pure transformations over the same primitives. |
| Delete offsets for a topic | `listConsumerGroupOffsets` filter by topic → `deleteConsumerGroupOffsets(groupId, Set[tp]).all()` | 2.4 (KIP-496) | `GroupSubscribedToTopicException` (an active member still consumes it), `GroupIdNotFoundException`, `UnknownTopicOrPartitionException` (no committed offsets for that topic), `GroupAuthorizationException` | Kafbat `:466-485`. |
| Delete group | `deleteConsumerGroups(ids).all()` | 1.1 | `GroupNotEmptyException` (active members), `GroupIdNotFoundException`, `GroupAuthorizationException` (`DELETE` on group) | Kafbat `:458-464`; Kouncil `:58-60`. |
| Remove members | `removeMembersFromConsumerGroup(groupId, RemoveMembersFromConsumerGroupOptions(members))` | 2.4 (KIP-345) | `UnknownMemberIdException`, `GroupAuthorizationException` | Only static members (`group.instance.id`) can be removed; not in references — M3 candidate. |

### 4. Consumer-side primitives (message browsing, analysis, event tracking)

All references use a *manual-assignment* consumer (`assign` + `seek`, never `subscribe`)
with `enable.auto.commit=false`, `group.id` absent or throw-away, key/value deserializer
`BytesDeserializer`, per request, closed after use (Kafbat `emitter/RangePollingEmitter.java:27-53`;
Kouncil `topic/TopicService.java:90-120`). Pull `isolation.level` into the request.

| Primitive | Semantics | Evidence / gotchas |
| --- | --- | --- |
| `partitionsFor(topic)` | Metadata for all partitions; `leader()` may be `null`. | `OffsetsInfo.java:10-16` |
| `beginningOffsets(tps)` | Log start offset per partition. On compacted topics can be 0 while the first live record is far higher. | `OffsetsInfo.java:30-49`: Kafbat first tries `offsetsForTimes(tp → 0L)` and only falls back to `beginningOffsets` if any result is `null` (message format < 0.10) or the call throws `UnsupportedVersionException`. |
| `endOffsets(tps)` | Next offset to be written (LEO) with `read_uncommitted`; LSO with `read_committed`. | `OffsetsInfo.java:20` |
| `offsetsForTimes(Map(tp → ts))` | First offset with `timestamp ≥ ts`, `null` if none (all records older). Uses the time index; `CreateTime` topics can give odd results. | `SeekOperations.java:80-99`: for "to timestamp" (backward) an unresolved partition means "start from end"; for "from timestamp" it means "nothing to read". Kouncil computes begin/end offsets per partition from two timestamps for time-window browsing (`TopicService.java:158-166`). |
| `assign` + `seek` | Position the consumer; only assign *non-empty* partitions in forward/backward mode, all partitions in tailing. | `SeekOperations.java:14-17,45-56`; explicit offsets are clamped to `[begin, end]` (`:57-79`). |
| `poll(timeout)` | Returns 0..`max.poll.records` records; an empty poll does **not** mean end-of-partition. | Kouncil loops until 5 consecutive empty polls or `position ≥ end` (`TopicService.java:171-185`); Kafbat checks `position(tp) ≥ end(tp)` and pauses that partition (`RangePollingEmitter.java:54-79`, `OffsetsInfo.java:50-58`). |
| `pause`/`resume` | Stop fetching a partition without un-assigning — used to poll a fixed offset window across partitions. | `RangePollingEmitter.java:63-77` |
| Backward paging | For each partition, window `[max(begin, to − ceil(limit/partitions)), to)`, then move `to := from`. Never loads a full partition. | `BackwardEmitter.java:20-41` |
| Tailing | Seek to `endOffsets` of all partitions and poll forever; throttle to ~20 msg/s to the UI. | `SeekOperations.java:49`; `MessagesService.java:8` |
| Cancellation | `consumer.wakeup()` from another thread raises `WakeupException` inside `poll`; thread interruption raises `InterruptException`. Both must close the consumer. | `analyze/TopicAnalysisService.java:55,77-85`; `RangePollingEmitter.java:46-52`. In KUI: fs2-kafka `KafkaConsumer.resource` + fiber cancellation. |
| Byte/time budget | Kafbat tracks bytes per poll and throttles; the whole browse is bounded by page size, so no separate time budget in the reference. KUI adds explicit `limit`, `maxBytes`, `deadline` per PLAN §22. | `emitter/PollingThrottler.java`, `EnhancedConsumer.java:14-20` |
| Control records | Not returned by `poll` in either isolation level, but they occupy offsets ⇒ `end − begin` overstates counts and a browse must terminate on position, not on record count. | Kafka protocol; Kouncil `TopicService.java:171-173` comment |
| Produce | `KafkaProducer[Array[Byte], Array[Byte]]` per request with `ProducerRecord(topic, partition?, key?, value?, headers)`; `send` callback gives `RecordMetadata(partition, offset, timestamp)`. | `MessagesService.java:90-147`; Kouncil resend reads a range then re-produces to a target topic (`TopicService.java:233-269`). |

**Topic analysis (Kafbat `service/analyze/*`).** One background thread per
`(cluster, topic)` (`TopicAnalysisService.java:24,30`) creates a consumer with
`receive.buffer.bytes=-1` and `max.poll.records=100000` (`:48-49`), seeks every non-empty
partition to its first offset, and polls until `assignedPartitionsFullyPolled()` (`:61-73`).
Progress = `Σ(position − seekOffset) / Σ(end − begin)` (`SeekOperations.java:27-36`). Per
partition and in total it keeps (`TopicAnalysisStats.java:25-113`):
`totalMsgs`, `min/maxOffset`, `min/maxTimestamp`, `nullKeys`, `nullValues`; for key and value
size: `sum/min/max`, `avg = sum / n`, and p50/p75/p95/p99/p99.9 from an Apache DataSketches
`UpdateDoublesSketch` (`DoublesSketch.builder().build()`, default k = 128 ⇒ ≈1 % rank error);
distinct key and value estimates from `HllSketch` (default lgK = 12 ⇒ ≈1.6 % RSE) updated with
the raw bytes; and an hourly message histogram for the last 14 days keyed by
`ts − ts % 3_600_000`. The result is stored in memory until the next run. DataSketches is a
plain Java library (Apache-2.0) with no Scala wrapper needed; KUI can depend on
`org.apache.datasketches:datasketches-java` directly in `kui-topic-service` infrastructure.

### 5. ACLs and quotas (`kui-security-service`)

| KUI op | Call | Min Kafka | Errors | Notes |
| --- | --- | --- | --- | --- |
| List ACLs | `describeAcls(AclBindingFilter(ResourcePatternFilter(type,name,patternType), AccessControlEntryFilter(principal,host,op,perm))).values()` | 0.11; prefixed 2.0 | `SecurityDisabledException` (no authorizer), `ClusterAuthorizationException` (no `DESCRIBE` on cluster), `InvalidRequestException` (some managed services), `UnsupportedVersionException` | Kafbat guards every ACL call with the probed feature (`:686-700`), sorts by `toString` for stable order (`acl/AclsService.java:36-42`), filters by principal substring in memory. |
| Create ACLs | `createAcls(List[AclBinding]).all()` | 0.11 | `InvalidRequestException` (`ANY`/`UNKNOWN` in a binding, `MATCH` pattern), `ClusterAuthorizationException` (`ALTER` on cluster), `SecurityDisabledException` | Kafbat convenience builders: consumer (`READ`+`DESCRIBE` on topics/groups), producer (`WRITE`/`DESCRIBE`/`CREATE` on topics, `WRITE`/`DESCRIBE` on transactional ids, `IDEMPOTENT_WRITE` on cluster), streams app (`READ` inputs, `WRITE` outputs, `ALL` on `appId`-prefixed groups/topics): `AclsService.java:117-200`. CSV import/sync at `:49-88` (`acl/AclCsv.java`). |
| Delete ACLs | `deleteAcls(List[AclBindingFilter]).all()` → `FilterResults` with per-filter matched bindings | 0.11 | as create | A filter may delete more than one binding — KUI must preview matches (`describeAcls` with the same filter) before deleting. |
| List quotas | `describeClientQuotas(ClientQuotaFilter.all() / .contains(List(ClientQuotaFilterComponent.ofEntity(type,name) / ofDefaultEntity(type) / ofEntityType(type)))).entities()` → `Map[ClientQuotaEntity, Map[String, Double]]` | 2.6 | `UnsupportedVersionException`, `ClusterAuthorizationException` (`DESCRIBE_CONFIGS`), `InvalidRequestException` | `ClientQuotaEntity.entries()` uses `null` value for `<default>`. Kafbat `quota/ClientQuotaService.java:6-11,57-66`. |
| Upsert/delete quotas | `alterClientQuotas(List(ClientQuotaAlteration(entity, List(Op(key, value or null)))))` | 2.6 | `InvalidRequestException` (unknown key, `ip` mixed with `user`), `ClusterAuthorizationException` (`ALTER_CONFIGS`) | `null` value clears a key; clearing all keys deletes the entity. Kafbat computes ops as `clear(current − new) ∪ set(new)` and returns 201/200/204 accordingly (`:12-56`). |
| SCRAM users | `describeUserScramCredentials`, `alterUserScramCredentials` | 2.7 | `ResourceNotFoundException`, `UnsupportedSaslMechanismException` | Not in references; optional M3 for `USER` resource type. |

### 6. Schema Registry REST API (`kui-schema-service`)

Base URL per cluster, optional basic auth / mTLS (Kafbat `ClustersProperties.java:80-83`).
Content type `application/vnd.schemaregistry.v1+json`. Endpoints KUI needs (Kafbat contract
`contract/src/main/resources/swagger/kafka-sr-api.yaml:17-266`, plus the ids endpoint used by
the serde):

| Purpose | Method + path | Notes |
| --- | --- | --- |
| List subjects | `GET /subjects[?deleted=true][&subjectPrefix=]` | `deleted=true` includes soft-deleted. |
| Versions of a subject | `GET /subjects/{subject}/versions[?deleted=true]` | Returns `[1,2,…]`. 40401 subject not found. |
| Get version | `GET /subjects/{subject}/versions/{version|latest}[?deleted=true]` → `{subject, version, id, schemaType?, schema, references[]}` | 40402 version not found; `schemaType` omitted means `AVRO`. |
| Raw schema | `GET /subjects/{subject}/versions/{version}/schema` | Plain schema text. |
| Referenced-by | `GET /subjects/{subject}/versions/{version}/referencedby` | Blocks deletion when non-empty (42206). |
| Register | `POST /subjects/{subject}/versions[?normalize=true]` body `{schema, schemaType?, references?}` → `{id}` | 409 / 40901 incompatible; 422 / 42201 invalid schema. Kafbat maps 409 → `SchemaCompatibilityException`, 422 → validation error (`SchemaRegistryService.java:88-97`). |
| Lookup existing | `POST /subjects/{subject}` body `{schema,…}` → `{subject, version, id, schema}` | 40403 schema not found. |
| Delete version | `DELETE /subjects/{subject}/versions/{version}[?permanent=true]` | Soft first; permanent requires prior soft delete (40407). |
| Delete subject | `DELETE /subjects/{subject}[?permanent=true]` → `[versions]` | 40404/40405 ordering errors. Kafbat `:72-84`. |
| By id | `GET /schemas/ids/{id}[?subject=]`, `GET /schemas/ids/{id}/versions`, `GET /schemas/ids/{id}/subjects` | Used by the deserializer for the 4-byte id in the wire format. |
| Types | `GET /schemas/types` → `["JSON","PROTOBUF","AVRO"]` | Feature-detect Protobuf/JSON support. |
| Global compatibility | `GET/PUT /config` body `{compatibility}` → `{compatibilityLevel}` | 42203 invalid level. |
| Subject compatibility | `GET/PUT/DELETE /config/{subject}[?defaultToGlobal=true]` | 40408 when not set at subject level — Kafbat swallows to "use global" (`:113-128`). |
| Compatibility check | `POST /compatibility/subjects/{subject}/versions/{version|latest}[?verbose=true]` → `{is_compatible, messages[]}` | `verbose` adds reasons. |
| Mode | `GET/PUT /mode[/{subject}]` (`READWRITE`, `READONLY`, `IMPORT`) | Import mode needed to register with explicit ids; M3+. |
| Contexts | `GET /contexts` (`:.ctx:` prefixed subjects) | Newer registries; support by passing subject through untouched. |

Error envelope: `{"error_code": 40401, "message": "..."}`. Full code table (Confluent
`Errors.java`): 40401 subject, 40402 version, 40403 schema not found, 40404–40407 soft/permanent
delete ordering, 40408 no subject-level compatibility, 40409 no subject-level mode; 40901
incompatible; 42201 invalid schema, 42202 invalid version, 42203 invalid compatibility,
42204 invalid mode, 42205 operation not permitted, 42206 reference exists, 42207 id mismatch,
42208 invalid subject, 42209 schema too large; 50001 store error, 50002 timeout, 50003
forwarding failed, 50004 unknown leader. Non-Confluent registries (Apicurio in compat mode,
Karapace, AWS Glue via Provectus serde) implement subsets — treat unknown codes as
`UpstreamError(code)`.

### 7. Kafka Connect REST API (`kui-connect-service`)

Per Connect cluster base URL (+ optional basic auth). Kafbat contract
`contract/src/main/resources/swagger/kafka-connect-api.yaml:17-415`:

| Purpose | Method + path | Notes |
| --- | --- | --- |
| Worker info | `GET /` → `{version, commit, kafka_cluster_id}` | Health + which Kafka cluster it serves (KUI can validate the pairing). |
| List connectors | `GET /connectors[?expand=status&expand=info]` | With `expand` returns a map name → `{info, status}` in one call (Kafka ≥ 2.3) — use it instead of N+1. |
| Create | `POST /connectors` body `{name, config}` → 201 | 409 rebalance in progress, 400 invalid config. |
| Get / config | `GET /connectors/{name}`, `GET/PUT /connectors/{name}/config` | `PUT` creates or updates (idempotent). |
| Status | `GET /connectors/{name}/status` → `{name, connector:{state, worker_id, trace?}, tasks:[{id, state, worker_id, trace?}], type}` | States `RUNNING/FAILED/PAUSED/RESTARTING/UNASSIGNED/STOPPED` (`kafka-connect-api.yaml:518-526`). |
| Actions | `POST /connectors/{name}/restart[?includeTasks=true&onlyFailed=true]`, `PUT /connectors/{name}/pause`, `PUT /connectors/{name}/resume`, `PUT /connectors/{name}/stop` (3.5+), `DELETE /connectors/{name}` | Restart with `includeTasks` returns 202 + status (KIP-745). |
| Tasks | `GET /connectors/{name}/tasks`, `GET /connectors/{name}/tasks/{id}/status`, `POST /connectors/{name}/tasks/{id}/restart` | |
| Topics | `GET /connectors/{name}/topics`, `PUT /connectors/{name}/topics/reset` | Active topics tracking (KIP-558). |
| Offsets | `GET /connectors/{name}/offsets`, `PATCH /connectors/{name}/offsets` (connector must be `STOPPED`), `DELETE /connectors/{name}/offsets` | KIP-875, 3.5/3.6+. |
| Plugins | `GET /connector-plugins[?connectorsOnly=false]`, `GET /connector-plugins/{class}/config` (3.2+), `PUT /connector-plugins/{class}/config/validate` → `{name, error_count, groups[], configs:[{definition, value:{name, value, recommended_values[], errors[], visible}}]}` | Validation drives the connector form. |
| Cluster-level | `GET /admin/loggers`, `PUT /admin/loggers/{logger}` (3.7+ cluster-wide via `?scope=cluster`) | M3+. |

Errors: `{"error_code": 404|400|409|500, "message": "..."}`. 409 means a rebalance is in
progress — retry with backoff, do not surface as failure immediately.

### 8. ksqlDB REST API (`kui-ksql-service`)

| Endpoint | Shape | Notes |
| --- | --- | --- |
| `POST /ksql` | Body `{ksql, streamsProperties?, sessionVariables?, commandSequenceNumber?}`; `Content-Type: application/vnd.ksql.v1+json`; response: JSON array of statement results, each with `@type` (`currentStatus`, `streams`, `tables`, `queries`, `properties`, `sourceDescription`, `queryDescription`, `topics`, `functionNames`, `connectors`, …), `statementText`, `warnings[]` | Kafbat lists streams/tables through `SHOW STREAMS;`/`SHOW TABLES;` here and renders each `@type` as a table (`ksql/KsqlApiClient.java:141-168`, `ksql/response/ResponseParser.java:97+`). Some versions omit the response `Content-Type` (`:100`). Error: `{error_code (40001 BAD_STATEMENT, 40002 QUERY_ENDPOINT …), message, entities?}`. |
| `POST /query` (legacy, HTTP/1.1) | Same content type; body `{ksql, streamsProperties}`; response is a *chunked JSON array*: first element `{"header":{"queryId","schema":"`COL` TYPE, …"}}`, then `{"row":{"columns":[…]}}` elements, possibly `{"errorMessage":…}` or `{"finalMessage":…}` | Kafbat parses the `schema` string by hand (`ResponseParser.java:15-69`) and tolerates ksqlDB ≤ 0.24 closing the array badly (`KsqlApiClient.java:129-139`, ksql issue #8746). |
| `POST /query-stream` (HTTP/2) | Body `{sql, properties?, sessionVariables?}`; `Accept: application/vnd.ksqlapi.delimited.v1` (default) ⇒ first line `{"queryId"?, "columnNames":[…], "columnTypes":[…]}` then one JSON array per line; or `Accept: application/json` ⇒ one big array | Preferred for KUI (typed columns, no schema-string parsing). Requires an HTTP/2-capable client (sttp with Netty/http4s-ember). |
| `POST /close-query` | `{queryId}` | Mandatory when the browser cancels a push query. |
| `POST /inserts-stream` | `{target}` then newline-delimited row objects; acks `{"status":"ok","seq":n}` | Optional; M3. |
| `GET /info`, `GET /healthcheck`, `GET /status/{commandId}` | Server version / health / async command status | Feature detection and health. |

Kafbat restricts execution to a single statement and parses it with an ANTLR grammar to
decide SELECT vs statement and to support `DEFINE`/`UNDEFINE` variables and `PRINT`
(`KsqlApiClient.java:170-195`, `ksql/KsqlGrammar.java`). KUI should keep the "one statement
per request" rule and classify by the first keyword instead of a full grammar in M1.

---

## Decision candidates

### DC-D1: One `KafkaAdmin[F]` port per bounded context, not one global admin port
- **Decision.** Define narrow tagless-final ports in each service's `domain`/`application`
  module (`ClusterAdmin[F]`, `TopicAdmin[F]`, `GroupAdmin[F]`, `SecurityAdmin[F]`) whose
  methods speak glossary types only. A single `kui-kafka-admin` library implements all of
  them over fs2-kafka's `KafkaAdminClient[F]` (using the raw `Admin` escape hatch where
  fs2-kafka lags), shared as an infrastructure adapter.
- **Evidence.** Kafbat's single 823-line `ReactiveAdminClient` mixes 5 contexts and forces
  every service to depend on all of it (`:103-823`). fs2-kafka covers the needed surface
  (§0) so no hand-written `Admin` wrapper is required.
- **Tradeoff.** More interfaces to maintain; the adapter library becomes the one place with
  `org.apache.kafka` imports (good for the dependency rule).
- **Reversibility.** High — ports are traits; merging is mechanical.

### DC-D2: Capability set computed at connect time, re-probed on a timer, exposed in contracts
- **Decision.** `ClusterAdmin.capabilities: F[Set[ClusterFeature]]` (table in §0). Every
  mutating endpoint checks the capability first and returns
  `ApplicationError.Unsupported(feature)`; the gateway forwards it to the UI to hide/disable
  controls.
- **Evidence.** Kafbat `SupportedFeature` + `FeatureService` and the managed-service
  workarounds (`:106-131,316-343,421-430`; `FeatureService.java:7-68`).
- **Tradeoff.** One extra hourly probe per cluster; ACL probe calls `describeAcls(ANY)`
  which can be slow on clusters with thousands of ACLs — bound with a 5 s timeout.
- **Reversibility.** High.

### DC-D3: "No-leader" filtering is a port invariant, not a caller concern
- **Decision.** `TopicAdmin.listOffsets(spec, partitions, onNoLeader: Skip | Fail)` always
  describes topics first and never passes leaderless partitions to `listOffsets`.
- **Evidence.** AdminClient retries until timeout on leaderless partitions
  (`ReactiveAdminClient.java:653-657,617-651`).
- **Tradeoff.** One extra `describeTopics` round-trip per offsets call (Kafbat pays it too);
  cache descriptions for a few seconds inside the adapter.
- **Reversibility.** High.

### DC-D4: Batching and concurrency live in the adapter, configurable per cluster
- **Decision.** Adapter chunks `describeTopics`/`describeConfigs` (default 200),
  `describeConsumerGroups`/`listConsumerGroupOffsets` (default 50, parallelism 4), and
  `listOffsets` (200 partitions), using `fs2.Stream.parEvalMapUnordered`. Values are in
  `ClusterConfig.admin`.
- **Evidence.** `ClustersProperties.java:61-69`; `ReactiveAdminClient.java:775-808`.
- **Tradeoff.** Slightly more code than `Admin.describeTopics(all)`; avoids timeouts on
  10k-topic clusters.
- **Reversibility.** High.

### DC-D5: Error translation table is part of the adapter, and it is exhaustive
- **Decision.** One `KafkaErrorMapper` maps every `org.apache.kafka.common.errors.*` type in
  §1–§5 to `KuiError` (`ApplicationError.NotFound/Conflict/Forbidden/Unsupported/Invalid`,
  `InfrastructureError.Unreachable/Timeout/AuthFailed`), with a property test that the
  mapper is total over the classes listed in this document. Suppressible per-key errors
  (`UnknownTopicOrPartitionException`, `TopicAuthorizationException`) become
  `Skipped(key, reason)` entries in a `BatchResult`, never silent drops.
- **Evidence.** Kafbat `toMonoWithExceptionFilter` silently drops keys (`:383-419`); the
  PLAN §26 error envelope needs stable codes.
- **Tradeoff.** More explicit result types; the UI can show "3 topics hidden (no
  permission)".
- **Reversibility.** Medium — result shapes are in contracts.

### DC-D6: Consumer-group offset reset follows KIP-122 with clamping and an explicit precondition
- **Decision.** `GroupAdmin.resetOffsets(group, ResetSpec)` where
  `ResetSpec = ToEarliest | ToLatest | ToTimestamp(Instant) | ToOffsets(Map) | ShiftBy(Long) |
  ByDuration(Duration)`. Precondition: group state ∈ {Empty, Dead} *and* existence via
  `listConsumerGroups`; timestamp misses ⇒ end offset; explicit offsets clamped to
  `[earliest, latest]` with a `Warning` in the result; leaderless partition ⇒ fail.
- **Evidence.** `OffsetsResetService.java:12-127`; Kouncil `ConsumerGroupService.java:61-97`
  (no clamping — a foot-gun).
- **Tradeoff.** `ShiftBy`/`ByDuration` are new relative to references (cheap: pure math over
  the same offsets).
- **Reversibility.** High.

### DC-D7: Lag is `Option[Lag]` per partition with anomaly flags; sums skip undefined
- **Decision.** As in glossary `PartitionLag`. Group list uses end offsets from the cluster
  scrape cache when fresh (`scrapedAt` within TTL), else live `listOffsets`.
- **Evidence.** `ConsumerGroupUtil.java:28-34`, `ConsumerGroupService.java:60-123,276-310`.
- **Tradeoff.** UI must render "n/a" for partitions without commits instead of 0.
- **Reversibility.** High.

### DC-D8: Browse consumer contract: assign/seek, position-based termination, `read_committed` opt-in
- **Decision.** `kui-kafka-consumer` port: `resolveOffsets(topic, seekMode, isolation):
  F[Map[TopicPartition, OffsetRange]]` (uses `offsetsForTimes(0)` fallback for compacted
  topics), `pollRange(ranges): Stream[F, Record]` terminating when `position ≥ to` for every
  partition (pause/resume), never on record count. `isolation` defaults to `ReadUncommitted`
  (matches references) and the UI can switch to `ReadCommitted`.
- **Evidence.** `OffsetsInfo.java:30-58`, `RangePollingEmitter.java:54-79`, Kouncil
  `TopicService.java:171-185`.
- **Tradeoff.** Position-based termination requires `consumer.position` calls per poll
  (cheap). fs2-kafka exposes `assign`/`seek`/`position`/`pause`/`resume`, so no raw
  `KafkaConsumer` needed.
- **Reversibility.** Medium — the message-service streaming protocol depends on it.

### DC-D9: Topic analysis reuses DataSketches directly, runs as a supervised fiber with a store
- **Decision.** Depend on `datasketches-java` in `kui-topic-service` infrastructure; sketches
  live behind a `TopicAnalysisStats` domain interface (`observe(record)`, `snapshot`). One
  analysis per `(cluster, topic)`, cancellable, progress = processed/total offsets, results
  kept in memory with TTL (as Kafbat) — durable storage deferred.
- **Evidence.** `analyze/TopicAnalysisStats.java:25-113`, `TopicAnalysisService.java:24-85`.
- **Tradeoff.** A full scan of a large topic is expensive; bound it by the same byte/time
  budget as browsing and use `max.poll.records` ≈ 100k like Kafbat.
- **Reversibility.** High.

### DC-D10: External REST clients are typed sttp clients with per-upstream error ADTs
- **Decision.** `SchemaRegistryClient[F]`, `ConnectClient[F]`, `KsqlClient[F]` in their
  service's infrastructure module; each has a sealed `UpstreamError` enumerating the codes in
  §6–§8 plus `Unknown(status, code, body)`. ksqlDB uses `/query-stream` (HTTP/2) first and
  falls back to `/query` when `/info` reports a version < 0.10 or the HTTP/2 connection fails.
- **Evidence.** Kafbat's hand-parsing of the `/query` schema string (`ResponseParser.java:
  44-69`) and its issue-8746 workaround are avoidable with `/query-stream`. Connect
  `?expand=status&expand=info` removes N+1 calls.
- **Tradeoff.** HTTP/2 client requirement for ksql; two code paths until the fallback is
  removed.
- **Reversibility.** High.

### DC-D11: `kui-security-service` stays separate from `kui-cluster-service`
- **Decision.** Keep the ACL/quota context separate: it has its own feature gate
  (authorizer present, `ALTER` on cluster), its own upstream failure mode
  (`SecurityDisabledException`), and CSV import/convenience builders that do not belong to
  cluster metadata.
- **Evidence.** `acl/AclsService.java`, `quota/ClientQuotaService.java`, `FeatureService.java:48-68`.
- **Tradeoff.** One more deployable; PLAN §15 allows the merge with an ADR if this proves thin.
- **Reversibility.** Medium (ADR required either way).

## Open questions

1. Which exact fs2-kafka release KUI pins, and whether `describeMetadataQuorum` /
   `listGroups` / `describeProducers` are in that release or need the raw `Admin` escape
   hatch (verify against the tag, not `main`).
2. KIP-848 groups: does the pinned `kafka-clients` expose `GroupState` and
   `MemberDescription.targetAssignment()`? If KUI pins 3.9 clients against 4.x brokers, the
   new states must be mapped defensively.
3. Whether to support ZooKeeper-era brokers (< 2.5) at all: `listOffsets` via AdminClient
   (2.5) and `alterConsumerGroupOffsets` (2.5) are hard requirements for the reset feature;
   proposal — minimum supported broker 2.8 (KIP-664 era), document as a constraint.
4. Tiered storage UX: should browsing refuse seeks below `earliestLocal` by default?
5. Should topic analysis results persist (Kafbat: memory only) — depends on the
   `kui-config-service` storage decision.

## Confidence

**High** for AdminClient method mapping, error types and reference workarounds (read from
source and the 4.0 javadoc). **Medium** for minimum-version numbers of a few newer calls
(tiered `OffsetSpec`s, `describeShareGroups`) and for the fs2-kafka surface, which was checked
on `main` rather than a tagged release. **Medium** for the Schema Registry endpoint list
beyond what Kafbat uses (from the Confluent error-code source and prior knowledge; the
Confluent docs page could not be fetched in full).
