# CLAPI-009 — The one store-backed write endpoint, and `NotConfigured` without a store

- **ID:** CLAPI-009
- **Title:** The one store-backed write endpoint, and `NotConfigured` without a store
- **Milestone / Feature:** M1 / CL-007, PA-003, KU-012
- **Owner role:** Chief Architect
- **Size:** M
- **Dependencies / blocked by:** CLAPI-004, CLADP-003

## Goal (user value)

A cluster can be registered or changed at runtime, and the guarantees that make that safe are
demonstrable: two replicas writing the same key concurrently produce one winner and one
`KUI-CONFIG-VERSION-CONFLICT`, a `200` means the write is already visible to every replica that
has caught up, and a deployment running on the file adapter is told plainly that writing is not
configured rather than silently failing.

## Scope

1. `PUT /internal/v1/clusters/{clusterId}` — the single write endpoint of M1, with no UI
   (DEVPLAN §10 D6). It is the surface M8's wizard will call, built once.
2. The write DTO, which **does** carry secrets (a write is how they arrive), and the response,
   which does **not** (it is the redacted profile of CLAPI-003).
3. Optimistic concurrency on the wire: `If-Match: "<version>"` required; a mismatch is
   `409 KUI-CONFIG-VERSION-CONFLICT`; a create uses `If-Match: "0"`.
4. Read-your-writes: the route returns only after the writer has read its own record back from
   the log tail (STORE-007 implements the waiting; this task binds it and asserts the contract).
5. `NotConfigured` when the file adapter is in use: `501 KUI-UNSUPPORTED` with a message naming
   the setting an operator must set.
6. Authorisation: `ApplicationConfig.Edit`, checked through the same seam every other route uses.

## Non-goals

No wizard, no validate/apply flow, no file upload, no delete endpoint, no `GET /config`
(CW-005 is M8, DEVPLAN §3). No store implementation (STORE-007). No adapter (CLADP-003). No
gateway route: this endpoint is deliberately not proxied (decision 1).

## Design references

- DEVPLAN §10 D6 (why this endpoint exists at all and why it has no UI), §2's three store exit
  criteria it demonstrates (concurrent writers, read-your-writes, `NotConfigured` without a
  store).
- ADR-042 §"Consistency" — the version check, the read-back before acknowledging, the tombstone
  for deletion, and why a partition rather than a lock is the serialisation point.
- ADR-036 (single writer per section: the cluster service owns `kui.clusters[]`; nothing else
  may write `cluster/<id>`), ADR-034 (`KUI-CONFIG-VERSION-CONFLICT` exists already, code
  `ConfigVersionConflict`, 409, not retryable), ADR-021 (the permission model M6 fills in),
  ADR-022 (the typed security ADT the body carries).
- `research/kafbat/api-analysis.md`, Kouncil clusters CRUD row — the shape M8 will grow into.

## Files to create

```
services/cluster/contract/src/kui/cluster/contract/dto/ClusterWriteDtos.scala
services/cluster/contract/src/kui/cluster/contract/ClusterWriteEndpoints.scala
services/cluster/api/src/kui/cluster/api/ClusterWriteRoutes.scala
services/cluster/api/src/kui/cluster/api/ClusterWriteMapping.scala
services/cluster/contract/test/src/kui/cluster/contract/ClusterWriteDtosSuite.scala
services/cluster/api/test/src/kui/cluster/api/ClusterWriteRoutesSuite.scala
services/cluster/contract/test/resources/golden/cluster-write-request.json
```

## Files to change

```
services/cluster/contract/src/kui/cluster/contract/ClusterEndpoints.scala   (add to `all`)
services/cluster/api/src/kui/cluster/api/ClusterApi.scala                    (route list, documented)
services/cluster/app/src/kui/cluster/app/ClusterWiring.scala                 (pass the write use case)
services/cluster/api/openapi.json                                            (regenerated)
```

## Public Scala signatures to implement

```scala
package kui.cluster.contract.dto

/** What a caller sends to register or change a cluster.
  *
  * This DTO **carries secrets** — that is the point of a write — and it is the only cluster DTO
  * in KUI that does. Every secret field is `Secret[String]`, whose `toString` redacts
  * (`libs/kernel`), so a body that reaches a log line or an exception message carries `****`.
  * Nothing in this type is ever echoed: the response is the redacted profile.
  */
final case class ClusterWriteRequest(
    name: String,
    readOnly: Boolean,
    bootstrapServers: String,
    security: ClusterSecurityWrite,
    properties: Map[String, String],   // the ADR-022 override layer, applied last
    admin: AdminTuningWrite
)

final case class ClusterSecurityWrite(
    protocol: String,
    mechanism: Option[String],
    username: Option[String],
    password: Option[Secret[String]],
    truststore: Option[StoreMaterialWrite],
    keystore: Option[StoreMaterialWrite],
    verifyHostname: Boolean
)

final case class StoreMaterialWrite(base64: Secret[String], password: Option[Secret[String]])

final case class AdminTuningWrite(timeoutMs: Long, batchSize: Int, parallelism: Int)
```

```scala
package kui.cluster.contract

object ClusterWriteEndpoints {

  /** `PUT /internal/v1/clusters/{clusterId}`.
    *
    * `If-Match` is **required**, not optional: an unconditional write to a versioned record is a
    * lost update waiting for a second replica. `"0"` means "create; fail if it exists".
    */
  val put: Endpoint[SignedPrincipal, (ClusterId, String, ClusterWriteRequest), ErrorEnvelope, ClusterProfileDto, Any]

  val all: List[AnyEndpoint] = List(put)
}
```

```scala
package kui.cluster.api

object ClusterWriteRoutes {
  def apply[F[_]: Async](
      write: ClusterWriteUseCase[F],     // CLDOM-004 / CLADP-003 supply it
      rbac: ...,                          // see decision 3
      principals: PrincipalCodec[F],
      rejections: Counter[F, Long],
      logger: StructuredLogger[F]
  ): List[ServerEndpoint[Any, F]]
}
```

Status and code table, which is the endpoint's specification:

| Situation | Status | Code |
| --- | --- | --- |
| written and read back | 200 | — (the redacted profile) |
| `If-Match` does not match the current version | 409 | `KUI-CONFIG-VERSION-CONFLICT` |
| `If-Match: "0"` and the cluster exists | 409 | `KUI-CONFIG-VERSION-CONFLICT` |
| `If-Match` absent or unparseable | 400 | `KUI-VALIDATION` (field `If-Match`) |
| body fails validation (bad bootstrap, unknown mechanism, name slugging to a different id) | 400 | `KUI-VALIDATION`, one `details` entry per field |
| the file adapter is in use | 501 | `KUI-UNSUPPORTED` |
| the store is unreachable | 503 → mapped by `statusOf` | `KUI-UPSTREAM-UNAVAILABLE` |
| read-back did not arrive inside the timeout | 408 | `KUI-TIMEOUT` |
| principal lacks the permission | 403 | `KUI-FORBIDDEN` |

Every status comes from `ErrorEnvelope.statusOf(error)`. Do not write a second status table in
code; the table above documents what the codes already imply.

## Decisions this task takes (no ADR covers them)

1. **The endpoint is `/internal/v1` only and is not exposed through the gateway.** D6 says it
   ships "with no UI"; leaving it out of the proxied set means no browser can reach it, in a
   deployment where authentication is disabled and nothing grants `ApplicationConfig.Edit`. It
   is reachable by an internal caller and by tests, which is exactly what the exit criteria
   need. M8 adds the public route together with the wizard and the permission that guards it.
   `ServiceContracts` therefore needs no change: the gateway derives routes from
   `ClusterEndpoints.all`, and `ClusterWriteEndpoints.all` is a **separate list** — the two are
   concatenated only in `ClusterApi.documented`, so the write endpoint is documented in the
   service's own OpenAPI document and absent from the merged public one.
2. **`If-Match` rather than a `version` field in the body.** The version is metadata about the
   record, not part of it; putting it in the body would let a caller send a body whose `version`
   disagrees with the record it is replacing, and would make "the same body, applied twice"
   ambiguous. `If-Match` is the HTTP mechanism for exactly this, and `"0"` for create keeps one
   code path instead of two endpoints.
3. **Permission is checked here, not only at the gateway.** M0's `RbacPreCheck` lives in the
   gateway and this endpoint is not proxied, so the service checks for itself: the signed
   principal's roles are inspected for `ApplicationConfig.Edit` through
   `kui.security.Rbac.decide` (`libs/security-core`). With `kui.auth.type = disabled` nothing
   grants it — an anonymous principal fails the check — so the test suite supplies a principal
   that holds it. **This is the honest reading of D6**: "reachable only by an internal caller
   and by tests" is a property of the permission, not of the network.
4. **The response is the redacted profile, never an echo of the request.** Echoing would put
   every secret the caller just sent back on the wire and into any proxy log between them. A
   caller that wants to confirm what it sent has the version.
5. **A name that slugs to a different `ClusterId` than the path is a 400, not a rename.**
   ADR-031 makes the id a slug of the name, and renaming a cluster produces a new id — which is
   a create plus a delete, not a `PUT`. Silently accepting it would leave a record whose key and
   name disagree.

## Library coordinates

None new.

## Acceptance criteria

```
$ ./mill services.cluster.contract.jvm.test.testOnly 'kui.cluster.contract.ClusterWriteDtosSuite'
$ ./mill services.cluster.api.test.testOnly 'kui.cluster.api.ClusterWriteRoutesSuite'
$ ./mill services.cluster.api.openApi && ./mill services.cluster.api.openApiCheck
$ ./mill checkArchitecture
```

Against the all-in-one with a store:

```
$ curl -si -X PUT localhost:8081/internal/v1/clusters/new-one -H 'If-Match: "0"' \
    -H 'Content-Type: application/json' -d @cluster.json | head -1
HTTP/1.1 200 OK
$ curl -s localhost:8081/internal/v1/clusters/new-one/profile | jq -c '{version, security}'
{"version":1,"security":{"protocol":"SASL_SSL","mechanism":"SCRAM-SHA-512","truststoreConfigured":true,"keystoreConfigured":false}}
$ curl -s -X PUT localhost:8081/internal/v1/clusters/new-one -H 'If-Match: "0"' -d @cluster.json | jq -r .code
KUI-CONFIG-VERSION-CONFLICT
```

Without a store:

```
$ curl -si -X PUT localhost:8081/internal/v1/clusters/new-one -H 'If-Match: "0"' -d @cluster.json | head -1
HTTP/1.1 501 Not Implemented
$ curl -s ... | jq -r .message
the metadata store is not configured; set kui.store.kafka.bootstrapServers to enable runtime cluster changes
```

## Tests required

`ClusterWriteRoutesSuite` (MUnit + Tapir stub interpreter + a fake `ClusterWriteUseCase`):

- One case per row of the status table above.
- `aSuccessfulWriteReturnsTheRedactedProfileAndNotTheRequest` — assert `kui-secret-canary`,
  used as every secret in the request, appears nowhere in the response (R-12 again, on the one
  endpoint that legitimately receives secrets).
- `theRouteWaitsForReadBackBeforeAnsweringTwoHundred` — with `TestControl`, a fake whose
  read-back completes at t=2s: assert no response before t=2s. This is the exit criterion "a
  write returns 200 only after the writer has read its own record back".
- `aRequestBodyNeverReachesALogLine` — drive a failing write and assert the captured log output
  contains no secret token. `Secret`'s redaction makes this true; the test makes it stay true.
- `aNameThatSlugsToADifferentIdIsFourHundred` (decision 5).
- `everyValidationFailureIsReportedTogether` — a body with three bad fields yields three
  `details` entries in one response, not the first one (ADR-013's accumulate-everything rule,
  applied to a request body).

`ClusterWriteDtosSuite` (cross-compiled): golden decode; `Secret` fields redact in `toString`;
the request round-trips; a request with no `properties` decodes with an empty map.

**The concurrency exit criterion is not tested here.** Two replicas racing on one key is
STORE-007's and STORE-009's test, against a real broker; this suite asserts that the conflict
the store reports becomes a 409 with the right code. Duplicating the race with fakes would test
the fake.

## Observability

- `kui.config.write{section="cluster", outcome="ok"|"conflict"|"rejected"|"unavailable"}` — a
  counter. A rising `conflict` rate is a real operational signal: two writers are fighting.
- One INFO per successful write with `cluster.id`, the old version and the new version, and the
  principal's name. Never the body, never a field value.
- `kui.config.write.duration` — a histogram that includes the read-back wait, because the wait
  is what the caller experiences.

## Degraded behaviour

- **Store unreachable**: the write is **rejected**, never buffered. ADR-042's version check
  needs the log to be readable, and a queued write would apply against a base version that no
  longer exists. This is the exit criterion "writes are rejected rather than lost".
- **File adapter**: `NotConfigured` (501) with the setting named, and every read path in M1
  continues to work.
- **Read-back timeout**: `408 KUI-TIMEOUT`, with a message saying the record may still have been
  written — because it may have been, and telling a caller otherwise would invite a duplicate
  write against a stale version. The caller's recovery is to re-read the profile.

## Docs to update

`services/cluster/api/openapi.json` (regenerated in this commit).
`docs/operations/metadata-store.md` gains a "Changing a cluster at runtime" paragraph with the
curl transcript above; if that file does not exist yet, the content goes in the Implementation
Report for CFGOP-008 to place.

## Deviations

1. **`ClusterWriteUseCase` is created in `services/cluster/application` by this task.** The spec says
   "CLDOM-004 / CLADP-003 supply it"; neither did, and there was none. The alternative was an `api`
   module orchestrating a domain port directly, which puts a use case in the wrong layer to avoid
   crossing a task boundary. One new file, in the shape the rest of that module uses.
2. **The permission check compares role *names* against `ApplicationConfig.Edit`.** `kui.security`
   has no `Rbac.decide` in M1, and the role vocabulary is M6's. The check exists, fails for an
   anonymous principal, and is one function for M6 to replace.
3. **The `If-Match` version is mapped in `ClusterWriteMapping.versionOf`**, tolerating quotes and a
   `W/` prefix, for the same proxy reason as the profile endpoint's ETag comparison.
4. **`AdminTuningWrite.timeoutMs` also caps the per-request timeout.** A caller sets one number - how
   long a whole admin call may take - and a per-round-trip default larger than it can never be
   reached, which the domain refuses. Capping is refusing a number the caller never wrote.
5. **`kui.config.write` and `kui.config.write.duration` are not implemented.** One INFO per
   successful write, with the cluster id and both versions, is. **Owed.**
6. The concurrency exit criterion is asserted by the STORE lane against a real broker, as the spec
   directs; this suite asserts that the conflict the store reports becomes a 409 with the right code.
