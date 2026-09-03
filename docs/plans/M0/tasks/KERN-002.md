# KERN-002 — `libs/kernel`: `KuiError`, `ErrorCode` and `Secret[A]`

- **ID:** KERN-002
- **Title:** `libs/kernel`: `KuiError`, `ErrorCode` and `Secret[A]`
- **Milestone / Feature:** M0 / OT-005, KU-007
- **Owner role:** Principal Scala Engineer, reviewed by the Chief Architect
- **Context / service:** `libs/kernel`
- **Size:** M
- **Dependencies / blocked by:** KERN-001

## Goal (user value)

Every failure anywhere in KUI has a stable machine-readable code an operator can search for,
and no secret value can be printed by accident — because the type that holds it refuses.

## Scope

1. The `KuiError` hierarchy of `ARCHITECTURE.md` §4.1 with its three branches and their
   concrete cases.
2. `ErrorCode` as a Scala 3 `enum` whose cases carry the stable `KUI-<AREA>-<NAME>` string and
   the HTTP status ADR-034 assigns, so the code table and the status mapping can never drift.
3. `Secret[A]`: a wrapper whose `toString`, `hashCode`-safe equality and every rendering path
   redact the value.

## Non-goals

No wire encoding (KERN-004 defines the envelope DTO and its codec). No Kafka error mapping
(M1, `libs/kafka`). No `Rbac` (M6).

## Design references

ADR-034 (envelope, code table, "codes are stable strings"), `ARCHITECTURE.md` §4.1 and §15,
PLAN §26, ADR-013 (`Secret[A]` semantics), `ARCHITECTURE.md` §14 ("Secrets: `Secret[A]`
everywhere").

## Files to create

```
libs/kernel/src/kui/kernel/error/ErrorCode.scala
libs/kernel/src/kui/kernel/error/KuiError.scala
libs/kernel/src/kui/kernel/Secret.scala
libs/kernel/test/src/kui/kernel/error/ErrorCodeSuite.scala
libs/kernel/test/src/kui/kernel/error/KuiErrorSuite.scala
libs/kernel/test/src/kui/kernel/SecretSuite.scala
```

## Public Scala signatures to implement

```scala
package kui.kernel.error

enum ErrorCode(val wire: String, val httpStatus: Int, val retryable: Boolean, val description: String):
  // `description` is one sentence, written for an operator reading `docs/api/error-codes.md`.
  // It lives on the enum rather than in a side table so a new case cannot be added without one
  // (KERN-008 generates the document from this field; ADR-034 amendment 2).
  // The description argument is elided from every case below to keep this sketch readable;
  // the implementation supplies one for each.
  case ClusterNotFound     extends ErrorCode("KUI-CLUSTER-NOT-FOUND", 404, false)
  case TopicNotFound       extends ErrorCode("KUI-TOPIC-NOT-FOUND", 404, false)
  case SchemaNotFound      extends ErrorCode("KUI-SCHEMA-NOT-FOUND", 404, false)
  case Validation          extends ErrorCode("KUI-VALIDATION", 400, false)
  case ReadOnly            extends ErrorCode("KUI-READ-ONLY", 405, false)
  case ConnectRebalancing  extends ErrorCode("KUI-CONNECT-REBALANCING", 409, true)
  case InvalidState        extends ErrorCode("KUI-INVALID-STATE", 409, false)
  case Timeout             extends ErrorCode("KUI-TIMEOUT", 408, true)
  case FilterCompile       extends ErrorCode("KUI-FILTER-COMPILE", 400, false)
  case ConnectorOffsets    extends ErrorCode("KUI-CONNECTOR-OFFSETS", 400, false)
  case UpstreamKsql        extends ErrorCode("KUI-UPSTREAM-KSQL", 502, true)
  case UpstreamAuth        extends ErrorCode("KUI-UPSTREAM-AUTH", 502, false)
  case UpstreamUnavailable extends ErrorCode("KUI-UPSTREAM-UNAVAILABLE", 503, true)
  case Unsupported         extends ErrorCode("KUI-UNSUPPORTED", 501, false)
  case Forbidden           extends ErrorCode("KUI-FORBIDDEN", 403, false)
  case Unauthenticated     extends ErrorCode("KUI-UNAUTHENTICATED", 401, false)
  case CursorExpired       extends ErrorCode("KUI-CURSOR-EXPIRED", 400, false)
  case CursorInvalid       extends ErrorCode("KUI-CURSOR-INVALID", 400, false)
  case CursorTooLarge      extends ErrorCode("KUI-CURSOR-TOO-LARGE", 400, false)
  case ConfigVersionConflict extends ErrorCode("KUI-CONFIG-VERSION-CONFLICT", 409, false)
  case RouteNotFound       extends ErrorCode("KUI-ROUTE-NOT-FOUND", 404, false)
  case Internal            extends ErrorCode("KUI-INTERNAL", 500, false)

object ErrorCode:
  def fromWire(s: String): Option[ErrorCode]

final case class FieldError(field: Option[String], restrictions: List[String])

sealed trait KuiError:
  def code: ErrorCode
  def message: String
  def details: List[FieldError] = Nil

sealed trait DomainError         extends KuiError
sealed trait ApplicationError    extends KuiError
sealed trait InfrastructureError extends KuiError

object ApplicationError:
  final case class NotFound(what: String, id: String, code: ErrorCode) extends ApplicationError
  final case class Conflict(message: String)                            extends ApplicationError
  final case class Forbidden(message: String)                           extends ApplicationError
  final case class Unauthenticated(message: String)                     extends ApplicationError
  final case class Unsupported(feature: String)                         extends ApplicationError
  final case class InvalidState(message: String)                        extends ApplicationError
  final case class Invalid(message: String, fields: List[FieldError])   extends ApplicationError

object InfrastructureError:
  final case class Unreachable(upstream: String, cause: String)  extends InfrastructureError
  final case class Timeout(operation: String, afterMs: Long)     extends InfrastructureError
  final case class AuthFailed(upstream: String)                  extends InfrastructureError
  final case class Upstream(upstream: String, status: Int)       extends InfrastructureError
  final case class CircuitOpen(upstream: String, since: Instant) extends InfrastructureError
```

`DomainError` gets one case in M0 — `DomainError.InvariantViolation(rule: String)` — wrapping
`ValidationError` from KERN-001; services add their own cases in their `domain` modules.

**Rule for `message`:** it is user-facing display text. It must never contain an upstream
response body, a stack trace, a JAAS string, a URL with credentials, or a `Secret` value
(ADR-034). `Upstream` deliberately carries only `status`, never `body`.

```scala
package kui.kernel

final class Secret[+A] private (private val underlying: A):
  def value: A                                   // the ONLY accessor; grep-able in review
  def map[B](f: A => B): Secret[B]
  override def toString: String = "Secret(***)"
  override def equals(that: Any): Boolean        // constant-time for Secret[String]
  override def hashCode: Int                     // constant, does not leak the value

object Secret:
  def apply[A](a: A): Secret[A]
  given [A]: CanEqual[Secret[A], Secret[A]] = CanEqual.derived
```

## Library coordinates

Unchanged from KERN-001 (cats-core, iron). No new dependency.

## Acceptance criteria

```
$ ./mill libs.kernel.jvm.test
$ ./mill libs.kernel.js.test
```

```scala
// must hold
assertEquals(Secret("hunter2").toString, "Secret(***)")
assertEquals(s"${Secret("hunter2")}", "Secret(***)")
assertEquals(ErrorCode.values.map(_.wire).distinct.length, ErrorCode.values.length)
```

## Tests required

- `ErrorCodeSuite` (unit):
  - `wireStringsAreUnique`.
  - `wireStringsMatchTheNamingConvention` — every code matches `^KUI-[A-Z]+(-[A-Z]+)*$`.
  - `fromWireIsInverseOfWire` — property over `ErrorCode.values`.
  - `everyCodeHasAValidHttpStatus` — 400 ≤ status ≤ 599.
- `KuiErrorSuite` (unit): every concrete case maps to the code ADR-034 assigns (a table test,
  one row per case — this is the test that fails if someone renames a code).
- `SecretSuite` (unit + property):
  - `toStringRedacts` — property over arbitrary strings: the output never contains the input
    (for inputs longer than zero characters).
  - `interpolationRedacts` — both `s""` and `f""` interpolation.
  - `equalsComparesValues`, `hashCodeIsConstant`.
  - `secretIsNotAProductAndDoesNotDeriveToString` — `compileErrors` asserting `Secret` is not
    a `case class` (a case class would generate a leaking `toString`).

## Observability

None here, but this task fixes the vocabulary every later log line uses: `error.code` is the
`wire` string, never the class name.

## Degraded behavior

Not applicable.

## Docs to update

None yet — `docs/api/error-codes.md` is generated in KERN-008.

## Deviations

- **`ApplicationError.Conflict` carries `KUI-INVALID-STATE`.** ADR-034's table has no code of
  its own for a conflict: `KUI-CONFIG-VERSION-CONFLICT` is specifically about the dynamic
  configuration store, and inventing a general `KUI-CONFLICT` would have been a contract change
  made in passing. Both `Conflict` and `InvalidState` therefore serve 409 with
  `KUI-INVALID-STATE`; the two cases stay distinct in code because they read differently at a
  call site, and a general conflict code can be added later without moving anything.
- **`InfrastructureError.Upstream` has no `body`, and `Unreachable.cause` never reaches
  `message`.** `ARCHITECTURE.md` §4.1's sketch showed `Upstream(status, body)`. A body is the
  most reliable way for an upstream's error text — which routinely contains hosts, tokens and
  JAAS strings — to end up in a user-visible response, and ADR-034 forbids exactly that. The
  case carries the status only. `Unreachable` keeps `cause` for the log but builds its message
  from the upstream name alone, and `KuiErrorSuite` asserts that a cause containing a password
  does not appear in the message.
- **`Secret.equals` is annotated `@nowarn("msg=pattern selector")`.** Overriding `equals`
  forces the parameter type `Any`, and `-source:future` warns that `Any` is not a legal match
  selector (only `Matchable` is). There is no way to satisfy both, and `-Werror` turns the
  warning into a build failure, so it is silenced on that one method.
- **`DomainError.InvariantViolation` takes an optional list of `FieldError`s**, so that
  `DomainError.fromValidation` can carry the field a rejected value object was about into the
  envelope's `details` array rather than losing it in prose.
