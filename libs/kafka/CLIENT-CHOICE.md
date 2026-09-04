# Which Kafka client `libs/kafka` builds on, and why

**Decision.** `libs/kafka` builds its **admin** calls on the raw
`org.apache.kafka.clients.admin.Admin`, behind the `KafkaFuture` bridge in `KafkaFutures.scala`. It
uses **fs2-kafka** for **consumers and producers**.

This reverses the default ADR-006 assumed — "one adapter over `KafkaAdminClient[F]` … using the raw
`Admin` escape hatch where fs2-kafka lags". [ADR-006 Amendment 1](../../docs/adr/ADR-006-fs2-kafka-and-admin-ports.md)
was written and Accepted during M1 planning (finding F-08) and records the reversal;
this file is the evidence behind it, verified against the pinned release rather than remembered.

fs2-kafka is not decorative and is not being removed. Consumers and producers are where its value
actually is — streaming, cancellation, resource safety and commit semantics — and those are exactly
what the metadata store (STORE-006) and M3's message browsing need.

## The four reasons

Each is a required behaviour of M1, not a matter of taste.

**1. The option objects.** Every admin request Kafka exposes takes an options object, and three of
them carry settings KUI needs: `DescribeClusterOptions.includeAuthorizedOperations` (KAFKA-007, for
"can this principal do anything here"), `DescribeConfigsOptions.includeSynonyms(true)` and
`.includeDocumentation(...)` (KAFKA-008, for showing an operator *where* a broker setting came from
and what it means), and the per-call `timeoutMs` override every one of them has. fs2-kafka's
wrappers take no options object at all — see the table below, whose "options" column is `no` on
every row.

**2. The null controller.** fs2-kafka models the controller as `describeCluster().controller(): F[Node]`.
Kafka returns `null` for the controller during a KRaft controller failover
(`research/kafka/admin-capabilities.md` §1, "Describe cluster"), and the project requires that "a
`null` controller during failover is `None` and not a crash". A type that cannot express absence
cannot satisfy that requirement; `KafkaFutures.fromNullableFuture` can.

**3. Per-key partial results.** `describeConfigs().values()` and `describeLogDirs().descriptions()`
return a *map of futures*, one per key, so that a broker KUI is not authorized to read can be
skipped while the other five succeed. fs2-kafka's `describeConfigs` and `describeLogDirs` return the
`all()`-shaped future, which fails the whole batch when one key fails. That is precisely what the
milestone's "authenticates but authorizes nothing" fault-injection scenario forbids, and it is why
`BatchResult` (KAFKA-005) exists.

**4. One layer, not two.** The bridge is about forty lines in one file. Wrapping a wrapper and then
reaching past it for most calls leaves two abstractions to debug and a reader who cannot tell which
one a given method went through.

## Verification against the pinned release

fs2-kafka **4.0.0** (`org.typelevel::fs2-kafka::4.0.0`), read from the jar on the resolved classpath
with `javap` rather than from documentation:

```
$ ./mill show libs.kafka.resolvedMvnDeps | grep fs2-kafka
  ".../org/typelevel/fs2-kafka_3/4.0.0/fs2-kafka_3-4.0.0.jar"
$ javap -classpath .../fs2-kafka_3-4.0.0.jar fs2.kafka.KafkaAdminClient
```

| Call | Wrapped by fs2-kafka 4.0.0 | Options KUI needs available | Note |
| --- | --- | --- | --- |
| `describeCluster` | yes, as `DescribeCluster[F]` with `nodes` / `controller` / `clusterId` | **no** | `controller(): F[Node]`; no `DescribeClusterOptions`, so `includeAuthorizedOperations` is unreachable and a `null` controller is unrepresentable |
| `describeConfigs` | yes, `describeConfigs(resources): F[Map[ConfigResource, List[ConfigEntry]]]` | **no** | `all()`-shaped: one unauthorized broker fails the whole call. No `includeSynonyms`, no `includeDocumentation` |
| `describeLogDirs` | yes, `describeLogDirs(brokers): F[Map[Int, Map[String, LogDirDescription]]]` | **no** | `all()`-shaped; per-broker errors are not recoverable |
| `describeFeatures` | yes, `describeFeatures(): F[FeatureMetadata]` | **no** | no timeout override, which KAFKA-009's probe wants so a slow probe cannot outlive its budget |
| `describeMetadataQuorum` | yes, `describeMetadataQuorum(): F[QuorumInfo]` | **no** | no options |
| `listOffsets` | yes, `listOffsets(offsets, isolationLevel)` | **no** | no `ListOffsetsOptions` beyond the isolation level; `all()`-shaped |
| `listTopics` | yes, as `ListTopics[F]` with `names` / `listings` / `namesToListings` / `includeInternal` | partly | `includeInternal` is the one option fs2-kafka does surface |
| `describeTopics` | yes, `describeTopics(topics)` | **no** | `all()`-shaped; no `includeAuthorizedOperations` |
| `createTopics` | yes, `createTopics(topics)` and `createTopic(topic)` | **no** | no `validateOnly`, which a dry-run create needs (M2) |
| `listGroups` | **no** | — | only `listConsumerGroups` exists; the KIP-848 `listGroups` surface is absent |
| `describeProducers` | yes, `describeProducers(partitions, brokerId)` | **no** | `all()`-shaped |

Ten of the eleven calls are wrapped; **ten of the eleven expose no options object**, and the one
option that is exposed (`includeInternal`) is not one M1 needs. The escape hatch would therefore be
the normal path rather than the exception, which is the finding ADR-006's own consequences section
left open (DEVPLAN risk R-5).

## Consequences

- `AdminClientPool.run` hands callers a raw `Admin`. Everything above it — `ClusterAdmin`
  (KAFKA-007 … KAFKA-009) — is KUI's own port, so no caller outside this module sees a Kafka type.
- `KafkaFutures` is the only bridge, so unwrapping `CompletionException` and getting off the admin
  client's single I/O thread happen once rather than at each call site.
- `kafka-clients` is pinned explicitly at 4.3.1, above fs2-kafka's own transitive version, per
  `DEPENDENCY_MATRIX.md`.
- Consumers and producers go through `ConsumerFactory` and `ProducerFactory`, which build fs2-kafka
  settings from the same `ConnectionProperties` renderer the admin client uses, so the two cannot
  authenticate differently.
- ADR-006 needs an amendment recording this; it was written at the gate review as Amendment 1, and
  CFGOP-008 folds the table above into that ADR's consequences section.
