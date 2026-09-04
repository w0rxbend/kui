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

## Amendments

**Amendment 1 — an endpoint declares its own permission, and an undeclared endpoint is refused.**

Added 2026-09-04 while building the enforcement this ADR specifies. The decision above says the gateway
pre-checks and every service re-runs `decide`, and left open where the two get the *question* from. Both now
read it off the endpoint itself: `kui.contracts.rbac.EndpointAuthorization` is a Tapir attribute a contract
attaches beside the existing mutation marker, naming the operation, the resource, the actions, and which path
parameter carries the resource's name. `EndpointDecision.decide` turns that plus a request path into a
`Decision`, and the gateway, the services and the browser all call it.

The alternative was a table of endpoint names on each enforcing side, and it was rejected for the reason this
ADR exists at all: a rule written twice is a rule that eventually disagrees with itself, and the disagreement
is normally in the direction of allowing something.

An endpoint carrying no declaration is **refused**, not allowed, and the gateway's `EndpointAuthorizationSuite`
turns that into a build failure by walking every proxied contract. Unreachable-until-noticed is loud;
unprotected-until-noticed is how the defects this project keeps finding get shipped.

**Amendment 2 — a resource named only in the request body is checked coarsely at the edge and exactly by its
service.**

A topic create names its topic in the body. The gateway does not decode service request bodies — it proxies
them, over endpoints whose input types Tapir has erased — so it cannot match that name against a pattern. Such
a requirement is declared as `NameSource.RequestBody(field)` and the edge then asserts two things about it:
the cluster is not read-only, and the caller holds the action on *some* pattern of that resource. The service,
which has the decoded body, owes the exact check.

This is a deliberate weakening and it is named rather than hidden, so that the endpoints in that position can
be enumerated — they are, by the same suite — instead of being discovered later. Treating a body-named
resource as `Unnamed` was rejected: `Permission.covers` would then refuse every create in any deployment with
RBAC on, which is a different bug in the safer direction and still a bug.

## Evidence

- `research/scala/security-research.md` §2 (model, matrix, evaluation algorithm, frontend
  permission set), §6.5, ADR-021 candidate.
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
