# STORE-009 — Store integration suite against a Testcontainers broker

- **ID:** STORE-009
- **Title:** Store integration suite against a Testcontainers broker
- **Milestone / Feature:** M1 / OT-004, ADR-042 (the whole ADR, as a test)
- **Owner role:** Principal Scala Engineer (with QA Engineer review)
- **Context / service:** `libs/config` test module
- **Size:** L
- **Dependencies / blocked by:** STORE-008, CFGOP-004

## Goal (user value)

Six of the milestone's exit criteria are about the metadata store, and every one of them is a
claim about behaviour against a real broker: topics created, an incompatible topic refused,
replay bounded, two writers racing, read-your-writes, and **no plaintext secret in the topic**.
This task is where those claims become commands in CI that fail when they stop being true.

## Scope

One integration suite, `StoreIntegrationSuite`, running against the PLAINTEXT Kafka container
`libs/testkit` provides (CFGOP-004), asserting the full ADR-042 contract end to end.

## Non-goals

**No SASL or SSL store cluster.** The store's connection uses the same `ClusterSecurity` ADT and
the same `KafkaClientFactory.baseProperties` as a managed cluster, and CFGOP-005's parity suite
already proves those three modes are equivalent through a live broker; proving it twice costs two
more containers per CI run for no new information. The store therefore runs on PLAINTEXT here,
and `docs/operations/metadata-store.md` §1's SASL_SSL example remains supported-by-construction
rather than integration-tested. **This is a deliberate coverage gap and CFGOP-008 must record it
in `TECH_DEBT.md`.** **No cluster service, no HTTP.** This suite talks to `ConfigStore[F]`
directly. The store seen through `PUT /internal/v1/clusters/{id}` is CLAPI-009's test.
**No multi-broker cluster**: RF 1, one container. `min.insync.replicas` behaviour with a broker
down is a Kafka property, not a KUI one.

## Design references

ADR-042 in full. `docs/operations/metadata-store.md` §2 (the exact incompatibility message), §5
(the console-consumer dump this suite reproduces programmatically), §6 (the failure table).
DEVPLAN §2 exit criteria 6–11, §7 rows "Store, integration" and "Crypto", §8 R-2, R-3, R-12.
ADR-018 (MUnit only, no mocking, fakes in `libs/testkit`).
CFGOP-004's `libs/testkit` fixture is the container source; **do not** start a container from a
locally written `GenericContainer`.

## Files to create

```
libs/config/test/src/kui/config/store/StoreIntegrationSuite.scala
libs/config/test/src/kui/config/store/StoreTestFixtures.scala
```

## Files to change

```
build.mill      (libs.config.test: moduleDeps += testkit.jvm, if STORE-005 did not already)
```

## Cross-area contract with CFGOP-004

This suite consumes, and does not define:

```scala
package kui.testkit.kafka

/** A single-broker PLAINTEXT Kafka container shared by every suite in one Mill module, started
  * once (`ResourceSuiteLocalFixture`) rather than once per suite. */
object KafkaContainers:
  def plaintext: Resource[IO, KafkaEndpoint]

final case class KafkaEndpoint(bootstrapServers: BootstrapServers, security: ClusterSecurity)
```

If CFGOP-004 has not landed, this task is blocked (the DEVPLAN's edge says so). Do not fork a
container fixture; two container definitions in one repository is how CI time doubles quietly.

**Isolation between tests.** Every test gets its own `topicPrefix`, generated as
`s"__kui_it_${UUID.randomUUID().toString.take(8)}_"`. That is cheaper and far more reliable than
deleting topics between tests, and it makes a failing test's leftover topic inspectable. The
fixture deletes the prefixed topics at suite teardown on a best-effort basis.

## Tests required

Each test below maps to a named exit criterion or ADR clause; the mapping is written in the test's
own comment so a future reader knows what breaks if they delete it.

### Topic bootstrap (exit criterion 6, first half; STORE-005)

- `createsBothTopicsWhenMissing` — after `KafkaConfigStore.resource`, `describeTopics` shows
  `<prefix>config` and `<prefix>files`, each with **one** partition and `cleanup.policy=compact`.
- `doesNotCreateTheAuditTopic` — DEVPLAN §10 D7, asserted, so that "we forgot" and "we decided"
  stay distinguishable.
- `isIdempotent` — building the store twice against the same prefix succeeds and creates nothing
  the second time.
- `concurrentBootstrapFromTwoStoresBothSucceed` — two `resource`s acquired in parallel; the
  `TopicExistsException` path.
- `existingTopicWithCleanupPolicyDeleteFailsStartup` — **the named exit criterion.** Pre-create
  `<prefix>config` with `cleanup.policy=delete`, then acquire the resource; assert the failure is
  `StoreError.TopicIncompatible`, that its `ErrorCode.wire` is `KUI-STORE-TOPIC-INCOMPATIBLE`, and
  that the rendered message contains the topic name, the literal `cleanup.policy`, `expected
  compact` and `delete`.
- `existingTopicWithThreePartitionsFailsStartup` — same, with `setting = "partitions"`,
  `expected 1`, `found 3`.
- `existingTopicWithTooSmallMaxMessageBytesFailsStartup` — on `<prefix>files`.
- `advisoryDifferenceLogsAndStarts` — `segment.ms` set to something else; the store starts.

### Replay (exit criterion 6, second half; STORE-006; risk R-2)

- `replaysAnEmptyLogImmediately` — a fresh prefix; acquisition completes well inside
  `replayTimeout`, `list` is empty. Guards the `endOffset == 0` case, which is the most likely
  place for a replay to hang forever waiting for a record that will never come.
- `replaysRecordsWrittenByAnotherProcess` — produce 200 envelopes with a raw producer, then build
  the store; all 200 are readable, `lastAppliedOffset` is 199.
- `replayStopsAtTheEndOffsetItTookAtTheStart` — produce continuously while replay runs; assert
  acquisition completes and does not chase the tail.
- `replayTimeoutIsNamedAndBounded` — `replayTimeout = 1.second` against a log with enough records
  (or a broker made unresponsive by pausing the container) that it cannot finish; assert
  `StoreError.ReplayTimeout` with the topic and both offsets, and assert the whole test takes
  under 10 seconds. **This is R-2's mitigation as an executable assertion.**
- `unreachableStoreAtStartupFailsWithTheBootstrapServers` — point at a closed port; assert the
  failure message contains the bootstrap servers and no client property.
- `oneUnreadableRecordDoesNotStopReplay` — hand-produce a record with `envelopeVersion: 99` and
  one with malformed JSON; the other records are readable, both keys are in
  `health.unreadableKeys`, and the store is `Healthy`.

### Writes, concurrency, read-your-writes (exit criteria 7 and 8; STORE-007)

- `writeIsVisibleImmediatelyAfterItReturns` — `put` then `get` in the same fiber returns the new
  version, with **no sleep anywhere in the test**. A sleep here would hide exactly the bug the
  criterion exists to catch.
- `writeReturnsOnlyAfterTheRecordIsInTheLog` — after `put` returns, a *separate raw consumer*
  reading the topic from the beginning finds the record. This is the exit criterion "a write
  returns 200 only after the writer has read its own record back from the log tail" at the layer
  that can prove it.
- `twoStoresRacingOnOneKeyGiveOneWinnerAndOneConflict` — **the named exit criterion.** Two
  independently constructed `KafkaConfigStore` instances (two "replicas") on the same topic, both
  reading version *n*, both calling `put` with `baseVersion = n`, started with
  `IO.both`. Assert exactly one `Right`, exactly one `Left` whose `code.wire` is
  `KUI-CONFIG-VERSION-CONFLICT`, and then — the second half of the criterion —
  **both stores converge**: after both have caught up, `store1.get(key) == store2.get(key)` and
  it is the winner's payload. Repeat the whole race 20 times in one test so that a
  timing-dependent implementation fails rather than passing four times out of five.
- `staleBaseVersionIsRejectedWithoutProducing` — the pre-check; assert the topic's end offset did
  not move.
- `creatingAKeyThatExistsIsAConflict` — `baseVersion = None` against an existing key.
- `deleteTombstonesAndTheKeyDisappears` — `get` is `None`, `list` omits it, and a raw consumer
  sees a record with `deleted: true`.
- `recreateAfterDeleteContinuesTheVersionSequence` — the version does not restart at 1.
- `changesStreamSeesAnotherReplicasWrite` — subscribe on store 1, write on store 2, assert the
  `Upserted` arrives with the right version inside 5 seconds.

### Secrets (exit criterion 9; STORE-002; risk R-12)

- `rawTopicDumpContainsNoPlaintextSecret` — **the milestone's security criterion.** Write a
  cluster payload whose every secret field is a distinctive token
  (`"KUI-LEAK-CANARY-<uuid>"`), including a SASL password, a keystore password and a
  `sasl.jaas.config`-shaped string. Then consume the topic with a **raw byte consumer** —
  `Deserializer.identity`, no KUI code between the broker and the assertion — and assert that no
  canary token appears in any record's key or value, as bytes and as UTF-8. Also assert the same
  for the whole record set concatenated, so that a token split across two fields cannot pass.
- `encryptedRecordIsReadableBackThroughTheStore` — the same record read through `get` has the
  canary tokens back, proving the test above is testing encryption and not a dropped field.
- `recordEncryptedUnderAnOldKeyIsReadableAfterRotation` — write with a keyring active on `k1`,
  rebuild the store with `k2` active and `k1` still present, read it back. Then rebuild with `k1`
  removed and assert the key is in `unreadableKeys` and the store is still `Healthy` — R-3's
  "fails loudly rather than producing garbage", and the reason `metadata-store.md` §4.2 tells
  operators not to drop the old key before a rekey.
- `noLogLineContainsACanary` — capture the log output for the whole of the write-and-replay
  sequence with a capturing `LoggerFactory` from `libs/testkit`, and assert no canary token
  appears. This is R-12's third layer, at the layer where the plaintext actually exists.

### Degraded and recovery (exit criterion 11; STORE-008)

- `storeUnreachableMidRunKeepsServingAndRecovers` — the fault-injection scenario 3 of DEVPLAN §7,
  in one test:
  1. build the store, write two clusters;
  2. `container.stop()` (or pause);
  3. assert every previously written key is still readable, unchanged;
  4. assert `health` becomes `Degraded` with a non-empty reason within 15 seconds;
  5. assert `put` fails with `KUI-STORE-UNAVAILABLE`;
  6. `container.start()` (same host port; the fixture must fix the port for this test — note it
     in CFGOP-004's contract if it does not already);
  7. assert `health` returns to `Healthy` and a subsequent `put` succeeds, **with no rebuild of
     the store value**. That last clause is the whole point: recovery without a restart.
- `degradedSinceIsStickyAcrossRetries` — sampled during step 4's window.

### File-adapter parity (exit criterion 10)

- `fileAdapterSatisfiesTheSameReadContract` — the same table of read assertions as the Kafka store,
  run against `FileConfigStore` over a directory written from the same fixtures, plus
  `put` giving `KUI-STORE-NOT-CONFIGURED`. Written as one shared `checkReadContract(store)`
  helper called twice, so the two adapters cannot drift.

## Library coordinates

```
com.dimafeng::testcontainers-scala-munit::0.44.1     (test)
com.dimafeng::testcontainers-scala-kafka::0.44.1     (test)
org.testcontainers:testcontainers-kafka:2.0.5        (test)
org.scalameta::munit::1.3.6
org.typelevel::munit-cats-effect::2.2.0
org.scalacheck::scalacheck::1.20.0
```

All already in DEPENDENCY_MATRIX.md; STORE-005 added the first three to `libs.config.test`.

## Acceptance criteria

```
$ ./mill libs.config.test
$ ./mill libs.config.test.testOnly kui.config.store.StoreIntegrationSuite
```

Expected: every test above green, and the whole suite completing in **under four minutes** on CI
with one shared container. If it does not, the fix is fewer containers, not a longer budget —
`libs.config.test` is on the critical path of every developer's `./mill __.test`.

```
$ ./mill __.test          # JVM; green
$ ./mill checkArchitecture
```

## Observability

The suite asserts the observability contract rather than adding to it:
`noLogLineContainsACanary` above, and one assertion that `store replay complete` was logged with
a `records` field, so that the log line operators are told to look for in
`docs/operations/metadata-store.md` cannot be removed silently.

## Degraded behavior

Of the suite itself: it is tagged so it can be excluded on a machine with no Docker
(`munit.Tag("integration")`, the tag `libs/testkit` already defines for E2E), and it must skip
with a clear message rather than fail when Docker is absent — a contributor without Docker still
needs `./mill libs.config.test` to be usable.

## Docs to update

`STATUS.md` is CFGOP-008's. This task updates nothing on its own, but its Implementation Report
must record, for `docs/spikes/`, the measured replay time for 200 and for 20 000 records — ADR-042's
Consequences section is required by DEVPLAN §9.10 to record "what the implementation learned about
replay timing", and this suite is the only place that number exists.

## Cancellation and shutdown (added at the M1 gate review, F-07)

The M0 review found cancellation systematically unconsidered across the milestone. This task
owns the integration evidence for all of the above, so it owns the answer here. State it in the spec's own words in the
Implementation Report, and ship the tests below.

- Add one named test to the suite: with the store running and a write in flight, cancel the
  application `Resource`; the process exits within its shutdown budget, the consumer and
  producer are closed, and restarting replays cleanly with no duplicate or missing record.
