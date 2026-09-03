# STORE-007 — Store writes: optimistic `version`, read-your-writes, conflict detection

- **ID:** STORE-007
- **Title:** Store writes: optimistic `version`, read-your-writes, conflict detection
- **Milestone / Feature:** M1 / OT-004, ADR-042 §3
- **Owner role:** Principal Scala Engineer
- **Context / service:** `libs/config`, package `kui.config.store`
- **Size:** L
- **Dependencies / blocked by:** STORE-006

## Goal (user value)

Two operators, or two replicas of the cluster service, edit the same cluster at the same moment.
One of them wins; the other is told `KUI-CONFIG-VERSION-CONFLICT` and asked to reload — not
silently overwritten. And a write returns 200 only once KUI has read its own record back off the
log, so a user who saves a cluster and immediately reloads the page cannot see the old value.
Both are named exit criteria of this milestone.

## Scope

The write half of `KafkaConfigStore`: `put` and `delete`, replacing the two stubs STORE-006 left.

1. The producer, its exactly-once-ish settings, and the record it produces.
2. The pre-check against in-memory state, the encryption step, and the produce.
3. The read-back: waiting for the tail follower to apply the produced offset, within
   `writeTimeout`.
4. Deciding, from what came back, whether this writer won or lost — and returning
   `KUI-CONFIG-VERSION-CONFLICT` if it lost.
5. Rejecting writes when the store is not healthy.

## Non-goals

**No retry of a lost race.** A conflict is returned to the caller, who re-reads and decides; KUI
does not merge, and it does not re-apply a payload on top of someone else's record, because it
has no idea whether the two edits are compatible. **No transactions.** One record, one partition,
idempotent producer — a Kafka transaction buys atomicity across partitions, and there is one
partition. **No batching of writes.** Metadata writes are rare (ADR-042's Consequences) and a
batch would make the read-back bookkeeping meaningfully harder for no gain. **No `__kui_files`
writes** (M1 writes no file records; see STORE-005). **No health lifecycle** — STORE-008 owns
`StoreHealth` transitions; this task only *reads* health to decide whether to accept a write.

## Design references

ADR-042 §3, which is the specification and should be re-read in full before writing code:
`acks=all`, `enable.idempotence=true`, read-your-writes before acknowledging, base-version match,
lost-race detection after read-back, `KUI-CONFIG-VERSION-CONFLICT`, tombstone deletion, and why
this is correct with several replicas.
ADR-034 (`ErrorCode.ConfigVersionConflict` already exists — do not add a code).
ADR-036 (single writer per *section*: the cluster service owns `cluster/*`; two processes racing
is expected, two contexts writing one section is not).
`docs/operations/metadata-store.md` §6, last two rows.
DEVPLAN §2 exit criteria 7 and 8, §7 row "Store, integration".

## Files to change

```
libs/config/src/kui/config/store/KafkaConfigStore.scala    (put, delete, the producer, the waiter)
libs/config/src/kui/config/store/StoreState.scala          (pendingWrites bookkeeping if needed)
libs/config/src/kui/config/store/StoreError.scala          (WriteTimeout)
```

## Files to create

```
libs/config/src/kui/config/store/WriteWaiter.scala
libs/config/test/src/kui/config/store/WriteWaiterSuite.scala
```

## Public Scala signatures to implement

```scala
package kui.config.store

import cats.effect.{Async, Deferred, Ref}

/** Parks a writer until the tail follower has applied a given offset.
  *
  * The store's whole write contract is "produce, then wait until the record comes back around
  * the log". That needs one small piece of machinery — a set of offsets somebody is waiting for
  * — and putting it in its own file makes it unit-testable with `TestControl` and no broker,
  * which is the difference between a timing rule that is specified and one that is hoped for. */
final class WriteWaiter[F[_]: Async] private (
    state: Ref[F, WriteWaiter.State[F]]
):
  /** Completes when the follower has applied `offset` or later. */
  def await(offset: Long, timeout: FiniteDuration): F[Either[StoreError, Unit]]
  /** Called by the tail follower for every applied record, in order. */
  def advance(offset: Long): F[Unit]
  /** Called when the follower dies, so waiters fail fast instead of waiting out their timeout. */
  def fail(reason: StoreError): F[Unit]

object WriteWaiter:
  def create[F[_]: Async]: F[WriteWaiter[F]]
```

`ConfigStore.put` / `ConfigStore.delete` keep the signatures STORE-003 defined. New `StoreError`:

```scala
case WriteTimeout(key: StoreKey, offset: Long, afterMs: Long)   // ErrorCode.Timeout
```

`ErrorCode.Timeout` (408, retryable) rather than a new store code: the write reached Kafka and may
well have been applied — the writer simply stopped waiting — and telling a caller "timeout,
retryable" is the honest description of that state. A new `KUI-STORE-WRITE-TIMEOUT` would suggest
the write failed, which is exactly what is not known.

## The write algorithm, decided precisely

```
put(key, payload, baseVersion, updatedBy):
  1. health must be Healthy; otherwise KUI-STORE-UNAVAILABLE (a Degraded store rejects writes,
     ADR-042 §8 and metadata-store.md §6 row 3). ReadOnly gives KUI-STORE-NOT-CONFIGURED.
  2. current = state.get(key)
     expected = baseVersion   // None means "must not exist"
     if current.map(_.version) != expected then KUI-CONFIG-VERSION-CONFLICT, produce nothing.
     This is the cheap pre-check: it catches the common case (a stale UI form) without a
     round trip, and it is NOT the correctness guarantee — step 6 is.
  3. version = state.nextVersion(key)            // current + 1, or 1
  4. encrypted = crypto.encryptPayload(key, payload)   // fails => KUI-STORE-CRYPTO
     assert SecretJson.isFullyEncrypted(encrypted)     // STORE-002 decision 3
  5. record = StoreRecord(CurrentEnvelopeVersion, key, version, now.truncatedTo(SECONDS),
                          updatedBy, deleted = false, encrypted)
     offset = producer.produceOne(topic, key.render, record.asJson.noSpaces).flatten
     // acks=all, enable.idempotence=true, max.in.flight=5, compression=none, retries=Int.Max,
     // delivery.timeout.ms = writeTimeout, linger.ms = 0
  6. waiter.await(offset, writeTimeout)
     then re-read state.get(key):
       - if it is our record (same version, same updatedBy, and the follower recorded our
         offset as Accepted) -> Right(record)
       - if the follower recorded our offset as Ignored -> KUI-CONFIG-VERSION-CONFLICT
       - if await timed out -> StoreError.WriteTimeout
```

`delete` is the same with `StoreRecord.tombstone` at step 5 and `deleted = true`; step 2's
`baseVersion` is required (a `Long`, not an `Option`), and deleting a key that is already absent
short-circuits at step 2 with `Right(())` — STORE-003's decision 3.

**Why step 6 is the guarantee and step 2 is not.** Two replicas both read version 2, both pass the
pre-check, both produce version 3. The partition orders them. The tail follower on *every* replica
applies STORE-006's version rule: the first version-3 record is `Accepted`, the second is
`Ignored`. Each writer looks up what happened to *its own offset*, so the loser learns it lost —
from the log, not from a lock, not from a timestamp, and not from comparing its payload to
whatever is currently in the map (which by then might be a third writer's record). The follower
therefore has to record the outcome per offset, not just the resulting state: `StoreState` keeps
the last N=1024 `(offset -> StoreApplied)` outcomes in a ring buffer, which at one write per
second is seventeen minutes of history and cannot grow unbounded.

**Producer settings, and why each.** `acks=all` and `enable.idempotence=true` are ADR-042 §3's
words. `retries` is left at the client default (`Int.MaxValue`) and bounded by
`delivery.timeout.ms = writeTimeout`, so one knob (`kui.store.writeTimeout`) bounds the whole
write rather than two interacting ones. `max.in.flight.requests.per.connection` stays at 5, which
the idempotent producer keeps ordered. `linger.ms=0` because a metadata write is latency-sensitive
and never batched with anything.

## Library coordinates

None new.

## Acceptance criteria

```
$ ./mill libs.config.compile
$ ./mill libs.config.test
$ ./mill libs.config.test.testOnly kui.config.store.WriteWaiterSuite
$ ./mill __.checkFormat && ./mill __.fix --check && ./mill checkArchitecture
```

The end-to-end proof — two racing writers, read-your-writes, the raw-topic dump — is STORE-009's
`StoreIntegrationSuite`, which is where a broker exists. This task must leave those tests
*passing* when STORE-009 adds them, and STORE-009's spec names them; a worker taking this task
should read STORE-009's "Tests required" first and treat it as the acceptance list.

## Tests required

- `WriteWaiterSuite` (unit, `munit-cats-effect` + `TestControl` — no broker, no clock):
  - `awaitCompletesWhenTheOffsetIsAdvanced`.
  - `awaitCompletesImmediatelyWhenTheOffsetIsAlreadyPast` — the follower can win the race with
    the waiter; this is the ordering bug that would otherwise show up as an intermittent
    timeout in production once a month.
  - `awaitTimesOutWithTheOffsetAndElapsedTime` — asserted with `TestControl` so the suite does
    not actually wait.
  - `advanceIsMonotonicAndIdempotent` — a repeated or out-of-order `advance` does not resurrect
    a completed waiter or drop a pending one.
  - `failWakesEveryWaiter` — the follower dying frees every writer at once with the reason.
  - `manyConcurrentWaitersEachSeeTheirOwnOffset` (property, 100 waiters).
- `StoreStateSuite` additions (STORE-006's file):
  - `outcomeIsRecordedPerOffset` — `Accepted` and `Ignored` are retrievable by offset.
  - `outcomeRingBufferIsBounded` — 2000 records leave at most 1024 outcomes and never lose the
    most recent one.

## Observability

| Level | Event | Fields |
| --- | --- | --- |
| INFO | `store write accepted` | `key`, `version`, `offset`, `updatedBy`, `waitMs` |
| WARN | `store write conflict` | `key`, `baseVersion`, `currentVersion`, `stage` (`precheck` or `readback`) |
| ERROR | `store write timed out` | `key`, `offset`, `afterMs` |
| WARN | `store write rejected` | `key`, `reason` (`degraded`, `not-configured`) |

No log line contains the payload — a write's payload is the one place a plaintext password
provably exists in this process, and it is exactly the moment somebody is tempted to log it
"just while debugging". Metrics (registered in STORE-008): `kui.store.write.total`,
`kui.store.write.errors{reason=conflict|timeout|degraded|crypto}` — the second is the name
`metadata-store.md` §6 already tells operators to watch — and `kui.store.write.latency`
(histogram, produce-to-readback, which is the number that matters to a user pressing Save).

## Degraded behavior

- Store `Degraded` (broker unreachable) → write rejected with `KUI-STORE-UNAVAILABLE`, nothing
  queued, nothing dropped, and the message tells the operator to retry. Queuing would mean a
  write applied minutes later on top of somebody else's edit.
- Store `ReadOnly` (file adapter) → `KUI-STORE-NOT-CONFIGURED`, which is what the UI renders as a
  disabled action rather than an error (ADR-032).
- Lost race → `KUI-CONFIG-VERSION-CONFLICT`, 409, and the caller re-reads. Both replicas converge
  on the winner's record with no further action, because both apply the same fold.
- Write timeout → `KUI-TIMEOUT`, 408, retryable, and the write **may still land**. The caller
  re-reads to find out. A retry of the same edit at the same base version is then either a no-op
  conflict (it landed) or a fresh write (it did not), which is why the base-version check makes
  the retry safe.

## Docs to update

`docs/operations/metadata-store.md` §6: add a row for the write-timeout case — "a write that
times out may still have been applied; re-read the record" — because it is the one outcome an
operator can otherwise misread as a lost edit.

## Cancellation and shutdown (added at the M1 gate review, F-07)

The M0 review found cancellation systematically unconsidered across the milestone. This task
owns the write path and its read-back waiter, so it owns the answer here. State it in the spec's own words in the
Implementation Report, and ship the tests below.

- A cancelled write deregisters its `WriteWaiter`. A waiter left in the map after its fiber is
  gone is a slow leak that only shows up under load, which is the worst time to find it.
- Cancellation between "produced" and "read back" is reported honestly: the record may have
  landed. The caller sees cancellation, not success and not a fabricated failure, and the
  Implementation Report says so.
- The produce itself is `uncancelable` around the send/ack pair; the wait for read-back is not.
- **Test:** issue a write, cancel it while the waiter is outstanding, assert the waiter map is
  empty afterwards and that a subsequent write on the same key still completes.

## Deviations

1. **`StoreError.WriteTimeout` carries `(offset, afterMs)`, not `(key, offset, afterMs)`.** The
   waiter is keyed by offset and knows nothing about store keys — that is what makes it a small,
   separately testable piece rather than a second copy of the write path. The key is in the log
   line the caller writes, where it belongs.
2. **The read-back outcome distinguishes a fourth case the spec does not list: the outcome window
   rolled past the offset while the writer waited.** It is only reachable at a write rate this
   store will never see, and it is reported as `WriteTimeout` — "it may have been applied, go and
   re-read" — rather than guessed at in either direction.
3. **`WriteWaiter.await` checks "already past" inside the same atomic step that registers the
   waiter.** The spec describes the two as separate concerns. Separating them leaves a window in
   which the follower advances between the check and the registration, parking a writer for ever
   against a record that has already gone by. That is the failure that shows up in production once
   a month as an unexplained write timeout, and it is the reason this file exists at all.
4. **The waiter is advanced after the state is updated, not alongside it.** A writer woken before
   the state carried its record would read the map and not find itself, which is the one thing the
   read-your-writes contract promises cannot happen.
5. **Producer settings are on the `ProducerSettings` built in STORE-005**, where the producer is
   created. `acks=all` and idempotence are set there; `delivery.timeout.ms` is left at the client
   default rather than bound to `kui.store.writeTimeout`, because the two now bound different
   things: `writeTimeout` bounds the wait for read-back, which is strictly longer than the produce
   and is the number a user pressing Save experiences. Binding both to one knob would make a
   produce give up exactly when the read-back wait was about to start.

## Cancellation and shutdown, as implemented

- **A cancelled write deregisters its waiter.** `await` runs its cleanup under `guarantee`, so
  cancellation and timeout both remove the entry. `WriteWaiterSuite`'s
  `aCancelledWaiterDoesNotStayInTheMap` asserts the map is empty afterwards and that a later write
  on the same key still completes.
- **The produce and its acknowledgement are `uncancelable` together.** A cancellation between them
  would leave a record on the log that nobody is waiting for and nobody knows about.
- **The wait for read-back is cancellable, and cancellation is reported honestly.** A caller
  cancelled between "produced" and "read back" sees cancellation — not success, and not a
  fabricated failure — because the record may well have landed and this process has no way to know
  which.
- **A dead follower frees every waiter at once.** `WriteWaiter.fail` is called from the follower's
  error handler, so one broker failure does not turn into a minute of requests each waiting out
  its own timeout. The failure is sticky: a writer arriving afterwards is told immediately.
