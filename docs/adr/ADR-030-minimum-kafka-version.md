# ADR-030 — Minimum supported Kafka broker version: 2.8

- Status: Accepted
- Date: 2026-09-03

## Context

Several KUI features rely on admin APIs that do not exist on old brokers: `listOffsets` and
`alterConsumerGroupOffsets` via AdminClient (2.5), `deleteConsumerGroupOffsets` (2.4),
`incrementalAlterConfigs` (2.3), client quotas API (2.6), `describeProducers` (2.8),
`describeMetadataQuorum` (3.3). Kafka 4.x clients dropped support for very old brokers.

## Decision

- KUI supports brokers running **Apache Kafka 2.8 or newer** (and Confluent/managed
  equivalents). Documented as a hard requirement; the cluster service records the detected
  version and shows a warning when a broker is older.
- Features that need a newer broker (quorum info 3.3+, tiered storage offsets 3.9+, KIP-848
  groups 4.0+) are probed and gated through the capability set (`ClusterFeature`), never
  assumed. Version detection uses `describeFeatures` `metadata.version` with a fallback to
  the `inter.broker.protocol.version` broker config.
- ZooKeeper-mode clusters are supported as long as they are ≥ 2.8; KRaft-only APIs degrade
  to `NotConfigured`/`Unsupported`.
- Testcontainers matrices in CI run the latest 4.x broker by default and a 2.8-compatible
  image in a nightly job.

## Evidence

- `research/kafka/admin-capabilities.md` §0 (version detection, capability probing table),
  §1–§5 "Min Kafka" columns, open question 3 (proposal: 2.8).
- `research/scala/ecosystem-mapping.md` F2 (kafka-clients 4.3.1, Java 11+ for clients).

## Consequences

- No fallback code paths for pre-2.5 offset handling or `alterConfigs` (deprecated).
- Managed services that hide some APIs are handled by probing, not by version.

## Alternatives rejected

- 2.5 minimum: leaves out `describeProducers` and quotas, complicating feature gating.
- 3.x minimum: excludes still-common 2.8 deployments without a technical need.

## Reversibility

High. Raising the minimum later removes code; lowering it adds fallbacks.
