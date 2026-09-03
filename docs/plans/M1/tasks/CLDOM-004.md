# CLDOM-004 — `ClusterRegistry`: static configuration overlaid by the store

- **ID:** CLDOM-004
- **Title:** `ClusterRegistry`: static configuration overlaid by the store
- **Milestone / Feature:** M1 / CL-001, CL-002, OT-004, OT-007
- **Owner role:** Domain Architect (Cluster Registry context)
- **Size:** M
- **Dependencies / blocked by:** CLDOM-003

## Goal (user value)

One answer to "which clusters does this KUI know about, and how do I reach each one?", built from
two sources that can disagree — the YAML an operator deployed and the records another replica
wrote to `__kui_config` — with a precedence rule that is written down, tested, and visible in the
UI. Every other use case in this service, and eventually every other Kafka-facing service,
resolves a `ClusterId` through this and nowhere else.

## Scope

1. `ClusterRegistry[F]` in `services/cluster/application`: `list`, `resolve`, `refs`,
   `registryVersion`, `reload`, `changes`.
2. `ClusterRegistry.make` — the live implementation over a static profile list and a
   `ClusterConfigStore[F]`, holding its state in a `Ref`.
3. The overlay rule: which source wins, field by field, and what happens to a stored cluster the
   static configuration has never heard of.
4. `RegistryVersion` — a monotonic counter that increments on every observed change, which
   CLAPI-003 serves as the profile ETag and CLADP-005 bumps.
5. `docs/domain/cluster.md` gains a "Registry" section.

## Non-goals

- **No store adapter.** `ClusterConfigStore[F]` is a port from CLDOM-003 and this task takes it as
  a constructor argument. The adapter over `libs/config` is CLADP-003; the tail-following wiring
  that calls `reload` is CLADP-005.
- **No configuration parsing.** `kui.clusters[]` is CFGOP-001's Ciris slice, in `libs/config`.
  This task receives an already-validated `List[ClusterProfile]` and never sees YAML, an
  environment variable or a `ConfigValue`.
- **No HTTP.** `/internal/v1/clusters/{id}/profile`, its ETag and the SSE stream are CLAPI-003.
- **No topology.** The registry answers "how do I reach it", never "what is it made of".
- **No writes.** `ClusterRegistry` never calls `ClusterConfigStore.put`. The one write endpoint
  (DEVPLAN §10 D6) goes through `ClusterConfigStore` directly in CLAPI-009 and reaches the
  registry only as a change on the store's `changes` stream — which is what makes the exit
  criterion "both replicas converge on the winner's record" true for the *reader* as well as the
  writer.

## The overlay rule — decided

`ARCHITECTURE.md` §10 says the cluster service owns `kui.clusters[]` and that the section is
backed by the `cluster/<id>` keys of `__kui_config`. It does not say what happens when both a YAML
file and a store record describe cluster `prod`. Every deployment that starts from static
configuration and later edits a cluster in the M8 wizard is in exactly that state, so it has to be
decided now.

**Decision: whole-profile replacement by the store, keyed on `ClusterId`, with the static entry
kept as the fallback.**

| Case | Result | `origin` |
| --- | --- | --- |
| In static configuration only | the static profile, `version = ProfileVersion.Static` | `Static` |
| In the store only | the stored profile | `Stored` |
| In both | the **stored** profile, in full | `StaticThenStored` |
| In the store, and the store then becomes unreachable | the last replayed stored profile, unchanged | unchanged |
| In both, and the store record is later deleted | the static profile again, `version = Static` | `Static` |

Whole-profile, not field-by-field merge. A field-level merge would mean an operator who removes
`security` from a stored record silently inherits the YAML's credentials — a change that reads as
"I removed the credentials" and behaves as "I kept them", which is the worst possible outcome for
a security setting. It would also make the profile's `version` meaningless: a version identifies a
*record*, and half of a record has no version.

Keeping the static entry as a fallback (row 5) is what makes the store's absence survivable: a
deployment whose store is wiped still has whatever its YAML said, and a deployment that never had
a store never notices any of this.

**A cluster in the store that static configuration has never heard of is added, not ignored.**
That is the M1 exit criterion "serves clusters from the store" and the whole point of the M8
wizard; the DEVPLAN's test plan names it explicitly ("a store record for an unknown cluster is
added, not ignored"). The `ProfileOrigin` field is what lets the UI show which is which without
the registry having to explain itself twice.

## Precedence of the registry against the profile version

`registryVersion` counts *observed changes to the resolved set*, not store record versions. It
increments when, and only when, the resolved profile map is not equal to the previous one. This
matters for CLAPI-003: an ETag that changed when nothing did makes every downstream service
rebuild its Kafka clients on a schedule, and an ETag that did not change when something did is a
correctness bug that only shows up as a stale credential. Equality is structural
(`ClusterProfile` is a case class with `CanEqual`), which includes the secrets — so a rotated
password does bump the version, as it must.

## Design references

- ADR-036 — single writer per section; the cluster service owns `kui.clusters[]`; distribution to
  other services is by ETag plus SSE, which is why a version exists at all.
- ADR-042 — the store replays to the end before serving, then follows the tail; unreachable means
  last known state plus `Degraded`, never an empty list.
- ADR-031 — `ClusterId` is the key everywhere; two configured entries whose names slug to the same
  id are rejected at configuration validation (CFGOP-001), so the registry may assume ids are
  unique *within* the static list and within the store, and must still decide what to do when the
  two lists overlap — which is this task's rule.
- ADR-016 — the registry is state with a documented staleness contract:
  `ARCHITECTURE.md` §9's cluster row says "profile version bump propagates within one poll
  interval; store unreachable means last known state plus `Degraded`".
- ADR-002 — the weakest `F[_]` bound that works.
- ADR-041 A3 — `application` may not see the wire, `libs/http`, `libs/contracts-core` or any
  `infrastructure` module.
- DEVPLAN §10 D4 — an unreachable *managed* cluster is a section of a healthy response, never an
  unavailable capability. The registry keeps serving its profile regardless.

## Files to create or change

```
services/cluster/application/src/kui/cluster/application/ClusterRegistry.scala      (new)
services/cluster/application/test/src/kui/cluster/application/ClusterRegistrySuite.scala (new)
services/cluster/application/test/src/kui/cluster/application/fakes/FakeClusterConfigStore.scala (new)
build.mill                                                                          (changed)
docs/domain/cluster.md                                                              (changed)
```

`build.mill` change, and nothing else in it: `services.cluster.application.test` gains
`moduleDeps += services.cluster.domain.test`, for `ClusterProfileFixtures` from CLDOM-001. A Mill
test module may depend on another module's test module; `checkArchitecture` skips A1/A2/A3 for
`.test` modules (SVC-001 deviation 1) and A4/A5/A8 are unaffected.

`FakeClusterConfigStore` lives in the application's *test* module and not in `libs/testkit`,
because rule A5 forbids a `libs` module depending on a service and a fake of a domain port
necessarily does. CLDOM-005, CLDOM-006 and CLDOM-007 reuse it; CLADP-003's adapter suite asserts
the real adapter satisfies the same behaviour table this fake implements (the "same fake-port
contract" line of DEVPLAN §7).

## Public Scala signatures to implement

```scala
package kui.cluster.application

import java.time.Instant
import cats.effect.kernel.{Concurrent, Ref, Resource}
import fs2.Stream
import org.typelevel.log4cats.StructuredLogger

import kui.cluster.domain.{ClusterConfigStore, ClusterProfile, ClusterRef, ProfileOrigin, StoreHealth}
import kui.kernel.ClusterId
import kui.kernel.error.KuiError

/** How many times the resolved cluster set has changed since this process started.
  *
  * Starts at 1 rather than 0, so that "the registry has never been loaded" (0) and "the registry
  * loaded and found nothing" (1) are different values. An ETag of `"0"` on a response is
  * indistinguishable from a bug.
  */
opaque type RegistryVersion = Long

object RegistryVersion:
  val Initial: RegistryVersion = 1L
  extension (v: RegistryVersion)
    def value: Long
    def next: RegistryVersion
    /** The ETag CLAPI-003 serves. Quoted-string form is the caller's job, not this type's. */
    def tag: String
  given Ordering[RegistryVersion]; given CanEqual[RegistryVersion, RegistryVersion]

/** One resolved snapshot of the registry: what is configured, at which version, and how healthy
  * the store that contributed to it is.
  */
final case class RegistrySnapshot(
    profiles: Map[ClusterId, ClusterProfile],
    version: RegistryVersion,
    storeHealth: StoreHealth,
    loadedAt: Instant
):
  def refs: List[ClusterRef]                  // sorted by display name, then id
  def get(id: ClusterId): Option[ClusterProfile]
  def size: Int = profiles.size

/** Which clusters this KUI knows about, and how to reach each one.
  *
  * The single resolution point for a `ClusterId` in this service. Nothing else may read the
  * static configuration list or the store directly: two resolvers would be two precedence rules,
  * and the second one is always the one nobody documented.
  */
trait ClusterRegistry[F[_]]:
  /** The current resolved snapshot. Never fails and never blocks on the store: it is a `Ref` read.
    * A store that is unreachable is reflected in `storeHealth`, not in an error — the M1 exit
    * criterion "clusters keep resolving from last known state". */
  def snapshot: F[RegistrySnapshot]

  def list: F[List[ClusterProfile]]

  /** `Left(ApplicationError.NotFound(_, _, ErrorCode.ClusterNotFound))` for an id that is not
    * configured. A 404 and not a 500: an unknown cluster id is a statement about the request
    * (ADR-039 §6), and returning an infrastructure error here would let a user dim the cluster
    * capability for everyone by typing a bad path segment. */
  def resolve(id: ClusterId): F[Either[KuiError, ClusterProfile]]

  def refs: F[List[ClusterRef]]

  def registryVersion: F[RegistryVersion]

  /** Re-reads the store and recomputes the overlay. Idempotent, and safe to call concurrently:
    * two simultaneous reloads produce one resolved state and at most one version bump. Called by
    * CLADP-005 on every store tail event and by the forced-refresh endpoint. Returns the snapshot
    * it settled on, so a caller need not immediately read it back. */
  def reload: F[RegistrySnapshot]

  /** Emits the current snapshot immediately, then one element per *actual* change. A reload that
    * resolves to the same profiles emits nothing. This is what CLAPI-003's SSE stream and the
    * per-cluster refresh loops of CLDOM-005 subscribe to. */
  def changes: Stream[F, RegistrySnapshot]

object ClusterRegistry:
  val Operation: String = "kui.cluster.registry"

  /** Builds the registry and performs the first resolution.
    *
    * `Resource` because `changes` is backed by a `Topic` that must be shut down with the service;
    * `Concurrent` because it holds a `Ref` and a `Topic` and serialises `reload` behind a
    * `Semaphore`. That is the weakest bound that works (ADR-002): nothing here sleeps or times
    * out, so `Temporal` would be more than is needed.
    *
    * **The first resolution never fails.** If the store cannot be read at construction time, the
    * registry is built from `static` alone, `storeHealth` is `Degraded`, and one WARN is logged.
    * A cluster service that refuses to start because its metadata store is down would take the
    * whole UI with it — and ADR-042's bootstrap ordering already guarantees that when a store is
    * configured, `CLAPI-005` completes the store's replay *before* this is constructed, so a
    * failure here means the store broke after replay, which is exactly the degraded case the exit
    * criteria require KUI to survive.
    */
  def make[F[_]: Concurrent](
      static: List[ClusterProfile],
      store: ClusterConfigStore[F],
      clock: kui.cluster.domain.ClockPort[F],
      logger: StructuredLogger[F]
  ): Resource[F, ClusterRegistry[F]]

  /** The overlay rule, extracted as a pure function so that it is testable as a table and so that
    * `reload` cannot accidentally re-implement it. Public because CLDOM-004's suite asserts it
    * directly and CLADP-005's suite reuses it.
    */
  def overlay(
      static: List[ClusterProfile],
      stored: List[ClusterProfile]
  ): Map[ClusterId, ClusterProfile]
```

`overlay` sets `origin` on each result: `Static`, `Stored` or `StaticThenStored` per the table
above. It is the only place `ProfileOrigin` is assigned.

## Library coordinates

`services.cluster.application` already has everything: `cats-core::2.13.0`,
`cats-effect::3.7.1` (`Ref`, `Semaphore`, `Resource`), `fs2-core::3.13.0` (`Topic`, `Stream`),
`log4cats-core::2.8.0`, `otel4s-core::1.1.0`, and `moduleDeps = Seq(domain)`.

Test module: `libs.testkit.jvm` (MUnit 1.3.6, munit-scalacheck 1.3.1, ScalaCheck 1.20.0,
`FakeClock`, `FakeStructuredLogger`) plus the new `services.cluster.domain.test` dep, plus
`org.typelevel::munit-cats-effect::2.2.0` and `org.typelevel::cats-effect-testkit::3.7.1` for
`TestControl`. Check whether `KuiTests` already brings the last two — `libs/testkit` was built for
exactly this in M0 — and add them to `services.cluster.application.test` only if it does not.

No new module dependency: `libs.cache` is **not** needed here (the registry is a `Ref`, not a
snapshot cell — it has no upstream to refresh on a timer and no `scrapedAt`). It is CLDOM-005 that
adds `libs.cache`.

## Acceptance criteria

```
$ ./mill services.cluster.application.test
Test run kui.cluster.application.ClusterRegistrySuite finished: 0 failed, 0 ignored, 14 total
SUCCESS

$ ./mill checkArchitecture
checkArchitecture: <n> modules, no layering violations

$ ./mill services.cluster.application.checkFormat
$ ./mill services.cluster.application.fix --check
```

`checkArchitecture` must still pass rule A3 — no tapir, no circe, no `libs.contractsCore`, no
`libs.http`, no `infrastructure` on `services.cluster.application`. Add
`libs.contractsCore.jvm` to the module temporarily, observe the A3 violation, revert, and paste
the message into the Implementation Report (the M0 SVC-001 practice).

## Tests required

`ClusterRegistrySuite` (MUnit + `munit-cats-effect` + `TestControl` + `FakeClusterConfigStore`).
The first six are table assertions on the pure `overlay` and need no effect at all:

1. `staticOnlyClusterIsServedAsStatic`.
2. `storedOnlyClusterIsAdded` — a store record for an id absent from static configuration appears
   in the result with `origin = Stored`. *The named exit-criterion test.*
3. `storedProfileWinsWholesale` — static has `bootstrap = a:9092, security = SaslScram`; the store
   has `bootstrap = b:9092, security = Plaintext`. The result is `b:9092` **and** `Plaintext`.
   Asserting the security field is the point: it is the field a merge would have got wrong.
4. `originIsStaticThenStoredWhenBothDescribeIt`.
5. `removingTheStoreRecordFallsBackToStatic` — overlay with the record, then without.
6. `overlayIsDeterministicUnderInputOrder` (property) — shuffling either input list gives the same
   map.

Effectful, through `FakeClusterConfigStore`:

7. `snapshotIsServedFromMemoryWhileTheStoreIsUnreachable` — reload once with two stored clusters,
   set the fake to fail every call, reload again: `snapshot.profiles` is unchanged and
   `storeHealth` is `Degraded`. *The named exit-criterion test.*
8. `constructionSucceedsWithAFailingStore` — `make` with a store that fails `list` produces a
   registry over the static list, `storeHealth = Degraded`, and exactly one WARN on the
   `FakeStructuredLogger` (assert the level: an ERROR here would page someone for a state the
   product is designed to survive).
9. `noStoreConfiguredReportsNotConfiguredAndNotDegraded` — a fake whose `health` is
   `NotConfigured` must leave `storeHealth = NotConfigured`. Per ADR-039 §2 this must never
   collapse into `Degraded`; the assertion is on the exact case.
10. `versionBumpsOnlyOnARealChange` — reload twice with identical store contents: `version` is the
    same value both times. Then change one profile and reload: it increments by exactly one.
11. `changesEmitsCurrentThenOnlyRealChanges` — subscribe, assert the first element arrives without
    any reload, run a no-op reload (nothing further arrives), run a real one (exactly one more).
12. `concurrentReloadsProduceOneStateAndAtMostOneBump` — `List.fill(20)(registry.reload).parSequence`
    under `TestControl`; assert the final version incremented by at most one and every returned
    snapshot has the same `profiles`. This is the test that fails if `reload` is written as
    read-then-write on a `Ref` without the semaphore.
13. `resolveOfAnUnknownIdIsANotFoundApplicationError` — assert the *type* is `ApplicationError`
    and the code is `ErrorCode.ClusterNotFound`. Asserting the branch, not just the code, is what
    stops a later refactor from making a bad path segment dim the sidebar (ADR-039 §6).
14. `refsAreSortedByDisplayNameAndCarryNoSecrets` — `refs.map(_.toString).mkString` contains no
    canary token.

`FakeClusterConfigStore` requirements (it is a fixture other tasks depend on, so its shape is
specified here):

```scala
final class FakeClusterConfigStore[F[_]: Concurrent] private (
    state: Ref[F, FakeClusterConfigStore.State]
) extends ClusterConfigStore[F]
object FakeClusterConfigStore:
  final case class State(
      profiles: Map[ClusterId, ClusterProfile],
      health: StoreHealth,
      failWith: Option[KuiError],     // when set, list/get/put all return it
      calls: List[String]             // "list", "get:<id>", "put:<id>@<version>"
  )
  def make[F[_]: Concurrent](initial: List[ClusterProfile]): F[FakeClusterConfigStore[F]]
  // controls: setProfiles, setHealth, fail(error), recover, calls
```

`put` on the fake implements the real version check — matching `expected` against the stored
version, returning `ApplicationError.Conflict` with `ErrorCode.ConfigVersionConflict` otherwise —
so that CLAPI-009's suite can use it without re-implementing the rule.

## Observability

Structured log fields on every line from this component: `service.name = "cluster"`,
`operation = "kui.cluster.registry"`, and `cluster.count`. `correlation.id` is not added by the
use case (SVC-001 deviation 3) — the MDC bridge in `libs/observability` puts it there when there
is a request, and `reload` frequently has none.

| Event | Level | Fields |
| --- | --- | --- |
| First resolution complete | INFO | `cluster.count`, `store.health`, `registry.version` |
| Reload changed the set | INFO | `registry.version`, `cluster.added`, `cluster.removed`, `cluster.changed` (counts, never names of secrets) |
| Reload found no change | DEBUG | `registry.version` |
| Store read failed | WARN | `store.health`, `error.code` — WARN and never ERROR: the product is designed to serve through this |

Metric: `kui.cluster.registry.size` (gauge) and `kui.cluster.registry.version` (gauge), emitted on
change. No span — `reload` is not request-scoped. Nothing here logs a `ClusterProfile`; log the
`ClusterRef` or a count.

## Degraded behavior

| Condition | Behaviour |
| --- | --- |
| Store unreachable at construction | registry is the static list; `storeHealth = Degraded`; one WARN; service starts |
| Store unreachable after replay | last resolved set is served unchanged; `storeHealth = Degraded`; `resolve` keeps working |
| Store never configured (file adapter, or nothing) | `storeHealth = NotConfigured`; never rendered as broken (ADR-039 §2) |
| Store returns an empty list | an empty overlay: the static list is served as `Static`. Not an error, and not a reason to keep a previous stored set — an empty store is a legitimate first start |
| An unknown cluster id is resolved | `ApplicationError.NotFound`, `ErrorCode.ClusterNotFound`, HTTP 404 via `ErrorEnvelope.statusOf` |
| A managed cluster is unreachable | **irrelevant here.** The registry never touches a broker. Its profile resolves normally and the *topology* snapshot is what goes stale (CLDOM-005, DEVPLAN §10 D4) |

## Docs to update

`docs/domain/cluster.md` gains a "Registry" section: the overlay table verbatim, the
whole-profile-replacement rationale (one paragraph — a future reader will propose a field merge
and must find the answer without reading a plan file), what `registryVersion` counts and why it
is not the store record version, and the degraded table above.
