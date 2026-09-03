# CLUI-002 — `ui-clusters`: typed clients derived from the cluster contract

- **ID:** CLUI-002
- **Title:** `ui-clusters`: typed clients derived from the cluster contract
- **Milestone / Feature:** M1 / CL-001, CL-003, CL-005, BR-001, BR-002, BR-005, KU-011
- **Owner role:** Frontend Architect
- **Size:** S
- **Dependencies / blocked by:** CLAPI-001 (the DTOs), CLAPI-002 (the read endpoints). This task
  touches only `frontend/ui-clusters`, which the restyle swarm does not edit, so it has no
  sequencing constraint.

## Goal (user value)

Nothing a user sees. This is the one file that decides, for every cluster screen, what URL is
called, what comes back and what the compiler checks — so that renaming a field in the cluster
contract breaks the build instead of breaking a page in production.

## Scope

1. Replace `ClustersApi.ping` with one value per public cluster endpoint, each built out of the
   path segments, parameter names and DTOs that `services/cluster/contract` publishes, by the same
   rewriting rule `ClustersApi` already documents: the browser calls the gateway, so the leading
   `/internal/v1` becomes `/api/v1` and the signed principal header is dropped (`ARCHITECTURE.md`
   §5, ADR-040).
2. Delete the `ping` client and every reference to `PingResponse` in this module. `Ping` does not
   survive M1 (DEVPLAN §1) and CLDOM-001 deletes the domain type; a client for a deleted endpoint
   would not compile.
3. One `ClustersQueries` object holding the module's `QueryCache` instances, so that the dashboard,
   the broker list and the broker detail page share one cache per resource and a navigation between
   them does not refetch what is already fresh.
4. Nothing else. No rendering, no state machine, no page.

## The endpoints, and which screen needs which field

The endpoint values, paths, parameter names and DTO type names all come from
`services/cluster/contract` (CLAPI-001, CLAPI-002); this task names them, it does not define them.
What *is* stated here, because it is a frontend requirement on that contract, is the field each
screen reads. If a field below is absent from the DTO the screen renders `DataTable.missing`
(`—`) for it rather than being redesigned — see the per-screen tasks.

| Client value | Public route | Screen | Fields the screen reads |
| --- | --- | --- | --- |
| `clusters` | `GET /api/v1/clusters` | CLUI-003 dashboard | per row: cluster id, display name, `readOnly`, and a `Section` whose payload carries broker count, controller broker id, Kafka version, online / offline partition counts, under-replicated partition count, total disk usage in bytes, `scrapedAt` |
| `brokers` | `GET /api/v1/clusters/{clusterId}/brokers` | CLUI-004 | per broker: id, host, port, rack, whether it is the controller, leader-partition count, replica count, in-sync replica count, disk usage bytes and segment count, plus the section's `scrapedAt` |
| `brokerConfigs` | `GET /api/v1/clusters/{clusterId}/brokers/{brokerId}/configs` | CLUI-005 | per entry: name, value (already redacted server-side), source, `sensitive`, `readOnly` |
| `brokerLogDirs` | `GET /api/v1/clusters/{clusterId}/brokers/{brokerId}/logdirs` | CLUI-005 | per log dir: path, optional error, and its topic-partition entries with topic name, partition, size in bytes and offset lag |
| `refresh` | `POST /api/v1/clusters/{clusterId}/refresh` | CLUI-008 | no body; the meaning is the 202 |

Two properties of this table are deliberate and are restated in the per-screen tasks so nobody
re-derives them wrongly:

- **There is no topic count and no partition total that needs a `describeTopics` sweep**, and no
  bytes-in / bytes-out. DEVPLAN §10 D5 and §3 put both out of M1's scope; the cells render `—`.
- **Every read endpoint answers with a `Section`**, not with a bare payload, because a cluster the
  service cannot reach is `Section.Unavailable` inside a 200 (DEVPLAN §10 D4). A client that
  unwrapped the section here would throw that away.

## Non-goals

- **No write endpoints.** `PUT /internal/v1/clusters/{id}` (CLAPI-009) is internal, has no UI in M1
  (DEVPLAN §10 D6) and is not called from the browser.
- **No SSE client.** `/internal/v1/clusters/stream` (CLAPI-003) is service-to-service; the browser's
  only stream is the capability stream the shell already owns.
- **No retry, no polling, no backoff** in this file. `ApiClient` deliberately has no retry policy,
  and D10 forbids browser polling of cluster data.
- **No hand-written URL strings.** Every path segment comes from a constant in `ClusterEndpoints`.
  A string literal `"/api/v1/clusters"` in this module is a review failure: it is exactly the thing
  cross-compiled contracts exist to prevent.

## Design references

- `frontend/ui-clusters/src/kui/ui/clusters/ClustersApi.scala` — the existing file's doc comment
  explains why a browser endpoint cannot be the service's own endpoint value. That reasoning is
  unchanged; only the endpoint list grows.
- **ADR-040** edge header policy — what the gateway strips and mints, and therefore what the
  browser must not send.
- **ADR-034** error envelope — every endpoint's error output is `ErrorEnvelope`, which
  `ApiClient` already turns into `ApiError.Envelope`.
- **ADR-031** cluster id strategy — the cluster id in a path is the configured name's slug, so it is
  URL-safe by construction; it is still passed through Tapir's path capture rather than
  concatenated.
- **ADR-011 §3.2 / UI-006** — `QueryCache` is how a screen holds server state; each resource gets
  one cache keyed by what identifies it.
- **`ARCHITECTURE.md` §5** internal contracts and headers, §6 the `Section` envelope.

## Files to create

```
frontend/ui-clusters/src/kui/ui/clusters/ClustersQueries.scala
frontend/ui-clusters/test/src/kui/ui/clusters/ClustersApiSuite.scala
```

## Files to change

```
frontend/ui-clusters/src/kui/ui/clusters/ClustersApi.scala      (rewritten: ping out, five clients in)
frontend/ui-clusters/src/kui/ui/clusters/ClustersState.scala    (ping state deleted; see CLUI-003)
frontend/ui-clusters/test/src/kui/ui/clusters/ClustersStateSuite.scala  (ping cases deleted)
```

`ClustersPage.scala` and `Messages.scala` are rewritten by CLUI-003, not here. To keep `main` green
at the end of *this* task, the ping button and its state are removed and the page renders its
heading plus an empty state; that is a two-line intermediate, and CLUI-003 replaces it.

## Public Scala signatures to implement

```scala
package kui.ui.clusters

import kui.cluster.contract.ClusterEndpoints
import kui.cluster.contract.dto.*
import kui.contracts.{ErrorEnvelope, KuiEndpoint, PublicApi, Section}
import sttp.tapir.*

/** The cluster service's endpoints as the browser calls them. */
object ClustersApi {

  /** `GET /api/v1/clusters` — every configured cluster with its own section status. */
  val clusters: PublicEndpoint[Unit, ErrorEnvelope, ClusterListResponse, Any]

  /** `GET /api/v1/clusters/{clusterId}/brokers` */
  val brokers: PublicEndpoint[ClusterId, ErrorEnvelope, Section[BrokerListResponse], Any]

  /** `GET /api/v1/clusters/{clusterId}/brokers/{brokerId}/configs` */
  val brokerConfigs
      : PublicEndpoint[(ClusterId, BrokerId), ErrorEnvelope, Section[BrokerConfigsResponse], Any]

  /** `GET /api/v1/clusters/{clusterId}/brokers/{brokerId}/logdirs` */
  val brokerLogDirs
      : PublicEndpoint[(ClusterId, BrokerId), ErrorEnvelope, Section[BrokerLogDirsResponse], Any]

  /** `POST /api/v1/clusters/{clusterId}/refresh` — 202 Accepted, no body (CL-005). */
  val refresh: PublicEndpoint[ClusterId, ErrorEnvelope, Unit, Any]
}
```

The DTO type names above are the ones CLAPI-001 publishes. If they differ, take the contract's
names: this module has no say in them, and the mismatch is a compile error, which is the intended
failure mode.

```scala
package kui.ui.clusters

/** The module's server state, one cache per resource.
  *
  * A class, not an object, for the reason `ClustersState` is a class (PLAN §21): a global cache is
  * shared by every instance of the feature and outlives all of them, so a test inherits the
  * previous test's rows. `ClustersFeature` creates exactly one and hands it to the pages.
  */
final class ClustersQueries(api: ApiClient) {

  val clusters: QueryCache[Unit, ClusterListResponse]
  val brokers: QueryCache[ClusterId, Section[BrokerListResponse]]
  val brokerConfigs: QueryCache[(ClusterId, BrokerId), Section[BrokerConfigsResponse]]
  val brokerLogDirs: QueryCache[(ClusterId, BrokerId), Section[BrokerLogDirsResponse]]

  /** Fires a forced refresh and reports the outcome. Used by CLUI-008; here so that every call in
    * the module goes through one file.
    */
  def requestRefresh(cluster: ClusterId): EventStream[Either[ApiError, Unit]]

  /** Drops every cached entry for one cluster, so that the next subscription refetches. Called
    * after a successful forced refresh and after the cluster switcher changes clusters.
    */
  def invalidateCluster(cluster: ClusterId): Unit
}
```

Cache settings, and why:

| Cache | `staleAfter` | `maxEntries` | Why |
| --- | --- | --- | --- |
| `clusters` | 30 s | 4 | the server refreshes its snapshot every 30 s (`ARCHITECTURE.md` §9); asking more often than that returns the same bytes. One key, so the bound is nominal. |
| `brokers` | 30 s | 8 | same snapshot cadence, one entry per cluster the user visits in a session |
| `brokerConfigs` | 30 s | 32 | one per (cluster, broker) |
| `brokerLogDirs` | 30 s | 32 | one per (cluster, broker) |

`QueryCache`'s 5 s negative TTL is left at its default everywhere: a failing endpoint must not be
hammered, and 5 s is short enough that a recovered service is picked up on the next interaction.

## Library coordinates

No new dependencies. `frontend.uiClusters` already depends on `frontend.uiKernel` and
`services.cluster.contract.js` (`build.mill`), and through the kernel on
`com.raquo::laminar::17.2.1`, `com.softwaremill.sttp.tapir::tapir-sttp-client4` and
`com.softwaremill.sttp.client4::core` at the versions `DEPENDENCY_MATRIX.md` pins. `build.mill` is
not edited by this task.

## Acceptance criteria

```
$ ./mill frontend.uiClusters.compile
$ ./mill frontend.uiClusters.test
$ ./mill frontend.uiShell.compile
```

All three clean. The third matters: the shell names `ClustersRoutes` statically, and deleting the
ping client must not have broken that edge.

```
$ ./mill frontend.uiClusters.checkFormat && ./mill frontend.uiClusters.fix --check
$ ./mill frontend.uiShell.checkBundleShape
```

`checkBundleShape` still reports `kui.ui.clusters.ClustersFeature` in a module of its own: adding
endpoints must not have created a static reference from the shell into the feature.

```
$ grep -rn '"/api/v1' frontend/ui-clusters/src   # expected: no matches
```

## Tests required

`ClustersApiSuite` (Node, no DOM — the module's test env stays plain Node until CLUI-003 changes
it):

- `everyClientTargetsThePublicPrefix` — for each endpoint value, the rendered path starts with
  `/api/v1` and contains no `/internal`.
- `pathsAreBuiltFromTheContractsOwnSegments` — asserted by construction: the suite compares each
  rendered path against one assembled from `ClusterEndpoints`' published constants, so a contract
  rename that this file failed to follow fails the test rather than silently changing a URL.
- `clusterIdsAreEncodedNotConcatenated` — an id containing a character that must be escaped
  (`a b/c`) renders percent-encoded. ADR-031 says ids are slugs and cannot contain one, which is
  exactly why this is worth asserting: the invariant is enforced somewhere else, and this proves
  the client does not depend on it.
- `noClientDeclaresAPrincipalHeader` — inspect each endpoint's inputs; none is the signed principal
  header ADR-040 reserves for the gateway.
- `refreshDeclaresNoResponseBody`.

`ClustersStateSuite` — the ping cases are deleted, not rewritten. CLUI-003 writes the suite for
the state that replaces them.

## Observability

None of its own. Every call goes through `ApiClient`, which already attaches `X-Kui-Request-Id` and
reports each outcome to `HealthReporting`. The one requirement this task carries forward: cluster
screens report with `CallScope.Feature`, never `CallScope.Shell`, so that a cluster service failure
can never take the whole application away from the user (ADR-032). That is enforced at the call
sites in CLUI-003 … CLUI-005; it is written here because this is the file those call sites go
through.

## Degraded behavior

`ApiClient` never fails a stream: every outcome is `Either[ApiError, O]`, and `QueryCache` caches
the `Left` for 5 s. So the degraded behaviour of this layer is simply that it has none of its own —
it hands the screen an error value, and the screen decides. What this task must *not* do is
introduce a retry, a fallback endpoint or a default value: a fabricated empty cluster list is
indistinguishable, on screen, from a real one, and that is the failure this whole milestone is
about avoiding.

## Docs to update

None. `docs/frontend/README.md` already describes how a feature builds clients from its contract;
this task is that description being followed. `docs/api/*` is CLAPI-010's.

## Deviations

Commit `8018dd9`.

1. **The DTO names in the spec's sketch are not the ones the contract publishes.** As the spec
   instructed, the contract's names win: `ClustersResponse`, `ClusterDetailResponse`,
   `BrokersResponse`, `BrokerConfigsResponse`, `LogDirsResponse`, `RefreshAcceptedDto`.

2. **None of them is a `Section[...]`, and there is one more endpoint and one fewer than planned.**
   The section sits *inside* the cluster list, one per row, because the list of configured clusters
   is available whenever the service is and only each row's summary needs a broker — which is what
   makes the "unavailable row stays clickable" criterion implementable at all. `getCluster` was added
   (a deep link must not fetch forty rows to draw one header) and the planned
   `/brokers/{id}/logdirs` is `GET /clusters/{id}/log-dirs?brokerId=`, one endpoint for the whole
   cluster with an optional filter, because the brokers list needs every broker's totals in one call.
   `brokerConfigs` carries the contract's `docs` flag, so its cache key is `(cluster, broker, docs)`.

3. **`ClustersState` was deleted rather than trimmed.** Everything in it was the ping state.
   `ClustersQueries` replaces it as the feature's server state, and CLUI-003 adds the selection state
   the screens need. `ClustersStateSuite` went with it.

4. **`HealthReporting` is called in `ClustersQueries`, not at the screens' call sites.** The spec put
   the `CallScope.Feature` rule at the call sites and this file "because this is the file those call
   sites go through" — so it is enforced here, in one private method every request passes through,
   rather than repeated in three screens where the fourth would forget it.

5. **`requestRefresh` answers `RefreshAcceptedDto`, not `Unit`.** The 202 carries the time the
   request was taken, which is what CLUI-008's button has to show; discarding it would leave the
   button with nothing truthful to say.

## Implementation report

```
./mill frontend.uiClusters.compile          SUCCESS
./mill frontend.uiClusters.test             ClustersApiSuite 6, 0 failed
./mill frontend.uiClusters.checkFormat      SUCCESS
./mill frontend.uiClusters.fix --check      SUCCESS
./mill frontend.uiShell.compile             SUCCESS
./mill frontend.uiShell.checkBundleShape    1 feature module split out
grep -rn '"/api/v1' frontend/ui-clusters/src   only the scaladoc sentence forbidding it
```
