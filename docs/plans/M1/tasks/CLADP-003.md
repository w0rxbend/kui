# CLADP-003 — `ClusterConfigStore` adapter over `ConfigStore[F]`

- **ID:** CLADP-003
- **Title:** `ClusterConfigStore` adapter over `ConfigStore[F]`
- **Milestone / Feature:** M1 / OT-004, CL-007, KU-010, KU-011
- **Owner role:** Domain Architect (Cluster Registry context)
- **Size:** M
- **Dependencies / blocked by:** CLADP-001, STORE-007 (`ConfigStore[F]` with optimistic
  versioning, read-your-writes and conflict detection)

## M1 gate review amendment — `ClusterConfigStore.changes` is now `onChange`

**F-02, blocker, fixed.** Rule A1 was **not** widened to allow `co.fs2::fs2-core` in
`services/cluster/domain` (see [ADR-041 Amendment 3](../../../adr/ADR-041-layering-rules-machine-enforced.md)),
so `ClusterConfigStore` has no `changes: Stream[F, List[ClusterProfile]]`. It has
`onChange(handler: List[ClusterProfile] => F[Unit]): F[F[Unit]]`, returning the deregistration
action. Wherever this spec subscribes to `changes`, register a handler instead; wherever it
consumes a stream, the stream now lives on `ClusterRegistry` in `application`, which may hold
fs2. Nothing else about the behaviour, the backoff or the reconcile logic changes.

## Goal (user value)

A cluster registered at runtime survives a restart, and two replicas of the cluster service
cannot both win a write. This task is the piece of that which belongs to the cluster context: the
translation between the domain's `ClusterProfile` and the store's generic
`StoreRecord` envelope under the key `cluster/<clusterId>`, and the translation of the store's
failures into the `KuiError` values the API already knows how to serve —
`KUI-CONFIG-VERSION-CONFLICT` above all.

It sits on the milestone's critical path (DEVPLAN §6.3) because CLADP-005 and then the whole
`services/cluster/app` bootstrap depend on it.

## Scope

1. **`ClusterConfigStoreAdapter`** implementing the domain's `ClusterConfigStore[F]` port
   (CLDOM-003 — read its committed signatures first) over `kui.config.store.ConfigStore[F]`
   (STORE-003/STORE-007). The port has five members and this task implements all five:
   - `list: F[Either[KuiError, List[ClusterProfile]]]` — every `cluster/*` key, decoded, in key
     order, each profile carrying its `ProfileVersion`. An empty store is `Right(Nil)`: a normal
     first start, not an error. A record that fails to decode is **not** fatal: it is skipped,
     logged once at ERROR with the key and the decode failure, and counted in `health`. A single
     malformed record must not stop the service from starting with the other nine clusters; a
     startup that dies on one bad row is an outage caused by a typo.
   - `get(id): F[Either[KuiError, Option[ClusterProfile]]]` — `Right(None)` for an absent key,
     distinguished from a decode failure, which is a `Left`.
   - `put(profile, expected: ProfileVersion): F[Either[KuiError, ClusterProfile]]` — writes with
     the base version and returns the stored profile carrying its **new** version, only after the
     write is readable back from the log tail (ADR-042 §3). The domain states read-your-writes as
     a contract; this adapter is where it is honoured, and `ConfigStore` (STORE-007) is where the
     waiting actually happens — do not implement a second read-back loop on top of it.
   - `changes: fs2.Stream[F, List[ClusterProfile]]` — the **current full list once on subscribe**,
     then one element per store change. Emitting whole lists rather than deltas is CLDOM-003's
     choice and it is the right one: a subscriber never has to also call `list` and reconcile the
     race between the two. Built by `scan`ning the store's key-level change feed over the map this
     adapter already holds; see CLADP-005, which is the only consumer in M1.
   - `health: F[StoreHealth]` — the domain's three-case enum (`Online`, `Degraded(reason, since)`,
     `NotConfigured`), **not** `libs/config`'s richer health type, which rule A1 forbids the
     domain seeing. Projecting one onto the other is this adapter's job. Never fails.

   There is **no `delete`** in M1: CLDOM-003 does not declare one, the one write endpoint
   (DEVPLAN §10 D6) is a `PUT`, and a tombstone path with no caller is a mutation with no audit.
   Deletion arrives with the M8 wizard.
2. **`ClusterRecordCodec`** — the explicit Circe codec for the *cluster section payload*
   (ADR-007: no `deriveCodec` on anything that crosses a wire or a log). This is the payload
   inside `StoreRecord`, not the envelope; the envelope, its `envelopeVersion` and its `keyId`
   belong to STORE-001 and must not be re-implemented here.
3. **Secret handling.** The profile's secret fields (`Secret[String]` passwords, JAAS material,
   keystore references, OAuth client secrets) are marked for encryption by naming them to the
   store's `FieldCrypto` boundary (STORE-002) — this adapter passes them through the mechanism
   `libs/config` provides and **never** encrypts anything itself. There is one AES-GCM
   implementation in KUI and it is not in this module. The adapter's own obligation is narrower
   and testable: the JSON it hands the store must have every secret in the field shape
   `FieldCrypto` recognises, and `ClusterRecordCodec` must never encode a `Secret[A]` as
   plaintext through some other path.
4. **Error mapping**, one table, in one file:

   | Store outcome | `KuiError` | Wire code | Why |
   | --- | --- | --- | --- |
   | version conflict on write | `ApplicationError.Conflict` carrying `ErrorCode.ConfigVersionConflict` | `KUI-CONFIG-VERSION-CONFLICT` | the exit criterion names this code; the loser of the two-replica race sees exactly this |
   | store not configured (file adapter, write attempted) | `ApplicationError.Unsupported("cluster configuration store")` | per `ErrorCode` | ADR-042 §7: with no `kui.store.kafka.*`, writes report `NotConfigured` |
   | store unreachable | `InfrastructureError.Unreachable("kui-store", cause)` | — | ADR-042 §8: reject writes rather than lose them |
   | read-back timeout after a write | `InfrastructureError.Timeout("store.readBack", ms)` | `KUI-TIMEOUT` | a write that cannot be confirmed is not a success |
   | payload decode failure | `ApplicationError.Invalid` with the key in the message | `KUI-VALIDATION` | it is bad data, not a broken store |
   | unknown `envelopeVersion` | `ApplicationError.Unsupported` | — | STORE-001 requires a named error, never a silent skip |

   If STORE-007 already returns typed store errors (it should — read its file first), this table
   is a `match` over that ADT and nothing more. Do not catch `Throwable` here.
5. **`StoreHealth` projection.** `Online` when the store has replayed and is following the tail;
   `Degraded(reason, since)` when it is serving last known state and refusing writes, with a
   *sticky* `since` (ADR-039: the timestamp of the first failure of the current outage, not of the
   most recent one — a `since` that resets on every retry makes "degraded for 40 minutes"
   unreadable); `NotConfigured` when the file adapter is in use, which per ADR-039 §2 is not a
   health verdict and must never render as broken. It is the input CLDOM-007's
   `CapabilityReportUseCase` folds.

## Non-goals

- **No store internals.** No topic bootstrap, no replay loop, no producer, no AES-GCM, no
  envelope. All of that is lane B under `libs/config/src/kui/config/store/**`, which this task
  must not edit (DEVPLAN §6.5).
- **No registry.** Overlaying store records on static configuration is `ClusterRegistry`
  (CLDOM-004), in `application`. This adapter returns records; it does not decide precedence.
- **No tail subscription.** Reacting to a change is CLADP-005.
- **No HTTP.** The write *endpoint* is CLAPI-009.
- **No `settings/global`, no `rbac/roles`, no `masking/*`.** This adapter owns the `cluster/`
  key prefix and nothing else; ADR-036's single-writer-per-section rule is enforced by nobody
  writing outside their prefix.

## Design references

- ADR-042 §2 (keys: `cluster/<clusterId>`), §3 (consistency, optimistic `version`,
  read-your-writes, `KUI-CONFIG-VERSION-CONFLICT`, tombstone deletion), §4 (secrets at rest,
  `keyId`, rotation), §5 (the port and its two adapters), §7 (no `kui.store.kafka.*` ⇒ file
  adapter ⇒ writes report `NotConfigured`), §8 (unreachable store: last known state, `Degraded`,
  reject writes).
- ADR-036 (ownership: the cluster service owns `kui.clusters[]` and is its single writer).
- ADR-007 (explicit codecs), ADR-034 (`ErrorCode`, `ErrorEnvelope.statusOf` is the only
  code-to-status mapping — this adapter picks a code, never a status).
- ADR-039 §6 (only `InfrastructureError` dims a capability: a decode failure must not).
- DEVPLAN §10 decisions D6 (one write endpoint exists in M1) and D7 (two topics, three
  documented).

## Files to create

```
services/cluster/infrastructure/src/kui/cluster/infrastructure/store/ClusterConfigStoreAdapter.scala
services/cluster/infrastructure/src/kui/cluster/infrastructure/store/ClusterRecordCodec.scala
services/cluster/infrastructure/src/kui/cluster/infrastructure/store/StoreErrorMapping.scala
services/cluster/infrastructure/test/src/kui/cluster/infrastructure/store/ClusterConfigStoreAdapterSuite.scala
services/cluster/infrastructure/test/src/kui/cluster/infrastructure/store/ClusterRecordCodecSuite.scala
services/cluster/infrastructure/test/src/kui/cluster/infrastructure/store/StoreErrorMappingSuite.scala
services/cluster/infrastructure/test/src/kui/cluster/infrastructure/store/StubConfigStore.scala
services/cluster/infrastructure/test/resources/golden/cluster-record.json
```

## Files to change

```
build.mill      (the test module's `mvnDeps`: circe-parser, if not already inherited)
```

## Public Scala signatures to implement

```scala
package kui.cluster.infrastructure.store

import cats.effect.kernel.Async
import kui.cluster.domain.{ClusterConfigStore, ClusterProfile, ProfileVersion, StoreHealth}
import kui.config.store.{ConfigStore, StoreKey}
import kui.kernel.ClusterId
import kui.kernel.error.KuiError

/** The cluster context's view of the metadata store: profiles under `cluster/<id>`, nothing else.
  *
  * The generic store speaks keys and JSON envelopes with a version. This adapter is the only
  * place that knows a cluster profile is what is inside one of them, and it is deliberately
  * narrow: it owns the `cluster/` prefix, and a bug here cannot corrupt `settings/`, `rbac/` or
  * `masking/`.
  */
final class ClusterConfigStoreAdapter[F[_]: Async](
    store: ConfigStore[F],
    logger: org.typelevel.log4cats.StructuredLogger[F],
    telemetry: kui.observability.Telemetry[F]
) extends ClusterConfigStore[F] {

  /** Every stored profile, in key order. Undecodable records are skipped and counted, not fatal.
    * An empty store is `Right(Nil)`.
    */
  def list: F[Either[KuiError, List[ClusterProfile]]]

  def get(id: ClusterId): F[Either[KuiError, Option[ClusterProfile]]]

  /** Returns the stored profile with its **new** version, after the write is readable back from
    * the log tail (ADR-042 §3) — `ConfigStore` does the waiting, this adapter does not repeat it.
    * `ProfileVersion.Initial` (or whatever CLDOM-001 calls "not yet stored") is the `expected`
    * value for a create.
    */
  def put(profile: ClusterProfile, expected: ProfileVersion): F[Either[KuiError, ClusterProfile]]

  /** The current full list once on subscribe, then one element per store change. Whole lists, not
    * deltas, so a subscriber never has to reconcile against a separate `list` call.
    */
  def changes: fs2.Stream[F, List[ClusterProfile]]

  /** What the capability fold reads (CLDOM-007). Never fails. */
  def health: F[StoreHealth]
}

object ClusterConfigStoreAdapter {

  /** The store key for a cluster. `cluster/<clusterId>`, and `ClusterId` is already a slug
    * (ADR-031), so no escaping is needed and none is invented.
    */
  def keyFor(id: ClusterId): StoreKey

  /** The inverse, for reading a listing back. `None` for a key outside this prefix — which is
    * how `settings/`, `rbac/` and `masking/` records are filtered out of everything here.
    */
  def clusterIdOf(key: StoreKey): Option[ClusterId]

  val KeyPrefix: String = "cluster/"
}
```

```scala
package kui.cluster.infrastructure.store

import io.circe.{Decoder, Encoder}
import kui.cluster.domain.ClusterProfile

/** The stored shape of a cluster profile. Explicit, per ADR-007: `deriveCodec` on a type with
  * secrets in it is how a password ends up in a topic in a field nobody meant to serialize.
  *
  * This encodes the *payload*. The envelope around it — `envelopeVersion`, `version`, `keyId`,
  * `updatedAt`, `updatedBy` — is `kui.config.store.StoreRecord` and is not restated here.
  */
object ClusterRecordCodec {
  given Encoder[ClusterProfile] = ???
  given Decoder[ClusterProfile] = ???
}
```

```scala
package kui.cluster.infrastructure.store

import kui.kernel.error.KuiError

/** The one place a store failure becomes a `KuiError`. One table, matched exhaustively over the
  * store's own error ADT, so that a new store error is a compile error here rather than a 500 in
  * production.
  */
object StoreErrorMapping {
  def toKuiError(clusterId: Option[kui.kernel.ClusterId], error: kui.config.store.StoreError): KuiError
}
```

`ConfigStore`, `StoreKey` and `StoreError` are STORE-003/STORE-007's; read those files and follow
their names exactly. `ProfileVersion` is the domain's (CLDOM-001); the store's own version — an
offset, or whatever STORE-007 chose — is mapped onto it here and the domain never learns what it
is made of. If the store's error channel is not a named ADT but
`Either[KuiError, *]` already, `StoreErrorMapping` shrinks to the conflict and not-configured
cases and the file stays — the mapping table above is still the contract this task must satisfy.
`StoreHealth` and `ClusterConfigStore` are CLDOM-003's and are quoted above from its committed
spec; same rule if they moved.

## Library coordinates

No new main-scope coordinate — `libs.config` is already a `moduleDep` of this module from
CLADP-001, and circe arrives with it. The test module needs
`mvn"io.circe::circe-parser::${Versions.circe}"` (0.14.16) for the golden-file assertion if it is
not already inherited, and `libs.testkit.jvm` for `Golden` and `RedactionAssertions`, which it
already has.

## Acceptance criteria

```
$ ./mill services.cluster.infrastructure.test
Test run kui.cluster.infrastructure.store.ClusterConfigStoreAdapterSuite finished: 0 failed, 0 ignored, 10 total
Test run kui.cluster.infrastructure.store.ClusterRecordCodecSuite finished: 0 failed, 0 ignored, 4 total
Test run kui.cluster.infrastructure.store.StoreErrorMappingSuite finished: 0 failed, 0 ignored, 6 total
SUCCESS

$ ./mill checkArchitecture
checkArchitecture: 36 modules, no layering violations
```

The golden document, `test/resources/golden/cluster-record.json`, is the payload of a profile
whose every secret is the distinctive token `SUPERSECRET-DO-NOT-LEAK`. It is committed, and the
suite asserts both directions plus the absence of that token:

```json
{
  "id": "prod-eu",
  "name": "Production EU",
  "bootstrapServers": "kafka-1.example.com:9093,kafka-2.example.com:9093",
  "security": {
    "type": "saslSsl",
    "mechanism": "SCRAM-SHA-512",
    "username": "kui",
    "password": { "keyId": "k1", "ciphertext": "<base64>", "nonce": "<base64>" }
  },
  "properties": {},
  "readOnly": false
}
```

The exact field set follows CLDOM-001's `ClusterProfile`; the shape that matters and is not
negotiable is that no secret appears as a bare string anywhere in this document.

## Tests required

`ClusterConfigStoreAdapterSuite` (against `StubConfigStore`, an in-memory `ConfigStore[IO]` with a
`Ref`-held map and a version counter — no Kafka; the live store is STORE-009's and CFGOP-005's):

- `listReturnsOnlyClusterKeys` — the stub also holds `settings/global` and `rbac/roles`; neither
  appears.
- `listSkipsAnUndecodableRecordAndReportsItInHealth` — nine good records and one malformed; the
  list has nine entries, `health` reports one decode failure, and the result is `Right`.
- `getDistinguishesAbsentFromUndecodable` — `Right(None)` versus `Left(Invalid)`.
- `emptyStoreListsAsRightNil` — a first start is not an error.
- `putWithTheCurrentVersionSucceedsAndReturnsTheProfileWithItsNewVersion`
- `putWithAStaleVersionIsAConflictWithTheDocumentedCode` — asserts
  `ErrorCode.ConfigVersionConflict.wire == "KUI-CONFIG-VERSION-CONFLICT"`, the string the exit
  criterion names.
- `putWithNoStoreConfiguredIsNotConfiguredAndNotAFailure` — the stub in file-adapter mode.
- `putWhileTheStoreIsUnreachableIsRejectedAndNotBuffered` — the returned error is an
  `InfrastructureError`, and the stub recorded no write.
- `changesEmitsTheCurrentListOnSubscribeAndThenOncePerChange`
- `healthNeverFails` — the stub raises on every call; `health` still answers, `Degraded`.

`ClusterRecordCodecSuite`:

- `profileMatchesTheGoldenDocument` (both directions, via `kui.testkit.Golden`).
- `everySecretIsAbsentFromTheEncodedJson` — encode a profile whose every secret is
  `SUPERSECRET-DO-NOT-LEAK`, assert with `RedactionAssertions.assertNoLeak` over the printed JSON.
  This is the adapter-level half of the exit criterion "a console-consumer dump of `__kui_config`
  contains no plaintext password and no JAAS string"; the end-to-end half is STORE-009's.
- `anUnknownFieldInStoredJsonIsIgnoredAndNotFatal` — forward compatibility: a record written by a
  newer KUI still decodes. Rolling upgrades are the normal case, not the exception.
- `aMissingRequiredFieldIsANamedDecodeFailure` — the message names the field.

`StoreErrorMappingSuite`:

- one case per row of the mapping table above, asserting the `ErrorCode.wire` value;
- `theMappingIsExhaustive` — a `match` with no default; add a compile-time check by matching on
  every case of the store's error ADT explicitly. If the store's ADT grows, this file must not
  compile.

## Observability

- **Metric**: `MetricNames.ConfigVersion` (`kui.config.version`, attribute `{section}`) set to the
  version of each cluster section after every successful read or write, with
  `section = "cluster/<clusterId>"`. This is the gauge an operator watches to see whether two
  replicas converged after a conflict, which is an exit criterion of this milestone.
- **Span**: `kui.cluster.store.<operation>` (`list`, `get`, `put`, `delete`) with
  `kui.cluster.id` and `kui.store.version`.
- **Log, ERROR, once per bad record**: `stored cluster record could not be decoded`, with the
  store key and the decode failure message. **Not** the record body — it contains ciphertext and
  possibly, if something went wrong upstream, plaintext.
- **Log, INFO, once per write**: `cluster profile written`, with the cluster id and the resulting
  version. No profile fields.
- Nothing in this module logs a `ClusterProfile`, a `Secret[A]`, or a rendered property map. The
  `toString` of `Secret[A]` already redacts (`libs/kernel`); do not add a code path that unwraps
  it for a message.

## Degraded behavior

Per ADR-042 §8 and ADR-039 §6, and these are the exact behaviours the milestone's exit criteria
check:

- **Store unreachable.** Reads are not attempted through this adapter at all in the steady state
  — the registry serves from its last replayed state (CLDOM-004), so clusters keep resolving.
  `health` returns `Degraded(reason)`, which the capability fold turns into a degraded envelope.
  Writes return an `InfrastructureError` and are **rejected, never queued**: a buffered
  configuration write that lands after an operator has given up and edited the file is worse than
  a refused one.
- **Store not configured** (file adapter, ADR-042 §7). Reads work, writes return
  `ApplicationError.Unsupported` which the capability layer renders as `NotConfigured`, and every
  other part of M1 keeps passing. This is an exit criterion; do not treat "no store" as an error
  state anywhere in this file.
- **A single undecodable record.** Skipped, counted, logged, surfaced in `health`. Never fatal.
  The rest of the clusters load.

## Docs to update

None. `docs/operations/metadata-store.md` is owned by STORE (sections 2–6) and CFGOP-008; record
the key prefix, the payload shape and the error table in the implementation report so those tasks
can quote them.

---

## Deviations

Recorded by the implementing agent, 2026-09-04. Commit `d8e7267`.

1. **`changes` is `onChange`, per the gate review's F-02**, and the adapter owns a `Supervisor`
   and a single subscription to `ConfigStore.changes` that feeds every registered handler. One
   subscription and not one per handler: the store's change stream is hot and drops slow
   consumers, and N subscribers each re-listing the section on every change is N times the work
   for one answer they all share. `ClusterConfigStoreAdapter.resource` is therefore a `Resource`,
   not a bare constructor.
2. **`keyFor` returns `Either[KuiError, StoreKey]`**, because `StoreKey.cluster` validates. The
   `Left` is unreachable in practice — `ClusterId`'s slug rule and `StoreKey`'s id rule are the
   same — and it is still a value rather than a `.get`, so an id that somehow passed both cannot
   address the wrong record.
3. **`StoreErrorMapping` is two functions, not a `match` over a store error ADT.** STORE-007
   returns typed `KuiError` values already, so the spec's six-row table is honoured by *not*
   reinterpreting them: the version conflict, the not-configured refusal, the unreachable store
   and the read-back timeout are passed through with their codes intact, and the suite asserts
   each. What is left for this file is the health projection and the one failure this module owns,
   an undecodable payload.
4. **A decode failure is a skip in `list` and a `Left` in `get`.** The spec says skip; that is
   right for a listing, where one bad row must not cost the other nine clusters. For `get(id)` the
   caller named that record, and answering `Right(None)` would send them to create a duplicate.
5. **`ClusterRecordCodec` also encodes the `properties` override map's sensitive values under the
   `$secret` marker.** An operator who pastes a password into the raw property escape hatch gets
   the same encryption as one who uses the typed fields; anything else would make the escape hatch
   the one place secrets are stored in the clear.
6. **The golden file is asserted from the test classpath, not through `Golden.assertJson`.** Mill
   runs the suite in a sandbox, so `Golden`'s working-directory-relative path writes into
   `out/`. The assertion is `assertNoDiff` against `/golden/cluster-record.json` read as a
   resource, which is the shape `services/cluster/contract`'s `GoldenFilesSuite` already uses. The
   sample holds plaintext markers rather than ciphertext, because pinning an encrypted form would
   pin a fresh random IV that changes on every write.
7. **The `updatedBy` of every write is the constant `"kui-cluster"`.** M1 has no authentication;
   a field that will later hold a principal must not be left holding something that looks like
   one.
