# STORE-003 — `ConfigStore[F]` port and the file adapter

- **ID:** STORE-003
- **Title:** `ConfigStore[F]` port and the file adapter
- **Milestone / Feature:** M1 / OT-004, ADR-036 as amended by ADR-042 §5
- **Owner role:** Principal Scala Engineer
- **Context / service:** `libs/config`, package `kui.config.store`
- **Size:** M
- **Dependencies / blocked by:** STORE-001

## Goal (user value)

KUI runs with no Kafka store at all — a GitOps deployment where static files are the whole truth
— and everything except runtime editing works exactly as it does with a store. That is the
milestone exit criterion *"with `kui.store.kafka.*` unset, the file adapter is used, the
store-backed write endpoints report `NotConfigured`, and everything else in M1 still passes"*.
This task defines the port both adapters implement and ships the simple one first, so that every
consumer written after it (CLADP-003, CLDOM-004) is written against a port that already has two
implementations and cannot accidentally assume Kafka.

## Scope

1. The `ConfigStore[F]` port: reads that cannot fail, writes that return a `KuiError`, a change
   stream, and a health value.
2. `StoreChange` and `StoreHealth` as types (their *semantics* under Kafka are STORE-008's).
3. `FileConfigStore`: reads a directory tree of envelope files at construction, serves them from
   memory, refuses every write with `KUI-STORE-NOT-CONFIGURED`, emits an empty change stream.
4. `ConfigStore.empty`: the zero store, for tests and for the "no store configured and no
   directory either" case.

## Non-goals

**No Kafka** (STORE-005 → STORE-008). **No file watching / hot reload**: the file adapter reads
once at construction. ADR-036 keeps the static file as the canonical base loaded by Ciris at
startup, and adding a watcher here would give KUI two reload mechanisms with different
semantics for the same directory. **No writes to files** — ADR-042 §5 calls the file adapter
"dev, bootstrap, read-only" and the exit criterion requires `NotConfigured`, so a write path
would be a feature nobody asked for that also breaks the criterion. **No decryption in the file
adapter**: see the decision below.

## Design references

ADR-036 §"Store" and §"Ownership" (single writer per section; the port stays), ADR-042 §5 and §7
(adapters, and what happens with no `kui.store.kafka.*`), §8 (failure behaviour).
`docs/operations/metadata-store.md` §7 "Running without the store".
ADR-034 (`ErrorCode`), ADR-039 §6 (only infrastructure failures dim a capability — which is why
`NotConfigured` is its own code and not an error).
DEVPLAN §7 row "File adapter", §10 D6.

## Files to create

```
libs/config/src/kui/config/store/ConfigStore.scala
libs/config/src/kui/config/store/StoreChange.scala
libs/config/src/kui/config/store/StoreHealth.scala
libs/config/src/kui/config/store/FileConfigStore.scala
libs/config/test/src/kui/config/store/FileConfigStoreSuite.scala
libs/config/test/resources/store/filestore/cluster/local.json
libs/config/test/resources/store/filestore/settings/global.json
libs/config/test/resources/store/filestore/cluster/broken.json
```

## Public Scala signatures to implement

```scala
package kui.config.store

import cats.effect.{Async, Resource}
import fs2.Stream
import io.circe.Json
import java.time.Instant
import kui.kernel.error.KuiError

/** KUI's own metadata, as a port.
  *
  * **Reads do not fail.** Every implementation serves reads from an in-memory map that was
  * populated before the store was handed to anyone — the file adapter reads its directory in its
  * `Resource`, the Kafka adapter replays its log in its `Resource` (ADR-042 §1's bootstrap
  * order). A read returning `F[Option[...]]` rather than `F[Either[KuiError, Option[...]]]` is
  * therefore not optimism, it is the bootstrap ordering expressed in a type: if the store is
  * unreachable at startup the service does not start, and if it becomes unreachable later reads
  * keep working from the last replayed state (ADR-042 §8). What degrades is `health`, and what
  * fails is a write.
  *
  * **Payloads are plaintext.** A caller hands and receives ordinary JSON; the `$secret` markers
  * of STORE-001 say which strings are sensitive, and the adapter encrypts and decrypts around
  * them. No caller ever sees a `$enc` node. */
trait ConfigStore[F[_]]:

  def get(key: StoreKey): F[Option[StoreRecord]]

  /** Every live (non-deleted) record in a section, in key order. Tombstoned keys are absent. */
  def list(section: StoreSection): F[List[StoreRecord]]

  /** Creates or replaces a record.
    *
    * `baseVersion` is `None` for "this key must not exist yet" and `Some(v)` for "the record I
    * read was at version v". Either way a lost race is `KUI-CONFIG-VERSION-CONFLICT`. The
    * returned record carries the version that was actually written, and — this is the
    * read-your-writes contract of ADR-042 §3 — it is returned only after the write has been read
    * back from the log, so a caller that immediately calls `get` sees at least this version. */
  def put(key: StoreKey, payload: Json, baseVersion: Option[Long], updatedBy: String): F[Either[KuiError, StoreRecord]]

  /** Writes a tombstone. Same versioning rules. Deleting an absent key is a success, not an
    * error: the caller's intent — "this key must not be there" — already holds. */
  def delete(key: StoreKey, baseVersion: Long, updatedBy: String): F[Either[KuiError, Unit]]

  /** Every change this process has applied, whoever wrote it — including this process's own
    * writes and another replica's. Hot (it does not replay history), and it never completes
    * while the store is open. A slow consumer is dropped rather than allowed to stall replay;
    * STORE-008 fixes the buffer and its overflow policy. */
  def changes: Stream[F, StoreChange]

  def health: F[StoreHealth]

object ConfigStore:
  /** A store with nothing in it that refuses every write. Used by tests and by the composition
    * root when neither `kui.store.kafka.*` nor a store directory is configured. */
  def empty[F[_]: Async]: ConfigStore[F]

/** What happened to one key. `Upserted` carries the whole record so a consumer never has to call
  * back into the store to find out what changed — a call that would race with the next change. */
enum StoreChange:
  case Upserted(record: StoreRecord)
  case Deleted(key: StoreKey, version: Long, at: Instant)

  def key: StoreKey

/** What the store can currently do. Reads always work; this says how stale they may be and
  * whether a write would be accepted. */
enum StoreHealth:
  /** Live and caught up. `lastAppliedOffset` is `-1` for a store with no log. */
  case Healthy(lastAppliedOffset: Long, since: Instant)
  /** Reachable no more; reads serve the last replayed state, writes are rejected. */
  case Degraded(reason: String, since: Instant, lastAppliedOffset: Long)
  /** No writable store is configured. Not a failure: the deployment chose this. */
  case ReadOnly(reason: String)

  def writable: Boolean
  /** Keys that are in the log but could not be decoded or decrypted. Empty in `ReadOnly`. */
  def unreadableKeys: List[StoreKey]

/** The read-only adapter over a directory: a mounted ConfigMap, a Secret, a checked-in folder.
  *
  * Layout is `<root>/<section>/<id>.json`, one `StoreRecord` envelope per file, which is exactly
  * what an export from `metadata-store.md` §5 can be reshaped into and exactly what the Kafka
  * adapter writes. One file per key rather than one big document because a Kubernetes ConfigMap
  * mounts as one file per data entry, and because a broken file then costs one key rather than
  * the whole store. */
object FileConfigStore:
  /** Reads the tree once. A missing root is an empty store, **not** an error — "no directory"
    * and "an empty directory" are the same statement about the deployment. A file that is not
    * readable JSON, or whose envelope is unsupported, is skipped with a `WARN` and recorded in
    * `health.unreadableKeys`; one bad file must not stop KUI from starting. A file whose
    * embedded `key` disagrees with its path is skipped the same way. */
  def resource[F[_]: Async: LoggerFactory](root: Path): Resource[F, ConfigStore[F]]
```

## Decisions taken here (no ADR covers them)

1. **The file adapter does not decrypt.** Its files are plaintext JSON; a `$secret` marker stays a
   marker and is returned to the caller as one. Reason: the file adapter's whole point is running
   with no encryption key (`metadata-store.md` §7, R-3's "the file adapter remains a supported way
   to run with no such risk"), so requiring a keyring to read a file would defeat it. The file's
   own confidentiality is the filesystem's job — a mounted Secret, mode 0400 — which is the same
   guarantee the static YAML configuration already relies on for `kui.clusters[].security`.
   A `$enc` node found in a file is left untouched and its key is recorded as unreadable, because
   the alternative — handing a caller a ciphertext as if it were a password — fails at connection
   time with an unexplainable authentication error.
2. **Writes fail with `KUI-STORE-NOT-CONFIGURED` (501), not 403 or 405.** It is a statement about
   the deployment, not about the caller or the resource, and the UI has to render it as
   `NotConfigured` (ADR-032), which is keyed off this code.
3. **`delete` on a missing key succeeds.** Idempotent deletes make the M8 wizard's retry logic and
   any GitOps reconciliation loop trivial, and there is no caller who can act on the difference.
4. **`list` takes a `StoreSection`, not a free-text prefix.** A prefix scan over a `Map` is the
   same code, but a typed section makes "list every cluster" impossible to spell wrongly and
   keeps the port's surface closed over STORE-001's enum.

## Library coordinates

None new. `cats-effect 3.7.1`, `fs2-core 3.13.0` and `circe-parser 0.14.16` are already on
`libs/config`; `java.nio.file` reads the directory inside `Sync[F].blocking`, matching CFG-001's
recorded deviation 2 (no `fs2-io` in this module for a handful of small startup reads).
`org.typelevel::log4cats-core::2.8.0` is added to `libs/config`'s `mvnDeps` if it is not there —
check `build.mill` first; the module already depends on `libs.kernel.jvm` only, so if the
coordinate is absent, add exactly that one line and nothing else.

## Acceptance criteria

```
$ ./mill libs.config.compile
$ ./mill libs.config.test
$ ./mill checkArchitecture
$ ./mill __.checkFormat && ./mill __.fix --check
```

Behavioural acceptance, by hand:

```
$ ./mill libs.config.test.testOnly kui.config.store.FileConfigStoreSuite
+ readsEveryEnvelopeInTheTree
+ missingRootIsAnEmptyStore
+ brokenFileIsSkippedAndRecorded
+ writesReportNotConfigured
...
```

## Tests required

- `FileConfigStoreSuite` (unit, `munit-cats-effect`):
  - `readsEveryEnvelopeInTheTree` — the two good fixtures come back from `get` and `list`.
  - `listReturnsKeyOrderAndSkipsTombstones` — add a `deleted: true` fixture in a temp directory.
  - `missingRootIsAnEmptyStore` — no exception, `health` is `ReadOnly`, `list` is empty.
  - `brokenFileIsSkippedAndRecorded` — `cluster/broken.json` (invalid JSON) leaves the other keys
    readable and appears in `health.unreadableKeys`.
  - `fileWhosePathDisagreesWithItsKeyIsSkipped`.
  - `unsupportedEnvelopeVersionIsSkippedNotFatal`.
  - `writesReportNotConfigured` — `put` and `delete` both give a `KuiError` whose
    `code.wire == "KUI-STORE-NOT-CONFIGURED"`.
  - `changesIsEmptyAndDoesNotTerminateTheConsumer` — `changes.take(1).timeout(200.millis)`
    fails with a timeout rather than completing, asserted with `TestControl` where possible and
    a real short timeout otherwise; the point is that a consumer of `changes` written against
    the Kafka adapter behaves identically here.
  - `secretMarkerIsReturnedAsAMarker` — decision 1, asserted.
  - `cipherNodeIsLeftAloneAndRecordedAsUnreadable` — decision 1's second half.
  - `emptyStoreSatisfiesTheSameContract` — the same table of assertions run against
    `ConfigStore.empty`, so the zero value is not a special case anybody has to remember.

## Observability

At construction the file adapter logs one INFO: the root path, the number of records read and
the number skipped. One WARN per skipped file, naming the path and the reason and **not** the
contents. No log line ever contains a payload (STORE-001's rule).

## Degraded behavior

The file adapter has no runtime failure mode: it holds no connection. `health` is permanently
`ReadOnly("no kui.store.kafka.* configured")`, which is what CLDOM-007's capability report folds
into `NotConfigured` for the store-backed write capability while leaving every read capability
`Available`.

## Docs to update

`docs/operations/metadata-store.md` §7: add the directory layout (`<root>/<section>/<id>.json`),
the `kui.store.dir` key name STORE-004 introduces, and the sentence that a broken file costs one
key rather than the store.
