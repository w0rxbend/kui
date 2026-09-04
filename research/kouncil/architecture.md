# Consdata Kouncil — reference architecture

- **Title:** Kouncil backend/frontend architecture analysis
- **Date:** 2026-09-03

## Questions

1. How are Kouncil's backend and frontend structured?
2. How does table-style record browsing work end to end (backend paging model, frontend JSON flattening)?
3. How does event tracking across topics work (matching, sync vs async delivery, limits)?
4. How are consumer groups monitored?
5. How is the Schema Registry integrated and how are records (de)serialized?
6. Which authentication providers exist and how is authorization modelled?
7. What is the configuration model (YAML vs database, cluster CRUD from the UI)?
8. What should KUI take from it, and where does it fit in the microservice + gateway topology?

## Method and sources

- Local shallow clone `/tmp/kui-ref/consdata`, commit `6e2fb85e6ceac813c39f762eecd2f4bce1b31faf` (2026-08-04, repository archived that day), per `research/REFERENCES.md`.
- `cat -n`, `grep -n`, `ls -R` over `kouncil-backend/src/main/java/com/consdata/kouncil`, `kouncil-backend/src/main/resources`, `kouncil-frontend/apps/kouncil/src/app`, `kouncil-frontend/libs`, and `docs/`. Citations are `path:line` relative to the clone root. (`find` over `kouncil-frontend` hangs on `node_modules`; directory listings were done with `ls`.)

## Findings

### F1. Structure

- Maven reactor with three modules: `kouncil-backend`, `kouncil-frontend` (built through a Maven wrapper around Nx/Yarn), and the root packaging (`pom.xml` has 4 `module` entries). Parent is `spring-boot-starter-parent` **3.4.0**, Java **17** (`pom.xml:12-15,21-23`).
- Backend: **Spring MVC (servlet, blocking)**, not WebFlux: `spring-boot-starter-web`, `spring-boot-starter-websocket`, `spring-kafka`, `spring-boot-starter-data-jpa` with **Flyway + PostgreSQL/H2**, `spring-boot-starter-security`, `spring-security-ldap`, `spring-boot-starter-oauth2-client`, `okta-spring-boot-starter`, Confluent Avro/Protobuf/JSON-Schema serializers, `aws-msk-iam-auth`, `springdoc-openapi-ui`, `aspectjweaver` (`kouncil-backend/pom.xml` artifact list). 133 classes.
- Backend packages (`ls -R kouncil-backend/src/main/java/com/consdata/kouncil`):

| Package | Responsibility |
| --- | --- |
| root | `KafkaConnectionService` (client factory/cache), `MessagesHelper` (offset math, header mapping, topic validation), `KouncilControllerAdvisor` (error mapping), `InfoController`, `CustomTomcatConfiguration` |
| `broker` | `BrokersController`, `BrokerJXMClient` (JMX system metrics per broker), `KafkaBrokerConfig` |
| `clusters` (+ `converter`, `dto`) | JPA `ClusterRepository`, `ClusterService` (CRUD + connection test), `ClustersService` |
| `config` (+ `cluster`, `database`, `security/{inmemory,ldap,ad,sso}`) | `KouncilConfiguration` (runtime cluster map), `ClusterConfigReader` (YAML → DB import), `WebSocketConfig` (STOMP), `FlywayMigration`, security chains per provider |
| `consumergroup` | list/details/delete/reset offsets |
| `datamasking` (+ `converter`, `dto`) | DB-backed masking policies, `PolicyApplier` |
| `model/{admin,cluster,datamasking,schemaregistry}` | JPA entities: `Cluster`, `Broker`, `SchemaRegistry`, `UserGroup`, `SystemFunction`, `Policy*` |
| `notifications` | STOMP push of UI notifications |
| `schema` (+ `clusteraware`, `registry`) | `SchemaRegistryFacade`, `SchemaRegistryClientBuilder`, `SchemaAwareClusterService`, `SchemaRegistryController` |
| `security` (+ `function`, `group`) | login/logout/user-roles endpoints, first-time-launch temporary admin, user-group and system-function CRUD |
| `serde` (+ `deserialization`, `formatter/schema`, `serialization`) | magic-byte detection, per-format formatters |
| `survey` | in-app survey controller |
| `topic` (+ `util`) | topic list/CRUD, message paging, send with placeholders, resend |
| `track` | event tracking across topics, sync/async strategies, STOMP destination bookkeeping |
| `logging` | `@EntryExitLogger` AOP aspect |

- Frontend: **Angular 18.2 + Angular Material 18.2 + RxJS 7.8**, Nx monorepo with one app (`apps/kouncil`) and feature/common libraries (`libs/{schema-registry, resend-events, message-data, feat-user-groups, feat-topics, feat-topic-form, feat-send, feat-notifications, feat-no-data, feat-first-time-app-launch, feat-favourites, feat-data-masking, feat-confirm, feat-clusters, feat-breadcrumb, common-utils, common-servers, common-model, common-login, common-components, common-auth}`) (`kouncil-frontend/package.json:20-22,41`; `ls kouncil-frontend/libs`). App screens: `access-denied, banner, broker(s), consumers, demo, login, main, oauth, page-not-found, routing, schemas, sidebar, survey, toolbar, topic, track` (`ls kouncil-frontend/apps/kouncil/src/app`). A **demo mode** with fake services exists for every backend service (`*.demo.service.ts`). WebSocket client is `rx-stomp` (`apps/kouncil/src/app/rx-stomp-service-factory.ts`, `rx-stomp.config.ts`).

### F2. Kafka client management

`KafkaConnectionService` (`kouncil-backend/src/main/java/com/consdata/kouncil/KafkaConnectionService.java`):

- One cached `AdminClient` and one cached Spring `KafkaTemplate<Bytes,Bytes>` per cluster id (`:31-33,39-66`), built from Spring Boot `KafkaProperties` of the cluster plus the first broker's address as bootstrap (`config/KouncilConfiguration.java:59-75`); reconnect backoff fixed at 5 s/10 s (`:27-28`).
- SASL settings (mechanism, protocol, JAAS config, callback handler) are looked up **per broker host:port** and copied into client properties (`:68-86`).
- Consumers are **never cached**: `getKafkaConsumer(serverId, limit)` builds a `KafkaConsumer<Bytes,Bytes>` with `enable.auto.commit=false`, `auto.offset.reset=latest`, `max.poll.records=limit` (`:88-104`).
- `cleanAdminClients()` drops all admin clients when cluster config changes (`:106-108`).

### F3. Table-style record browsing

**Backend** (`topic/TopicController.java:29-42`, `topic/TopicService.java:70-224`):

- Endpoint `GET /api/topic/messages/{topicName}/{partition}?page&limit&beginningTimestampMillis&endTimestampMillis&offset&serverId` where `partition` is `all` or a comma list; **page and limit are per partition** (`TopicService.java:79-80`). Requires the `TOPIC_MESSAGES` function (`TopicController.java:29`).
- One consumer per request. For each selected partition the service computes `[beginningOffset, endOffset)` from the optional time window or single offset (`MessagesHelper.java:36-79`: an explicit `offset` yields the range `[offset, offset+1)`; timestamps use `offsetsForTimes`, with "no offset for time" mapped to `-1` for the beginning (partition skipped) and to the global end for the end).
- Paging is **newest-first by page number**: `position = end - limit*(page-1)`, `seekTo = max(position - limit, begin)`; the consumer is assigned one partition at a time, seeks, and polls until `limit` records, 5 consecutive empty 200 ms polls, or the end offset is reached (`TopicService.java:107-117,174-224`). Records at or beyond `endOffset` are dropped.
- Each record is deserialized, then masked with the policies applicable to `(cluster, topic)`; the response carries both masked `value` and masked `originalValue` (raw string) plus `keyFormat`/`valueFormat` (`:195-211`).
- Result set is sorted by timestamp across partitions; response `TopicMessagesDto{messages[], partitionOffsets{p→begin}, partitionEndOffsets{p→end}, totalResults}` where `totalResults` is the **maximum partition range**, used by the UI to compute page count (`:121-130`; `apps/kouncil/src/app/topic/topic-messages.ts:3-9`).
- Topic list filters out names matching `kouncil.topics.exclude-regex-patterns` (default `__.*`, `topic/TopicsService.java:27-51`; `resources/kouncil.yaml:33-34`).

**Frontend JSON flattening** (`apps/kouncil/src/app/topic/json-grid.ts`):

- `JsonGrid.replaceObjects(rows)` builds a dynamic column set from every message: headers become `H[<key>]` columns, key JSON properties `K[<path>]`, value JSON properties `V[<path>]` (`:53-123,241-243`).
- Nested objects are flattened as dotted paths, arrays as `[i]`, down to **depth 3**; arrays with more than 10 items or objects with more than 100 keys collapse into a placeholder cell (`[Array of N elements]`), scalar strings are truncated to 100 chars and HTML-escaped (`:13-17,133-180`).
- Column headers get a shortened form (`abc~.def~.last`) for display (`:182-196`); columns are ordered headers → keys → values, with top-level fields before nested ones (`:205-216`).
- The grid keeps at most 1000 rows and flags rows newer than the previous load as "fresh" for highlighting during live refresh (`:13-14,198-203,218-222`).
- Every row also keeps the raw Kouncil fields (`kouncilKey`, `kouncilOffset`, `kouncilPartition`, `kouncilTopic`, `kouncilTimestamp`, `kouncilValue`, `kouncilOriginalValue`, formats, headers) for the detail drawer, copy and "resend" actions (`:96-108`; docs `docs/features/TOPICS.md:17-28`).
- The Angular service polls `/api/topic/messages/...` with a `BehaviorSubject<Page{pageNumber,size}>` and re-fetches on pagination or partition change (`apps/kouncil/src/app/topic/topic.backend.service.ts:19-94`).

**Send and resend** (`TopicService.java:226-317`): sending `count` copies of a message with `{{count}}`, `{{timestamp}}`, `{{uuid}}` placeholders in key, value and headers (`:294-317`, `topic/util/PlaceholderFormatUtil`); resend copies a contiguous offset range of one source partition to a destination topic/partition, optionally keeping only headers listed in `resendHeadersToKeep`, validating the range against the partition's begin/end offsets (`:226-292`). Both require the `TOPIC_SEND_MESSAGE` / `TOPIC_RESEND_MESSAGE` functions.

### F4. Event tracking across topics

- Endpoint family `/api/track` (`track/TrackController.java:36-75`): `GET /sync` returns a list; `GET /async?asyncHandle=<id>` submits the same search to an executor and streams batches over **STOMP WebSocket** to `/topic/track/<asyncHandle>`; `GET /stats` dumps WebSocket broker statistics and active destinations. Parameters: `topicNames[]`, `field` (header name or empty = message value), `operator`, `value`, `beginningTimestampMillis`, `endTimestampMillis`, `serverId`. Requires `TRACK_LIST`.
- Matching (`track/EventMatcher.java:13-48`): if `field` is set, the **header with that key** is compared; otherwise the **deserialized value string** is compared. Operators: `LIKE` (contains), `NOT_LIKE`, `IS` (equals), `NOT_IS`, `REGEX` (`track/TrackOperator.java:8-13`, wire values are indexes `"0".."4"`).
- Algorithm (`track/TrackService.java:38-134`): one consumer with `max.poll.records=5000`; for each topic compute per-partition `[begin,end)` from the time window; **topics are processed in ascending order of total range size** (`:45`), assign all partitions, seek, poll with a growing timeout (`100ms × (emptyPolls+1)`) until every partition passed its end offset or 5 empty polls; every matching record becomes a `TopicMessage` candidate; candidates are handed to the strategy after each poll (`:89`).
- Strategies (`track/TrackStrategy.java:7-15`): `SyncTrackStrategy` accumulates, stops at a sanity limit of 1000 events and returns them sorted by timestamp (`track/SyncTrackStrategy.java:11-37`); `AsyncTrackStrategy` sends each non-empty batch (sorted) to the STOMP destination, stops when the client unsubscribed or 1000 events were sent, and sends an **empty list as end-of-stream marker** (`track/AsyncTrackStrategy.java:25-50`).
- Client liveness is tracked by `DestinationStore` (session id → destination) updated from STOMP subscribe/unsubscribe events (`track/DestinationStore.java:8-31`; `config/WebSocketConfig.java:62-74`). The STOMP endpoint is `/ws`, simple broker prefixes `/topic` and `/notifications`, allowed origins from `allowedOrigins` (default `*`) (`WebSocketConfig.java:27-48`; `docs/configuration/WEBSOCKET.md`).
- The frontend toggles sync/async mode and subscribes to `/topic/track/<handle>` before calling `/async` (`apps/kouncil/src/app/track/track.backend.service.ts:16-46`). Results reuse the JSON grid (F3).

### F5. Consumer group monitoring

- `GET` list: `listConsumerGroups` + `describeConsumerGroups` → `{groupId, status}` (`consumergroup/ConsumerGroupService.java:38-51`).
- `GET` details: `listConsumerGroupOffsets` for committed offsets; member assignments join `clientId/consumerId/host` onto each partition row; a throwaway consumer fetches `endOffsets` so the UI can compute lag per partition (`:53-92`). The Angular `cached-cell` component stores previous values to render deltas/velocity between refreshes (`apps/kouncil/src/app/consumers/cached-cell/*`; `docs/features/CONSUMER_GROUPS.md`).
- Delete group (`:94-96`); **reset offsets** to `EARLIEST|LATEST|TIMESTAMP|OFFSET_NUMBER` for all partitions currently committed, refused if the group has active members; a timestamp with no offset falls back to latest (`:99-145`).

### F6. Schema Registry and serialization

- One `SchemaRegistryFacade` per cluster that has a registry, built lazily from the cluster entity with a `CachedSchemaRegistryClient` (basic auth, SSL truststore/keystore, and namespaced `schema.registry.ssl.*` configs) and holding one formatter per format (`schema/clusteraware/SchemaAwareClusterService.java:19-58`; `schema/registry/SchemaRegistryClientBuilder.java:25-71`). Reloaded whenever cluster config changes (`clusters/ClusterService.java` → `reloadConfig`, lines ~70-75).
- Subject naming is fixed to `<topic>-key` / `<topic>-value` (`schema/registry/TopicUtils`, used at `SchemaRegistryFacade.java:41,58,70`).
- Deserialization (`serde/deserialization/DeserializationService.java:30-85`): if the cluster has a registry and the payload starts with magic byte `0` the next 4 bytes are the schema id; the schema **for that id** is fetched via the cached client (not "latest", `:75-79`) and the schema type (`AVRO|PROTOBUF|JSON`) selects the formatter; otherwise the payload is treated as a UTF-8 string. Result carries `deserialized`, `originalValue` and `messageFormat` (`serde/MessageFormat.java`). Serialization mirrors it (`serde/serialization/SerializationService.java:30`).
- Registry management endpoints (`schema/registry/SchemaRegistryController.java:26-77`): list latest schemas for topics, get by subject+version, create (refused if the subject exists), update (register new version), delete version, test compatibility (temporarily switching subject compatibility and restoring it, `SchemaRegistryFacade.java:128-140`), with per-function permissions `SCHEMA_LIST/DETAILS/CREATE/UPDATE/DELETE`.

### F7. Authentication and authorization

- Provider selected by `kouncil.auth.active-provider` ∈ `inmemory | ldap | ad | sso`, each with its own `SecurityFilterChain` under `@ConditionalOnProperty` (`config/security/inmemory/InMemoryWebSecurityConfig.java:25-30`; `ldap/LdapWebSecurityConfig.java:31-93`; `ad/ActiveDirectoryWebSecurityConfig.java:27-73`; `sso/SSOWebSecurityConfig.java:38-79`). Defaults in `resources/kouncil.yaml:16-32`; docs in `docs/configuration/security/*.md`.
- `inmemory`: default users `admin/editor/viewer` with password = username, forced first-login password change flow and a `UserManager` abstraction (`inmemory/InMemoryUserManager.java:42-176`; `config/security/UserManager.java:5-14`).
- `ldap`/`ad`: bind or technical-user search, group search parameters (`kouncil.auth.ldap.{provider-url, search-base, search-filter, group-search-base, group-search-filter, group-role-attribute, technical-user-*}`, `kouncil.auth.ad.{domain, url, search-filter}`).
- `sso`: Spring OAuth2 client with registrations for **GitHub** (teams fetched through the GitHub GraphQL API and used as groups, optionally limited to organisations) and **Okta**; `/api/sso/providers` lists configured providers (`sso/CustomOAuth2UserService.java:20-37`; `sso/SSOProvidersController.java:16-29`; `docs/configuration/security/GITHUB.md`).
- **Authorization is database-driven RBAC**: IdP groups are mapped to `user_group` rows; each group has a set of `system_function`s; `UserRolesMapping` turns the user's groups into granted authorities named after functions plus `ROLE_<group>` (`security/UserRolesMapping.java:17-33`). Controllers use `@RolesAllowed("<FUNCTION>")` (e.g. `topic/TopicController.java:29,44,56,65,72,79,86,93`). Function catalog: topic list/create/update/delete/messages/send/resend, brokers list/details, consumer group list/delete/details, track list, schema list/details/create/update/delete, cluster list/create/update/details/delete, login, user-group CRUD, policy CRUD (`model/admin/SystemFunctionName.java:3-55`; seeded by `resources/db/migration/V2__create_roles_functions_tables.sql`). Legacy `kouncil.authorization.role-admin/editor/viewer` lists pre-populate groups (`docs/configuration/security/AUTHORIZATION.md`).
- First application launch: a temporary admin session is created so an operator can add clusters and groups before any provider is fully configured (`security/FirstTimeApplicationLaunchService.java:19-53`).
- Errors: a single `@ControllerAdvice` maps any exception to **500 with the raw message as a text body**, `SchemaRegistryNotConfiguredException` to 400, and swallows broken-pipe write errors from closed WebSockets (`KouncilControllerAdvisor.java:13-36`). There is no structured error envelope.

### F8. Configuration model

- **Two sources merged into the database**: on start `ClusterConfigReader` (bound to `kouncil.clusters[]` for the "advanced" YAML, or `bootstrapServers`/`schemaRegistryUrl` env vars for the "simple" form) upserts `Cluster/Broker/SchemaRegistry` JPA entities, mapping Spring `KafkaProperties` SSL and per-broker SASL/JAAS strings into typed security columns (`config/cluster/ClusterConfigReader.java:47-276`; tables in `resources/db/migration/V1__create_cluster_tables.sql`). H2 in-memory is the default DB; PostgreSQL is configured with standard `spring.datasource.*` (`docs/configuration/DATABASE.md`); Flyway runs with `baselineOnMigrate` (`config/database/FlywayMigration.java:17-28`).
- `KouncilConfiguration` materialises the runtime `Map<clusterName, ClusterConfig{kafka: KafkaProperties, brokers[], schemaRegistry, jmxPort/user/password}>` from the DB at start and on every change; it also writes a persistent installation id file (`config/KouncilConfiguration.java:43-112`; `config/ClusterConfig.java:12-33`).
- **Cluster CRUD from the UI** (`clusters/ClusterService.java:33-75`, docs `docs/configuration/KAFKA_CLUSTER.md`): save/delete cluster then `reloadConfig()` (re-read map, rebuild schema registry facades, drop cached admin clients); `testConnection` temporarily swaps the config map, tries `describeCluster` with 5 s/10 s timeouts, and restores it — a non-thread-safe but restart-free flow. Security choices in the form: cluster auth `NONE|SASL|SSL|AWS_MSK`, protocol, SASL mechanism (incl. `AWS_MSK_IAM` with profile name), key/trust stores; registry auth `NONE|SSL|SSL_BASIC_AUTH` (`model/cluster/ClusterSecurityConfig.java:13-49`; `model/schemaregistry/*`).
- Data-masking policies are also DB entities (`policy`, `policy_field`, `policy_resource`, `policy_user_groups`; `V8__data_masking.sql`) with `MaskingType ∈ ALL|FIRST_5|LAST_5`, applied to JSON field paths (including array elements) or to the whole value (`datamasking/PolicyApplier.java:21-98`; `model/datamasking/MaskingType.java`).
- Per-broker JMX: `BrokerJXMClient` reads system MBeans through RMI with optional credentials to show CPU/memory per broker (`broker/BrokerJXMClient.java:22-48`; `docs/configuration/JMX.md`).
- Misc: `allowedOrigins`, `resendHeadersToKeep`, `kouncil.topics.exclude-regex-patterns`, custom context path (`docs/configuration/CUSTOM_CONTEXT_PATH.md`).

### F9. Tests

Unit/slice tests only, no Testcontainers (`grep` for `Testcontainers|KafkaContainer` is empty): serde round-trips for Avro/JSON-Schema/Protobuf/none (`kouncil-backend/src/test/java/com/consdata/kouncil/serde/*Test.java`), `EventMatcherTest`, `TopicServiceTest`, `ConsumerGroupServiceTest`, `PolicyApplierTest`, security context tests per provider (`Kouncil{InMemory,Ldap,ActiveDirectory,SSO}ApplicationTests.java`).

## Decision candidates for KUI

**D1. Table-style browsing is a presentation transform in the messages microfrontend, fed by the same message stream as the classic view.**
Decision: `kui-message-service` keeps emitting Kafbat-style events; the messages microfrontend implements Kouncil's flattening rules (`H[]/K[]/V[]` columns, depth 3, list/object collapse thresholds, 100-char cell truncation, fresh-row flag, 1000-row window) client-side. The service adds only what the grid needs and the browser cannot compute cheaply: `valueKind` (`Text|Json`) — already in `DeserializeResult` — and the raw original value alongside the masked one.
Evidence: F3 (`apps/kouncil/src/app/topic/json-grid.ts:13-17,53-180`; `topic/TopicService.java:195-211`).
Tradeoff: exposing `originalValue` must respect masking (Kouncil masks both); KUI must never send unmasked bytes.
Reversibility: high.

**D2. Adopt Kouncil's per-partition page model as an additional polling mode rather than replacing cursors.**
Decision: add to the message contract a `PAGE` request shape (`partitions`, `pageNumber`, `pageSizePerPartition`, optional time window) that the service translates into Kafbat-style backward ranges; response includes per-partition begin/end offsets and a `maxPartitionRange` for page-count display. This gives Kouncil's "jump to page N newest-first" UX without server state.
Evidence: F3 (`topic/TopicService.java:107-117,121-130`).
Tradeoff: page N is computed from current end offsets, so pages shift while producers write (Kouncil accepts this); document it.
Reversibility: high (additive).

**D3. Event tracking becomes a first-class `kui-message-service` use case streamed over SSE through the gateway, with the sanity limit and end-of-stream marker preserved.**
Decision: `POST/GET /clusters/{id}/tracking` with `topics[]`, `window`, `match{source: value|header(name), operator: contains|notContains|equals|notEquals|regex, value}`; implementation scans topics smallest-range-first, one fs2 stream, batches sorted by timestamp, hard cap (configurable, default 1000), terminal `DONE` event. Also accept a CEL predicate as an alternative matcher so the smart-filter engine is reused, in `kui-message-service`.
Evidence: F4 (`track/TrackService.java:38-134`; `track/EventMatcher.java:13-48`; `track/AsyncTrackStrategy.java:25-50`).
Tradeoff: Kouncil's WebSocket/STOMP transport is replaced by SSE fan-in; the "client unsubscribed → stop" behavior maps to SSE cancellation. Scanning whole topics without an index is expensive; the time window is mandatory in KUI.
Reversibility: high.

**D4. Resend (copy an offset range to another topic) joins produce in the message service; placeholder templating (`{{count}}`, `{{uuid}}`, `{{timestamp}}`) is a frontend feature.**
Decision: service endpoint `resend{sourceTopic, sourcePartition, fromOffset, toOffset, destTopic, destPartition?, keepHeaders?[]}` with range validation; bulk-send with templates is done by the microfrontend expanding N requests, or by a `count`+template DSL only if research H finds it heavily used.
Evidence: F3 (`topic/TopicService.java:226-317`).
Tradeoff: server-side templating keeps parity but adds a mini-DSL to the contract.
Reversibility: high.

**D5. Consumer-group lag "velocity" is a frontend cache, not a service feature.**
Decision: `kui-consumer-service` returns committed and end offsets per partition (as Kafbat does); the consumer-groups microfrontend keeps the previous sample to show deltas (Kouncil's `cached-cell`).
Evidence: F5 (`consumergroup/ConsumerGroupService.java:53-92`; `apps/kouncil/src/app/consumers/cached-cell/*`).
Tradeoff: none.
Reversibility: high.

**D6. Offset reset guard: refuse resets while the group has active members; timestamp fallback to latest.**
Decision: adopt as a domain rule in `kui-consumer-service` (Kafbat performs the same check in `OffsetsResetService`; Kouncil states it explicitly).
Evidence: F5 (`consumergroup/ConsumerGroupService.java:110-114,133-135`).
Tradeoff: none.
Reversibility: high.

**D7. Cluster CRUD from the UI with a persisted store is the model for `kui-config-service`, but the store is a versioned file/K8s secret, not a relational DB, and the "test connection" flow must not mutate live config.**
Decision: reproduce Kouncil's UX (cluster form with typed security choices `NONE|SASL|SSL|AWS_MSK`, registry auth `NONE|SSL|SSL_BASIC_AUTH`, test connection, save → reload) on top of Kafbat's YAML model; validation uses a **throwaway client built from the candidate config** (as Kafbat's `KafkaClusterFactory.validate` does) instead of Kouncil's swap-and-restore of the global map.
Evidence: F8 (`clusters/ClusterService.java:33-63`; `config/cluster/ClusterConfigReader.java:63-117`).
Tradeoff: no relational DB dependency means no Flyway; user groups (D8) need another store.
Reversibility: medium.

**D8. RBAC model stays Kafbat's, but KUI adds Kouncil's "manage groups and permissions in the UI" as an identity-service feature backed by the same YAML/secret store.**
Decision: `kui-identity-service` exposes group→permission editing that writes roles in Kafbat's YAML shape; Kouncil's coarse function catalog (`SystemFunctionName`) maps onto Kafbat resources/actions (e.g. `TOPIC_MESSAGES` → `TOPIC.MESSAGES_READ`, `TRACK_LIST` → `TOPIC.MESSAGES_READ` on all tracked topics, `POLICY_*` → masking admin, `CLUSTER_*` → `CLUSTERCONFIG`/`APPLICATIONCONFIG`).
Evidence: F7 (`model/admin/SystemFunctionName.java:3-55`; `security/UserRolesMapping.java:17-33`).
Tradeoff: two storage shapes if a DB is later introduced; keep the YAML canonical.
Reversibility: medium.

**D9. Masking policies editable in the UI and scoped to user groups is a Kouncil-only capability worth scheduling.**
Decision: extend Kafbat's masking config with an optional `subjects`/groups scope and a management endpoint in `kui-message-service` (policies are applied there), storing policies in the config store; masking types `ALL|FIRST_5|LAST_5` map to Kafbat `MASK` with a prefix/suffix option.
Evidence: F8 (`datamasking/PolicyApplier.java:92-98`; `V8__data_masking.sql`).
Tradeoff: policies become per-cluster config objects that need RBAC (`POLICY_*` functions in Kouncil).
Reversibility: high.

**D10. Auth providers: Kouncil's `inmemory` first-run flow (temporary admin, forced password change) is worth copying for the all-in-one shape; GitHub-teams-as-groups and Okta are covered by Kafbat's OAuth extractors.**
Decision: `kui-identity-service` supports a bootstrap admin when `auth.type=LOGIN_FORM` and no users are configured, with mandatory password rotation and an "installation id".
Evidence: F7 (`security/FirstTimeApplicationLaunchService.java:19-53`; `inmemory/InMemoryUserManager.java:48-176`; `config/KouncilConfiguration.java:100-112`).
Tradeoff: adds a small persisted user store to the identity service.
Reversibility: high.

**D11. Do not copy Kouncil's error handling, blocking MVC, or "consumer per request with fixed 200 ms poll and 5 empty polls" heuristics.**
Decision: KUI keeps Kafbat's structured errors and the seek/pause range polling; Kouncil's empty-poll heuristics indicate why explicit end-offset bounds (Kafbat) are preferable.
Evidence: F3/F7 (`topic/TopicService.java:170-224`; `KouncilControllerAdvisor.java:13-36`).
Tradeoff: none.
Reversibility: n/a.

## Open questions

1. Event tracking scans entire topics within a time window; should KUI require an upper bound on scanned bytes/records (Kafbat's polling throttler) and expose progress events (`CONSUMING`) during tracking? Likely yes; confirm against the UX findings in `research/kafbat/ui-analysis.md` and `research/kouncil/ui-analysis.md`.
2. Kouncil's "resend" writes raw bytes to another topic; with Schema Registry the destination subject may not exist. Decide whether KUI validates schema compatibility before resend.
3. Should the `PAGE` mode (D2) be per-partition (Kouncil) or global (page over the merged timeline)? Per-partition is cheap; global needs cursors.
4. Kouncil's DB-backed masking scope by user group implies masking decisions depend on the principal; KUI's message service would need the principal's groups (available via the signed principal header) — confirm this is acceptable for the "defense in depth" rule.

## Confidence

**High** for backend structure, browsing/tracking algorithms, consumer group logic, serde/registry handling, security providers and configuration model — all read from source with line references. **Medium** for the frontend beyond `json-grid.ts`, `topic.backend.service.ts`, `track.backend.service.ts` and directory listings (component templates were not read). **Medium** for `ClusterService.reloadConfig` line numbers (~70-75, file tail was cut).
