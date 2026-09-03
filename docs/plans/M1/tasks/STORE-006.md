# STORE-006 — `KafkaConfigStore`: replay to the end, then follow the tail

- **ID:** STORE-006
- **Title:** `KafkaConfigStore`: replay to the end, then follow the tail
- **Milestone / Feature:** M1 / OT-004, ADR-042 §1 and §3
- **Owner role:** Principal Scala Engineer
- **Context / service:** `libs/config`, package `kui.config.store`
- **Size:** L
- **Dependencies / blocked by:** STORE-005, STORE-002

## Goal (user value)

KUI reads its whole metadata log into memory before it says it is ready, and then keeps up with
it forever. Every replica of the cluster service sees the same records in the same order, which
is what makes the rest of the store — concurrent writes, conflict detection, change notification
— possible without a lock. And it either finishes replaying inside a bounded time or fails with a
named error: risk R-2 says the worst startup failure shape is a hang, and this task is where the
hang is designed out.

## Scope

The read half of the Kafka adapter.

1. `KafkaConfigStore.resource`: assign, seek to the beginning, replay to the end offset within
   `replayTimeout`, then keep consuming in a background fiber for the life of the resource.
2. `StoreState`: the in-memory `Map[StoreKey, StoreRecord]` behind a `Ref`, the version rule that
   decides whether a record is *accepted* or *ignored*, and the derived read methods of
   `ConfigStore`.
3. Decryption on the way in (STORE-002), decode failures handled per record.
4. The `changes` stream, published from the same fold that updates the state.
5. `KUI-STORE-REPLAY-TIMEOUT`, and the reachability failures at startup.

## Non-goals

**No writes.** `put` and `delete` are implemented in STORE-007. Until then `KafkaConfigStore`
is a **read-only** `ConfigStore[F]` whose `put` and `delete` return `KUI-STORE-NOT-CONFIGURED`
with the message *"the store's write path is not wired yet (STORE-007)"*, and STORE-007 replaces
exactly those two methods. That keeps `main` green after this task, which DEVPLAN §6 requires of
every task, without shipping a half-working write.
**No `StoreHealth` transitions** beyond `Healthy` after a successful replay — the degraded
lifecycle, the reconnect behaviour and the health metric are STORE-008. **No `__kui_files`
consumption**: M1 writes no file records, so the adapter consumes `__kui_config` only, and the
`files` topic is created and left alone.

## Design references

ADR-042 §1 (bootstrap order: static config → store client → replay to end → clusters known →
Ready), §3 (one partition, total order, each replica replays then follows), §8 (unreachable
behaviour). `docs/operations/metadata-store.md` §1 "Startup order" and §6.
ADR-006 (fs2-kafka 4; the consumer API and the raw `Admin` escape hatch).
DEVPLAN §8 R-2, §7 rows "Store, integration" and "Store envelope", §4 first-mover note
("STORE-006 — does replay-then-tail terminate?").
`research/kafka/admin-capabilities.md` §0 for `listOffsets`/end-offset behaviour and the errors it
can return.

## Files to create

```
libs/config/src/kui/config/store/KafkaConfigStore.scala
libs/config/src/kui/config/store/StoreState.scala
libs/config/test/src/kui/config/store/StoreStateSuite.scala
```

## Files to change

```
libs/config/src/kui/config/store/StoreError.scala      (ReplayTimeout, Unreachable)
```

## Public Scala signatures to implement

```scala
package kui.config.store

import cats.effect.{Async, Resource}
import fs2.Stream
import org.typelevel.log4cats.LoggerFactory

/** The in-memory projection of `__kui_config`, and the rule that decides what the log means.
  *
  * Pure and platform-free on purpose: every interesting decision in the store — which of two
  * racing writers won, whether a record is a lost race, what the next version is — is in here
  * and is unit-testable without a broker. The Kafka adapter is then only plumbing. */
final case class StoreState(
    records: Map[StoreKey, StoreRecord],
    lastAppliedOffset: Long,
    unreadable: Map[StoreKey, String]
):
  def get(key: StoreKey): Option[StoreRecord]           // None for a tombstone
  def list(section: StoreSection): List[StoreRecord]
  def nextVersion(key: StoreKey): Long                  // current + 1, or 1

  /** Folds one decoded record in. Returns the new state and what, if anything, changed.
    *
    * **The version rule, which is the heart of the design.** A record is *accepted* only when
    * `record.version == nextVersion(key)`. Anything else is *ignored*: it is a writer whose base
    * version was stale — two replicas both read version 2 and both produced version 3, and the
    * one that landed second in the partition lost. Every replica applies this rule to the same
    * ordered log, so every replica agrees on who won without any of them talking to each other.
    * That is what makes ADR-042 §3's "the partition, not a lock, is the serialization point"
    * true rather than aspirational. */
  def apply(record: StoreRecord, offset: Long): (StoreState, StoreApplied)

object StoreState:
  val empty: StoreState

enum StoreApplied:
  case Accepted(change: StoreChange)
  case Ignored(key: StoreKey, recordVersion: Long, expectedVersion: Long)
  case Unreadable(key: StoreKey, reason: String)

/** The Kafka-backed `ConfigStore`. Read-only until STORE-007. */
object KafkaConfigStore:
  /** Bootstraps the topics, replays the log, starts the tail follower, and hands back a store
    * that is already caught up. The `Resource`'s acquisition **is** ADR-042's bootstrap step: a
    * caller that has this value knows the clusters. */
  def resource[F[_]: Async: LoggerFactory](
      config: StoreConfig,
      kafka: StoreKafkaConfig,
      crypto: FieldCrypto[F],
      clientId: String
  ): Resource[F, ConfigStore[F]]
```

New `StoreError` cases:

```scala
case ReplayTimeout(topic: String, reached: Long, endOffset: Long, afterMs: Long)
  // ErrorCode.StoreReplayTimeout
case Unreachable(bootstrapServers: String, why: String)
  // ErrorCode.StoreUnavailable
```

`Unreachable.why` is a short classification (`"connection refused"`, `"authentication failed"`,
`"not authorized to describe topic"`), never a driver exception's `toString`, which routinely
carries the client property map.

## Replay, decided precisely

A worker must implement this sequence and no other:

1. Build the consumer from `StoreClients.consumer` (STORE-005) with, on top of the base
   properties: `enable.auto.commit=false`, `auto.offset.reset=earliest`, `isolation.level=read_committed`,
   and **no `group.id`**.
2. **Assign, never subscribe.** `consumer.assign(topic, NonEmptySet.one(0))`. Rationale, and it is
   not stylistic: a consumer group would make replay wait for a rebalance, would let a second
   replica take the partition away from the first, and would mean every replica sees only part of
   the log — the exact opposite of "every replica replays the whole thing". `metadata-store.md`
   §4.1 already tells operators KUI needs no consumer group for this topic.
3. Assert the topic has exactly one partition (`describeTopics` in STORE-005's bootstrap already
   did; re-check here is cheap and turns a silently-half-read log into an error).
4. `consumer.seekToBeginning`. Take `endOffset = consumer.endOffsets(partition)` **once**, before
   consuming. Records produced after that point are the tail follower's business; replay does not
   chase a moving target, which is the other way a replay fails to terminate.
5. If `endOffset == 0` (or equals the beginning offset — a fully compacted, fully truncated log)
   the store is empty and replay is complete immediately. Do not wait for a record.
6. Stream records, folding each through `StoreState.apply`, until `offset >= endOffset - 1`.
7. The whole of steps 4–6 is wrapped in `Async[F].timeout(config.replayTimeout)`; on timeout,
   fail the `Resource` with `StoreError.ReplayTimeout(topic, lastAppliedOffset, endOffset, ms)`.
   The message names all three numbers, because "replayed 40 000 of 41 200 records in 30s" tells
   an operator to raise the timeout and "replay timed out" does not.
8. On success, log one INFO with the record count, the end offset and the elapsed time, then start
   the tail follower with `Supervisor` / `Stream.compile.drain.background`, and only then complete
   the resource's acquisition. **Nothing may observe the store before this point** — that is the
   ordering guarantee CLAPI-005 relies on to gate readiness.

### The tail follower

Consumes from where replay stopped, forever, applying the same fold. It is the only writer of the
state `Ref` (STORE-007's writes go through the producer and come back around through this loop —
that is what makes read-your-writes a real read and not a hopeful local mutation).

- A record that fails to decode is `Unreadable`: WARN with key and reason, recorded in
  `state.unreadable`, and the loop continues. One bad record must not stop the log.
- A record that fails to decrypt is the same, with the `keyId` in the WARN (STORE-002's degraded
  contract).
- A physical tombstone (null value) is a delete at the record's own version — since it carries no
  envelope, it is applied unconditionally and sets the key's version to `nextVersion`. It exists
  only for hand-edited or compaction-produced logs; KUI writes logical tombstones.
- A consumer failure (broker gone) does **not** kill the process: the follower fails, and
  STORE-008 owns the retry and the `Degraded` transition. Until STORE-008 lands, the follower
  logs an ERROR and terminates, leaving reads serving the last state — which is already the
  ADR-042 §8 behaviour, minus the health reporting.

### `changes`

A `Topic[F, StoreChange]` (fs2) published from the fold, subscribers with a bounded buffer of
**256** and an overflow policy of *drop-oldest for that subscriber*. A slow subscriber must never
back-pressure the fold, because the fold is the thing every read depends on. 256 is chosen as
"more than any burst of metadata edits a human can produce, small enough to be free"; a subscriber
that overflows it has lost changes and must re-read from the store, so `StoreChange` consumers are
written to be re-readable — CLADP-005's profile propagation does exactly that.

## Library coordinates

None new beyond STORE-005's `org.typelevel::fs2-kafka::4.0.0`.
`org.typelevel::log4cats-core::2.8.0` for the logging described above.

## Acceptance criteria

```
$ ./mill libs.config.compile
$ ./mill libs.config.test
$ ./mill libs.config.test.testOnly kui.config.store.StoreStateSuite
$ ./mill __.checkFormat && ./mill __.fix --check && ./mill checkArchitecture
```

The behavioural acceptance — replay against a real broker, the timeout, the empty log — is
STORE-009, which owns the container fixture. This task's Implementation Report must state
explicitly whether replay-then-tail terminated against a broker in a scratch run, because it is
the DEVPLAN's named first-mover question (§6.4); if the answer needs a container to produce, run
STORE-009's fixture locally ahead of time rather than merging an unverified loop.

## Tests required

- `StoreStateSuite` (unit + property, no broker — this is where the design is actually tested):
  - `acceptsTheNextVersion` — an empty state accepts version 1; a state at 3 accepts 4.
  - `ignoresAStaleVersion` — the lost-race rule: at version 3, a second version-3 record is
    `Ignored(key, 3, 4)`.
  - `ignoresAFutureVersion` — version 9 against a state at 3 is `Ignored`, not accepted. A gap
    means records were lost or the log was tampered with, and accepting would let a writer skip
    the conflict check by inventing a large version.
  - `twoWritersOnOneKeyConvergeWhicheverOrderIsReplayed` — property: for any interleaving of two
    writers' records **in one fixed log order**, every replica's final state is identical. This is
    the exit criterion "both converge on the winner's record" proved as a property, one layer
    below the integration test that proves it end to end.
  - `tombstoneRemovesTheKeyAndKeepsTheVersion` — `get` is `None` afterwards; `nextVersion` keeps
    counting, so a re-create does not restart at 1 and collide with a record still in the log.
  - `listSkipsTombstonesAndSortsByKey`.
  - `unreadableRecordDoesNotAdvanceTheVersion` — and appears in `unreadable`.
  - `lastAppliedOffsetIsMonotonic` (property).
  - `applyIsPureAndTotal` (property over generated records): never throws, always returns a state.
- `KafkaConfigStoreSuite` — **not created here.** Every assertion about the adapter needs a
  broker and lives in STORE-009's `StoreIntegrationSuite`. Writing a mock-consumer suite here
  would test fs2-kafka's API surface rather than KUI's behaviour, and PLAN §32 has no mocking
  library for exactly that reason.

## Observability

Log lines, all structured, none containing a payload or a property map:

| Level | Event | Fields |
| --- | --- | --- |
| INFO | `store replay started` | `topic`, `endOffset`, `timeoutMs` |
| INFO | `store replay complete` | `topic`, `records`, `accepted`, `ignored`, `unreadable`, `endOffset`, `elapsedMs` |
| ERROR | `store replay timed out` | `topic`, `reached`, `endOffset`, `afterMs` |
| WARN | `store record unreadable` | `key`, `offset`, `reason`, `keyId` when the reason is crypto |
| INFO | `store change applied` | `key`, `version`, `offset` — at INFO because metadata changes are rare and an operator wants them in the log |
| WARN | `store record ignored` | `key`, `recordVersion`, `expectedVersion` — a lost race is normal but worth seeing |

Metrics (registered by STORE-008; named here so both tasks agree):
`kui.store.replay.records` (counter), `kui.store.replay.duration` (histogram),
`kui.store.replay.lag` (gauge: `endOffset - lastAppliedOffset`, which is the metric
`metadata-store.md` §6 already tells operators to watch),
`kui.store.records.ignored` and `kui.store.records.unreadable` (counters).

## Degraded behavior

- **Unreachable at startup** → the resource fails, the service does not start, and the message
  names the bootstrap servers (ADR-042 §8, `metadata-store.md` §6 row 1). Never start with an
  empty registry: "you have no clusters" is indistinguishable, to a user, from "your clusters
  were deleted".
- **Replay does not finish in `replayTimeout`** → `KUI-STORE-REPLAY-TIMEOUT`, same outcome, with
  the three numbers. R-2's mitigation, in one line of code and one error case.
- **The tail dies while running** → reads keep working from the last state; writes are the ones
  that fail. STORE-008 turns this into `StoreHealth.Degraded` and a reconnect.
- **One record is unreadable** → that key is missing and everything else works. Recorded in
  `health.unreadableKeys` so the capability report can say which.
