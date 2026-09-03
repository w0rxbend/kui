# KERN-005 — `libs/contracts-core`: capability, `Section` and SSE DTOs

- **ID:** KERN-005
- **Title:** `libs/contracts-core`: capability, `Section` and SSE DTOs
- **Milestone / Feature:** M0 / KU-001, KU-003, KU-006, KU-014 (kernel half)
- **Owner role:** Chief Architect
- **Context / service:** `libs/contracts-core`
- **Size:** M
- **Dependencies / blocked by:** KERN-004

## Goal (user value)

The contract that makes fault isolation visible: the browser can be told *which* feature is
down, *why*, *since when*, and how often to retry — in a shape that is fixed before the first
real service exists, so no feature has to be retrofitted.

## Scope

1. `CapabilityState`, `ReasonCode`, `DegradedReason`, `CapabilityKey`, `CapabilitySnapshot`,
   `CapabilityChange` DTOs (ADR-032 and `ARCHITECTURE.md` §4.5, §6).
2. `Section[A]` envelope for partial aggregations (`ARCHITECTURE.md` §6).
3. The per-service `/capabilities` response DTO of `ARCHITECTURE.md` §6.
4. SSE event DTOs shared by every stream (ADR-035): `SseEvent` names as constants,
   `DoneEvent`, `ErrorEvent`, `HeartbeatEvent`, and the `CapabilitiesEvent` payload.
5. Golden files for all of them.

## Non-goals

No registry logic (GW-003). No SSE transport (HTTP-004). No message/phase/consumed events —
those are M3 and belong to `services/message/contract`; M0 defines only the events every
stream shares (`done`, `error`, `heartbeat`) plus the capability stream's own data event.

## Design references

ADR-032 (five navigation states, structured degraded reason, snapshot-then-deltas),
ADR-035 (named events, one terminal event, heartbeat cadence), ADR-034 (the `error` event
reuses `ErrorEnvelope`), `ARCHITECTURE.md` §4.5, §6, §7, feature matrix rows KU-001 and KU-003.

## Files to create

```
libs/contracts-core/src/kui/contracts/capability/CapabilityDtos.scala
libs/contracts-core/src/kui/contracts/Section.scala
libs/contracts-core/src/kui/contracts/sse/SseEvents.scala
libs/contracts-core/test/src/kui/contracts/capability/CapabilityDtosSuite.scala
libs/contracts-core/test/src/kui/contracts/SectionSuite.scala
libs/contracts-core/test/resources/golden/capabilities-snapshot.json
libs/contracts-core/test/resources/golden/capability-change-unavailable.json
libs/contracts-core/test/resources/golden/service-capabilities.json
libs/contracts-core/test/resources/golden/sse-done.json
libs/contracts-core/test/resources/golden/sse-error.json
```

## Public Scala signatures to implement

```scala
package kui.contracts.capability

/** Why a capability is not fully available. Shared by CapabilityState, Section and the SSE
  * error event so the three can never disagree. */
enum ReasonCode:
  case UpstreamUnavailable, UpstreamTimeout, CircuitOpen, UpstreamAuth, NotConfigured,
       Forbidden, Starting, Unknown

final case class DegradedReason(
    code: ReasonCode,
    message: String,
    suggestedPollIntervalMs: Option[Long],
    p95Ms: Option[Long]
)

final case class CapabilityKey(service: ServiceId, cluster: Option[ClusterId])

enum CapabilityState:
  case Available
  case Degraded(reason: DegradedReason)
  case Unavailable(reason: ReasonCode, message: String, since: Instant)
  case NotConfigured

final case class CapabilityEntry(key: CapabilityKey, state: CapabilityState, updatedAt: Instant)
final case class CapabilitySnapshot(entries: List[CapabilityEntry], generatedAt: Instant)
final case class CapabilityChange(entry: CapabilityEntry, previous: Option[CapabilityState])

/** The per-service GET /capabilities response of ARCHITECTURE.md §6. */
final case class ServiceCapabilities(
    service: ServiceId,
    clusters: Map[ClusterId, ClusterCapability]
)
final case class ClusterCapability(configured: Boolean, features: List[String], status: String)
```

```scala
package kui.contracts

enum Section[+A]:
  case Ok(data: A, fetchedAt: Instant)
  case Stale(data: A, fetchedAt: Instant, reason: ReasonCode)
  case Unavailable(reason: ReasonCode, message: String, since: Option[Instant])
  case Forbidden
  case NotConfigured

object Section:
  given [A: CirceCodec]: CirceCodec[Section[A]]     // tagged by a "status" discriminator
  given [A: Schema]: Schema[Section[A]]
  def fromEither[A](e: Either[KuiError, A], at: Instant): Section[A]
```

```scala
package kui.contracts.sse

object SseEventName:
  val Phase = "phase"; val Done = "done"; val Error = "error"; val Heartbeat = "heartbeat"
  val Capabilities = "capabilities"

enum DoneReason { case Limit, Exhausted, Budget, Cancelled }
final case class DoneEvent(reason: DoneReason, cursor: Option[String])
final case class HeartbeatEvent()                    // encodes as {}
type ErrorEvent = ErrorEnvelope                      // ADR-034 reuse, not a new shape
```

**Wire forms.** `CapabilityState` is a tagged union with a `"status"` discriminator whose
values are `available | degraded | unavailable | not_configured`. `Section` uses `"status"`
with `ok | stale | unavailable | forbidden | not_configured`. `ReasonCode` encodes in
`SCREAMING_SNAKE_CASE`. `DoneReason` encodes lowercase. These strings are contract; a rename
is a breaking change.

## Library coordinates

None beyond KERN-004's set.

## Acceptance criteria

```
$ ./mill libs.contractsCore.jvm.test
$ ./mill libs.contractsCore.js.test
```

```json
// golden/capability-change-unavailable.json
{ "entry": { "key": { "service": "cluster", "cluster": null },
             "state": { "status": "unavailable", "reason": "UPSTREAM_UNAVAILABLE",
                        "message": "readiness probe failed", "since": "2026-09-03T10:11:12.000Z" },
             "updatedAt": "2026-09-03T10:11:13.000Z" },
  "previous": { "status": "available" } }
```

## Tests required

- `CapabilityDtosSuite` (unit + golden, cross-compiled):
  - `encodesEveryCapabilityStateToItsGoldenForm` — one assertion per state.
  - `decodesAnUnknownReasonCodeAsUnknown` — forward compatibility.
  - `stateDiscriminatorStringsAreStable` — a literal table; this test exists to fail loudly
    when someone renames a wire string.
  - `serviceCapabilitiesMatchesTheArchitectureExample` — decodes the exact JSON printed in
    `ARCHITECTURE.md` §6.
- `SectionSuite` (unit + property):
  - `fromEitherMapsErrorsToUnavailableWithTheRightReason` — table over `KuiError` cases.
  - `sectionRoundTripsForEveryCase`.
  - `staleCarriesBothDataAndReason`.

## Observability

None (DTOs). Note for GW-003: `CapabilityState` changes drive the `kui.capability.state`
metric (PLAN §30), whose label values are exactly the discriminator strings defined here.

## Degraded behavior

Unknown `status` or `reason` strings decode to `Unknown`/a passthrough rather than failing —
an older browser must not break against a newer gateway.

## Docs to update

`ARCHITECTURE.md` §6: point the `/capabilities` example at `golden/service-capabilities.json`.

## Deviations

- **`Section`'s Tapir schema is `Schema.any` with the discriminator described in prose.** A
  five-shape union whose data type is a parameter cannot be derived into an honest OpenAPI
  schema, and a schema that claims more than it delivers is worse for a client than one that
  says "this is an object; the `status` field tells you which". The Circe codec is exact and
  tested case by case; the schema is documentation.
- **`ServiceCapabilities` needs a `KeyEncoder`/`KeyDecoder` for `ClusterId`,** because JSON
  object keys are always strings. Decoding re-runs the slug rule, so a malformed cluster id in
  a `clusters` map is refused rather than becoming a key nothing matches.
- **`Section.fromEither` maps by failure case, not by error code.** `InfrastructureError.
  CircuitOpen` and `InfrastructureError.Unreachable` share `KUI-UPSTREAM-UNAVAILABLE`, and a
  user interface says different things about "we cannot reach it" and "we have stopped calling
  it while it recovers"; mapping by code would have collapsed the two. `CircuitOpen` also keeps
  the instant the circuit opened as the section's `since`, rather than the moment of the call.
- **The golden documents follow the `GoldenDocuments` pattern established in KERN-004:** a
  string constant both platforms assert against, plus a JVM-only suite proving the constant is
  the committed file, because a browser cannot read one.
