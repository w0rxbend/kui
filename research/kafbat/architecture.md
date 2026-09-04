# Kafbat Kafka UI — reference architecture

- **Title:** Kafbat Kafka UI backend/contract architecture analysis
- **Date:** 2026-09-03

## Questions

1. How is the Kafbat repository split into modules, and what does each backend package own?
2. What is the request flow from an HTTP call to a Kafka client call?
3. How are clusters registered, and how is their state cached and refreshed?
4. How does message browsing stream records to the browser (emitter design, SSE event shape, paging cursors, throttling)?
5. What is the serde plugin API and how is a serde resolved for a topic key/value?
6. How is configuration loaded, and how does the "dynamic config wizard" persist and apply changes?
7. How are authentication and RBAC implemented and enforced?
8. What is cached, where, and for how long?
9. What background pollers and schedulers exist?
10. How are errors mapped to HTTP responses, and what is the error body shape?
11. How are metrics collected (JMX / Prometheus / inferred) and exposed?
12. How are the Schema Registry, Kafka Connect and ksqlDB clients built and made resilient?
13. How is the test suite structured (Testcontainers, WireMock)?

## Method and sources

- Local shallow clone `/tmp/kui-ref/kafbat`, commit `fa485c2bd45cac713cd994c62bc2d458abd3f328` (2026-09-02), see `research/REFERENCES.md`.
- Read with `cat -n`, `grep -n`, `find`, `diff`. All citations below are `path:line` relative to the clone root. Line numbers are exact for the files quoted; ranges are approximate when marked "~".
- No web sources were used.

Correction to the previously assumed baseline: the clone targets **Java 25** (`build.gradle:11-12`, `contract-typespec/build.gradle` source/target 25), not Java 21, on **Spring Boot 3.5.16** (`gradle/libs.versions.toml:2`). The project's module inventory also lists `e2e-playwright` and `documentation` modules; both exist as directories (`ls` of the clone root) but only `contract`, `contract-typespec`, `serde-api`, `api`, `frontend` are Gradle subprojects (`settings.gradle:9-13`).

## Findings

### F1. Modules and build

| Module | Evidence | Responsibility |
| --- | --- | --- |
| `contract` | `contract/build.gradle:22-71` | Holds `src/main/resources/swagger/kafbat-ui-api.yaml` (4829 lines), `kafka-connect-api.yaml`, `kafka-sr-api.yaml`, `prometheus-query-api.yaml`. Two OpenAPI-generator tasks: `generateBackendApi` produces Spring **reactive, interface-only** server stubs with a `DTO` suffix (`contract/build.gradle:42-71`); `generateUiClient` produces a Java WebClient client for tests (`:22-40`). The same generator style is used to build **typed clients for Connect, Schema Registry and Prometheus** from their own YAMLs — those are the `KafkaConnectClientApi`, `KafkaSrClientApi`, `PrometheusClientApi` types referenced in `api/src/main/java/io/kafbat/ui/model/KafkaCluster.java:4-6`. |
| `contract-typespec` | `contract-typespec/api/main.tsp:1-15`, `contract-typespec/build.gradle` | TypeSpec sources (one `.tsp` per resource family: clusters, brokers, topics, messages, consumer-groups, schemas, kafka-connect, ksql, acls, quotas, auth, config, graphs, prometheus) compiled with `tsp` through a Node toolchain into `build/tsp/api/openapi.yaml`. A root flag `useTypeSpec` (`build.gradle:38`, default `true`) makes `contract` consume the TypeSpec output instead of the checked-in YAML (`contract/build.gradle:24-28`). The YAML is therefore a generated artefact kept in sync, and the OpenAPI style validator runs on whichever is active (`contract/build.gradle:73-89`). |
| `serde-api` | `serde-api/src/main/java/io/kafbat/ui/serde/api/*.java` | Public plugin API for custom serdes (7 types: `Serde`, `DeserializeResult`, `SchemaDescription`, `SerdeParameter`, `PropertyResolver`, `RecordHeader(s)`). No Spring or Kafka dependency in the interface (`Serde.java:27` extends only `Closeable`). |
| `api` | `api/build.gradle:34-150` | The single Spring Boot WebFlux deployable. 319 main classes under `io.kafbat.ui`. Depends on `contract` and `serde-api` (`api/build.gradle:35-36`). |
| `frontend` | `frontend/package.json:12-14,23,35,38,102-103` | React 18.2, TypeScript 5.9, Vite 6.4, TanStack react-query 5.95 + react-table 8.21, zustand 4.5, styled-components 6.3. Screens live in `frontend/src/components/{ACLPage,AuthPage,Brokers,ClusterPage,Connect,ConsumerGroups,Dashboard,KsqlDb,Schemas,Topics,...}`. |
| `e2e-playwright` | directory at clone root | Playwright E2E (replaces Provectus's Selenide suite, see `research/provectus/diff.md`). |

Key backend dependencies confirmed in `api/build.gradle`: kafka-clients (`:52`), Confluent SR client + Avro/Protobuf/JSON-Schema serializers (`:63-66`), CEL (`:77`, version 0.3.0 at `gradle/libs.versions.toml:26`), Caffeine (`:78`), ANTLR (`:79-80`, used for the ksql grammar and PromQL grammar, not for message filters), Lucene 10.3 (`:82-84`), micrometer-prometheus (`:95`), MCP SDK 0.10 (`:104`), Prometheus metrics core/textformats/pushgateway (`:116-120`), Testcontainers (`:135-137`), WireMock and okhttp MockWebServer (`:147-149`).

### F2. Backend packages (`api/src/main/java/io/kafbat/ui/`)

| Package | Contents (evidence) | Responsibility |
| --- | --- | --- |
| `client` | `RetryingKafkaConnectClient.java` | Wraps the generated Connect client with retries and error-body parsing (F12). |
| `config` | `ClustersProperties`, `Config`, `CorsGlobalConfiguration`, `CustomWebFilter`, `GeneralSecurityConfig`, `JsonSchemaConfig`, `McpConfig`, `ReadOnlyModeFilter`, `WebclientProperties` | Typed configuration binding (`kafka.*`, `webclient.*`), WebFlux filters, MCP server wiring. |
| `config/auth` (+ `azure`, `condition`, `logout`) | `AbstractAuthSecurityConfig`, `BasicAuthSecurityConfig`, `DisabledAuthSecurityConfig`, `OAuthSecurityConfig`, `LdapSecurityConfig`, `*Properties`, `Rbac*User` | One Spring Security filter chain per `auth.type` (F7). |
| `controller` | 17 controllers, each implementing a generated `*Api` interface and extending `AbstractController` | HTTP edge: resolves cluster, builds an `AccessContext`, validates RBAC, calls a service, audits. |
| `emitter` | `AbstractEmitter`, `RangePollingEmitter`, `ForwardEmitter`, `BackwardEmitter`, `TailingEmitter`, `MessagesProcessing`, `EnhancedConsumer`, `PollingThrottler`, `PollingSettings`, `SeekOperations`, `OffsetsInfo`, `Cursor`, `MessageFilters`, `ConsumingStats`, `PolledRecords` | Message browsing engine (F4). |
| `exception` | `CustomBaseException`, `ErrorCode`, `GlobalErrorWebExceptionHandler`, 22 concrete exceptions | Error model (F10). |
| `mapper` | MapStruct mappers (`ClusterMapper`, `ConsumerGroupMapper`, `DescribeLogDirsMapper`, `DynamicConfigMapper`, `KafkaConnectMapper`, `KafkaSrMapper`, `QuorumInfoMapper`) | Internal model → generated DTO mapping. |
| `model` (+ `connect`, `rbac`, `rbac/permission`, `rbac/provider`) | `KafkaCluster`, `Statistics`, `Internal*`, `ConsumerPosition`, `ClusterFeature`, RBAC `Role/Permission/Resource/Subject/AccessContext` | Internal domain records. Generated DTOs live in the `contract` output (`io.kafbat.ui.model.*DTO`). |
| `serdes` (+ `builtin`, `builtin/sr`, `builtin/mm2`) | `SerdesInitializer`, `ClusterSerdes`, `SerdeInstance`, `CustomSerdeLoader`, `ClassloaderUtil`, `ConsumerRecordDeserializer`, `ProducerRecordCreator`, `PropertyResolverImpl`, built-ins | Serde registry and resolution (F5). |
| `service` | `AdminClientService(Impl)`, `ReactiveAdminClient`, `ClustersStorage`, `KafkaClusterFactory`, `StatisticsCache`, `StatisticsService`, `ClustersStatisticsScheduler`, `TopicsService`, `MessagesService`, `ConsumerGroupService`, `SchemaRegistryService`, `KafkaConnectService`, `BrokerService`, `ClusterService`, `FeatureService`, `DeserializationService`, `OffsetsResetService`, `PollingCursorsStorage`, `KafkaConfigSanitizer`, `CsvWriterService`, `ApplicationInfoService` | Application services; one per resource family. |
| `service/acl`, `service/quota` | `AclsService`, `AclCsv`, `ClientQuotaService`, `ClientQuotaRecord` | Kafka security objects. |
| `service/analyze` | `TopicAnalysisService`, `AnalysisTasksStore`, `TopicAnalysisStats`, `TopicIdentity` | Background topic scan with DataSketches. |
| `service/app` | `ConfigReloadService` | Hot reload of RBAC roles from YAML files (F6). |
| `service/audit` | `AuditService`, `AuditWriter`, `AuditRecord` | Audit log to console and/or a Kafka topic. |
| `service/graphs` | `GraphsService`, `GraphDescriptions`, `PromQueryTemplate`, `PromQueryLangGrammar` | Pre-defined PromQL graphs proxied to a Prometheus store. |
| `service/index` (+ `lucene`) | `TopicsIndex`, `LuceneTopicsIndex`, `FilterTopicIndex`, `NgramFilter`, `*Filter` | Full-text search over topic/consumer-group/schema/connector/ACL names. |
| `service/integration/odd` | ODD exporter | Pushes metadata to OpenDataDiscovery on a schedule. |
| `service/ksql` (+ `response`) | `KsqlApiClient`, `KsqlServiceV2`, `KsqlGrammar` | ksqlDB HTTP client with statement classification. |
| `service/masking` (+ `policies`) | `DataMasking`, `MaskingPolicy`, `Mask`, `Remove`, `Replace`, `FieldsSelector` | Field-level masking of deserialized messages. |
| `service/mcp` | `McpTool` (marker), `McpSpecificationGenerator` | Exposes controllers as MCP tools. |
| `service/metrics` (+ `scrape/{jmx,prometheus,inferred}`, `sink`, `prometheus`) | `MetricsScraper`, `ScrapedClusterState`, `JmxMetricsRetriever`, `PrometheusScraper`, `InferredMetricsScraper`, `MetricsSink`, `PrometheusPushGatewaySink`, `PrometheusMetricsExposer` | Metrics pipeline (F11). |
| `service/rbac` (+ `extractor`) | `AccessControlService`, `*AuthorityExtractor` | Permission evaluation and IdP group extraction. |
| `service/ssl` | `SkipSecurityProvider`, `SkipTrustManagerFactorySpi` | Trust-all TLS support (`ssl.verify=false`). |
| `util` (+ `annotation`, `jsonschema`) | `DynamicConfigOperations`, `ApplicationRestarter`, `MultiFileWatcher`, `ReactiveFailover`, `WebClientConfigurator`, `OAuthTokenProvider/Cache`, `KafkaClientSslPropertiesUtil`, `ApplicationMetrics`, `KafkaServicesValidation`, ... | Cross-cutting helpers. |

### F3. Request flow (controller → service → Kafka client)

1. **Routing**: each controller implements a generated interface (e.g. `MessagesController implements MessagesApi, McpTool`, `api/src/main/java/io/kafbat/ui/controller/MessagesController.java:46`). Paths and parameter binding come from the OpenAPI/TypeSpec contract, so a route cannot exist without a contract entry.
2. **Cluster lookup**: `AbstractController.getCluster(name)` looks the name up in `ClustersStorage` and throws `ClusterNotFoundException` (`controller/AbstractController.java:25-29`).
3. **Authorization**: the controller builds an `AccessContext` (cluster + list of resource/action pairs + operation name), then `validateAccess(context)` (`AbstractController.java:31-33`), e.g. `MessagesController.java:56-61` for delete, `:108-118` for browsing (with a special case: reading the audit topic requires the `AUDIT.VIEW` permission instead of `TOPIC.MESSAGES_READ`).
4. **Read-only guard** runs before controllers as a `WebFilter`: any non-GET/OPTIONS request whose path matches `/api/clusters/{name}` on a cluster configured `readOnly: true` is rejected with `ReadOnlyModeException`, except the two "safe" mutation endpoints `.../smartfilters` and `.../analysis` (`config/ReadOnlyModeFilter.java:24-29,35-70`).
5. **Service call**: services are plain Spring `@Service`s returning Reactor `Mono`/`Flux`. Blocking Kafka calls are wrapped: `ReactiveAdminClient.toMono(KafkaFuture)` (`service/ReactiveAdminClient.java:218`), and anything that blocks a thread (serdes, `KafkaConsumer.poll`, JMX) is pushed to `Schedulers.boundedElastic()` (`service/MessagesService.java:259`, `service/metrics/scrape/jmx/JmxMetricsRetriever.java:52`, `controller/MessagesController.java:180`).
6. **Kafka client**: `AdminClientService.get(cluster)` returns a cached `ReactiveAdminClient` (`service/AdminClientServiceImpl.java:38-42`); consumers are created per request via `ConsumerGroupService.createConsumer` (`service/ConsumerGroupService.java:585-603`) and producers per send (`service/MessagesService.java:181,208-218`).
7. **Audit**: the controller attaches `.doOnEach(sig -> audit(context, sig))` so success and failure signals are both recorded (`MessagesController.java:67,138,156`). `AuditService.audit` extracts the user from the Reactor context's `SecurityContext` and writes to console logger and/or Kafka topic (`service/audit/AuditService.java:198-232`).
8. **Response**: DTOs from the contract are returned inside `ResponseEntity`; SSE endpoints return `ResponseEntity<Flux<TopicMessageEventDTO>>` (`MessagesController.java:96`), which the contract marks as `text/event-stream` (`contract/src/main/resources/swagger/kafbat-ui-api.yaml:816,982,1985`).
9. **Reactor context**: `CustomWebFilter` puts the `ServerWebExchange` into the Reactor context (`config/CustomWebFilter.java:11-31`) so that deep services (MCP, audit) can read the request.

Observed rule: **controllers contain the RBAC and audit boilerplate; services never check permissions**. `AccessControlService` additionally offers post-filtering helpers used by list endpoints (`filterViewableTopics`, `isConsumerGroupAccessible`, `isSchemaAccessible`, `isConnectorAccessible`, `service/rbac/AccessControlService.java:164-234`).

### F4. Cluster registration and state caching

**Registration** is static and happens at boot:

- `ClustersProperties` binds `kafka.clusters[]` (`config/ClustersProperties.java:27-33,72-106`). A `@PostConstruct` validates names (single unnamed cluster becomes `"Default"`; multiple clusters must be uniquely named), flattens nested `properties`/`consumerProperties`/`producerProperties` maps to dotted keys, and defaults `metrics.type` to `JMX` (`:294-353`).
- `ClustersStorage` builds an **immutable** `name → KafkaCluster` map once (`service/ClustersStorage.java:15-19`). There is no runtime add/remove; the config wizard restarts the process instead (F6).
- `KafkaClusterFactory.create` turns the properties into a `KafkaCluster` holding: client `Properties`, `readOnly`, masking rules, polling settings, a `MetricsScraper`, and lazily-built failover clients for Schema Registry, each Connect cluster, ksqlDB and a Prometheus store (`service/KafkaClusterFactory.java:74-114`; `model/KafkaCluster.java:19-42`). Multiple comma-separated URLs are split into a failover list (`KafkaClusterFactory.java:312-314`).
- `AdminClient`s are created lazily per cluster with `request.timeout.ms` defaulting to 30 s and a unique `client.id` (`service/AdminClientServiceImpl.java:23,44-62`); they are **invalidated and closed** when a `org.apache.kafka.common.errors.*` exception is observed during a statistics refresh (`:64-77`, called from `service/StatisticsService.java:74`).

**State cache** (`Statistics`):

- `StatisticsCache` is a `ConcurrentHashMap<clusterName, Statistics>` seeded with `Statistics.initializing()` (status `INITIALIZING`) for every cluster (`service/StatisticsCache.java:21-26`). `Statistics` carries `status` (`ONLINE|OFFLINE|INITIALIZING`), `lastKafkaException`, broker `version`, enabled `features`, `clusterDescription`, `metrics`, a `ScrapedClusterState`, per-Connect states, controller type and KRaft quorum info (`model/Statistics.java:15-27`).
- `ScrapedClusterState` snapshots **all nodes, all topics (description + configs + start/end offsets + segment stats), all consumer groups (description + committed offsets)** plus a Lucene/filter index over topic names (`service/metrics/scrape/ScrapedClusterState.java:41-76`). It is built in two phases of parallel AdminClient calls: describeLogDirs + listConsumerGroups + describeTopics + getTopicsConfig, then listOffsets(latest/earliest) + describeConsumerGroups + listConsumerGroupOffsets (`:128-150`). Batching sizes and concurrency for these bulk calls are configurable (`config/ClustersProperties.java:60-69`).
- The refresh is a **fixed-rate scheduler** (`kafka.update-metrics-rate-millis`, default 30 s) that refreshes all clusters in parallel and swallows per-cluster errors (`service/ClustersStatisticsScheduler.java:20-36`). Errors produce `Statistics.statsUpdateError(t)` = `OFFLINE` with the exception attached (`service/StatisticsService.java:75`; `model/Statistics.java:41-43`).
- **Read paths serve from this cache**: cluster list/stats/metrics (`service/ClusterService.java:26-45`), topic list/search (`service/TopicsService.java:470-471`), connector-to-topic mapping (`service/KafkaConnectService.java:370-371`), Prometheus exposition (`controller/PrometheusExposeController.java:23-31`), and feature gating (`TopicsService.java:443`).
- **Write paths update the cache incrementally**: loading specific topics refreshes only those entries (`TopicsService.java:70-80` → `StatisticsCache.update`, `StatisticsCache.java:32-51`), deleting a topic removes it (`:53-59`). Old snapshots are closed to release the Lucene index (`:44-50`; `ScrapedClusterState.java:49-54`).
- A manual "refresh cluster" endpoint re-runs the whole scrape (`ClusterService.updateCluster`, `service/ClusterService.java:47-50`).

**Feature detection** happens during each scrape: `FeatureService` decides per cluster whether Connect, ksqlDB, Schema Registry, Prometheus graphs, full-text search, ACL view/edit, quota management, topic deletion and relative timestamps are available (`service/FeatureService.java:25-100`; enum `model/ClusterFeature.java:3-15`). The UI reads this list to hide features.

### F5. Message browsing: emitter/streaming design

**API surface** (`getTopicMessagesV2`, `kafbat-ui-api.yaml:912-973`): query params `mode` (`PollingMode`: `EARLIEST|LATEST|FROM_OFFSET|TO_OFFSET|FROM_TIMESTAMP|TO_TIMESTAMP|TAILING`), `partitions[]`, `limit`, `stringFilter`, `smartFilterId`, `offset`, `timestamp`, `keySerde`, `valueSerde`, `cursor`. The v1 endpoint is kept in the contract but the implementation throws "Not supported" (`controller/MessagesController.java:78-92`).

**Event envelope** (`kafbat-ui-api.yaml:3225-3244`): every SSE event is a `TopicMessageEvent{type: PHASE|MESSAGE|CONSUMING|DONE, message?, phase?, consuming?, cursor?}`. Semantics: `PHASE` = human-readable progress text ("Consumer created", "Polling partitions: [..]"; `emitter/RangePollingEmitter.java:52,82-83`); `CONSUMING` = cumulative bytes/records/elapsed/filter-errors after every poll (`emitter/ConsumingStats.java:16-25`); `DONE` = end of page with an optional `cursor.id` — **null cursor means the topic range is exhausted** and the UI must stop (`ConsumingStats.java:31-42`; comment at `kafbat-ui-api.yaml:3234-3236`).

**Position model**: `ConsumerPosition(pollingMode, topic, partitions[], timestamp?, offsets?)` (`model/ConsumerPosition.java:12-16`); `Offsets` is either one offset for all partitions or a per-partition map, never both (`:18-24`). Validation errors (`timestamp not provided for FROM_TIMESTAMP`, etc.) are raised at construction (`:52-70`).

**Seek resolution** (`emitter/SeekOperations.java:70-81`): `TAILING` → end offsets of all partitions; `LATEST` → end offsets of non-empty partitions; `EARLIEST` → beginning offsets; `FROM/TO_OFFSET` → requested offsets **clamped into [begin, end]** (`:83-106`); `FROM/TO_TIMESTAMP` → `offsetsForTimes`, and for `TO_TIMESTAMP` a partition whose records are all older than the target seeks to its end (`:108-128`). Empty partitions are excluded from seeks everywhere ("only contains non-empty partitions", `:22`).

**Emitters** (all are `Consumer<FluxSink<TopicMessageEventDTO>>` wrapped with `Flux.create`, `service/MessagesService.java:298`):

- `RangePollingEmitter` (`emitter/RangePollingEmitter.java:48-76`) owns one consumer for the request (`try-with-resources`, `:51`), asks a subclass for the next `partition → [from,to)` range, and loops until the sink is cancelled, the range map is empty, or the page limit is reached. Each range is polled by **assigning only the partitions in range, seeking, and pausing a partition as soon as its position reaches `to`** (`:78-106`), which bounds over-read.
- `ForwardEmitter` splits `messagesPerPage` evenly across partitions (`ceil(limit / partitions)`, min 1) and advances `from` = previous `to` (`emitter/ForwardEmitter.java:33-56`). `BackwardEmitter` mirrors it going down to the beginning offset (`emitter/BackwardEmitter.java:33-56`).
- `MessagesProcessing` deserializes, applies the filter predicate, counts sent messages against the limit and **merge-sorts records across partitions by timestamp while keeping per-partition offset order** (`emitter/MessagesProcessing.java:42-67,94-115`). Filter exceptions are counted, not propagated (`:61-64`).
- `TailingEmitter` seeks to end offsets and polls until the client disconnects (`emitter/TailingEmitter.java:30-57`); there is no page limit for tailing (`MessagesProcessing` gets `limit=null`, `TailingEmitter.java:25`).
- `EnhancedConsumer` extends `KafkaConsumer<Bytes,Bytes>`, forbids `subscribe` (only `assign` with exactly one topic), meters bytes/records/time per poll, and applies the throttler (`emitter/EnhancedConsumer.java:20-70`).

**Paging cursors**: a `Cursor.Tracking` records the last offset seen per partition; on `DONE` with remaining range it registers a `Cursor(deserializer, position, filter, limit)` under a random 8-char id in `PollingCursorsStorage` (Guava cache, max 10 000 entries, in-memory, no TTL) (`emitter/Cursor.java:58-87`; `service/PollingCursorsStorage.java:17-38`). Forward cursors continue from `lastOffset + 1`, backward from `lastOffset` (`Cursor.java:73-79`). A missing cursor id yields a validation error "Next page cursor not found. Maybe it was evicted" (`service/MessagesService.java:239-241`). **Cursors are process-local state**, which matters for a multi-instance deployment.

**Filters**: `stringFilter` is a substring match over key, value and headers, also tried against JSON-escaped variants (`emitter/MessageFilters.java:62-98`). Smart filters are **CEL** programs compiled once and registered under a salted SHA-256 prefix id in a Guava cache (`MessagesService.java:336-346`); the CEL environment exposes a `record` variable with `partition`, `offset`, `timestampMs`, `keyAsText`, `valueAsText`, `headers: map<string,string>`, and `key`/`value` as `dyn` (parsed as JSON when possible, JSON `null`s replaced by CEL null) (`MessageFilters.java:100-202,210-245`). A test endpoint evaluates a filter against a synthetic record and returns compile/runtime errors as data (`MessagesService.java:101-131`).

**Throttling and limits**: page size is clamped to `kafka.polling.maxPageSize` (default 500) with default 100 (`MessagesService.java:61-63,330-334`); poll timeout defaults to 1 s (`emitter/PollingSettings.java:10`); a per-cluster **bytes/second `RateLimiter`** shared by all consumers of that cluster throttles polling (`emitter/PollingThrottler.java:12-20,37-47`); tailing output to the UI is capped at 20 events/s (`MessagesService.java:65-66,317-328`). `ApplicationMetrics` publishes Micrometer counters/timers for polled records, bytes, poll time, throttling activations and active consumers (`util/ApplicationMetrics.java:37-79`).

**Produce**: one `KafkaProducer` per send, `ByteArraySerializer` for key and value, serde chosen by name with optional serde properties, partition index validated against the topic description (`MessagesService.java:157-218`). **Purge** uses `deleteRecords` up to the end offset of non-empty partitions (`:133-155`).

### F6. Serde plugin API and per-topic resolution

**Plugin contract** (`serde-api/src/main/java/io/kafbat/ui/serde/api/Serde.java`): `configure(serdeProps, clusterProps, globalProps)` (`:50`), `getDescription()` (`:60`), `getSchema(topic, target)` (`:69`), `canDeserialize/canSerialize(topic, target)` (`:77,85`), `serializer(topic, target[, properties])` (`:105,117`), `getParameters(topic, target)` for UI dropdowns (`~:130`), `couldBePreferable(topic, target)` for ranking (`~:142`), `deserializer(topic, target)` (`:154`); nested `Serializer.serialize(String[, Headers])` (`:159-178`) and `Deserializer.deserialize(RecordHeaders, byte[]) → DeserializeResult` (`:182-190`). `DeserializeResult` is `{result: String?, type: STRING|JSON, additionalProperties}` (`DeserializeResult.java:15-41`); `type` is a hint for the UI and for smart filters. The Javadoc above `Serde.java:27` documents the lifecycle: scan config at startup → one **child-first classloader per custom serde** → instantiate via no-arg constructor → `configure` → runtime calls from multiple threads → `close` at shutdown. `PropertyResolver` (`PropertyResolver.java`) exposes typed `getProperty/getListProperty/getMapProperty`.

**Registry construction** (`serdes/SerdesInitializer.java`): built-ins are `String`, `SchemaRegistry`, `ProtobufFile`, `Int32/64`, `UInt32/64`, `AvroEmbedded`, `Base64`, `Hex`, `MessagePack`, `UuidBinary`, `ProtobufRaw`, plus MirrorMaker2 `Heartbeat/OffsetSync/Checkpoint` (`:43-67`). Algorithm (`:95-150`, documented in the class Javadoc): iterate `kafka.clusters[i].serde[]` in order; a configured `name` matching a built-in means "auto-configure unless properties given"; a `className` matching a built-in class means a **second named instance of a built-in**; anything else is a custom serde loaded from `filePath` (jar or directory of jars) through `CustomSerdeLoader` (`:280-303`). Remaining built-ins are auto-configured with **no topic pattern** so they appear in the UI selector but are never auto-selected (`:125-133`). Topic-specific built-ins are registered with fixed patterns (`__consumer_offsets`, MM2 topics; `:152-185`). A `Fallback` String serde is always present (`:187-192`). Custom classloaders are cached per path and are child-first except for `java.*` (`serdes/CustomSerdeLoader.java:31,77-127`).

**Resolution** (`serdes/ClusterSerdes.java:30-59`): for a `(topic, KEY|VALUE)` pair, walk serdes in config order; skip those whose `couldBePreferable` is false; a serde matches if its `topicKeysPattern`/`topicValuesPattern` matches, or, when it has no pattern, if it was **explicitly configured**; then `canDeserialize`/`canSerialize` must hold; else fall back to `defaultKeySerde`/`defaultValueSerde`, else `String` (`:69-77`). The UI "suggested serdes" endpoint returns the preferred one first plus every other applicable serde with its schema and parameters (`service/DeserializationService.java:122-148`). Explicit `keySerde`/`valueSerde` query parameters bypass suggestion but are validated with `canDeserialize` (`:73-89`).

**Per-record behavior** (`serdes/ConsumerRecordDeserializer.java:40-113`): key and value are deserialized independently; on any exception the **fallback String serde** is used and the message reports `keySerde`/`valueSerde` = `"Fallback"`. Headers are converted to strings, sizes recorded, timestamp normalized to UTC, then the topic's masker is applied last (`:56`, from `DeserializationService.java:103-120`).

**Schema Registry serde** (`serdes/builtin/sr/SchemaRegistrySerde.java`): detects the magic byte `0x0` (`:57`), auto-configures itself from the cluster's `schemaRegistry*` properties including basic auth, keystore/truststore and subject-name templates `%s-key`/`%s-value` (`:76-121`), and keeps two Caffeine caches: schema-id → subjects (size-bounded) and the "all subjects" list (TTL) (`:72-73,190-195`).

### F7. Configuration loading and the dynamic config wizard

- Static config is Spring Boot property binding (`@ConfigurationProperties("kafka")`, `config/ClustersProperties.java:28`; `"rbac"`, `config/auth/RoleBasedAccessControlProperties.java:13`; `auth.*`, `webclient.*`). Defaults live in `api/src/main/resources/application.yml:1-33` (`auth.type: DISABLED`, actuator `info,health,prometheus`, Swagger disabled unless `SWAGGER_UI_ENABLED`).
- **Dynamic config** (`util/DynamicConfigOperations.java`): enabled by `dynamic.config.enabled=true`; the file `dynamic.config.path` (default `/etc/kafkaui/dynamic_config.yaml`) is added as the **highest-priority property source at context init** (`:46-58,74-89`). `getCurrentProperties()` re-assembles a `PropertiesStructure{kafka, rbac, auth{type, oauth2}, webclient}` from live beans (`:91-103,208-236`); `persist()` validates it (`initAndValidate`, `:222-235`) and writes YAML (`:114-120`); config-related uploads (keystores, protobuf files) go to `config.related.uploads.dir` (`:122-130`).
- **Wizard endpoints** (`controller/ApplicationConfigController.java`): `getApplicationInfo`/`getAuthenticationSettings` (unauthenticated, whitelisted at `config/auth/AbstractAuthSecurityConfig.java:46`), `getCurrentConfig` (needs `APPLICATIONCONFIG.VIEW`, `:58-70`), `validateConfig` (`:106-134` → `KafkaClusterFactory.validate`, which actually connects to Kafka, SR, ksql, each Connect and Prometheus and reports per-component errors, `service/KafkaClusterFactory.java:116-163`), `uploadConfigRelatedFile` (`:90-104`), and `restartWithConfig` (`:72-88`) which **persists then restarts the whole Spring context in a new thread** (`util/ApplicationRestarter.java:24-33`). There is no in-place reconfiguration of clusters.
- **Auto reload** (`service/app/ConfigReloadService.java`, gated by `config.autoreload=true`, `:34`): a `MultiFileWatcher` thread watches every YAML property-source file (`:46-88`) and on change re-binds **only `rbac.roles`** (`:90-111`, `RoleBasedAccessControlProperties.setRoles`, `config/auth/RoleBasedAccessControlProperties.java:28-31`; the field is `volatile`, `:16`). Cluster changes still require the restart path.
- Secrets are redacted from config output by `KafkaConfigSanitizer` using a pattern list (`service/KafkaConfigSanitizer.java:22-31,75-81`) and `@ToString(exclude=...)` on property classes (`config/ClustersProperties.java:117,137,151,165,173,186,196,214`).

### F8. Authentication and RBAC

**Authentication**: one `SecurityWebFilterChain` bean per `auth.type` via `@ConditionalOnProperty`: `DISABLED` permits everything and exits the process if the deprecated `auth.enabled` key is present (`config/auth/DisabledAuthSecurityConfig.java:16-37`); `LOGIN_FORM` uses Spring form login with CSRF disabled and a static-file filter for the login page (`config/auth/BasicAuthSecurityConfig.java:17-43`); `OAUTH2` registers clients from `auth.oauth2.client.*`, and wraps the OIDC/OAuth2 user services so that a **provider-specific authority extractor** (Google, GitHub, Cognito, generic OAuth) turns IdP claims into RBAC group names (`config/auth/OAuthSecurityConfig.java:58,141-184,205-211`; extractors in `service/rbac/extractor/`); `LDAP` binds with `BindAuthenticator`, populates authorities through LDAP group search or Active Directory extractor when RBAC is on (`config/auth/LdapSecurityConfig.java:53-140,186-191`). Azure Entra and Google managed-Kafka login handlers exist for **Kafka broker** auth, not UI auth (`config/auth/azure/*`, `api/build.gradle:108-114`). A common whitelist covers static assets, actuator health/info/prometheus, Swagger, `/login`, `/oauth2/**`, `/api/config/authentication`, `/api/authorization` (`config/auth/AbstractAuthSecurityConfig.java:17-48`).

**RBAC model** (`model/rbac/`): `Role{name, clusters[], subjects[], permissions[]}` (`Role.java:10-23`); `Subject{provider, type, value, isRegex}` matched case-insensitively or by regex (`Subject.java:12-33`); `Permission{resource, value (regex, compiled lazily), actions[]}` where `ALL` expands to every action and actions expand their dependants (`Permission.java:18-59`; `Resource.java:77-86`). Resources: `APPLICATIONCONFIG, CLUSTERCONFIG, TOPIC, CONSUMER, SCHEMA, CONNECT, CONNECTOR, KSQL, ACL, AUDIT, CLIENT_QUOTAS` (`Resource.java:25-47`). A `defaultRole` (permissions only, no subjects) applies when a user matches no role (`RoleBasedAccessControlProperties.java:18`).

**Evaluation** (`service/rbac/AccessControlService.java`): RBAC is enabled only if roles or a default role exist (`:69-75`); `validateAccess` yields `AccessDeniedException` (HTTP 403 via Spring) when not allowed (`:102-106`). The user's permissions are the union of permissions of roles whose subjects match the user's groups **and** whose `clusters` include the requested cluster (`:122-137`); cluster access itself is a role-membership check (`:147-155`). `AccessContext.isAccessible` requires **every** requested resource access to be satisfied; for a named resource the permission's regex must match the name, for an unnamed one the permission must be unscoped (`model/rbac/AccessContext.java:75-92,99-102`). `CONNECTOR` accesses fall back to the parent `CONNECT` permission (`:144-154`). The `/api/authorization` endpoint returns the user's name and flattened permissions so the frontend can hide controls (`controller/AuthorizationController.java:36-69`).

**Audit** (`service/audit/AuditService.java`): per cluster, if `audit.topicAuditEnabled` or `consoleAuditEnabled`, an `AuditWriter` is created; the topic (`__kui-audit-log` by default, `:47`) is created if missing with a dedicated gzip producer (`:53-55,86-176`); `level: ALTER_ONLY` (default) logs only mutations, `ALL` also reads (`config/ClustersProperties.java:244-251`). A failed topic init degrades to console-only (`:104-110`).

**Masking** (`service/masking/DataMasking.java:43,65`): `kafka.clusters[].masking[]` rules of type `REMOVE|MASK|REPLACE`, selected by field names or a field-name regex, scoped by topic key/value regex (`config/ClustersProperties.java:221-234`); applied as the last step of deserialization (F6).

### F9. Caching strategy (summary)

| Cache | Type | Scope / eviction | Evidence |
| --- | --- | --- | --- |
| `StatisticsCache` | `ConcurrentHashMap` | one snapshot per cluster; replaced every 30 s; partial updates on topic load/delete | `service/StatisticsCache.java:21-63` |
| AdminClient pool | `ConcurrentHashMap` | one per cluster; evicted on Kafka exception | `service/AdminClientServiceImpl.java:27,64-77` |
| Serde registry | `ConcurrentHashMap<cluster, ClusterSerdes>` | built once at startup, closed at shutdown | `service/DeserializationService.java:33-44,178-179` |
| Smart filters | Guava cache, max 10 000 | id → compiled CEL predicate | `service/MessagesService.java:73-75` |
| Paging cursors | Guava cache, max 10 000 | id → `Cursor`; no TTL | `service/PollingCursorsStorage.java:17-21` |
| SR subject caches | Caffeine, size + TTL | inside `SchemaRegistrySerde`; TTL `schemaRegistryAllSubjectsCacheTtlSeconds` | `serdes/builtin/sr/SchemaRegistrySerde.java:72-73,190-195` |
| OAuth token for SR | Caffeine with custom expiry | refreshed `tokenRefreshBuffer` before expiry | `util/OAuthTokenCache.java:19-77`; `config/ClustersProperties.java:174-183` |
| Connect cluster info | property `kafka.cache.connectClusterCacheExpiry` (24 h) | `config/ClustersProperties.java:257-260` |
| Failover client instances | lazily created per URL, marked failed for a 5 s grace period | `util/ReactiveFailover.java:17,131-161` |
| GitHub release info | scheduled refresh (1 h) | `service/ApplicationInfoService.java:141` |

Everything is **process-local and unreplicated**. Caffeine is used only in two places (`grep Caffeine` → `util/OAuthTokenCache.java`, `serdes/builtin/sr/SchemaRegistrySerde.java`); the rest is Guava or plain maps.

### F10. Background pollers and schedulers

- `ClustersStatisticsScheduler` — `@Scheduled(fixedRate = kafka.update-metrics-rate-millis, default 30000)`, full cluster scrape + broker metrics + Connect states (`service/ClustersStatisticsScheduler.java:20`; `service/StatisticsService.java:48-76`).
- `OddExporterScheduler` — pushes metadata to OpenDataDiscovery every `kafka.send-stats-to-odd-millis` (default 30 s) (`service/integration/odd/OddExporterScheduler.java:15`).
- `ApplicationInfoService` — polls GitHub releases hourly (`service/ApplicationInfoService.java:141`).
- `ConfigReloadService` — file-watcher thread (F7).
- `TopicAnalysisService` — user-triggered, runs on a dedicated bounded-elastic scheduler, one task per topic, cancellable, progress reported as processed/summary offsets; results kept in an in-memory `AnalysisTasksStore` (`service/analyze/TopicAnalysisService.java:34-135`; `service/analyze/AnalysisTasksStore.java:18-21`).
- Metrics sink — after every scrape, metrics are pushed to a Prometheus Pushgateway if configured (`service/metrics/scrape/MetricsScraper.java:68-82`; `service/metrics/sink/PrometheusPushGatewaySink.java:17,35`).

### F11. Error handling and error response shape

- All application exceptions extend `CustomBaseException` and declare an `ErrorCode` (`exception/CustomBaseException.java:4-27`). `ErrorCode` pairs a **numeric application code with an HTTP status**, e.g. `UNEXPECTED(5000, 500)`, `BINDING_FAIL(4001, 400)`, `VALIDATION_FAIL(4002, 400)`, `READ_ONLY_MODE_ENABLE(4003, 405)`, `CONNECT_CONFLICT_RESPONSE(4004, 409)`, `CLUSTER_NOT_FOUND(4007, 404)`, `TOPIC_NOT_FOUND(4008, 404)`, `SCHEMA_NOT_FOUND(4009, 404)`, `TOPIC_OR_PARTITION_NOT_FOUND(4013, 400)`, `RECREATE_TOPIC_TIMEOUT(4015, 408)`, `CEL_ERROR(4020, 400)`, `OAUTH_TOKEN_FETCH_ERROR(5002, 500)` (`exception/ErrorCode.java:7-31`); a static block warns on duplicate codes (`:33-42`).
- `GlobalErrorWebExceptionHandler` (highest precedence) renders four cases: bean-validation binding errors → 400 with `fieldsErrors[]`; Spring `ResponseStatusException` (404 routing, 403 access) → its status; `CustomBaseException` → its `ErrorCode`; everything else → 5000/500 (`exception/GlobalErrorWebExceptionHandler.java:56-149`). Stack traces are included unless `http.error.excludeStackTraces=true` (`:41-42,172-177`). CORS headers are re-applied on error responses (`:155-157`).
- **Body shape** (`kafbat-ui-api.yaml:2496-2529`): `ErrorResponse{code: int, message: string, timestamp: number (epoch ms), requestId: string, fieldsErrors: [{fieldName, restrictions[]}], stackTrace: string}`. `requestId` is the WebFlux request id (`GlobalErrorWebExceptionHandler.java:151-153`).
- Upstream errors are translated at the client boundary: Connect 400/500 bodies are parsed for `message` (`client/RetryingKafkaConnectClient.java:89-110`), SR 404/409/422 map to `SchemaNotFoundException`/`SchemaCompatibilityException`/`UnprocessableEntity` (`service/SchemaRegistryService.java:112,140-142`), ksqlDB errors are returned **as data rows** in the response table rather than as HTTP errors (`service/ksql/KsqlApiClient.java:125-126,170-199`).
- Streaming errors: emitters call `sink.error(e)` on unexpected exceptions and `sink.complete()` on Kafka `InterruptException` (client cancel) (`emitter/RangePollingEmitter.java:69-75`).

### F12. Metrics collection and exposition

- Per cluster, `MetricsScraper.create` picks a **broker scraper**: `JmxMetricsScraper` when `metrics.type=JMX` and a port is set, or `PrometheusScraper` when `metrics.type=PROMETHEUS` (scrapes `http://broker:port/metrics` with `WebClientConfigurator`, `service/metrics/scrape/prometheus/PrometheusMetricsRetriever.java:15-27`); plus an always-on `InferredMetricsScraper` that derives topic/partition/consumer-group/node gauges from `ScrapedClusterState` (`service/metrics/scrape/MetricsScraper.java:37-69`; `service/metrics/scrape/inferred/InferredMetricsScraper.java:22-30`).
- JMX retrieval opens a `JMXConnector` per node per scrape on the bounded-elastic scheduler, with optional credentials and a custom SSL socket factory (`service/metrics/scrape/jmx/JmxMetricsRetriever.java:28-128`). Raw metrics are normalized to Prometheus `MetricSnapshot`s; IO rates are summarized by `IoRatesMetricsScanner`.
- Exposition: `/metrics` and `/metrics/{cluster}` render the cached snapshots in Prometheus text format for clusters with `metrics.prometheusExpose != false` (`controller/PrometheusExposeController.java:18-46`; `service/KafkaClusterFactory.java:165-169`). Micrometer's `/actuator/prometheus` separately exposes app metrics (`api/src/main/resources/application.yml:4-13`).
- Storage/graphs: an optional Prometheus store (`kafka.defaultMetricsStorage.prometheus.url` or per-cluster `metrics.store.prometheus.url`) gets metrics via Pushgateway/remote-write and is queried back through the generated `PrometheusClientApi` for the `graphs/descriptions` and `graphs/prometheus` endpoints (`config/ClustersProperties.java:131-145`; `service/KafkaClusterFactory.java:100-110,179-192`; `service/graphs/*`).

### F13. Schema Registry, Connect and ksqlDB clients

- **Common HTTP layer**: `WebClientConfigurator` builds a Reactor Netty `WebClient` with truststore/keystore SSL (or trust-all when `ssl.verify=false`), basic auth or OAuth2 client-credentials, buffer size (default 20 MB), response timeout (default 20 s), extra accept media types and a lenient Jackson mapper (`util/WebClientConfigurator.java:37-225`; defaults at `service/KafkaClusterFactory.java:52-53`).
- **Failover**: every external client is wrapped in `ReactiveFailover<T>`: a list of publishers built from comma-separated URLs; on a "Connection refused" error the current publisher is marked failed for 5 s and the call is retried on the next one; when all are failed an `IllegalStateException("No live ... instances available")` is raised (`util/ReactiveFailover.java:17-19,70-90,117-161`). Used for SR, each Connect, ksqlDB and Prometheus (`KafkaClusterFactory.java:185-191,228-239,283-289,297-309`).
- **Schema Registry**: generated `KafkaSrClientApi` from `kafka-sr-api.yaml`; accepts `application/vnd.schemaregistry.v1+json` and `application/vnd.schemaregistry+json` for WarpStream-style registries (`KafkaClusterFactory.java:55-57,273`); basic auth **or** OAuth, never both (validated at `:252-269`); subject suffix configurable (`schemaRegistryTopicSubjectSuffix`, `config/ClustersProperties.java:83`).
- **Kafka Connect**: generated `KafkaConnectClientApi` wrapped by `RetryingKafkaConnectClient` which retries **409 Conflict** and "rebalance in progress" responses 5 times with 200 ms delay and parses Connect error bodies (`client/RetryingKafkaConnectClient.java:39-94`). Connect cluster state (version, connectors, tasks) is scraped into `Statistics.connectStates` each cycle (`service/KafkaConnectService.java:140`; `service/StatisticsService.java:136-138`) so list screens do not hit Connect on every request.
- **ksqlDB**: hand-written `KsqlApiClient` that classifies the statement with an ANTLR grammar, sends `SELECT`-like statements to `/query` as a **streaming JSON array** and other statements to `/ksql`, tolerating truncated arrays from old ksqlDB versions (`service/ksql/KsqlApiClient.java:112-168`); only single statements are accepted (`:170-183`).
- Validation of all of these at wizard time reuses the same factories (`KafkaClusterFactory.validate`, `:116-163`).

### F14. Other cross-cutting mechanisms

- **Full-text search**: `ScrapedClusterState` carries a `TopicsIndex` (`LuceneTopicsIndex` when `kafka.fts.enabled`, else a simple `FilterTopicIndex`) with n-gram analyzers; similar n-gram filters exist for consumer groups, schemas, connectors and ACL bindings (`service/index/*`; `config/ClustersProperties.java:265-292`). `ClusterFeature.FTS_ENABLED/FTS_DEFAULT_ENABLED` tell the UI whether to offer it.
- **MCP server**: enabled by `mcp.enabled=true`; an SSE transport at `/mcp/sse` + `/mcp/message`; every controller marked `McpTool` has its `@Operation`-annotated methods converted into MCP tools with JSON-schema parameters; write operations are blocked on read-only clusters except `analyzeTopic`, `cancelTopicAnalysis`, `registerFilter` (`config/McpConfig.java:21-66`; `service/mcp/McpSpecificationGenerator.java:47-49,55-81`).
- **CSV**: ACL export and generic CSV responses through `CsvWriterService` with configurable delimiter/quoting (`controller/AbstractController.java:59-75`; `config/ClustersProperties.java:52-58`).

### F15. Test architecture

- 141 test classes under `api/src/test/java`. The integration base class `AbstractIntegrationTest` is a `@SpringBootTest` with `WebTestClient` and a **static Testcontainers topology started once per JVM**: `ConfluentKafkaContainer` (cp-kafka 7.8.0) with an extra listener and **JMX enabled**, plus `SchemaRegistryContainer`, `KafkaConnectContainer`, `KsqlDbContainer` (`api/src/test/java/io/kafbat/ui/AbstractIntegrationTest.java:33-83`; custom containers in `api/src/test/java/io/kafbat/ui/container/` incl. `ActiveDirectoryContainer`, `PrometheusContainer`).
- The Spring context is configured through **system properties in an `ApplicationContextInitializer`**, deliberately exercising: SR failover with two dead URLs before the live one (`:103-106`), an unreachable second Connect cluster (`:111-112`), a masking rule and a ProtobufFile serde bound to `masking-test-.*` (`:92-101,113-115`), console + topic audit (`:117-118`), consumer/producer overrides such as `isolation.level=read_committed` (`:120-124`), JMX metrics via the mapped port (`:125-126`), a second **read-only** cluster (`:128-133`), and dynamic config with an upload dir (`:135-136`).
- HTTP dependencies (OAuth, GitHub release info, WebClient TLS) are tested with WireMock/MockWebServer (`api/src/test/java/io/kafbat/ui/config/auth/OAuthTestSupport.java`, `util/WebClientConfiguratorTest.java`, `util/GithubReleaseInfoTest.java`).
- Test resources include RBAC profiles (`application-rbac-ad.yml`, `application-rbac-audit.yml`, `application-roles-definition.yml`) and a `protobuf-serde` directory.

## Decision candidates for KUI

**D1. Contract-first from a single source, consumed by gateway and services.**
Decision: keep KUI's Tapir endpoint definitions as the single source of routes and forbid hand-written path lists in the gateway, mirroring Kafbat's "controller implements generated interface" invariant.
Evidence: F1, F3 (`contract/build.gradle:42-71`; `controller/MessagesController.java:46`).
Tradeoff: Tapir gives us this for free but every service must publish its contract module for JVM+JS; the gateway compiles against all contract modules.
Reversibility: high (adding a hand-written route later is trivial; removing one is not).

**D2. Cluster registry lives in `kui-cluster-service`; other services receive cluster connection config via contract, cache it, and never read `kafka.clusters` themselves.**
Decision: reproduce `ClustersStorage` + `KafkaClusterFactory` as the cluster service's domain, exposing "resolved cluster connection profile" (bootstrap, client properties, SSL/SASL, SR/Connect/ksql endpoints and auth) through `kui-cluster-service`'s contract, with services holding a last-known copy.
Evidence: F4 (`service/ClustersStorage.java:15-19`; `service/KafkaClusterFactory.java:74-114`).
Tradeoff: an extra hop on first use and a cache-invalidation protocol; but it makes the wizard (D8) a single-writer flow instead of a restart.
Reversibility: medium; the all-in-one shape hides the hop, so the decision can be revisited without changing contracts.

**D3. Split Kafbat's monolithic `Statistics` snapshot by bounded context, keep the "snapshot with status" pattern.**
Decision: cluster service owns node/controller/quorum/feature snapshot; topic service owns topic descriptions/configs/offsets/index; consumer service owns group descriptions/committed offsets; metrics service owns scraped broker metrics. Each keeps a per-cluster snapshot with `INITIALIZING|ONLINE|OFFLINE(lastError)` and a scrape timestamp, refreshed by its own scheduler, and serves reads from it. Aggregation for dashboards happens in the gateway with per-section status.
Evidence: F4 (`service/StatisticsCache.java:21-63`; `service/metrics/scrape/ScrapedClusterState.java:41-150`; `service/ClustersStatisticsScheduler.java:20-36`).
Tradeoff: Kafbat's single scrape shares one AdminClient round of calls; KUI will perform 3–4 partially overlapping scrapes per cluster (describeTopics is needed by both topic and consumer contexts). Accept the duplication; expose scrape-rate knobs per service.
Reversibility: medium; the snapshot shape is internal to each service.

**D4. Message browsing = one fs2 `Stream` per request with the same four event types and cursor semantics; cursors become service-local only if the gateway pins the session, otherwise encode them.**
Decision: adopt `PHASE|MESSAGE|CONSUMING|DONE(cursor?)` as the SSE envelope in `kui-contracts-core`, the seven polling modes and clamp/timestamp rules of `SeekOperations`, the per-partition range-slicing with pause, the timestamp merge-sort, and fallback-serde-on-error. Replace the in-memory cursor cache by an **opaque, signed cursor value** carrying position, filter id, serde names and limit so any `kui-message-service` replica can continue a page (flagged as a risk to track).
Evidence: F5 (`emitter/RangePollingEmitter.java:48-106`; `emitter/Cursor.java:58-87`; `service/PollingCursorsStorage.java:17-38`; `kafbat-ui-api.yaml:3225-3244`).
Tradeoff: encoded cursors are larger and cannot reference a compiled filter object; the filter must be re-compilable from its source or a registered id that is itself replicated (see D5).
Reversibility: high for the envelope (additive), medium for cursor format (changes the contract once).

**D5. CEL smart filters, compiled per service instance, addressed by a content hash.**
Decision: keep CEL (not Groovy) with Kafbat's `record` schema; the filter id is a hash of the source; the browse request carries the id **and** the source on the first call so any replica can compile on miss.
Evidence: F5 (`emitter/MessageFilters.java:100-202`; `service/MessagesService.java:336-346`).
Tradeoff: CEL for Scala means the Java `dev.cel` runtime (JVM only); acceptable since the message service is JVM. Filter-test endpoint stays as a pure function.
Reversibility: high.

**D6. `kui-serde` mirrors the Kafbat plugin contract semantically, but as a Scala trait with explicit effects and no classloader-per-plugin at first.**
Decision: `Serde[F]` with `configure`, `describe`, `schema`, `canDeserialize/canSerialize`, `serializer(topic, target, params)`, `deserializer`, `parameters`, `preferable`; `DeserializeResult(result, kind: Text|Json, extras)`; registry built per cluster in config order with the same pattern/explicit/default/String resolution chain and a mandatory `Fallback` String serde. Jar plugin loading (child-first classloader) is a Milestone-later adapter in `kui-serde` because it is the only reason the API must stay Java-callable.
Evidence: F6 (`serde-api/.../Serde.java:27-190`; `serdes/SerdesInitializer.java:95-150`; `serdes/ClusterSerdes.java:30-77`; `serdes/ConsumerRecordDeserializer.java:77-113`).
Tradeoff: Kafbat-compatible **Java** plugin jars would need a bridge; decide in an ADR whether binary compatibility with `io.kafbat.ui.serde.api` is a goal.
Reversibility: medium (adding a bridge later is additive).

**D7. Serde resolution is owned by `kui-message-service`, with Schema Registry access through the SR client library, not through `kui-schema-service`'s HTTP contract.**
Decision: the SR-backed serde needs low-latency schema-by-id lookups on every record; route it to a shared `kui-kafka`/`kui-serde` SR client with Caffeine caches (id → schema, subjects TTL) rather than a synchronous cross-service call.
Evidence: F6/F9 (`serdes/builtin/sr/SchemaRegistrySerde.java:57,72-73,190-195`).
Tradeoff: two components talk to the registry (schema service for management, message service for decoding); config for the registry must be shared via D2.
Reversibility: high.

**D8. Config wizard writes through `kui-config-service` and is applied without process restart.**
Decision: `kui-config-service` validates (connectivity checks equivalent to `KafkaClusterFactory.validate`) and persists the dynamic YAML; changes are pushed to `kui-cluster-service`, which republishes cluster profiles; other services refresh through D2. RBAC role hot-reload (Kafbat's only hot path) is owned by `kui-identity-service`.
Evidence: F7 (`controller/ApplicationConfigController.java:72-134`; `util/ApplicationRestarter.java:24-33`; `service/app/ConfigReloadService.java:90-111`).
Tradeoff: more moving parts than "restart the JVM", but restart is unacceptable for a gateway shared by all clusters.
Reversibility: medium.

**D9. RBAC: Kafbat's model (roles → subjects/clusters/permissions with regex values and action dependants) is adopted as the KUI permission model in `kui-security-core`; enforcement in the gateway with a signed principal re-checked by services.**
Decision: reuse the resource/action vocabulary (`TOPIC`, `CONSUMER`, `SCHEMA`, `CONNECT`, `CONNECTOR` with fallback, `KSQL`, `ACL`, `AUDIT`, `CLIENT_QUOTAS`, `CLUSTERCONFIG`, `APPLICATIONCONFIG`) and the `AccessContext` evaluation rules; keep the "audit-topic requires AUDIT.VIEW" special case. Provider-specific group extractors (Google/GitHub/Cognito/generic OAuth/LDAP/AD) live in `kui-identity-service`.
Evidence: F8 (`model/rbac/AccessContext.java:75-92,144-154`; `service/rbac/AccessControlService.java:102-155`; `service/rbac/extractor/*`).
Tradeoff: list endpoints need post-filtering by permission (Kafbat's `filterViewableTopics`); the gateway must do it or services must receive the permission set.
Reversibility: high.

**D10. Error envelope and codes: adopt Kafbat's shape and code ranges.**
Decision: `kui-contracts-core` error envelope = `{code, message, timestamp, requestId, fieldsErrors[], details?}`; keep 4xxx/5xxx numeric codes with per-code HTTP status; stack traces never leave the service (Kafbat's `excludeStackTraces` default is `false`, KUI defaults to excluded).
Evidence: F11 (`exception/ErrorCode.java:7-31`; `kafbat-ui-api.yaml:2496-2529`).
Tradeoff: numeric codes are opaque; add a stable `kind` string alongside.
Reversibility: high (additive).

**D11. External clients: failover list + retry-on-409/rebalance + per-client timeouts, implemented once in `kui-http`.**
Decision: reproduce `ReactiveFailover` (rotate on connection refused, 5 s grace), `RetryingKafkaConnectClient` (retry 409/rebalance, parse error bodies), lenient JSON decoding and SR vendor media types in the shared sttp client factory; wire the same circuit-breaker into the gateway's capability registry.
Evidence: F13 (`util/ReactiveFailover.java:70-161`; `client/RetryingKafkaConnectClient.java:39-94`; `service/KafkaClusterFactory.java:55-57`).
Tradeoff: none significant.
Reversibility: high.

**D12. Metrics: JMX/Prometheus/inferred scrapers belong to `kui-metrics-service`; the inferred metrics need the topic/consumer snapshots, so the metrics service consumes them via contracts or recomputes from AdminClient.**
Decision: metrics service performs its own broker scrape (JMX or Prometheus) and computes inferred metrics from `kui-topic-service`/`kui-consumer-service` snapshot endpoints; `/metrics` exposition and Pushgateway sink live there.
Evidence: F12 (`service/metrics/scrape/MetricsScraper.java:37-82`; `controller/PrometheusExposeController.java:18-46`).
Tradeoff: cross-service read on each scrape; acceptable at 30 s cadence.
Reversibility: medium.

**D13. Testkit: one static Testcontainers topology per test JVM, configuration injected as properties, and the deliberately-broken endpoints pattern.**
Decision: `kui-testkit` provides Kafka (with JMX listener), SR, Connect, ksqlDB, Prometheus and AD containers, plus fixtures that include a dead SR URL before the live one, an unreachable Connect cluster and a read-only cluster, so failover and degradation are always exercised.
Evidence: F15 (`api/src/test/java/io/kafbat/ui/AbstractIntegrationTest.java:47-136`).
Tradeoff: slow first start; mitigated by container reuse.
Reversibility: high.

**D14. MCP server is a gateway concern generated from the contracts.**
Decision: since Kafbat derives MCP tools from annotated controller methods, KUI can derive them from Tapir endpoint metadata in the gateway once contracts are stable; treat as Milestone 6+.
Evidence: F14 (`config/McpConfig.java:41-66`; `service/mcp/McpSpecificationGenerator.java:55-81`).
Tradeoff: none now.
Reversibility: high.

## Open questions

1. Should KUI aim for **binary compatibility with Kafbat's `io.kafbat.ui.serde.api`** so existing community serde jars (AWS Glue, Smile, etc.) load unchanged? This drives whether `kui-serde` keeps a Java-facing shim.
2. How should paging cursors be made replica-safe: opaque encoded cursor (D4) vs sticky routing at the gateway vs shared store? Needs a decision before the message-service contract is frozen.
3. Kafbat targets Java 25; the previously assumed baseline was 21. Confirm the JDK baseline for KUI's Docker images and Testcontainers versions.
4. Which Kafbat `ClusterFeature` flags map to the gateway's capability registry vs to per-cluster "supports" flags returned by services (e.g. `TOPIC_DELETION` is a broker setting, `FTS_ENABLED` is a KUI config)?
5. The dynamic-config restart model implies Kafbat never handles two config writers; KUI's config service needs an optimistic-concurrency rule (version in the YAML?).
6. Provectus/Kafbat keep `Statistics` per process; for the distributed shape, do we accept N scrapes per cluster (one per service) or introduce the internal events topic earlier than Milestone 6?

## Confidence

**High** for module structure, request flow, cluster caching, emitter design, serde resolution, RBAC evaluation, error shape and test topology — all read directly from source with line references. **Medium** for the metrics pipeline internals (`IoRatesMetricsScanner`, `JmxMetricsFormatter`, graph templates were only skimmed) and for the frontend (only package versions and component directories were inspected; the UX research in `research/kafbat/ui-analysis.md` covers that ground). **Medium** on exact line numbers in `serde-api/Serde.java` for `getParameters`/`couldBePreferable` (marked `~`).
