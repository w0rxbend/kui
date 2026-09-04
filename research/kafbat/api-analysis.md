# Kafbat HTTP API surface analysis (with Provectus and Kouncil deltas) and KUI `/api/v1` mapping

**Date:** 2026-09-03
**Status:** complete, ready for architect review

## Questions

1. What is the exact HTTP surface of Kafbat: every endpoint, its parameters, bodies, responses,
   error responses and the RBAC permission it checks?
2. What are the pagination, sorting, filtering and search semantics of every list endpoint?
3. How do the streaming endpoints (message browsing SSE, KSQL response pipe) behave: event
   envelope, seek/polling modes, limits, cursor paging, cancellation, v1 vs v2?
4. Which DTOs and enums does the frontend depend on?
5. What is the error envelope and which error codes exist?
6. How do login, logout and the session cookie work?
7. What does Kouncil expose for event tracking and table-style browsing?
8. How should each reference endpoint map onto KUI `/api/v1`, which service owns it, which are
   gateway aggregations, and where must the gateway return partial results?

## Method and sources

Local clones (read-only), commit SHAs and clone dates:

| Ref | Path | SHA | Commit date |
| --- | --- | --- | --- |
| Kafbat kafka-ui | `/tmp/kui-ref/kafbat` | `fa485c2bd45cac713cd994c62bc2d458abd3f328` | 2026-09-03 |
| Provectus kafka-ui | `/tmp/kui-ref/provectus` | `83b5a60cc08501b570a0c4d0b4cdfceb1b88d6b7` | 2024-04-08 |
| Consdata Kouncil | `/tmp/kui-ref/consdata` | `6e2fb85e6ceac813c39f762eecd2f4bce1b31faf` | 2026-08-04 |

Path abbreviations used in citations:

- `Y` = `/tmp/kui-ref/kafbat/contract/src/main/resources/swagger/kafbat-ui-api.yaml` (legacy hand-written OpenAPI, 4829 lines)
- `T` = `/tmp/kui-ref/kafbat/contract-typespec/api/*.tsp` (TypeSpec, the **build-time source of truth**, see Finding 1)
- `J` = `/tmp/kui-ref/kafbat/api/src/main/java/io/kafbat/ui`
- `PY` = `/tmp/kui-ref/provectus/kafka-ui-contract/src/main/resources/swagger/kafka-ui-api.yaml`
- `PJ` = `/tmp/kui-ref/provectus/kafka-ui-api/src/main/java/com/provectus/kafka/ui`
- `C` = `/tmp/kui-ref/consdata/kouncil-backend/src/main/java/com/consdata/kouncil`

Method: read every path in `Y`, every interface in `T`, every controller under `J/controller`,
the message emitter package `J/emitter`, the KSQL service, the error handler and the security
configs. Provectus diff done by `comm` on path names and schema names of `PY` vs `Y` plus reading
the Provectus `MessagesController`. Kouncil read from its controllers, DTOs, `TrackService`,
`TopicService`, `EventMatcher` and `WebSocketConfig`.

---

## Findings

### 1. Contract sources of truth in Kafbat

Kafbat carries two API descriptions:

- `Y` (`kafbat-ui-api.yaml`, "version 0.1.0"), the historical OpenAPI file inherited from Provectus.
- `T` (`contract-typespec/api/*.tsp`, `@info version: "0.2.0"`, `T/main.tsp:23-27`), compiled by
  `tsp compile` into `build/tsp/api/openapi.yaml` (`T/tspconfig.yaml:1-8`). The Java `contract`
  module's code generation **depends on the TypeSpec output**, not on `Y`
  (`/tmp/kui-ref/kafbat/contract/build.gradle:25-26,45-46,75`).

Consequence: `T` is authoritative. Endpoints that exist only in `T` (and in the controllers) but
not in `Y`:

| Endpoint | Citation |
| --- | --- |
| `GET /api/clusters/{c}/brokers/csv` | `T/brokers.tsp:16-20` |
| `GET /api/clusters/{c}/topics/csv` | `T/topics.tsp:28-38` |
| `GET /api/clusters/{c}/topics/{t}/acls` | `T/topics.tsp` (listTopicAcls), `J/controller/TopicsController.java:295-301` |
| `GET /api/clusters/{c}/consumer-groups/lag` | `T/consumer-groups.tsp:27-35` |
| `GET /api/clusters/{c}/consumer-groups/csv` | `T/consumer-groups.tsp:38-50` |
| `state[]` query param on `/consumer-groups/paged` | `T/consumer-groups.tsp:24` |
| `GET /api/clusters/{c}/connects/csv` | `T/kafka-connect.tsp:11-15` |
| `GET /api/clusters/{c}/connectors/csv` | `T/kafka-connect.tsp:44-54` |
| `GET /api/clusters/{c}/acls/csv` (with filters) | `T/acls.tsp:23-33` (note `Y:2132` still says `/acl/csv`) |
| `Cluster.controller: ZOOKEEPER \| KRAFT \| UNKNOWN` | `T/clusters.tsp:49,104-108` |
| `Connect.consumerNamePattern`, `Connector.consumer`, `FullConnectorInfo.consumer` | `T/kafka-connect.tsp` (Connect, Connector, FullConnectorInfo models) |
| `SerdeDescription.parameters: SerdeParameter[]` | `T/messages.tsp` (SerdeDescription, SerdeParameter) |
| `ConsumerGroupsLagResponse / ConsumerGroupLag / ConsumerGroupTopicLag` | `T/consumer-groups.tsp` |

TypeSpec models use `Response<...>` unions for errors: `ApiNotFoundResponse` (404),
`ApiTimeoutResponse` (408), `ApiDuplicateResponse`/`ApiRebalanceInProgressResponse` (409),
`ApiInvalidParametersResponse` (422), `ApiBadRequestResponse` (400, body `ErrorResponse`),
`ApiUnauthorized` (401), `CsvResponse` (200, `text/csv`), `SseResponse<M>` (200,
`text/event-stream`, body `M[]`) (`T/responses.tsp:10-78`).

### 2. Cross-cutting conventions

**Base path.** All resource routes are `/api/clusters/{clusterName}/...`. `clusterName` is the
user-facing cluster name from config; unknown names raise `ClusterNotFoundException`
(`J/controller/AbstractController.java:25-29`, error code 4007 → 404).

**Permission model.** Each controller method builds an `AccessContext` with the cluster and the
required (resource, action) pairs, then `validateAccess(context)`. When RBAC is disabled every
check passes; otherwise the user's roles are filtered by cluster and evaluated
(`J/service/rbac/AccessControlService.java:102-127`). Failure raises Spring's
`AccessDeniedException` → HTTP 403. List endpoints do not require a permission up-front; they
**filter the result set** by what the user may see (`filterViewableTopics`,
`isConsumerGroupAccessible`, `isSchemaAccessible`, `isConnectorAccessible`)
(`J/controller/TopicsController.java:202`, `ConsumerGroupsController.java:139,159`,
`SchemasController.java:230`, `KafkaConnectController.java:158`). Every mutating (and most
reading) call is audited via `audit(context, signal)`.

Resource types and actions (`Y:3790-3820` region, `T/auth.tsp`; enum values from the schema
summary): `ResourceType = APPLICATIONCONFIG | CLUSTERCONFIG | TOPIC | CONSUMER | SCHEMA | CONNECT
| CONNECTOR | KSQL | ACL | AUDIT | CLIENT_QUOTAS`; `Action = ALL | VIEW | EDIT | CREATE | DELETE
| RESET_OFFSETS | EXECUTE | MODIFY_GLOBAL_COMPATIBILITY | ANALYSIS_VIEW | ANALYSIS_RUN |
MESSAGES_READ | MESSAGES_PRODUCE | MESSAGES_DELETE | OPERATE | RESTART`.

**Audit-topic special case.** Reading messages of, or details of, the configured audit topic
requires `AUDIT:VIEW` instead of `TOPIC:MESSAGES_READ`/`TOPIC:VIEW`
(`J/controller/MessagesController.java:112-116`, `TopicsController.java:171-175`).

**Pagination style.** Offset pagination with `page` (1-based) and `perPage`; the response carries
`pageCount` only, never `totalItems`. The whole list is computed in memory, then sorted, then
`skip/limit`. Details per resource in Finding 4.

**Full-text search (`fts`).** Every searchable list accepts `fts?: boolean`. Semantics
(`J/config/ClustersProperties.java:274-292`): if `fts.enabled` (default true) and the request says
`fts=true` → n-gram index search; if the request omits `fts` → `fts.defaultEnabled` (default
false) decides; if `fts=false` → plain substring/contains matching. N-gram settings are per
resource (`schemas`, `consumers`, `connect`, `acl`, ngram 1..4, case-insensitive). The cluster
advertises `FTS_ENABLED` / `FTS_DEFAULT_ENABLED` in `Cluster.features` so the UI can render a
toggle (`T/clusters.tsp:52-62`).

**Sorting.** `orderBy` is a per-resource enum, `sortOrder = ASC | DESC` (default ASC). Sorting
happens after filtering, before paging.

**CSV exports.** Every list endpoint has a `/csv` sibling that reuses the same filters and sorting
but ignores paging and returns `text/csv` (`T/responses.tsp:75-78`, controllers'
`responseToCsv`).

### 3. Endpoint catalog (Kafbat)

Legend: **Perm** = permission checked via `AccessContext` (`—` = only cluster existence; `filter`
= results filtered by visibility). Statuses beyond 200 are those declared in `Y`/`T` plus what the
error handler produces (Finding 6).

#### 3.1 Application, auth and config (no cluster scope)

| Method | Path | Params / body | Response | Errors | Perm | Cite |
| --- | --- | --- | --- | --- | --- | --- |
| GET | `/api/clusters` | — | `Cluster[]` | — | — (all clusters; UI hides by RBAC) | `Y:18`, `J/controller/ClustersController.java:25` |
| GET | `/api/info` | — | `ApplicationInfo` | — | — | `Y:2339` |
| GET | `/api/config` | — | `ApplicationConfig` | 403 | `APPLICATIONCONFIG:VIEW` | `Y:2353`, `J/controller/ApplicationConfigController.java:59-62` |
| PUT | `/api/config` | body `RestartRequest{config}` | 200 (app restarts) | 400 | `APPLICATIONCONFIG:EDIT` | `Y:2364`, ibid `:73-77` |
| PUT | `/api/config/validated` | body `ApplicationConfig` | `ApplicationConfigValidation` | 400 | `APPLICATIONCONFIG:EDIT` | `Y:2380`, ibid `:107-111` |
| POST | `/api/config/relatedfiles` | multipart `file` | `UploadedFileInfo{location}` | 4019 | `APPLICATIONCONFIG:EDIT` | `Y:2399`, ibid `:91-95` |
| GET | `/api/config/authentication` | — | `AppAuthenticationSettings{authType, oAuthProviders[]}` | — | none (whitelisted) | `Y:2422`, `J/config/auth/AbstractAuthSecurityConfig.java:47` |
| GET | `/api/authorization` | — | `AuthenticationInfo{rbacEnabled, userInfo?}` | — | none (whitelisted) | `Y:2325`, `J/controller/AuthorizationController.java:36-84` |
| POST | `/login` | form `username`, `password` | 200 empty (no redirect) | 401 | none | `Y:2436`, `J/config/auth/BasicAuthSecurityConfig.java:31-35` |
| GET | `/login` | — | `index.html` | — | none | `J/controller/AuthenticationController.java` |
| GET | `/logout` | — | 302 → `/auth?logout` | — | none | `J/config/auth/AbstractAuthSecurityConfig.java:15,56-59`, `BasicAuthSecurityConfig.java:35-37` |
| GET | `/metrics`, `/metrics/{clusterName}` | — | Prometheus text (`application/text` in `Y`) | — | none (whitelisted) | `Y:204,218` |
| PUT | `/api/smartfilters/testexecutions` | body `SmartFilterTestExecution` | `SmartFilterTestExecutionResult{result?, error?}` | — | **none** (no cluster/topic context) | `Y:738`, `J/controller/MessagesController.java:71-77` |

`ApplicationInfo.enabledFeatures` is `["DYNAMIC_CONFIG"]` when the config wizard is enabled;
`build{commitId, version, buildTime, isLatestRelease}` and `latestRelease{versionTag,
publishedAt, htmlUrl}` drive the "update available" banner (`Y:2536-2560`).

#### 3.2 Clusters, brokers, graphs

| Method | Path | Params / body | Response | Errors | Perm | Cite |
| --- | --- | --- | --- | --- | --- | --- |
| POST | `/api/clusters/{c}/cache` | — | `Cluster` | 404 | — | `Y:80`, `ClustersController.java:67-72` |
| GET | `/api/clusters/{c}/metrics` | — | `ClusterMetrics{items: Metric[]}` | — | — | `Y:184` |
| GET | `/api/clusters/{c}/stats` | — | `ClusterStats` | — | — | `Y:238` |
| GET | `/api/clusters/{c}/brokers` | — | `Broker[]` | — | — | `Y:102`, `BrokersController.java:36-40` |
| GET | `/api/clusters/{c}/brokers/csv` | — | csv | — | — | `T/brokers.tsp:16-20` |
| GET | `/api/clusters/{c}/brokers/{id}/metrics` | path `id:int` | `BrokerMetrics{segmentSize, segmentCount, metrics[]}` | — | — | `Y:258` |
| GET | `/api/clusters/{c}/brokers/{id}/configs` | path `id:int` | `BrokerConfig[]` | 404 | `CLUSTERCONFIG:VIEW` | `Y:124`, `BrokersController.java:94-100` |
| PUT | `/api/clusters/{c}/brokers/{id}/configs/{name}` | body `BrokerConfigItem{value}` | 200 | — | `CLUSTERCONFIG:VIEW+EDIT` | `Y:153`, ibid `:131-139` |
| GET | `/api/clusters/{c}/brokers/logdirs` | query `broker?: int[]` | `BrokersLogdirs[]` | — | — | `Y:283`, ibid `:75-83` |
| PATCH | `/api/clusters/{c}/brokers/{id}/logdirs` | body `BrokerLogdirUpdate{topic, partition, logDir}` | 200 | 4012 (400) | `CLUSTERCONFIG:VIEW+EDIT` | `Y:313`, ibid `:112-119` |
| GET | `/api/clusters/{c}/graphs/descriptions` | — | `GraphDescriptions{graphs: GraphDescription[]}` | — | — | `Y:34`, `GraphsController.java:61-65` |
| POST | `/api/clusters/{c}/graphs/prometheus` | body `GraphDataRequest{id, parameters, from, to}` | `PrometheusApiQueryResponse` | — | — | `Y:55`, ibid `:38-43` |

`Cluster.features` enum: `SCHEMA_REGISTRY, KAFKA_CONNECT, KSQL_DB, TOPIC_DELETION, KAFKA_ACL_VIEW,
KAFKA_ACL_EDIT, CLIENT_QUOTA_MANAGEMENT, GRAPHS_ENABLED, FTS_ENABLED, FTS_DEFAULT_ENABLED`
(`Y:2596-2607`). This is Kafbat's ad-hoc capability registry; KUI formalises it (§16).

#### 3.3 Topics

| Method | Path | Params / body | Response | Errors | Perm | Cite |
| --- | --- | --- | --- | --- | --- | --- |
| GET | `/api/clusters/{c}/topics` | `page?, perPage?, showInternal?, search?, orderBy?: TopicColumnsToSort, sortOrder?, fts?` | `TopicsResponse{pageCount, topics: Topic[]}` | — | filter (`TOPIC:VIEW`) | `Y:339-405`, `TopicsController.java:187-231` |
| GET | `/api/clusters/{c}/topics/csv` | same minus paging | csv | — | filter | `T/topics.tsp:28-38` |
| POST | `/api/clusters/{c}/topics` | body `TopicCreation{name*, partitions*, replicationFactor?, configs?}` | 201 `Topic` | 4002/4001 | `TOPIC:CREATE` (on the new name) | `Y:405`, `TopicsController.java:71-77` |
| GET | `/api/clusters/{c}/topics/{t}` | — | `TopicDetails` | 4008 | `TOPIC:VIEW` (or `AUDIT:VIEW`) | `Y:518`, ibid `:164-175` |
| POST | `/api/clusters/{c}/topics/{t}` (recreate) | — | 201 `Topic` | 404, 408 (4015) | `TOPIC:VIEW+CREATE+DELETE` | `Y:540`, ibid `:91-96` |
| PATCH | `/api/clusters/{c}/topics/{t}` | body `TopicUpdate{configs*}` | `Topic` | — | `TOPIC:VIEW+EDIT` | `Y:565`, ibid `:258-265` |
| DELETE | `/api/clusters/{c}/topics/{t}` | — | 200 | 404; 4003 (405) if cluster read-only | `TOPIC:DELETE` | `Y:597`, ibid `:125-131` |
| POST | `/api/clusters/{c}/topics/{t}/clone` | query `newTopicName*` | 201 `Topic` | 404 | `TOPIC:VIEW+CREATE` | `Y:417`, ibid `:107-113` |
| GET | `/api/clusters/{c}/topics/{t}/config` | — | `TopicConfig[]` | — | `TOPIC:VIEW` | `Y:620`, ibid `:143-149` |
| PATCH | `/api/clusters/{c}/topics/{t}/partitions` | body `PartitionsIncrease{totalPartitionsCount* ≥1}` | `PartitionsIncreaseResponse` | 404, 4002 | `TOPIC:VIEW+EDIT` | `Y:2053`, ibid `:277-284` |
| PATCH | `/api/clusters/{c}/topics/{t}/replications` | body `ReplicationFactorChange{totalReplicationFactor*}` | `ReplicationFactorChangeResponse` | 400, 404 | `TOPIC:VIEW+EDIT` | `Y:647`, ibid `:317-325` |
| GET/POST/DELETE | `/api/clusters/{c}/topics/{t}/analysis` | — | GET `TopicAnalysis{progress?\|result?}`; POST 200 "started"; DELETE 200 "cancelled" | 404, 4018 | GET `ANALYSIS_VIEW`; POST/DELETE `ANALYSIS_RUN` | `Y:449-516`, ibid `:337-376` |
| GET | `/api/clusters/{c}/topics/{t}/activeproducers` | — | `TopicProducerState[]` | — | `TOPIC:VIEW` | `Y:989`, ibid `:387-393` |
| GET | `/api/clusters/{c}/topics/{t}/connectors` | — | `FullConnectorInfo[]` | — | `TOPIC:VIEW` | `Y:711`, ibid `:436-442` |
| GET | `/api/clusters/{c}/topics/{t}/consumer-groups` | — | `ConsumerGroup[]` | 404 | `TOPIC:VIEW` + filter groups | `Y:1017`, `ConsumerGroupsController.java:146-166` |
| GET | `/api/clusters/{c}/topics/{t}/acls` | — | `KafkaAcl[]` | — | `TOPIC:VIEW` + `ACL:VIEW` | `T/topics.tsp`, `TopicsController.java:295-301` |
| GET | `/api/clusters/{c}/topic/{t}/serdes` | query `use*: SERIALIZE\|DESERIALIZE` | `TopicSerdeSuggestion{key[], value[]}` | — | `TOPIC:VIEW` | `Y:681` (note singular `topic`; TypeSpec moves it under `/topics/{t}/serdes`, `T/messages.tsp:3-13`) |

**Topic list semantics** (`J/controller/TopicsController.java:187-231`, `J/service/TopicsService.java:470-483`):

- Source is the in-memory statistics cache (`ScrapedClusterState.topicIndex`), not a live
  `listTopics` call; a second `listTopics` pass drops topics deleted since the last scrape.
- `search` is matched by the topic index (`find(search, showInternal, useFts, null)`); n-gram or
  substring depending on `fts` (Finding 2).
- `showInternal` defaults to false: internal topics (`__consumer_offsets`, prefix from
  `kafka.internalTopicPrefix`) are excluded.
- Default `perPage` = 25, `page` defaults to 1, non-positive values reset to defaults.
- `orderBy` default `NAME`; enum `NAME | OUT_OF_SYNC_REPLICAS | TOTAL_PARTITIONS |
  REPLICATION_FACTOR | SIZE | MESSAGES_COUNT` (`Y:2730-2737`).
- **Reference bug worth not copying:** `pageCount` is computed from the list *before* the
  `showInternal` filter is applied, so it can overstate pages when internal topics are hidden
  (`TopicsController.java:213-220`).
- Only the page's topics are then hydrated (`loadTopics`) so `Topic.partitions` etc. are filled
  for the current page only.

#### 3.4 Messages (see Finding 5 for streaming details)

| Method | Path | Params / body | Response | Errors | Perm | Cite |
| --- | --- | --- | --- | --- | --- | --- |
| GET | `/api/clusters/{c}/topics/{t}/messages` (**v1, deprecated**) | `seekType?, seekTo?: string[] ("p::offset" or "p::tsMillis"), limit?, q?, filterQueryType?, seekDirection?, keySerde?, valueSerde?` | SSE `TopicMessageEvent` | **Kafbat throws `ValidationException("Not supported")` → 4002/400** | — | `Y:758-820`, `J/controller/MessagesController.java:79-95` |
| GET | `/api/clusters/{c}/topics/{t}/messages/v2` | `mode?: PollingMode (default LATEST), partitions?: int[], limit?, stringFilter?, smartFilterId?, offset?: int64, timestamp?: int64, keySerde?, valueSerde?, cursor?` | SSE `TopicMessageEvent` | 400 (4002) on missing offset/timestamp for mode, unknown filter id, evicted cursor; 4008 topic not found | `TOPIC:MESSAGES_READ` (or `AUDIT:VIEW`) | `Y:907-987`, `MessagesController.java:96-139` |
| POST | `/api/clusters/{c}/topics/{t}/messages` | body `CreateTopicMessage{partition*, key?, value?, headers?, keySerde?, valueSerde?, keySerdeProperties?, valueSerdeProperties?}` | 200 | 404, 400 | `TOPIC:MESSAGES_PRODUCE` | `Y:849`, ibid `:142-158` |
| DELETE | `/api/clusters/{c}/topics/{t}/messages` | query `partitions?: int[]` (empty = all) | 200 | 404 | `TOPIC:MESSAGES_DELETE` | `Y:823`, ibid `:52-68` |
| POST | `/api/clusters/{c}/topics/{t}/smartfilters` | body `MessageFilterRegistration{filterCode}` | `MessageFilterId{id}` | 4020 on CEL compile error | `TOPIC:MESSAGES_READ` | `Y:876`, ibid `:186-199` |

#### 3.5 Consumer groups

| Method | Path | Params / body | Response | Errors | Perm | Cite |
| --- | --- | --- | --- | --- | --- | --- |
| GET | `/api/clusters/{c}/consumer-groups/paged` | `page?, perPage?, search?, orderBy?: ConsumerGroupOrdering, sortOrder?, fts?, state?: ConsumerGroupState[]` | `ConsumerGroupsPageResponse{pageCount, consumerGroups: ConsumerGroup[]}` | — | filter (`CONSUMER:VIEW`) | `Y:1044-1093`, `T/consumer-groups.tsp:12-25`, `ConsumerGroupsController.java:173-208` |
| GET | `/api/clusters/{c}/consumer-groups/csv` | same | csv | — | filter | `T/consumer-groups.tsp:38-50` |
| GET | `/api/clusters/{c}/consumer-groups/lag` | `ids*: string[], lastUpdate?: int64, includePartitions?: boolean` | `ConsumerGroupsLagResponse{updateTimestamp, consumerGroups: map<groupId, ConsumerGroupLag{lag, topics: map<topic,int64>, topicPartitions?: map<topic,{partitions: map<p,int64>}>}>}` | 404 when nothing | filter | `T/consumer-groups.tsp:27-35`, `ConsumerGroupsController.java:108-144` |
| GET | `/api/clusters/{c}/consumer-groups/{id}` | — | `ConsumerGroupDetails` (= `ConsumerGroup` + `partitions: ConsumerGroupTopicPartition[]`) | — | `CONSUMER:VIEW` | `Y:1095`, ibid `:89-95` |
| DELETE | `/api/clusters/{c}/consumer-groups/{id}` | — | 200 | — | `CONSUMER:DELETE` | `Y:1119`, ibid `:55-61` |
| POST | `/api/clusters/{c}/consumer-groups/{id}/offsets` | body `ConsumerGroupOffsetsReset{topic*, resetType*: EARLIEST\|LATEST\|TIMESTAMP\|OFFSET, partitions?: int[], resetToTimestamp?, partitionsOffsets?: PartitionOffset[]}` | 200 | 4016 (400) if group not EMPTY/DEAD | `CONSUMER:RESET_OFFSETS` + `TOPIC:VIEW` | `Y:1140`, ibid `:243-252` |
| DELETE | `/api/clusters/{c}/consumer-groups/{id}/topics/{t}` | — | 200 | — | `CONSUMER:RESET_OFFSETS` + `TOPIC:VIEW` | `Y:1166`, ibid `:71-79` |

**Paged list semantics** (`J/service/ConsumerGroupService.java:125-150`): `listConsumerGroups`
→ filter by `search`/`fts` → filter by `state[]` → RBAC filter → describe **all** remaining
groups, sort, then page. Default `perPage` = `consumer.groups.page.size` (25); default order
`NAME`, default `ASC`. Ordering enum `NAME | MEMBERS | STATE | MESSAGES_BEHIND | TOPIC_NUM`
(`Y:3120-3127`). `consumerLag` is null when no offsets are committed. The `lag` endpoint exists
so the UI can poll lag for the visible page without re-listing; `lastUpdate` lets the server
return `updateTimestamp` from its cache.

#### 3.6 Schema registry

| Method | Path | Params / body | Response | Errors | Perm | Cite |
| --- | --- | --- | --- | --- | --- | --- |
| GET | `/api/clusters/{c}/schemas` | `page?, perPage?, search?, orderBy?: SUBJECT\|ID\|TYPE\|COMPATIBILITY\|VERSION, sortOrder?, fts?` | `SchemaSubjectsResponse{pageCount, schemas: SchemaSubject[]}` | — | filter (`SCHEMA:VIEW`) | `Y:1224-1270`, `SchemasController.java:213-283` |
| POST | `/api/clusters/{c}/schemas` | body `NewSchemaSubject{subject*, schema*, schemaType*: AVRO\|JSON\|PROTOBUF, references?}` | `SchemaSubject` | 400, 409 duplicate, 422 invalid | `SCHEMA:CREATE` | `Y:1192-1223`, ibid `:80-87` |
| DELETE | `/api/clusters/{c}/schemas/{subject}` | — | 200 | 404 (4009), 4017 | `SCHEMA:DELETE` | `Y:1272` |
| GET | `/api/clusters/{c}/schemas/{subject}/versions` | — | `SchemaSubject[]` | — | `SCHEMA:VIEW` | `Y:1295` |
| GET/DELETE | `/api/clusters/{c}/schemas/{subject}/latest` | — | `SchemaSubject` / 200 | 404 | VIEW / DELETE | `Y:1322` |
| GET/DELETE | `/api/clusters/{c}/schemas/{subject}/versions/{version:int}` | — | `SchemaSubject` / 200 | 404 | VIEW / DELETE | `Y:1369` |
| GET/PUT | `/api/clusters/{c}/schemas/compatibility` | PUT body `CompatibilityLevel{compatibility*}` | `CompatibilityLevel` / 200 | 404 | GET none; PUT `SCHEMA:MODIFY_GLOBAL_COMPATIBILITY` | `Y:1425`, ibid `:169,329-335` |
| PUT | `/api/clusters/{c}/schemas/{subject}/compatibility` | body `CompatibilityLevel` | 200 | 404 | `SCHEMA:EDIT` | `Y:1466` |
| POST | `/api/clusters/{c}/schemas/{subject}/check` | body `NewSchemaSubject` | `CompatibilityCheckResponse{isCompatible*}` | 404 | `SCHEMA:VIEW` | `Y:1494` |

**Schema list semantics** (`SchemasController.java:230-283`): fetch all subject names → RBAC
filter → `SchemasFilter.find(search)` (n-gram or contains) → if `orderBy` is null or `SUBJECT`,
sort names and page **before** fetching latest versions (cheap path); for any other `orderBy` it
fetches latest versions of **all** filtered subjects, sorts, then pages (expensive path). Default
`perPage` 25. `SchemaSubject.version` is a string (`Y:3300`), `id` int.

#### 3.7 Kafka Connect

| Method | Path | Params / body | Response | Errors | Perm | Cite |
| --- | --- | --- | --- | --- | --- | --- |
| GET | `/api/clusters/{c}/connects` | `withStats?: boolean` (default false) | `Connect[]` | — | — (stats nullable when Connect unreachable) | `Y:1526`, `KafkaConnectController.java:47-52` |
| GET | `/api/clusters/{c}/connects/csv` | `withStats?` | csv | — | — | `T/kafka-connect.tsp:11-15` |
| GET | `/api/clusters/{c}/connectors` | `search?, orderBy?: NAME\|CONNECT\|TYPE\|STATUS, sortOrder?, fts?` | `FullConnectorInfo[]` (**no paging**) | — | filter (`CONNECTOR:VIEW`) | `Y:1553`, ibid `:137-163` |
| GET | `/api/clusters/{c}/connectors/csv` | same | csv | — | filter | `T/kafka-connect.tsp:44-54` |
| GET | `/api/clusters/{c}/connects/{k}/connectors` | — | `string[]` (names) | — | `CONNECT:VIEW` | `Y:1595`, ibid `:66-72` |
| POST | `/api/clusters/{c}/connects/{k}/connectors` | body `NewConnector{name*, config*}` | `Connector` | 409 rebalance (4004) | `CONNECT:CREATE` | `Y:1621`, ibid `:85-92` |
| GET | `/api/clusters/{c}/connects/{k}/connectors/{n}` | — | `Connector` | — | `CONNECTOR:VIEW` | `Y:1652` |
| DELETE | ibid | — | 200 | 409 | `CONNECTOR:DELETE` | `Y:1681` |
| POST | `.../connectors/{n}/action/{action}` | path `action: RESTART\|RESTART_ALL_TASKS\|RESTART_FAILED_TASKS\|PAUSE\|RESUME\|STOP` | 200 | 409, 400 | `CONNECTOR:VIEW+OPERATE` | `Y:1708`, ibid `:213-220` |
| GET/PUT | `.../connectors/{n}/config` | PUT body `ConnectorConfig` (free map) | `ConnectorConfig` / `Connector` | 409, 400 | VIEW / VIEW+EDIT | `Y:1741` |
| GET | `.../connectors/{n}/tasks` | — | `Task[]` | — | `CONNECTOR:VIEW` | `Y:1806` |
| POST | `.../connectors/{n}/tasks/{taskId:int}/action/restart` | — | 200 | 400 | `CONNECTOR:VIEW+OPERATE` | `Y:1838` |
| DELETE | `.../connectors/{n}/offsets` | — | 200 | 4021 (400) | `CONNECTOR:VIEW+RESET_OFFSETS` | `Y:1869`, ibid `:317-326` |
| GET | `/api/clusters/{c}/connects/{k}/plugins` | — | `ConnectorPlugin[]{class}` | — | `CONNECT:VIEW` | `Y:1991` |
| PUT | `/api/clusters/{c}/connects/{k}/plugins/{plugin}/config/validate` | body `ConnectorConfig` | `ConnectorPluginConfigValidationResponse` | — | `CONNECT:VIEW` | `Y:2018`, ibid `:284` |

#### 3.8 KSQL

| Method | Path | Params / body | Response | Errors | Perm | Cite |
| --- | --- | --- | --- | --- | --- | --- |
| POST | `/api/clusters/{c}/ksql/v2` | body `KsqlCommandV2{ksql*, streamsProperties?}` | `KsqlCommandV2Response{pipeId*}` | 400 | `KSQL:EXECUTE` | `Y:1895`, `KsqlController.java:33-56` |
| GET | `/api/clusters/{c}/ksql/response` | query `pipeId*` | SSE `KsqlResponse{table: KsqlTableResponse{header, columnNames[], values[][]}}` | 4002 unknown/expired pipe | `KSQL:EXECUTE` | `Y:1964`, ibid `:58-77` |
| GET | `/api/clusters/{c}/ksql/tables` | — | `KsqlTableDescription[]` | 5001 | `KSQL:EXECUTE` | `Y:1920` |
| GET | `/api/clusters/{c}/ksql/streams` | — | `KsqlStreamDescription[]` | 5001 | `KSQL:EXECUTE` | `Y:1942` |

#### 3.9 ACLs and client quotas

| Method | Path | Params / body | Response | Errors | Perm | Cite |
| --- | --- | --- | --- | --- | --- | --- |
| GET | `/api/clusters/{c}/acls` | `resourceType?, resourceName?, namePatternType?, search?, fts?` | `KafkaAcl[]` (no paging) | — | `ACL:VIEW` | `Y:2085`, `AclsController.java:69-99` |
| GET | `/api/clusters/{c}/acls/csv` (`Y`: `/acl/csv`) | same filters | `text/plain` csv | — | `ACL:VIEW` | `T/acls.tsp:23-33`, `Y:2132` |
| POST | `/api/clusters/{c}/acl/csv` | body `text/plain` csv | 200 | 400 | `ACL:EDIT` | `Y:2150` |
| POST | `/api/clusters/{c}/acl` | body `KafkaAcl` | 200 | 400 | `ACL:EDIT` | `Y:2171` |
| DELETE | `/api/clusters/{c}/acl` | **body** `KafkaAcl` | 200 | 404 | `ACL:EDIT` | `Y:2192` |
| POST | `/api/clusters/{c}/acl/consumer` | `CreateConsumerAcl{principal*, host*, topics?\|topicsPrefix?, consumerGroups?\|consumerGroupsPrefix?}` | 200 | — | `ACL:EDIT` | `Y:2214` |
| POST | `/api/clusters/{c}/acl/producer` | `CreateProducerAcl{principal*, host*, topics?\|topicsPrefix?, transactionalId?\|transactionsIdPrefix?, idempotent?}` | 200 | — | `ACL:EDIT` | `Y:2235` |
| POST | `/api/clusters/{c}/acl/streamapp` | `CreateStreamAppAcl{principal*, host*, inputTopics*, outputTopics*, applicationId*}` | 200 | — | `ACL:EDIT` | `Y:2304` |
| GET | `/api/clusters/{c}/clientquotas` | — | `ClientQuotas[]{user?, clientId?, ip?, quotas: map<string,number>}` | — | `CLIENT_QUOTAS:VIEW` | `Y:2256`, `ClientQuotasController.java:48-53` |
| POST | `/api/clusters/{c}/clientquotas` | body `ClientQuotas` | 200 updated / 201 created / 204 deleted (empty `quotas`) | — | `CLIENT_QUOTAS:EDIT` | `Y:2272-2303`, ibid `:69-91` |

ACL list filtering: `resourceType` default `ANY`, `namePatternType` default `ANY`; the three
form a Kafka `ResourcePatternFilter` pushed to the broker, and `search`/`fts` is applied
in-process on the result (`AclsController.java:82-97`).

### 4. Pagination, sorting, filtering and search matrix

| List endpoint | Paged? | Default perPage | Sort enum | Search | Extra filters | Total returned |
| --- | --- | --- | --- | --- | --- | --- |
| topics | yes | 25 | TopicColumnsToSort (6) | yes (fts) | `showInternal` | `pageCount` only |
| consumer-groups/paged | yes | 25 (`consumer.groups.page.size`) | ConsumerGroupOrdering (5) | yes (fts) | `state[]` | `pageCount` only |
| schemas | yes | 25 | SchemaColumnsToSort (5) | yes (fts) | — | `pageCount` only |
| connectors (all) | **no** | — | ConnectorColumnsToSort (4) | yes (fts) | — | full list |
| connects | no | — | — | — | `withStats` | full list |
| acls | **no** | — | — | yes (fts) | resourceType, resourceName, namePatternType | full list |
| clientquotas | no | — | — | — | — | full list |
| brokers, ksql tables/streams, topic sub-lists | no | — | — | — | — | full list |
| messages v2 | cursor (`DONE.cursor.id`) | 100 (`kafka.polling.defaultPageSize`), max 500 | — | `stringFilter`, `smartFilterId` (CEL) | `partitions[]`, `mode`, `offset`, `timestamp` | stream |

All offset-paged lists are computed fully in memory per request, sorted, then sliced: they are
O(n) on the cluster size for every page. `pageCount` is the only total the UI receives.

### 5. Streaming endpoints

#### 5.1 Message browsing v2: event envelope

Content type `text/event-stream`; each SSE `data:` line is one JSON `TopicMessageEvent`
(`T/messages.tsp` `TopicMessageEvent`, `Y:3184-3206`):

```
TopicMessageEvent {
  type: "PHASE" | "MESSAGE" | "CONSUMING" | "DONE",
  phase?:     { name: string },
  message?:   TopicMessage,
  consuming?: { bytesConsumed: int64, elapsedMs: int64, isCancelled: boolean,
                messagesConsumed: int32, filterApplyErrors: int32 },
  cursor?:    { id: string }        // only on DONE, only when more data remains
}
```

Exactly one of `phase | message | consuming | cursor` is populated per type. Observed sequence
for forward/backward polling (`J/emitter/RangePollingEmitter.java:39-63`,
`MessagesProcessing.java:22-63`, `ConsumingStats.java`):

1. `PHASE {name:"Consumer created"}`
2. per polling range: `PHASE {name:"Polling partitions: [0, 1, ...]"}`, then one `CONSUMING`
   per `consumer.poll` (cumulative counters), then zero or more `MESSAGE`
3. `DONE` with cumulative `consuming` and `cursor.id` if the assigned partitions were **not**
   fully polled (`sendFinishStatsAndCompleteSink(sink, pollRange.isEmpty() ? null : cursor)`),
   then the stream completes.

For `TAILING` (`TailingEmitter.java:22-40`): infinite loop of `PHASE {name:"Polling"}`,
`CONSUMING`, `MESSAGE`... until the client disconnects; no `DONE`; UI emission is rate-limited
to 20 msgs/s (`MessagesService.java:64,270-281`).

**There is no `ERROR` event type.** Failures inside the emitter call `sink.error(e)`
(`RangePollingEmitter.java:61`, `TailingEmitter.java:37`), which WebFlux turns into an abrupt
close of the SSE connection *after* the headers were sent; errors before subscription (topic not
found, bad params, missing filter id, evicted cursor) surface as a normal JSON `ErrorResponse`
with a 4xx status because `validateAccess(...).then(Mono.just(ResponseEntity.ok(flux)))` is
evaluated first. Provectus additionally had `EMIT_THROTTLING` in the enum (`PY:2786`) but no
producer of that event survives in its code (grep empty). `TopicMessageConsuming.isCancelled` is
always `false` in Kafbat (`ConsumingStats.java:37`). KUI should add an explicit `Error` event
(§22) rather than rely on connection drops.

`TopicMessage` (`Y:3232-3290`): `partition*`, `offset*`, `timestamp*` (ISO date-time),
`timestampType: NO_TIMESTAMP_TYPE | CREATE_TIME | LOG_APPEND_TIME`, `key`, `value`, `headers:
map<string,string>`, `keySize`, `valueSize`, `headersSize`, `keySerde`, `valueSerde`,
`keyDeserializeProperties`, `valueDeserializeProperties` (free maps, e.g. schema registry
`{type, id, subjects[]}` per `T/messages.tsp` `SchemaRegistryDeserializeProperties`). Deprecated
and no longer filled: `keyFormat`, `valueFormat`, `keySchemaId`, `valueSchemaId`.

#### 5.2 Polling modes, seek resolution and paging

`PollingMode = FROM_OFFSET | TO_OFFSET | FROM_TIMESTAMP | TO_TIMESTAMP | LATEST | EARLIEST |
TAILING`, default `LATEST` (`MessagesController.java:126`). Validation
(`J/model/ConsumerPosition.java`): `FROM_OFFSET/TO_OFFSET` require `offset` (one offset applied
to **all** selected partitions; per-partition offsets are only reachable through a cursor);
`FROM_TIMESTAMP/TO_TIMESTAMP` require `timestamp` (ms). `partitions` empty = all partitions.

Emitter choice (`MessagesService.java:213-256`): `TO_OFFSET | TO_TIMESTAMP | LATEST` →
`BackwardEmitter`; `FROM_OFFSET | FROM_TIMESTAMP | EARLIEST` → `ForwardEmitter`; `TAILING` →
`TailingEmitter`. Seek resolution (`J/emitter/SeekOperations.java:47-107`): empty partitions are
excluded; offsets are clamped to `[beginningOffset, endOffset]`; `offsetsForTimes` misses in
`TO_TIMESTAMP` mode map to the partition end (everything is older than the target).

Backward paging never loads a whole partition: each round reads at most
`ceil(limit / partitionCount)` records per partition, walking windows `[to - n, to)` down to the
beginning offset (`BackwardEmitter.java:24-48`), assigning/seeking/pausing partitions as their
window is exhausted (`RangePollingEmitter.java:66-95`). Records inside a page are merge-sorted by
timestamp across partitions with per-partition offset order preserved
(`MessagesProcessing.java:69-93`).

`limit`: `null`, `≤0` or `> maxPageSize` → `defaultPageSize` (100); `maxPageSize` default 500
(`MessagesService.java:62-63,283-287`). Kafka `max.poll.records` is set to `limit` per request.

**Cursor paging** (`J/emitter/Cursor.java`, `J/service/PollingCursorsStorage.java`): the emitter
tracks the last offset sent per partition; on `DONE` with leftovers it registers a `Cursor`
(deserializer, filter, limit and a new `ConsumerPosition` with `tpOffsets` = last offset (+1 for
forward)) under a random 8-char id in a Guava cache bounded to 10 000 entries (no TTL, LRU
eviction). `GET .../messages/v2?cursor=<id>` **ignores every other query param** and replays
from the stored position; unknown id → `ValidationException("Next page cursor not found. Maybe it
was evicted from cache.")` (4002). Cursors are process-local: they break behind a load balancer
without sticky sessions.

**Filters.** `stringFilter` = case-insensitive contains over key/value/headers
(`MessageFilters.containsStringFilter`). `smartFilterId` = CEL predicate registered via
`POST .../smartfilters`; id = first 8 hex chars of `sha256(code + processSalt)`, cached (10 000
max, no TTL); unknown id → 4002. Filter exceptions per record increment `filterApplyErrors` and
do not abort the stream (`MessagesProcessing.java:35-52`). `PUT /api/smartfilters/testexecutions`
compiles and runs the CEL against a synthetic record and returns `{result}` or
`{error:"Compilation error : ..."}` (`MessagesService.java:106-129`).

**Cancellation.** The emitter runs in a `Flux.create` on `boundedElastic`; every loop checks
`sink.isCancelled()`; the browser closing the `EventSource` cancels the subscription, the loop
exits, and the try-with-resources closes the `KafkaConsumer`
(`RangePollingEmitter.java:39-63`). No server-side timeout or byte budget exists beyond
`limit`, `pollTimeout` (default 1 s, `PollingSettings.java:14`) and the optional per-cluster
`pollingThrottleRate` (bytes/s, `PollingThrottler`).

**Serde selection.** `keySerde`/`valueSerde` name a registered serde; when absent the
`preferred` serde from `GET .../serdes?use=DESERIALIZE` is used (pattern rules → default → auto).
`SerdeDescription{name, description, preferred, schema, additionalProperties, parameters[]}`.

#### 5.3 v1 vs v2

| Aspect | v1 (`/messages`, Provectus) | v2 (`/messages/v2`, Kafbat) |
| --- | --- | --- |
| Seek | `seekType: BEGINNING\|OFFSET\|TIMESTAMP\|LATEST` + `seekTo[]` of `"partition::offset"` or `"partition::tsMillis"` (per-partition positions) | `mode` + one `offset`/`timestamp` for all partitions, `partitions[]` subset |
| Direction | `seekDirection: FORWARD\|BACKWARD\|TAILING` (default FORWARD) | encoded in `mode` (`FROM_*`/`EARLIEST` forward, `TO_*`/`LATEST` backward) |
| Filter | `q` + `filterQueryType: STRING_CONTAINS\|GROOVY_SCRIPT` (Provectus, Groovy gated by config `PJ/controller/MessagesController.java:100-102`); Kafbat enum says `CEL_SCRIPT` | `stringFilter` + pre-registered `smartFilterId` (CEL) |
| Paging | none: UI re-issues with new `seekTo` | server cursor in `DONE` |
| Status in Kafbat | controller throws `ValidationException("Not supported")` (`MessagesController.java:79-95`), `@Deprecated(forRemoval)` since 1.1.0 | live |

KUI needs only the v2 shape (plus Kouncil's table mode, Finding 9); per-partition seek positions
(`seekTo`) are worth keeping as an *optional* extension because v2 lost them.

#### 5.4 KSQL response pipe

Two-step protocol (`J/service/ksql/KsqlServiceV2.java`): `POST /ksql/v2` validates access,
stores `(cluster, ksql, streamsProperties)` under a UUID in a cache with **1-minute TTL**, returns
`pipeId`; `GET /ksql/response?pipeId=` re-checks access, **invalidates** the entry (single use)
and streams the ksqlDB HTTP response as `KsqlResponse{table{header, columnNames, values}}`
events. Push queries (`SELECT ... EMIT CHANGES`) stream until the client disconnects; statements
return one table. Parser restrictions: single statement only; unsupported statements
(`PRINT`, `DEFINE`, `UNDEFINE`) and ksqlDB errors are returned **as an error table row**
(`errorTableWithTextMsg`), not as HTTP errors (`J/service/ksql/KsqlApiClient.java:170-199`). The
reason for the two-step design is that `EventSource` cannot POST a body.

### 6. Error response format and codes

Every 4xx/5xx body is `ErrorResponse{code:int, message, timestamp:number(ms), requestId,
fieldsErrors?: FieldError[]{fieldName, restrictions[]}, stackTrace?}` (`Y:2490-2525`,
`T/responses.tsp:49-71`), produced by `J/exception/GlobalErrorWebExceptionHandler.java`:

- `WebExchangeBindException` (bean validation / query binding) → 400, code 4001, per-field errors.
- `ResponseStatusException` (routing, 404 no route, 403 access denied) → its status, code 5000.
- `CustomBaseException` subclasses → mapped by `ErrorCode`.
- anything else → 500, code 5000, message from the throwable.
- `stackTrace` is included unless `http.error.excludeStackTraces=true` (then the literal
  `"REDACTED FOR SECURITY REASONS"`).

`ErrorCode` (`J/exception/ErrorCode.java:8-32`):

| Code | HTTP | Name | Typical trigger |
| --- | --- | --- | --- |
| 5000 | 500 | UNEXPECTED | any uncaught error |
| 5001 | 500 | KSQL_API_ERROR | list tables/streams header mismatch |
| 5002 | 500 | OAUTH_TOKEN_FETCH_ERROR | serde/registry OAuth |
| 4001 | 400 | BINDING_FAIL | invalid query/body |
| 4002 | 400 | VALIDATION_FAIL | `ValidationException` (missing offset, unknown cursor/filter, v1 API) |
| 4003 | 405 | READ_ONLY_MODE_ENABLE | mutation on `readOnly` cluster |
| 4004 | 409 | CONNECT_CONFLICT_RESPONSE | Connect rebalance in progress |
| 4006 | 422 | UNPROCESSABLE_ENTITY | schema registry 422 |
| 4007 | 404 | CLUSTER_NOT_FOUND | unknown `clusterName` |
| 4008 | 404 | TOPIC_NOT_FOUND | |
| 4009 | 404 | SCHEMA_NOT_FOUND | |
| 4012 | 400 | DIR_NOT_FOUND | log dir alter |
| 4013 | 400 | TOPIC_OR_PARTITION_NOT_FOUND | purge/produce |
| 4014 | 400 | INVALID_REQUEST | |
| 4015 | 408 | RECREATE_TOPIC_TIMEOUT | |
| 4016 | 400 | INVALID_ENTITY_STATE | offset reset on active group |
| 4017 | 500 | SCHEMA_NOT_DELETED | |
| 4018 | 400 | TOPIC_ANALYSIS_ERROR | analysis already running |
| 4019 | 500 | FILE_UPLOAD_EXCEPTION | |
| 4020 | 400 | CEL_ERROR | smart filter compile |
| 4021 | 400 | CONNECTOR_OFFSETS_RESET_ERROR | |
| 404 | 404 | NOT_FOUND | generic |

Kafbat's codes are integers without namespaces; the frontend switches on them. KUI's envelope
(`§26`) uses string codes, `details[]` and `correlationId`; Finding 10 proposes the mapping.

### 7. Auth endpoints, session and cookie behavior

`auth.type = DISABLED | LOGIN_FORM | OAUTH2 | LDAP` selects one Spring WebFlux security chain:

- **DISABLED** (`J/config/auth/DisabledAuthSecurityConfig.java`): everything `permitAll`, CSRF
  off; `/api/authorization` returns `rbacEnabled:false` and no `userInfo`
  (`AuthorizationController.java:57-84`, `switchIfEmpty(builder)`).
- **LOGIN_FORM** (`BasicAuthSecurityConfig.java:24-42`) and **LDAP**
  (`LdapSecurityConfig.java:152-158`): `formLogin` at `/login`; `POST /login`
  (`application/x-www-form-urlencoded` `username`, `password`) answers **200 with empty body**
  on success (`EmptyRedirectStrategy`) and 401 on failure; the SPA then navigates itself. `GET
  /login` serves `index.html` so the SPA renders the form. `GET /logout` invalidates the session
  and redirects to `/auth?logout`. CSRF disabled.
- **OAUTH2/OIDC** (`OAuthSecurityConfig.java:98-125`): `oauth2Login` (redirect flow at
  `/oauth2/authorization/{registrationId}`, callback `/login/oauth2/code/{id}`); optional
  `oauth2ResourceServer` (JWT via `jwkSetUri` or opaque introspection) so API calls can also carry
  `Authorization: Bearer` tokens; logout delegates to provider-specific handlers (Cognito,
  default OIDC RP-initiated logout). `GET /api/config/authentication` lists
  `oAuthProviders[{clientName, authorizationUri}]` for the login page buttons.
- Session: default Spring WebFlux in-memory `WebSession` with the `SESSION` cookie (no
  `spring.session` configuration found; grep across `application*.yml` and `config/` returned
  nothing). Sessions are therefore per-instance and lost on restart, which is why the
  Kafbat Helm chart runs a single replica by default.
- Whitelist without auth (`AbstractAuthSecurityConfig.java:17-48`): static assets, `/metrics`,
  actuator health/info/prometheus, swagger, `/login`, `/logout`, `/oauth2/**`,
  `/api/config/authentication`, `/api/authorization`.
- Principal → roles: `AccessControlService.getUser()` reads the reactive security context; roles
  are matched by subject provider/type/value (with regex support) against `rbac.roles[]`;
  `AuthenticationInfo.userInfo.permissions[]` is the flattened `(clusters[], resource, value,
  actions[])` list (default role permissions when no role matched,
  `AuthorizationController.java:38-58`).

### 8. DTO catalog relevant to the frontend

Field-level detail is in the tables above; this lists every schema name the UI consumes so the
KUI contract can be checked for parity (`Y:2460-4829`, `T/*.tsp`):

- **App:** `ApplicationInfo`, `AppAuthenticationSettings`, `OAuthProvider`, `AuthType`,
  `AuthenticationInfo`, `UserInfo`, `UserPermission`, `Action`, `ResourceType`,
  `ApplicationConfig` (full config tree incl. `rbac.roles[]`, `kafka.clusters[]`, `masking[]`,
  `audit`, `metrics`, `serde[]`), `ApplicationConfigValidation`, `ClusterConfigValidation`,
  `ApplicationPropertyValidation`, `RestartRequest`, `UploadedFileInfo`, `ErrorResponse`,
  `FieldError`.
- **Cluster/broker:** `Cluster` (+`ServerStatus = online|offline|initializing`, `features[]`,
  `controller`), `MetricsCollectionError`, `ClusterStats`, `BrokerDiskUsage`, `ClusterMetrics`,
  `Metric{name, labels, value}`, `Broker{id*, host, port, bytesIn/OutPerSec, partitionsLeader,
  partitions, inSyncPartitions, partitionsSkew, leadersSkew}`, `BrokerMetrics`, `BrokerConfig`,
  `BrokerConfigItem`, `ConfigSource` (8 values), `ConfigSynonym`, `BrokersLogdirs`,
  `BrokerTopicLogdirs`, `BrokerTopicPartitionLogdir`, `BrokerLogdirUpdate`.
- **Topic:** `TopicsResponse`, `Topic`, `TopicDetails`, `Partition{partition*, leader,
  replicas[], offsetMin*, offsetMax*}`, `Replica{broker, leader, inSync}`, `TopicConfig`,
  `TopicCreation`, `TopicUpdate`, `CleanUpPolicy = DELETE|COMPACT|COMPACT_DELETE|UNKNOWN`,
  `PartitionsIncrease(+Response)`, `ReplicationFactorChange(+Response)`, `TopicAnalysis`,
  `TopicAnalysisProgress`, `TopicAnalysisResult`, `TopicAnalysisStats` (with
  `hourlyMsgCounts[]`), `TopicAnalysisSizeStats` (sum/min/max/avg/p50/p75/p95/p99/p999),
  `TopicProducerState`, `TopicColumnsToSort`, `SortOrder`.
- **Messages:** `TopicMessageEvent`, `TopicMessage`, `TopicMessagePhase`,
  `TopicMessageConsuming`, `TopicMessageNextPageCursor`, `CreateTopicMessage`, `PollingMode`,
  `SeekType`, `SeekDirection`, `MessageFilterType`, `MessageFilterRegistration`,
  `MessageFilterId`, `SmartFilterTestExecution(+Result)`, `TopicSerdeSuggestion`,
  `SerdeDescription`, `SerdeParameter`, `SerdeUsage`, `MessageFormat` (deprecated).
- **Consumer groups:** `ConsumerGroup`, `ConsumerGroupDetails`, `ConsumerGroupTopicPartition`,
  `ConsumerGroupState` (6 values), `ConsumerGroupOrdering`, `ConsumerGroupsPageResponse`,
  `ConsumerGroupOffsetsReset`, `ConsumerGroupOffsetsResetType`, `PartitionOffset`,
  `ConsumerGroupsLagResponse`, `ConsumerGroupLag`, `ConsumerGroupTopicLag`.
- **Schemas:** `SchemaSubjectsResponse`, `SchemaSubject`, `NewSchemaSubject`,
  `SchemaReference`, `SchemaType`, `CompatibilityLevel` (7 levels),
  `CompatibilityCheckResponse`, `SchemaColumnsToSort`.
- **Connect:** `Connect`, `FullConnectorInfo`, `Connector`, `NewConnector`, `ConnectorConfig`,
  `ConnectorStatus`, `ConnectorState` (7), `ConnectorType`, `ConnectorAction` (6), `Task`,
  `TaskId`, `TaskStatus`, `ConnectorTaskStatus` (5), `ConnectorPlugin`,
  `ConnectorPluginConfigDefinition`, `ConnectorPluginConfigValue`, `ConnectorPluginConfig`,
  `ConnectorPluginConfigValidationResponse`, `ConnectorColumnsToSort`.
- **KSQL:** `KsqlCommandV2(+Response)`, `KsqlResponse`, `KsqlTableResponse`,
  `KsqlTableDescription`, `KsqlStreamDescription`.
- **Security:** `KafkaAcl` (operation enum 14 values, permission `ALLOW|DENY`),
  `KafkaAclResourceType` (7), `KafkaAclNamePatternType`, `CreateConsumerAcl`,
  `CreateProducerAcl`, `CreateStreamAppAcl`, `ClientQuotas`.
- **Graphs:** `GraphDescriptions`, `GraphDescription{id*, type: range|instant, defaultPeriod,
  parameters[]}`, `GraphParameter`, `GraphDataRequest`, `PrometheusApiQueryResponse` (+base,
  data with `resultType matrix|vector|scalar|string`).

### 9. Provectus deltas (2024-04-08 snapshot)

Path-level diff (`comm` of `PY` vs `Y`): Provectus lacks `/messages/v2`, `/smartfilters`,
`/clientquotas`, `/graphs/*`, `/metrics`, `/metrics/{c}`, `/connectors/{n}/offsets`,
`/consumer-groups/{id}/topics/{t}` (delete offsets), `/topics/{t}/connectors`,
`/config/authentication`, `/login` in the contract; it names the stream-app route
`/acl/streamApp` (camel-case) vs Kafbat `/acl/streamapp`. Schema-level: Provectus has no
`PollingMode`, cursor, `MessageFilterRegistration/Id`, `ClientQuotas`, `Graph*`, `Prometheus*`,
`SchemaColumnsToSort`, `AppAuthenticationSettings`; its `TopicMessageEvent.type` includes
`EMIT_THROTTLING` (`PY:2786`), `MessageFilterType` is `STRING_CONTAINS | GROOVY_SCRIPT`
(`PY:2894-2898`), `ConnectorAction` lacks `STOP` (`PY:3281-3288`), `Action` lacks `OPERATE`,
`RESTART`, `ALL`, `MESSAGES_PRODUCE/DELETE` split, and `ResourceType` lacks `CONNECTOR` and
`CLIENT_QUOTAS` (`PY:3626-3655`). Provectus v1 messages: defaults `seekType=BEGINNING`,
`seekDirection=FORWARD`, `filterQueryType=STRING_CONTAINS`; per-partition `seekTo` parsed by
`parseSeekTo` (`PJ/controller/MessagesController.java:83-129`). Everything else is identical in
shape; Kafbat is a strict superset except for Groovy filters (replaced by CEL) and the v1 browse
endpoint (kept in the contract, disabled in the controller).

### 10. Kouncil endpoints (event tracking and table browsing)

Kouncil is Spring MVC (servlet), `@RolesAllowed` on system-function names, all cluster scoping by
a `serverId` query param (cluster id). Error handling: any exception → HTTP 500 with the raw
message as `text/plain`; `SchemaRegistryNotConfiguredException` → 400
(`C/KouncilControllerAdvisor.java`). No JSON error envelope.

**Table-style browsing** (`C/topic/TopicController.java`, `C/topic/TopicService.java:70-131`):

```
GET /api/topic/messages/{topicName}/{partition}?page=&limit=&beginningTimestampMillis?=&endTimestampMillis?=&offset?=&serverId=
  partition: integer or "all"
  page, limit: strings parsed to numbers; BOTH are per partition
  → TopicMessagesDto { messages: TopicMessage[], partitionOffsets: map<p,int64>,
                       partitionEndOffsets: map<p,int64>, totalResults: int64 }
  TopicMessage { topic, partition, offset, timestamp(ms), key, keyFormat, value, originalValue,
                 valueFormat, headers: [{key,value}] }   (MessageFormat = JSON|AVRO|PROTOBUF|STRING…)
```

Semantics: newest-first paging computed per partition as `position = end - limit*(page-1)`,
`seekTo = max(position - limit, beginning)`, then poll up to `limit` records per partition; the
optional timestamp window / explicit `offset` narrows `[beginning, end]` before paging.
`totalResults` is the sum of per-partition ranges so the UI can show a total and page count. JSON
column flattening (the "table view") is done client-side from `value`; the backend keeps
`originalValue` (pre-masking) and applies data-masking policies server-side. Permission
`TOPIC_MESSAGES`.

Other topic operations: `POST /api/topic/send/{topic}/{count}` body `TopicMessage`
(produce `count` copies), `POST /api/topic/resend` body
`TopicResendEventsModel{sourceTopicName, sourceTopicPartition, offsetBeginning, offsetEnd,
destinationTopicName, destinationTopicPartition (-1 = any), shouldFilterOutHeaders}`
(permission `TOPIC_RESEND_MESSAGE`), `POST /api/topic/create` and `PUT
/api/topic/partitions/update` body `TopicData{name, partitions, replicationFactor}`,
`GET/DELETE /api/topic/{topicName}`, `GET /api/topic/is-topic-exist/{topicName}`,
`GET /api/topics?serverId=` → `TopicsDto{topics: [{name, partitions}]}`.

**Event tracking** (`C/track/TrackController.java`, `C/track/TrackService.java:38-95`,
`C/track/EventMatcher.java`):

```
GET /api/track/sync?topicNames[]=&field=&operator=&value=&beginningTimestampMillis=&endTimestampMillis=&serverId=
  → TopicMessage[]  (sorted by timestamp, capped by EVENTS_SANITY_LIMIT = 1000)
GET /api/track/async?...same...&asyncHandle=<clientToken>
  → 202-like void; results pushed over STOMP WebSocket (/ws endpoint, simple broker prefixes
    /topic and /notifications) to destination "/topic/track/<asyncHandle>" as batches of
    TopicMessage[]; an EMPTY array marks end of stream; server stops when the STOMP
    subscription disappears or 1000 events were sent.
GET /api/track/stats  → WebSocket broker statistics (debug)
```

Matching: `field` empty → match against the deserialized **value** string; `field` non-empty →
match against the header with that key (first match only). `operator` is a frontend index
`0..4` = `LIKE (contains) | NOT_LIKE | IS (equals) | NOT_IS | REGEX (String.matches)`. Algorithm:
for each topic compute `[offsetForTime(begin), offsetForTime(end))` per partition, sort topics
by total range size, scan sequentially with a single consumer, stop after 5 empty polls per
topic. There is no key-based matching and no cross-topic join: "tracking" is a multi-topic
time-window scan with one predicate; correlation across topics is left to the user reading the
merged, timestamp-sorted result. Permission `TRACK_LIST`.

**Consumer groups:** `GET /api/consumer-groups?serverId=` → `{consumerGroups:[{groupId,
status}]}`; `GET /api/consumer-group/{groupId}?serverId=` → `{consumerGroupOffset:[{consumerId,
clientId, host, partition, topic, offset, endOffset}]}`; `DELETE /api/consumer-group/{groupId}`;
`POST /api/consumer-group/{groupId}/reset` body `{serverId, resetType: EARLIEST|LATEST|TIMESTAMP|
OFFSET_NUMBER, offsetNo?, timestampMillis?}` (`C/consumergroup/*.java`).

**Brokers/clusters:** `GET /api/brokers?serverId=` → `{brokers:[{id, host, port, rack, system,
availableProcessors, freeMem, totalMem, systemLoadAverage, jmxStats}]}` (JMX enrichment);
`GET /api/configs/{brokerId}?serverId=`; `GET /api/clusters` → `{clusters:[ClusterDto{id, name,
brokers[], clusterSecurityConfig, schemaRegistry, globalJmx*}]}`; cluster CRUD at
`/api/cluster` (POST/PUT/DELETE/{id}), `POST /api/cluster/test-connection`,
`GET /api/cluster/{name}/is-cluster-name-unique` (`C/clusters/*`, `C/broker/BrokersController.java`).

**Schemas** (topic-centric, not subject-centric): `GET /api/schemas/latest/{topicName}?serverId=`
→ `{keyMessageFormat, keyPlainTextSchema, valueMessageFormat, valuePlainTextSchema}`;
`GET /api/schemas/{serverId}?topicNames[]=` → `SchemaDTO[]{messageFormat, plainTextSchema,
topicName, subjectName, version, subjectType, versionsNo[], compatibility}`;
`GET/DELETE /api/schemas/{serverId}/{subject}/{version}`; `POST/PUT /api/schemas/{serverId}`
body `SchemaDTO`; `POST /api/schemas/test-compatibility/{serverId}`; `GET /api/schemas/configs`
(`C/schema/registry/SchemaRegistryController.java`).

**Auth:** `GET /api/active-provider` → `"inmemory" | "ldap" | "sso"`; `POST /api/login` JSON
`{username,password}` → boolean, servlet session cookie `JSESSIONID`; `GET /api/logout`;
`GET /api/user-roles` → `string[]` of system functions; `GET /api/installation-id`
(`C/security/AuthController.java`). Permissions are flat system functions
(`C/model/admin/SystemFunctionNameConstants.java`), not resource-pattern based.

---

## Proposed KUI `/api/v1` mapping

Conventions applied everywhere:

- Base `/api/v1`. Cluster scope is `/clusters/{clusterId}` where `clusterId` is a typed opaque
  id (`ClusterId`, slug of the configured name) rather than the display name; the gateway
  resolves it once and forwards `X-Kui-Cluster-Id`.
- Names: `kebab-case` paths, plural nouns, sub-resources nested; actions are `POST
  .../{id}:verb`-style is **rejected** in favour of explicit sub-resources (`/actions/restart`)
  to keep Tapir paths simple.
- Lists: `page`/`pageSize` **and** `pageToken` are offered; responses carry
  `{ items, page: { totalItems?, pageCount?, nextPageToken? } }`. Offset paging is kept where
  the reference does in-memory sorting (small n, needed for column sorting); cursor paging is
  introduced where the data is naturally ordered by offset/time (messages, track results,
  audit).
- Search: `q` (substring), `mode=fts|plain` (replaces boolean `fts`), `sort=<field>:<asc|desc>`.
- Errors: string codes (Finding 6 → `KUI-*`), `correlationId` = gateway request id.
- Streaming: SSE with named `event:` field = `phase | message | consumed | done | error |
  heartbeat`; `id:` = cursor so `Last-Event-ID` reconnects work.

| Reference endpoint(s) | KUI `/api/v1` | Owner (§15) | Gateway role | Changes / notes |
| --- | --- | --- | --- | --- |
| `GET /api/clusters` | `GET /clusters` | cluster-service + capability registry | **aggregation, partial** | Each item carries `status` and `capabilities[]` from the registry; a down cluster-service yields items from cached config with `status: unavailable`. |
| `POST /clusters/{c}/cache` | `POST /clusters/{id}/refresh` | cluster-service | proxy | 202 Accepted, async. |
| `GET .../stats`, `.../metrics` | `GET /clusters/{id}/stats`, `GET /clusters/{id}/metrics` | cluster-service / metrics-service | proxy | Dashboard aggregation `GET /clusters/{id}/dashboard` returns `{stats, brokers, metrics, topicsSummary}` each `Ok | Unavailable(reason)` — **partial by design**. |
| brokers, configs, logdirs | `GET /clusters/{id}/brokers`, `GET/PUT .../brokers/{brokerId}/configs[/{name}]`, `GET .../brokers/{brokerId}/metrics`, `GET .../log-dirs?brokerId=`, `PATCH .../brokers/{brokerId}/log-dirs` | cluster-service (metrics from metrics-service) | proxy; broker page is an aggregation (partial) | `brokerId: BrokerId` typed int. |
| graphs descriptions / prometheus | `GET /clusters/{id}/graphs`, `POST /clusters/{id}/graphs/query` | metrics-service | proxy | Optional capability `GRAPHS`. |
| `/metrics`, `/metrics/{c}` | `GET /metrics`, `GET /metrics/clusters/{id}` (outside `/api/v1`) | metrics-service | proxy (no auth, allowlisted) | |
| topics list/csv | `GET /clusters/{id}/topics?q&mode&showInternal&sort&page&pageSize` (+ `Accept: text/csv`) | topic-service | proxy | Fix `pageCount` bug; add `page.totalItems`. CSV via content negotiation instead of `/csv`. |
| create/get/update/delete/recreate/clone | `POST /topics`, `GET/PATCH/DELETE /topics/{topic}`, `POST /topics/{topic}/recreate`, `POST /topics/{topic}/clone {newName}` | topic-service | proxy | `TopicName` typed newtype; clone body instead of query param. |
| config, partitions, replications | `GET /topics/{topic}/config`, `PATCH /topics/{topic}/partitions`, `PATCH /topics/{topic}/replication-factor` | topic-service | proxy | |
| analysis | `GET/POST/DELETE /topics/{topic}/analysis` | topic-service | proxy | Long-running; expose progress over SSE `GET .../analysis/stream` later. |
| active producers | `GET /topics/{topic}/producers` | topic-service | proxy | |
| topic connectors / consumer groups / acls | `GET /topics/{topic}/overview` | gateway | **aggregation, partial** | Sections `topic` (topic-service), `consumerGroups` (consumer-service), `connectors` (connect-service), `acls` (security-service), `schemas` (schema-service, Kouncil-style latest key/value schema). Individual endpoints stay available as proxies. |
| serdes suggestion | `GET /topics/{topic}/serdes?use=` | message-service | proxy | |
| messages v2 SSE | `GET /topics/{topic}/messages/stream?mode&partitions&offset&timestamp&limit&q&filterId&keySerde&valueSerde&cursor` | message-service | streaming fan-in, cancellation propagated | Add `seekTo[]` per-partition option, `error` event, `heartbeat`, `id:`-based cursor, server time/byte budget. Cursor must be self-describing (signed token) so it survives instance changes. |
| messages v1 | **REJECTED** (Kafbat itself disabled it) | — | — | Covered by the stream endpoint + `seekTo[]`. |
| Kouncil table browse | `GET /topics/{topic}/messages/page?partitions&page&pageSize&from&to&offset` → `{items, partitionOffsets, partitionEndOffsets, totalItems}` | message-service | proxy | Non-streaming, newest-first per-partition paging; feeds the table/flattened view. Flattening stays client-side; optional `flatten=true` returns dotted columns for CSV. |
| produce / purge | `POST /topics/{topic}/messages`, `DELETE /topics/{topic}/messages?partitions=` | message-service | proxy (audited) | Add `count` (Kouncil send-N). |
| Kouncil resend | `POST /topics/{topic}/messages/resend` | message-service | proxy (audited) | Body = Kouncil `TopicResendEventsModel` renamed (`source{topic,partition,fromOffset,toOffset}`, `destination{topic,partition?}`, `keepHeaders`). |
| smart filters register/test | `POST /clusters/{id}/message-filters` → `{filterId}`, `POST /message-filters/test` | message-service | proxy | Test endpoint gains cluster scope + `TOPIC:MESSAGES_READ` check (Kafbat has none). TTL-bound cache. |
| Kouncil track sync/async | `GET /clusters/{id}/events/track/stream?topics[]&from&to&field&op&value&limit` (SSE) and `GET .../events/track` (sync, capped) | message-service | streaming fan-in | Replaces STOMP/WebSocket with SSE; `op = contains|notContains|equals|notEquals|regex`; add `key` matching and `correlationKey` grouping in the response (`{events[], groups?}`) as a KUI extension. `done` event replaces empty array sentinel. |
| consumer groups paged/csv/lag | `GET /clusters/{id}/consumer-groups?q&mode&state[]&sort&page&pageSize`, `GET .../consumer-groups/lag?ids[]&includePartitions` | consumer-service | proxy | Keep offset paging (column sorting). |
| group get/delete/reset/delete offsets | `GET/DELETE /consumer-groups/{groupId}`, `POST /consumer-groups/{groupId}/offsets/reset`, `DELETE /consumer-groups/{groupId}/topics/{topic}/offsets` | consumer-service | group page is an aggregation (partial: group + topic partitions from topic-service) | `GroupId` typed. |
| schemas list/csv | `GET /clusters/{id}/schemas?q&mode&sort&page&pageSize` | schema-service | proxy | Keep the cheap path (sort by subject before hydration); document cost of other sorts. |
| schema CRUD / versions / compatibility / check | `POST /schemas`, `GET/DELETE /schemas/{subject}`, `GET /schemas/{subject}/versions[/{version}|/latest]`, `DELETE ...`, `GET/PUT /schemas/compatibility`, `PUT /schemas/{subject}/compatibility`, `POST /schemas/{subject}/compatibility-check` | schema-service | proxy | `version` typed int; Kouncil topic→schema lookup as `GET /topics/{topic}/schemas`. |
| connects, connectors (all), csv | `GET /clusters/{id}/connects?withStats`, `GET /clusters/{id}/connectors?q&mode&sort&page&pageSize` | connect-service | proxy; `connects?withStats` is **partial** (per-connect `status`) | Add paging to the all-connectors list (Kafbat has none). |
| connector CRUD/config/tasks/actions/offsets/plugins/validate | `GET/POST /connects/{connect}/connectors`, `GET/DELETE /connects/{connect}/connectors/{name}`, `GET/PUT .../config`, `GET .../tasks`, `POST .../actions/{action}`, `POST .../tasks/{taskId}/restart`, `DELETE .../offsets`, `GET /connects/{connect}/plugins`, `POST /connects/{connect}/plugins/{plugin}/validate` | connect-service | proxy | 409 rebalance → `KUI-CONNECT-REBALANCING`. |
| ksql execute / pipe / tables / streams | `POST /clusters/{id}/ksql/queries` → `{queryId}`, `GET /ksql/queries/{queryId}/stream` (SSE), `GET /ksql/tables`, `GET /ksql/streams` | ksql-service | streaming fan-in | Keep two-step design (EventSource cannot POST); TTL 1 min, single-use; ksql errors become an `error` **row** event as today plus `error` SSE event on transport failure. |
| acls list/csv/create/delete/csv-sync/convenience | `GET /clusters/{id}/acls?resourceType&resourceName&patternType&q&mode`, `POST /acls`, `DELETE /acls` (body) or `POST /acls/delete`, `GET/PUT /acls/csv`, `POST /acls/presets/{consumer|producer|stream-app}` | security-service | proxy | DELETE-with-body is fragile through proxies: prefer `POST /acls/delete`. |
| client quotas | `GET /clusters/{id}/client-quotas`, `PUT /client-quotas` (200/201/204 kept) | security-service | proxy | |
| `/api/info` | `GET /info` | gateway | local | Build info + `enabledFeatures`. |
| `/api/config*` | `GET /config`, `PUT /config/validate`, `PUT /config/apply`, `POST /config/files` | config-service | proxy | Restart semantics replaced by hot reload + config distribution. |
| `/api/config/authentication`, `/api/authorization` | `GET /auth/settings`, `GET /auth/me` | identity-service (cached in gateway) | local + proxy | `/auth/me` returns `{rbacEnabled, user?{name, permissions[]}}`; unauthenticated → `401` when auth enabled, anonymous principal when disabled. |
| `/login`, `/logout`, oauth2 | `POST /auth/login` (JSON), `POST /auth/logout`, `GET /auth/oauth2/{provider}/start`, `GET /auth/oauth2/callback` | gateway (edge auth) | local | Session cookie `kui_session` (HttpOnly, SameSite=Lax), server-side session store pluggable (in-memory → Redis) so multiple gateway replicas work; bearer tokens accepted for API clients. |
| Kouncil clusters CRUD / test-connection | `POST/PUT/DELETE /clusters`, `POST /clusters/test-connection` | config-service | proxy | Same feature as Kafbat config wizard, cluster-granular. |
| Kouncil user-groups / policies / SSO providers | `DEFERRED` to identity-service (RBAC UI) and message-service (masking policies) design | — | — | Not in M0–M5 scope. |
| capabilities (new) | `GET /capabilities`, `GET /capabilities/stream` | gateway | local | §16. |

Endpoints where the gateway **must return partial results**: `GET /clusters` (per cluster
status), `GET /clusters/{id}/dashboard`, `GET /clusters/{id}/brokers/{brokerId}` (metrics
section), `GET /topics/{topic}/overview`, `GET /consumer-groups/{groupId}` page aggregation,
`GET /connects?withStats`, and `GET /capabilities`. Each section is
`{status: "ok" | "unavailable", reason?, data?}`.

Error code mapping proposal: `4007→KUI-CLUSTER-NOT-FOUND`, `4008→KUI-TOPIC-NOT-FOUND`,
`4009→KUI-SCHEMA-NOT-FOUND`, `4003→KUI-READ-ONLY`, `4004→KUI-CONNECT-REBALANCING`,
`4001/4002→KUI-VALIDATION` (with `details[]` as field errors), `4016→KUI-INVALID-STATE`,
`4015→KUI-TIMEOUT`, `4020→KUI-FILTER-COMPILE`, `4021→KUI-CONNECTOR-OFFSETS`,
`5001→KUI-UPSTREAM-KSQL`, `5002→KUI-UPSTREAM-AUTH`, `5000→KUI-INTERNAL`; plus new
`KUI-UPSTREAM-UNAVAILABLE` (circuit open), `KUI-FORBIDDEN`, `KUI-UNAUTHENTICATED`,
`KUI-CURSOR-EXPIRED`.

## Decision candidates

| Decision | Evidence | Tradeoff | Reversibility |
| --- | --- | --- | --- |
| Model the KUI contract on Kafbat's TypeSpec (`T`) rather than the legacy yaml (`Y`) | Finding 1: build depends on `T`; `Y` lacks 9 endpoints and newer fields | `T` has no line-level docs for a few enums; both must be read | High (documentation only) |
| Adopt Kafbat v2 polling modes + Kouncil per-partition page mode as the two browse APIs; reject v1 | Finding 5.3, Finding 10 | Two code paths in message-service; but both share seek resolution | Medium |
| SSE with named events including `error` and `heartbeat`; cursor in `id:` | Finding 5.1 (no error event, connection drop on failure) | Slightly larger frames; standard EventSource reconnect semantics for free | High |
| Self-describing signed cursor instead of server cache id | Finding 5.2 (process-local Guava cache, no TTL) | Cursor size grows with partition count (~20 B/partition); must be encrypted if offsets are sensitive | Medium |
| Replace Kouncil STOMP/WebSocket track with SSE | Finding 10 (STOMP only used for one-way push) | WebSocket reserved for KSQL bidirectional needs only (§17) | High |
| Keep offset paging for topics/groups/schemas/connectors, add `totalItems`, cursor only for messages/track | Finding 4 (in-memory sort needs full set anyway) | Offset paging is O(n) per request; acceptable for ≤10⁴ items, needs caching beyond | High |
| Gateway-level smart-filter test endpoint requires a cluster + topic permission | Finding 3.1 (Kafbat's has none, allows arbitrary CEL execution by any authenticated user) | Slight UX cost: filter editor needs topic context (it already has it) | High |
| Session store pluggable from day one (in-memory default) | Finding 7 (Kafbat single-replica sessions) | One more config knob | High |
| `DELETE` with body (ACL delete) replaced by `POST /acls/delete` | Finding 3.9 | Deviates from reference UI client; trivial | High |

## Open questions

1. Should `clusterId` be a slug of the display name (readable URLs, matches Kafbat) or a stable
   generated id (survives rename)? Recommendation: slug now, rename = new id, ADR needed.
2. Kouncil's "event tracking" is a filtered multi-topic scan, not a key-correlated trace
   (Finding 10). KUI must decide whether to add key/correlation-id grouping as a
   first-class feature or keep Kouncil's semantics; the contract above reserves `groups?`.
3. Kafbat's audit-topic permission swap (`AUDIT:VIEW` instead of topic permissions) needs an RBAC
   design decision in KUI (identity-service): keep, or model the audit topic as a normal topic
   with a deny-by-default rule.
4. Whether `GET /api/config` (full config including secrets fields, `Y:4100-4829`) should ever be
   exposed unmasked; Kafbat returns it to `APPLICATIONCONFIG:VIEW` holders.
5. Prometheus proxy (`graphs/prometheus`) forwards raw PromQL responses; KUI may want a typed
   `GraphData` instead (`RESEARCH`, metrics-service).

## Confidence

**High** for the Kafbat endpoint inventory, streaming semantics, error codes and auth flow: all
statements are read from the current clone (contract, controllers, emitters, security configs)
with citations. **High** for Provectus deltas at the contract level (mechanical diff), **medium**
for Provectus runtime behavior (only the messages controller was read in depth). **Medium-high**
for Kouncil: controllers, DTOs and the track/browse services were read fully, but data-masking
policy endpoints and user-group management were only inventoried, not analysed. The KUI mapping
is a proposal for the architects and remains subject to §15/§20 ADRs.
