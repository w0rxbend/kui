# ADR-046 — The cluster profile seam: credentials travel on `/internal/v1`, and one shared client consumes them

- Status: Accepted
- Date: 2026-09-04
- Amends in effect: ADR-036 §"distribution", ADR-043; supersedes the open question in
  `ARCHITECTURE.md` §14 ("M2's first consumer decides how it receives credentials")

## Context

`services/cluster` is the single owner of `kui.clusters[]` (ADR-036): it reads the static
configuration, replays the `__kui_config` topic, decrypts the stored secret fields with
`kui.store.encryptionKey`, and holds the resolved `ClusterProfile` for every cluster.

Every other Kafka-facing service needs to turn a cluster id into a live Kafka client, and a
Kafka client needs the credentials — the SASL mechanism and JAAS values, the keystore and
truststore bytes, the passwords. Four such services are scheduled: `topic` (M2), `message`
(M3), `consumer` (M4) and `security` (M7).

M1 built the server half of the channel — `GET /internal/v1/clusters/{id}/profile` and
`GET /internal/v1/clusters/stream` — and shipped it with **every credential removed**, with the
description "M1 has no consumer that builds a Kafka client from this", and a `TECH_DEBT.md`
entry deferring the question to the first consumer. Three milestones were then groomed in
parallel, and each independently designed its own answer: M2 a shared
`services/cluster/client` module, M3 an `HttpClusterProfileSource` inside
`services/message/infrastructure`, M4 a `ClusterProfileSource` inside
`services/consumer/infrastructure`. Three implementations of one distributed protocol is the
failure mode the M0 review named as its second process finding, with a distributed-systems
blast radius attached.

## Decision

### 1. The internal profile channel carries credentials; nothing else does

`GET /internal/v1/clusters/{id}/profile` returns the **resolved** profile including its
credentials, as `Secret[_]`-typed fields. This is the only channel in KUI on which a Kafka
credential travels outward from the cluster service, and `/internal/v1` is reachable only
inside the deployment, signed per ADR-020 and ADR-043.

No `/api/v1` endpoint of any service ever returns a credential. Each Kafka-facing service's
contract suite asserts this against a profile whose every secret is a distinctive token, and
the same token is asserted absent from log lines and span attributes.

This is ADR-036's own sentence executed rather than reinterpreted: non-owner services "keep
receiving the resolved `ClusterProfile` over the internal contract", and "keystore bytes travel
inside the signed inter-service channel".

**The alternative was rejected:** making each Kafka-facing service a metadata-store client with
its own copy of `kui.store.encryptionKey`. That multiplies the blast radius of the one key
whose loss makes stored secrets unrecoverable by four, gives four processes credentials capable
of writing a topic only one of them owns, and contradicts ADR-036's single-writer ownership.

### 2. There is exactly one consumer implementation: `services/cluster/client`

A new JVM-only Mill module, `services/cluster/client`, compiled against
`services/cluster/contract.jvm`. It implements the whole protocol once:

- a conditional `GET` with `If-None-Match`, so an unchanged profile costs a 304;
- a subscription to `GET /internal/v1/clusters/stream` for change notification;
- a fallback poll for when the stream is unavailable;
- a **last-known-profile cache**, so a cluster-service restart does not stop a running browse
  or a running scrape;
- a change callback, so a consumer rebuilds its Kafka clients when a profile's version moves
  and closes the old ones;
- one `Resource`, whose cancellation closes the subscription and releases the cache.

Every Kafka-facing service depends on this module and writes none of it. A service that
believes it needs different behaviour changes this module, in a commit that has to say why,
rather than forking it invisibly.

### 3. A service holding a stale profile is `Degraded`, never `Unavailable`

While the cluster service is unreachable, a consuming service keeps working from its last known
profile and reports its capability as `Degraded` with the reason and the age. It does not fail
requests, and it does not crash-loop on startup — a service that has never seen a profile
reports `Degraded` with "no profile yet" and becomes ready when the first one arrives, so that
boot order between two containers is not a correctness requirement.

### 4. The dependency is legal by rule A11

A service may depend on another service's `contract` and `client` modules and on nothing else
(ADR-041 Amendment 4, rule A11). `client` is on the allow-list by name, so a second such module
has to be argued in the commit that adds it.

## Consequences

- `ClusterProfileDto` on `/internal/v1` gains its credential fields; the public cluster DTOs are
  unchanged and stay redacted. Two shapes, one of which is internal-only, is the cost.
- The cluster service becomes a runtime dependency of every Kafka-facing service — but a soft
  one: its absence degrades, it does not break. This is asserted by a fault-injection scenario
  in each consuming milestone (M2 TOP-034, M3 MSG-026, M4 GRP-022).
- There is exactly one resolver of "what does cluster X connect to". Two services can no longer
  disagree about whether a cluster is reachable, because only one of them ever decides.
- `ARCHITECTURE.md` §14's open question closes.

## Reversibility

Medium. The channel's shape is easy to change while there is one client implementation, which
is the main reason there is one. Reversing decision 1 — moving to per-service store clients —
is a deployment-model change affecting key management and operator documentation, and would be
a new ADR superseding this one.

## References

ADR-020 (signed principal header), ADR-022 (typed cluster auth, `ClusterConnection`),
ADR-036 (dynamic config ownership), ADR-037 (upstream resilience), ADR-041 Amendment 4 (rule
A11), ADR-042 (metadata store), ADR-043 (internal service-to-service calls);
`ARCHITECTURE.md` §5, §14, and the `TECH_DEBT.md` row for the cluster-profile API.
