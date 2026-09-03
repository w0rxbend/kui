# CLDOM-003 — Cluster domain ports: `ClusterAdmin`, `ClusterConfigStore`, `ConnectivityProbe`

- **ID:** CLDOM-003
- **Title:** Cluster domain ports: `ClusterAdmin`, `ClusterConfigStore`, `ConnectivityProbe`
- **Milestone / Feature:** M1 / CL-001, CL-002, CL-007, BR-002, BR-005, OT-004
- **Owner role:** Domain Architect (Cluster Registry context)
- **Size:** S
- **Dependencies / blocked by:** CLDOM-002

## M1 gate review amendment — `ClusterFeatures` keeps its third set

**F-05, major, fixed.** KAFKA-009 produces a three-valued `ClusterFeatures` — `present`,
`absent`, **`unknown`**, plus `probedAt` — precisely so that a probe which timed out is not
recorded as "this cluster cannot do that". The domain then collapsed it back to a
`Set[ClusterFeature]` at the port, which throws the third set away and reintroduces the exact
bug KAFKA-009's decision exists to prevent: a one-hour cache of a lie.

**The domain gains its own `ClusterFeatures`**, in
`services/cluster/domain/src/kui/cluster/domain/ClusterFeatures.scala`, with the same three sets:

```scala
final case class ClusterFeatures(
    present: Set[ClusterFeature],
    absent:  Set[ClusterFeature],
    unknown: Set[ClusterFeature],
    probedAt: Instant
) {
  def has(f: ClusterFeature): Boolean = present.contains(f)
  def isUnknown(f: ClusterFeature): Boolean = unknown.contains(f)
}
object ClusterFeatures {
  def unprobed(at: Instant): ClusterFeatures   // everything unknown
}
```

with the same invariant KAFKA-009 asserts as a property: `present ++ absent ++ unknown ==
ClusterFeature.All`, always, and the three sets are pairwise disjoint. Assert it here too — the
two enums are defined in two modules (CLDOM-002 decision 2), and a shared invariant checked on
one side only is half a check.

**Signature changes that follow, everywhere in this spec:**

- `ClusterAdmin.capabilities(profile): F[ClusterFeatures]` (not `F[Set[ClusterFeature]]`).
- `ClusterSnapshots.capabilitiesOf(id): F[Option[SnapshotCell[F, ClusterFeatures]]]`.
- `ClusterDescription.features: ClusterFeatures`; `ClusterDescription.has(f)` is
  `features.has(f)`, unchanged in meaning.
- `CapabilityReportUseCase.stateOf` distinguishes the third case: a feature in `unknown` is
  **not** reported as unsupported. It renders as `Degraded` with the probe's reason where the
  screen depends on it, and as "not determined" otherwise — never as `absent`.
- `CLADP-002`'s adapter maps `libs/kafka`'s `ClusterFeatures` onto the domain's field for field,
  by exhaustive match on `ClusterFeature`, preserving all three sets and `probedAt`.

## M1 gate review amendment — no `fs2.Stream` in the domain

**F-02, blocker, fixed.** This spec asked CFGOP-003 to add `co.fs2::fs2-core` to rule A1's
allow-list so that `ClusterConfigStore.changes` could be an `fs2.Stream`. **Refused**, and
recorded as [ADR-041 Amendment 3](../../../adr/ADR-041-layering-rules-machine-enforced.md).
Two reasons, one of them fatal on its own:

1. **Ordering.** CFGOP-003 depends on CLADP-001, which depends on this task. The rule change
   would land *after* the code that needs it, so this task would leave `./mill
   checkArchitecture` — and therefore `main` — red for several tasks. The DEVPLAN's own rule is
   that every task ends on a green `main`.
2. **A1 is worth keeping short.** A port stated over an abstract `F[_]` needs no runtime
   dependency at all. `fs2.Stream` is a concrete type from a concrete runtime, and a domain that
   imports it can no longer be read, tested or moved without it.

**Take the fallback this spec already specifies.** Replace the `changes` member with callback
registration, and let `ClusterRegistry` (in `application`, where fs2 is already allowed) own the
stream:

```scala
trait ClusterConfigStore[F[_]] {
  /** Registers a handler invoked with the full resolved profile list on every change.
    * Returns the deregistration action. No fs2, no cats-effect — just `F`. */
  def onChange(handler: List[ClusterProfile] => F[Unit]): F[F[Unit]]
  // ... the rest of the port unchanged
}
```

`services.cluster.domain` keeps `moduleDeps = Seq(libs.kernel.jvm)` and
`mvnDeps = Seq(cats-core)`. Do not add an mvnDep to that module in this task or any other.

## Goal (user value)

The three sentences the cluster context says to the outside world: *tell me what this cluster
looks like*, *remember this cluster for me*, *is this cluster reachable*. Written as domain-owned
traits in terms of domain types, so that the use cases can be written and tested before any
adapter exists — which is what lets lane C finish weeks before lane D.

## Scope

1. `ClusterAdmin[F]` — reading a cluster's topology. Five methods, no mutations.
2. `ClusterConfigStore[F]` — the profiles KUI has been told to remember, and the one write M1
   ships.
3. `ConnectivityProbe[F]` — "can KUI open a connection and authenticate?", separate from
   `ClusterAdmin` for the reason given below.
4. `StoreHealth` — the store's own state as the domain sees it, needed by CLDOM-007.
5. `docs/domain/cluster.md` gains a "Ports" section.

## Non-goals

- **No implementations.** Not even an in-memory one in `src`. Fakes live in the *test* modules
  (`services/cluster/application/test`), because a fake in `src` is a production class nobody
  meant to ship (`libs/testkit` cannot hold these — rule A5 forbids a `libs` module depending on
  a service).
- **No mutations except the one M1 ships.** No `alterBrokerConfig`, no `alterReplicaLogDir`, no
  `deleteCluster`. DEVPLAN §3: broker configuration is read-only in M1, and mutations arrive in
  M5 behind read-only mode and audit. `ClusterConfigStore.put` exists only because DEVPLAN §10 D6
  ships one write endpoint to satisfy the concurrency exit criteria.
- **No `TopicAdmin`, `GroupAdmin`, `SecurityAdmin`, `MessageBrowsePort`.** DEVPLAN §3 forbids
  declaring them; risk R-11.
- **No `Resource`, no lifecycle.** A port says what can be asked, never how a client is built or
  closed. Client lifecycle is CLADP-002's.

## Why `ConnectivityProbe` is not a method on `ClusterAdmin`

They answer different questions for different callers and fail differently.

`ClusterAdmin.describeCluster` is on the read path: it runs on a 30-second refresh loop, it is
allowed to take the full admin timeout, and its failure means "serve the previous snapshot as
stale". `ConnectivityProbe.probe` is on the *decision* path: the capability report (CLDOM-007) and
the M8 wizard's "test connection" both need a fast, bounded, side-effect-free yes/no that
distinguishes *cannot reach* from *reached but was refused*, and neither wants a whole topology
back. Folding them together would mean either the capability probe pays for a full
`describeCluster` every time it runs, or the refresh path inherits the probe's short timeout.

The distinction is also the one ADR-039 §6 keys on: an authentication failure is an
`InfrastructureError` and dims a capability; a *reachable* cluster is not, whatever else went
wrong. Having a port whose whole return type is that distinction makes it hard to get wrong.

## Design references

- ADR-041 A1 — ports live in `domain` and are stated in domain types; the `F[_]` bound is the
  weakest that works (ADR-002).
- ADR-006 — one adapter over fs2-kafka implements the admin ports; the raw `Admin` escape hatch
  exists for what fs2-kafka does not wrap.
- ADR-034 — everything returns `F[Either[KuiError, A]]`; nothing throws across a layer boundary.
- ADR-036 — the cluster service is the single writer of `kui.clusters[]`; concurrent writers are
  rejected with `KUI-CONFIG-VERSION-CONFLICT` (`ErrorCode.ConfigVersionConflict`, already in
  `libs/kernel`).
- ADR-042 — the store may be a file adapter with no write support, which is why `put` can return
  `ApplicationError.Unsupported`.
- ADR-039 §6 — only `InfrastructureError` dims a capability; a business refusal must not.
- `research/kafka/admin-capabilities.md` §0 (partial failure, per-key results) and §1 (every error
  each call can produce).
- DEVPLAN §10 D6 (one write endpoint), D4 (an unreachable managed cluster is a section, not an
  unavailable capability).

## The `BatchResult` question — decided

`ARCHITECTURE.md` §4.2 gives `libs/kafka` a `BatchResult[K, A](values, skipped)` so that one
failed key never fails a batch (`admin-capabilities.md` §0, "Partial failure"). That type is
declared in `libs/kafka` by KAFKA-005, and rule A1 forbids the domain seeing it.

**Decision.** The domain declares its own `PartialResult[K, A]` in
`services/cluster/domain/src/kui/cluster/domain/PartialResult.scala`, with the same two fields and
a domain-typed skip reason, and CLADP-002 maps `BatchResult` onto it. This is the same
adapter-maps-foreign-shape decision CLDOM-002 took for the topology types, for the same reason,
and it is stated here again so that a worker who reads only this task does not go looking for a
shared type. The skip reason is a closed enum and not a `String`, because the UI renders a
sentence per case and CLAPI-001 encodes it.

## Files to create

```
services/cluster/domain/src/kui/cluster/domain/PartialResult.scala        (new)
services/cluster/domain/src/kui/cluster/domain/ClusterAdmin.scala         (new)
services/cluster/domain/src/kui/cluster/domain/ClusterConfigStore.scala   (new)
services/cluster/domain/src/kui/cluster/domain/ConnectivityProbe.scala    (new)
services/cluster/domain/test/src/kui/cluster/domain/PartialResultSuite.scala (new)
docs/domain/cluster.md                                                    (changed)
```

Only `PartialResult` has behaviour, so only it has a suite. The three traits are compiled by
CLDOM-004..007's suites through their fakes, which is the correct place for a trait to be
exercised: by something that uses it.

## Public Scala signatures to implement

```scala
package kui.cluster.domain

import cats.data.NonEmptyList
import kui.kernel.{BrokerId, ClusterId}
import kui.kernel.error.KuiError

/** Why one key of a batch produced no value.
  *
  * A closed set, and every case is one the reference products hit in production
  * (`research/kafka/admin-capabilities.md` §0–§1). `Failed` carries a `KuiError` and not a
  * `Throwable`, so a skip reason can be rendered to a user without a class name reaching a screen.
  */
enum SkipReason:
  /** The broker refused the request for this key: no `DESCRIBE_CONFIGS`, no `DESCRIBE` on the
    * resource. The rest of the batch is fine. */
  case Unauthorized
  /** The key vanished between listing and describing, or the managed service reports it as
    * unknown (Azure Event Hubs does this for broker configs). */
  case NotFound
  /** The cluster does not implement this call for this key at all — the managed-service
    * `InvalidRequestException` / `UnsupportedVersionException` downgrade. */
  case Unsupported
  /** Anything else, already classified. */
  case Failed(error: KuiError)

object SkipReason:
  given CanEqual[SkipReason, SkipReason] = CanEqual.derived

/** A batch answer that says what it could not do rather than dropping it.
  *
  * The whole point is `skipped`. Kafbat's equivalents return an empty map on a per-key failure
  * (`ReactiveAdminClient.java:316-343,421-430`), which is why its broker page cannot distinguish
  * "this cluster has no dynamic config" from "KUI is not allowed to read it" — and neither can its
  * user. A silent drop is forbidden: every key that went in comes out in exactly one of the two
  * maps, which is invariant (1) below.
  */
final case class PartialResult[K, A](values: Map[K, A], skipped: Map[K, SkipReason]):
  def isComplete: Boolean = skipped.isEmpty
  def get(key: K): Option[A] = values.get(key)
  def map[B](f: A => B): PartialResult[K, B]
  /** Merges two results over disjoint key sets, `values` winning over `skipped` for a key that
    * appears in both (a retry that succeeded). Used to fold chunked calls back together. */
  def merge(other: PartialResult[K, A]): PartialResult[K, A]

object PartialResult:
  def complete[K, A](values: Map[K, A]): PartialResult[K, A]
  def empty[K, A]: PartialResult[K, A]
  /** Builds from the requested key set, so the invariant is established at construction rather
    * than hoped for: any requested key present in neither map becomes
    * `SkipReason.Failed(InfrastructureError.…)` — see the suite. */
  def from[K, A](requested: Set[K], values: Map[K, A], skipped: Map[K, SkipReason]): PartialResult[K, A]

/** Reading one cluster's topology. Implemented by `services/cluster/infrastructure` over
  * `libs/kafka` (CLADP-002); faked by `application`'s suites.
  *
  * Every method takes the whole `ClusterProfile` rather than a `ClusterId`, because the adapter
  * needs the connection settings to build or look up its client and the domain has no registry to
  * resolve an id against. The use case resolves the id once (CLDOM-004) and passes the profile
  * down, which also means a profile that changed mid-refresh cannot be half-used.
  *
  * `Monad` is not required: the bound is `F[_]` with none at all (ADR-002). Nothing here composes
  * effects; the use cases do.
  */
trait ClusterAdmin[F[_]]:
  /** `describeCluster`, plus the controller mode. Fails only when the cluster cannot be reached
    * or refuses KUI entirely; an absent controller or cluster id is a `Right` (CLDOM-002). */
  def describeCluster(profile: ClusterProfile): F[Either[KuiError, ClusterDescription]]

  /** `describeFeatures` with the `inter.broker.protocol.version` fallback (ADR-030). `None` means
    * the version could not be established — a legitimate answer on a managed service, and not an
    * error, because a UI that shows "unknown" is more honest than one that shows a guess. */
  def detectVersion(profile: ClusterProfile): F[Either[KuiError, Option[KafkaVersion]]]

  /** `describeMetadataQuorum`. `None` on a ZooKeeper cluster or when the call is unauthorized —
    * both are "there is no quorum information here", which is what the caller needs to know. */
  def describeQuorum(profile: ClusterProfile): F[Either[KuiError, Option[QuorumInfo]]]

  /** `describeConfigs(ConfigResource(BROKER, id))` for one broker, sorted by name.
    *
    * Returns `Left(ApplicationError.Unsupported)` — never `Right(Nil)` — when the cluster refuses
    * the call, so that the UI can say "this cluster does not expose broker configuration" instead
    * of showing an empty table that looks like a broker with no settings. Kafbat's swallow-to-empty
    * behaviour (`admin-capabilities.md` §1, "Broker configs") is the defect this signature exists
    * to avoid. */
  def brokerConfigs(profile: ClusterProfile, broker: BrokerId, docs: Boolean)
      : F[Either[KuiError, List[ConfigEntry]]]

  /** `describeLogDirs`, per broker, with per-broker partial failure. A `Left` means the whole call
    * failed (unreachable, unsupported); a `skipped` entry means one broker did not answer while
    * others did — which is the normal shape when one broker is down. */
  def describeLogDirs(profile: ClusterProfile, brokers: NonEmptyList[BrokerId])
      : F[Either[KuiError, PartialResult[BrokerId, List[LogDir]]]]

  /** Which of `ClusterFeature.All` this cluster supports, established by probing and never by
    * inferring from a version (ADR-030). Total: it returns a set rather than an `Either`, because
    * "the probe failed" is indistinguishable from "the feature is absent" from the caller's point
    * of view, and an unreachable cluster produces an empty set — which reads correctly as "KUI
    * currently knows of nothing it can do here". */
  def capabilities(profile: ClusterProfile): F[Set[ClusterFeature]]

/** The verdict of a cheap, bounded connection attempt. */
enum Connectivity:
  /** A connection was opened and KUI authenticated. */
  case Reachable
  /** The cluster answered but rejected KUI's credentials (SASL or SSL). Not retryable: the
    * configuration must change first. */
  case AuthenticationFailed(detail: String)
  /** No answer within the probe's own bound. */
  case Unreachable(detail: String)

object Connectivity:
  given CanEqual[Connectivity, Connectivity] = CanEqual.derived

/** "Can KUI talk to this cluster right now?" — bounded, read-only, and cheaper than a topology
  * refresh. `detail` is display text and must never contain a host, a URL with credentials or a
  * JAAS string (ADR-034); the adapter is responsible for that and CLADP-004's suite asserts it.
  */
trait ConnectivityProbe[F[_]]:
  def probe(profile: ClusterProfile): F[Connectivity]

/** How the metadata store itself is doing, as the cluster domain sees it.
  *
  * The domain needs this because the capability report (CLDOM-007) must distinguish "KUI is
  * serving you cluster definitions it replayed an hour ago and cannot currently accept a change"
  * from "everything is fine" — the M1 exit criterion for a stopped store cluster. It is
  * deliberately *not* the store's own richer health type from `libs/config` (STORE-008): rule A1
  * forbids the domain seeing it, and this is the subset the domain reasons about.
  */
enum StoreHealth:
  /** Replayed, following the tail, writes accepted. */
  case Online
  /** Last known state is being served; writes are rejected. `since` drives the "how long" the UI
    * shows, and `reason` is display text. */
  case Degraded(reason: String, since: java.time.Instant)
  /** No store is configured at all: the file adapter, or nothing. Writes report `NotConfigured`.
    * Per ADR-039 §2 this is *not* a health verdict and must never render as broken. */
  case NotConfigured

object StoreHealth:
  given CanEqual[StoreHealth, StoreHealth] = CanEqual.derived

/** The cluster profiles KUI has been told to remember, and the one write M1 ships.
  *
  * Implemented over `ConfigStore[F]` from `libs/config` by CLADP-003. The domain does not know
  * that this is a compacted Kafka topic, that records are envelope-encrypted, or that the version
  * is an offset — it knows only that a profile has a version, that a stale version loses, and that
  * a write may be refused because there is nowhere to write to.
  */
trait ClusterConfigStore[F[_]]:
  /** Every profile the store currently holds. A store that has replayed and found nothing returns
    * an empty list, which is a normal first start and not an error. */
  def list: F[Either[KuiError, List[ClusterProfile]]]

  def get(id: ClusterId): F[Either[KuiError, Option[ClusterProfile]]]

  /** Writes a profile, refusing when `expected` is not the version currently stored.
    *
    * Returns the stored profile with its **new** version on success — the caller needs it, and
    * returning `Unit` would force a read-back at every call site.
    *
    * Failure modes, all of which the M1 exit criteria name:
    *   - `ApplicationError.Conflict` with `ErrorCode.ConfigVersionConflict` — another writer got
    *     there first. The loser of the two-replica race sees exactly this.
    *   - `ApplicationError.Unsupported` — the file adapter is in use; there is nowhere to write.
    *     This is what the endpoint reports as `NotConfigured` (DEVPLAN §10 D6).
    *   - `InfrastructureError.*` — the store cluster is unreachable. Writes are *rejected*, never
    *     buffered: a queued configuration change that silently applies twenty minutes later is
    *     worse than a refusal (ADR-042).
    *
    * It returns only after the write is readable back from the store (ADR-042's read-your-writes
    * rule); the domain states that as a contract here and CLADP-003 implements it.
    */
  def put(profile: ClusterProfile, expected: ProfileVersion)
      : F[Either[KuiError, ClusterProfile]]

  /** Profiles as they change, for the registry to follow. Emits the current list once on
    * subscribe and then one element per store change, so a subscriber never has to also call
    * `list` and reconcile a race. Terminates only when the store is shut down. */
  def changes: fs2.Stream[F, List[ClusterProfile]]

  def health: F[StoreHealth]
```

`changes` puts `fs2.Stream` in a domain signature, which needs saying out loud because rule A1
lists "`libs.kernel` and cats-core". `services.cluster.domain`'s `mvnDeps` gains
`co.fs2::fs2-core::3.13.0` in this task. The alternative is a hand-rolled callback registration in
the domain, which is a worse `Stream`; fs2-core is a pure, dependency-light streaming vocabulary
in the same Typelevel family as cats-core, `services.cluster.application` already depends on it,
and `checkArchitecture`'s A1 rule is expressed as an allow-list that CFGOP-003 extends by one
coordinate. **This is a build-rule change owned by another area:** raise it in CLDOM-003's PR,
have CFGOP-003 add `co.fs2::fs2-core` to A1's allow-list with its own build test, and do not edit
`build.mill`'s rule table from this task (DEVPLAN §6.5). If CFGOP-003 refuses, the fallback that
needs no rule change is to move `changes` off the port and onto the registry
(`ClusterRegistry.changes`, in `application`, where fs2 is already allowed) and give
`ClusterConfigStore` a `def subscribe(onChange: List[ClusterProfile] => F[Unit]): F[Unit]`; note
which of the two shipped in the Implementation Report.

## Library coordinates

```
services.cluster.domain.mvnDeps += co.fs2::fs2-core::3.13.0     (see the note above)
                          existing: org.typelevel::cats-core::2.13.0
                          moduleDeps: libs.kernel.jvm
```

No other change. `build.mill` edit is limited to that one `mvnDeps` line of
`services.cluster.domain` (DEVPLAN §6.5 permits a task to edit the module it is wiring).

## Acceptance criteria

```
$ ./mill services.cluster.domain.compile
$ ./mill services.cluster.domain.test
Test run kui.cluster.domain.PartialResultSuite finished: 0 failed, 0 ignored, 7 total
SUCCESS

$ ./mill checkArchitecture
checkArchitecture: <n> modules, no layering violations
```

If `checkArchitecture` fails on `co.fs2::fs2-core` for `services.cluster.domain`, that is the A1
allow-list conversation above — record the exact message in the Implementation Report and do not
work around it by moving the type.

Two greps, to paste into the Implementation Report:

```
$ grep -rn "alterBrokerConfig\|alterReplicaLogDir\|deleteCluster" services/cluster/domain  # (none)
$ grep -rn "trait TopicAdmin\|trait GroupAdmin\|trait SecurityAdmin" services/cluster       # (none)
```

## Tests required

`PartialResultSuite` (MUnit + ScalaCheck):

1. `everyRequestedKeyAppearsExactlyOnce` (property) — for arbitrary requested key sets and
   arbitrary partial value/skip maps, `from` produces `values.keySet ++ skipped.keySet ==
   requested` and `values.keySet.intersect(skipped.keySet).isEmpty`. This is the invariant the
   whole type exists for, and it is a property rather than an example because the failure mode is
   a key the caller forgot to account for.
2. `aKeyInNeitherMapBecomesASkip` — `from(Set(1,2), Map(1 -> "a"), Map.empty)` skips `2`.
3. `isCompleteIsTrueOnlyWhenNothingWasSkipped`.
4. `mapPreservesSkips`.
5. `mergePrefersAValueOverASkipForTheSameKey` — the retry case.
6. `mergeIsAssociativeOverDisjointKeys` (property).
7. `skipReasonFailedCarriesAKuiErrorAndNoStackTrace` — `SkipReason.Failed(err).toString` contains
   the error's `code` and `message` and does not contain `"Exception"` (the type makes a
   `Throwable` unrepresentable; the test documents that it is deliberate).

No suite is written for the three traits. A test that instantiates an empty trait asserts that
Scala can make an anonymous class, which is not a fact about this system.

## Observability

None (declarations only). The constants the layers above will use are fixed here by naming:
`ClusterAdmin`'s methods become span names `kui.cluster.admin.describeCluster`,
`.detectVersion`, `.describeQuorum`, `.brokerConfigs`, `.describeLogDirs`, `.capabilities` in
CLADP-002; `ClusterConfigStore`'s become `kui.cluster.store.list/get/put`. CLADP-002 and CLADP-003
own the instrumentation; this task owns the names by owning the method names.

## Degraded behavior

The ports *are* the degraded contract; the table is the specification the adapters implement and
the fakes reproduce:

| Situation | Port result |
| --- | --- |
| Cluster unreachable | `describeCluster` → `Left(InfrastructureError.Unreachable)`; `capabilities` → `Set.empty`; `probe` → `Connectivity.Unreachable` |
| Bad credentials | `Left(InfrastructureError.AuthFailed)`; `probe` → `Connectivity.AuthenticationFailed` |
| One broker of five down | `describeLogDirs` → `Right(PartialResult)` with that broker in `skipped` |
| Managed service hides broker configs | `brokerConfigs` → `Left(ApplicationError.Unsupported)`; `capabilities` omits `BrokerConfigs` |
| ZooKeeper cluster | `describeQuorum` → `Right(None)` |
| Store cluster stopped | `list`/`get` still succeed from last known state; `put` → `Left(InfrastructureError.*)`; `health` → `Degraded` |
| File adapter configured | `put` → `Left(ApplicationError.Unsupported)`; `health` → `NotConfigured` |
| No cluster configured | `list` → `Right(Nil)`; not an error |

The `ApplicationError` / `InfrastructureError` split in that table is not stylistic: ADR-039 §6
dims a capability on the second and never on the first. A worker who returns
`InfrastructureError.Upstream` for "this cluster does not support log dirs" makes the cluster
service dim itself for every user on a cluster that is working perfectly.

## Docs to update

`docs/domain/cluster.md` gains a "Ports" section: the three traits with the question each answers,
the `PartialResult` invariant, the degraded table above verbatim (it is the contract an adapter
author needs and it must not live only in a plan file), and one paragraph recording the
`fs2-core`-in-`domain` decision and its outcome.

## Deviations

1. **`onChange`, not `changes`** — the gate review's F-02 fallback. `services.cluster.domain` gained
   no `mvnDeps` and `checkArchitecture` stayed green throughout.
2. **`ClusterAdmin.capabilities` returns `ClusterFeatures`**, per F-05.
3. **`SkipReason.describe` and `Connectivity.isReachable` added** — display text and the one derived
   question, defined once so the API layer does not invent a second wording.
4. **`PartialResult.from` filters to the requested key set** as well as filling the gaps, so a value
   for a key nobody asked about cannot appear in the result.
5. `PartialResultSuite` asserts `SkipReason.Failed`'s *display text* (`describe`) rather than its
   `toString`: `KuiError` is a case class, so its generated `toString` prints constructor arguments
   and not the message the spec expected to find there.
