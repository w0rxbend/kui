# KAFKA-005 — `libs/kafka`: `KafkaErrorMapper` and `BatchResult`, total over the documented exceptions

- **ID:** KAFKA-005
- **Title:** `libs/kafka`: `KafkaErrorMapper` and `BatchResult`, total over the documented exceptions
- **Milestone / Feature:** M1 / OT-005, KU-010
- **Owner role:** Principal Scala Engineer, reviewed by the Chief Architect
- **Context / service:** `libs/kafka`
- **Size:** M
- **Dependencies / blocked by:** KAFKA-004

## Goal (user value)

Every failure a Kafka cluster can hand KUI turns into an error whose code an operator can
search for and whose HTTP status is the right one — a 403 when the user lacks an ACL, a 404
when a topic is not there, a 503 when the broker is unreachable — instead of a 500 with a Java
class name in it. And a request that partly succeeded returns what it got, saying explicitly
which keys it did not, rather than dropping them.

## Why this is one of the four first-movers

DEVPLAN §6.4 lists this task among the four to start early, and the reason is that everything
downstream keys on this mapping. Which failures dim a capability is decided by whether they are
an `InfrastructureError` or an `ApplicationError` (ADR-039 §6). Which produce a 404 rather than
a 500 is decided by `ErrorCode` (ADR-034). Which mean "the connection is dead" was decided in
KAFKA-004 and is asserted against here. Ten services in M2–M8 inherit all three. A property
test that the mapper is total is cheap now and is a retrofit across ten services later.

## Scope

1. `KafkaErrorMapper` — `Throwable` to `KuiError`, **total by construction**, with a documented
   row for every exception class `research/kafka/admin-capabilities.md` §0 and §1 name.
2. `BatchResult[K, A]` and `SkipReason` — the shape a per-key partial result takes, with the
   rule that a dropped key is always accompanied by a reason.
3. `KafkaErrorMapper.suppressible` — the classification that turns a per-key failure into a
   `Skipped` entry rather than failing the whole call, which is what makes the "authenticates
   but authorizes nothing" fault-injection scenario render a page instead of an error.
4. Message sanitization: a `KuiError.message` from this mapper never carries a broker's raw
   exception text.

## Non-goals

- No batching mechanics (KAFKA-006 consumes `BatchResult`; this task defines it).
- No HTTP concerns. `ErrorEnvelope.statusOf` is the single code-to-status mapping in the system
  (`libs/contracts-core`, M0) and this task does not touch it; it only chooses `ErrorCode`s
  that already exist.
- **No new `ErrorCode` cases.** `libs/kernel/src/kui/kernel/error/ErrorCode.scala` is outside
  this lane's file boundary (DEVPLAN §6.5) and adding a wire code is a contract change. See
  "Deviations" for the one place this pinches and how it is handled.
- No group, topic or ACL *operations*. The mapper has rows for their exceptions because a
  cluster throws them at cluster-level calls too (Event Hubs answers a broker `describeConfigs`
  with `UnknownTopicOrPartitionException`), not because M1 calls those APIs.

## Design references

`research/kafka/admin-capabilities.md` §0 (exception wrapping, managed-service quirks,
invalidation, partial failure) and §1 (the per-operation error column — this is the behavioural
source and it outranks any sketch), ADR-034 (the error hierarchy, the code table, "message is
user-facing display text" and what it must never contain), ADR-039 §6 (only
`InfrastructureError` dims a capability — the reason the split matters), ADR-006 ("exhaustive
`KafkaErrorMapper` with a property test over the documented exception classes"),
`ARCHITECTURE.md` §4.2 (`BatchResult`, "never silent drops"), DEVPLAN §7 (the error-mapping
suite row) and §6.4.

## Files to create

```
libs/kafka/src/kui/kafka/BatchResult.scala
libs/kafka/src/kui/kafka/SkipReason.scala
libs/kafka/src/kui/kafka/KafkaErrorMapper.scala
libs/kafka/test/src/kui/kafka/KafkaErrorMapperSuite.scala
libs/kafka/test/src/kui/kafka/BatchResultSuite.scala
```

## Files to change

None. `libs.kafka` already exists (KAFKA-004) and gains no dependency.

## Public Scala signatures to implement

```scala
package kui.kafka

import kui.kernel.error.ErrorCode

/** Why one key of a batch is missing from the result.
  *
  * It is an ADT rather than a string because the caller acts on it: `NotAuthorized` renders a
  * lock icon, `Unsupported` renders a dash, `NoLeader` renders "offline", and `Failed` renders
  * the code. A string would be rendered as prose or, more likely, ignored.
  */
enum SkipReason {
  case NotFound(detail: String)
  case NotAuthorized(detail: String)
  case Unsupported(feature: String)
  case NoLeader
  case Failed(code: ErrorCode, detail: String)

  /** One short sentence for a tooltip or a log line. Never carries a broker's raw message. */
  def message: String
}
```

```scala
package kui.kafka

/** A per-key result where some keys are missing, and every missing key says why.
  *
  * The invariant that makes this type worth having: `values.keySet` and `skipped.keySet` are
  * disjoint, and their union is the set of keys that were asked for. A key cannot vanish. That
  * is the whole difference from the reference implementations, which return an empty map on
  * error and leave the caller to guess whether the cluster has no log directories or would not
  * say (`research/kafka/admin-capabilities.md` §1, "Log dirs" and "Broker configs").
  */
final case class BatchResult[K, A](values: Map[K, A], skipped: Map[K, SkipReason]) {
  def requested: Set[K]
  def isComplete: Boolean
  def map[B](f: A => B): BatchResult[K, B]
  def get(key: K): Either[SkipReason, A]

  /** Merges two results over disjoint key sets. Used by chunked calls (KAFKA-006); a key
    * present in both is a programming error and is reported by `combineChecked`. */
  def combine(that: BatchResult[K, A]): BatchResult[K, A]
  def combineChecked(that: BatchResult[K, A]): Either[String, BatchResult[K, A]]
}

object BatchResult {
  def empty[K, A]: BatchResult[K, A]
  def complete[K, A](values: Map[K, A]): BatchResult[K, A]
  def allSkipped[K, A](keys: Set[K], reason: SkipReason): BatchResult[K, A]

  /** Ordering is by the key's own `Ordering` wherever one exists, so that a merged result is
    * deterministic and a golden file over one does not flap. */
  given [K: Ordering, A]: ...
}
```

```scala
package kui.kafka

import kui.kernel.KuiError

object KafkaErrorMapper {

  /** Turns anything a Kafka client can throw into a `KuiError`.
    *
    * Total by construction, not by enumeration: after the documented rows, the fallbacks are
    * `RetriableException` to `InfrastructureError.Unreachable` and `ApiException` to
    * `ApplicationError.InvalidState`, and anything that is neither to
    * `InfrastructureError.Upstream("kafka", 502)`. A Kafka release that adds an exception class
    * therefore lands in a sensible bucket instead of an unhandled match, and the property test
    * checks the specific rows rather than having to predict the future.
    *
    * `operation` is the short label from the closed set KAFKA-004 uses, and it appears in the
    * error's message ("describeLogDirs timed out after 30000 ms"), which is what makes a user
    * report actionable.
    */
  def map(operation: String, t: Throwable): KuiError

  /** How this failure should be handled by a caller, independent of how it is rendered. */
  def classify(t: Throwable): FailureClass

  /** `Some(reason)` when a per-key failure should become a `Skipped` entry rather than fail the
    * whole batch: not-found, not-authorized and unsupported. `None` for everything else — a
    * timeout is not suppressible, because a partial result produced by a timeout is
    * indistinguishable from a cluster that genuinely has less data.
    */
  def suppressible(t: Throwable): Option[SkipReason]

  enum FailureClass {
    /** The connection is finished; the pool rebuilds it. Exactly `AdminInvalidation`'s set. */
    case Reconnect
    /** This request failed; the connection is fine. */
    case Request
    /** The broker or the managed service does not offer this call at all. */
    case Unsupported
    /** The principal KUI authenticates as lacks an ACL. */
    case NotAuthorized
    /** The thing asked about does not exist. */
    case NotFound
  }
}
```

### The mapping table

Every row is from `research/kafka/admin-capabilities.md`. `KafkaErrorMapperSuite` asserts it
row by row; this table *is* the specification.

| Kafka exception | `FailureClass` | `KuiError` | `ErrorCode` (status) | Suppressible per key |
| --- | --- | --- | --- | --- |
| `TimeoutException` (org.apache.kafka.common.errors) | `Reconnect` | `InfrastructureError.Timeout(op, afterMs)` | `KUI-TIMEOUT` (408) | no |
| `SaslAuthenticationException` | `Reconnect` | `InfrastructureError.AuthFailed("kafka")` | `KUI-UPSTREAM-AUTH` (502) | no |
| `SslAuthenticationException` | `Reconnect` | `InfrastructureError.AuthFailed("kafka")` | `KUI-UPSTREAM-AUTH` (502) | no |
| `BrokerNotAvailableException` | `Reconnect` | `InfrastructureError.Unreachable("kafka", cause)` | `KUI-UPSTREAM-UNAVAILABLE` (503) | no |
| `ClusterAuthorizationException` | `NotAuthorized` | `ApplicationError.Forbidden` | `KUI-FORBIDDEN` (403) | yes — `NotAuthorized` |
| `TopicAuthorizationException` | `NotAuthorized` | `ApplicationError.Forbidden` | `KUI-FORBIDDEN` (403) | yes — `NotAuthorized` |
| `GroupAuthorizationException` | `NotAuthorized` | `ApplicationError.Forbidden` | `KUI-FORBIDDEN` (403) | yes — `NotAuthorized` |
| `DelegationTokenAuthorizationException` | `NotAuthorized` | `ApplicationError.Forbidden` | `KUI-FORBIDDEN` (403) | yes |
| `TransactionalIdAuthorizationException` | `NotAuthorized` | `ApplicationError.Forbidden` | `KUI-FORBIDDEN` (403) | yes |
| `SecurityDisabledException` | `Unsupported` | `ApplicationError.Unsupported("acls")` | `KUI-UNSUPPORTED` (501) | yes — `Unsupported` |
| `UnsupportedVersionException` | `Unsupported` | `ApplicationError.Unsupported(op)` | `KUI-UNSUPPORTED` (501) | yes — `Unsupported` |
| `InvalidRequestException` | `Unsupported` | `ApplicationError.Unsupported(op)` | `KUI-UNSUPPORTED` (501) | yes — `Unsupported` |
| `UnknownTopicOrPartitionException` | `NotFound` | `ApplicationError.NotFound("topic", "", TopicNotFound)` | `KUI-TOPIC-NOT-FOUND` (404) | yes — `NotFound` |
| `UnknownTopicIdException` | `NotFound` | as above | `KUI-TOPIC-NOT-FOUND` (404) | yes — `NotFound` |
| `LogDirNotFoundException` | `NotFound` | `ApplicationError.NotFound("log directory", "", TopicNotFound)` | `KUI-TOPIC-NOT-FOUND` (404) | yes — `NotFound` |
| `KafkaStorageException` | `Request` | `ApplicationError.InvalidState("log directory is offline")` | `KUI-INVALID-STATE` (409) | yes — `Failed` |
| `InvalidConfigurationException` | `Request` | `ApplicationError.Invalid(msg, fields)` | `KUI-VALIDATION` (400) | no |
| `PolicyViolationException` | `Request` | `ApplicationError.Invalid(msg, Nil)` | `KUI-VALIDATION` (400) | no |
| `InvalidTopicException`, `InvalidPartitionsException`, `InvalidReplicationFactorException`, `InvalidReplicaAssignmentException` | `Request` | `ApplicationError.Invalid` | `KUI-VALIDATION` (400) | no |
| `TopicExistsException` | `Request` | `ApplicationError.Conflict` | `KUI-INVALID-STATE` (409) | no |
| `GroupNotEmptyException`, `GroupSubscribedToTopicException`, `ReassignmentInProgressException`, `NoReassignmentInProgressException`, `ElectionNotNeededException` | `Request` | `ApplicationError.InvalidState` | `KUI-INVALID-STATE` (409) | no |
| `GroupIdNotFoundException`, `TransactionalIdNotFoundException`, `UnknownMemberIdException` | `NotFound` | `ApplicationError.InvalidState` — see "Deviations" | `KUI-INVALID-STATE` (409) | yes — `NotFound` |
| `CoordinatorNotAvailableException`, `NotLeaderOrFollowerException`, `LeaderNotAvailableException`, `NotEnoughReplicasException` | `Request` | `InfrastructureError.Unreachable("kafka", cause)` | `KUI-UPSTREAM-UNAVAILABLE` (503) | no |
| `TopicDeletionDisabledException` | `Unsupported` | `ApplicationError.Unsupported("topic deletion")` | `KUI-UNSUPPORTED` (501) | no |
| `UnknownServerException` | `Request` | `InfrastructureError.Upstream("kafka", 502)` | `KUI-UPSTREAM-UNAVAILABLE` (503) | yes — `Failed` |
| any other `RetriableException` | `Request` | `InfrastructureError.Unreachable("kafka", cause)` | `KUI-UPSTREAM-UNAVAILABLE` (503) | no |
| any other `ApiException` | `Request` | `ApplicationError.InvalidState` | `KUI-INVALID-STATE` (409) | no |
| anything else (including `InterruptedException` and `java.util.concurrent.TimeoutException`) | `Request` | `InfrastructureError.Upstream("kafka", 502)` | `KUI-UPSTREAM-UNAVAILABLE` (503) | no |

Two rules that are not rows:

- **Unwrap first.** `map`, `classify` and `suppressible` all call
  `KafkaFutures.unwrap` (KAFKA-004) before matching. A `CompletionException` never reaches the
  table.
- **`Reconnect` is exactly `AdminInvalidation.reconnectClasses`.** Not "the same list written
  twice": `classify` calls `AdminInvalidation.isReconnectClass`, and a test asserts the derived
  set equals it.

### Message sanitization

`KuiError.message` from this mapper is built from KUI's own vocabulary — the operation name,
the cluster's configured name, a duration — and never from `t.getMessage`. Kafka's message text
for `SaslAuthenticationException` routinely contains the mechanism and the principal, and for
`InvalidConfigurationException` it can contain a configuration value that is a password. The
original throwable goes to the log at DEBUG with its stack trace, where ADR-023's masking and
the operator's own access controls apply; it does not go into an HTTP response body.

## ADRs this task must obey

ADR-034 (the hierarchy, the codes, and the rule that `message` carries no upstream body),
ADR-039 §6 (the `Application` / `Infrastructure` split is what decides whether a failure dims a
capability — a wrong row here dims the sidebar for every user because one of them lacked an
ACL), ADR-006, ADR-030 (an old broker produces `UnsupportedVersionException`, which must
degrade the feature and never fail the page).

## Library coordinates

None new.

## Acceptance criteria

```
$ ./mill libs.kafka.test
$ ./mill libs.kafka.compile        # clean under -Werror
```

```scala
// The split that ADR-039 keys on
assert(KafkaErrorMapper.map("describeConfigs", new TopicAuthorizationException("x"))
         .isInstanceOf[ApplicationError])          // must NOT dim the capability
assert(KafkaErrorMapper.map("describeCluster", new org.apache.kafka.common.errors.TimeoutException())
         .isInstanceOf[InfrastructureError])       // must dim it

// Wrapping does not change the answer
val raw     = new SaslAuthenticationException("PLAIN authentication failed for user admin")
val wrapped = new java.util.concurrent.CompletionException(raw)
assertEquals(KafkaErrorMapper.map("describeCluster", wrapped),
             KafkaErrorMapper.map("describeCluster", raw))

// Nothing from the broker's message reaches the user
assert(!KafkaErrorMapper.map("describeCluster", raw).message.contains("admin"))

// A key never disappears
val r = BatchResult(Map(1 -> "a"), Map(2 -> SkipReason.NotAuthorized("no DESCRIBE_CONFIGS")))
assertEquals(r.requested, Set(1, 2))
```

## Tests required

- `KafkaErrorMapperSuite`:
  - **`theMappingTable`** — one assertion per row of the table above, checking `FailureClass`,
    the `KuiError` case, the `ErrorCode.wire` string and `suppressible`. Written as a list of
    tuples so a reviewer reads the table and the code side by side.
  - **`isTotalOverEveryDocumentedException`** (ScalaCheck over a generator that picks uniformly
    from a hard-coded list of every exception class the research names, instantiated
    reflectively): `map` returns a value for each and never throws.
  - **`isTotalOverArbitraryThrowables`** (ScalaCheck): including `null`-messaged exceptions,
    deeply nested `CompletionException` chains and a custom `ApiException` subclass Kafka does
    not ship.
  - `reconnectClassMatchesTheInvalidationPredicate` — the derived `Reconnect` set equals
    `AdminInvalidation.reconnectClasses`, in both directions.
  - `noMessageContainsTheOriginalThrowablesText` (property): generate exceptions whose message
    is a distinctive token and assert it never appears in the mapped `message`.
  - `unwrapIsAppliedBeforeClassification` (property over arbitrary wrapping depth).
  - `everyMappedCodeExistsInTheErrorCodeEnum` — trivially true by construction, but it is the
    test that fails if a code is later renamed.
- `BatchResultSuite`:
  - `requestedIsTheUnionOfValuesAndSkipped` (property).
  - `valuesAndSkippedAreDisjoint` (property over merges).
  - `combineIsAssociativeAndDeterministic` (property; the deterministic ordering is what makes a
    chunked result reproducible).
  - `combineCheckedReportsAnOverlap`.
  - `mapPreservesSkipped`.
  - `allSkippedIsTheEmptyClusterCase` — a batch where every key failed is a valid result, not an
    error; this is the shape "authenticates but authorizes nothing" produces.

## Observability

No metric or span of its own — the call was already measured in KAFKA-004. Two logging rules
this task owns, both under `kui.kafka`:

- Every mapped error is logged **once**, at the level its class implies: WARN for
  `Reconnect` and `Request`, DEBUG for `NotAuthorized`, `NotFound` and `Unsupported` (those are
  normal on a partly-authorized or managed cluster and would otherwise fill an operator's log
  with noise that means nothing is wrong). The log line carries `cluster`, `operation`,
  `error.code` (the `wire` string, never a class name — the vocabulary KERN-002 fixed) and the
  original exception with its stack trace.
- A `Skipped` key is logged at DEBUG with its reason, never dropped silently and never at WARN:
  on a cluster where KUI can see 800 of 1000 topics, 200 WARN lines every 30 seconds is a log
  nobody reads.

## Degraded behavior

This task *is* the degraded-behaviour vocabulary for everything Kafka-shaped. The contracts it
fixes:

- A per-key failure that is `suppressible` never fails the whole call. A broker list where two
  brokers refuse `describeConfigs` renders eight brokers and two lock icons.
- A per-key failure that is **not** suppressible (a timeout) fails the call, because a partial
  result caused by slowness looks exactly like a small cluster and would be shown as fact.
- `Unsupported` is the managed-service path: MSK Serverless answering `InvalidRequestException`
  and Event Hubs answering `UnknownTopicOrPartitionException` to a broker `describeConfigs` both
  end as "this cluster does not offer that", which ADR-039 §6 renders as `NotConfigured` and
  never as an outage.
- Nothing in this file throws, ever. It is the last line of defence between a Java SDK and an
  HTTP response, and a mapper that can itself fail turns a handled error into a 500.

## Docs to update

None here. `docs/api/error-codes.md` is regenerated from `ErrorCode` by CLAPI-010, and this task
adds no code. The table above is the evidence CFGOP-008 uses for the operator-facing "what a
Kafka failure looks like" section.

## Deviations

- **`GroupIdNotFoundException` maps to `ApplicationError.InvalidState` (409), not to a
  not-found (404).** `ErrorCode` has `KUI-TOPIC-NOT-FOUND`, `KUI-CLUSTER-NOT-FOUND` and
  `KUI-SCHEMA-NOT-FOUND` but no group code, and `libs/kernel/src/kui/kernel/error/ErrorCode.scala`
  is outside this lane's file boundary (DEVPLAN §6.5) — adding a wire code is a contract change
  made in passing, which is precisely what KERN-002's own deviation note refused to do for
  conflicts. Nothing in M1 calls a group API, so the row costs nothing today. Its
  `FailureClass` is nevertheless `NotFound` and its `SkipReason` is `NotFound`, so the two
  behaviours that matter — suppressibility and capability neutrality — are already right, and
  M2's consumer lane adds `KUI-GROUP-NOT-FOUND` and changes one row.

### Further deviations, recorded by the implementer

2. **`map` takes a third parameter, `apiTimeoutMs: Long = 0L`.** The table's timeout row renders
   `InfrastructureError.Timeout(operation, afterMs)`, and the mapper has no way to know `afterMs`
   from the exception — Kafka's `TimeoutException` does not carry the bound it exceeded. Passing it
   in means the message names a number an operator can go and change (`AdminTuning.apiTimeout`)
   rather than a number this file invented. It defaults, so a caller that does not know still gets a
   correct classification and a slightly less useful message.

3. **`InvalidTopicException` and `InvalidReplicationFactorException` are matched before
   `InvalidConfigurationException`.** Both extend it in kafka-clients 4.3.1, so the table's row
   order would have made two rows unreachable — which `-Werror` caught as a compile error. The
   behaviour the table specifies is unchanged (all three are `KUI-VALIDATION`); the ordering is what
   lets the message say *which* value was rejected.

4. **`SkipReason.Failed` is produced for two request-level conditions the table marks
   non-suppressible in the strict reading**: `KafkaStorageException` and `UnknownServerException`.
   Both are per-key facts rather than call failures — one log directory being offline must not blank
   the other eleven, and a broker that answers `UnknownServerException` about itself must not fail
   the broker list it is one row of. The table's own "Suppressible per key" column already says
   `yes — Failed` for both; this note records that the mapper implements it through
   `FailureClass.Request` plus an explicit two-case match, not through the failure class alone.

5. **`describe` never reads `t.getMessage`.** The sanitization rule says a mapped message is built
   from KUI's own vocabulary; the implementation goes further and derives the detail text from the
   exception's *class name* with the `Exception` suffix trimmed, so there is no code path through
   which a broker's text can reach a response body even if a future row forgets. A property test
   generates exceptions whose message is a distinctive token and asserts it never appears in the
   mapped message or in the skip reason.

6. **`BatchResult.combine` drops a skip entry for a key that succeeded on the other side.** The
   spec describes `combine` over disjoint key sets. In practice a chunk that failed and a retry that
   worked would otherwise leave the key in both halves and break the invariant the type exists for,
   so a key present in `values` is removed from `skipped`. `combineChecked` still reports the
   overlap for the caller that wants to know.

7. **The deterministic ordering is `orderedValues` / `orderedSkipped` extension methods** rather
   than the `given [K: Ordering, A]: ...` the spec sketches, which does not name a type class. A
   `BatchResult` is a `Map` pair and has no canonical order of its own; the extensions give a caller
   that needs a reproducible rendering — a golden file, a chunked merge — one, without imposing an
   `Ordering` on every key type.

8. **The logging rules of the Observability section are not implemented in this file.** They belong
   at the call site that has both a logger and the cluster id, which is `ClusterAdmin`
   (KAFKA-007…009) — this object is a pure function and taking a `Logger[F]` would make it an
   effect. The vocabulary the rules need (`FailureClass`, `ErrorCode.wire`) is what this task
   provides; KAFKA-007 applies the WARN/DEBUG split.
