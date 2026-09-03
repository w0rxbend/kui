# CLDOM-007 — The real `CapabilityReportUseCase`: per cluster, including store health

- **ID:** CLDOM-007
- **Title:** The real `CapabilityReportUseCase`: per cluster, including store health
- **Milestone / Feature:** M1 / CL-007, OT-007, OT-008, OT-009, KU-010
- **Owner role:** Domain Architect (Cluster Registry context)
- **Size:** M
- **Dependencies / blocked by:** CLDOM-005

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

The cluster service stops answering "everything is fine" by construction and starts answering it
from evidence. `GET /capabilities` — which the gateway polls and which decides what the sidebar
dims, what a fallback panel says and what an operator reads first during an incident — reports,
per cluster, whether the cluster is configured, what KUI has established it can do there, and
whether the metadata store behind it all is healthy.

## Scope

1. Replace `CapabilityReportUseCase.constant` with a real implementation over the registry and the
   snapshots.
2. Extend `ClusterCapabilityReport` with the fields a real report needs, and `CapabilityReport`
   with the service-wide store verdict.
3. The mapping rule: which observed condition produces which reported state, and — the load-bearing
   half — which conditions produce *no* change.
4. `docs/domain/cluster.md` gains a "Capabilities" section; the `Ping` sentence is deleted.

## Non-goals

- **No capability *fold*.** `CapabilityFold` is the gateway's pure function over four inputs
  (ADR-039 §1) and lives in `services/gateway/application`. This use case produces input number
  three — the service's own report — and must not attempt to reproduce the precedence, the
  debounce or the sticky `since`. Those are the gateway's, and computing them twice would let the
  two disagree.
- **No HTTP, no DTO.** `ServiceCapabilities` is a `libs/contracts-core` type; rule A3 forbids
  `application` seeing it. This use case returns `CapabilityReport` and `services/cluster/api`
  maps it (the pattern SVC-001 established and deviation 2 records).
- **No probing of its own.** It reads the capability cell CLDOM-005 already maintains. A second
  probe path would double the admin traffic and produce a second answer.
- **No per-*feature* capability entries for other services.** The report describes what the
  cluster service can do; whether the *topic* service can reach the same cluster is that service's
  report, in M2.

## The mapping rule — decided

`ARCHITECTURE.md` §6 and ADR-032 define three reported states plus `NotConfigured`. What the
cluster service reports, per cluster:

| Observed | Reported | Reason |
| --- | --- | --- |
| Cluster is in the registry, topology snapshot is `Fresh` | `Available` | — |
| Cluster is in the registry, snapshot is `Loading` | `Degraded("starting")` | ADR-039 §5: unknown is `Degraded(Starting)`, never `Unavailable`. A service that reports `Unavailable` for its first two seconds makes every deployment look broken at rollout |
| Cluster is in the registry, snapshot is `Stale` or `Unavailable` | **`Available`**, with the cluster's features and `reachable = false` | DEVPLAN §10 D4 and ADR-039 §6. An unreachable *managed* cluster is a section of a healthy response, not a broken capability. Reporting it as `Unavailable` would dim the sidebar for every user because one operator typed a bad broker address |
| Cluster is not in the registry | absent from the map entirely | The gateway renders an id it has no entry for as `NotConfigured` (ADR-039 §2), which is what "you did not set this up" should look like |
| The **metadata store** is `Degraded` | every cluster entry becomes `Degraded(reason)`, and `storeHealth` says so | This one *is* a KUI-side failure: configuration changes cannot be accepted and the profiles being served are last-known. It is the M1 exit criterion "the affected capability reports `Degraded` with a reason" |
| The metadata store is `NotConfigured` | no effect on any cluster entry; `storeHealth = NotConfigured` | The file adapter is a supported way to run (ADR-042); it is not a degradation |

The third row is the decision most likely to be argued with, so it is stated as an invariant with
a test: **no state of any managed Kafka cluster can move this service's reported capability below
`Available`.** Only two things can — the store being degraded, and the process not having started
yet. Everything else about a managed cluster is data on a page.

`reachable` is a new field carrying the third row's nuance to the UI: the cluster capability is
`Available` (the sidebar entry is live, the page loads) while `reachable = false` tells CLUI-003
to render the row's `Unavailable: <reason>` state. Two facts, two fields; collapsing them is what
produces either a dimmed sidebar or a lying dashboard.

## Design references

- ADR-039 in full — especially §2 (precedence), §5 (unknown is `Degraded(Starting)`) and §6
  (business errors must not dim capabilities). This use case is input 3 of the four the fold takes.
- ADR-032 — how each state renders; `NotConfigured` is hidden, not shown as broken.
- ADR-042 — the store's failure modes; "store unreachable means last known state plus `Degraded`"
  in `ARCHITECTURE.md` §9's cluster row.
- ADR-030 — features are probed; the reported feature set is the probe's answer, not a version
  inference.
- ADR-041 A3 — no `libs/contracts-core` in `application`.
- DEVPLAN §10 D4 — the decision this task implements.
- `ARCHITECTURE.md` §6 (the degraded-response envelope) and §4.5 (the capability registry the
  gateway keeps).

## Files to create or change

```
services/cluster/application/src/kui/cluster/application/CapabilityReportUseCase.scala   (rewritten)
services/cluster/application/test/src/kui/cluster/application/CapabilityReportUseCaseSuite.scala (new)
docs/domain/cluster.md                                                                   (changed)
```

The M0 `CapabilityReportUseCase.constant` is **deleted**, not deprecated. It is referenced by
`services/cluster/app/ClusterWiring.scala` and `services/cluster/api` — both in the `CLAPI-` area.
Coordinate with CLAPI-005 (the wiring task) so the two land together, exactly as CLDOM-001 does for
`Ping`: this task's commit changes the signature, and CLAPI-005's commit changes the caller. If
CLAPI-005 has not landed, **keep `constant` compiling as a deprecated-in-comment alternative
constructor for one commit** and delete it in a follow-up — a red `main` is worse than one extra
constructor for a day. State which happened in the Implementation Report.

## Public Scala signatures to implement

```scala
package kui.cluster.application

import java.time.Instant
import cats.effect.kernel.Temporal
import kui.cluster.domain.{ClusterFeature, StoreHealth}
import kui.kernel.ClusterId

/** What this service reports about one cluster.
  *
  * Replaces the M0 shape `(configured, features: Set[String], available: Boolean)`. Three of the
  * four fields survive with the same meaning; `available` splits into `state` and `reachable`
  * because one boolean cannot say both "the sidebar entry works" and "the broker answered".
  */
final case class ClusterCapabilityReport(
    /** Always `true` for an entry that is present at all — an absent entry *is* "not configured".
      * Kept as an explicit field because the wire DTO has it and the gateway's fold reads it. */
    configured: Boolean,
    /** The health of *this service's* ability to serve this cluster. See the mapping table. */
    state: CapabilityState,
    /** Whether the last topology refresh against the cluster's brokers succeeded. `false` does not
      * make `state` anything other than `Available` (DEVPLAN §10 D4). */
    reachable: Boolean,
    /** `ClusterFeature.token` values — the probed capability set. Empty for a cluster that has
      * never been reached, which reads correctly as "KUI knows of nothing it can do here yet". */
    features: Set[String],
    /** When the topology snapshot was last successfully refreshed. `None` before the first
      * success. The UI shows it; an operator uses it to decide whether a page is worth reading. */
    scrapedAt: Option[Instant]
)

/** The state this service reports for one cluster. Deliberately a *subset* of the gateway's
  * `CapabilityState`: this service can say `Available` or `Degraded`, and never `Unavailable`,
  * because a service that is answering the capability request at all is by definition reachable —
  * `Unavailable` is the gateway's verdict when it gets no answer, and a self-reported
  * `Unavailable` would be a service claiming it is not there.
  */
enum CapabilityState:
  case Available
  case Degraded(reason: String)

object CapabilityState:
  given CanEqual[CapabilityState, CapabilityState] = CanEqual.derived

/** Everything this service currently reports about itself. */
final case class CapabilityReport(
    clusters: Map[ClusterId, ClusterCapabilityReport],
    /** The metadata store's state, reported once rather than per cluster, because it is one fact
      * about the deployment. The per-cluster `state` already reflects its consequence. */
    storeHealth: StoreHealth
)

trait CapabilityReportUseCase[F[_]]:
  def report: F[CapabilityReport]

object CapabilityReportUseCase:
  val Operation: String = "kui.cluster.capabilities"

  def make[F[_]: Temporal](
      registry: ClusterRegistry[F],
      snapshots: ClusterSnapshots[F]
  ): CapabilityReportUseCase[F]

  /** The mapping table as a pure, total function of the three observations. Public so the table is
    * asserted directly, one case per row, which is what ADR-039 requires of the gateway's fold and
    * is just as right here. */
  def stateOf(
      freshness: SnapshotFreshness,
      storeHealth: StoreHealth
  ): (CapabilityState, Boolean)   // (state, reachable)
```

`stateOf` in full — this is the implementation, not a sketch:

| `freshness` | `storeHealth` | result |
| --- | --- | --- |
| any | `Degraded(reason, _)` | `(Degraded(s"configuration store: $reason"), reachableOf(freshness))` |
| `Loading` | `Online` or `NotConfigured` | `(Degraded("starting"), false)` |
| `Fresh(_)` | `Online` or `NotConfigured` | `(Available, true)` |
| `Stale(_, reason, _)` | `Online` or `NotConfigured` | `(Available, false)` |
| `Unavailable(reason, _)` | `Online` or `NotConfigured` | `(Available, false)` |

where `reachableOf` is `true` only for `Fresh`. The store row is first because it takes precedence:
a degraded store degrades every cluster entry whatever the clusters themselves are doing.

`report` never fails and never blocks: it reads the registry `Ref` and one `SnapshotCell` per
cluster, all in memory. That is required, not incidental — the gateway polls this endpoint every
ten seconds (ADR-004) and a capability endpoint that can hang is a capability registry that
reports the wrong thing about a service that is fine.

## Library coordinates

Unchanged: `services.cluster.application` = `moduleDeps Seq(domain, libs.cache)`, cats-core
2.13.0, cats-effect 3.7.1, fs2-core 3.13.0, log4cats-core 2.8.0, otel4s-core 1.1.0. Test module as
in CLDOM-005.

No dependency on `libs.contractsCore` is added, in either module. `checkArchitecture` rule A3
fails the build if one appears, which is exactly the guard SVC-001 built this shape to get.

## Acceptance criteria

```
$ ./mill services.cluster.application.test
Test run kui.cluster.application.CapabilityReportUseCaseSuite finished: 0 failed, 0 ignored, 13 total
SUCCESS

$ ./mill services.cluster.application.compile
$ ./mill checkArchitecture
checkArchitecture: <n> modules, no layering violations
```

Whole-service green before the commit lands (CLAPI-005 coordination above):

```
$ ./mill services.cluster.__.compile
```

## Tests required

`CapabilityReportUseCaseSuite` (MUnit + `munit-cats-effect`, over `FakeClusterConfigStore` and
`FakeClusterAdmin`). The first five assert the pure `stateOf` table, one per row:

1. `freshIsAvailableAndReachable`.
2. `loadingIsDegradedStartingAndNotReachable` — assert the reason string is `"starting"`, because
   ADR-032's browser rule matches on it.
3. `staleIsAvailableAndNotReachable` — **the D4 test.** A named comment in the suite says so.
4. `unavailableIsAvailableAndNotReachable` — same, for the never-reached cluster.
5. `aDegradedStoreDegradesEveryClusterWhateverTheClustersAreDoing` — `Fresh` topology plus
   `StoreHealth.Degraded` gives `Degraded`, and the reason names the store.

Effectful:

6. `notConfiguredStoreDoesNotDegradeAnything` — `StoreHealth.NotConfigured` with fresh clusters
   gives every entry `Available`, and `report.storeHealth` is `NotConfigured`. The file-adapter
   exit criterion.
7. `anUnconfiguredClusterIsAbsentFromTheMap` — asking about an id not in the registry: no entry,
   not an entry with `configured = false`.
8. `everyConfiguredClusterHasAnEntry` — three configured clusters, three entries, whatever their
   states.
9. `featuresAreTheProbedSetAsTokens` — the set equals `ClusterFeature.token` values from the
   capability cell; a never-reached cluster has an empty set.
10. `scrapedAtIsTheLastSuccessAndNotTheLastAttempt` — refresh successfully, then fail; `scrapedAt`
    is unchanged.
11. `reportDoesNotCallTheAdminPort` — under `TestControl` with a one-hour-delay fake, `report`
    completes at virtual time zero. The gateway polls this every ten seconds; an admin call here
    would be a broker call per cluster per ten seconds per gateway replica.
12. `noManagedClusterConditionCanProduceAnythingBelowAvailable` (property) — generate arbitrary
    `SnapshotFreshness` values with `storeHealth` in `{Online, NotConfigured}`; assert the state is
    `Available` unless the freshness is `Loading`. This is the invariant of the mapping table
    stated once, so that a future edit to `stateOf` that "helpfully" reports an unreachable cluster
    as degraded fails a test with a name that explains why it is wrong.
13. `reportCarriesNoSecretAndNoBootstrapString` (property) — build the report over profiles whose
    secrets are the canary token; assert `report.toString` does not contain it. The capability
    response is public to every authenticated user and is the least-reviewed response body in the
    product.

## Observability

| Signal | Name | Attributes |
| --- | --- | --- |
| Gauge | `kui.cluster.capability.reachable` | `cluster.id`; 1 or 0 |
| Gauge | `kui.cluster.store.health` | `0 = Online, 1 = Degraded, 2 = NotConfigured` |
| Log | store health *transition* only, INFO on recovery, WARN on degradation | `store.health`, `reason` |

`report` itself logs nothing per call. It is polled every ten seconds by every gateway replica; a
log line per call is a log volume proportional to the number of gateway replicas and nothing else.
The endpoint's own span comes from `libs/http` (OBS-002); this use case adds no span of its own.

## Degraded behavior

| Condition | `report` |
| --- | --- |
| Everything healthy | every cluster `Available`, `reachable = true`, `storeHealth = Online` |
| One managed cluster down | that entry `Available`, `reachable = false`, `features` from the last probe, `scrapedAt` from the last success. **The sidebar does not dim.** |
| Every managed cluster down | every entry `Available`, `reachable = false`. Still no dimming: the cluster *service* is fine and its pages render with `Unavailable` sections |
| Store cluster stopped | every entry `Degraded("configuration store: …")`, `storeHealth = Degraded`. The M1 exit criterion |
| No store configured | unaffected; `storeHealth = NotConfigured` |
| Process starting | every entry `Degraded("starting")` until the first refresh completes; the gateway renders it as "starting", not as an outage (ADR-039 §5) |
| No clusters configured | `clusters` is empty; `storeHealth` still reported. The gateway shows the cluster capability as configured-with-nothing, which is what an empty first install is |
| **The cluster service itself is stopped** | there is no report. The gateway's readiness poll fails and its fold produces `Unavailable` after the debounce (ADR-039 §4). Nothing in this file participates; that is the correct division and it is why this use case has no `Unavailable` case to return |

## Docs to update

`docs/domain/cluster.md`:

- A "Capabilities" section carrying the mapping table verbatim, the `state` / `reachable` split
  and its one-sentence justification, and the note that `Unavailable` is the gateway's verdict and
  never this service's.
- **Delete the `Ping` sentence** left by CLDOM-001. By the time this task lands, CLAPI-002 has
  removed the type; if it has not, leave the sentence and delete it in CLAPI-002's commit — and
  say which in the Implementation Report.
- Confirm the page no longer says "scaffolded, not modelled" anywhere. DEVPLAN §9 item 9 makes
  that a milestone completion criterion, and this is the last CLDOM task, so it is the one that
  checks.
