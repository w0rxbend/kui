# CLADP-005 — Profile change propagation: store tail → registry reload → version bump

- **ID:** CLADP-005
- **Title:** Profile change propagation: store tail → registry reload → version bump
- **Milestone / Feature:** M1 / OT-004, CL-007, KU-011, KU-012
- **Owner role:** Domain Architect (Cluster Registry context)
- **Size:** M
- **Dependencies / blocked by:** CLADP-003, CLDOM-004 (`ClusterRegistry`), and — for the
  underlying store change feed and health that CLADP-003's `changes`/`health` are built on —
  STORE-008

> **Note on the declared dependencies.** DEVPLAN §6.2 lists CLADP-003 and CLDOM-004. Both of
> those rest on `ConfigStore`'s change feed and health, which STORE-008 introduces (STORE-008
> depends on STORE-007, which CLADP-003 already depends on, so the ordering is consistent and the
> critical path does not lengthen). It is stated here so that a worker who finds
> `ClusterConfigStoreAdapter.changes` returning an empty stream knows which task to wait for
> rather than inventing a poll loop.

## M1 gate review amendment — `ClusterConfigStore.changes` is now `onChange`

**F-02, blocker, fixed.** Rule A1 was **not** widened to allow `co.fs2::fs2-core` in
`services/cluster/domain` (see [ADR-041 Amendment 3](../../../adr/ADR-041-layering-rules-machine-enforced.md)),
so `ClusterConfigStore` has no `changes: Stream[F, List[ClusterProfile]]`. It has
`onChange(handler: List[ClusterProfile] => F[Unit]): F[F[Unit]]`, returning the deregistration
action. Wherever this spec subscribes to `changes`, register a handler instead; wherever it
consumes a stream, the stream now lives on `ClusterRegistry` in `application`, which may hold
fs2. Nothing else about the behaviour, the backoff or the reconcile logic changes.

## Goal (user value)

A cluster registered or edited on one replica becomes visible on every replica, without a
restart, in under a second — and the version number that the other nine Kafka-facing services
subscribe to goes up, so their `ClusterProfile` clients rebuild their Kafka clients (ADR-036).

This is the last piece of the milestone's critical path before the service can be wired
(CLAPI-005). It is also the piece with the most ways to be subtly wrong: a reload loop that
reloads on its own writes, a stream that dies silently on the first decode failure, or a version
that goes backwards after a store outage.

## Scope

1. **`ProfileChangeListener`** — a `Resource`-scoped background process that:
   - subscribes to `ClusterConfigStore[F].changes` — CLDOM-003's
     `fs2.Stream[F, List[ClusterProfile]]`, which emits the current full list once on subscribe
     and then one full list per store change. The `cluster/`-prefix filtering and the decoding
     already happened in CLADP-003; this listener never sees a `settings/` or `rbac/` key and must
     not reimplement that filter;
   - **diffs each emitted list against the registry's current state** and turns the difference
     into upserts and removals. Whole-list emission is what makes this component simple and
     idempotent: there is no delta protocol to get wrong, and a missed element is impossible
     because the next emission is complete. A cluster present in the previous list and absent from
     the new one, and which the registry holds from the store rather than from static
     configuration, is removed;
   - bumps the cluster's `ProfileVersion` and emits the change on an internal
     `Topic[F, ProfileChanged]` that CLAPI-003's `/internal/v1/clusters/stream` SSE endpoint and
     CLAPI-005's wiring subscribe to;
   - runs under a `Supervisor`, restarts on failure with capped exponential backoff (1 s, doubling,
     capped at 30 s, jittered), and **never terminates the service**. A store outage must degrade,
     not kill — ADR-042 §8.
2. **Idempotence and self-writes.** The stream carries back the service's *own* writes; that is
   the read-your-writes mechanism of ADR-042 §3, and it is a feature. The listener must therefore
   be idempotent rather than filtered: a profile whose `(clusterId, ProfileVersion)` the registry
   already holds is a no-op — no registry call, no version bump, no emitted event. Suppressing
   self-writes by writer identity instead would break the multi-replica case, because replica B's
   write looks exactly like a foreign write to replica A and exactly like a self-write to replica
   B, and only one of them may be ignored. The first emission after subscribe is, by this rule,
   almost entirely a no-op — which is exactly what it should be, since the initial replay already
   populated the registry (see scope item 6 and CLAPI-005's bootstrap ordering).
3. **Monotonic versions.** `ProfileVersion` for a cluster never decreases, including across a
   store reconnect that replays records the process has already seen. A version that goes
   backwards makes every downstream service's ETag comparison wrong in the silent direction —
   they conclude nothing changed and keep talking to the old brokers with the old credentials.
   Implement as `max(current, incoming)` at the registry boundary and assert it.
4. **Ordering.** Emissions are applied one at a time, in the order the stream delivers them —
   `evalMap`, never `parEvalMap`. `__kui_config` has one partition precisely so that total order
   is available (ADR-042 §2–§3); processing it concurrently throws that away for no gain, since
   metadata writes are rare.
5. **Gap recovery is free, and that is the point.** When the stream fails and is resubscribed,
   its first emission is the current full list, so the reconciliation of scope item 1 recovers
   anything missed during the outage — including a removal, which a delta protocol would have lost
   forever on that replica. The listener therefore needs **no** separate `list` call on restart;
   if it appears to, the stream is not honouring CLDOM-003's "current list once on subscribe"
   contract and that is a CLADP-003 bug, not something to work around here. Statically configured
   clusters are never removed by the reconciliation — they do not come from the store.
6. **Health transitions.** The listener owns the "store went away / came back" signal: it writes
   the current `StoreHealth` into the value CLDOM-007's capability report reads, and logs the
   transition exactly once in each direction.

## Non-goals

- **No HTTP and no SSE encoding.** `/internal/v1/clusters/stream`, its named events and its
  envelope are CLAPI-003 (ADR-035). This task publishes to an in-process `Topic`; the endpoint
  subscribes to it.
- **No registry semantics.** The precedence of static configuration versus store records is
  CLDOM-004's, in `application`. This listener calls the registry; it does not decide what the
  registry does with the record.
- **No polling fallback.** ADR-036's 60-second poll is what the *other nine services* do against
  the cluster service's HTTP endpoint. The cluster service reads the log directly and has no
  reason to poll it; the resubscribe of scope item 5 covers the same failure.
- **No write path.** Writing a profile is CLADP-003 plus CLAPI-009.
- **No file-adapter watching.** With the file adapter the store is read once at startup and
  `changes` is empty (ADR-042 §7). Do not add an inotify watcher; nothing in M1's exit criteria
  asks for one, and CFGOP-006's development environment has a broker.

## Design references

- ADR-042 §3 (total order, optimistic version, read-your-writes), §6 (the log is the change
  notification; the cluster and identity services read `__kui_config` directly; the gateway never
  does), §8 (unreachable store: last known state, `Degraded`, reject writes).
- ADR-036 (distribution: `ClusterProfile` per cluster with a monotonically increasing `version`,
  ETag at `/internal/v1/clusters/{id}/profile`, SSE at `/internal/v1/clusters/stream`; subscribers
  rebuild clients when the version changes, no restart).
- ADR-035 (streaming envelope — the shape CLAPI-003 wraps this task's events in).
- ADR-039 (capability fold; `Degraded` with a reason and sticky `since`).
- ADR-002 (cats-effect: `Supervisor`, `Topic`, `Resource` — a background fiber that is not
  supervised is a fiber that dies unobserved).
- DEVPLAN §10 decision D6 (there is exactly one writer surface in M1) and the exit criteria
  "two replicas … both converge on the winner's record" and "store cluster stopped mid-run:
  clusters keep resolving from last known state".

## Files to create

```
services/cluster/infrastructure/src/kui/cluster/infrastructure/store/ProfileChangeListener.scala
services/cluster/infrastructure/src/kui/cluster/infrastructure/store/ProfileChanged.scala
services/cluster/infrastructure/src/kui/cluster/infrastructure/store/ClusterRegistryWriter.scala
  (only if CLDOM-004 has not already declared this port in `domain` — see the layering note below)
services/cluster/infrastructure/test/src/kui/cluster/infrastructure/store/ProfileChangeListenerSuite.scala
```

## Files to change

```
services/cluster/infrastructure/test/src/kui/cluster/infrastructure/store/StubConfigStore.scala
  (a `changes` stream the test can push profile lists and failures onto, and a
   `kui.cluster.domain.ClusterConfigStore[IO]` façade over it)
```

## Public Scala signatures to implement

```scala
package kui.cluster.infrastructure.store

import java.time.Instant

import kui.cluster.domain.ClusterProfile
import kui.kernel.ClusterId

/** What changed, for whoever is subscribed. Deliberately small: an SSE event that carries a whole
  * profile would carry secrets to a subscriber that is only entitled to a redacted one, and
  * CLAPI-003 redacts on the way out precisely because this type stays internal.
  */
enum ProfileChanged {
  case Upserted(id: ClusterId, version: kui.cluster.domain.ProfileVersion, at: Instant)
  case Removed(id: ClusterId, at: Instant)
}
```

```scala
package kui.cluster.infrastructure.store

import cats.effect.kernel.{Async, Resource}
import cats.effect.std.Supervisor
import fs2.concurrent.Topic

/** Keeps the in-memory cluster registry equal to the `cluster/` section of the metadata store,
  * and tells everyone else when it changed.
  *
  * Started once, at startup, *after* the initial replay has completed (ADR-042 §1 fixes the
  * bootstrap order: static config → store client → replay `__kui_config` to end → managed
  * clusters known → Ready). CLAPI-005 owns that ordering; this component's contract is that it is
  * safe to start after it and pointless to start before it.
  */
trait ProfileChangeListener[F[_]] {

  /** Every change this process has applied, since subscription. Bounded; a slow subscriber is
    * dropped rather than allowed to stall the tail.
    */
  def changes: Topic[F, ProfileChanged]

  /** The store's health as this listener last observed it, for the capability fold. */
  def storeHealth: F[kui.cluster.domain.StoreHealth]
}

object ProfileChangeListener {

  /** Allocates the listener and starts its supervised fiber. Releasing the resource stops it. */
  def resource[F[_]: Async](
      store: kui.cluster.domain.ClusterConfigStore[F],
      registry: ClusterRegistryWriter[F],
      supervisor: Supervisor[F],
      logger: org.typelevel.log4cats.StructuredLogger[F],
      telemetry: kui.observability.Telemetry[F]
  ): Resource[F, ProfileChangeListener[F]]

  /** 1 s, doubling, capped at 30 s, with full jitter. Named so the test can assert it and the
    * operator documentation can quote it.
    */
  val InitialBackoff: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.DurationInt(1).second
  val MaxBackoff: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.DurationInt(30).seconds
}
```

**A layering note that matters.** `ClusterRegistry` lives in `services/cluster/application`.
`infrastructure` depending on `application` would invert the dependency rule. Two shapes are
legal and only one is right:

- **Wrong**: `infrastructure → application`.
- **Right**: the registry's *mutation* surface is a port declared in `domain` (CLDOM-003/004 —
  something like `ClusterRegistryWriter[F]` with `upsert`, `remove` and `currentVersion`), the
  `application` class implements it, and this listener depends on the port. `app` (CLAPI-005)
  wires the two together.

If CLDOM-004 has not exposed such a port, **do not add an `application` dependency to
`build.mill`**. Take the second shape: declare the small port this listener needs in
`services/cluster/infrastructure` as a `trait` of its own with the three methods above and let
CLAPI-005's wiring adapt the registry to it in five lines. That keeps the arrow pointing inward
and keeps this task unblocked. Record which shape you used in the implementation report so
CLDOM-004 can absorb the port if it is the better home.

## Library coordinates

No new coordinate. `fs2.concurrent.Topic` and `cats.effect.std.Supervisor` come from
`co.fs2::fs2-core::${Versions.fs2}` (3.13.0) and
`org.typelevel::cats-effect::${Versions.catsEffect}` (3.7.1), both already on the module.
`TestControl` for the backoff assertions comes from the test classpath as in CLADP-004.

## Acceptance criteria

```
$ ./mill services.cluster.infrastructure.test
Test run kui.cluster.infrastructure.store.ProfileChangeListenerSuite finished: 0 failed, 0 ignored, 10 total
SUCCESS

$ ./mill checkArchitecture
checkArchitecture: 36 modules, no layering violations
```

Every assertion below runs under `TestControl` with a stub store — no broker. The behaviour
against a real broker is exercised end to end by CFGOP-006 and CFGOP-007, and by STORE-009 on the
store's own side; duplicating a Testcontainers suite here would triple the runtime of the module
for no new coverage.

## Tests required

`ProfileChangeListenerSuite`:

- `aNewProfileInTheListReachesTheRegistryAndEmitsUpserted`
- `aProfileThatDisappearsFromTheListIsRemovedAndEmitsRemoved`
- `reemittingTheSameVersionIsANoOpAndEmitsNothing` — the read-your-writes case. Push the same
  list twice; one event, one registry call.
- `theFirstEmissionAfterSubscribeIsMostlyANoOp` — the registry was already populated by the
  initial replay; subscribing produces no spurious burst of events.
- `theVersionNeverGoesBackwards` — emit version 5 then version 3 (the replay-after-resubscribe
  case); the registry's version stays 5 and no event is emitted for the older profile.
- `emissionsAreAppliedOneAtATimeInOrder` — a slow registry; the second emission is not applied
  concurrently with the first.
- `aFailedStreamIsResubscribedWithCappedBackoff` — fail the stream three times; under
  `TestControl` the resubscribes happen at 1 s, 2 s, 4 s (± jitter bound) and never beyond
  `MaxBackoff`.
- `aResubscribeReconcilesARemovalThatHappenedDuringTheOutage` — while the stream was down, a
  cluster was deleted from the store; the first emission after resubscribe removes it from the
  registry, with no explicit `list` call.
- `aResubscribeDoesNotRemoveStaticallyConfiguredClusters` — the reconciliation touches only
  store-sourced entries.
- `storeHealthGoesDegradedOnFailureAndBackToAvailableOnRecovery` — and each transition was logged
  exactly once.

## Observability

- **Metric**: `MetricNames.ConfigVersion` (`kui.config.version`, `{section}`) updated to the new
  version on every applied change, `section = "cluster/<clusterId>"`. Two replicas converging
  after a version conflict is an exit criterion, and this gauge is how an operator sees it happen.
- **Metric**: `MetricNames.StreamEvents` (`kui.stream.events`, `{service, stream, event}`) with
  `stream = "cluster.profiles"` and `event` = `upserted` / `removed` / `ignored` / `skipped`.
- **Span**: `kui.cluster.store.apply-change`, one per applied record, with `kui.cluster.id`,
  `kui.store.version` and the outcome.
- **Log, INFO, once per applied change**: `cluster profile changed`, with the cluster id, the new
  version and whether it was an upsert or a removal. Never the profile.
- **Log, WARN, once per transition**: `metadata store unreachable, serving last known clusters`
  and, on recovery, `metadata store reachable again, N clusters reconciled`. Once per transition,
  not once per retry — a retry loop that logs every attempt at WARN produces a hundred lines a
  minute during an outage and buries the one line that says it recovered.
- **Log, ERROR**: undecodable records are logged by CLADP-003, where the decoding happens. This
  file does not log them a second time; one bad record producing two ERROR lines in two modules is
  how a log stops being countable.

## Degraded behavior

This task *is* the milestone's "store cluster stopped mid-run" exit criterion, so its degraded
contract is the acceptance contract:

- **Store unreachable.** The listener retries with capped backoff, forever, without terminating
  the service. The registry keeps serving its last known state, so every configured cluster keeps
  resolving and every cluster screen keeps working. `storeHealth` reports `Degraded(reason)`,
  which CLDOM-007 folds into a degraded envelope (ADR-039), and CLADP-003 rejects writes. Nothing
  is queued and nothing is lost, because nothing is accepted.
- **Store recovers.** Full re-read, reconcile, resume the tail, log the recovery once, health
  returns to available. Versions may jump; they never go backwards.
- **A single bad record.** Already skipped, logged and counted by CLADP-003 before it reaches
  this listener; the emitted list simply has one fewer profile in it, and the reconciliation must
  **not** treat that absence as a removal. Distinguish the two with the decode-failure count in
  `health`: while it is non-zero, removals are not applied. Deleting a cluster from every replica
  because one record briefly failed to decode is the worst outcome available in this file.
- **A slow subscriber** to `changes` is dropped rather than allowed to apply backpressure to the
  tail. The tail feeding the registry is the thing that must not stall; an SSE client that cannot
  keep up reconnects and gets the current state from
  `/internal/v1/clusters/{id}/profile` (ADR-036's ETag path).
- **Startup.** The listener is started after the initial replay, never before. If it is started
  before, the reconciliation in scope item 5 makes it correct anyway — but it would emit a burst
  of events for clusters nobody had asked about yet, so CLAPI-005 orders it correctly and this
  file's documentation says so.

## Docs to update

None in this task. The backoff numbers, the transition log lines and the "last known state"
guarantee are quoted by CFGOP-008 into `docs/operations/metadata-store.md`; put the final values
in the implementation report.

## Cancellation and shutdown (added at the M1 gate review, F-07)

The M0 review found cancellation systematically unconsidered across the milestone. This task
owns a long-lived listener fiber, so it owns the answer here. State it in the spec's own words in the
Implementation Report, and ship the tests below.

- The listener runs under a `Supervisor` owned by its `Resource`; releasing the resource cancels
  the fiber, deregisters the `onChange` handler, and completes without waiting for the next
  change.
- Cancellation in the middle of a reconcile leaves the registry at its previous consistent
  value, never half-applied: the swap into the registry's `Ref` is a single `uncancelable`
  update of a fully-built snapshot.
- **Test:** cancel mid-reconcile and assert the registry still returns the previous snapshot in
  full and that the handler is deregistered.
