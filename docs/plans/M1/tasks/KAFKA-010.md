# KAFKA-010 — `libs/cache`: `SnapshotCell` with status, `scrapedAt` and supervised refresh

- **ID:** KAFKA-010
- **Title:** `libs/cache`: `SnapshotCell` with status, `scrapedAt` and supervised refresh
- **Milestone / Feature:** M1 / CL-003, CL-005, OT-007, OT-008, KU-033
- **Owner role:** Principal Scala Engineer
- **Context / service:** `libs/cache` (new module)
- **Size:** M
- **Dependencies / blocked by:** none. Startable on day one, in parallel with KAFKA-001.

## Goal (user value)

When a cluster stops answering, the page keeps showing what KUI last saw, greyed out and
stamped with the time it was seen — instead of going blank or hanging. That behaviour is the
milestone's most visible promise, and it is one type, implemented once, that every per-cluster
snapshot in KUI will use.

## Scope

1. Create the Mill module `libs.cache` (JVM only).
2. `SnapshotCell[F, A]` — a single value with a status, a `scrapedAt`, atomic replacement, a
   background refresh under a `Supervisor`, and reads that keep serving the previous value while
   the upstream fails.
3. `SnapshotStatus` — `Initializing`, `Online`, `Offline(lastError, since)` — the vocabulary
   `ARCHITECTURE.md` §9 requires of every snapshot in the system.
4. Forced refresh, idempotent under concurrency: the CL-005 refresh button pressed five times in
   a second is one refresh.
5. Cache metrics per ADR-016.

## Non-goals

- **No `BoundedCache`.** ADR-016 defines two primitives but ties each to a named consumer, and
  the Caffeine wrapper has none until M2 or M3 (DEVPLAN decision D2). A cache with no caller has
  no TTL policy to test against. `libs/cache` ships one type in M1.
- No Kafka. `libs/cache` has no Kafka dependency and must not acquire one; A10's allow-list does
  not include it. It is generic in `A`.
- No cache of many keys, no eviction, no TTL-as-expiry. A `SnapshotCell` is one value that is
  refreshed, not a map of entries that expire — a "stale" read is a feature here, not a miss.
- No HTTP, no `Section` envelope. Turning a `Snapshot` into a `Section[A]` with
  `Section.Stale` is the cluster `api`'s job (CLAPI-004); this module has no wire types.

## Design references

ADR-016 (`SnapshotCell` defined: "`Ref`-backed single value with `status`, `scrapedAt`, atomic
replacement, `refresh` under a `Supervisor`, `Stale` reads while the upstream fails"; and the
discipline every cache must declare — TTL, invalidation trigger, bound, hit/miss metrics,
staleness contract), ADR-027 (per-context snapshots: the three-state `status`, `scrapedAt`,
atomic replacement), `ARCHITECTURE.md` §9 (the cluster row: metadata every 30 s, capabilities
hourly, "reads ≤ 30 s old"), ADR-032 (how `Stale` renders, rule DC-H3), ADR-002 (cats-effect
concurrency), DEVPLAN §7 (the snapshot-cell suite row) and decision D2.

## Files to create

```
libs/cache/src/kui/cache/SnapshotStatus.scala
libs/cache/src/kui/cache/Snapshot.scala
libs/cache/src/kui/cache/SnapshotCell.scala
libs/cache/src/kui/cache/CacheMetrics.scala
libs/cache/test/src/kui/cache/SnapshotCellSuite.scala
```

## Files to change

```
build.mill    # add `object cache` inside `object libs`
```

```scala
  /** The one caching primitive M1 needs: a single value that is refreshed on a schedule and
    * keeps answering while the refresh fails (ADR-016).
    *
    * `BoundedCache`, ADR-016's other primitive, is deliberately absent: it arrives with its
    * first consumer in M2/M3. A cache with no caller has no TTL policy to test against.
    */
  object cache extends KuiPureModule with KuiJvmModule {
    def moduleDeps = Seq(kernel.jvm, observability)

    def mvnDeps = Seq(
      mvn"org.typelevel::cats-core::${Versions.cats}",
      mvn"org.typelevel::cats-effect::${Versions.catsEffect}",
      mvn"co.fs2::fs2-core::${Versions.fs2}",
      mvn"org.typelevel::log4cats-core::${Versions.log4cats}",
      mvn"org.typelevel::otel4s-core::${Versions.otel4s}"
    )

    object test extends ScalaTests with KuiTests {
      def moduleDeps = super.moduleDeps ++ Seq(testkit.jvm)
    }
  }
```

The `libs.observability` edge is not in DEVPLAN §5.1's list for this module; it is added
because ADR-016 requires `kui.cache.hits` and `kui.cache.misses` per cache and those names
already live in `libs/observability`'s `MetricNames`. `libs.http` sets the precedent for a
`libs` module depending on `libs.observability`.

## Public Scala signatures to implement

```scala
package kui.cache

import java.time.Instant
import kui.kernel.KuiError

/** Where a snapshot stands with its upstream — the three states `ARCHITECTURE.md` §9 requires
  * of every snapshot in KUI, so that "how old is this and can I trust it" has the same answer
  * shape on every screen.
  */
enum SnapshotStatus {
  /** No successful load yet. There is no value to show. */
  case Initializing
  /** The last refresh succeeded. */
  case Online
  /** The last refresh failed. `since` is the time of the *first* failure in this run of
    * failures, not the most recent one — the question a user asks about a grey row is "how long
    * has this been down", exactly as ADR-039 argues for its own sticky `since`. */
  case Offline(lastError: KuiError, since: Instant)
}
```

```scala
package kui.cache

/** A value, how old it is, and whether it is current.
  *
  * `value` and `status` are independent, and that is the entire point of the type: the
  * interesting state is `Some(value)` with `Offline` — data from the last successful scrape,
  * known to be out of date. `Initializing` with `None` is the only combination in which a
  * caller has nothing to render.
  */
final case class Snapshot[A](
    value: Option[A],
    status: SnapshotStatus,
    /** When `value` was produced. `None` only while `Initializing`. Never advanced by a failed
      * refresh — a timestamp that moves while the data does not is a lie told once a minute. */
    scrapedAt: Option[Instant]
) {
  def isStale: Boolean                    // value.isDefined && status is Offline
  def map[B](f: A => B): Snapshot[B]
  def toEither: Either[KuiError, A]       // for a caller that genuinely cannot render stale data
}
```

```scala
package kui.cache

import cats.effect.{Resource, Temporal}
import scala.concurrent.duration.FiniteDuration

/** One value, kept fresh in the background, always readable.
  *
  * The contract, in four sentences, because every screen in KUI depends on it:
  *   - `get` never blocks on the upstream and never fails.
  *   - A failed refresh leaves the previous value in place and changes only the status.
  *   - `scrapedAt` advances only on success.
  *   - Replacement is atomic: a reader sees the old value or the new one, never a mixture and
  *     never an empty gap during a refresh.
  */
trait SnapshotCell[F[_], A] {

  /** The current snapshot. Pure read of a `Ref`; no I/O, no waiting, no failure. */
  def get: F[Snapshot[A]]

  /** Forces a refresh and returns the snapshot that results.
    *
    * Idempotent under concurrency: callers arriving while a refresh is in flight join that
    * refresh instead of starting another. Five presses of the CL-005 refresh button are one
    * request to the broker, which is what stops that button from being an outage tool.
    */
  def refresh: F[Snapshot[A]]

  /** Drops the value and returns to `Initializing`, then refreshes. For a profile change, where
    * the previous value describes a cluster that is no longer the one configured — showing it
    * greyed would be showing another cluster's data. */
  def invalidate: F[Snapshot[A]]

  /** The stream of successful snapshots, for a caller that wants to react rather than poll
    * (CLADP-005). Backpressure-free: a slow subscriber sees the latest, not a queue. */
  def updates: fs2.Stream[F, Snapshot[A]]
}

object SnapshotCell {

  /** Creates a cell and starts its refresh loop under a `Supervisor`, so that releasing the
    * `Resource` cancels an in-flight refresh rather than leaking a fiber that outlives the
    * component it belonged to.
    *
    * @param name     the `cache` metric attribute and the log context; one short stable string
    *                 per kind of snapshot, e.g. `cluster.topology`. Never a per-cluster value —
    *                 the cluster goes in its own attribute, and a metric label that multiplies
    *                 with the number of clusters is how a metrics backend runs out of memory.
    * @param interval the background cadence. 30 seconds for the cluster snapshot, 1 hour for
    *                 capabilities (`ARCHITECTURE.md` §9). There is no TTL and no expiry: a
    *                 value older than `interval` is shown, marked stale, not withheld.
    * @param load     the refresh. Called with no arguments; a failure is caught, mapped and
    *                 recorded, never propagated to a reader.
    */
  def resource[F[_]: Temporal, A](
      name: String,
      cluster: ClusterId,
      interval: FiniteDuration,
      metrics: CacheMetrics[F]
  )(load: F[A]): Resource[F, SnapshotCell[F, A]]

  /** A cell that never refreshes, for tests and for a value that is genuinely constant. */
  def constant[F[_]: Temporal, A](value: A, at: Instant): SnapshotCell[F, A]
}

/** ADR-016's metric requirement, as an interface, so the cell records without knowing about
  * OpenTelemetry and so `libs/testkit` can supply a counting fake. */
trait CacheMetrics[F[_]] {
  def hit(cache: String, cluster: ClusterId): F[Unit]
  def miss(cache: String, cluster: ClusterId): F[Unit]
  def staleRead(cache: String, cluster: ClusterId): F[Unit]
  def refreshFailed(cache: String, cluster: ClusterId): F[Unit]
}

object CacheMetrics {
  def otel4s[F[_]: Async](meter: Meter[F]): F[CacheMetrics[F]]
  def noop[F[_]: Applicative]: CacheMetrics[F]
}
```

### Implementation rules

- **One `Ref` and one `Semaphore`.** The `Ref` holds the `Snapshot`; the semaphore (permits = 1)
  makes concurrent refreshes join rather than pile up. A caller that arrives while a refresh
  holds the permit waits on a shared `Deferred` completed by that refresh, so it gets the *new*
  value rather than the value from before its own call — which is what "forced refresh" has to
  mean for the button to be honest.
- **The background loop is `(refresh >> sleep(interval)).foreverM`, started with
  `Supervisor.supervise`** and cancelled by the `Resource`'s finalizer. Sleep *after* the
  refresh, so a cell has data as soon as it can rather than one interval later.
- **A failed refresh maps its error with the caller's own mapping** — `load` returns `F[A]` and
  raises `KuiError` (or anything else, which becomes
  `InfrastructureError.Upstream("snapshot", 502)`), and the cell records it in
  `Offline(lastError, since)`. `since` is preserved across consecutive failures and reset on the
  first success.
- **`scrapedAt` comes from `Temporal[F].realTime`,** never from the loaded value, so it means
  "when KUI saw this" and can be compared across snapshots.
- **`updates` is a `Topic`-style broadcast of successful snapshots only.** A failure is visible
  through `get`; publishing failures on the stream would make every subscriber implement the
  same filtering.

### The staleness contract (ADR-016 requires this to be written down)

| Property | Value |
| --- | --- |
| TTL | none. Data is never withheld for being old; it is marked. |
| Refresh | every `interval`, in the background, under a `Supervisor` |
| Invalidation triggers | `refresh` (CL-005's button), `invalidate` (a profile change, CLADP-005) |
| Bound | one value per cell. Cells are created per cluster by their owner and released with it. |
| Metrics | `kui.cache.hits`, `kui.cache.misses` (`{cache, cluster}`), plus stale-read and refresh-failure counters under the same names with an outcome attribute |
| Staleness contract | a read is at most `interval` old while `Online`; while `Offline` it is arbitrarily old and carries `scrapedAt` so the UI can say how old (ADR-032 DC-H3) |

## ADRs this task must obey

ADR-016 (the primitive, the discipline table above, and "no cache without a named consumer" —
which is why `BoundedCache` is not here), ADR-027 (the status vocabulary and atomic
replacement), ADR-032 (stale data is rendered, not hidden), ADR-002, ADR-041 (a `libs` module,
so no `var` — `KuiPureModule`).

## Library coordinates

`org.typelevel::cats-effect::3.7.1`, `co.fs2::fs2-core::3.13.0`,
`org.typelevel::cats-core::2.13.0`, `org.typelevel::log4cats-core::2.8.0`,
`org.typelevel::otel4s-core::1.1.0`. Test scope adds
`org.typelevel::munit-cats-effect::2.2.0` (through `KuiTests`). No Caffeine — it arrives with
`BoundedCache`, not before.

## Acceptance criteria

```
$ ./mill libs.cache.compile         # clean under -Werror
$ ./mill libs.cache.test
$ ./mill libs.cache.checkFormat
$ ./mill libs.cache.fix --check
$ ./mill show libs.cache.moduleDeps | grep -i kafka
# no output: libs/cache has no Kafka dependency and never will
```

The behaviour the milestone is judged on, asserted with virtual time:

```scala
TestControl.executeEmbed {
  for {
    failing <- Ref[IO].of(false)
    cell    <- cellLoading(failing)         // loads "v1", then fails once `failing` is true
    _       <- IO.sleep(1.second)
    first   <- cell.get                     // Online, Some("v1")
    _       <- failing.set(true)
    _       <- IO.sleep(35.seconds)         // one refresh interval passes and fails
    stale   <- cell.get
  } yield {
    assertEquals(stale.value, Some("v1"))         // the value survives
    assert(stale.status.isInstanceOf[SnapshotStatus.Offline])
    assertEquals(stale.scrapedAt, first.scrapedAt) // the timestamp did NOT move
    assert(stale.isStale)
  }
}
```

## Tests required

- `SnapshotCellSuite` (unit, `munit-cats-effect` + `TestControl` — no real sleeping anywhere):
  - `initializingHasNoValue` and `getDoesNotBlockOnTheFirstLoad`.
  - **`staleReadsSurviveAFailingUpstream`** — the assertion above.
  - **`scrapedAtIsMonotonicAndOnlyAdvancesOnSuccess`** (property over a generated sequence of
    successes and failures).
  - `offlineSinceIsStickyAcrossConsecutiveFailuresAndResetsOnSuccess`.
  - `replacementIsAtomicUnderConcurrentReaders` — a hundred concurrent readers during a refresh
    see either the old value or the new one, never `None` and never a mixture.
  - **`concurrentForcedRefreshesCollapseIntoOne`** — ten simultaneous `refresh` calls, one
    invocation of `load`, and all ten receive the *new* snapshot.
  - `aRefreshArrivingDuringAnInFlightRefreshGetsTheNewValueNotTheOld`.
  - `backgroundRefreshRunsOnTheInterval` — three intervals, three loads.
  - **`releasingTheResourceCancelsAnInFlightRefresh`** — assert the load's cancellation
    finalizer ran; a leaked refresh fiber holding an admin client is the failure this test
    exists to prevent.
  - `invalidateReturnsToInitializingAndReloads` — and, importantly, `get` between the two shows
    no value rather than the previous cluster's data.
  - `aRaisedNonKuiErrorBecomesAnInfrastructureError`.
  - `loadIsNeverCalledConcurrentlyWithItself` (property, with a counter that fails if it ever
    exceeds one).
  - `updatesEmitsOnlySuccessfulSnapshots` and `aSlowSubscriberDoesNotStallTheRefresh`.
  - `metricsAreRecorded` — with the `libs/testkit` fake: a hit per `get` with a value, a miss
    per `get` without one, a stale-read per `get` while `Offline`, a refresh-failure per failed
    load.
  - `constantNeverCallsAnything`.

`libs/testkit` gains `FakeCacheMetrics`, counting by `(cache, cluster, kind)`.

## Observability

- **Metrics**, using the existing names in `libs/observability`'s `MetricNames`:
  `kui.cache.hits` and `kui.cache.misses`, attributes `cache` and `cluster`. A stale read counts
  as a hit *and* increments the stale counter — it did serve data, and the fact that it was old
  is a separate question an operator asks separately.
- **Logs**, under `kui.cache`: WARN on the transition `Online -> Offline` with the error code and
  the cache name; INFO on `Offline -> Online` with how long it was offline. Nothing at all on a
  refresh that succeeds after a success — a per-cluster INFO line every thirty seconds is a log
  file nobody can read. This asymmetry mirrors ADR-039's: slow to complain, quick to say it is
  better.
- No span. A background refresh has no request to attach to; the admin call inside `load` has
  its own span from KAFKA-004.

## Degraded behavior

This module *is* KUI's degraded behaviour for read paths, and its contract is the milestone's
headline promise. Concretely:

- **Upstream failing, value present:** serve it, `Offline`, `scrapedAt` unchanged. CLAPI-004
  turns this into `Section.Stale`, CLUI-001 draws the overlay, and the row stays clickable
  (decision D4).
- **Upstream failing, no value yet:** `Initializing` with `None`. The caller renders
  `Section.Unavailable(reason)` — a cluster KUI has never reached has nothing to show, and
  pretending otherwise is worse than saying so.
- **Upstream recovering:** the next scheduled refresh replaces the value and the status; no
  restart, no manual step, and `updates` emits.
- **The cell's own failure modes:** there are none by design. `get` cannot fail, `refresh`
  returns a snapshot rather than raising, and the background loop catches everything. A cache
  that can throw turns one upstream outage into a broken page, which is the whole thing this
  type exists to prevent.

## Docs to update

`ARCHITECTURE.md` §9's cluster row already describes this behaviour and needs no change here.
CFGOP-008 checks it against what shipped. The staleness-contract table above is what ADR-016
requires "recorded in `ARCHITECTURE.md` §9" and is the source CFGOP-008 works from.

## Deviations

*(filled in by the implementer, in the same commit)*
