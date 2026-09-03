# ADR-006 — fs2-kafka 4 and per-context Kafka admin ports

- Status: Accepted (amended 2026-09-03, Amendment 1 — see below)
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

## Amendment 1 — 2026-09-03 (M1 gate review)

**What changed.** The split between the two clients is now explicit, and it is the opposite of
what the Decision above implies for admin work:

- **Admin calls use the raw `org.apache.kafka.clients.admin.Admin`**, wrapped by
  `libs/kafka`'s own `KafkaFutures` bridge and `AdminClientPool`. `KafkaAdminClient[F]` is not
  used.
- **Consumers and producers use fs2-kafka** (`KafkaConsumer.resource`,
  `KafkaProducer.resource`), which is what the streaming, cancellation and chunking argument in
  the Context paragraph was actually about.

**Why.** Four things M1 needs are not expressible through fs2-kafka's admin wrappers, and each
was found by reading the pinned 4.0.0 surface rather than by preference:

1. The option objects. `DescribeClusterOptions.includeAuthorizedOperations`,
   `DescribeConfigsOptions.includeSynonyms` / `includeDocumentation`, and a per-call
   `timeoutMs` are not surfaced. Capability probing (ADR-030, ADR-039) is built on exactly
   those flags.
2. `DescribeCluster.controller` cannot represent the `null` controller a KRaft cluster reports
   during failover. KUI must render that as `None`, not crash — it is a named test in the M1
   plan (§7, "Admin adapter").
3. The convenient wrappers return `all()`-shaped futures, which fail an entire batch when one
   key fails. `BatchResult`/`PartialResult` — the whole partial-authorization story of
   `research/kafka/admin-capabilities.md` §1 — needs the per-key futures.
4. One bridge (`KafkaFutures`) is one layer to reason about instead of two.

**What did not change.** The pinned coordinates, the compression codecs, the port family in
`ARCHITECTURE.md` §4.2–§4.3, and the rule that `org.apache.kafka.*` is importable only inside
`libs/kafka*`, `libs/config`, `libs/testkit`, a service's `infrastructure` and an `app`
(ADR-041 A10). The "escape hatch where fs2-kafka lags" is no longer an exception for admin
work; it is the admin path.

**Tasks updated:** KAFKA-004 (which records the evidence in `libs/kafka/CLIENT-CHOICE.md`),
KAFKA-005 … KAFKA-009, STORE-005.
