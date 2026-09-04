# Provectus Kafka UI vs Kafbat Kafka UI — architectural and feature diff

- **Title:** What Kafbat added, removed and renamed relative to its Provectus origin
- **Date:** 2026-09-03

## Questions

1. What changed structurally (build system, modules, packages, classes) between Provectus `83b5a60` (2024-04-08) and Kafbat `fa485c2` (2026-09-02)?
2. Which API endpoints and schemas were added, removed or renamed in the OpenAPI contract?
3. Which features are Kafbat-only (contract-typespec, MCP, CEL filters, quotas, graphs, FTS, Prometheus exposition, Azure/GCP auth, MessagePack/MM2 serdes, paging cursors, config auto-reload) and which Provectus behaviors were dropped (Groovy filters, v1 message API, hand-written SR models)?
4. What does this imply for KUI's parity target and architecture?

## Method and sources

- `/tmp/kui-ref/provectus` at `83b5a60cc08501b570a0c4d0b4cdfceb1b88d6b7` (last commit 2024-04-08) and `/tmp/kui-ref/kafbat` at `fa485c2bd45cac713cd994c62bc2d458abd3f328` (last commit 2026-09-02), per `research/REFERENCES.md`.
- `diff` of sorted directory trees and class lists under `kafka-ui-api/src/main/java/com/provectus/kafka/ui` vs `api/src/main/java/io/kafbat/ui`; `diff` of sorted OpenAPI path and schema names between `kafka-ui-contract/src/main/resources/swagger/kafka-ui-api.yaml` and `contract/src/main/resources/swagger/kafbat-ui-api.yaml`; `diff` of the `ClustersProperties` field lists; `diff` of `Serde.java`; grep for `groovy`, `glue`, `cel`, `lucene`, `mcp`; inspection of `pom.xml` / `build.gradle` / `package.json`. Citations are `provectus/<path>:<line>` and `kafbat/<path>:<line>` relative to each clone root.

## Findings

### F1. Build, toolchain and module names

| Aspect | Provectus | Kafbat | Evidence |
| --- | --- | --- | --- |
| Build | Maven multi-module: `kafka-ui-contract`, `kafka-ui-api`, `kafka-ui-serde-api`, `kafka-ui-e2e-checks` | Gradle: `contract`, `contract-typespec`, `serde-api`, `api`, `frontend` (+ non-Gradle `e2e-playwright`, `documentation`) | `provectus/pom.xml:7-10`; `kafbat/settings.gradle:9-13` |
| Java | 17 | 25 | `provectus/pom.xml:14`; `kafbat/build.gradle:11-12` |
| Spring Boot | 3.1.3 | 3.5.16 | `provectus/pom.xml:39`; `kafbat/gradle/libs.versions.toml:2` |
| Package root | `com.provectus.kafka.ui` | `io.kafbat.ui` | class paths |
| Main class count | 258 | 319 | `find ... -name '*.java' | wc -l` |
| Contract source | hand-maintained OpenAPI YAML (4091 lines) | TypeSpec (`contract-typespec/api/*.tsp`) compiled to OpenAPI (4829 lines); YAML kept as fallback when `-Ptypespec=false` | `kafbat/build.gradle:38`; `kafbat/contract/build.gradle:24-28` |
| E2E | Selenide (Java, Selenoid) | Playwright | `provectus/kafka-ui-e2e-checks/pom.xml:151-152`; `kafbat/e2e-playwright/` |
| Frontend | React 18.1, react-query 4, react-table 8.5, Vite 4, styled-components 5, zustand 4.1 | React 18.2, react-query 5.95, react-table 8.21, Vite 6.4, styled-components 6.3, zustand 4.5, TypeScript 5.9 | `provectus/kafka-ui-react-app/package.json:13-48`; `kafbat/frontend/package.json:12-103` |
| Kafka test container | `org.testcontainers.containers.KafkaContainer` | `org.testcontainers.kafka.ConfluentKafkaContainer` with JMX enabled and extra listener | `provectus/kafka-ui-api/src/test/java/com/provectus/kafka/ui/AbstractIntegrationTest.java:23,38`; `kafbat/api/src/test/java/io/kafbat/ui/AbstractIntegrationTest.java:29,47-57` |

### F2. Backend dependency deltas

Provectus `kafka-ui-api/pom.xml` artifact set (grep of `<artifactId>`): kafka-clients, confluent serializers/SR client, avro, aws-msk-iam-auth, **groovy-json + groovy-jsr223**, antlr4, datasketches, micrometer-registry-prometheus, reactor-extra, opendatadiscovery, spring-security-ldap/oauth2, testcontainers, mockwebserver, json-schema-validator.

Kafbat `api/build.gradle` adds: **CEL** (`:77`), **Caffeine** (`:78`), **Lucene** core/queryparser/analysis (`:82-84`), **MCP Spring WebFlux SDK** (`:104`), **victools jsonschema-generator** (`:105`, for MCP tool schemas), **Prometheus metrics core/textformats/pushgateway** (`:116-120`), **azure-identity** and **google managed-kafka login handler + google-oauth-client** (`:72-74,108-114`), **msgpack core/jackson** (`:68-69`), **fastcsv** (`:86`), **commons-pool2/compress/text** (`:58-60,100`), **snappy + lz4 (yawk fork)** (`:102,121`), **wiremock** (`:149`), **bouncycastle bcpkix** (`:145`), spring-boot-starter-actuator + oauth2 resource server (`:39,42`), springdoc webflux (`:46`). Groovy is gone.

### F3. Package and class deltas (backend)

Directory diff (`find -type d`, sorted):

- **Added in Kafbat**: `config/auth/azure`, `serdes/builtin/mm2`, `service/app`, `service/graphs`, `service/index`, `service/index/lucene`, `service/mcp`, `service/metrics/prometheus`, `service/metrics/scrape{,/inferred,/jmx,/prometheus}`, `service/metrics/sink`, `service/quota`, `service/ssl`.
- **Removed**: `model/schemaregistry` (hand-written SR response models replaced by the generated `kafka-sr-api.yaml` client — Provectus already shipped that YAML, so this is cleanup), `config/auth/condition` (`ActiveDirectoryCondition`).

Class-level diff highlights (full `diff` of sorted class lists):

| Area | Provectus | Kafbat |
| --- | --- | --- |
| Controllers | `AccessController`, `AuthController` | renamed `AuthorizationController`, `AuthenticationController`; added `ClientQuotasController`, `GraphsController`, `PrometheusExposeController` |
| Emitters | `ResultSizeLimiter` (post-hoc predicate limiting `MESSAGE` events, `provectus/.../emitter/ResultSizeLimiter.java`) | removed; limit enforced inside `MessagesProcessing`; added `Cursor` (next-page cursors) and `service/PollingCursorsStorage` |
| Filters | `MessageFilters` with `STRING_CONTAINS` and **`GROOVY_SCRIPT`** evaluated by a `GroovyScriptEngineImpl` (`provectus/.../emitter/MessageFilters.java:6,15,20,27-30,41-42,81-90`) | `MessageFilters` with substring + **CEL** (`kafbat/.../emitter/MessageFilters.java:100-202`); new `CelException` |
| Exceptions | `ConnectNotFoundException`, `DuplicateEntityException`, `KsqlDbNotFoundException`, `SchemaFailedToDeleteException`, `UnprocessableEntityException`, `KafkaConnectConflictReponseException` (sic) | removed the first five; renamed to `KafkaConnectConflictResponseException`; added `CelException`, `ConnectorOffsetsResetException`, `OAuthTokenFetchException`, `UnknownSchemaTypeException` |
| Models | `BrokerMetrics`, `InternalBrokerDiskUsage`, `InternalClusterMetrics`, `InternalSegmentSizeDto`, `MetricsConfig`, `model/connect/InternalConnectInfo` | replaced by `MetricsScrapeProperties`, `InternalQuorumInfo`, `model/connect/InternalConnectorInfo`; RBAC gains `DefaultRole`, `ClientQuotaAction`, `ConnectorAction` |
| Serdes | 12 built-ins (`String`, `SchemaRegistry`, `ProtobufFile`, `ProtobufRaw`, `Int/UInt32/64`, `AvroEmbedded`, `Base64`, `Hex`, `UuidBinary`, `ConsumerOffsets`) | + `MessagePackSerde`, `StructSerde`, `mm2/{Heartbeat,OffsetSync,Checkpoint,MirrorMaker}Serde`, `sr/FormatterProperties` |
| Metrics | `service/metrics/{MetricsCollector, MetricsRetriever, JmxMetricsRetriever, PrometheusMetricsRetriever, PrometheusEndpointMetricsParser, WellKnownMetrics, JmxMetricsFormatter, JmxSslSocketFactory}` — a flat collector producing a fixed "well-known" set (`provectus/.../service/metrics/MetricsCollector.java:22-61`) | restructured into `scrape/{MetricsScraper, ScrapedClusterState, BrokerMetricsScraper, PerBrokerScrapedMetrics, IoRatesMetricsScanner, KafkaConnectState, jmx/*, prometheus/*, inferred/*}`, `sink/{MetricsSink, PrometheusPushGatewaySink}`, `prometheus/PrometheusMetricsExposer`, `SummarizedMetrics` |
| Services | — | added `app/ConfigReloadService`, `CsvWriterService`, `graphs/*` (5), `index/*` (13), `mcp/*` (2), `quota/*` (2), `PollingCursorsStorage`, `ssl/*` (2), `rbac/extractor/RbacActiveDirectoryAuthoritiesExtractor` |
| Util | `SslPropertiesUtil` | renamed `KafkaClientSslPropertiesUtil`; added `MultiFileWatcher`, `OAuthTokenProvider/Cache/Response`, `StaticFileWebFilter`, `YamlNullSkipRepresenter`, `MetadataVersion`, `MetricsUtils`, `ContentUtils`, `ConsumerGroupUtil`, `CustomSslSocketFactory`, `annotation/CsvIgnore` |
| Config | — | added `GeneralSecurityConfig` (strict firewall), `JsonSchemaConfig`, `McpConfig`, `mapper/DynamicConfigMapper`, `mapper/QuorumInfoMapper` |

Unchanged architecture: Spring WebFlux monolith, `ClustersStorage` immutable map, `StatisticsCache` + 30 s `ClustersStatisticsScheduler` (`provectus/.../service/ClustersStatisticsScheduler.java:19`), Forward/Backward/Tailing emitters (Kafbat's `ForwardEmitter` differs only by the cursor parameter and a `max(1, ...)` guard, per `diff`), RBAC `AccessContext` pattern, `DynamicConfigOperations` wizard with restart, audit service, masking policies, `ReactiveFailover`, `RetryingKafkaConnectClient`.

### F4. Serde plugin API deltas

`diff` of `Serde.java` (package names normalized): Kafbat **adds** `default Serializer serializer(topic, type, Map<String,Object> properties)`, `default List<SerdeParameter> getParameters(topic, type)`, `default boolean couldBePreferable(topic, type)`, and `Serializer.serialize(String input, Headers headers)`; adds the `SerdeParameter` class. All additions are default methods, so **Provectus-era plugin jars remain source- and binary-compatible with Kafbat** apart from the package rename (`com.provectus.kafka.ui.serde.api` → `io.kafbat.ui.serde.api`), which does break old jars.

AWS Glue: neither repository contains a Glue serde (`grep -ril glue` on both `src/main/java` trees is empty). Both READMEs mention "AWS Glue or Smile" as ready-made **external** serde plugins (`provectus/README.md:47`; `kafbat/README.md:58`). "AWS Glue serde" as previously assumed is therefore an out-of-tree plugin that targets this API, not a Provectus feature to diff.

### F5. OpenAPI contract deltas

Paths: Provectus 58, Kafbat 70 (`diff` of sorted `^  /` lines):

- **Added in Kafbat**: `/api/clusters/{clusterName}/clientquotas`; `/api/clusters/{clusterName}/connects/{connectName}/connectors/{connectorName}/offsets` (connector offsets reset); `/api/clusters/{clusterName}/consumer-groups/{id}/topics/{topicName}` (delete group offsets for a topic); `/api/clusters/{clusterName}/graphs/descriptions` and `/graphs/prometheus`; `/api/clusters/{clusterName}/topics/{topicName}/connectors` (connectors related to a topic); `/api/clusters/{clusterName}/topics/{topicName}/messages/v2`; `/api/clusters/{clusterName}/topics/{topicName}/smartfilters` (register CEL filter → id); `/api/config/authentication` (auth settings for the login page); `/login`; `/metrics`; `/metrics/{clusterName}`.
- **Renamed**: `/acl/streamApp` → `/acl/streamapp`.
- **Kept but deprecated**: `/topics/{topicName}/messages` (v1) exists in both contracts; Kafbat's implementation throws "Not supported" (`kafbat/api/.../controller/MessagesController.java:78-92`).

Schemas added in Kafbat (`diff` of `^    Name:` lines): `AppAuthenticationSettings`, `AuthType`, `OAuthProvider`, `ClientQuotas`, `ClusterMetricsStoreConfig`, `GraphDataRequest`, `GraphDescription(s)`, `GraphParameter`, `MessageFilterId`, `MessageFilterRegistration`, `PollingMode`, `PrometheusApi*Response*`, `SchemaColumnsToSort`, `TopicMessageNextPageCursor`. No schema was removed; Provectus's `SeekType{BEGINNING,OFFSET,TIMESTAMP,LATEST}` + `SeekDirection{FORWARD,BACKWARD,TAILING}` + `MessageFilterType{STRING_CONTAINS,GROOVY_SCRIPT}` (`provectus/.../kafka-ui-api.yaml:2886-2900`) survive only for the deprecated v1 route; v2 uses the single `PollingMode` enum plus explicit `offset`/`timestamp`/`partitions`/`cursor` params (`kafbat/.../kafbat-ui-api.yaml:912-973`).

Message browsing semantics diff: Provectus `getTopicMessages(seekType, seekTo[], limit, q, filterQueryType, seekDirection, keySerde, valueSerde)` (`provectus/.../kafka-ui-api.yaml:653-697`) with `seekTo` encoded as `partition::offset` strings; `MessagesService` switched on `SeekDirection` (`provectus/.../service/MessagesService.java:200-260`). Kafbat: mode-driven, typed params, filter pre-registered by id, **next-page cursors** in the `DONE` event.

### F6. Configuration model deltas (`ClustersProperties` field diff)

Kafbat adds: top-level `adminClient{timeout, describeConsumerGroupsPartitionSize/Concurrency, listConsumerGroupOffsetsPartitionSize/Concurrency, getTopicsConfigPartitionSize, describeTopicsPartitionSize}`, `cache{enabled, connectClusterCacheExpiry}`, `fts{enabled, defaultEnabled, schemas/consumers/connect/acl ngram settings}`, `csv{lineDelimeter, quoteCharacter, quoteStrategy, fieldSeparator}`, `defaultMetricsStorage.prometheus{url, pushGatewayUrl/Username/Password/JobName, remoteWrite}`, `messageRelativeTimestamp`, `polling.responseTimeoutMs`; per cluster: `schemaRegistryTopicSubjectSuffix`, `schemaRegistryAuth.oauth{tokenUrl, clientId, clientSecret, scopes, tokenCacheEnabled, tokenRefreshBuffer, maxRetries}`, `ssl.verify`, `kafkaConnect[].consumerNamePattern`, `metrics.prometheusExpose`, `metrics.store`, `audit.requireAuditTopic`, `audit.level` defaulting to `ALTER_ONLY` (was unset). `MetricsConfigData` was renamed `MetricsConfig`. Everything in Provectus's model still exists in Kafbat, so **Provectus configs load unchanged** into Kafbat (KUI's env-key compatibility goal for `kui-config` therefore only needs to track Kafbat).

### F7. Feature-level summary

| Capability | Provectus | Kafbat | Note |
| --- | --- | --- | --- |
| Smart filters | Groovy script | CEL | security: Groovy = arbitrary JVM code; CEL is sandboxed |
| Message paging | limit only; UI re-seeks | server cursors (`DONE.cursor`) | cursors are in-memory |
| Client quotas | — | list/upsert/delete + RBAC action | `ClientQuotaService` |
| Graphs / Prometheus store | — | PromQL templates proxied to Prometheus; push-gateway sink | `service/graphs`, `service/metrics/sink` |
| `/metrics` exposition | actuator only | per-cluster Prometheus text endpoint | `PrometheusExposeController` |
| Metrics model | fixed "well-known" set | scraped + inferred snapshots (`MetricSnapshot`) | |
| KRaft quorum info | — | `describeMetadataQuorum`, controller type | `StatisticsService.java:78-96` (Kafbat) |
| Full-text search | substring | Lucene n-gram indexes per resource type; feature-flagged | `ClusterFeature.FTS_*` |
| Connector offsets reset | — | endpoint + error | |
| Delete group offsets per topic | — | endpoint | |
| Topic→connectors | — | endpoint (from cached Connect state) | |
| Serdes | 12 | +MessagePack, Struct, MM2 internal topics | |
| Auth | form, OAuth2 (Google/GitHub/Cognito/generic), LDAP, AD via condition | + Azure Entra broker auth, GCP managed Kafka, AD RBAC extractor, `/api/config/authentication` for login page, OAuth for Schema Registry | |
| RBAC | roles | + `defaultRole`, `CONNECTOR` resource with `CONNECT` fallback, `CLIENT_QUOTAS` | |
| Config | wizard + restart | + `config.autoreload` file watcher for RBAC roles | |
| MCP server | — | `/mcp/sse` tools generated from controllers | |
| CSV | — | ACL export/import via fastcsv | |
| Read-only safe endpoints | — | `smartfilters`, `analysis` exempt from read-only | `ReadOnlyModeFilter.java:27-29` (Kafbat) |
| Docs | GitBook external | `documentation/` in repo | |
| E2E | Selenide | Playwright | |

Removed or dropped in Kafbat: Groovy filters, the v1 message-browsing behavior (endpoint kept, returns error), hand-written SR models, `ResultSizeLimiter`, five exception types, `ActiveDirectoryCondition` (superseded by properties-driven LDAP/AD config).

## Decision candidates for KUI

**D1. Parity target = Kafbat's contract, with Provectus features considered a strict subset.**
Decision: KUI's feature matrix is seeded from Kafbat's 70 paths; the only Provectus-specific items (Groovy filters, v1 seek API) are `REJECTED(superseded by CEL / PollingMode)`, a decision that has been recorded.
Evidence: F5, F7.
Tradeoff: none; Kafbat is a superset.
Reversibility: high.

**D2. Never adopt script-engine filters; keep CEL as the only user-programmable predicate.**
Decision: `kui-message-service` embeds the `dev.cel` JVM runtime; no Groovy/JS engines, for security reasons.
Evidence: F3 filters row (`provectus/.../emitter/MessageFilters.java:41-90` vs `kafbat/.../emitter/MessageFilters.java:100-202`).
Tradeoff: CEL has no user-defined functions beyond extensions; acceptable.
Reversibility: high.

**D3. Contract authoring in Tapir replaces TypeSpec, but keep TypeSpec's organisation: one contract file per resource family, and an OpenAPI style check in CI.**
Decision: mirror `contract-typespec/api/{clusters,brokers,topics,messages,consumer-groups,schemas,kafka-connect,ksql,acls,quotas,auth,config,graphs,prometheus}.tsp` as one Tapir contract module per KUI service, and run an OpenAPI linter on the aggregated document at `/api/docs` with the same naming conventions (camelCase properties/params) Kafbat enforces (`kafbat/contract/build.gradle:73-89`).
Evidence: F1.
Tradeoff: none.
Reversibility: high.

**D4. Treat Kafbat's config additions as the KUI typed config baseline; keep Kafbat/Provectus env-key mapping for all of them.**
Decision: `kui-config` maps every key in F6 (including the `adminClient.*` batching knobs, `fts.*`, `cache.*`, `defaultMetricsStorage.*`, SR OAuth) so a Kafbat deployment can be migrated by renaming nothing.
Evidence: F6.
Tradeoff: some knobs are meaningless in the distributed shape (e.g. `adminClient.describeTopicsPartitionSize` applies per service); document them as accepted-and-forwarded.
Reversibility: high.

**D5. Serde API compatibility decision must be explicit.**
Decision: record an ADR choosing between (a) a Java shim implementing `io.kafbat.ui.serde.api.Serde` so Kafbat plugin jars (Glue, Smile) load in `kui-message-service`, or (b) Scala-only plugins. Provectus-era jars are irrelevant (package renamed already).
Evidence: F4.
Tradeoff: (a) costs a Java module and child-first classloading in a Scala service; (b) costs community plugins.
Reversibility: medium.

**D6. Adopt Kafbat's newer capabilities as separate milestones rather than as part of core parity.**
Decision: quotas → `kui-security-service` (M?); graphs/Prometheus store/pushgateway → `kui-metrics-service`; FTS → topic/consumer/schema/connect services behind a feature flag; MCP → gateway (late); connector offsets reset and topic→connectors → `kui-connect-service`; delete-offsets-per-topic → `kui-consumer-service`.
Evidence: F7.
Tradeoff: parity date slips if all are in M1; the project's milestone sequencing handles it.
Reversibility: high.

## Open questions

1. Is Kafbat's kept-but-broken v1 messages endpoint something KUI must expose for old UI clients, or can it be omitted from the contract entirely?
2. Should KUI's audit `level` default follow Kafbat (`ALTER_ONLY`) — Provectus had no default and logged nothing unless configured?
3. The `ActiveDirectoryCondition` removal implies AD is now configured purely through `spring.ldap`/`oauth2.ldap` properties in Kafbat; confirm which AD flow (bind vs Active Directory provider) KUI's identity service must support first.
4. Provectus history (unshallow clone) would show *why* Groovy was replaced by CEL (security advisory) and why `ResultSizeLimiter` was removed; fetch only if an ADR needs the rationale.

## Confidence

**High** for build/module/class/path/schema diffs (mechanical `diff` output). **High** for the Groovy→CEL, cursor, quotas, graphs, MCP and metrics restructuring findings (read in both trees). **Medium** for the claim that Provectus configs load unchanged in Kafbat (based on field-name superset; semantics of `metrics.type` defaults were not tested). **Medium** for AWS Glue being external (based on absence in both trees and README wording; the plugin repository itself was not inspected).
