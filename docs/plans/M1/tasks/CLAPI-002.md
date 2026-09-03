# CLAPI-002 — Cluster read endpoints: clusters, brokers, configs, log dirs, refresh

- **ID:** CLAPI-002
- **Title:** Cluster read endpoints: clusters, brokers, configs, log dirs, refresh
- **Milestone / Feature:** M1 / CL-001, CL-002, CL-003, CL-005, BR-001, BR-002, BR-005
- **Owner role:** Chief Architect
- **Size:** M
- **Dependencies / blocked by:** CLAPI-001

## Goal (user value)

The addresses of everything M1 shows, declared once. After this task the gateway can derive its
public routes, the browser can generate a typed client and the OpenAPI document can be
regenerated — all from the same six endpoint values, so no path in KUI is ever written twice.

## Scope

1. The six read endpoints of `kui-cluster-service`, replacing `ping`, declared in
   `services/cluster/contract` on the cross-compiled `KuiEndpoint.internal` base.
2. The response envelopes those endpoints carry, composing the fragments CLAPI-001 published in
   `libs/contracts-core`.
3. Path- and query-parameter codecs for `ClusterId` and `BrokerId`, with the validation failure
   rendered as a Tapir decode failure (so a bad id is a 400 with the field named, not a 500).
4. `ClusterEndpoints.all` updated; `ping` and `PingResponse` **deleted**, together with
   `PingDtosSuite`'s ping cases and the `ping-response.json` golden file.
5. The contract suite extended: every endpoint's path is asserted to start `/internal/v1`, and
   every endpoint's security input is asserted to be `SignedPrincipal` (the assumption
   `ContractRouting`'s casts rest on).

## Non-goals

No server logic (CLAPI-004). No profile or stream endpoints (CLAPI-003). No write endpoint
(CLAPI-009). No gateway changes (CLAPI-006). No broker config **edit**: `BR-002` is read-only in
M1 and `PUT .../configs/{name}` must not be declared — an endpoint declared before its safety
net exists is one someone will implement (DEVPLAN §3).

## Design references

- `research/kafbat/api-analysis.md` "Proposed KUI `/api/v1` mapping", rows for
  `GET /clusters`, `POST /clusters/{id}/refresh`, brokers / configs / log-dirs. Paths are
  kebab-case, plural nouns, explicit sub-resources — `/log-dirs`, never `/logdirs`.
- `research/kafka/admin-capabilities.md` §1 — what each endpoint's data actually costs and which
  parts of it can fail independently (`describeLogDirs` per-directory errors,
  `describeConfigs` per-broker errors on managed services).
- DEVPLAN §10 D5 (the dashboard's data boundary) and D10 (30 s server-side refresh, no browser
  polling, forced refresh is the user's control).
- ADR-003 (contract is the single source), ADR-034 (one error envelope), ADR-026 (paging) —
  see decision 3 below for why nothing here is paged.
- `ARCHITECTURE.md` §5 (a service serves `/internal/v1`; `/api/v1` belongs to the gateway).

## Files to create

```
services/cluster/contract/src/kui/cluster/contract/dto/ClusterResponses.scala
services/cluster/contract/src/kui/cluster/contract/dto/BrokerResponses.scala
services/cluster/contract/test/src/kui/cluster/contract/ClusterEndpointsSuite.scala
services/cluster/contract/test/src/kui/cluster/contract/ClusterResponsesSuite.scala
services/cluster/contract/test/resources/golden/clusters-response.json
services/cluster/contract/test/resources/golden/brokers-response.json
services/cluster/contract/test/resources/golden/broker-configs-response.json
services/cluster/contract/test/resources/golden/log-dirs-response.json
services/cluster/contract/test/resources/golden/refresh-accepted.json
```

## Files to change

```
services/cluster/contract/src/kui/cluster/contract/ClusterEndpoints.scala   (rewritten)
services/cluster/contract/test/src/kui/cluster/contract/GoldenDocuments.scala
services/cluster/contract/test/src/kui/cluster/contract/PingDtosSuite.scala  (deleted)
services/cluster/contract/src/kui/cluster/contract/dto/PingDtos.scala        (deleted)
services/cluster/contract/test/resources/golden/ping-response.json           (deleted)
```

`PingUseCase`, `Ping` and `PingMapping` are **not** deleted here — CLDOM-001 deletes the domain
and application halves, CLAPI-004 deletes `PingMapping` and the route. This task deletes only
the contract's half, and it may therefore land only together with CLAPI-004 or after it in the
same commit if the build would otherwise be red. If in doubt, keep `ping` compiling until
CLAPI-004 and delete all four files there: **`main` is never red at the end of a task**
(DEVPLAN §6).

## Public Scala signatures to implement

```scala
package kui.cluster.contract.dto

import java.time.Instant
import kui.contracts.Section
import kui.contracts.cluster.{BrokerConfigEntryDto, BrokerDto, ClusterRowDto, ClusterSummaryDto, LogDirDto}
import kui.kernel.{BrokerId, ClusterId}

final case class ClustersResponse(items: List[ClusterRowDto], generatedAt: Instant)

final case class ClusterDetailResponse(cluster: ClusterRowDto)

final case class BrokersResponse(brokers: Section[List[BrokerDto]])

final case class BrokerConfigsResponse(configs: Section[List[BrokerConfigEntryDto]])

final case class LogDirsResponse(logDirs: Section[List[LogDirDto]])

/** What a forced refresh answers with: it was accepted, not that it has finished. */
final case class RefreshAcceptedDto(clusterId: ClusterId, requestedAt: Instant)
```

```scala
package kui.cluster.contract

object ClusterEndpoints {

  val ClustersSegment: String = "clusters"
  val BrokersSegment: String = "brokers"
  val LogDirsSegment: String = "log-dirs"
  val RefreshSegment: String = "refresh"
  val BrokerIdParam: String = "brokerId"

  /** `ClusterId` as a path segment. A malformed slug is a decode failure — a 400 naming the
    * field — rather than a lookup that cannot match and answers 404. The two mean different
    * things to a caller: "you typed something that is not an id" and "no such cluster".
    */
  given Codec[String, ClusterId, CodecFormat.TextPlain] = ...
  given Codec[String, BrokerId, CodecFormat.TextPlain] = ...

  val listClusters:  Endpoint[SignedPrincipal, Unit, ErrorEnvelope, ClustersResponse, Any]
  val getCluster:    Endpoint[SignedPrincipal, ClusterId, ErrorEnvelope, ClusterDetailResponse, Any]
  val listBrokers:   Endpoint[SignedPrincipal, ClusterId, ErrorEnvelope, BrokersResponse, Any]
  val brokerConfigs: Endpoint[SignedPrincipal, (ClusterId, BrokerId, Boolean), ErrorEnvelope, BrokerConfigsResponse, Any]
  val logDirs:       Endpoint[SignedPrincipal, (ClusterId, Option[BrokerId]), ErrorEnvelope, LogDirsResponse, Any]
  val refresh:       Endpoint[SignedPrincipal, ClusterId, ErrorEnvelope, RefreshAcceptedDto, Any]

  val all: List[AnyEndpoint] =
    List(listClusters, getCluster, listBrokers, brokerConfigs, logDirs, refresh)
}
```

Paths, exactly:

| Endpoint | Method and path | Inputs | Success |
| --- | --- | --- | --- |
| `listClusters` | `GET /internal/v1/clusters` | — | 200 `ClustersResponse` |
| `getCluster` | `GET /internal/v1/clusters/{clusterId}` | path | 200 `ClusterDetailResponse` |
| `listBrokers` | `GET /internal/v1/clusters/{clusterId}/brokers` | path | 200 `BrokersResponse` |
| `brokerConfigs` | `GET /internal/v1/clusters/{clusterId}/brokers/{brokerId}/configs` | path + `docs: Boolean = false` | 200 `BrokerConfigsResponse` |
| `logDirs` | `GET /internal/v1/clusters/{clusterId}/log-dirs` | path + `brokerId: Option[BrokerId]` | 200 `LogDirsResponse` |
| `refresh` | `POST /internal/v1/clusters/{clusterId}/refresh` | path | **202** `RefreshAcceptedDto` |

Every endpoint carries `.name("cluster.<something>")`, a `.summary`, a `.description` and
`.tag("cluster")`; the name is what the merged OpenAPI document keys on (GW-007) and what a
metric label carries, so it must be stable and unique.

## Decisions this task takes (no ADR covers them)

1. **`refresh` answers 202 with a body, not 204.** The body carries `requestedAt`, which is what
   CLUI-008 shows ("refresh requested at 14:02:11") while the 30-second loop does the work. A
   204 would leave the button with nothing to say. The status is 202 because the response does
   **not** mean the snapshot is new — D10 keeps refresh asynchronous, so a 200 would be a lie.
2. **Log directories are one endpoint with an optional `brokerId`, not a sub-resource of a
   broker.** `describeLogDirs` is a per-broker admin call that the adapter batches
   (`admin-capabilities.md` §1: "use `describeLogDirs` per broker with a bounded parallelism"),
   and the brokers *list* page wants the totals for every broker in one call. One endpoint that
   can be narrowed serves both the list page and the broker-detail tab; two endpoints would make
   the list page issue N calls. This matches Kafbat's `/brokers/logdirs?broker=`.
3. **Nothing here is paged.** ADR-026 governs paged listings; a cluster list is a handful of
   configured entries and a broker list is a few dozen rows read from one snapshot
   (DEVPLAN §3: "no virtualized tables"). Adding `page`/`pageSize` to a list that is never long
   would add a cursor contract with no consumer and a second sort order to keep consistent.
4. **`docs` is a query flag, defaulting to `false`.** `DescribeConfigsOptions.includeDocumentation`
   exists only from Kafka 2.6 and doubles the response size; the configs tab asks for it, and
   anything else does not. When the cluster cannot supply documentation the field is simply
   `None` (CLAPI-001) — asking for it on an old broker is never an error.
5. **`getCluster` exists even though `listClusters` returns the same row.** The broker pages need
   one cluster's summary without fetching every cluster's, and a deep link into a cluster must
   work on a KUI with forty of them.

## Library coordinates

None new. `services.cluster.contract.{jvm,js}` already has `tapir-core` 1.13.31,
`tapir-json-circe` 1.13.31, `circe-core` 0.14.16 and `circe-parser` 0.14.16
(`DEPENDENCY_MATRIX.md`).

## Acceptance criteria

```
$ ./mill services.cluster.contract.jvm.test
$ ./mill services.cluster.contract.js.test          # separate invocation (CLAUDE.md)
$ ./mill services.cluster.contract.jvm.compile services.cluster.contract.js.compile
$ ./mill checkArchitecture
```

`ClusterEndpointsSuite` prints the six paths; they must read exactly:

```
GET    /internal/v1/clusters
GET    /internal/v1/clusters/{clusterId}
GET    /internal/v1/clusters/{clusterId}/brokers
GET    /internal/v1/clusters/{clusterId}/brokers/{brokerId}/configs
GET    /internal/v1/clusters/{clusterId}/log-dirs
POST   /internal/v1/clusters/{clusterId}/refresh
```

## Tests required

`ClusterEndpointsSuite` (cross-compiled):

- `everyEndpointIsUnderInternalV1` — the precondition `ContractRouting.derive` fails
  construction on, asserted here so the failure is found in this module and not in the gateway.
- `everyEndpointCarriesTheSignedPrincipalSecurityInput` — the checked assumption behind
  `Unsafe.secured`'s cast in the gateway.
- `everyEndpointHasAUniqueName`, `...HasASummaryAndATag`.
- `theEndpointListIsExactlyTheSixDeclared` — a seventh endpoint must be a deliberate edit.
- `aMalformedClusterIdIsADecodeFailure` — decode `"Not A Slug"` through the path codec and
  assert a `DecodeResult.Failure` mentioning `clusterId`.
- `pingIsGone` — `all.exists(_.info.name.contains("cluster.ping"))` is false. It reads as a
  joke and it is not: `Ping` surviving somewhere is exactly the failure mode DEVPLAN §1 names.

`ClusterResponsesSuite` (cross-compiled): golden decode and round-trip for each response type,
including a `LogDirsResponse` whose section is `Unavailable` and a `BrokersResponse` whose
section is `Stale`.

## Observability

None: this module has no runtime. The endpoint `name`s it fixes are what CLAPI-004's metrics and
the gateway's access log label their spans with, so a rename here is an observability change and
the Implementation Report should say so.

## Degraded behaviour

Each response wraps its data in a `Section`, so a partially available cluster is a 200 with a
`stale` or `unavailable` section rather than a 5xx. `ClustersResponse.items` is deliberately a
plain list: the *list of configured clusters* comes from the registry, which is local
configuration overlaid by the replayed store, and is available whenever the service is. What can
fail is each cluster's `summary`, and that is where the `Section` sits.

## Docs to update

None in this task. `docs/api/openapi.json` is regenerated by CLAPI-010, once the write endpoint
and the aggregation exist, so the document is regenerated once rather than four times.
