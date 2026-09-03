# ADR-021 — RBAC model: Kafbat vocabulary, pure evaluation shared with the frontend

- Status: Accepted
- Date: 2026-09-03

## Context

Kafbat's RBAC (roles → subjects/clusters/permissions with regex values and action
dependants, `defaultRole`, `CONNECTOR` → `CONNECT` fallback, audit-topic special case) is the
model operators already run. Kouncil has a flat 44-function catalog. The frontend must hide
controls consistently with the server without re-implementing the rules in another language.

## Decision

- Adopt Kafbat's model and its complete resource × action matrix verbatim as the canonical
  KUI vocabulary (`Resource`: ApplicationConfig, ClusterConfig, Topic, ConsumerGroup, Schema,
  Connect, Connector, Ksql, Acl, Audit, ClientQuotas; actions and `implies` per
  `research/scala/security-research.md` §2.2), including `defaultRole`, regex `value`,
  subject `provider/type/value/isRegex`, the connector → connect fallback and the "audit topic
  requires `Audit.View`" rule. The config keys are Kafbat's under `kui.rbac`.
- `Rbac` in `libs/security-core` is pure and cross-compiled: `resolveRoles` (login time),
  `effectivePermissions`, `decide`, `visible` (list post-filtering). Laws are ScalaCheck
  properties: monotone in permissions, `implies` closure idempotent, `ALL` = full set,
  `defaultRole` applies only when no role matches, cluster gate before resource gate,
  connector falls back to connect.
- Read-only clusters are enforced inside `decide` via `ClusterFlags.readOnly` and
  `Action.isAlter`; the two Kafbat exceptions (topic analysis, smart-filter registration/test)
  are modelled as non-alter actions. No URL-pattern read-only filter exists.
- Enforcement: the gateway pre-checks with the session's expanded permissions; every service
  re-runs `decide` from the signed principal (ADR-020). Audit records derive from the same
  `AccessRequest` + `Decision` (ADR-023).
- The frontend runs the same `decide` on the expanded permission list from `/auth/me`; a
  kernel `ActionPermissionWrapper` merges the RBAC decision and the capability state into
  one tooltip.
- UI-managed roles (Kouncil's group/permission matrix) are an identity-service feature (M6+)
  writing the same role shape into the `ConfigStore`; file-configured roles remain canonical
  and win on conflict.
- Regex values are compiled once at load and linted for catastrophic backtracking.

## Evidence

- `research/scala/security-research.md` §2 (model, matrix, evaluation algorithm, frontend
  permission set), §6.5, ADR-019 candidate.
- `research/kafbat/architecture.md` D9; `research/kouncil/architecture.md` D8;
  `research/kafbat/feature-matrix.md` D-5 and open question 1 (Kafbat vocabulary chosen).

## Consequences

- Kafbat → KUI migration of `rbac.roles[]` is a key rename.
- List endpoints filter by visibility rather than failing, as in Kafbat.

## Alternatives rejected

- Kouncil's function catalog as canonical: no per-resource patterns, no per-cluster scope.
- Evaluating RBAC only at the gateway: a service reachable without the gateway would be open.

## Reversibility

Medium. The vocabulary is part of the configuration contract.
