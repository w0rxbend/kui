# CLAPI-010 — OpenAPI regeneration, error-code table, contract snapshot

- **ID:** CLAPI-010
- **Title:** OpenAPI regeneration, error-code table, contract snapshot
- **Milestone / Feature:** M1 / OT-008, OT-009, KU-011
- **Owner role:** Chief Architect
- **Size:** S
- **Dependencies / blocked by:** CLAPI-007, CLAPI-009

## Goal (user value)

The published description of KUI's API matches the API. An integrator reading
`docs/api/openapi.json` sees the cluster and broker endpoints as they actually are, and
`docs/api/error-codes.md` lists every code M1 can return, so a failure a caller meets is a
failure a caller can look up.

## Scope

1. Regenerate and commit `services/cluster/api/openapi.json` and `docs/api/openapi.json`.
2. Regenerate and commit `docs/api/error-codes.md` (`./mill docs.errorCodes`) — no new
   `ErrorCode` case is added by the CLAPI area, but the store and Kafka lanes may add some and
   this is the task that ensures the committed table is current at the end of M1.
3. Verify the merged document's shape: the aggregation endpoint appears once, the write endpoint
   does **not** appear in the merged public document, the SSE endpoints appear with their
   event-stream bodies, and `OpenApiStyleCheck` passes for every new endpoint.
4. A snapshot test over the *shape* of the merged document, so a contract change that nobody
   regenerated fails CI rather than shipping.
5. The error-code section of `docs/api/error-codes.md` gains no hand-written text — the file is
   generated and says so in its first line.

## Non-goals

No new endpoints. No new error codes from this area (CLAPI's endpoints reuse
`ClusterNotFound`, `Validation`, `Unsupported`, `ConfigVersionConflict`, `Timeout`, `Forbidden`
and the upstream family, all of which already exist in `libs/kernel`'s `ErrorCode`). No
documentation prose about clusters (CFGOP-008 owns the milestone documentation).

## Design references

- The M0 machinery this task drives: `build.mill`'s `services.cluster.api.openApi` /
  `openApiCheck`, `services.gateway.api.openApi` / `openApiCheck`, `docs.errorCodes`, and
  `services/gateway/api/src/kui/gateway/api/openapi/{OpenApiMerge,OpenApiStyleCheck,OpenApiDocument}.scala`
  (GW-007).
- ADR-003 (the contract is the single source; a generated document nobody regenerates is worse
  than none), ADR-034 (the error envelope and the one code table).
- DEVPLAN §9 item 6 ("`GET /api/v1/openapi.json` is regenerated and its snapshot committed under
  `docs/api/openapi.json`; `docs/api/error-codes.md` includes the store and Kafka codes").
- CLAPI-009 decision 1 — the write endpoint is deliberately absent from the merged document.

## Files to change

```
docs/api/openapi.json                      (regenerated)
docs/api/error-codes.md                    (regenerated)
services/cluster/api/openapi.json          (regenerated)
services/gateway/api/test/src/kui/gateway/api/openapi/OpenApiMergeSuite.scala   (new assertions)
```

## Files to create

```
services/gateway/api/test/src/kui/gateway/api/openapi/MergedDocumentShapeSuite.scala
```

## Public Scala signatures to implement

None: this task adds assertions, not production code. The one exception, if
`ClusterApi.documented` does not already include them, is wiring the new endpoint lists into the
documents:

```scala
// services/cluster/api/ClusterApi.scala
val documented: List[AnyEndpoint] =
  ClusterEndpoints.all ++
    ProfileEndpoints.all ++
    ClusterWriteEndpoints.all ++
    ClusterStreamEndpoint.endpoints[fs2.Pure] ++
    List(HealthEndpoints.live, HealthEndpoints.ready, HealthEndpoints.capabilities)

// services/gateway/api/openapi/OpenApiDocument.scala — the merged, public document
//   = the gateway's own endpoints (including ClusterOverviewEndpoints.all)
//   + each service's *proxied* endpoints, path-rewritten
//   - anything in ServiceContracts.aggregated (already served by the gateway itself)
//   - anything not in ServiceContracts.proxied (so the write endpoint never appears)
```

## Decisions this task takes (no ADR covers them)

1. **The merged public document describes what a browser can call, and nothing else.** An
   internal-only endpoint in the public document is an invitation to call something that is not
   routed, and the 404 that follows is a support ticket. The service's own document keeps
   everything, so an operator debugging `kui-cluster` directly still has a complete description.
2. **The snapshot test asserts shape, not bytes.** `openApiCheck` already fails on any byte
   difference and is the regeneration gate. A second byte-level assertion would fail twice for
   one cause. `MergedDocumentShapeSuite` instead asserts the properties that matter and that a
   byte diff cannot express: every path appears exactly once, every operation has a unique
   `operationId`, every operation documents the error envelope, no path contains `internal`, and
   the set of public cluster paths equals the set derived from
   `ServiceContracts.proxied` plus the gateway's own.
3. **`docs.errorCodes --check` runs in this task even though this area adds no code.** The file
   is generated from an enum other lanes edit; someone has to be the one who notices it drifted,
   and the milestone's documentation gate (DEVPLAN §9.6) names this task.

## Library coordinates

None new.

## Acceptance criteria

```
$ ./mill services.cluster.api.openApi
$ ./mill services.gateway.api.openApi
$ ./mill docs.errorCodes
$ git status --short
 M docs/api/error-codes.md
 M docs/api/openapi.json
 M services/cluster/api/openapi.json

$ ./mill services.cluster.api.openApiCheck
$ ./mill services.gateway.api.openApiCheck
$ ./mill docs.errorCodes --check
$ ./mill services.gateway.api.test.testOnly 'kui.gateway.api.openapi.*'
```

Spot checks on the merged document:

```
$ jq -r '.paths | keys[]' docs/api/openapi.json | grep clusters
/api/v1/clusters
/api/v1/clusters/{clusterId}
/api/v1/clusters/{clusterId}/brokers
/api/v1/clusters/{clusterId}/brokers/{brokerId}/configs
/api/v1/clusters/{clusterId}/log-dirs
/api/v1/clusters/{clusterId}/refresh

$ jq -r '.paths | keys[]' docs/api/openapi.json | grep -c internal
0
$ jq -r '.paths["/api/v1/clusters"].put // "absent"' docs/api/openapi.json
absent
```

## Tests required

`MergedDocumentShapeSuite` (MUnit, JVM):

- `everyPublicPathAppearsExactlyOnce` — the collision `ServiceContracts.aggregated` prevents,
  asserted at the document level so the two mechanisms cannot both be wrong.
- `noPublicPathContainsInternal`.
- `everyOperationHasAUniqueOperationId`.
- `everyOperationDocumentsTheErrorEnvelope`.
- `theWriteEndpointIsAbsentFromTheMergedDocument` and its complement,
  `theWriteEndpointIsPresentInTheClusterServiceDocument` — the pair is decision 1, and either
  half alone would pass while the intent was broken.
- `thePublicClusterPathsEqualTheDerivedSet` — computed from `ServiceContracts.proxied` and
  `ClusterOverviewEndpoints.all`, so a seventh endpoint requires no edit here.
- `theSseEndpointsAreDocumentedAsEventStreams`.

`OpenApiMergeSuite` (existing): a case asserting an aggregated endpoint is taken from the
gateway's own list and not from the service's.

## Observability

None.

## Degraded behaviour

None: this task produces files.

## Docs to update

The three generated files listed above. Their content is generated; the commit message says what
changed in the API and why, because a diff of a generated JSON document is not readable and the
commit body is the only place a reviewer will get the summary.
