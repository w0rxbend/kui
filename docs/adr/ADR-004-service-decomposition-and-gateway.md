# ADR-004 — Service decomposition, service catalog and the BFF gateway

- Status: Accepted
- Date: 2026-09-03

## Context

The initial service catalog proposed twelve deployables and left open whether
`kui-security-service` merges into `kui-cluster-service` and `kui-config-service` into the
gateway. Microservices are a fault-isolation requirement, so the split must follow
failure domains and bounded contexts, not code size.

## Decision

Catalog (`ARCHITECTURE.md` §2): gateway + cluster, topic, message, consumer, security,
schema, connect, ksql, metrics, identity. Eleven deployables.

1. **`kui-security-service` stays separate.** Its capability gate is different (an
   authorizer must exist and the principal needs `ALTER` on the cluster), its failure signature
   is different (`SecurityDisabledException`, slow `describeAcls` on large ACL sets), and
   ACL presets / CSV sync are not topology. Putting it inside the Core cluster service would
   let an optional feature degrade the one service the UI needs for every cluster.
2. **`kui-config-service` is dissolved.** Configuration is not a bounded context. Cluster
   configuration (registry, wizard validate/apply/test-connection, cluster CRUD, related
   file upload) is owned by the Cluster Registry context, which already holds the runtime
   registry and publishes `ClusterProfile`. Auth/RBAC configuration is owned by
   Application Identity. Gateway configuration is the gateway's. `/api/v1/config` is a
   gateway aggregation over those owners (ADR-036).
3. The gateway is application code only: edge auth, RBAC pre-check, contract-derived routing,
   screen aggregations with `Section` envelopes, SSE fan-in, capability registry, static
   assets, OpenAPI merge, audit forwarding. No domain rules, no Kafka client.
4. Every service exposes `/health/live`, `/health/ready`, `/capabilities`; the gateway never
   fails a whole response because one upstream failed.
5. Cross-service calls in request paths are limited to: any Kafka-facing service →
   cluster-service (`ClusterProfile`, cached with last-known fallback) and metrics-service →
   topic/consumer snapshot endpoints (30 s cadence).

## Evidence

- `research/kafka/admin-capabilities.md` §5 and DC-D11 (ACL/quota failure modes and gates).
- `research/kafbat/feature-matrix.md` D-8 (the merge candidate: six endpoints, same AdminClient) —
  rejected on tier grounds, not size.
- `research/kafbat/architecture.md` D2, D8 (cluster registry as the profile publisher; wizard
  without restart), D12 (metrics needs topic/consumer snapshots).
- `research/kouncil/architecture.md` D7 (cluster CRUD with test-connection belongs with the
  registry; validation on a throwaway client).
- `research/kafbat/api-analysis.md` "Proposed KUI /api/v1 mapping" (partial aggregations list).

## Consequences

- Eleven Docker images and Helm deployments; the all-in-one shape hides this for small installs.
- The cluster service gains a config store port and the wizard use cases; it stays Core.
- Kafka-facing services carry a cached `ClusterProfile` and rebuild clients on version change.
- 3–4 partially overlapping admin scrapes per cluster in distributed mode (accepted, ADR-027).

## Alternatives rejected

- Config service inside the gateway: puts Kafka client dependencies and the SSRF-prone
  validation surface into the Core edge process and gives the gateway domain logic.
- Config service as its own deployable: a "utility service" without a domain.
- Merging security into cluster: rejected above.

## Reversibility

Medium. Service boundaries are module boundaries; merging two services later is a composition
root change, splitting one is a contract change.
