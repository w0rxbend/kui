# CLDOM-001 — Cluster domain: `ClusterProfile` and `ClusterRef`

- **ID:** CLDOM-001
- **Title:** Cluster domain: `ClusterProfile` and `ClusterRef`; `Ping` scheduled for deletion
- **Milestone / Feature:** M1 / CL-001, CL-002, OT-003
- **Owner role:** Domain Architect (Cluster Registry context)
- **Size:** M
- **Dependencies / blocked by:** KAFKA-001

## M1 gate review amendment — `Ping`

**F-01, blocker, fixed.** This spec said CLAPI-002 deletes the whole `Ping` family; CLAPI-002
said CLDOM-001 deletes the domain and application halves and CLAPI-004 deletes the rest. Each
deferred to the other, so as written `Ping` is never deleted, which DEVPLAN §1 makes a milestone
failure.

**Decided: `Ping` is deleted, entirely, in one commit, by CLAPI-004**, which by then depends on
CLDOM-006 and CLAPI-002 and so has every replacement in place. CLAPI-004 is granted an explicit
area-boundary exception (DEVPLAN §6.5) to delete the six `services/cluster/{domain,application}`
`Ping` files — deletion only, no other change to those modules. This task changes nothing about
`Ping`: it leaves it compiling alongside the real model, exactly as the section below describes.

## Goal (user value)

The first real value object of the first real domain: *what KUI knows about one configured
cluster*. Every later cluster screen, every other Kafka-facing service and the metadata store all
address a cluster through this type, so it is built once, with its rules stated where the rules
belong, before anything reads it.

## Scope

1. `ClusterProfile` — the aggregate root of the Cluster Registry context: identity, display name,
   how to reach the cluster, how to authenticate to it, the admin tuning knobs, whether it is
   read-only, its colour tag, the optimistic version it was last read at, and where the value came
   from (static configuration, the store, or both).
2. `ClusterRef` — the `(id, displayName)` pair that list screens, log lines, cache keys and error
   messages need when they do not need connection settings. It exists so that nothing passes a
   whole profile — with its secrets — into a logger or a map key by habit.
3. `ProfileVersion` — the optimistic-concurrency version carried by a profile.
4. `ProfileOrigin` — where this profile came from.
5. `ClusterProfile.from`, the only way to build one, returning `Either[DomainError, ClusterProfile]`
   with **all** violations accumulated, not the first.
6. `docs/domain/cluster.md` rewritten from "scaffolded, not modelled" to describe the profile.

## Non-goals

- **No connection or security ADT of its own.** `BootstrapServers`, `ClusterSecurity`,
  `ClientProperties` and `AdminTuning` come from `kui.kernel.cluster` (KAFKA-001, DEVPLAN §10 D1).
  Re-declaring any of them in the domain is the exact duplication that decision was taken to
  prevent, and rule A1 permits `libs/kernel`, so there is nothing to work around.
- **No topology.** `ClusterDescription`, `Broker`, `LogDir`, `ConfigEntry`, `KafkaVersion`,
  `QuorumInfo` and `ClusterFeature` are CLDOM-002. A profile says how to reach a cluster; it never
  holds what was found there.
- **No ports.** CLDOM-003.
- **No Schema Registry, Connect, ksqlDB or metrics endpoints on the profile, and no serde,
  masking or audit declarations.** `ARCHITECTURE.md` §10 describes the eventual `ClusterProfile`
  as the published language carrying all of those. They arrive with the milestone that reads
  them (M3, M4, M5, M8). A field nothing reads is a field nobody maintains, and every one of them
  would have to be encoded, redacted, stored and versioned in M1 for no caller.
- **No wire codecs.** No Circe, no Tapir, no Chimney anywhere in `domain` (rule A1 and A3). The
  redacted DTO is CLAPI-001's.
- **`Ping` is not deleted in this task.** See "The `Ping` question" below.

## The `Ping` question — decided

DEVPLAN §6.2 titles this task "`Ping` deleted". Executing that literally cannot end on a green
`main` from inside this task's area boundary. `Ping` is referenced by
`services/cluster/contract/.../PingDtos.scala`, `ClusterEndpoints.ping`, `PingMapping.scala` in
`api`, the wiring in `app`, two golden files and four suites — all of them in the `CLAPI-` area,
which DEVPLAN §6.5 forbids this area to touch.

**Decision: the whole `Ping` family is deleted in one commit by CLAPI-002**, which is the task
that replaces `/internal/v1/ping` with the real read endpoints and therefore already has to edit
every one of those files. That commit deletes, together:

```
services/cluster/domain/src/kui/cluster/domain/Ping.scala
services/cluster/domain/test/src/kui/cluster/domain/PingSuite.scala
services/cluster/application/src/kui/cluster/application/PingUseCase.scala
services/cluster/application/test/src/kui/cluster/application/PingUseCaseSuite.scala
services/cluster/contract/src/kui/cluster/contract/dto/PingDtos.scala
services/cluster/contract/test/src/kui/cluster/contract/PingDtosSuite.scala
services/cluster/contract/test/resources/golden/ping-response.json
services/cluster/api/src/kui/cluster/api/PingMapping.scala
```

plus the `ping` entry in `ClusterEndpoints.all` and the `PingUseCase` line in `ClusterWiring`.
Until then `Ping` compiles alongside the real model and harms nothing: it is 20 lines with no
dependency on anything this task adds.

The alternative — deleting the domain half here and leaving `main` red until CLAPI-002 lands — is
rejected because DEVPLAN §6 requires every task to end on a green `main`, and a red `main` shared
by seven parallel lanes stops six of them.

## Design references

- ADR-031 — cluster identity: `ClusterId` is a slug of the configured name; renaming produces a
  new id; the Kafka-reported `KafkaClusterId` is a *finding*, not part of the profile.
- ADR-022 — typed cluster auth: secrets travel as `Secret[A]`; keystores travel as inline bytes;
  the `properties` map is an override layer applied last.
- ADR-036 — the cluster service is the single writer of `kui.clusters[]`; the profile carries a
  version that other services use as an ETag.
- ADR-041 A1 — `domain` may depend on `libs.kernel` and cats-core, and nothing else.
- DEVPLAN §10 D1 — the connection/security ADT lives in `kui.kernel.cluster`.
- `docs/domain/kafka-glossary.md` §1 "Cluster" — a KUI cluster entry maps to exactly one Kafka
  cluster; the KUI name is a KUI-side label, not a Kafka concept.
- `ARCHITECTURE.md` §10 (configuration ownership), §14 (secrets never in a response or a log).

## What KAFKA-001 must provide (the contract this task compiles against)

This task consumes, and does not define, the following from `libs/kernel`
(`libs/kernel/src/kui/kernel/cluster/`). If KAFKA-001 ships a different shape, this task's
`ClusterProfile` field types change and nothing else does.

```scala
package kui.kernel.cluster

/** A validated, non-empty `host:port,host:port` list. */
opaque type BootstrapServers = String
object BootstrapServers:
  def from(raw: String): Either[ValidationError, BootstrapServers]
  def unsafe(raw: String): BootstrapServers
  extension (b: BootstrapServers) def value: String

/** How KUI authenticates to a cluster. Every secret-bearing case holds `Secret[String]` or
  * `Secret[Array[Byte]]`, so `toString` is redacted by construction. */
enum ClusterSecurity:
  case Plaintext
  case SaslPlaintext(mechanism: SaslMechanism)
  case SaslSsl(mechanism: SaslMechanism, truststore: Option[KeystoreMaterial], ...)
  case Ssl(truststore: Option[KeystoreMaterial], keystore: Option[KeystoreMaterial], ...)

/** The raw `key -> value` overrides an operator may set, applied after everything KUI renders. */
opaque type ClientProperties = Map[String, Secret[String]]

/** Timeouts, chunk sizes and parallelism for admin calls against one cluster. */
final case class AdminTuning(
  requestTimeout: FiniteDuration,
  apiTimeout: FiniteDuration,
  topicChunkSize: PositiveInt,
  groupChunkSize: PositiveInt,
  partitionChunkSize: PositiveInt,
  parallelism: PositiveInt
)
object AdminTuning:
  val Default: AdminTuning
```

## Files to create or change

```
services/cluster/domain/src/kui/cluster/domain/ClusterProfile.scala      (new)
services/cluster/domain/src/kui/cluster/domain/ClusterRef.scala          (new)
services/cluster/domain/test/src/kui/cluster/domain/ClusterProfileSuite.scala (new)
services/cluster/domain/test/src/kui/cluster/domain/ClusterProfileFixtures.scala (new)
docs/domain/cluster.md                                                   (rewritten)
build.mill                                                               (no change expected)
```

`build.mill` is listed because `services.cluster.domain` already depends on `libs.kernel.jvm` and
cats-core, which is everything this task needs. If KAFKA-001 puts `kui.kernel.cluster` in a
*separate* module rather than inside `libs/kernel`, this task adds that `moduleDep` and nothing
else — and says so in its Implementation Report, because rule A1 would then need amending and that
is a CFGOP-003 conversation.

## Public Scala signatures to implement

```scala
package kui.cluster.domain

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

import cats.data.NonEmptyList
import kui.kernel.ClusterId
import kui.kernel.cluster.{AdminTuning, BootstrapServers, ClientProperties, ClusterSecurity}
import kui.kernel.error.{DomainError, FieldError}

/** The optimistic-concurrency version of one stored profile.
  *
  * `0` means "this profile has never been written to the metadata store": it came from static
  * configuration only. The store's own record version (STORE-001) is mapped onto this by the
  * infrastructure adapter; the domain deliberately does not know that a version is a Kafka offset
  * or a counter, only that it increases and that a stale one loses a write.
  */
opaque type ProfileVersion = Long

object ProfileVersion:
  val Static: ProfileVersion = 0L
  def from(raw: Long): Either[kui.kernel.ValidationError, ProfileVersion]
  def unsafe(raw: Long): ProfileVersion
  extension (v: ProfileVersion)
    def value: Long
    def next: ProfileVersion
  given Ordering[ProfileVersion]
  given CanEqual[ProfileVersion, ProfileVersion]

/** Where a profile's field values came from. The UI shows it (a stored cluster can be edited in
  * M8; a statically configured one cannot), and the registry's precedence rule (CLDOM-004) is
  * stated in terms of it.
  */
enum ProfileOrigin:
  /** Only `kui.clusters[]` in this process's configuration describes this cluster. */
  case Static
  /** Only a `cluster/<id>` record in `__kui_config` describes it. */
  case Stored
  /** Both do, and the stored record won field by field (CLDOM-004). */
  case StaticThenStored

object ProfileOrigin:
  given CanEqual[ProfileOrigin, ProfileOrigin] = CanEqual.derived

/** Everything KUI needs in order to talk to one configured Kafka cluster.
  *
  * The private constructor plus `from` is the M0 `Ping` pattern and is not decoration here: a
  * profile with an empty bootstrap list or a blank display name would fail at the point a Kafka
  * client is built, which is inside an adapter, inside a refresh loop, on a background fiber —
  * the furthest possible place from the operator who typed it.
  */
final case class ClusterProfile private (
    id: ClusterId,
    displayName: String,
    bootstrap: BootstrapServers,
    security: ClusterSecurity,
    properties: ClientProperties,
    admin: AdminTuning,
    readOnly: Boolean,
    colour: Option[ColourTag],
    version: ProfileVersion,
    origin: ProfileOrigin
):
  /** The cheap identity of this profile, for logs, map keys and list rows. */
  def ref: ClusterRef

  /** `displayName` if set to something other than the id, else the id's own text. Used in error
    * messages, which must name a cluster the way the operator wrote it. */
  def label: String

object ClusterProfile:
  val MaxDisplayNameLength: Int = 128

  /** Builds a profile, accumulating **every** violation.
    *
    * Accumulation rather than fail-fast because the caller is either the startup configuration
    * validator — whose exit criterion is that an unknown key, a missing secret and an invalid URL
    * are reported *together, in one message* — or the M8 wizard, whose form must highlight every
    * bad field at once.
    */
  def from(
      id: ClusterId,
      displayName: String,
      bootstrap: BootstrapServers,
      security: ClusterSecurity,
      properties: ClientProperties,
      admin: AdminTuning,
      readOnly: Boolean,
      colour: Option[String],
      version: ProfileVersion,
      origin: ProfileOrigin
  ): Either[DomainError, ClusterProfile]

  given CanEqual[ClusterProfile, ClusterProfile] = CanEqual.derived

/** A cluster's identity and its human label, and nothing that could leak. */
final case class ClusterRef(id: ClusterId, displayName: String)

object ClusterRef:
  given Ordering[ClusterRef] = Ordering.by(r => (r.displayName, r.id.value))
  given CanEqual[ClusterRef, ClusterRef] = CanEqual.derived

/** The colour an operator assigned to a cluster so that production and staging do not look alike
  * in the switcher (CLUI-006). A closed set, not a free CSS colour: an arbitrary string here would
  * be user-controlled text interpolated into a stylesheet, and the design system has a fixed
  * palette anyway (`research/design/REFERENCE.md`).
  */
enum ColourTag:
  case Slate, Blue, Green, Amber, Red, Violet, Teal

object ColourTag:
  def from(raw: String): Either[kui.kernel.ValidationError, ColourTag]
  extension (c: ColourTag) def token: String   // the lowercase name used as a CSS token suffix
  given CanEqual[ColourTag, ColourTag] = CanEqual.derived
```

### The validation rules `from` enforces

| Field | Rule | Failure message (the `FieldError` restriction text) |
| --- | --- | --- |
| `displayName` | non-blank after trim, ≤ 128 characters | `must be 1 to 128 non-blank characters` |
| `displayName` | no ASCII control characters | `must not contain control characters` |
| `colour` | one of the `ColourTag` names, case-insensitively | `must be one of slate, blue, green, amber, red, violet, teal` |
| `version` | ≥ 0 | `must not be negative` |
| `properties` | no key is empty or blank | `property names must not be blank` |
| `properties` | no key is one of `bootstrap.servers`, `client.id`, `security.protocol`, `sasl.mechanism`, `sasl.jaas.config` | `'<key>' is rendered by KUI and cannot be overridden; change the cluster's security settings instead` |

The last rule is the one to get right and the reason the check lives in the domain rather than in
the Ciris slice. `ClientProperties` is an escape hatch for the properties KUI does not model
(ADR-022), and an operator who sets `sasl.jaas.config` there is silently replacing the JAAS string
KUI assembled — defeating the quoting and escaping that KAFKA-002 exists to get right, and doing
it in a value that is then rendered into a Kafka client with no further checking. It is refused,
by name, with a message that says what to do instead. `bootstrap.servers` and `client.id` are on
the list for the duller reason that overriding them makes the profile lie about which cluster it
addresses and breaks the per-client attribution of `research/kafka/admin-capabilities.md` §0.

`displayName` is a plain `String` and not an opaque `ClusterName`, which is a deviation from
`docs/domain/kafka-glossary.md` §1. The glossary's `ClusterName` would have to live in
`libs/kernel` to be usable by the config slice (CFGOP-001), the contract DTO (CLAPI-001) and this
module at once, and `kui.kernel.cluster` is KAFKA-001's file set with a fixed field list
(DEVPLAN §5.2). A domain-local opaque type would be visible to nobody else and would buy one
`.value` call per use site. The identity that must not be confused with a `String` is `ClusterId`,
and that one *is* an opaque kernel type. Recorded in the Implementation Report; if a later
milestone finds a second consumer, promoting it to the kernel is a mechanical change.

## Library coordinates

No new dependencies. `services.cluster.domain` keeps exactly:

```
org.typelevel::cats-core::2.13.0     (Validated, for accumulating the failures above)
moduleDeps = Seq(libs.kernel.jvm)
```

Test module (`services.cluster.domain.test`) keeps `libs.testkit.jvm`, which brings
`org.scalameta::munit::1.3.6`, `org.scalameta::munit-scalacheck::1.3.1` and
`org.scalacheck::scalacheck::1.20.0`.

## Acceptance criteria

```
$ ./mill services.cluster.domain.compile
$ ./mill services.cluster.domain.test
Test run kui.cluster.domain.ClusterProfileSuite finished: 0 failed, 0 ignored, 11 total
Test run kui.cluster.domain.PingSuite finished: 0 failed, 0 ignored, 6 total
SUCCESS

$ ./mill checkArchitecture
checkArchitecture: <n> modules, no layering violations

$ ./mill services.cluster.domain.checkFormat && ./mill services.cluster.domain.fix --check
```

A concrete accumulation check, to run by hand once and paste into the Implementation Report — a
profile with a blank name, a bad colour and a forbidden property key must produce **three**
`FieldError`s in one `DomainError`, not one:

```scala
ClusterProfile.from(
  id = ClusterId.unsafe("prod"), displayName = "   ",
  bootstrap = BootstrapServers.unsafe("a:9092"), security = ClusterSecurity.Plaintext,
  properties = ClientProperties.unsafe(Map("sasl.jaas.config" -> Secret("x"))),
  admin = AdminTuning.Default, readOnly = false, colour = Some("neon"),
  version = ProfileVersion.Static, origin = ProfileOrigin.Static
)
// Left(DomainError.InvariantViolation(_, List(FieldError(Some("displayName"), _),
//                                             FieldError(Some("colour"), _),
//                                             FieldError(Some("properties"), _))))
```

## Tests required

`ClusterProfileSuite` (MUnit + ScalaCheck), in `services/cluster/domain/test`:

1. `acceptsAMinimalPlaintextProfile` — id, name, one bootstrap host, `Plaintext`, no properties,
   `AdminTuning.Default` builds a `Right`.
2. `rejectsABlankDisplayName` — `"   "` and `""` both fail with a `FieldError` whose field is
   `displayName`.
3. `rejectsAnOverLongDisplayName` — 129 characters fails, 128 succeeds (boundary asserted on both
   sides, because an off-by-one here is invisible).
4. `rejectsControlCharactersInTheDisplayName` — `"prod"` fails.
5. `accumulatesEveryViolation` — the three-violation case above; asserts `details.size == 3` and
   the exact set of field names. This is the test that fails if someone rewrites `from` as a
   `for`-comprehension over `Either`, which short-circuits.
6. `rejectsEveryReservedPropertyKey` — table-driven over the five reserved keys; each fails, and
   the message contains the offending key.
7. `acceptsANonReservedPropertyKey` — `"reconnect.backoff.ms" -> "100"` succeeds.
8. `colourIsCaseInsensitiveAndClosed` — `"AMBER"` gives `ColourTag.Amber`; `"neon"` fails.
9. `refAndLabelDoNotCarryConnectionSettings` (property) — for an arbitrary generated profile,
   neither `ref.toString` nor `label` contains the bootstrap string or any secret's plaintext.
10. `profileToStringRedactsEverySecret` (property) — generate a profile whose every secret is the
    distinctive token `"S3CR3T-CANARY"`; assert `profile.toString` does not contain it. Use
    `kui.testkit.RedactionAssertions`. This is the domain's half of risk R-12, and it is here
    rather than only in the contract test because the first place a profile is printed is a log
    line, not a response body.
11. `versionOrderingAndNext` — `ProfileVersion.Static.next.value == 1`; negative `from` fails.

`ClusterProfileFixtures` (in the test module, not in `src`): `plaintext(id)`, `saslScram(id)` and
`arbitraryProfile: Arbitrary[ClusterProfile]`, used by this suite and by every CLDOM suite after
it. It lives in the domain's test module rather than in `libs/testkit` because rule A5 forbids a
`libs` module depending on a service, and a generator of `ClusterProfile` necessarily does.
CLDOM-004..007's suites depend on `services.cluster.domain.test` for it; that `moduleDep` is added
by the task that first needs it.

## Observability

None. `ClusterProfile` is pure data with no effects. Two requirements it must satisfy *for*
observability, both asserted above:

- `toString` is safe to put on a log line (test 10).
- `ref` is the value that goes into the structured log field `cluster.id` and into span
  attributes; `ClusterProfile` itself must never be an attribute value.

## Degraded behavior

Not applicable — no upstream. The degraded story a profile participates in is CLDOM-004's: a
profile that came only from static configuration (`ProfileOrigin.Static`) is what the registry
keeps serving when the metadata store is unreachable.

## Docs to update

`docs/domain/cluster.md`:

- Replace the "Status in M0" section with "Status in M1", describing `ClusterProfile` as the
  aggregate root, `ClusterRef` as its identity, and the field table above.
- State the ADR-031 rule in the context's own words: the id is a slug of the name, renaming makes
  a new id, and the Kafka-reported cluster id is a *finding* about the cluster and not part of the
  profile.
- Replace the "What arrives in M1" section with a one-line note: `Ping` is removed together with
  its endpoint in CLAPI-002, and it is the only thing left in this context that is not real.
- Do **not** yet document the topology, the ports or the registry — CLDOM-002, CLDOM-003 and
  CLDOM-004 each add their own section in their own commit.

## Degraded / failure notes for the worker

If KAFKA-001 has not landed when this task starts, do not stub `kui.kernel.cluster`. Wait, or take
CLDOM-002's model work first: a local stub would be a second definition of the ADT, which is
precisely the outcome DEVPLAN §10 D1 exists to prevent, and it would be discovered only when the
two disagree.
