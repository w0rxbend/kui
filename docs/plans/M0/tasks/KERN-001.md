# KERN-001 — `libs/kernel`: identifiers and value objects

- **ID:** KERN-001
- **Title:** `libs/kernel`: identifiers and value objects
- **Milestone / Feature:** M0 / OT-005 (foundation for every later row)
- **Owner role:** Principal Scala Engineer, reviewed by the Chief Architect (shared kernel)
- **Context / service:** `libs/kernel` — the shared kernel, jointly owned (context map §"Shared Kernel")
- **Size:** M
- **Dependencies / blocked by:** BUILD-003

## Goal (user value)

Nobody passes a raw `String` where a `TopicName` belongs, in any service, ever. Primitive
obsession is designed out on day one rather than refactored out on day two hundred.

## Scope

The identifier and value-object subset of `docs/domain/context-map.md` §"Shared-kernel type
list" that M0 and M1 need. Implement **all** identifiers now (they are one-liners and adding
them later means touching the shared kernel repeatedly); implement only the value objects
listed below.

Identifiers, all as Scala 3 `opaque type` over `String` or `Int` with a smart constructor and
a `value` extension: `ClusterId`, `KafkaClusterId`, `TopicName`, `PartitionId`, `Offset`,
`BrokerId`, `GroupId`, `Subject`, `SchemaId`, `ConnectName`, `ConnectorName`, `TaskId`,
`CorrelationId`, `ServiceId`, `UserName`, `RoleName`.

Value objects: `TopicPartition`, `TopicPartitionReplica`, `OffsetRange` (half-open,
`from <= until`), `Host`, `Port`, `PositiveInt`, `ByteSize`.

`NameIndex` is **out of scope** (M2, ADR-038). `Rack` is out of scope (M1).

Validation rules, decided here and binding on every service:

| Type | Rule | Error |
| --- | --- | --- |
| `ClusterId` | slug: `^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$` (ADR-031) | `ValidationError.Format` |
| `TopicName` | non-empty, ≤ 249 chars, `^[a-zA-Z0-9._-]+$`, not `.` or `..` (Kafka's own rule) | `ValidationError.Format` |
| `PartitionId`, `BrokerId` | `>= 0` | `ValidationError.Range` |
| `Offset` | `>= 0` | `ValidationError.Range` |
| `Port` | `1 … 65535` | `ValidationError.Range` |
| `PositiveInt` | `> 0` | `ValidationError.Range` |
| `GroupId`, `Subject`, `ConnectName`, `ConnectorName`, `UserName`, `RoleName` | non-empty, ≤ 255 | `ValidationError.Format` |
| `CorrelationId` | non-empty, ≤ 64, `^[A-Za-z0-9-]+$` | `ValidationError.Format` |
| `OffsetRange` | `from <= until` | `ValidationError.Invariant` |

## Non-goals

No `KuiError` hierarchy (KERN-002 — this task defines only the `ValidationError` it needs, and
KERN-002 folds it into the hierarchy). No paging (KERN-003). No wire codecs (KERN-004: the
kernel has **no** Circe or Tapir dependency).

## Design references

`ARCHITECTURE.md` §4.1, `docs/domain/context-map.md` §"Shared-kernel type list", ADR-031
(cluster id strategy), PLAN §25 (domain modeling), ADR-007 (codecs live in contracts-core, not
here).

## Files to create

```
libs/kernel/src/kui/kernel/ids.scala
libs/kernel/src/kui/kernel/values.scala
libs/kernel/src/kui/kernel/ValidationError.scala
libs/kernel/test/src/kui/kernel/IdsSuite.scala
libs/kernel/test/src/kui/kernel/ValuesSuite.scala
libs/kernel/test/src/kui/kernel/Generators.scala      (moved to libs/testkit by KERN-007)
```

Delete `libs/kernel/src/kui/kernel/package.scala` and the `PlaceholderSuite` from BUILD-003.

## Public Scala signatures to implement

Copied from `ARCHITECTURE.md` §4.1 and completed:

```scala
package kui.kernel

opaque type ClusterId       = String
opaque type KafkaClusterId  = String
opaque type TopicName       = String
opaque type PartitionId     = Int
opaque type Offset          = Long
opaque type BrokerId        = Int
opaque type GroupId         = String
opaque type Subject         = String
opaque type SchemaId        = Int
opaque type ConnectName     = String
opaque type ConnectorName   = String
opaque type TaskId          = Int
opaque type CorrelationId   = String
opaque type ServiceId       = String
opaque type UserName        = String
opaque type RoleName        = String

object ClusterId:
  def from(raw: String): Either[ValidationError, ClusterId]
  /** Only for values that provably came from a validated source (config load, a decoded id
    * that was validated upstream). Never call this on user input. */
  def unsafe(raw: String): ClusterId
  extension (id: ClusterId) def value: String
  given Ordering[ClusterId]

final case class TopicPartition(topic: TopicName, partition: PartitionId)
final case class TopicPartitionReplica(tp: TopicPartition, broker: BrokerId)

final case class OffsetRange private (from: Offset, until: Offset):
  def isEmpty: Boolean
  def size: Long
object OffsetRange:
  def from(begin: Offset, until: Offset): Either[ValidationError, OffsetRange]

opaque type Host = String
opaque type Port = Int
opaque type PositiveInt = Int
opaque type ByteSize = Long

enum ValidationError:
  case Format(field: String, expected: String, got: String)
  case Range(field: String, min: Option[String], max: Option[String], got: String)
  case Invariant(field: String, rule: String)
```

Every companion follows the same shape: `from`, `unsafe`, `value`, `Ordering`, and a
`given CanEqual` where the opaque type is compared.

## Library coordinates

- `org.typelevel::cats-core::2.13.0` (shared scope) — `NonEmptyList`, `Validated`.
- `io.github.iltotore::iron::3.3.2` and `io.github.iltotore::iron-cats::3.3.2` (shared) —
  used for the numeric refinements; the opaque types wrap Iron-refined values where a
  constraint is purely structural.

No Circe. No Tapir. No cats-effect. The module must link on Scala.js.

## Acceptance criteria

```
$ ./mill libs.kernel.jvm.test           # green
$ ./mill libs.kernel.js.test            # green (same suites under Node)
$ ./mill checkArchitecture              # rule A6: no JVM-only dependency in the shared set
```

```scala
// must compile
val ok: Either[ValidationError, ClusterId] = ClusterId.from("prod-eu")
// must NOT compile (assert with munit's compileErrors)
val bad: ClusterId = "prod-eu"
```

## Tests required

- `IdsSuite` (unit + property, cross-compiled):
  - `roundTripsThroughFromAndValue` — property over generated valid strings.
  - `rejectsInvalidClusterId` — table of 12 rejected forms (uppercase, leading dash, empty,
    64+ chars, dots) each asserting the exact `ValidationError`.
  - `topicNameAcceptsKafkaLegalNamesAndRejectsDotAndDotDot`.
  - `opaqueTypesAreNotSubstitutable` — `compileErrors` asserting a raw `String` does not
    typecheck as a `TopicName`.
- `ValuesSuite` (unit + property):
  - `offsetRangeRejectsInvertedBounds`.
  - `offsetRangeSizeIsHalfOpen` — property: `OffsetRange.from(a, b).size == b - a`.
  - `portRejectsZeroAndAbove65535`.

## Observability

None — this module has no effects.

## Degraded behavior

Not applicable. Validation failures are values (`Either`), never exceptions.

## Docs to update

`docs/domain/context-map.md`: mark the implemented subset of the shared-kernel type list, and
note that `NameIndex` and `Rack` are still pending with their milestones.
