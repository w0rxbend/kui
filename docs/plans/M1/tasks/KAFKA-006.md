# KAFKA-006 — `libs/kafka`: batching and bounded parallelism from `AdminTuning`

- **ID:** KAFKA-006
- **Title:** `libs/kafka`: batching and bounded parallelism from `AdminTuning`
- **Milestone / Feature:** M1 / CL-001, BR-005, KU-010
- **Owner role:** Principal Scala Engineer
- **Context / service:** `libs/kafka`
- **Size:** M
- **Dependencies / blocked by:** KAFKA-005

## Goal (user value)

A cluster with ten thousand topics and forty brokers answers, instead of timing out. Every
reference product learned the same lesson the same way — one giant `describeConfigs` or
`describeLogDirs` exceeds the request timeout on a large cluster — and every one of them fixed
it by chunking with a bounded number of chunks in flight. KUI writes that once, reads its
numbers from configuration, and makes a failed chunk cost that chunk's keys rather than the
whole answer.

## Scope

1. `AdminBatch.chunked` — split a key list into chunks, run at most `parallelism` at a time,
   merge into one `BatchResult` deterministically, and turn a failed chunk into skipped keys
   rather than a failed call.
2. `AdminBatch.perKey` — the other shape: one call that returns a map of per-key futures
   (`describeConfigs().values()`), where each key can fail on its own.
3. `AdminBatch.perBroker` — a bounded-parallel fan-out over brokers, which is the shape
   `describeLogDirs` needs (the research is explicit that one call for all brokers is the wrong
   way to do it, because one slow disk stalls the request for every broker).
4. `libs/kafka/PORT-INVARIANTS.md` — two behaviours that the research proved are required, that
   have no port to live in yet, and that must not be rediscovered in M2 and M3.

## Non-goals

- No admin calls (KAFKA-007 to KAFKA-009 are the first callers).
- No retry. A failed chunk is a skipped chunk; retrying inside the batcher multiplies the
  timeout by the retry count and is invisible to the metric that measured the call.
- No adaptive chunk sizing, no backpressure heuristics. The numbers come from `AdminTuning`,
  which comes from configuration (CFGOP-002), and a tuning knob nobody can see is worse than a
  wrong constant.
- No topic, group or offset types. This module handles `List[K] => F[BatchResult[K, A]]` for an
  arbitrary `K`, which is why it can be written before its callers exist without violating
  DEVPLAN §3's rule against ports designed ahead of their first caller: this is a combinator,
  not a port.

## Design references

`research/kafka/admin-capabilities.md` §0 rows "Batching" (200 topics / 50 groups, concurrency
4), "Partial failure" and "Single I/O thread", and §1 "Log dirs" ("use `describeLogDirs` per
broker with a bounded parallelism rather than one call for all brokers"); ADR-006 (chunked
batching as an adapter invariant, configurable per cluster); `ARCHITECTURE.md` §4.2 (the
closing paragraph on batching); DEVPLAN §7 (the batching suite row).

## Files to create

```
libs/kafka/PORT-INVARIANTS.md
libs/kafka/src/kui/kafka/AdminBatch.scala
libs/kafka/test/src/kui/kafka/AdminBatchSuite.scala
```

## Files to change

None.

## Public Scala signatures to implement

```scala
package kui.kafka

import cats.Parallel
import cats.effect.Concurrent
import kui.kernel.cluster.AdminTuning

object AdminBatch {

  /** Splits `keys` into chunks of `chunkSize`, runs at most `parallelism` chunks at once, and
    * merges the results.
    *
    * A chunk that fails does not fail the batch: its keys become `skipped` entries carrying the
    * reason `KafkaErrorMapper.suppressible` gives, or `SkipReason.Failed(code, detail)` when
    * the failure is not suppressible. The alternative — one bad chunk failing everything — is
    * how a cluster with one unreachable broker shows an empty broker list.
    *
    * The merge is deterministic: chunks are cut in the order `keys` arrives and merged in that
    * same order, regardless of which finished first. A result that depends on scheduling cannot
    * be asserted against a golden file and cannot be reproduced from a bug report.
    */
  def chunked[F[_]: Concurrent: Parallel, K, A](
      keys: List[K],
      chunkSize: Int,
      parallelism: Int,
      operation: String
  )(run: List[K] => F[Map[K, A]]): F[BatchResult[K, A]]

  /** For a call that returns one future per key, such as `describeConfigs().values()`.
    *
    * Each key succeeds or fails independently. `parallelism` bounds how many are awaited at
    * once — the futures were all issued by one request, so this bounds KUI's own work rather
    * than the broker's, and it matters because the continuations run on the way off the admin
    * client's single I/O thread.
    */
  def perKey[F[_]: Concurrent: Parallel, K, A](
      perKeyEffects: Map[K, F[A]],
      parallelism: Int,
      operation: String
  ): F[BatchResult[K, A]]

  /** A bounded fan-out: one independent call per key, results merged the same way.
    *
    * This is what `describeLogDirs` uses, one call per broker. A single slow or offline disk
    * then costs that broker's entry rather than the whole cluster's
    * (`research/kafka/admin-capabilities.md` §1, "Log dirs").
    */
  def perBroker[F[_]: Concurrent: Parallel, K, A](
      keys: List[K],
      parallelism: Int,
      operation: String
  )(run: K => F[A]): F[BatchResult[K, A]]

  /** The chunk sizes, from configuration rather than from a constant in this file. */
  def topicChunk(t: AdminTuning): Int      // t.topicChunkSize
  def partitionChunk(t: AdminTuning): Int  // t.partitionChunkSize
  def groupChunk(t: AdminTuning): Int      // t.groupChunkSize

  /** Exposed for tests and for a log line: the chunks `chunked` would produce. */
  def chunks[K](keys: List[K], chunkSize: Int): List[List[K]]
}
```

### Rules the implementation must follow

- **Bounded, not unbounded.** Use `parTraverseN(parallelism)`, never `parTraverse`. On a
  forty-broker cluster the difference is forty in-flight requests against a broker that
  configured a connection limit.
- **Cancellation propagates.** Cancelling the batch cancels the chunks in flight and does not
  wait for the rest. `SnapshotCell`'s supervised refresh (KAFKA-010) depends on it.
- **A duplicate key is a programming error**, reported by `combineChecked`, not silently
  deduplicated: a caller that asks about the same broker twice has a bug worth seeing.
- **An empty key list is an empty complete result**, never a call to the broker. Every caller
  eventually passes an empty list — an unauthorized user, a cluster with no log directories —
  and a request with an empty argument list is a request that can only waste a round trip.
- **`operation` is a fixed label** from KAFKA-004's closed set, so log lines and metric
  attributes from a batch match the call that contains it.

## `libs/kafka/PORT-INVARIANTS.md` — what this file must say

Two behaviours are established by `research/kafka/admin-capabilities.md`, are *required* of
ports M1 does not build, and would otherwise be rediscovered as production bugs by whoever
writes those ports in M2 and M3. DEVPLAN §3 forbids declaring empty `TopicAdmin` and
`GroupAdmin` traits to hold them, and it is right to — but the knowledge must not be lost with
the trait. It goes in a file next to the module that will implement them, with a pointer from
each future task.

Write it with these two sections, each stating the behaviour, the evidence and the consequence
of getting it wrong:

1. **Leaderless partitions are filtered before `listOffsets`, at the port.** If any target
   partition has no leader, the AdminClient does not fail — it retries metadata until
   `default.api.timeout.ms` expires. So one offline partition turns a fast call into a
   sixty-second one, and the caller sees a timeout that names nothing useful. Kafbat filters
   no-leader partitions first, and skips the whole topic when one of its partitions is
   leaderless (`research/kafka/admin-capabilities.md` §2, "Topic offsets / message counts").
   KUI's rule: the port filters, and each filtered partition appears in the `BatchResult` as
   `SkipReason.NoLeader` — which is why that case exists in `SkipReason` (KAFKA-005) before any
   code produces it. **Owner: M2, `TopicAdmin.listOffsets`.**
2. **Describing a consumer group that does not exist returns a fabricated dead group, not an
   error.** Older brokers answer `describeConsumerGroups` for an unknown group with a `DEAD`
   description rather than an error, and newer ones throw `GroupIdNotFoundException`
   (`research/kafka/admin-capabilities.md` §3, "Describe groups"). A port that propagates the
   difference makes every caller branch on broker version. KUI's rule: the port normalises to
   the older behaviour — an unknown group is a `GroupDescription` in state `Dead` with no
   members and no assignment — and existence is confirmed with a listing when it actually
   matters, as the reference does before an offset reset. **Owner: M3, `GroupAdmin.describeGroups`.**

Add a third, short section — "Why these are here and not in a trait" — citing DEVPLAN §3 and
risk R-11, so the next reader does not helpfully create the empty traits.

## ADRs this task must obey

ADR-006 (chunked batching, bounded parallelism, per-key `BatchResult` with explicit `Skipped`
reasons and never silent drops — all four are asserted by this task's suite), ADR-016 (nothing
here caches), ADR-002 (cats-effect concurrency primitives only; no thread pools of its own).

## Library coordinates

None new. `cats.Parallel` comes from `cats-core` 2.13.0, already on the module.

## Acceptance criteria

```
$ ./mill libs.kafka.test
$ ./mill libs.kafka.compile        # clean under -Werror
$ test -f libs/kafka/PORT-INVARIANTS.md
$ grep -q "NoLeader" libs/kafka/PORT-INVARIANTS.md
$ grep -q "GroupIdNotFoundException" libs/kafka/PORT-INVARIANTS.md
```

```scala
// 450 keys, chunk 200 -> chunks of 200, 200, 50
assertEquals(AdminBatch.chunks((1 to 450).toList, 200).map(_.size), List(200, 200, 50))

// A failing chunk costs its own keys and nothing else
val result = AdminBatch.chunked[IO, Int, String](keys = List(1, 2, 3, 4), chunkSize = 2,
  parallelism = 2, operation = "describeConfigs") {
  case List(1, 2) => IO.pure(Map(1 -> "a", 2 -> "b"))
  case chunk      => IO.raiseError(new TopicAuthorizationException("no DESCRIBE"))
}.unsafeRunSync()
assertEquals(result.values.keySet, Set(1, 2))
assertEquals(result.skipped.keySet, Set(3, 4))
assert(result.skipped(3).isInstanceOf[SkipReason.NotAuthorized])
assertEquals(result.requested, Set(1, 2, 3, 4))
```

## Tests required

- `AdminBatchSuite` (unit, `munit-cats-effect` + `TestControl`):
  - `chunkSizesComeFromTuning` — a table over `AdminTuning.default` asserting 200 / 200 / 50,
    and a second `AdminTuning` proving the numbers are read, not hard-coded.
  - `chunksArePartitionsOfTheInput` (property): concatenating the chunks gives back the input,
    order preserved, no chunk larger than `chunkSize`, no empty chunk.
  - **`parallelismIsBounded`** — with `parallelism = 4` and eight chunks each taking one second
    of virtual time, the batch finishes in two seconds, and a counter of concurrently running
    chunks never exceeds four. Asserted with `TestControl`, so a change to `parTraverse` fails
    the test rather than merely making production slower.
  - **`aFailedChunkDoesNotFailTheBatch`**, for a suppressible failure and for a non-suppressible
    one (the latter yields `SkipReason.Failed` with the mapped code).
  - `mergeOrderIsDeterministic` — run the same batch with chunk completion in reversed order
    (`TestControl` with different delays) and assert an identical `BatchResult`.
  - `emptyInputCallsNothing` — a `run` function that raises if it is called at all.
  - `cancellationCancelsInFlightChunksAndSkipsTheRest` — assert the number of chunks started.
  - `perKeyIsolatesAKeyFailure` — one key's effect fails, the others still land.
  - `perBrokerIsolatesABrokerFailure` and `perBrokerIsBounded` — the `describeLogDirs` shape,
    with one broker sleeping past the others.
  - `duplicateKeysAreReportedNotDeduplicated`.
  - `everySkippedKeyHasAReason` (property over arbitrary failure patterns) — the invariant
    ADR-006 calls "never silent drops", asserted rather than assumed.

## Observability

No metric of its own; the enclosing admin call is already measured (KAFKA-004). One DEBUG line
per batch under `kui.kafka`: `operation`, `cluster`, the key count, the chunk count, the
parallelism and the skipped count. That line is what tells an operator whose broker list is
missing two rows whether KUI asked and was refused, or never asked.

A batch whose skipped count is more than half its key count logs once at INFO instead of DEBUG.
It usually means an ACL is missing rather than that a cluster is oddly shaped, and it is the
one batching outcome worth noticing without turning on debug logging.

## Degraded behavior

- **Some keys failed:** the documented normal case. `BatchResult` carries both halves and the
  caller renders what it has, which is the milestone's "authenticates but authorizes nothing"
  fault-injection scenario (DEVPLAN §7, scenario 4).
- **Every key failed:** still a `BatchResult`, still a 200 with an empty table and per-row
  reasons — not an error. A cluster where KUI may see nothing is a cluster with an ACL problem,
  and the page that says so is more useful than a 403.
- **A timeout mid-batch:** not suppressible (KAFKA-005), so those keys are `SkipReason.Failed`
  with `KUI-TIMEOUT`. The distinction matters: a caller may not present a timed-out batch as a
  complete answer, and `BatchResult.isComplete` is how it tells.
- **Cancellation:** in-flight chunks are cancelled, remaining chunks are never started, and the
  batch does not produce a partial result — a cancelled refresh must leave the previous
  snapshot in place, not replace it with half a cluster.

## Docs to update

`libs/kafka/PORT-INVARIANTS.md`, created here. Nothing under `docs/` — the M2 and M3 grooming
steps pick this file up through the DEVPLAN's reference to it.

## Deviations

*(filled in by the implementer, in the same commit)*
