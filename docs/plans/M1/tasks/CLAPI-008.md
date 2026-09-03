# CLAPI-008 — Gateway: per-cluster capability entries in the registry

- **ID:** CLAPI-008
- **Title:** Gateway: per-cluster capability entries in the registry
- **Milestone / Feature:** M1 / CL-009, OT-003, OT-004
- **Owner role:** Chief Architect
- **Size:** S
- **Dependencies / blocked by:** CLAPI-006, CLDOM-007

## M1 gate review amendment — `ClusterFeatures` keeps its third set

**F-05, major, fixed.** KAFKA-009 produces a three-valued `ClusterFeatures` — `present`,
`absent`, **`unknown`**, plus `probedAt` — precisely so that a probe which timed out is not
recorded as "this cluster cannot do that". The domain then collapsed it back to a
`Set[ClusterFeature]` at the port, which throws the third set away and reintroduces the exact
bug KAFKA-009's decision exists to prevent: a one-hour cache of a lie.

**The domain gains its own `ClusterFeatures`**, in
`services/cluster/domain/src/kui/cluster/domain/ClusterFeatures.scala`, with the same three sets:

```scala
final case class ClusterFeatures(
    present: Set[ClusterFeature],
    absent:  Set[ClusterFeature],
    unknown: Set[ClusterFeature],
    probedAt: Instant
) {
  def has(f: ClusterFeature): Boolean = present.contains(f)
  def isUnknown(f: ClusterFeature): Boolean = unknown.contains(f)
}
object ClusterFeatures {
  def unprobed(at: Instant): ClusterFeatures   // everything unknown
}
```

with the same invariant KAFKA-009 asserts as a property: `present ++ absent ++ unknown ==
ClusterFeature.All`, always, and the three sets are pairwise disjoint. Assert it here too — the
two enums are defined in two modules (CLDOM-002 decision 2), and a shared invariant checked on
one side only is half a check.

**Signature changes that follow, everywhere in this spec:**

- `ClusterAdmin.capabilities(profile): F[ClusterFeatures]` (not `F[Set[ClusterFeature]]`).
- `ClusterSnapshots.capabilitiesOf(id): F[Option[SnapshotCell[F, ClusterFeatures]]]`.
- `ClusterDescription.features: ClusterFeatures`; `ClusterDescription.has(f)` is
  `features.has(f)`, unchanged in meaning.
- `CapabilityReportUseCase.stateOf` distinguishes the third case: a feature in `unknown` is
  **not** reported as unsupported. It renders as `Degraded` with the probe's reason where the
  screen depends on it, and as "not determined" otherwise — never as `absent`.
- `CLADP-002`'s adapter maps `libs/kafka`'s `ClusterFeatures` onto the domain's field for field,
  by exhaustive match on `ClusterFeature`, preserving all three sets and `probedAt`.

## Goal (user value)

The sidebar and the cluster switcher tell the truth per cluster: `prod-eu` is fine, `staging` is
degraded, and neither fact changes what the other shows. And — the property this task exists to
protect — one unreachable Kafka cluster never dims a feature for every other cluster's users.

## Scope

1. `CapabilitySignals` re-keyed from `ServiceId` to `CapabilityKey(service, cluster)`, which is
   what `CapabilityRegistry` has always keyed on (`CapabilityKey` already carries
   `cluster: Option[ClusterId]`; only the *inputs* side is service-keyed today).
2. `ReadinessPoller` writing one input set per cluster the polled service reported, plus the
   service-level one, and reporting each key to the registry.
3. The service-level fold stops folding per-cluster reports into itself (decision 1) — the fix
   for a latent bug in `ReadinessPoller.summarise`.
4. `CircuitFeed` and `ContractRouting.reportIfInfrastructure` continue to report on the
   **service** key only, unchanged (a transport failure is about the service, not a cluster).
5. The capability snapshot and stream therefore carry per-cluster entries with no change to
   `CapabilityRoutes`, `CapabilitySnapshot` or any DTO — the wire already models it.

## Non-goals

No new endpoint, no new DTO, no fold-algorithm change (ADR-039's four inputs, precedence, sticky
`since` and asymmetric debounce are untouched). No aggregation (CLAPI-007). No per-cluster
probing by the gateway: it polls services, never clusters (ADR-004 §3).

## Design references

- ADR-039 §6 — "only transport failures *of the upstream service* feed the registry". Read
  carefully, that sentence already decides this task: a Kafka cluster's unreachability arrives
  as *content* of a healthy `/capabilities` response, not as a transport failure, and content
  belongs to the key it is about.
- DEVPLAN §10 D4 — "The `cluster` capability is `Unavailable` only when the cluster *service* is
  down. An unreachable managed cluster is a `Section.Unavailable(reason)` inside a 200."
- `ARCHITECTURE.md` §6's `/capabilities` sample: `clusters: { "prod-eu": {...}, "staging": {...} }`
  — a service already reports per cluster; the gateway is what collapses it.
- The code this task changes:
  `services/gateway/application/src/kui/gateway/application/capability/{CapabilitySignals,ReadinessPoller,CapabilityRegistry}.scala`.
  `ReadinessPoller.summarise`'s own doc comment says "per-cluster keys arrive in M1 with
  clusters. Until then a service's clusters are folded into one verdict, taking the worst" —
  this is the task that comment names.

## Files to change

```
services/gateway/application/src/kui/gateway/application/capability/CapabilitySignals.scala
services/gateway/application/src/kui/gateway/application/capability/ReadinessPoller.scala
services/gateway/application/src/kui/gateway/application/capability/CircuitFeed.scala
services/gateway/api/src/kui/gateway/api/routing/ContractRouting.scala   (reportIfInfrastructure: service key, made explicit)
services/gateway/application/test/src/kui/gateway/application/capability/ReadinessPollerSuite.scala
services/gateway/application/test/src/kui/gateway/application/capability/CapabilitySignalsSuite.scala
```

## Files to create

```
services/gateway/application/test/src/kui/gateway/application/capability/PerClusterCapabilitySuite.scala
```

## Public Scala signatures to implement

```scala
package kui.gateway.application.capability

trait CapabilitySignals[F[_]] {

  /** Now keyed by `(service, cluster)`. The service-wide key is `CapabilityKey(service, None)`
    * and is the one `CircuitFeed` and the proxy's transport-failure reporting write to.
    */
  def update(key: CapabilityKey)(observe: CapabilityInputs => CapabilityInputs): F[Unit]

  def inputs(key: CapabilityKey): F[CapabilityInputs]

  /** Every key currently known for one service: the service key plus one per cluster it last
    * reported. Used by the poller to retire a cluster that has disappeared from configuration.
    */
  def keysOf(service: ServiceId): F[Set[CapabilityKey]]
}
```

`ReadinessPoller`'s per-poll write becomes, in full:

```
readiness (the /health/ready call)   -> the service key only
circuit state                        -> the service key only
p95 latency                          -> the service key only
serviceReport for cluster c          -> the key (service, Some(c))
serviceReport for the service key    -> Some(ClusterCapability(configured = clusters.nonEmpty,
                                            features = union of features, status = "available"))
```

and after a successful poll the poller **retires** any `(service, Some(c))` key the response no
longer mentions, by reporting `CapabilityState.NotConfigured` for it. A cluster removed from
configuration must stop appearing in the switcher; leaving its last state behind is how a
deleted cluster stays on screen until the gateway restarts.

## Decisions this task takes (no ADR covers them)

1. **The service key's `serviceReport` no longer takes the worst of the clusters.** Today
   `summarise` folds three clusters into one verdict and reports `unavailable` if any one of
   them is; combined with the fold, one unreachable Kafka cluster dims the `cluster` capability
   for everyone — exactly the failure D4 forbids and exactly the reason ADR-039 §6 exists. After
   this task the service key's status is derived from readiness and the circuit only, and the
   per-cluster status lives on the per-cluster key. **This is a behaviour change to shipped M0
   code and it is a bug fix; the Implementation Report must say so, and
   `ReadinessPollerSuite`'s existing expectations change with it.**
2. **`configured = false` on a cluster key means `NotConfigured`, not `Unavailable`.** ADR-032 is
   explicit that "not configured" is not a failure and must not be rendered as one; the
   switcher shows such a cluster greyed with no error styling.
3. **A cluster the service reports but the gateway has never seen appears immediately.** No
   allow-list, no configured cluster list in the gateway (CLAPI-006 decision 2 for the same
   reason): a cluster registered at runtime through the store must be visible on the next poll.
4. **The debounce applies per key.** ADR-039's asymmetric debounce is a property of a
   transition, and two clusters flapping independently must not debounce each other. The
   registry already stores state per key, so this falls out of the re-keying; the suite asserts
   it rather than leaving it to luck.

## Library coordinates

None new.

## Acceptance criteria

```
$ ./mill services.gateway.application.test.testOnly 'kui.gateway.application.capability.*'
$ ./mill services.gateway.api.test
$ ./mill checkArchitecture
```

Against Compose with three clusters, one unreachable:

```
$ curl -s localhost:8080/api/v1/capabilities | jq -c '.entries[] | {key, status: .state.status}'
{"key":{"service":"cluster","cluster":null},"status":"available"}
{"key":{"service":"cluster","cluster":"prod-eu"},"status":"available"}
{"key":{"service":"cluster","cluster":"staging"},"status":"available"}
{"key":{"service":"cluster","cluster":"dead"},"status":"unavailable"}

$ docker stop kui-cluster && sleep 12 && curl -s localhost:8080/api/v1/capabilities | jq -c '.entries[0]'
{"key":{"service":"cluster","cluster":null},"state":{"status":"unavailable","reason":"UPSTREAM_UNAVAILABLE",...}}
```

The first block is the whole point: the service is `available` while one of its clusters is not.

## Tests required

`PerClusterCapabilitySuite` (MUnit + `munit-cats-effect` + `TestControl`):

- `anUnreachableClusterDoesNotChangeTheServiceCapability` — **the D4 regression test.** A poll
  returns three clusters, one `unavailable`; assert `CapabilityKey(cluster, None)` is
  `Available` and `CapabilityKey(cluster, Some("dead"))` is `Unavailable`.
- `aDeadServiceMakesEveryKeyUnavailable` — the other direction: readiness fails, and the service
  key and every cluster key report unavailable, because a service that cannot answer cannot
  vouch for any cluster.
- `aClusterRemovedFromTheReportIsRetiredAsNotConfigured`.
- `aNewClusterAppearsOnTheNextPollWithNoRestart`.
- `twoClustersFlapIndependently` — cluster A transitions while B holds; assert B's `since` and
  state are untouched and B produced no change event.
- `debounceIsPerKey` — A goes down and recovers inside the debounce window while B goes down and
  stays; only B is published as unavailable.
- `capabilityStreamCarriesPerClusterChanges` — a subscriber receives a `CapabilityChange` whose
  key has `cluster = Some(...)`.

`ReadinessPollerSuite` (existing, updated): every M0 case still passes with the service key;
`summarise` no longer takes the worst — the case that asserted the old behaviour is replaced,
not deleted, by one asserting the new rule, with a comment naming decision 1.

## Observability

`kui.capability.state{service, cluster, status}` — the existing gauge gains a `cluster`
attribute, absent (not empty) for the service key. One INFO per transition, as today, now
including the cluster when there is one. The registry already logs transitions; this task must
not add a second log line in the poller.

## Degraded behaviour

If a service's `/capabilities` cannot be parsed or fails while readiness succeeds, the previous
per-cluster payloads are retained (the existing rule, now applied per key) rather than cleared:
losing them would turn a blip into "this service can do nothing for any cluster", which is a
much stronger claim than the evidence supports. If a service reports no clusters at all, only
the service key exists — a KUI with nothing configured has no per-cluster capability to report,
and that is correct rather than a placeholder.

## Docs to update

`ARCHITECTURE.md` §6: replace "the gateway folds … into `CapabilityState` per `(service,
cluster)`" — which was aspirational in M0 — with a note that the per-cluster half is now real,
and name the rule from decision 1 so nobody re-introduces the fold.
