# KUI context map

Status: accepted 2026-09-03 (G2/G3). Companion to `ARCHITECTURE.md` §2–§3 and ADR-004.
Terms come from `docs/domain/kafka-glossary.md`; one concept has one name inside a context.

## Bounded contexts

| Context | Service | Subdomain | Core language (own terms) | Owns |
| --- | --- | --- | --- | --- |
| Cluster Registry and Topology | `kui-cluster-service` | Core | `Cluster`, `ClusterProfile`, `Broker`, `Controller`, `QuorumInfo`, `LogDir`, `ClusterFeature`, `ConfigEntry` | which clusters exist and how to reach them; topology snapshot; broker configuration; capability probing; cluster configuration store and wizard |
| Topic Management | `kui-topic-service` | Core | `Topic`, `Partition`, `Replica`, `Isr`, `TopicConfig`, `ReassignmentPlan`, `TopicAnalysis`, `ProducerState` | the topic snapshot per cluster; topic lifecycle commands; analysis runs |
| Message Exploration | `kui-message-service` | Core (highest risk) | `BrowseRequest`, `SeekMode`, `PollingMode`, `OffsetRange`, `Cursor`, `Record`, `Serde`, `SmartFilter`, `MaskingPolicy`, `TrackQuery`, `Resend` | reading, filtering, decoding, masking and producing records; event tracking |
| Consumer Group Management | `kui-consumer-service` | Core | `ConsumerGroup`, `Member`, `Assignment`, `CommittedOffset`, `PartitionLag`, `ResetSpec` | the group snapshot; lag; offset reset/delete; group delete |
| Kafka Security Objects | `kui-security-service` | Supporting | `AclBinding`, `AclFilter`, `AclPreset`, `ClientQuotaEntity`, `QuotaAlteration` | ACLs and quotas on the broker |
| Schema Registry Management | `kui-schema-service` | Supporting | `Subject`, `SchemaVersion`, `SchemaId`, `CompatibilityLevel`, `SchemaReference` | registry management (not decoding) |
| Kafka Connect Management | `kui-connect-service` | Supporting | `ConnectCluster`, `Connector`, `Task`, `ConnectorPlugin`, `ConnectorOffsets` | connector lifecycle |
| ksqlDB | `kui-ksql-service` | Supporting | `Statement`, `PushQuery`, `PullQuery`, `KsqlTable`, `KsqlStream` | statement execution and result streaming |
| Kafka Observability | `kui-metrics-service` | Supporting | `MetricSnapshot`, `Scraper`, `InferredMetric`, `GraphDescription`, `PromQuery` | broker metrics collection, inferred metrics, graphs, exposition |
| Application Identity and Access | `kui-identity-service` | Generic (buy-like, but owns policy) | `Principal`, `Session`, `Role`, `Subject`, `Permission`, `Action`, `AuditRecord` | who the user is, which roles they hold, what was done |
| Edge (BFF) | `kui-gateway` | Application layer, not a domain context | `Capability`, `Section`, `Aggregation` | routing, aggregation, capability registry, session cache; no business rules |
| Frontend shell | `@kui/shell` + `@kui/kernel` | Presentation | `Feature`, `FeatureState`, `NavEntry` | rendering, navigation, client state |

Core investment goes to Message Exploration, Topic Management, Consumer Group Management
and Cluster Registry. Supporting contexts are kept close to their upstream REST vocabulary
(Confluent, Connect, ksqlDB) with an anticorruption layer only where the upstream shape is
hostile (ksqlDB `/query` schema strings, Connect 409 semantics).

## Relationships

Notation: `U` upstream, `D` downstream. OHS = Open Host Service, PL = Published Language,
ACL = Anticorruption Layer, CF = Conformist, SK = Shared Kernel, C/S = Customer/Supplier.

| Upstream | Downstream | Pattern | Contract | Translation duty |
| --- | --- | --- | --- | --- |
| Cluster Registry (U) | Topic, Message, Consumer, Security, Schema, Connect, Ksql, Metrics (D) | OHS + PL | `ClusterProfile` DTO in `services/cluster/contract`, versioned, served with ETag and change stream | downstream `application` maps `ClusterProfileDto` to its own `ConnectionProfile` value object (Chimney); keeps last known profile as fallback |
| Topic Management (U) | Kafka Observability (D) | C/S | topic snapshot endpoint (`/internal/v1/clusters/{id}/topics/snapshot`) | metrics maps snapshot rows to `InferredMetric`; tolerates `Stale`/`Unavailable` |
| Consumer Group Management (U) | Kafka Observability (D) | C/S | group snapshot endpoint | same |
| Application Identity (U) | Edge (D) | OHS + PL | `/internal/v1/auth/*`, `Principal`, expanded `Permission` list, role reload stream | gateway caches `sessionId → Principal`; runs `Rbac.decide` from `libs/security-core` |
| Application Identity (U) | every service (D) | PL via `libs/security-core` | `PrincipalClaims` JWS, `RbacPolicy` | services verify and re-decide; identity owns the vocabulary |
| Each service (U) | Edge (D) | CF | the service's `contract` module | none: the gateway conforms to each contract and composes `Section`s |
| Edge (U) | Frontend (D) | CF | `/api/v1` contracts, via the committed `docs/api/openapi.browser.json` the browser's types are generated from (ADR-048) | the generated types are committed, so drift is caught by `./mill __.openApiCheck` and by regenerating, not by construction |
| Schema Registry (external U) | Schema Registry Management (D) | ACL | own sttp client, sealed `UpstreamError` | error code table → `KuiError`; `schemaType` default → `Avro` |
| Schema Registry (external U) | Message Exploration (D) | ACL (separate ways from the schema-service) | `libs/serde-confluent` wire-format serdes with own registry client cache | schema-by-id decoding; does not go through `kui-schema-service` (ADR-014, research/kafbat/architecture.md D7) |
| Kafka Connect (external U) | Connect Management (D) | ACL | sttp client with 409/rebalance retry | Connect error bodies → `KuiError` |
| ksqlDB (external U) | ksqlDB context (D) | ACL | `/query-stream` typed columns, `/query` fallback | schema-string parsing isolated in the adapter |
| Kafka brokers (external U) | Cluster, Topic, Message, Consumer, Security (D) | ACL via `libs/kafka` | `KafkaAdminPort` family, `MessageBrowsePort` | `org.apache.kafka.*` never crosses into a service; `KafkaErrorMapper` total |
| Identity providers (external U) | Application Identity (D) | ACL | `IdentityProviderPort`, `OidcProviderPort` | provider claims → `IdentityAttributes` → roles (login time only) |
| Kafbat serde jars (external U) | Message Exploration (D) | ACL | `libs/serde` Kafbat bridge | Java SPI ↔ `Serde[F]` |

Shared Kernel: `libs/kernel` (`kui-kernel`). Jointly owned by all context architects; changes
require a Chief Architect review and are additive within a milestone.

No context imports another context's `domain` module. Cross-context data crosses only as
contract DTOs and is translated in the consumer's `application` layer.

## Shared-kernel type list (`libs/kernel`)

Implemented (KERN-001) — identifiers, all opaque types with `from` validation, an `unsafe`
escape hatch for already-validated values, a `value` extension and an `Ordering`:
`ClusterId`, `KafkaClusterId`, `TopicName`, `PartitionId`, `Offset`, `BrokerId`, `GroupId`,
`Subject`, `SchemaId`, `ConnectName`, `ConnectorName`, `TaskId`, `CorrelationId`,
`ServiceId`, `UserName`, `RoleName`. The validation rule of each one is stated on the type itself in `libs/kernel`, is covered by that
module's suites, and is binding on every service.

Implemented (KERN-001) — value objects: `TopicPartition`, `TopicPartitionReplica`,
`OffsetRange` (half-open, start never after end), `Host`, `Port`, `PositiveInt`, `ByteSize`.

Pending: `PageSize`, `PageRequest`, `Page[A]`, `PageToken`, `SortOrder`, `Sort[Field]`
(KERN-003, M0); `TimestampType` (M3, with message browsing); `Rack` (M1, with the broker
topology snapshot); `NameIndex` (prefix/substring/trigram search — M2, ADR-038).

Errors: `KuiError`, `DomainError`, `ApplicationError` (`NotFound`, `Conflict`, `Forbidden`,
`Unsupported`, `InvalidState`, `Invalid`), `InfrastructureError` (`Unreachable`, `Timeout`,
`AuthFailed`, `Upstream`, `CircuitOpen`), `ErrorCode`, `ValidationError`, `FieldError`.

Enumerations shared by more than one context: `ClusterFeature`, `GroupState`, `IsolationLevel`,
`ConfigSource`.

Helpers: `Validated` accumulation syntax, `Secret[A]` (redacting wrapper; defined in kernel so
domain modules can hold it), `Clock`-free `Instant` helpers.

Not in the shared kernel: anything with a wire codec (`libs/contracts-core`), anything
security-specific (`libs/security-core`), anything Kafka-client-specific (`libs/kafka`).
