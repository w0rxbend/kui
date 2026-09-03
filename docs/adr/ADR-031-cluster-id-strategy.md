# ADR-031 — Cluster identity: slug of the configured name, Kafka cluster id recorded

- Status: Accepted
- Date: 2026-09-03

## Context

Kafbat addresses clusters by their configured display name in every URL; Kouncil uses a
database id via `serverId`. KUI URLs, RBAC `clusters[]` lists, caches, audit records and
cursors all need a stable, typed key; broker ids are reusable and the Kafka cluster id is
not always available (some managed services).

## Decision

- `ClusterId` (opaque, `libs/kernel`) is a URL-safe slug derived deterministically from the
  configured `name` (`lowercase`, `[a-z0-9-]`, collisions rejected at config validation).
  Renaming a cluster yields a new id; bookmarks and RBAC entries referring to the old name
  stop matching, which is documented behaviour (same as Kafbat).
- The Kafka-reported `KafkaClusterId` is captured by the cluster service, shown in the UI, used
  to detect two configured entries pointing at the same cluster (warning), and paired with
  `BrokerId` in every cache key so reused broker ids never collide across clusters.
- Paths use `/clusters/{clusterId}`; the gateway validates the slug syntactically before
  routing and forwards `X-Kui-Cluster-Id`.
- RBAC `clusters[]` values are matched case-insensitively against the configured name **and**
  the slug, so Kafbat role files migrate unchanged.

## Evidence

- `research/kafbat/api-analysis.md` "Proposed KUI /api/v1 mapping" conventions and open
  question 1 (slug now, rename = new id).
- `docs/domain/kafka-glossary.md` §1 Cluster and Broker invariants.
- `research/scala/security-research.md` §2.4 (case-insensitive cluster matching in roles).

## Consequences

- Readable URLs and a key that exists before the first successful connection.
- A later "stable generated id" can be introduced as an alias table without changing paths.

## Alternatives rejected

- Generated UUIDs: unreadable URLs; requires persistence before any cluster is reachable.
- Kafka cluster id as the key: absent on some services; not known until connected.

## Reversibility

High.
