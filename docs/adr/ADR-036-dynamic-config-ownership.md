# ADR-036 — Dynamic configuration: ownership, store and distribution without restart

- Status: Accepted
- Date: 2026-09-03

## Context

Kafbat's config wizard persists a YAML file and restarts the whole Spring context; only
`rbac.roles` hot-reloads via a file watcher. Kouncil keeps clusters, groups and policies in a
relational database with UI CRUD. PLAN §3 forbids a shared database; PLAN §24 asks for
distribution to services without restart; ADR-004 dissolved the config service.

## Decision

- **Ownership** (single writer per section): `kui.clusters[]` → cluster service;
  `kui.auth`/`kui.rbac` → identity service; process-local sections → each process.
- **Store**: a `ConfigStore[F]` port in `libs/config` with adapters for a versioned YAML file
  (default `dynamic-config.yaml` next to the static file, optimistic version field) and
  Kubernetes Secret/ConfigMap. No relational database is introduced; UI-managed roles and
  masking policies (M5+/M6+) use the same store. Static file configuration is the canonical
  base; the dynamic store overlays it; conflicts reject with `KUI-CONFIG-VERSION-CONFLICT`.
- **Distribution**: the cluster service publishes `ClusterProfile` per cluster with a
  monotonically increasing `version` at `GET /internal/v1/clusters/{id}/profile` (ETag) and
  change notifications at `GET /internal/v1/clusters/stream` (SSE, ADR-035). Kafka-facing
  services subscribe, keep the last known profile, poll as a fallback (60 s), and rebuild
  clients, serde registries and snapshots when the version changes; no restart. The identity
  service hot-reloads `RbacPolicy` from file watcher or store and notifies the gateway the
  same way. Authentication adapter changes (OIDC providers, LDAP) require an identity-service
  restart, documented.
- **Wizard** (cluster service): `PUT /config/clusters/validate` builds a throwaway profile and
  probes Kafka, registry, each Connect, ksqlDB and metrics endpoints independently, reporting
  per-component results; `PUT /config/clusters/apply` persists with version check and
  publishes; `POST /config/files` stores related files (keystores, protobuf descriptors)
  into the store as `Secret[Bytes]` referenced by name; cluster CRUD and test-connection
  reuse the same use cases. Remote validation is gated by `kui.clusters.remoteValidation.enabled`
  and a host allow-list. All wizard operations require `ApplicationConfig.Edit` and are audited.
- The gateway exposes `GET /api/v1/config` as a redacted aggregation over the owners.

## Evidence

- `research/kafbat/architecture.md` F7, D2, D8, open question 5 (two writers);
  `research/kouncil/architecture.md` F8, D7; `research/kafbat/feature-matrix.md` D-10
  (relational store proposal — rejected); `research/scala/security-research.md` §5 (SSRF via
  configured URLs; secret leakage through config endpoints).

## Consequences

- Cluster-facing services carry a small profile client and subscriber; in all-in-one the
  stream is an in-memory topic.
- Keystore bytes travel inside the signed inter-service channel; adapters materialize to tmpfs.
- Multi-replica writers of the same store rely on the version check, not on locks.

## Alternatives rejected

- Restart-on-apply (Kafbat): unacceptable for a gateway shared by many clusters.
- PostgreSQL/H2 with migrations (Kouncil): a stateful dependency the product does not need.
- Shared filesystem for uploaded files: not portable across Kubernetes nodes.

## Reversibility

Medium. The store is a port; the profile stream is an internal contract.
