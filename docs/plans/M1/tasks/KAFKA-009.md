# KAFKA-009 — `ClusterAdmin` C: the `ClusterFeature` capability probe

- **ID:** KAFKA-009
- **Title:** `ClusterAdmin` C: the `ClusterFeature` capability probe
- **Milestone / Feature:** M1 / CL-007, CL-009, OT-004, KU-012
- **Owner role:** Principal Scala Engineer
- **Context / service:** `libs/kafka`
- **Size:** M
- **Dependencies / blocked by:** KAFKA-007

## Goal (user value)

KUI finds out what a particular cluster can actually do — by asking it, not by reading its
version number and assuming. A managed service that reports Kafka 3.9 but refuses
`describeLogDirs`, and a self-hosted 2.8 that supports everything it should, both end up
described correctly, so a screen is hidden because the cluster cannot do the thing rather than
because a version comparison guessed wrong.

## Scope

1. `ClusterFeature` — the closed set of capabilities the probe reports.
2. `ClusterFeatures` — present, absent and *unknown*, with a `probedAt`.
3. The probe itself: version-derived features from `BrokerVersion` (KAFKA-007), call-derived
   features from cheap calls with a short timeout, and the downgrade rules that turn a managed
   service's refusal into "absent" rather than an error.
4. The rule that the probe never fails.

## Non-goals

- No caching or scheduling. "Every hour and on reconnect" (`ARCHITECTURE.md` §9) is
  CLDOM-005's refresh loop over a `SnapshotCell`; this task provides the function it calls.
- No mapping to `ServiceCapabilities` or to the gateway's capability registry. That is
  CLDOM-007 and CLAPI-008, and the distinction matters: `ClusterFeature` is "what this Kafka
  cluster supports", `CapabilityState` is "what the UI may show", and ADR-039 §6 plus DEVPLAN
  decision D4 keep them apart deliberately.
- No probing of anything that is not a Kafka broker. Schema Registry, Connect and ksqlDB
  probes belong to their own services in M3 and M4.
- No feature *use*. `AclManagement` being present does not mean M1 lists ACLs; M5 does.

## Design references

`research/kafka/admin-capabilities.md` §0, the "Capability probing" table — the twelve rows
below are that table, and it is the behavioural source; ADR-030 ("features that need a newer
broker are probed and gated through the capability set, never assumed"); ADR-039 (the fold this
feeds, and §6's rule that only transport failures dim anything); DEVPLAN decision D4 (an
unreachable managed cluster is a `Section.Unavailable` inside a 200, never a dimmed capability);
`ARCHITECTURE.md` §9 (capabilities every hour and on reconnect); DEVPLAN §7 (the capability
probe suite row).

## Files to create

```
libs/kafka/src/kui/kafka/admin/ClusterFeature.scala
libs/kafka/src/kui/kafka/admin/CapabilityProbe.scala
libs/kafka/test/src/kui/kafka/admin/CapabilityProbeSuite.scala
libs/kafka/test/src/kui/kafka/admin/CapabilityProbeIntegrationSuite.scala
```

## Files to change

```
libs/kafka/src/kui/kafka/admin/KafkaClusterAdmin.scala   # implement `capabilities`
```

## Public Scala signatures to implement

```scala
package kui.kafka.admin

/** What a Kafka cluster can do, as far as KUI could determine by asking it.
  *
  * A closed enum rather than a string set: a feature name that only exists as a string will be
  * misspelled in a comparison somewhere, and the compiler will not care.
  */
enum ClusterFeature {
  case IncrementalAlterConfigs
  case ConfigDocumentation
  case AuthorizedOperations
  case AclManagement
  case AclEdit
  case ClientQuotas
  case TopicDeletion
  case LogDirs
  case KRaftQuorum
  case ProducersAndTransactions
  case TieredStorage
  case NewGroupProtocol
}

/** The probe's result. Three sets, not two.
  *
  * `unknown` is the set the reference implementations do not have and the one that matters
  * most: a probe that timed out tells you nothing, and recording it as "absent" would hide a
  * screen until the next hourly probe for a reason that was never true. Present means asked and
  * yes; absent means asked and no; unknown means could not ask.
  */
final case class ClusterFeatures(
    present: Set[ClusterFeature],
    absent: Set[ClusterFeature],
    unknown: Set[ClusterFeature],
    probedAt: Instant
) {
  def has(f: ClusterFeature): Boolean          // present only
  def isKnown(f: ClusterFeature): Boolean
  /** `present ++ absent ++ unknown` is every `ClusterFeature`, always. Asserted by a property
    * test, because a feature that appears in none of the three sets is a feature that silently
    * disappears from the UI. */
  def isTotal: Boolean
}

object ClusterFeatures {
  /** Everything unknown — the value before the first probe completes. */
  def unprobed(at: Instant): ClusterFeatures
}
```

```scala
package kui.kafka.admin

import cats.effect.Async
import kui.kernel.cluster.ClusterConnection

/** Asks a cluster what it supports.
  *
  * The single most important property of this object: **it never returns a failed effect.** A
  * probe is a diagnostic, and a diagnostic that can take the page down with it is worse than no
  * diagnostic. Every failure becomes `absent` or `unknown` according to the rules below.
  */
object CapabilityProbe {
  def probe[F[_]: Async](
      pool: AdminClientPool[F],
      connection: ClusterConnection,
      version: BrokerVersion,
      description: ClusterDescription
  ): F[ClusterFeatures]

  /** The per-probe timeout: `AdminTuning.requestTimeout / 4`, floored at two seconds. Probing
    * twelve features must not cost twelve request timeouts on a cluster that is answering
    * slowly, and a probe is by definition something the product can do without. */
  def probeTimeout(tuning: AdminTuning): FiniteDuration
}
```

### The probe table

Every row is `research/kafka/admin-capabilities.md` §0. "Version" rows are decided from
`BrokerVersion` with no call at all; "call" rows issue the cheapest request that answers the
question. All call rows run through `AdminBatch.perBroker`-style bounded parallelism —
`AdminTuning.parallelism` at a time — under `probeTimeout` each.

| Feature | How | Present when | Absent when | Unknown when |
| --- | --- | --- | --- | --- |
| `IncrementalAlterConfigs` | version | version ≥ 2.3 | version < 2.3 | version undetected |
| `ConfigDocumentation` | version | version ≥ 2.6 | version < 2.6 | version undetected |
| `AuthorizedOperations` | version | version ≥ 2.3 | version < 2.3 | version undetected |
| `ClientQuotas` | version | version ≥ 2.6 | version < 2.6 | version undetected |
| `AclManagement` | call: `describeAcls(AclBindingFilter.ANY)` with a limit of one | the call succeeds | `SecurityDisabledException`, `InvalidRequestException`, `UnsupportedVersionException`, `ClusterAuthorizationException` | timeout or a reconnect-class failure |
| `AclEdit` | derived | `AclManagement` present **and** `description.authorizedOperations` contains `Alter` or `All` | `AclManagement` absent, or authorized operations present and containing neither | `authorizedOperations` is `None` — ACLs may be off, which is not the same as "denied" |
| `TopicDeletion` | derived from KAFKA-008's broker configs | `delete.topic.enable` is `true` | it is `false` | the config could not be read (the managed-service downgrade) |
| `LogDirs` | call: `describeLogDirs` for the lowest node id | the call succeeds | `UnsupportedVersionException`, `ClusterAuthorizationException` | timeout or reconnect-class |
| `KRaftQuorum` | call: `describeMetadataQuorum` | the call succeeds | `UnsupportedVersionException` (ZooKeeper), `ClusterAuthorizationException` | timeout or reconnect-class |
| `ProducersAndTransactions` | version | version ≥ 2.8 | version < 2.8 | version undetected |
| `TieredStorage` | version | version ≥ 3.6 | version < 3.6 | version undetected |
| `NewGroupProtocol` | version | version ≥ 4.0 | version < 4.0 | version undetected |

Two rows deviate from the research's suggested probe, deliberately, and the "Deviations"
section says why: `ProducersAndTransactions` and `TieredStorage` are decided by version rather
than by a call.

### The downgrade rule, stated once

`UnsupportedVersionException`, `InvalidRequestException`, `SecurityDisabledException` and
`ClusterAuthorizationException` from a probe mean **absent**. Everything else — a timeout, a
reconnect-class failure, anything unmapped — means **unknown**. That is the whole rule, and it
is the managed-service behaviour the research names: MSK Serverless answers
`InvalidRequestException` to questions it does not implement, and treating that as an outage
would make every managed cluster look broken.

## ADRs this task must obey

ADR-030 (probe, never assume; the capability set is the gate), ADR-039 (this feeds the fold but
is not the fold; a Kafka feature being absent is `NotConfigured`-shaped, never `Unavailable`),
ADR-034 (nothing here produces a user-facing error at all), ADR-006, ADR-016 (no caching here —
the hourly cadence is the caller's).

## Library coordinates

None new.

## Acceptance criteria

```
$ ./mill libs.kafka.test
$ ./mill libs.kafka.compile         # clean under -Werror
```

Against the pinned 4.x PLAINTEXT container:

```scala
val f = admin.capabilities(connection).unsafeRunSync()
assert(f.isTotal)
assert(f.has(ClusterFeature.LogDirs))
assert(f.has(ClusterFeature.KRaftQuorum))
assert(f.has(ClusterFeature.IncrementalAlterConfigs))
assert(f.unknown.isEmpty)
```

And the property the whole task rests on, against an address with nothing behind it:

```scala
val f = admin.capabilities(deadConnection).unsafeRunSync()   // does NOT raise
assert(f.isTotal)
assert(f.present.isEmpty)
assert(f.absent.isEmpty)      // "could not ask" is not "does not have"
```

## Tests required

- `CapabilityProbeSuite` (unit, fake pool, `TestControl`):
  - **`resultIsAlwaysTotal`** (ScalaCheck over arbitrary combinations of per-probe outcomes):
    `present ++ absent ++ unknown` is every `ClusterFeature`, and the three sets are pairwise
    disjoint.
  - **`neverRaises`** (ScalaCheck): with every probe failing with an arbitrary generated
    throwable, the effect still succeeds.
  - `theDowngradeTable` — one assertion per (feature, exception) pair from the probe table:
    the four documented classes give `absent`, a timeout gives `unknown`, a
    `SaslAuthenticationException` gives `unknown`.
  - `versionDerivedFeaturesTable` — a table over 2.2, 2.3, 2.6, 2.8, 3.3, 3.6, 4.0 and
    "undetected", asserting the full expected `ClusterFeatures` for each. This table is the
    specification of ADR-030's gating and is the test that fails when someone changes a bound.
  - `undetectedVersionMakesEveryVersionFeatureUnknown` — not absent. The distinction is the
    reason `unknown` exists.
  - **`aclEditIsUnknownWhenAuthorizedOperationsAreAbsent`** — the "ACLs are off" case; asserting
    it is not `absent` is the point.
  - `aclEditIsAbsentWhenOperationsAreKnownAndLackAlter`.
  - `topicDeletionFollowsTheBrokerConfig`, including the unreadable-config case giving
    `unknown`.
  - **`probesRunInParallelAndAreBounded`** — with four call probes each taking one second of
    virtual time and `parallelism = 4`, the probe finishes in about one second, and never more
    than `parallelism` are in flight. Asserted with `TestControl`.
  - `eachProbeIsBoundedByProbeTimeout` — a probe that would take a full request timeout is cut
    at `probeTimeout` and reported `unknown`.
  - `probedAtIsTheTimeTheProbeFinished`.
- `CapabilityProbeIntegrationSuite` (Testcontainers, one PLAINTEXT broker, reusing KAFKA-007's
  container fixture):
  - `theFullTableAgainstALiveBroker` — assert the complete expected `ClusterFeatures` for the
    pinned image, so an image bump that changes what a cluster supports is visible as a failed
    assertion rather than as a screen quietly disappearing.
  - `aclManagementIsAbsentWithNoAuthorizerConfigured` — the container has none, so this asserts
    the `SecurityDisabledException` downgrade against a real broker.
  - `everyProbeCompletesWithinTheBudget` — the whole probe finishes inside
    `probeTimeout * 2`, which is the guard against a probe set that grows into a slow startup.

Because the nightly ADR-030 job runs a 2.8-compatible image (DEVPLAN §7), the integration suite
must not hard-code the 4.x expectations in one place: put the expected feature set behind a
helper keyed by the container's detected version, so the same suite passes on both images. That
helper is `libs/testkit`'s (CFGOP-004 owns the image matrix); until it exists, tag the
version-specific assertions so the nightly job can select them.

## Observability

- One INFO line per probe under `kui.kafka.admin`, listing the three sets by name:
  "cluster <id> supports [KRaftQuorum, LogDirs, ...]; not supported [...]; undetermined [...]".
  This is the single most useful line in the log when a user asks why a tab is missing, and it
  is written once an hour per cluster, which is cheap.
- One DEBUG line per individual probe with its outcome and the exception class where there was
  one.
- Each call probe inherits `kui.kafka.admin.duration` from `AdminClientPool.run`, with
  `operation` set to the probed call's name, so a slow probe is visible in the same histogram as
  a slow read.
- No dedicated metric for the feature set itself: `kui.capability.state` (ADR-039) is the
  gateway's and is a different thing — what the UI may show, not what the broker supports. Do
  not emit it from here.

## Degraded behavior

- **A probe times out:** `unknown`, and the caller keeps the previous `ClusterFeatures` if it
  has one (CLDOM-005's `SnapshotCell`). A feature does not disappear from the UI because one
  probe was slow.
- **Every probe fails:** every feature `unknown`, `probedAt` set, no error. The cluster row is
  `Section.Unavailable` for a different reason — it could not be described at all — and the
  capability set is simply uninformative, which is honest.
- **The cluster is a managed service that refuses most things:** most features `absent`, which
  is the correct and permanent answer, and the UI hides those screens without any suggestion
  that something is broken (ADR-039's `NotConfigured`, not `Unavailable`).
- **A broker below the ADR-030 minimum:** most version-derived features `absent`. KAFKA-007
  already logged the "below the supported minimum" warning; this task does not repeat it.
- **KUI newer than its table:** a `metadata.version` level above `MetadataVersions.highestKnownLevel`
  gives an undetected version, hence version-derived features `unknown` rather than `absent` —
  KUI does not claim a cluster lacks a feature just because KUI is old.

## Docs to update

None. CFGOP-008 writes the operator-facing capability table in
`docs/operations/configuration.md` from the probe table above, including which ACLs a probe
needs to answer "present" rather than "unknown".

## Deviations

- **`ProducersAndTransactions` and `TieredStorage` are decided by version, not by a call.** The
  research's probe column suggests `describeProducers` succeeding and a topic-config lookup for
  `remote.storage.enable`. Both need a *topic* to ask about, and M1 has no topic port and must
  not grow one (DEVPLAN §3, risk R-11). Version bounds — 2.8 for KIP-664 producers, 3.6 for
  tiered storage — are what the same research records in its "Min Kafka" columns, and neither
  feature is used by anything M1 ships, so a version-derived answer costs nothing today. M2's
  topic lane, which will have both a topic port and a caller, replaces these two rows with real
  probes; the table above and this note are what tells it to.

*(further deviations filled in by the implementer, in the same commit)*
