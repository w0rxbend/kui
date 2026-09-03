# ADR-006 — fs2-kafka 4 and per-context Kafka admin ports

- Status: Accepted
- Date: 2026-09-03

## Context

Kafbat wraps the Java `Admin` client in one 800-line reactive class that mixes every
resource family. KUI needs Kafka access that respects the dependency rule (no
`org.apache.kafka.*` in services), streams records with cancellation, and survives the
managed-service quirks the research found.

## Decision

- **fs2-kafka 4.0.0** (`org.typelevel` group id) with **kafka-clients 4.3.1** overriding the
  transitive 4.2.0. Compression codecs `snappy-java` and `lz4-java` (yawk fork) at runtime scope.
- `libs/kafka` exposes the port family in `ARCHITECTURE.md` §4.2–§4.3: `ClusterAdmin`,
  `TopicAdmin`, `GroupAdmin`, `SecurityAdmin`, `MessageBrowsePort`. One adapter implements
  them over `KafkaAdminClient[F]`, `KafkaConsumer.resource` and `KafkaProducer.resource`,
  using the raw `Admin` escape hatch where fs2-kafka lags.
- Adapter invariants: chunked batching (200 topics/partitions, 50 groups, parallelism 4,
  configurable per cluster), no-leader filtering before `listOffsets`, per-key
  `BatchResult` with explicit `Skipped` reasons (never silent drops), client invalidation on
  reconnect-class errors, unique `client.id` per cluster and purpose, exhaustive
  `KafkaErrorMapper` with a property test over the documented exception classes.
- Consumers for browsing use `assign`/`seek`/`pause`, never `subscribe`; termination is
  position-based; `isolation.level` is a request parameter defaulting to `read_uncommitted`.
- `libs/kafka-auth` assembles client properties from the typed security config (ADR-022) and
  hosts the cloud SASL handlers as optional runtime modules.

## Evidence

- `research/scala/ecosystem-mapping.md` F2 (fs2-kafka 4.0.0 group id change, kafka-clients 4.3.1).
- `research/kafka/admin-capabilities.md` §0 (single I/O thread, batching, partial failure,
  version detection, managed-service errors), DC-D1, DC-D3, DC-D4, DC-D5, DC-D8.
- `research/kafbat/architecture.md` F4 (AdminClient invalidation), F5 (emitter design).

## Consequences

- Kafka 4 client API differs from Kafbat's 3.9-based reference in places (`describeCluster`,
  `listGroups`, KIP-848 group states); the adapter maps new states defensively.
- Open question carried to DEPENDENCY_MATRIX: which admin calls need the raw `Admin` escape
  hatch on the pinned fs2-kafka tag (`describeMetadataQuorum`, `listGroups`, `describeProducers`).
- Minimum broker version is governed by ADR-030.

## Alternatives rejected

- Hand-written `Admin` wrapper: fs2-kafka already covers the surface; a wrapper would
  duplicate resource management.
- fs2-kafka 3.9.1 (`com.github.fd4s`): parked line; 4.x offset model is a deliberate redesign.
- One global admin port: reproduces Kafbat's god class and couples every context to all of it.

## Reversibility

Medium. Ports are traits; the adapter is one module; the streaming protocol of the message
service depends on `MessageBrowsePort` semantics.
