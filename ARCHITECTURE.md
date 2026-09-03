# KUI architecture

Status: authoritative as of 2026-09-03 (grooming phase G3). Every decision referenced as
`ADR-NNN` lives in `docs/adr/` and is indexed in `DECISIONS.md`. Research findings are cited
by report file and section rather than restated; read the report when you need the evidence.

This document answers one question: *how are the pieces of KUI shaped, and how do they fit
together so that the product properties in PLAN §2 hold?* Anything about "why this library"
is in an ADR. Anything about "what does this screen do" is in the feature matrix.

Sections:

1. Topology and deployment shapes
2. Service catalog and tiers
3. Bounded contexts and module layout
4. Shared libraries and their public APIs
5. Inter-service contracts, headers and principal propagation
6. Capability registry and the degraded-response envelope
7. Streaming (SSE) envelope
8. Paging: offset pages and signed cursors
9. State, caching and refresh schedulers per service
10. Configuration ownership and distribution without restart
11. All-in-one composition
12. Frontend shell and microfrontends
13. Observability standard
14. Security boundaries
15. Errors
16. Repository layout

---

## 1. Topology and deployment shapes

```
 Browser: Scala.js shell + lazily loaded feature modules (ADR-011, ADR-012)
   │  HTTPS, session cookie or bearer token, SSE for streams
   ▼
 kui-gateway  (BFF: edge auth, RBAC pre-check, routing from contracts, screen aggregations,
               SSE fan-in, capability registry, OpenAPI merge, static assets)
   │  HTTP over Tapir contracts, signed principal header, W3C trace context
   ├── kui-cluster-service     Core        cluster registry, topology, broker config, log dirs,
   │                                       cluster capability probing, cluster config wizard
   ├── kui-topic-service       Degradable  topics, configs, partitions, RF, analysis, producers
   ├── kui-message-service     Degradable  browse/stream/page, produce, resend, purge, filters,
   │                                       serdes, masking, event tracking
   ├── kui-consumer-service    Degradable  groups, lag, offset reset/delete, group delete
   ├── kui-security-service    Degradable  ACLs, quotas, CSV import/export, ACL presets
   ├── kui-schema-service      Optional    subjects, versions, compatibility (Confluent-compatible)
   ├── kui-connect-service     Optional    Connect clusters, connectors, tasks, plugins, offsets
   ├── kui-ksql-service        Optional    statements, push/pull queries, tables, streams
   ├── kui-metrics-service     Degradable  JMX/Prometheus scrape, inferred metrics, graphs,
   │                                       /metrics exposition
   └── kui-identity-service    Core*       login (form/OIDC/LDAP), sessions, roles, audit sink
                                           (* only when auth is enabled)
   ▼
 Kafka clusters · Schema Registries · Connect · ksqlDB · Prometheus / JMX
```

Two shapes are built from the same Mill modules (ADR-005):

| Shape | What runs | Used for |
| --- | --- | --- |
| Distributed | one container per service + the gateway; discovery by static `kui.gateway.services.<name>.url` or Kubernetes DNS (Helm values) | production |
| All-in-one | `apps/allinone`: one JVM, one listening port, every service's application layer wired into the gateway's composition root; the gateway's client for each service is an in-process Tapir interpreter | local development, small installs, E2E tests |

No service mesh, no registry service, no shared database (PLAN §3). Services own their
state; most state is a rebuildable snapshot of the target cluster (§9).

## 2. Service catalog and tiers

Validated against PLAN §15 with two amendments, both recorded in ADR-004:

1. **`kui-security-service` stays a separate service.** It has its own capability gate
   (authorizer present, `ALTER` on the cluster), its own failure signature
   (`SecurityDisabledException`, slow `describeAcls` on clusters with thousands of bindings)
   and functionality (CSV sync, ACL presets) that is not cluster topology. Merging it into
   the Core `kui-cluster-service` would put a slow, optional feature inside the one service
   the UI cannot live without. Evidence: `research/kafka/admin-capabilities.md` §5 and DC-D11.
2. **`kui-config-service` is dissolved.** "Configuration management" is not a bounded context;
   it is three ownerships. Cluster configuration (registry, wizard validate/apply,
   test-connection, cluster CRUD, related file uploads) belongs to the Cluster Registry
   context, which already owns the runtime cluster registry (`research/kafbat/architecture.md`
   D2, D8). Auth and RBAC configuration belongs to Application Identity. Gateway configuration
   belongs to the gateway. The `/api/v1/config` facade is a gateway aggregation over the two
   owners. Details in §10 and ADR-036.

Tier = what the UI does when the service is down (PLAN §16).

| Service | Bounded context (`docs/domain/context-map.md`) | Owns | Tier |
| --- | --- | --- | --- |
| `kui-gateway` | Edge (application, not a domain context) | sessions at the edge, RBAC pre-check, routing, aggregations, capability registry, SSE fan-in, static assets, OpenAPI merge, audit forwarding | **Core** |
| `kui-cluster-service` | Cluster Registry and Topology | cluster registry + resolved connection profiles, `describeCluster`, KRaft quorum, brokers, broker configs, log dirs, cluster stats, cluster capability probing, cluster config wizard and store | **Core** per cluster: shell and other clusters keep working |
| `kui-topic-service` | Topic Management | topic snapshot (descriptions, configs, offsets), list/search/sort/page, CRUD, clone, recreate, partitions, replication factor, topic analysis, active producers | Degradable |
| `kui-message-service` | Message Exploration | browse stream (seek/polling modes, cursors), per-partition page mode, tailing, event tracking, produce, resend, purge, smart filters, serde registry and resolution, masking | Degradable |
| `kui-consumer-service` | Consumer Group Management | group snapshot, list/page, details, lag, offset reset/delete, group delete | Degradable |
| `kui-security-service` | Kafka Security Objects | ACL list/create/delete/presets/CSV, client quotas | Degradable |
| `kui-schema-service` | Schema Registry Management | subjects, versions, compatibility, checks, registry auth | Optional plugin (NotConfigured when a cluster has no registry) |
| `kui-connect-service` | Kafka Connect Management | connect clusters, connectors, tasks, plugins, validation, actions, offsets | Optional plugin |
| `kui-ksql-service` | ksqlDB | statement execution, query streaming, tables/streams | Optional plugin |
| `kui-metrics-service` | Kafka Observability | JMX/Prometheus scrapers, inferred metrics, graph descriptions, PromQL templates, `/metrics` exposition | Degradable |
| `kui-identity-service` | Application Identity and Access | authentication adapters, session store, role model and hot reload, permission query, audit sink | **Core when `kui.auth.type != disabled`**; the gateway runs anonymous when auth is disabled |

Two Degradable/Optional services with different failure domains are never merged (PLAN §15).

## 3. Bounded contexts and module layout

Each service is one Mill module tree (PLAN §18), hexagonal, with the dependency rule
enforced by `moduleDeps` (a module cannot import what it does not depend on):

```
services/<name>/
  domain/          entities, value objects, domain errors, ports (traits). Depends on kui-kernel
                   and cats-core only. No Tapir, Circe, fs2-kafka, kafka-clients, otel4s, MacWire.
  application/     use cases, orchestration, DTO<->domain mapping (Chimney, ADR-033),
                   snapshot/refresh logic. Depends on domain, cats-effect, fs2, log4cats, otel4s-core.
  infrastructure/  adapters implementing the domain ports: kui-kafka, sttp clients, caches,
                   file stores. Java SDKs live only here. Exceptions become KuiError here.
  contract/        Tapir endpoints + DTOs + Circe codecs, cross-compiled JVM/JS.
                   Depends on tapir-core, circe, kui-contracts-core. Never on domain.
  api/             server logic: contract -> application, KuiError -> HTTP envelope,
                   principal verification, OpenAPI document.
  app/             MacWire composition root, Ciris config slice, Netty server, telemetry bootstrap.
```

```
app -> api -> application -> domain <- infrastructure
        \-> contract                      \-> kui-kafka / kui-serde / external SDKs
```

Per-service specifics (what each `domain` module models; details in `docs/domain/<context>.md`):

| Service | Key aggregates / value objects | Ports in `domain` | Adapters in `infrastructure` |
| --- | --- | --- | --- |
| cluster | `ClusterProfile` (config + resolved endpoints + security), `ClusterDescription`, `Broker`, `LogDir`, `ClusterFeature` set | `ClusterAdmin[F]`, `ClusterConfigStore[F]`, `ConnectivityProbe[F]` | kui-kafka admin adapter, YAML/K8s config store, probe clients |
| topic | `Topic` (NonEmptyList[Partition], ISR ⊆ replicas), `TopicConfig`, `TopicAnalysis` | `TopicAdmin[F]`, `ClusterProfileSource[F]`, `TopicAnalysisPort[F]` | kui-kafka, cluster-service contract client, datasketches |
| message | `BrowseRequest`, `SeekMode`, `PollingMode`, `OffsetRange`, `MaskingPolicy`, `TrackQuery` | `MessageBrowsePort[F]`, `SerdeRegistry[F]`, `MessageFilterPort[F]`, `ClusterProfileSource[F]` | fs2-kafka consumer/producer, kui-serde, kui-filter (CEL) |
| consumer | `ConsumerGroup`, `Member`, `PartitionLag` (`Option[Lag]` + anomaly flags), `ResetSpec` | `GroupAdmin[F]`, `ClusterProfileSource[F]` | kui-kafka |
| security | `AclBinding`, `AclFilter`, `ClientQuotaEntity`, `AclPreset` | `SecurityAdmin[F]`, `ClusterProfileSource[F]` | kui-kafka, fs2-data-csv |
| schema | `Subject`, `SchemaVersion`, `CompatibilityLevel` | `SchemaRegistryPort[F]` | own sttp client (ADR-014) |
| connect | `ConnectCluster`, `Connector`, `Task`, `Plugin` | `ConnectPort[F]` | sttp client with 409 retry (ADR-037) |
| ksql | `Statement`, `QueryResult` stream | `KsqlPort[F]` | sttp HTTP/2 `/query-stream` with `/query` fallback |
| metrics | `MetricSnapshot`, `GraphDescription`, `PromQuery` | `BrokerMetricsScraper[F]`, `MetricsStore[F]`, `TopicSnapshotSource[F]`, `GroupSnapshotSource[F]` | JMX, Prometheus HTTP, contract clients |
| identity | `Principal`, `Session`, `Role`, `Subject`, `Permission` (from kui-security-core), `AuditRecord` | `IdentityProviderPort[F]`, `OidcProviderPort[F]`, `SessionStore[F]`, `RolePolicySource[F]`, `AuditSink[F]` | UnboundID LDAP, nimbus OIDC, bcrypt users, in-memory/Kafka session and audit sinks |

The gateway has `contract` (its own `/api/v1` endpoint definitions, cross-compiled JVM/JS so
the frontend derives typed clients from them), `application` (aggregations, capability
registry, session cache), `api` and `app`. It depends on every service's `contract` module and
on nothing else from a service. It has no `domain` and no `infrastructure`: it holds no
business rules (ADR-004) and its only outbound adapters are contract clients.

The layering rules above are checked by `./mill checkArchitecture` on every build, not by
review (ADR-041). In particular no `application` module — the gateway's included — may depend
on `libs/contracts-core`, `libs/http`, Tapir or Circe; the `api` layer maps between
application-owned types and wire DTOs.

## 4. Shared libraries and their public APIs

Shared libraries hold no business rule of a single context (PLAN §19). Sketches below are
the *shape*; exact signatures are finalized in the M0 tasks. Scala 3, opaque types for ids,
`F[_]` at port boundaries, `IO` only in `app`.

| Module | Content | ADR |
| --- | --- | --- |
| `libs/kernel` (`kui-kernel`) | shared-kernel types below, `KuiError` hierarchy, paging/sorting primitives, `Validated` helpers. Pure, cross-compiled JVM/JS. | ADR-004, ADR-034 |
| `libs/contracts-core` | error envelope DTO, `Page` DTOs, `Section[A]` envelope, SSE event DTOs, capability DTOs, Tapir codecs for kernel types. JVM/JS. | ADR-003, ADR-007, ADR-034, ADR-035 |
| `libs/kafka` (+ `libs/kafka-auth`) | `KafkaAdminPort` family over fs2-kafka `KafkaAdminClient`, consumer/producer factories, `KafkaErrorMapper`, batching, client property assembly from `ClusterProfile`; cloud SASL handlers as optional runtime modules | ADR-006, ADR-022, ADR-030 |
| `libs/serde` (+ `libs/serde-confluent`) | `Serde[F]` SPI, built-ins, registry/resolution, Kafbat bridge; Confluent wire-format serializers isolated | ADR-028, ADR-014 |
| `libs/filter` | `MessageFilterPort[F]` over cel-java | ADR-017 |
| `libs/cache` | `Ref`+TTL `SnapshotCell`, Caffeine wrapper, metrics hooks | ADR-016 |
| `libs/observability` | otel4s bootstrap, log4cats structured logger with MDC bridge, Tapir interceptors, metric names | ADR-008, ADR-009 |
| `libs/security-core` | `Principal`, `Rbac.decide`, `PrincipalCodec`, masking rule model. Pure; JVM/JS. | ADR-020, ADR-021, ADR-023 |
| `libs/http` | Netty server setup, error interceptor, health/ready/capabilities endpoints, sttp client factory with failover/retry/circuit breaker/bulkhead, SSE helpers | ADR-003, ADR-037 |
| `libs/config` | Ciris loaders, YAML + env mapping, `Secret[A]`, `ConfigStore[F]` port and file/K8s adapters | ADR-013, ADR-036 |
| `libs/testkit` | Testcontainers topology, fake ports, ScalaCheck generators, Tapir stubs, golden files | ADR-018 |

### 4.1 `kui-kernel` shared-kernel types

```scala
package kui.kernel

opaque type ClusterId = String          // slug of the configured name (ADR-031)
opaque type KafkaClusterId = String     // reported by describeCluster
opaque type TopicName = String
opaque type PartitionId = Int
opaque type Offset = Long
opaque type BrokerId = Int
opaque type GroupId = String
opaque type Subject = String
opaque type SchemaId = Int
opaque type ConnectName = String
opaque type ConnectorName = String
opaque type CorrelationId = String
opaque type ServiceId = String          // "cluster", "topic", ... used by the capability registry

object ClusterId:
  def from(raw: String): Either[ValidationError, ClusterId]
  extension (id: ClusterId) def value: String

final case class TopicPartition(topic: TopicName, partition: PartitionId)
final case class OffsetRange(from: Offset, until: Offset)   // half-open, from <= until

enum SortOrder { case Asc, Desc }
final case class PageRequest(page: PositiveInt, pageSize: PageSize)
final case class Page[A](items: List[A], page: Int, pageSize: Int, totalItems: Option[Long], nextPageToken: Option[PageToken])

sealed trait KuiError { def code: ErrorCode; def message: String }
sealed trait DomainError         extends KuiError
sealed trait ApplicationError    extends KuiError   // NotFound, Conflict, Forbidden, Unsupported(feature), InvalidState, Invalid(fields)
sealed trait InfrastructureError extends KuiError   // Unreachable, Timeout, AuthFailed, Upstream(status, body), CircuitOpen
```

### 4.2 `KafkaAdminPort` family (`libs/kafka`)

One narrow port per context (`research/kafka/admin-capabilities.md` DC-D1), all implemented by
one adapter over fs2-kafka 4 with the raw `Admin` escape hatch where needed (ADR-006). Services
never import `org.apache.kafka.*`.

```scala
package kui.kafka.admin

final case class BatchResult[K, A](values: Map[K, A], skipped: Map[K, SkipReason])   // never silent drops

trait ClusterAdmin[F[_]]:
  def describeCluster(profile: ClusterProfile): F[Either[KuiError, ClusterDescription]]
  def describeQuorum(profile: ClusterProfile): F[Either[KuiError, Option[QuorumInfo]]]
  def capabilities(profile: ClusterProfile): F[Set[ClusterFeature]]
  def brokerConfigs(profile: ClusterProfile, broker: BrokerId, docs: Boolean): F[Either[KuiError, List[ConfigEntry]]]
  def alterBrokerConfig(profile: ClusterProfile, broker: BrokerId, ops: NonEmptyList[ConfigOp]): F[Either[KuiError, Unit]]
  def describeLogDirs(profile: ClusterProfile, brokers: Set[BrokerId]): F[Either[KuiError, BatchResult[BrokerId, List[LogDir]]]]
  def alterReplicaLogDir(profile: ClusterProfile, replica: TopicPartitionReplica, path: LogDirPath): F[Either[KuiError, Unit]]

trait TopicAdmin[F[_]]:
  def listTopics(profile: ClusterProfile, includeInternal: Boolean): F[Either[KuiError, List[TopicListing]]]
  def describeTopics(profile: ClusterProfile, names: List[TopicName]): F[Either[KuiError, BatchResult[TopicName, TopicDescription]]]
  def describeConfigs(profile: ClusterProfile, names: List[TopicName], docs: Boolean): F[Either[KuiError, BatchResult[TopicName, List[ConfigEntry]]]]
  def listOffsets(profile: ClusterProfile, spec: OffsetSpec, partitions: Set[TopicPartition], onNoLeader: NoLeaderPolicy): F[Either[KuiError, BatchResult[TopicPartition, Offset]]]
  def createTopic(profile: ClusterProfile, spec: NewTopicSpec, validateOnly: Boolean): F[Either[KuiError, Unit]]
  def deleteTopic(profile: ClusterProfile, name: TopicName): F[Either[KuiError, Unit]]
  def alterConfig(profile: ClusterProfile, name: TopicName, ops: NonEmptyList[ConfigOp], validateOnly: Boolean): F[Either[KuiError, Unit]]
  def createPartitions(profile: ClusterProfile, name: TopicName, total: PositiveInt): F[Either[KuiError, Unit]]
  def reassign(profile: ClusterProfile, plan: ReassignmentPlan): F[Either[KuiError, Unit]]
  def deleteRecords(profile: ClusterProfile, before: Map[TopicPartition, Offset]): F[Either[KuiError, Map[TopicPartition, Offset]]]
  def describeProducers(profile: ClusterProfile, partitions: Set[TopicPartition]): F[Either[KuiError, BatchResult[TopicPartition, List[ProducerState]]]]

trait GroupAdmin[F[_]]:
  def listGroups(profile: ClusterProfile, states: Set[GroupState]): F[Either[KuiError, List[GroupListing]]]
  def describeGroups(profile: ClusterProfile, ids: List[GroupId]): F[Either[KuiError, BatchResult[GroupId, GroupDescription]]]
  def committedOffsets(profile: ClusterProfile, ids: List[GroupId]): F[Either[KuiError, BatchResult[GroupId, Map[TopicPartition, Option[Offset]]]]]
  def alterOffsets(profile: ClusterProfile, id: GroupId, offsets: Map[TopicPartition, Offset]): F[Either[KuiError, Unit]]
  def deleteOffsets(profile: ClusterProfile, id: GroupId, partitions: Set[TopicPartition]): F[Either[KuiError, Unit]]
  def deleteGroup(profile: ClusterProfile, id: GroupId): F[Either[KuiError, Unit]]

trait SecurityAdmin[F[_]]:
  def describeAcls(profile: ClusterProfile, filter: AclFilter): F[Either[KuiError, List[AclBinding]]]
  def createAcls(profile: ClusterProfile, bindings: NonEmptyList[AclBinding]): F[Either[KuiError, Unit]]
  def deleteAcls(profile: ClusterProfile, filters: NonEmptyList[AclFilter]): F[Either[KuiError, List[AclBinding]]]
  def describeQuotas(profile: ClusterProfile, filter: QuotaFilter): F[Either[KuiError, List[QuotaEntry]]]
  def alterQuotas(profile: ClusterProfile, alterations: NonEmptyList[QuotaAlteration]): F[Either[KuiError, Unit]]
```

Batching (200 topics / 50 groups / 200 partitions, parallelism 4), no-leader filtering before
`listOffsets`, client invalidation on reconnect-class errors and the exhaustive
`KafkaErrorMapper` are adapter concerns (DC-D3..D5); they are configured per cluster through
`ClusterProfile.admin`.

### 4.3 `MessageBrowsePort` (`libs/kafka`, consumed by `kui-message-service`)

```scala
package kui.kafka.consume

enum SeekMode:
  case Beginning, Latest
  case Offset(perPartition: Map[PartitionId, Offset] | Offset)
  case Timestamp(at: Instant)
enum Direction { case Forward, Backward }
enum IsolationLevel { case ReadUncommitted, ReadCommitted }
final case class PollBudget(maxRecords: Int, maxBytes: Long, deadline: FiniteDuration, throttle: Option[BytesPerSecond])

final case class RawRecord(tp: TopicPartition, offset: Offset, timestamp: Instant, timestampType: TimestampType,
                           key: Option[Array[Byte]], value: Option[Array[Byte]], headers: List[RawHeader],
                           keySize: Int, valueSize: Int)

trait MessageBrowsePort[F[_]]:
  /** Describes the topic, drops empty and leaderless partitions, resolves seek positions,
    * clamps into [begin, end], applies the compacted-topic beginning fallback. */
  def resolveRanges(profile: ClusterProfile, topic: TopicName, seek: SeekMode, direction: Direction,
                    partitions: Option[NonEmptySet[PartitionId]], isolation: IsolationLevel): F[Either[KuiError, Map[TopicPartition, OffsetRange]]]
  /** One consumer per stream; assign/seek/pause; terminates when position >= until for all. */
  def pollRanges(profile: ClusterProfile, ranges: Map[TopicPartition, OffsetRange], budget: PollBudget, isolation: IsolationLevel): Stream[F, PollEvent]
  def tail(profile: ClusterProfile, partitions: NonEmptySet[TopicPartition], budget: PollBudget): Stream[F, PollEvent]
  def produce(profile: ClusterProfile, record: RawProducerRecord): F[Either[KuiError, RecordMetadata]]

enum PollEvent:
  case Polled(records: Chunk[RawRecord], stats: PollStats)   // stats: bytes, records, elapsed
  case Exhausted(tp: TopicPartition)
```

`kui-message-service` composes this port with `SerdeRegistry`, `MessageFilterPort` and the
masking rules into the browse use case; the merge-sort by timestamp with per-partition offset
order, page limits, cursor creation and the SSE envelope are application logic there.

### 4.4 `SerdeRegistry` (`libs/serde`)

```scala
package kui.serde

enum Target { case Key, Value }
enum PayloadKind { case Text, Json }
final case class DeserializeResult(text: String, kind: PayloadKind, properties: Map[String, Json])
final case class DeserializeFailure(serde: SerdeName, cause: String)

trait Serde[F[_]]:
  def name: SerdeName
  def describe: SerdeDescription
  def canDeserialize(topic: TopicName, target: Target): F[Boolean]
  def canSerialize(topic: TopicName, target: Target): F[Boolean]
  def preferable(topic: TopicName, target: Target): F[Boolean]
  def schema(topic: TopicName, target: Target): F[Option[SchemaDescription]]
  def parameters(topic: TopicName, target: Target): F[List[SerdeParameter]]
  def deserializer(topic: TopicName, target: Target): F[Deserializer[F]]
  def serializer(topic: TopicName, target: Target, params: Map[String, String]): F[Serializer[F]]

trait Deserializer[F[_]]:
  def deserialize(headers: List[RawHeader], bytes: Array[Byte]): F[Either[DeserializeFailure, DeserializeResult]]
trait Serializer[F[_]]:
  def serialize(input: String, headers: List[RawHeader]): F[Either[SerializeFailure, Array[Byte]]]

trait SerdeRegistry[F[_]]:
  def forCluster(profile: ClusterProfile): Resource[F, ClusterSerdes[F]]   // built in config order, closed on profile change

trait ClusterSerdes[F[_]]:
  def all: List[Serde[F]]
  /** pattern rules -> explicit config -> default serde -> String; Fallback String serde always exists. */
  def resolve(topic: TopicName, target: Target, explicit: Option[SerdeName]): F[Either[KuiError, Serde[F]]]
  def suggest(topic: TopicName, target: Target, use: SerdeUse): F[List[SerdeSuggestion]]
```

Deserialization failures never abort a stream: the record is emitted with the fallback
`String` result and a `deserializeError` (ADR-035).

### 4.5 `CapabilityRegistry` (gateway `application`)

The shapes below live in two places on purpose (ADR-041): the registry's own types belong to
the gateway's `application` layer, and the wire DTOs of the same name belong to
`libs/contracts-core`, where they carry Tapir schemas and Circe codecs. `services/gateway/api`
maps between them. The two differ where the wire needs it: the DTO spells durations as
milliseconds, because JSON has no duration type.

```scala
package kui.gateway.application.capability

final case class CapabilityKey(service: ServiceId, cluster: Option[ClusterId])   // None = cluster-independent

enum CapabilityState:
  case Available
  case Degraded(reason: DegradedReason)                       // structured (ADR-032)
  case Unavailable(reason: ReasonCode, message: String, since: Instant)
  case NotConfigured                                          // e.g. no schema registry on this cluster

final case class DegradedReason(code: ReasonCode, message: String, suggestedPollInterval: Option[FiniteDuration], p95: Option[FiniteDuration])

trait CapabilityRegistry[F[_]]:
  def snapshot: F[Map[CapabilityKey, CapabilityState]]
  def state(key: CapabilityKey): F[CapabilityState]
  def changes: Stream[F, CapabilityChange]                    // fed to /api/v1/capabilities/stream
  def report(key: CapabilityKey, state: CapabilityState): F[Unit]   // from readiness pollers and circuit breakers
  def probeNow(service: ServiceId): F[Unit]                   // POST /api/v1/capabilities/{service}/probe
```

The wire counterparts in `libs/contracts-core` (`package kui.contracts.capability`, task
KERN-005) mirror these case for case, with `ServiceId`/`ClusterId` carried through the Tapir
codecs from KERN-004 rather than degraded to bare `String`, and with
`suggestedPollIntervalMs: Option[Long]` / `p95Ms: Option[Long]` in place of the two
`FiniteDuration`s.

Inputs: each service's `GET /capabilities` (which lists, per cluster, the features it
currently supports and whether the cluster has the upstream configured), readiness polling,
the gateway's circuit breaker state per upstream (§6), and observed p95 latency. How the four
combine — the precedence order, the sticky `since`, the asymmetric debounce, and the rule that
a business error never dims a capability — is ADR-039.

### 4.6 `Rbac.decide` and the signed principal (`libs/security-core`)

```scala
package kui.security

enum Resource { case ApplicationConfig, ClusterConfig, Topic, ConsumerGroup, Schema, Connect, Connector, Ksql, Acl, Audit, ClientQuotas }
sealed trait Action { def resource: Resource; def implies: Set[Action]; def isAlter: Boolean }
final case class Permission(resource: Resource, pattern: Option[CompiledPattern], actions: Set[Action])   // actions expanded
final case class Role(name: RoleName, clusters: Set[ClusterId], subjects: List[Subject], permissions: List[Permission])
final case class RbacPolicy(roles: List[Role], defaultRole: Option[List[Permission]]) { def enabled: Boolean }

enum PrincipalKind { case Anonymous, Session, Bearer, System }
final case class Principal(name: UserName, roles: Set[RoleName], kind: PrincipalKind)

final case class ResourceAccess(resource: Resource, name: Option[String], actions: Set[Action], fallback: Option[ResourceAccess])
final case class AccessRequest(cluster: Option[ClusterId], resources: List[ResourceAccess], operation: OperationName, flags: ClusterFlags)
enum Decision { case Allowed; case Denied(reason: DenyReason) }

object Rbac:
  def resolveRoles(attributes: IdentityAttributes, roles: List[Role]): Set[RoleName]              // login time
  def effectivePermissions(policy: RbacPolicy, p: Principal, cluster: Option[ClusterId]): List[Permission]
  def decide(policy: RbacPolicy, p: Principal, req: AccessRequest): Decision                    // pure, shared with Scala.js
  def visible[A](policy: RbacPolicy, p: Principal, cluster: ClusterId, resource: Resource)(name: A => String): List[A] => List[A]

final case class PrincipalClaims(subject: UserName, roles: Set[RoleName], kind: PrincipalKind, sessionRef: Option[SessionRef],
                                 issuedAt: Instant, expiresAt: Instant, audience: ServiceId, requestDigest: RequestDigest)
final case class RequestDigest(method: String, path: String, bodySha256: Sha256)

trait PrincipalCodec[F[_]]:
  def sign(claims: PrincipalClaims): F[SignedPrincipal]                                          // compact JWS, HS256, kid
  def verify(token: SignedPrincipal, expected: ServiceId, request: RequestDigest, now: Instant): F[Either[PrincipalError, Principal]]
```

Read-only clusters are enforced inside `decide` through `ClusterFlags.readOnly` and
`Action.isAlter` (ADR-021), so no URL-pattern filter exists anywhere.

## 5. Inter-service contracts, headers and principal propagation

- Every service publishes exactly one `contract` module; the gateway derives routes from
  endpoint definitions (`research/kafbat/architecture.md` D1). Hand-written path lists are
  forbidden.
- Contract version `/api/v1` on the gateway; service-internal base path `/internal/v1`. Only
  additive changes inside a major; consumers ignore unknown fields.
- Headers on every gateway → service call:

| Header | Content | Required |
| --- | --- | --- |
| `X-Kui-Principal` | compact JWS from `PrincipalCodec.sign` (ADR-020); omitted in all-in-one, where the principal is passed in-process | yes (distributed) |
| `X-Kui-Correlation-Id` | gateway request id, always generated by the gateway and never taken from an inbound request (ADR-040); echoed in every log, span and error envelope | yes |
| `traceparent`, `tracestate` | W3C trace context from otel4s | yes |
| `X-Kui-Cluster-Id` | the `ClusterId` from the path, for adapters that log/metric outside the route layer | when cluster-scoped |
| `Accept: text/event-stream` | for streaming endpoints; the gateway re-streams | streams |

Services strip any inbound `X-Kui-*` header that did not pass `PrincipalCodec.verify`; the
gateway strips every inbound header whose name begins `X-Kui-` at the edge, by prefix rather
than by an enumerated list, before routing, authentication or logging (ADR-040). `traceparent`
and `tracestate` are outside that family and are handled by otel4s.

- Synchronous calls carry per-service timeout, bounded concurrency (bulkhead), retry only
  for idempotent reads, and a circuit breaker with half-open probing (`libs/http`, ADR-037).
- Streaming: FS2 over HTTP chunked `text/event-stream` service → gateway, re-streamed to the
  browser; cancellation propagates browser → gateway → service → consumer close (fiber
  cancellation, `KafkaConsumer.resource`).
- Services never call each other in request paths except through the gateway-visible
  contracts and with a cached fallback (PLAN §16.6). The only such dependencies are:
  every Kafka-facing service → cluster-service (`ClusterProfile`, §10), and
  metrics-service → topic/consumer snapshot endpoints (30 s cadence, tolerant).
- Asynchronous internal events (`kui.internal.events` topic) stay `RESEARCH` for M6+; nothing
  in M0–M5 depends on them.

## 6. Capability registry and the degraded-response envelope

Each service exposes `GET /health/live`, `GET /health/ready` and `GET /capabilities`:

```
GET /capabilities ->
{ "service": "schema",
  "clusters": { "prod-eu": { "configured": true,  "features": ["SCHEMA_REGISTRY"], "status": "available" },
                "staging": { "configured": false, "features": [], "status": "not_configured" } } }
```

The gateway folds readiness polling (every 10 s, configurable), the per-upstream circuit
breaker and these reports into `CapabilityState` per `(service, cluster)`; publishes them at
`GET /api/v1/capabilities` and `GET /api/v1/capabilities/stream` (SSE, full snapshot on
connect, deltas afterwards); accepts `POST /api/v1/capabilities/{service}/probe`.

Aggregated responses are partial by design. The envelope in `contracts-core`:

```scala
enum Section[+A]:
  case Ok(data: A, fetchedAt: Instant)
  case Stale(data: A, fetchedAt: Instant, reason: ReasonCode)   // last known snapshot served while upstream is down
  case Unavailable(reason: ReasonCode, message: String, since: Option[Instant])
  case Forbidden
  case NotConfigured
```

Aggregations that must return partial results (`research/kafbat/api-analysis.md` "Proposed
KUI /api/v1 mapping"): `GET /clusters`, `GET /clusters/{id}/dashboard`,
`GET /clusters/{id}/brokers/{brokerId}` (metrics section), `GET /topics/{topic}/overview`,
`GET /consumer-groups/{groupId}` page aggregation, `GET /connects?withStats`,
`GET /capabilities`. A section failure never fails the response.

## 7. Streaming (SSE) envelope

Applies to message browsing, event tracking, KSQL responses, capability changes, live
metrics and topic-analysis progress (ADR-035). Named events, JSON `data`, cursor in `id`:

```
event: phase      data: {"name": "Polling partitions [0,1]"}
event: message    data: {"partition":0,"offset":42,"timestamp":"...","timestampType":"CREATE_TIME",
                          "key":{"text":"...","kind":"JSON","serde":"SchemaRegistry","properties":{...}},
                          "value":{...}, "headers":{"k":"v"}, "keySize":12,"valueSize":340,"headersSize":8,
                          "deserializeErrors":[{"target":"value","serde":"Avro","cause":"..."}]}
event: consumed   data: {"bytes":1048576,"records":250,"elapsedMs":812,"filterErrors":0,"budget":{"bytesLeft":...,"msLeft":...}}
event: done       data: {"reason":"limit"|"exhausted"|"budget"|"cancelled","cursor":"<signed cursor or null>"}
id: <signed cursor>
event: error      data: {"code":"KUI-UPSTREAM-UNAVAILABLE","message":"...","retryable":true,"correlationId":"..."}
event: heartbeat  data: {}      (every 15 s while idle; keeps proxies and EventSource alive)
```

Rules: exactly one terminal event (`done` or `error`) unless the client cancels; validation
and permission failures before the stream starts are ordinary HTTP error envelopes (§15);
`Last-Event-ID` on reconnect is accepted as a cursor; tailing emits no `done`; UI-facing
tailing throughput is rate-limited (default 20 events/s) in the service.

## 8. Paging: offset pages and signed cursors

- Lists with in-memory sort (topics, consumer groups, schemas, connectors): offset paging
  `page`/`pageSize`, `sort=<field>:<asc|desc>`, `q`, `mode=plain|fts`, response
  `Page{items, page, pageSize, totalItems}`. The reference `pageCount` bug (computed before
  the internal-topic filter) is not reproduced.
- Naturally ordered data (message browsing, event tracking, audit): opaque signed cursors
  (ADR-026), never a process-local cache:

```
cursor = base64url(payload) "." base64url(HMAC-SHA256(payload, cursorKey))
payload = { v:1, c:<clusterId>, t:<topic>, d:"fwd"|"bwd", p:{"0":43,"1":17}, f:<filterId?>,
            ks:<keySerde?>, vs:<valueSerde?>, l:<limit>, i:"ru"|"rc", exp:<epoch s> }
```

Any replica of `kui-message-service` can continue a page. Smart filter sources are not
embedded; the browse request accepts `filterId` **and** `filterSource`, and the client sends
both whenever it has a smart filter, so a replica that has not compiled the filter compiles
it on demand (ADR-017). Cursors expire (default 1 h) and are bound to the cluster and topic.
- Kouncil-style table paging is a separate non-streaming endpoint
  (`GET /topics/{topic}/messages/page`) with per-partition newest-first pages; it needs no
  server state at all (ADR-026, `research/kouncil/architecture.md` D2).

## 9. State, caching and refresh schedulers per service

Kafbat's single `Statistics` snapshot is split by context (ADR-027); every snapshot carries
`status: Initializing | Online | Offline(lastError)`, `scrapedAt`, is replaced atomically, and
is served for reads with `Section.Stale` when the upstream is currently failing. All refresh
loops run under a `Supervisor`, are cancellable and emit `kui.cache.*` and
`kui.kafka.admin.duration` metrics (ADR-016).

| Service | Snapshot / cache | Refresh | Invalidation | Staleness contract |
| --- | --- | --- | --- | --- |
| cluster | `ClusterProfile` registry (from config store); `ClusterDescription`, quorum, brokers, log dirs, capability set per cluster | metadata every 30 s; capabilities every 1 h and on reconnect | config apply; `POST /clusters/{id}/refresh` | reads ≤ 30 s old; profile version bump propagates within one poll interval |
| topic | per-cluster topic snapshot: descriptions, configs, begin/end offsets, name index | every 30 s (chunked admin calls) | partial update after create/update/delete of a topic; refresh endpoint | list/search ≤ 30 s old; detail pages re-describe the one topic live |
| message | serde registry per cluster (`Resource`, rebuilt on profile change); compiled CEL filters (content-hash id, bounded, TTL 1 h); SR schema-by-id cache (size-bounded), subjects (TTL 60 s) | on demand | profile change | payloads are never cached |
| consumer | per-cluster group snapshot: listings, descriptions, committed offsets; end offsets for committed partitions via its own `listOffsets` (no cross-service call) | every 30 s (50 groups × 4 parallel) | after reset/delete of a group | lag on the list page ≤ 30 s old; group detail page computes live |
| security | none (ACL/quota lists are live, 5 s bounded) ; ACL capability probe | probe hourly | — | — |
| schema | subject list (TTL 60 s), latest-version cache (size-bounded, TTL 60 s) | on demand | after register/delete | — |
| connect | per-connect state: connectors + statuses via `?expand=status&expand=info` | every 30 s | after any action | list ≤ 30 s old; detail live |
| ksql | query pipes (TTL 1 min, single use) | — | — | — |
| metrics | scraped broker metrics, inferred metrics from topic/consumer snapshot endpoints | every 30 s | — | `/metrics` exposition is the last scrape |
| identity | `RbacPolicy` (compiled once, hot-reloaded from file watcher/ConfigStore), sessions, OIDC state entries (5 min, single use) | on change | file change, session expiry | — |
| gateway | capability registry; `sessionId → Principal` (TTL 30 s); OpenAPI merge | readiness every 10 s | logout, role reload event | — |

Cache discipline (PLAN §29): TTL, invalidation trigger, bound, hit/miss metrics and a named
staleness contract, all recorded in the table above. Secrets and message payloads are never
cached. Small caches use `Ref` + TTL (`libs/cache.SnapshotCell`); bounded large caches
(schema by id, compiled filters) wrap Caffeine `AsyncCache` in `IO` (no Scaffeine).

Search: an in-memory prefix/substring/trigram index inside each snapshot (`libs/kernel`
`NameIndex`); Lucene only if a benchmark on ≥ 50 k names shows p95 > 50 ms (ADR-038).

## 10. Configuration ownership and distribution without restart

Typed configuration (PLAN §24) is loaded by Ciris (ADR-013) from CLI flags → env → YAML
files → defaults, with Kafbat-compatible env keys mapped explicitly. Ownership (ADR-036):

| Section | Owner (single writer) | Readers | Distribution |
| --- | --- | --- | --- |
| `kui.clusters[]` | cluster-service (`ClusterConfigStore`) | every Kafka-facing service via `GET /internal/v1/clusters/{id}/profile` (ETag = profile version) + `GET /internal/v1/clusters/stream` (SSE change notifications, cached fallback = last known profile) | no restart; services rebuild clients/serdes when the version changes |
| `kui.auth`, `kui.rbac` | identity-service (`RolePolicySource`, file watcher or store) | gateway (`/auth/me`, role reload SSE) | no restart for roles; auth adapter changes need an identity-service restart (documented) |
| `kui.gateway`, `kui.server`, `kui.telemetry` | each process | — | restart of that process |

`ClusterProfile` is a value object of the Cluster Registry context and the *published
language* every other context translates from: bootstrap servers, typed security (ADR-022),
optional registry/connect/ksql/metrics endpoints with their auth, serde declarations,
masking rules, audit settings, polling limits and admin batching knobs. Secrets travel as
`Secret[A]` (redacted in `toString`, Circe encoders and logs) and, for keystores, as inline
bytes so a distributed service never depends on a shared filesystem; adapters materialize
them into a private tmpfs path when the Kafka client insists on a path.

The config wizard (validate/apply/test-connection, cluster CRUD, related file upload) is a
cluster-service use case with a throwaway client built from the candidate profile; remote
validation of arbitrary URLs is gated by `kui.clusters.remoteValidation.enabled` and an
allow-list (SSRF, §14). Concurrent writers are rejected with `KUI-CONFIG-VERSION-CONFLICT`
(optimistic version in the stored YAML). The `ConfigStore` port has file and Kubernetes
Secret/ConfigMap adapters; no relational database is introduced (ADR-036).

## 11. All-in-one composition

`apps/allinone` depends on every service's `application`, `infrastructure` and `api` modules
plus the gateway's. One MacWire composition root (ADR-010) builds one `IO` runtime, one
Netty server, one otel4s provider:

- The gateway's per-service client is `TapirInProcessClient(endpoints, serverLogic)`, so the
  gateway code path (routing, RBAC pre-check, capability registry, aggregation) is byte-for-byte
  the one used in distributed mode; only the transport differs.
- `PrincipalCodec` is `PrincipalCodec.inProcess` (no signing); `Principal` is passed as a
  value. `kui.gateway.principalKey` is ignored with a warning.
- Services do not open listeners of their own; only the gateway port is exposed.
- Session store, `RbacPolicy`, audit sink, config store and capability registry are single
  in-memory instances.
- Fault isolation still holds at the code level: a failing use case returns a `KuiError`
  that the capability registry records exactly as it would from a remote 5xx.

## 12. Frontend shell and microfrontends

`frontend/ui-kernel` (design tokens, primitives, `QueryCache`, `Sse`, `ApiClient`, kernel
`Var`s), `frontend/ui-shell` (Waypoint router, layout, `FeatureGate`, capability banner,
lazy feature loader) and one module per feature (PLAN §21). Loading is a single Scala.js
link with `ModuleKind.ESModule` and `ModuleSplitStyle.SmallModulesFor(feature packages)`;
the shell references features only through `js.dynamicImport` thunks keyed by `FeatureId`
(ADR-012). Cross-feature panels (topic page → consumers tab) go through the kernel
`FeaturePanel` slot, never a direct import (`research/kafbat/ui-analysis.md` DC-H6).

Navigation state per feature and cluster (ADR-032):

```
FeatureState = Ready | Degraded(reason) | Unavailable(reason, since) | Forbidden | NotConfigured
```

`NotConfigured` entries are hidden; `Forbidden` entries are shown disabled with a permission
tooltip; `Unavailable` entries are shown dimmed and **clickable**, landing on the feature's
fallback panel (reason, `since`, retry, "what still works"); `Degraded` shows an amber dot.
Stale data stays on screen greyed with its timestamp; actions are disabled. The frontend
runs the same `Rbac.decide` on the pre-expanded permission list from `/api/v1/auth/me`.

## 13. Observability standard

PLAN §30 applies unchanged: otel4s (`oteljava` backend, ADR-009) for traces and metrics,
log4cats structured logs over Logback/logstash JSON with MDC bridged from the span context
(ADR-008). Every request/stream/refresh emits `correlation.id`, `user.id` (hashed when
`kui.telemetry.hashUserIds`), `cluster.id`, `service.name`, `operation`. Metric names are
the PLAN §30 list plus `kui.stream.events {service, stream, event}`,
`kui.stream.active {service, stream}`, `kui.cursor.rejected {reason}`,
`kui.principal.rejected {reason}`, `kui.config.version {section}`. Tapir interceptors in
`libs/observability` apply them to every server; the sttp client factory in `libs/http` to
every client. Health endpoints are unauthenticated and allow-listed; everything under
`/api/v1` is authenticated unless `kui.auth.type = disabled`.

## 14. Security boundaries

Three concerns, never conflated (PLAN §23): application authentication (identity-service,
ADR-015, ADR-019), Kafka cluster authentication (`libs/kafka-auth`, typed per cluster,
ADR-022), authorization (`libs/security-core`, enforced at the gateway and re-checked in every
service from the signed principal, ADR-020, ADR-021).

Boundary rules:

- Browser → gateway: opaque session cookie `kui_session` (`HttpOnly; Secure; SameSite=Lax`),
  id rotated at login, idle 30 min / absolute 12 h; `X-Kui-Csrf` double-submit header plus
  `Sec-Fetch-Site` check on every cookie-authenticated mutation; `POST /auth/logout`. Bearer
  tokens for API clients are stateless and exempt from CSRF.
- Gateway → services: `X-Kui-Principal` JWS (HS256, `kid` rotation, 60 s expiry, `aud` per
  service, request digest). A service reachable without the gateway still refuses unsigned
  requests. Services must not be exposed outside the cluster network.
- Read-only clusters and audit are decided from the same `AccessRequest` (`Action.isAlter`),
  so authorization, read-only enforcement and audit never disagree (ADR-021, ADR-023).
- Secrets: `Secret[A]` everywhere; `GET /api/v1/config` returns a redacted view derived
  from the same model; JAAS strings are rendered from typed fields with escaping; nothing
  secret appears in logs, traces, errors or the frontend.
- Outbound URLs (registry, connect, ksql, Prometheus, JMX, OIDC, LDAP) are config-time
  inputs: strict URL type, `http`/`https` only, deny link-local and metadata ranges by
  default, no cross-host redirects, upstream bodies never echoed into error messages.
- Regex patterns from config (RBAC values, masking, topic patterns) are compiled once at load
  and linted for catastrophic backtracking; user input never becomes a regex except the
  event-tracking `regex` operator, which runs with a match timeout.
- Threat model document by M6 (`docs/security/threat-model.md`).

## 15. Errors

`KuiError` (§4.1) is mapped in each `api` module to one envelope (ADR-034):

```json
{ "code": "KUI-TOPIC-NOT-FOUND", "message": "Topic orders does not exist on prod-eu",
  "details": [ { "field": "partitions", "restrictions": ["must be > 0"] } ],
  "correlationId": "3b1f...", "timestamp": "2026-09-03T10:11:12Z", "retryable": false }
```

Codes are stable strings, namespaced `KUI-<AREA>-<NAME>`; the frontend renders by code.
Stack traces never leave a service. Kafka client exceptions are translated by
`KafkaErrorMapper` in `libs/kafka` (total over the class list in
`research/kafka/admin-capabilities.md` §1–§5, property-tested); upstream REST errors are
translated by each client's sealed `UpstreamError` (ADR-037).

## 16. Repository layout

```
kui/
├── build.mill  .mill-version  .scalafmt.conf  .scalafix.conf
├── libs/      kernel/ contracts-core/ kafka/ kafka-auth/ serde/ serde-confluent/ filter/
│              cache/ observability/ security-core/ http/ config/ testkit/
├── services/  gateway/ cluster/ topic/ message/ consumer/ security/ schema/ connect/ ksql/
│              metrics/ identity/       (each: domain application infrastructure contract api app)
├── frontend/  ui-kernel/ ui-shell/ ui-clusters/ ui-topics/ ui-messages/ ui-consumers/
│              ui-schemas/ ui-connect/ ui-ksql/ ui-security/ ui-metrics/ ui-admin/
├── apps/allinone/
├── deployment/ docker/ compose/ helm/
├── e2e/        JVM Playwright + Testcontainers suites, fault-injection scenarios
├── benchmarks/ docs/ research/ tools/
```

`services/config` from PLAN §48 does not exist (§2). Mill task names follow PLAN §48.
