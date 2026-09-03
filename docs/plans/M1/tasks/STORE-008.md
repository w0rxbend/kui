# STORE-008 — `StoreHealth`, the `changes` stream and the unreachable-store contract

- **ID:** STORE-008
- **Title:** `StoreHealth`, the `changes` stream and the unreachable-store contract
- **Milestone / Feature:** M1 / OT-004, ADR-042 §8, ADR-039
- **Owner role:** Principal Scala Engineer
- **Context / service:** `libs/config`, package `kui.config.store`
- **Size:** M
- **Dependencies / blocked by:** STORE-007

## Goal (user value)

The store cluster goes down while KUI is running. Nothing that a user is looking at breaks: every
cluster still resolves, every page still renders, and a banner says the store is degraded and why.
Writes are refused rather than lost. When the store comes back, KUI catches up on its own with no
restart. That is the milestone's last exit criterion, and it is the difference between "KUI needs
Kafka" and "KUI needs Kafka to be up at all times".

## Scope

1. The `StoreHealth` lifecycle: `Healthy` ⇄ `Degraded(reason, since)`, with the transitions
   driven by the tail follower, not by a probe.
2. Reconnection: the follower retries with bounded backoff and resumes from its last applied
   offset, forever, without restarting the process.
3. The observability surface: every metric STORE-002/005/006/007 named, registered once here.
4. The `changes` stream's guarantees under a reconnect — what a subscriber is promised.
5. Adding the store codes to `KuiError.InfrastructureCodes` so the capability fold can see them.

## Non-goals

**No capability report.** CLDOM-007 folds store health into the cluster service's capability
report and CLAPI-008 puts it in the registry; this task only *produces* the health value.
**No health HTTP endpoint** — `libs/config` has no HTTP. **No `SnapshotCell`**: that is KAFKA-010's
primitive for cluster topology, and the store's state is not a snapshot of an upstream, it is a
replicated log projection; giving it a TTL would be wrong. **No re-replay on reconnect**: the
follower resumes from its offset. A full re-replay would be correct and enormously wasteful, and
the offset is durable in the process's own memory, which is exactly as durable as the state it
indexes.

## Design references

ADR-042 §8 (the four-row failure table), `docs/operations/metadata-store.md` §6 (the same table,
operator-facing, plus the metric names — those names are a published contract and this task must
implement them exactly).
ADR-039 §6 (only `InfrastructureError` dims a capability; the fold's inputs and its asymmetric
debounce), ADR-027 (`status` + `scrapedAt` shape, which `StoreHealth` deliberately mirrors),
ADR-016 (staleness contract), ADR-037 (backoff and breaker policy — reuse its shape, do not
invent a second one).
DEVPLAN §7 fault-injection scenario 3, §8 R-2.

## Files to change

```
libs/config/src/kui/config/store/KafkaConfigStore.scala     (the follower's supervision)
libs/config/src/kui/config/store/StoreHealth.scala          (transitions, since, unreadableKeys)
libs/config/src/kui/config/store/StoreMetrics.scala          (new)
libs/kernel/src/kui/kernel/error/KuiError.scala              (InfrastructureCodes)
libs/config/test/src/kui/config/store/StoreHealthSuite.scala  (new)
```

## Public Scala signatures to implement

```scala
package kui.config.store

/** The follower's supervision policy. One place, so a change is one change. */
final case class StoreRetryPolicy(
    initialDelay: FiniteDuration,   // 1s
    maxDelay: FiniteDuration,       // 30s
    jitter: Double                  // 0.2
)

object StoreRetryPolicy:
  val Default: StoreRetryPolicy

/** Owns the health value and the transitions. Separate from `StoreState` because state is what
  * the log said and health is how well we are keeping up with it — two things that change for
  * different reasons and are read by different callers. */
final class StoreHealthRef[F[_]] private (ref: Ref[F, StoreHealth]):
  def get: F[StoreHealth]
  /** Called after each successfully applied batch. Clears a `Degraded` and resets `since`. */
  def markHealthy(lastAppliedOffset: Long): F[Unit]
  /** Called when the follower's stream fails. Idempotent: a second failure while already
    * degraded keeps the original `since`, so "degraded for 20 minutes" is true rather than
    * being reset by every retry. */
  def markDegraded(reason: String): F[Unit]
  def markUnreadable(key: StoreKey, why: String): F[Unit]

object StoreMetrics:
  /** Registers every store metric against the otel4s meter `libs/observability` provides.
    * Named exactly as `docs/operations/metadata-store.md` §6 promises. */
  def register[F[_]: Async](meter: Meter[F], health: StoreHealthRef[F]): Resource[F, StoreMetrics[F]]
```

## The health lifecycle, decided precisely

| From | Event | To | Notes |
| --- | --- | --- | --- |
| (start) | replay succeeded | `Healthy(offset, now)` | the only way to enter a live store; a store that never replayed does not exist as a value |
| `Healthy` | follower stream fails | `Degraded(classify(e), now, offset)` | `since` is set now |
| `Degraded` | retry fails again | `Degraded` unchanged | **`since` is not reset** — the sticky-`since` rule of ADR-039 |
| `Degraded` | retry succeeds and the first batch applies | `Healthy(offset, now)` | no debounce on recovery for *reads*; see below |
| any | a record is unreadable | unchanged + `unreadableKeys` | one bad record is not a degraded store |

**Debounce.** ADR-039's fold applies an asymmetric debounce (fast to degrade, slow to recover) to
*capabilities*, and that is where it belongs — CLDOM-007 applies it. `StoreHealth` itself is
undebounced and reports what is true right now, because a debounced health value would make the
integration test in STORE-009 depend on wall-clock timing and would give the fold a
already-smoothed input to smooth again. Stated here because a reviewer who knows ADR-039 will
otherwise ask.

**`reason` is a classification, not an exception message**: `"connection refused"`,
`"authentication failed"`, `"not authorized"`, `"topic deleted"`, `"unknown"`. It reaches a user
through the capability banner, so it must be short, stable and free of hosts, ports and
credentials (ADR-034's rule about `message` being display text).

**Reconnect.** The follower runs under a `Supervisor`; on failure it waits
`min(initialDelay * 2^n, maxDelay)` with ±20% jitter, rebuilds the consumer (a consumer whose
connection failed is not reliably reusable), assigns partition 0, seeks to
`lastAppliedOffset + 1`, and resumes. It never gives up: an operator restarting a broker for
twenty minutes must not have to restart KUI too. Every attempt logs at WARN with the attempt
number and the delay; the first success logs INFO with the outage duration.

## `changes` under a reconnect, decided here

A subscriber to `changes` is promised: **every change this process applies, in log order, at
least once, with no gaps while the subscriber keeps up.** It is *not* promised changes that
happened while the store was degraded and this process was not consuming — those arrive as a
burst when the follower catches up, which is the same thing arriving late, and is why the
promise is worded as "every change this process applies".

A subscriber that falls more than 256 changes behind (STORE-006's buffer) loses the oldest and
**must** re-read from the store rather than assume its view is complete. To make that possible
rather than theoretical, `StoreChange` consumers get one extra signal:

```scala
enum StoreChange:
  case Upserted(record: StoreRecord)
  case Deleted(key: StoreKey, version: Long, at: Instant)
  /** This subscriber missed changes and its view is incomplete; re-read. */
  case Desynchronized(missed: Long)
```

CLADP-005 (profile propagation) handles `Desynchronized` by reloading the whole registry, which
is a handful of records and is the honest response.

## Library coordinates

None new. Metrics go through `libs/observability`'s otel4s `Meter` (`org.typelevel::otel4s-*
1.1.0`), which `libs/config` reaches **through a passed-in `Meter[F]`, not a module dependency** —
`libs/config` must not gain an edge to `libs/observability`, because `libs/observability` is a
JVM-only module with an OpenTelemetry SDK on it and `libs/config` is depended on by everything.
`StoreMetrics.register` therefore takes the meter as a parameter and the composition root
(CLAPI-005) supplies it. If a worker finds `Meter[F]` is not reachable without that edge, the
fallback is to expose a `StoreStats` case class from the store and have
`services/cluster/infrastructure` register the gauges; record which of the two was used in the
Implementation Report.

## Acceptance criteria

```
$ ./mill libs.config.compile
$ ./mill libs.config.test
$ ./mill libs.config.test.testOnly kui.config.store.StoreHealthSuite
$ ./mill libs.kernel.jvm.test && ./mill libs.kernel.js.test
$ ./mill __.checkFormat && ./mill __.fix --check && ./mill checkArchitecture
```

`libs.kernel` is re-run because `InfrastructureCodes` changes and KERN-008's suite asserts its
contents.

## Tests required

- `StoreHealthSuite` (unit, `munit-cats-effect` + `TestControl`):
  - `degradedKeepsItsOriginalSince` — degrade, wait, degrade again; `since` is the first one.
  - `recoveryResetsSince`.
  - `unreadableKeyDoesNotDegrade`.
  - `unreadableKeyIsClearedWhenTheKeyIsWrittenSuccessfully` — an operator fixing a record must
    not have to restart to clear the warning.
  - `reasonIsAClassificationNotAnExceptionMessage` — table over the exception types
    `research/kafka/admin-capabilities.md` §0 lists for a consumer, asserting the mapped string
    and asserting that no mapped string contains a host, a port or the word "Exception".
  - `backoffIsBoundedAndJittered` (property): for any attempt number the delay is within
    `[0.8, 1.2] × min(initial × 2^n, max)` and never exceeds `maxDelay × 1.2`.
  - `writesAreRejectedWhileDegraded` — `put` gives `KUI-STORE-UNAVAILABLE` (STORE-007's rule,
    asserted here because this is the task that can make a store degraded without a broker).
- `KuiErrorSuite` addition (`libs/kernel`): `storeCodesAreInfrastructureCodes` —
  `StoreUnavailable` and `StoreReplayTimeout` are in `InfrastructureCodes`;
  `StoreNotConfigured` and `ConfigVersionConflict` are **not**, with the ADR-039 §6 reason in the
  test's comment (a not-configured store is a deployment choice and a version conflict is a
  user's stale form; neither may dim a capability for everyone).
- The reconnect itself — broker stopped, writes rejected, broker restarted, catch-up with no
  restart — is STORE-009's `storeUnreachableMidRunKeepsServingAndRecovers`.

## Observability

Every metric named by an earlier STORE task is registered here, once, with these exact names —
three of them are already promised to operators by `docs/operations/metadata-store.md` §6:

| Metric | Kind | Labels |
| --- | --- | --- |
| `kui.store.replay.lag` | gauge | — |
| `kui.store.replay.records` | counter | — |
| `kui.store.replay.duration` | histogram | — |
| `kui.store.write.total` | counter | — |
| `kui.store.write.errors` | counter | `reason` |
| `kui.store.write.latency` | histogram | — |
| `kui.store.records.ignored` | counter | — |
| `kui.store.records.unreadable` | counter | — |
| `kui.store.crypto.failures` | counter | `reason` |
| `kui.store.topics.created` | counter | — |
| `kui.store.health` | gauge | `state` = `healthy`/`degraded`/`read_only` |

Logs: WARN per reconnect attempt (`attempt`, `delayMs`, `reason`), INFO on recovery
(`outageMs`, `resumedAtOffset`, `caughtUpRecords`), ERROR once on the transition to degraded.

## Degraded behavior

This task *is* the degraded behavior. The contract, restated so it can be checked off:

| Situation | Reads | Writes | Health | UI |
| --- | --- | --- | --- | --- |
| Store healthy | from memory | accepted | `Healthy` | normal |
| Store unreachable mid-run | from memory, unchanged | `KUI-STORE-UNAVAILABLE` | `Degraded(reason, since)` | banner with the reason, save disabled |
| Store unreachable at startup | — | — | — | the service does not start (STORE-006) |
| No store configured | from files | `KUI-STORE-NOT-CONFIGURED` | `ReadOnly` | the action is absent, not broken |
| One record unreadable | that key missing | that key writable (a write repairs it) | `Healthy` + `unreadableKeys` | the affected cluster is missing, with a WARN in the log naming it |

## Docs to update

`docs/operations/metadata-store.md` §6: add the `kui.store.health` gauge and the
`kui.store.write.errors{reason}` label set to the "watch these" paragraph, and add the
unreadable-record row to the table.
