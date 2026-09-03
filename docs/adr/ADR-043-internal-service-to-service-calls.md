# ADR-043 — Direct service→service calls are permitted on the callee's published contract

- Status: Accepted
- Date: 2026-09-03

## Context

PLAN §16.6 says services "never call each other synchronously in request paths except through
the gateway's contracts", and gives as its own example the topic service reading connection
configuration from the cluster service "with caching" and degrading "to its last-known config".
The example describes a direct call; the sentence can be read as requiring the gateway to
relay it. `ARCHITECTURE.md` §5 resolved the ambiguity silently in favour of direct calls, and
listed the only two edges that exist: every Kafka-facing service → cluster-service for
`ClusterProfile`, and metrics-service → the topic and consumer snapshot endpoints. The G6 gate
review (finding F-06) recorded that no document states which reading is correct.

The question has to be settled before M1, when the first such call is written, and eleven
services will inherit whichever answer is given.

## Decision

**A service may call another service directly, over the callee's published `contract` module,
on `/internal/v1`.** The gateway does not relay internal traffic.

Permitted only under all four conditions:

1. **Through the published contract.** The caller uses the callee's `contract` module and its
   generated client — never a hand-written path, never a private endpoint. This is what
   PLAN §3 requires ("all inter-service traffic uses the published Tapir contract of the
   callee") and it is unchanged by this ADR.
2. **With a cached last-known fallback.** The caller keeps the last successful response and
   serves it, marked `Section.Stale`, when the callee is unavailable. A direct call may never
   be the reason a caller becomes unavailable — this is the condition that preserves PLAN §2.1.
3. **Reported to the capability registry.** A failing internal call surfaces as the caller's
   own degraded capability, so the UI still explains what is wrong (ADR-039 §6 governs which
   errors count).
4. **One hop, no chains.** A service called over `/internal/v1` must not itself make an
   internal call to satisfy that request. Fan-out is a gateway aggregation concern (ADR-004).

The edge list is closed and lives in `ARCHITECTURE.md` §5. Adding an edge requires an
amendment to this ADR, so the list cannot grow by habit.

Every internal call carries the signed principal (ADR-020) and is subject to the same
resilience wrapper as any upstream (ADR-037): timeout, bulkhead, circuit breaker.

## Why not relay through the gateway

- **It would reduce fault isolation, which is the property §16.6 exists to protect.** Relaying
  makes the gateway a mandatory dependency of every service *pair*. Today a gateway outage
  costs the UI; under relaying it would also stop the topic service from refreshing its cluster
  profile. A rule written to contain failure would have spread it.
- **It contradicts ADR-004.** The gateway "has no domain logic"; relaying `ClusterProfile`
  traffic between two domain services routes domain data through the one module defined as
  holding none, and gives it a second, invisible API surface.
- **It buys no policy.** The stated benefits of a central hop — authorization, observability,
  resilience — are already unconditional: the principal is signed and re-verified by the callee
  (ADR-020), traces propagate by header (ADR-009), and the resilience wrapper is a library every
  caller uses (ADR-037). The hop would add latency and a failure domain in exchange for nothing.
- **The reference agrees by construction.** Kafbat is a monolith and makes these calls in
  process; nothing in the research suggests a relay is needed, and `research/kafbat/architecture.md`
  D2 and D12 describe exactly the two dependencies listed here.

## Consequences

- PLAN §16.6's wording needs the amendment recorded in `STATUS.md`: "except through the
  gateway's contracts" reads as "except through the **published** contract of the callee".
- Each caller carries a cached profile and rebuilds its clients when the version changes
  (`ARCHITECTURE.md` §10) — already the plan of record.
- The four conditions are reviewable: a task that introduces an internal call must show the
  fallback, the capability report and the absence of a chain.
- Nothing in M0 is affected: no service→service call exists before M1.

## Alternatives rejected

- **Relay through the gateway.** Rejected above.
- **Forbid internal calls entirely; duplicate the data.** Every Kafka-facing service would need
  its own copy of the cluster registry, which is the shared-mutable-state problem PLAN §3
  forbids in a different shape.
- **Asynchronous propagation over `kui.internal.events`.** Deferred at M6 by PLAN §17 and
  unchanged here; when it arrives it removes calls rather than reshaping them.

## Reversibility

Medium. The call sites are behind ports in each caller's `application`; swapping the transport
is an adapter change, but the caching and fallback behaviour would have to move with it.

## References

PLAN §3, §16.6, §17; ADR-004 (gateway holds no domain logic), ADR-020 (signed principal),
ADR-037 (resilience), ADR-039 §6 (what feeds the capability fold), ADR-042 (the store the
cluster registry writes to); `ARCHITECTURE.md` §5, §9, §10; `docs/domain/context-map.md`
(Cluster Registry as Open Host Service and Published Language);
`docs/plans/M0/GATE-REVIEW.md` finding F-06.
