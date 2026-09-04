# ADR-042 — KUI metadata lives in Kafka, in internal compacted topics

- Status: Accepted
- Date: 2026-09-03

## Context

ADR-036 put dynamic configuration behind a `ConfigStore[F]` port with a versioned YAML file and
a Kubernetes Secret/ConfigMap adapter. That works for one replica writing one file, but it does
not answer where UI-managed clusters, roles, masking policies and uploaded keystores live when
KUI runs as eleven services with more than one replica each: a file is per-pod, and a shared
filesystem is not portable (ADR-036 already rejected it). Kouncil solves the same problem with
PostgreSQL; the project's data-ownership rules forbid a shared database, and `docs/FEATURE_MATRIX.md`
OT-004 has carried that contradiction since the M0 architecture review (`TECH_DEBT.md` TD-014).

KUI already requires a Kafka cluster to be useful at all, and Kafka's log compaction is exactly
a durable, replicated, ordered key-value store with change notification built in.

## Decision

**KUI stores its own metadata in Kafka, in internal compacted topics prefixed `__kui_`
(`kui.store.topicPrefix`). No relational database is introduced, ever.** In scope: registered
clusters and their connection configuration, application settings, UI-managed RBAC roles and
user groups, masking policies, uploaded related files, and audit events.

1. **Store cluster and bootstrap.** The topics live on a designated *store cluster* configured
   **statically** under `kui.store.kafka.*` (file/env, Ciris, ADR-013). It cannot come from the
   store, because the store is where the managed clusters' connection strings are. It may be one
   of the managed clusters or a separate one. Bootstrap order is fixed and one-directional:
   static config → store Kafka client → replay `__kui_config` to end → managed clusters known →
   service reports Ready.
2. **Topics.** `__kui_config` (compacted, **single partition**, key = section path such as
   `cluster/<clusterId>`, `settings/global`, `rbac/roles`, `masking/<clusterId>`; value = a
   versioned JSON envelope, Circe, ADR-007); `__kui_files` (compacted, single partition, key =
   file id, binary payloads with a size cap); `__kui_audit` (**not** compacted, retention-based,
   partitioned by cluster id — the topic ADR-023 already anticipated, renamed to the `__kui_`
   prefix). Exact configs are in `docs/operations/metadata-store.md`. KUI creates missing topics
   and validates existing ones, failing fast with a named error when settings are incompatible.
3. **Consistency.** One partition gives total order per topic. Each owning service replays the
   log into memory, then follows the tail. Writes use `acks=all` and `enable.idempotence=true`,
   and the writer waits to read its own record back from the tail before acknowledging the API
   call (read-your-writes). Each entry carries a `version`; a writer produces only if its base
   version matches its current state, and after read-back detects a lost race — another record
   for the same key with the same base version landed first — and fails with the existing
   `KUI-CONFIG-VERSION-CONFLICT`. Deletion is a tombstone. This is correct with several replicas
   of the same owning service because the partition, not a lock, is the serialization point:
   every replica sees the same order and exactly one of two racing writers wins. ADR-036's
   single-writer-per-section ownership still holds; it now prevents two *contexts* from writing
   one section, not two processes from racing.
4. **Secrets at rest.** SASL passwords, JAAS material, keystore bytes and OAuth client secrets
   are encrypted with AES-GCM before they reach the topic, under a key from
   `kui.store.encryptionKey` (env or mounted secret, never in the store), with a `keyId` in the
   envelope so keys can be rotated. Records are readable by anyone with topic read access, so
   this is mandatory, not optional (`research/scala/security-research.md` §5, "Secret leakage
   through config endpoints/logs"). Operations docs carry restrictive ACL guidance for `__kui_*`.
5. **Port and adapters.** The `ConfigStore[F]` port of ADR-036 stays. Adapters: **Kafka**
   (default, production) and **file** (dev, bootstrap, read-only). The separate Kubernetes
   Secret/ConfigMap adapter is **dropped**: a mounted Secret or ConfigMap is a path, so the file
   adapter reads it with no extra code. Static file configuration remains the canonical base and
   the store overlays it; precedence is unchanged.
6. **Distribution.** The log *is* the change notification. The **cluster** and **identity**
   services read `__kui_config` directly, because they own sections and must write them. Every
   other Kafka-facing service (topic, message, consumer, schema, connect, ksql, security,
   metrics) keeps getting `ClusterProfile` over `/internal/v1/clusters/{id}/profile` and
   `/internal/v1/clusters/stream` (ADR-036, ADR-035): they need the *resolved, redacted* profile
   rather than raw sections, they must work when they have no store-cluster credentials, and one
   extra hop is cheaper than nine more Kafka connections and nine decryption key holders. The
   **gateway stays out of the store** entirely (ADR-040 edge rules).
7. **All-in-one and local dev.** With one Kafka (the dev Compose broker) the store cluster is
   that broker, RF 1. With no `kui.store.kafka.*` configured, the file adapter is used and every
   store-backed write capability reports `NotConfigured`.
8. **Failure behavior.** If the store cluster is unreachable, services keep serving from last
   known state, mark the affected capability `Degraded(reason)` through the fold (ADR-039) so
   responses carry the degraded envelope, and reject writes with a store-unavailable error.

## Evidence

- `research/kouncil/architecture.md` F8, D7 and `research/kafbat/feature-matrix.md` D-10
  (relational store — rejected here as it was in ADR-036).
- `research/scala/security-research.md` §5 (secret leakage; audit topic tampering, which is why
  ACL guidance is required).
- ADR-023 already put audit records in a Kafka topic; ADR-036 already anticipated a Kafka
  compacted `SessionStore` (`TECH_DEBT.md` TD-003). This ADR generalizes what was already there.

## Consequences

- The only stateful dependency KUI has is the Kafka it already manages.
- Cluster and identity services carry a store client, a replay loop and an encryption key.
- Single-partition topics cap write throughput; metadata writes are rare, so this is a feature
  (total order) and not a limit.
- A wrong or lost `kui.store.encryptionKey` makes secrets unreadable: backup is an operator duty
  documented in `docs/operations/metadata-store.md`.
- TD-014 is resolved; TD-003 (shared session store) gains an obvious adapter shape.

## Alternatives rejected

- **Relational database (Kouncil's approach).** A second stateful system to run, back up and
  migrate, for a few hundred rows. The project's data-ownership rules forbid sharing one, and one database per owning
  context multiplies the operational burden instead of removing it.
- **Versioned YAML file only (ADR-036 as written).** Per-pod state; two replicas of the cluster
  service diverge, and uploaded keystores need a shared filesystem that Kubernetes does not give
  portably.
- **Kubernetes ConfigMap/Secret only.** Ties KUI to one deployment platform, needs cluster-role
  permissions to write, and has a 1 MiB object limit that keystores and descriptors can exceed.

## Reversibility

Medium. `ConfigStore[F]` is a port and the file adapter stays shipped, so a different backing
store is an adapter. The envelope format and topic names are public operator surface, so
changing them after M1 needs a migration path.
