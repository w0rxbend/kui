# CLAPI-003 — Internal profile contract: `{id}/profile` with ETag and `clusters/stream`

- **ID:** CLAPI-003
- **Title:** Internal profile contract: `{id}/profile` with ETag and `clusters/stream`
- **Milestone / Feature:** M1 / CL-007, OT-003
- **Owner role:** Chief Architect
- **Size:** M
- **Dependencies / blocked by:** CLAPI-001

## Goal (user value)

The mechanism every Kafka-facing service of M2–M8 will use to learn about a cluster and to be
told when it changes, built once, here, while there is exactly one producer and no consumer to
break. An operator edits a cluster's connection and every service picks it up within one poll
interval, without a restart.

## Scope

1. `GET /internal/v1/clusters/{clusterId}/profile` — the resolved connection profile, with an
   `ETag` set to the profile's `version` and `If-None-Match` support answering **304**.
2. `GET /internal/v1/clusters/stream` — a Server-Sent Events stream of change notifications
   (ADR-035, `libs/http`'s `Sse.body`), declared in the `api` module rather than the
   cross-compiled contract because describing it needs `fs2` (the precedent is
   `CapabilityRoutes.streamEndpoint`).
3. The event vocabulary: one named event, `clusters`, carrying a `ClusterChangeDto`; plus the
   shared `heartbeat` and `error` events every KUI stream has.
4. The redacted profile DTO, and the decision below about why it is redacted in M1.
5. The R-12 leak assertion for this path: a profile whose every secret field is the token
   `kui-secret-canary` produces a response body and a stream frame containing no occurrence of
   it.

## Non-goals

No profile *client* — nothing consumes this in M1, because `services/topic` does not exist
(DEVPLAN §3). No subscription bookkeeping beyond what `Sse` already gives. No write endpoint
(CLAPI-009). No secret distribution (see decision 1). No `Last-Event-ID` resume: this stream is
a notification, not a log.

## Design references

- ADR-036 §"Distribution": "resolved profiles at `GET /internal/v1/clusters/{id}/profile`
  (ETag) and change notifications at `GET /internal/v1/clusters/stream` (SSE, ADR-035).
  Kafka-facing services subscribe, keep the last known profile, poll as a fallback (60 s), and
  rebuild clients … when the version changes." This task builds the server half of exactly that
  sentence.
- ADR-043 (a direct service→service call is permitted on the callee's published contract, one
  hop, with a cached last-known fallback and a report to the capability registry) — this is the
  first such edge in KUI and `ARCHITECTURE.md` §5 already lists it.
- ADR-035 (streaming envelope, named events), ADR-022 (the typed security ADT), ADR-042
  (`version` is the store record's optimistic version — the same number the ETag carries).
- DEVPLAN R-12 (the four secret-leak paths), §10 D6 (the write endpoint that bumps the version).

## Files to create

```
services/cluster/contract/src/kui/cluster/contract/dto/ClusterProfileDtos.scala
services/cluster/contract/src/kui/cluster/contract/ProfileEndpoints.scala
services/cluster/api/src/kui/cluster/api/ClusterStreamEndpoint.scala
services/cluster/contract/test/src/kui/cluster/contract/ProfileEndpointsSuite.scala
services/cluster/contract/test/resources/golden/cluster-profile.json
services/cluster/contract/test/resources/golden/cluster-change.json
```

## Files to change

```
services/cluster/contract/src/kui/cluster/contract/ClusterEndpoints.scala   (add profile to `all`)
services/cluster/contract/test/src/kui/cluster/contract/GoldenDocuments.scala
```

## Public Scala signatures to implement

```scala
package kui.cluster.contract.dto

import java.time.Instant
import kui.contracts.cluster.ClusterSecurityDto
import kui.kernel.ClusterId

/** A cluster's resolved connection profile, as a consumer needs to see it.
  *
  * `version` is the store record's optimistic version (ADR-042) and is also the ETag. A
  * consumer compares versions, not payloads: a rebuild of every Kafka client is expensive and
  * must happen when the profile actually changed, not when a scrape re-serialised it.
  */
final case class ClusterProfileDto(
    id: ClusterId,
    name: String,
    version: Long,
    readOnly: Boolean,
    bootstrapServers: String,
    security: ClusterSecurityDto,
    adminTimeoutMs: Long,
    adminBatchSize: Int,
    adminParallelism: Int,
    propertyKeys: List[String],   // the *keys* of the ADR-022 override map, sorted; never values
    updatedAt: Instant
)

/** One change notification. It carries no profile: a consumer that sees a version it does not
  * hold fetches the profile, which keeps the stream small and means a dropped frame costs a
  * fetch rather than a stale client.
  */
final case class ClusterChangeDto(
    id: ClusterId,
    version: Long,
    change: String,               // "updated" | "removed"
    at: Instant
)
```

```scala
package kui.cluster.contract

object ProfileEndpoints {

  val ProfileSegment: String = "profile"
  val StreamSegment: String = "stream"

  /** `GET /internal/v1/clusters/{clusterId}/profile`.
    *
    * The input carries `If-None-Match`; the output is either 200 with the profile and an
    * `ETag`, or 304 with an `ETag` and no body. Tapir models that as a `oneOf` over two
    * variants rather than an `Option` body, so the generated document and the generated client
    * both know 304 is a normal outcome and not an error.
    */
  val profile: Endpoint[SignedPrincipal, (ClusterId, Option[String]), ErrorEnvelope, ProfileResult, Any]

  enum ProfileResult:
    case Current(etag: String, profile: ClusterProfileDto)   // 200
    case NotModified(etag: String)                            // 304

  val all: List[AnyEndpoint] = List(profile)
}
```

```scala
package kui.cluster.api

object ClusterStreamEndpoint {

  /** The event name a consumer registers a listener for. */
  val EventName: String = "clusters"

  val StreamPath: String = "/internal/v1/clusters/stream"

  def endpoint[F[_]]: Endpoint[SignedPrincipal, Unit, ErrorEnvelope, Stream[F, Byte], Fs2Streams[F]]

  /** Every endpoint of this file, for CLAPI-010's OpenAPI merge, which must document the stream
    * even though the cross-compiled contract cannot describe it.
    */
  def endpoints[F[_]]: List[AnyEndpoint] = List(endpoint[F])
}
```

The ETag is the profile version rendered as a **weak-free strong tag**: `"7"` — quoted, no `W/`
prefix. The comparison is byte equality after trimming the quotes; a client sending `*` gets the
profile.

## Decisions this task takes (no ADR covers them)

1. **The profile is redacted in M1: no password, no keystore bytes, no JAAS string leaves this
   endpoint.** ADR-036 says services fetch "resolved profiles", and a service that has to *build
   a Kafka client* eventually needs the credentials. It does not need them in M1: no consumer
   exists — `services/topic` and the rest arrive in M2–M8 — and shipping a
   credential-distributing endpoint a milestone before its first caller means shipping an
   untested secret path and asserting nothing about it. R-12 names this exact endpoint as a leak
   path to be tested, which only makes sense if the M1 answer is redaction. `propertyKeys`
   follows the same rule: the ADR-022 override map may contain `sasl.jaas.config`, so its keys
   are published and its values are not.
   **The reconciliation path is cheap and must be recorded**: M2's first consumer either (a)
   gains a second, credential-bearing variant of this endpoint gated on the signed principal's
   `aud`, or (b) receives credentials by reading `__kui_config` itself with the store key it
   already holds. The Implementation Report states this so CFGOP-008 can record it in
   `TECH_DEBT.md` as a milestone-scoped debt with a named owner.
2. **The stream carries notifications, not profiles.** A change frame is four fields. Pushing
   whole profiles would make every subscriber's socket carry credentials (see decision 1),
   would re-send a 4 KB document on every unrelated field change, and would give a consumer two
   sources of truth for the same value.
3. **A `removed` change is a first-class event, not a missing update.** A cluster deleted from
   the store must make every consumer drop its clients; inferring deletion from silence is how a
   service keeps talking to a cluster an operator revoked.
4. **No `Last-Event-ID` resume.** A reconnecting consumer re-fetches the profiles it holds and
   compares versions — the same code path it runs at startup. A per-subscriber backlog would
   exist solely to avoid a fetch that is already implemented.

## Library coordinates

None new. `services.cluster.api` already has `tapir-server`, `tapir-cats-effect` and `fs2-core`;
`Sse.body` and `SseConfig` come from `libs/http`.

## Acceptance criteria

```
$ ./mill services.cluster.contract.jvm.test.testOnly 'kui.cluster.contract.ProfileEndpointsSuite'
$ ./mill services.cluster.contract.js.test.testOnly  'kui.cluster.contract.ProfileEndpointsSuite'
$ ./mill services.cluster.api.compile
$ ./mill checkArchitecture
```

With the service running (after CLAPI-004 and CLAPI-005 land, and using the all-in-one so a
signed principal is not needed):

```
$ curl -si localhost:8081/internal/v1/clusters/local/profile | head -3
HTTP/1.1 200 OK
ETag: "3"
$ curl -si -H 'If-None-Match: "3"' localhost:8081/internal/v1/clusters/local/profile | head -2
HTTP/1.1 304 Not Modified
$ curl -sN localhost:8081/internal/v1/clusters/stream
event: heartbeat
data: {}

event: clusters
data: {"id":"local","version":4,"change":"updated","at":"2026-09-03T10:11:12.000Z"}
```

## Tests required

`ProfileEndpointsSuite` (cross-compiled, MUnit):

- `theProfileGoldenDocumentDecodes`, `theChangeGoldenDocumentDecodes`, both round-trip.
- `noSecretFieldExistsOnTheProfileDto` — R-12's second assertion: build the DTO from a profile
  whose username, password, keystore bytes, truststore password and every `properties` value is
  `kui-secret-canary`, encode it, and assert the token is absent. Assert `propertyKeys` contains
  the keys, so the test proves the keys survive and the values do not.
- `theProfilePathIsUnderInternalV1AndCarriesTheSignedPrincipal`.
- `anUnknownChangeKindDecodes` — a future `"renamed"` decodes as itself rather than failing.
- `theEtagIsTheVersion` — `ProfileResult.Current(etag, p)` requires `etag == s""""${p.version}""""`;
  assert it with a property over arbitrary versions.

`ClusterStreamEndpointSuite` (in `services.cluster.api.test`, added by CLAPI-004's suite file or
created here): the endpoint's path is exactly `StreamPath` and its output body is an SSE body.

## Observability

The stream route (implemented in CLAPI-004) emits `kui.sse.subscribers{stream="clusters"}` as an
up/down counter and one INFO line per subscriber connect and disconnect with the correlation id.
The profile route emits nothing of its own: it is a request/response endpoint and the shared
interceptors already time it. A 304 must be recorded with the same metric and the same span as a
200 — a 304-only client that has silently stopped receiving updates should still be visible as
traffic.

## Degraded behaviour

- **Store unreachable**: the profile served is the last replayed one, the response carries its
  version unchanged, and the `cluster` capability reports `Degraded` (STORE-008, CLDOM-007). The
  endpoint never fails because the store is down — a consumer that cannot fetch a profile
  cannot build a client, which would turn a store outage into a total outage.
- **Unknown cluster id**: `KUI-CLUSTER-NOT-FOUND`, 404. The syntactically-invalid case is a 400
  from the path codec (CLAPI-002).
- **Stream backpressure**: a subscriber that stops reading is dropped after `SseConfig`'s queue
  bound rather than allowed to stall the publisher — the rule `CapabilityRegistry` already
  applies to its own subscribers.

## Docs to update

`ARCHITECTURE.md` §5's internal-contract table: mark the two profile edges as implemented and
name the files. (This is the one `ARCHITECTURE.md` edit in the CLAPI area; the rest of §4.2's
sketch replacement is CFGOP-008's.)

## Deviations

1. **The profile endpoint is *not* added to `ClusterEndpoints.all`.** The spec's "files to change"
   said to add it; `all` is what the gateway turns into public `/api/v1` routes, and ADR-043 makes
   this a service-to-service call, one hop, not a browser-reachable path. It lives in
   `ProfileEndpoints.all`, which `ClusterApi.documented` concatenates, so it is in the service's own
   OpenAPI document and absent from the merged public one. A test asserts both halves.
2. **`ProfileResult` lives in `kui.cluster.contract.dto`**, not inside `ProfileEndpoints`: Tapir's
   `mapTo` needs the case classes, and the DTO package is where every other wire type of this
   contract lives.
3. **`ProfileResult.entityTag`, not `etag`.** Both cases already have an `etag` *field*, and a
   member of that name on the enum itself is an override of them.
4. **`ProfileResult.isCurrent` tolerates a `W/` prefix and unquoted values.** A proxy between the
   caller and KUI may add or remove either. `*` is treated as unconditional, which is the only
   useful reading of the wildcard on a read.
5. The stream route's suite is `ClusterStreamEndpointSuite` in `services.cluster.api.test`, as the
   spec's second option allowed.
