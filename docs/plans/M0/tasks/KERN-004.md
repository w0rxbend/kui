# KERN-004 — `libs/contracts-core`: error envelope and kernel codecs

- **ID:** KERN-004
- **Title:** `libs/contracts-core`: error envelope and kernel codecs
- **Milestone / Feature:** M0 / OT-005
- **Owner role:** Principal Scala Engineer, reviewed by the Chief Architect
- **Context / service:** `libs/contracts-core`
- **Size:** M
- **Dependencies / blocked by:** KERN-002, KERN-003

## Goal (user value)

Every KUI error looks identical on the wire, in every service, in every stream, and the
browser can switch on `code` instead of parsing prose. A client written against one endpoint
handles the errors of all of them.

## Scope

1. The module itself: cross-compiled (JVM + JS), depending on `libs.kernel` plus Tapir and
   Circe. **This is the only place kernel types acquire wire forms** (ADR-007).
2. `ErrorEnvelope` DTO exactly as ADR-034 specifies, with an explicit Circe codec and an
   explicit Tapir `Schema`.
3. `Codec`/`Schema` instances for the kernel identifiers and value objects from KERN-001 and
   KERN-003, including Tapir path- and query-parameter codecs (so `ClusterId` can be a path
   segment and a decode failure becomes `KUI-VALIDATION`, not a 500).
4. The golden-file test harness: every DTO in this module has a committed JSON sample; the
   same samples are decoded on both platforms.

## Non-goals

No capability or `Section` DTOs (KERN-005). No `KuiError → ErrorEnvelope` mapping function that
needs a correlation id and a clock — that lives in `libs/http`'s interceptor (HTTP-001);
this task provides only the pure `ErrorEnvelope.of(error, correlationId, timestamp)`.
No automatic derivation, ever.

## Design references

ADR-034 (envelope shape and codes), ADR-007 (explicit codecs, no `auto`), ADR-003 (Tapir
version and cross-compilation), `ARCHITECTURE.md` §4 table row for `libs/contracts-core`,
§15 errors.

## Files to create

```
libs/contracts-core/src/kui/contracts/ErrorEnvelope.scala
libs/contracts-core/src/kui/contracts/KernelCodecs.scala
libs/contracts-core/src/kui/contracts/KernelSchemas.scala
libs/contracts-core/test/src/kui/contracts/ErrorEnvelopeSuite.scala
libs/contracts-core/test/src/kui/contracts/KernelCodecsSuite.scala
libs/contracts-core/test/resources/golden/error-envelope-validation.json
libs/contracts-core/test/resources/golden/error-envelope-upstream.json
build.mill                                   (declare the cross module)
```

## Public Scala signatures to implement

```scala
package kui.contracts

import io.circe.{Codec => CirceCodec}
import sttp.tapir.Schema

final case class ErrorDetail(field: Option[String], restrictions: List[String])

final case class ErrorEnvelope(
    code: String,             // ErrorCode.wire — a String on the wire so unknown codes decode
    message: String,
    details: List[ErrorDetail],
    correlationId: String,
    timestamp: Instant,
    retryable: Boolean
)

object ErrorEnvelope:
  given CirceCodec[ErrorEnvelope]
  given Schema[ErrorEnvelope]
  def of(error: KuiError, correlationId: CorrelationId, at: Instant): ErrorEnvelope
  /** The HTTP status the envelope must be served with. Single source of truth for every
    * `api` module and for the gateway. */
  def statusOf(error: KuiError): Int

object KernelCodecs:
  given CirceCodec[ClusterId]
  given CirceCodec[TopicName]
  // ... one per identifier from KERN-001, each encoding as its underlying primitive
  given [A: CirceCodec]: CirceCodec[Page[A]]
  given CirceCodec[PageToken]
  given CirceCodec[SortOrder]                 // "asc" | "desc", lowercase on the wire

object KernelSchemas:
  given Schema[ClusterId]                     // Schema.string with the documented pattern
  given sttp.tapir.Codec[String, ClusterId, TextPlain]     // path/query codec
  // ... one per identifier; decode failure -> Codec.Result.Error carrying ValidationError
```

**Wire-form rules** (binding on every later contract module):

- Identifiers encode as their underlying primitive, never as an object.
- `SortOrder` and every enum encode as a lowercase or Kafka-native string, never as an ordinal.
- `Instant` encodes as RFC 3339 UTC with milliseconds (`2026-09-03T10:11:12.000Z`).
- Unknown fields are ignored on decode (additive contract evolution, `ARCHITECTURE.md` §5).
- `details` is always present, `[]` when empty — never `null`, never absent.

## Library coordinates

```
com.softwaremill.sttp.tapir::tapir-core::1.13.31          (shared)
com.softwaremill.sttp.tapir::tapir-json-circe::1.13.31    (shared)
com.softwaremill.sttp.tapir::tapir-iron::1.13.31          (shared)
io.circe::circe-core::0.14.16                             (shared)
io.circe::circe-parser::0.14.16                           (shared)
io.github.iltotore::iron-circe::3.3.2                     (shared)
io.github.cquiroz::scala-java-time::2.7.0                 (JS side only, for Instant)
```

## Acceptance criteria

```
$ ./mill libs.contractsCore.jvm.test
$ ./mill libs.contractsCore.js.test
$ ./mill checkArchitecture
```

```json
// golden/error-envelope-validation.json — byte-for-byte what the encoder must produce
{
  "code": "KUI-VALIDATION",
  "message": "Request is not valid",
  "details": [ { "field": "partitions", "restrictions": ["must be > 0"] } ],
  "correlationId": "3b1fa9c2e4d54f0b",
  "timestamp": "2026-09-03T10:11:12.000Z",
  "retryable": false
}
```

## Tests required

- `ErrorEnvelopeSuite` (unit + golden, cross-compiled):
  - `encodesToTheGoldenDocument` — for both golden files.
  - `decodesTheGoldenDocument` and `roundTrips`.
  - `ignoresUnknownFields` — a golden file with an extra `"hint"` field decodes.
  - `statusOfCoversEveryErrorCode` — exhaustive over `ErrorCode.values`, asserting the status
    matches ADR-034's table.
  - `messageNeverContainsASecret` — construct an `InfrastructureError.Upstream` from a fake
    upstream whose body holds `hunter2`; assert the envelope's `message` does not contain it.
- `KernelCodecsSuite` (property, cross-compiled):
  - `identifierCodecsRoundTrip` — one property per identifier, over generated valid values.
  - `pathCodecRejectsInvalidClusterId` — asserts the Tapir codec returns a decode error, and
    that the error carries the `ValidationError` so HTTP-001 can render `KUI-VALIDATION`.
  - `instantFormatIsRfc3339Utc`.

## Observability

None (no effects), but note for reviewers: `correlationId` in the envelope is the same value
`libs/observability` puts in the log MDC and the span attribute `correlation.id`.

## Degraded behavior

An unknown `code` string decodes successfully into `ErrorEnvelope` (forward compatibility);
the frontend falls back to rendering `message` when it does not recognize the code.

## Docs to update

`ARCHITECTURE.md` §15: replace the hand-written JSON sample with a pointer to the golden file.

## Deviations

- **Golden files are asserted through string constants, and the file check is JVM-only.** A
  Scala.js suite cannot read a file: a browser has no filesystem. The committed documents are
  therefore duplicated as constants in `GoldenDocuments`, which both platforms assert against,
  and a JVM-only `GoldenFilesSuite` asserts that each constant is exactly the committed file.
  Neither copy can drift without a test failing. `libs/testkit`'s `Golden.assertJson`
  (KERN-007) is JVM-only for the same reason.
- **No Iron, and therefore no `tapir-iron` or `iron-circe`.** KERN-001 records the reasoning:
  the kernel's smart constructors already return `Either[ValidationError, _]`, and a second
  refinement vocabulary beside them would have to be translated at every boundary.
- **A path or query decode failure is `DecodeResult.Error(raw, KernelDecodeFailure(error))`,
  not `DecodeResult.InvalidValue`.** Tapir's `ValidationError` is built around its own
  `Validator`, and forcing a kernel `ValidationError` through one loses the field name.
  `KernelDecodeFailure` is a small exception type that carries the kernel error unchanged, so
  HTTP-001's interceptor can render `KUI-VALIDATION` with the offending field named.

## Findings for later lanes

- **64-bit numbers lose precision in the browser above 2^53 - 1.** Discovered by running the
  cross-compiled codec property under Node: an `Offset` of 1957712847277465469 round-trips on
  the JVM and comes back altered in JavaScript, because `JSON.parse` produces a double. This is
  a platform fact, not a circe one, and it contradicts the optimistic reading of ADR-007's note
  that "numbers keep 64-bit precision on Scala.js". KUI keeps offsets and byte sizes as JSON
  numbers, because no Kafka partition holds 2^53 records and stringifying every offset would
  cost every reader of the API something real; the boundary is now asserted by a test on both
  platforms. **M3 (message browsing) should not invent any 64-bit identifier that can exceed
  2^53** — a hash, a fingerprint, a synthetic id — without encoding it as a string.
