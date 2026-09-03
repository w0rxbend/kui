# CLAPI-006 — Gateway: cluster routes and `X-Kui-Cluster-Id` validation

- **ID:** CLAPI-006
- **Title:** Gateway: cluster routes and `X-Kui-Cluster-Id` validation
- **Milestone / Feature:** M1 / CL-002, BR-001, BR-002, BR-005, OT-004
- **Owner role:** Chief Architect
- **Size:** M
- **Dependencies / blocked by:** CLAPI-002

## Goal (user value)

Every cluster and broker endpoint becomes reachable at `/api/v1/...` from the browser, with no
path written by hand in the gateway, and every cluster-scoped call carries the cluster it is
about — into the upstream's logs, spans and metrics, so a slow request can be attributed to a
cluster instead of to "the cluster service".

## Scope

1. Cluster-scoped routing: `ContractRouting` learns to extract the `ClusterId` from a request
   and to put it in `CallContext`, which is what makes `SttpServiceClient` emit
   `X-Kui-Cluster-Id` (the header it already knows how to send, `SttpServiceClient.ClusterHeader`).
2. Validation of that id at the edge: a syntactically invalid id is `400 KUI-VALIDATION` with
   the field named, before the upstream is called.
3. `ServiceContracts` gains the notion of an endpoint the gateway **aggregates** rather than
   proxies, so that CLAPI-007 can serve `/api/v1/clusters` itself without colliding with a
   derived route for the same path.
4. The RBAC pre-check is handed the real cluster (`RbacPreCheck.check(principal, endpoint,
   cluster)`), which M6 needs and which today is always `None` — a seam that is wrong until this
   task fills it.
5. The proxied-route test suite extended for the cluster endpoints.

## Non-goals

No aggregation (CLAPI-007). No capability changes (CLAPI-008). No Kafka anything: rule A8 and
A10 forbid a Kafka client on the gateway, and this task must not add one — the gateway's whole
knowledge of clusters is `services/cluster/contract` (A4). No membership check (decision 2).

## Design references

- `ARCHITECTURE.md` §5's header table: `X-Kui-Cluster-Id` is "the `ClusterId` from the path, for
  adapters that log/metric outside the route layer", sent "when cluster-scoped".
- ADR-040 — every inbound `X-Kui-*` header is stripped at the edge by prefix. The cluster id the
  gateway sends is therefore always one **it** derived from the path; an inbound one is
  discarded, and the existing `EdgeHeaders` already does that. Nothing in this task may read an
  inbound cluster header.
- ADR-031 (`ClusterId` is a slug of the configured name), ADR-004 (the gateway holds no domain
  logic), ADR-039 §6 (only transport failures of the upstream *service* feed the registry).
- The files this task changes:
  `services/gateway/api/src/kui/gateway/api/routing/ContractRouting.scala` (its `proxy` currently
  pins `cluster = none[ClusterId]`, with a `Async[F].pure(none[ClusterId])` line that exists to
  be replaced) and `.../routing/ServiceContracts.scala`.

## Files to create

```
services/gateway/api/src/kui/gateway/api/routing/ClusterScope.scala
services/gateway/api/test/src/kui/gateway/api/routing/ClusterScopeSuite.scala
services/gateway/api/test/src/kui/gateway/api/routing/ClusterRoutingSuite.scala
```

## Files to change

```
services/gateway/api/src/kui/gateway/api/routing/ContractRouting.scala
services/gateway/api/src/kui/gateway/api/routing/ServiceContracts.scala
services/gateway/api/test/src/kui/gateway/api/routing/ContractRoutingSuite.scala
```

## Public Scala signatures to implement

```scala
package kui.gateway.api.routing

/** Which cluster a request is about, decided from its path and nothing else.
  *
  * A pure function over the path segments, so every case is unit-testable without HTTP and
  * without a stub upstream — the same shape `CsrfCheck.verdict` uses, and for the same reason:
  * the table of cases *is* the specification.
  */
object ClusterScope {

  /** The path segment that introduces a cluster id, matching `ClusterEndpoints.ClustersSegment`
    * rather than repeating the literal.
    */
  val Segment: String = kui.cluster.contract.ClusterEndpoints.ClustersSegment

  enum Scope:
    case None                                   // not a cluster-scoped path
    case Cluster(id: ClusterId)                 // /api/v1/clusters/{id}/...
    case Malformed(raw: String, error: ValidationError)

  /** `segments` are the request's decoded path segments, base path already removed. The id is
    * the segment immediately after the first `clusters` segment, and only when one follows:
    * `/api/v1/clusters` itself is `None`, because it is about every cluster.
    */
  def of(segments: List[String]): Scope
}
```

`ContractRouting` changes:

```scala
/** Now returns the scope as well, and fails the security logic on a malformed id. */
private def callerOf[F[_]: Async](request: ServerRequest): F[Either[Failure, (Principal, CorrelationId, Option[ClusterId])]]
```

The malformed case becomes `ApplicationError.Invalid("cluster id", List(FieldError(Some("clusterId"),
List(<the expectation string from ClusterId.from>))))`, rendered by the existing envelope
machinery — no new error code, and `ErrorEnvelope.statusOf` decides the status (400).

`ServiceContracts` changes:

```scala
object ServiceContracts {

  val byService: Map[ServiceId, List[AnyEndpoint]] =
    Map(ServiceId.unsafe("cluster") -> ClusterEndpoints.all)

  /** Endpoints the gateway serves **itself**, as an aggregation, and therefore must not derive a
    * proxy route for. Keyed by the endpoint `name` the contract fixes, because a name is stable
    * and a path is a string the derivation is already deriving.
    *
    * Exactly one entry in M1: `cluster.listClusters`, which CLAPI-007 replaces with a response
    * carrying per-row status and the gateway's last-known fallback. Two routes for one path is a
    * collision nobody sees in a route list, so the exclusion is data rather than an accident of
    * ordering.
    */
  val aggregated: Set[String] = Set("cluster.listClusters")

  def proxied(service: ServiceId): List[AnyEndpoint] =
    of(service).filterNot(_.info.name.exists(aggregated.contains))
}
```

`GatewayWiring`'s proxy-route builder calls `ServiceContracts.proxied` instead of `of`.

## Decisions this task takes (no ADR covers them)

1. **The cluster id is read from the request path, not from the decoded endpoint input.**
   `ContractRouting` works over `AnyEndpoint`s whose input types Tapir has erased; recovering a
   typed path capture would need a third cast on top of the two `Unsafe` already justifies. The
   path is right there on the `ServerRequest`, the rule ("the segment after `clusters`") is one
   line, and it is identical for every cluster-scoped endpoint in every service M2–M8 will add.
2. **The gateway validates the id's *syntax* and never its *membership*.** An unknown but
   well-formed id is forwarded and answered `404 KUI-CLUSTER-NOT-FOUND` by the cluster service.
   The gateway holding a list of clusters would be domain state in the module ADR-004 defines as
   holding none, it would be stale exactly when it matters (a cluster added a second ago), and
   it would give two different 404s for the same question depending on which copy was fresher.
3. **A malformed id fails before the upstream call, like an RBAC denial.** The check sits in the
   security logic, which runs before the endpoint's own input decoding completes and before
   `client.call`. A request that cannot be about any cluster must not cost the cluster service a
   connection.
4. **`/api/v1/clusters` (no id) is not cluster-scoped and sends no header.** It is about all of
   them. Sending an empty or arbitrary cluster header there would put a meaningless label on
   every metric the aggregation produces.

## Library coordinates

None new.

## Acceptance criteria

```
$ ./mill services.gateway.api.test.testOnly 'kui.gateway.api.routing.*'
$ ./mill __.compile
$ ./mill checkArchitecture
```

Against a running all-in-one:

```
$ curl -s localhost:8080/api/v1/clusters/local/brokers | jq -c '.brokers.status'
"ok"
$ curl -si 'localhost:8080/api/v1/clusters/NOT%20A%20SLUG/brokers' | head -1
HTTP/1.1 400 Bad Request
$ curl -s 'localhost:8080/api/v1/clusters/NOT%20A%20SLUG/brokers' | jq -c '{code,details}'
{"code":"KUI-VALIDATION","details":[{"field":"clusterId","restrictions":["a lowercase slug of 1 to 64 letters, digits and dashes, starting and ending with a letter or a digit"]}]}
$ curl -s localhost:8080/api/v1/clusters/does-not-exist/brokers | jq -r .code
KUI-CLUSTER-NOT-FOUND
```

## Tests required

`ClusterScopeSuite` (pure, exhaustive table — this table is the specification):

| Path | Expected |
| --- | --- |
| `/api/v1/clusters` | `None` |
| `/api/v1/clusters/prod-eu` | `Cluster("prod-eu")` |
| `/api/v1/clusters/prod-eu/brokers/1/configs` | `Cluster("prod-eu")` |
| `/api/v1/clusters/NOT A SLUG/brokers` | `Malformed` |
| `/api/v1/capabilities` | `None` |
| `/api/v1/topics/clusters/x` | `None` (the first `clusters` is not the scope segment here — assert the rule is "the *first* `clusters` segment, and only when the path starts with the API prefix") |
| `/api/v1/clusters/` (trailing slash, empty segment) | `Malformed` |

`ClusterRoutingSuite` (MUnit + stub upstream):

- `everyClusterEndpointIsReachableAtApiV1` — one case per endpoint in `ClusterEndpoints.all`,
  driven from the endpoint list so a seventh endpoint is covered automatically.
- `theUpstreamCallCarriesTheClusterHeader` — the stub asserts `X-Kui-Cluster-Id: prod-eu`.
- `theListEndpointSendsNoClusterHeader`.
- `anInboundClusterHeaderIsIgnored` — send `X-Kui-Cluster-Id: attacker` on a path scoped to
  `prod-eu`; the upstream receives `prod-eu`. ADR-040's promise, asserted rather than assumed.
- `aMalformedIdIsFourHundredAndTheUpstreamIsNeverCalled` — the stub records zero calls.
- `theRbacPreCheckReceivesTheCluster` — a recording `RbacPreCheck` asserts `Some(prod-eu)`; with
  `denyAll` the upstream is never called.
- `theAggregatedEndpointHasNoDerivedRoute` — `ServiceContracts.proxied` excludes
  `cluster.listClusters`, and deriving routes from the proxied list produces no
  `/api/v1/clusters` route. Without this, CLAPI-007 would silently shadow or be shadowed.

## Observability

`kui.upstream.duration` (OBS-002's existing metric) gains a `cluster` attribute when the scope
is a cluster, and does not when it is not — an absent attribute, never an empty string, because
an empty label value is a cardinality bucket that means nothing. The access log line gains
`cluster.id` on the same condition. No new metric.

## Degraded behaviour

Unchanged from M0's proxy path: an unreachable cluster service produces the mapped
`InfrastructureError`, reported to the capability registry by
`ContractRouting.reportIfInfrastructure` (which must keep reporting on the **service** key —
see CLAPI-008 for the per-cluster keys, and D4 for why a dead *cluster* must never dim the
service capability).

## Docs to update

None. `docs/api/openapi.json` is regenerated once, by CLAPI-010.

## Deviations

1. **`ServiceContracts.aggregated` is keyed on `cluster.list`, not `cluster.listClusters`.** The
   endpoint's name is what CLAPI-002 fixed, and the name is the key.
2. **`GatewayWiring` switches to `ServiceContracts.proxied` in CLAPI-007's commit, not this one.**
   Switching here would have removed `/api/v1/clusters` from the served routes for the length of one
   commit, with nothing serving it - and the all-in-one suite asserts that path exists. The
   exclusion and the aggregation that replaces it land together.
3. **`ClusterScope.of` finds the public prefix wherever it is, rather than requiring it at the
   start.** A deployment served under a base path - `/kui` in the Compose stack - carries that base
   path in the request's segments while no endpoint definition knows about it. One rule for both
   shapes; a test covers the based path.
4. **`/api/v1/clusters/` with a trailing slash is `None`, not `Malformed`.** The spec's table asked
   for `Malformed`; every path parser between the browser and here drops the empty segment, so
   treating it as a malformed id would answer 400 to a valid request for the list.
5. `callerOf` returns the failure itself rather than raising, which is what lets the malformed-id
   case be a 400 rendered by the same envelope machinery as everything else, with no new error code.
