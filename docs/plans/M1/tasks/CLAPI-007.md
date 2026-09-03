# CLAPI-007 — Gateway: the dashboard aggregation with per-row section status

- **ID:** CLAPI-007
- **Title:** Gateway: the dashboard aggregation with per-row section status
- **Milestone / Feature:** M1 / CL-003, OT-001, OT-004, KU-033 (first scenario)
- **Owner role:** Chief Architect
- **Size:** M
- **Dependencies / blocked by:** CLAPI-006

## Goal (user value)

The milestone's headline promise, made visible: three configured clusters, one of them
unreachable, and the dashboard returns two populated rows and one marked
`Unavailable: <reason>` that is still clickable — in a time bounded by the per-upstream timeout,
not by the dead cluster. The response never fails as a whole, whatever fails inside it.

## Scope

1. `GET /api/v1/clusters` served **by the gateway**, as an aggregation, replacing the derived
   proxy route CLAPI-006 excluded.
2. `ClusterOverviewUseCase` in `services/gateway/application`: fetch the cluster service's
   `/internal/v1/clusters` through its `ServiceClient`, merge the capability registry's
   per-cluster entries, and fall back to the last known good rows when the call fails.
3. `LastKnown[A]` — the ADR-043 §2 cached fallback, a `Ref` holding the last successful value
   with the instant it was fetched.
4. The two-level section shape and its rendering rules (decision 1).
5. The timing test, with `TestControl`, asserting the bound against the **configured** timeout
   rather than a literal (DEVPLAN D9).

## Non-goals

No `GET /clusters/{id}/dashboard` (decision 2). No per-cluster fan-out from the gateway — the
gateway calls the cluster *service*, once, and never a broker (A8). No metrics section: there is
no metrics service until M8. No capability registry changes (CLAPI-008). No frontend (CLUI-003).

## Design references

- DEVPLAN §2 (the dashboard exit criterion, verbatim), §7's "Gateway aggregation" suite row,
  R-8 (serialisation risk and why the bound is asserted with `TestControl`), §10 D4 (an
  unreachable managed cluster is a `Section` in a 200, never a dimmed capability), D5 (what the
  dashboard may show), D9 (the bound is `kui.gateway.services.cluster.timeout`).
- `ARCHITECTURE.md` §6 ("Aggregated responses are partial by design"; `GET /clusters` is named
  in the list of aggregations that must return partial results).
- `research/kafbat/api-analysis.md`, `GET /api/clusters` row: "Each item carries `status` and
  `capabilities[]` from the registry; a down cluster-service yields items from cached config
  with `status: unavailable`."
- ADR-043 §2 (a direct call must have a cached last-known fallback and may never be the reason
  the caller becomes unavailable), ADR-037 (the per-upstream timeout, bulkhead and breaker that
  bound it), ADR-039 §6.
- `research/kafbat/ui-analysis.md` "Dashboard" for the columns; `research/design/REFERENCE.md`
  for how they look.

## Files to create

```
services/gateway/contract/src/kui/gateway/contract/ClusterOverviewEndpoints.scala
services/gateway/contract/src/kui/gateway/contract/dto/ClusterOverview.scala
services/gateway/application/src/kui/gateway/application/cluster/ClusterOverviewUseCase.scala
services/gateway/application/src/kui/gateway/application/cluster/LastKnown.scala
services/gateway/api/src/kui/gateway/api/ClusterOverviewRoutes.scala
services/gateway/contract/test/src/kui/gateway/contract/ClusterOverviewSuite.scala
services/gateway/contract/test/resources/golden/cluster-overview.json
services/gateway/application/test/src/kui/gateway/application/cluster/ClusterOverviewUseCaseSuite.scala
services/gateway/application/test/src/kui/gateway/application/cluster/LastKnownSuite.scala
services/gateway/api/test/src/kui/gateway/api/ClusterOverviewRoutesSuite.scala
```

## Files to change

```
services/gateway/app/src/kui/gateway/app/GatewayWiring.scala   (build the use case, pass the route in `extra`)
services/gateway/api/src/kui/gateway/api/GatewayApi.scala      (doc comment: what `extra` now carries)
```

## Public Scala signatures to implement

```scala
package kui.gateway.contract.dto

import java.time.Instant
import kui.contracts.Section
import kui.contracts.capability.CapabilityState
import kui.contracts.cluster.{ClusterRowDto, ClusterSummaryDto}

/** The dashboard, in one document, with two independent levels of failure.
  *
  *   - `clusters` is the **outer** section: can the gateway see the list of clusters at all?
  *     `Ok` when the cluster service answered, `Stale` when it did not and the gateway is
  *     serving its last known rows, `Unavailable` when it did not and there are none.
  *   - each row's `summary` is the **inner** section: can the cluster service see *that*
  *     cluster's brokers?
  *
  * The two failures are different events with different fixes — "KUI's cluster service is
  * down" and "this Kafka cluster is unreachable" — and a screen has to say which one happened.
  */
final case class ClusterOverviewDto(
    clusters: Section[List[ClusterOverviewRow]],
    generatedAt: Instant
)

final case class ClusterOverviewRow(
    cluster: ClusterRowDto,             // identity + inner section, from the cluster service
    capability: CapabilityState         // what the registry says about (cluster-service, thisCluster)
)
```

```scala
package kui.gateway.contract

object ClusterOverviewEndpoints {

  /** `GET /api/v1/clusters`. Declared on `GatewayEndpoints.base`, because this path is the
    * gateway's own — CLAPI-006 removed it from the derived proxy routes.
    */
  val overview: PublicEndpoint[Unit, ErrorEnvelope, ClusterOverviewDto, Any]

  val all: List[AnyEndpoint] = List(overview)
}
```

```scala
package kui.gateway.application.cluster

/** The last value that worked, and when.
  *
  * A `Ref`, not a `libs/cache` `SnapshotCell`: this holds one value with no refresh loop, no
  * TTL and no supervisor, and the gateway has no `libs.cache` dependency in M1's module map
  * (DEVPLAN §5.2). If a second consumer appears, promote it then.
  */
final class LastKnown[F[_], A] private (ref: Ref[F, Option[(A, Instant)]]) {
  def get: F[Option[(A, Instant)]]
  def put(value: A, at: Instant): F[Unit]
}

trait ClusterOverviewUseCase[F[_]] {
  def overview(principal: Principal, correlationId: CorrelationId): F[ClusterOverviewDto]
}

object ClusterOverviewUseCase {
  def resource[F[_]: {Async, Parallel}](
      clusters: ServiceClient[F],           // the cluster service's client
      registry: CapabilityRegistry[F],
      logger: StructuredLogger[F]
  ): Resource[F, ClusterOverviewUseCase[F]]
}
```

The use case's algorithm, exactly:

1. `clusters.call(ClusterEndpoints.listClusters, ())(CallContext(principal, correlationId, None))`
   — one call, already bounded by `UpstreamConfig.callTimeout` from
   `kui.gateway.services.cluster.timeout` (ADR-037; the gateway adds no second timeout, D9).
2. On `Right(response)`: store the rows in `LastKnown`, and build `Section.Ok(rows, now)`.
3. On `Left(error)`: read `LastKnown`. Some → `Section.Stale(rows, fetchedAt, reasonOf(error))`;
   None → `Section.Unavailable(reasonOf(error), error.message, Some(now))` with an empty list.
   Either way, report the failure to the capability signals exactly as
   `ContractRouting.reportIfInfrastructure` does — an aggregation that swallows an upstream
   failure would leave the sidebar green while the page shows an outage.
4. Merge capabilities: for each row, `registry.state(CapabilityKey(clusterServiceId, Some(row.id)))`,
   read from the in-memory registry (no I/O). A row with no entry yet is `Available` when its
   inner section is `Ok`, and `Degraded(Starting)` otherwise — never missing.
5. The response is **always** a 200. There is no failure path that produces a 5xx from this
   route; a bug that throws is caught by the shared error interceptor, and the suite asserts the
   route never produces one for any upstream failure.

## Decisions this task takes (no ADR covers them)

1. **Two levels of `Section`, as above.** The exit criterion needs both: "stopping `kui-cluster`
   leaves the other clusters' cached rows (greyed, timestamped) usable" is the outer section
   going `Stale`, and "one unreachable, two populate" is one inner section going `Unavailable`.
   A single flat status could express one of the two, and CFGOP-007's E2E asserts both.
2. **`GET /clusters/{id}/dashboard` is not built in M1.** `research/kafbat/api-analysis.md`
   proposes it and D5 bounds what it could contain: after removing topic counts and metrics,
   what remains is exactly `GET /clusters/{id}`, which CLAPI-002 already serves. No M1 screen
   consumes a per-cluster dashboard (CLUI-003 is the multi-cluster list, CLUI-004 the brokers
   list), and an aggregation endpoint with no caller is an aggregation nobody tests. It arrives
   with the metrics section in M8.
3. **The gateway makes one upstream call, not one per cluster.** The per-cluster data is already
   aggregated by the cluster service from cached snapshots (D10: a 30-second refresh loop, not a
   live admin call per request), so fanning out per cluster would multiply calls without
   multiplying freshness. R-8's failure mode — "the dashboard serialises its per-cluster calls
   and a dead cluster stalls the page" — is therefore prevented structurally, and the timing
   test still asserts it: a cluster service that never answers must not make this route take
   longer than the configured timeout.
4. **Rows are ordered by the cluster service's order, which is configuration order, and the
   gateway does not re-sort.** Sorting is the table's job (CLUI-003), and two components sorting
   by different rules is how a row appears to move when nothing changed.
5. **A row for a cluster the registry knows but the cluster service did not return is not
   invented.** The list is the cluster service's answer; the registry only decorates it.

## Library coordinates

None new. `services.gateway.application` has cats-effect and fs2; `services.gateway.contract`
has tapir and circe.

## Acceptance criteria

```
$ ./mill services.gateway.api.test.testOnly 'kui.gateway.api.ClusterOverviewRoutesSuite'
$ ./mill services.gateway.application.test.testOnly 'kui.gateway.application.cluster.*'
$ ./mill services.gateway.contract.jvm.test services.gateway.contract.js.test   # separate invocations
$ ./mill checkArchitecture
```

The milestone demonstration, against Compose with three configured clusters, one pointing at a
closed port:

```
$ curl -s -w '\n%{time_total}s\n' localhost:8080/api/v1/clusters | jq -c \
    '.clusters.status, [.clusters.data[] | {id: .cluster.id, status: .cluster.summary.status,
                                            reason: .cluster.summary.reason}]'
"ok"
[{"id":"prod-eu","status":"ok","reason":null},
 {"id":"staging","status":"ok","reason":null},
 {"id":"dead","status":"unavailable","reason":"UPSTREAM_UNAVAILABLE"}]
0.081s

$ docker stop kui-cluster && curl -s localhost:8080/api/v1/clusters | jq -c '.clusters | {status, fetchedAt, reason}'
{"status":"stale","fetchedAt":"2026-09-03T10:11:12.000Z","reason":"UPSTREAM_UNAVAILABLE"}
```

## Tests required

`ClusterOverviewUseCaseSuite` (MUnit + `munit-cats-effect` + `TestControl` + a stub client):

- `threeClustersOneUnreachableGivesTwoOkSectionsAndOneUnavailable` — the exit criterion, at the
  use-case level. Assert the unavailable row still carries `id`, `name` and `bootstrapServers`,
  because "remains clickable" is a property of the payload before it is a property of the UI.
- `theResponseIsBoundedByTheConfiguredTimeoutNotByTheDeadCluster` — **the R-8 / D9 test.** The
  stub client never answers; with `TestControl`, assert the result arrives at
  `config.timeout` ± one tick, and write the assertion **against the configured value read from
  the `UpstreamConfig` under test**, never a literal `10.seconds`, so that changing the default
  cannot silently invalidate the bound.
- `aFailedCallServesTheLastKnownRowsAsStaleWithTheirOriginalFetchedAt` (ADR-043 §2).
- `aFailedCallWithNoCacheIsAnUnavailableOuterSectionAndAnEmptyList`.
- `theRouteNeverProducesAFiveHundred` (property over generated `KuiError`s from the stub).
- `anInfrastructureFailureIsReportedToTheCapabilitySignals`, and its mirror,
  `anApplicationFailureIsNotReported` (ADR-039 §6).
- `aClusterThatIsUnreachableDoesNotChangeTheServiceCapability` — D4, asserted here as well as in
  CLAPI-008, because this is the response a user actually sees.
- `capabilityStateIsMergedPerRow`, and `aRowWithNoRegistryEntryIsNeverMissingAStatus`.

`LastKnownSuite`: `get` is `None` before any `put`; `put` overwrites; concurrent `put`s leave one
value (property, 1000 parallel writes).

`ClusterOverviewRoutesSuite` (Tapir stub interpreter): the body matches the golden document; the
route is reachable at `/api/v1/clusters`; no proxy route shadows it (assert the assembled route
list contains exactly one `/api/v1/clusters` `GET`).

## Observability

- `kui.aggregation.section{endpoint="clusters", level="outer"|"row", status}` — a counter. It is
  what answers "how often is the dashboard degraded" without log parsing, and CFGOP-007 asserts
  it moved.
- One WARN when the outer section becomes `Stale` or `Unavailable`, and one INFO when it
  recovers, both rate-limited to one per minute — a dead cluster service must not write a log
  line per page load.
- The span for this route has attributes `clusters.total` and `clusters.unavailable`.
- No log line and no metric label ever carries a bootstrap server or a property value.

## Degraded behaviour

This task *is* the degraded behaviour of the product's front page. Stated as a table, which the
suite mirrors row for row:

| Cluster service | Cache | This cluster | Response |
| --- | --- | --- | --- |
| answers | — | reachable | 200, outer `ok`, row `ok` |
| answers | — | unreachable | 200, outer `ok`, row `unavailable` + reason, row still identified |
| answers | — | scraping for the first time | 200, outer `ok`, row `unavailable` reason `STARTING` |
| down | present | — | 200, outer `stale` with `fetchedAt`, rows as last seen |
| down | empty | — | 200, outer `unavailable` + reason, `data` absent |
| circuit open | present | — | 200, outer `stale`, reason `CIRCUIT_OPEN` |

## Docs to update

`ARCHITECTURE.md` §6's list of aggregations: mark `GET /clusters` implemented and name
`ClusterOverviewUseCase`. `docs/api/openapi.json` is regenerated by CLAPI-010.

## Deviations

1. **`ClusterOverviewUseCase.resource` takes `CapabilitySignals` as well as the registry.** The spec's
   signature omitted it, but step 3 of its own algorithm requires reporting the failure to the
   signals - reading the registry is not enough to write to it.
2. **`services.gateway.application` gains `services.gateway.contract.jvm` and
   `services.cluster.contract.jvm`.** The use case calls the cluster service's published endpoint
   and answers with the gateway's own document; both are wire types, which is the exception ADR-041
   §1a already grants this module. `checkArchitecture` passes.
3. **The timing test asserts against `UpstreamServiceConfig.DefaultTimeout` with a stub that takes
   exactly that long and then fails.** A stub that never answers is not bounded by anything - the
   real bound lives in `SttpServiceClient` - so what is asserted is the property that is actually
   at risk: the aggregation adds no waiting of its own on top of the upstream's budget. The
   assertion reads the configured value rather than a literal, as D9 requires.
4. **The `kui.aggregation.section` counter, the rate-limited transition WARN and the span attributes
   are not implemented.** One WARN per failed fetch is emitted, unthrottled. **Owed**, and the same
   note as CLAPI-004's item 5 applies: CFGOP-007's E2E asserts the metric moved.
5. `GET /clusters/{id}/dashboard` is not built, as decision 2 says.
