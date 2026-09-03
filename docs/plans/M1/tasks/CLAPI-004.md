# CLAPI-004 — Cluster `api`: server logic, error envelope, `Section` staleness

- **ID:** CLAPI-004
- **Title:** Cluster `api`: server logic, error envelope, `Section` staleness
- **Milestone / Feature:** M1 / CL-001, CL-002, CL-003, CL-005, BR-001, BR-002, BR-005
- **Owner role:** Chief Architect
- **Size:** L
- **Dependencies / blocked by:** CLAPI-002, CLAPI-003, CLDOM-006

## M1 gate review amendment — `Ping` is deleted here, and only here

**F-01, blocker, fixed.** CLDOM-001 and CLAPI-002 each deferred the `Ping` deletion to the other,
so nothing deleted it. This task now owns the whole deletion, in one commit, and DEVPLAN §6.5
grants it a stated exception to the CLAPI area boundary for that purpose: it may delete files
under `services/cluster/domain` and `services/cluster/application`, **for deletion only**.

Delete, in this task's commit:

```
services/cluster/domain/src/kui/cluster/domain/Ping.scala
services/cluster/domain/test/src/kui/cluster/domain/PingSuite.scala
services/cluster/application/src/kui/cluster/application/PingUseCase.scala
services/cluster/application/test/src/kui/cluster/application/PingUseCaseSuite.scala
services/cluster/contract/src/kui/cluster/contract/dto/PingDtos.scala
services/cluster/contract/test/src/kui/cluster/contract/PingDtosSuite.scala
services/cluster/contract/test/resources/golden/ping-response.json
services/cluster/api/src/kui/cluster/api/PingMapping.scala
services/cluster/api/test/src/kui/cluster/api/PingMappingSuite.scala
```

plus the `ping` entry in `ClusterEndpoints.all`, the `PingUseCase` line in `ClusterWiring`, and
the `Ping` paragraph in `docs/domain/cluster.md`. Regenerate the committed OpenAPI document in the
same commit. `grep -ri ping services/ frontend/ docs/` must come back empty except for this
sentence's neighbours in the M1 plan; that grep is an acceptance criterion of this task.

(The exact file list is the one CLDOM-001 verified against the tree; if a path has moved, follow
the tree, not this list.)

## Goal (user value)

The cluster service answers for real. Every endpoint declared in the contract is bound to a use
case, every failure becomes the one error envelope with the one status, and a cluster whose data
is old or missing produces a 200 with an honest section rather than a 500 that takes the page
down.

## Scope

1. Routes for the six read endpoints (CLAPI-002) and the two profile endpoints (CLAPI-003),
   bound to the use cases of `services/cluster/application` (CLDOM-005, CLDOM-006, CLDOM-004).
2. `ClusterMapping` — application types to wire DTOs, with Chimney (ADR-033), including the
   skew computation and the redaction of the profile.
3. `SectionMapping` — the snapshot's `status` and `scrapedAt` (ADR-027) to `Section.Ok` /
   `Section.Stale` / `Section.Unavailable`, and a `KuiError` to a section via the existing
   `Section.fromEither`.
4. The SSE route behind `ClusterStreamEndpoint`, over the registry's change stream.
5. `PingMapping`, `ClusterApi.pingRoute` and every remaining reference to `Ping` deleted.
6. `CapabilityMapping` extended to carry per-cluster `features` and the `degraded` status that
   M0 could not produce (CLDOM-007 supplies the reason).

## Non-goals

No writes (CLAPI-009). No wiring, no `Resource`, no `IO` (CLAPI-005 — this module starts
nothing; ADR-010). No gateway (CLAPI-006/007). No broker config edit. No Kafka client: rule A10
forbids `libs/kafka` on this module's classpath, and the admin port is reached only through the
application layer's ports.

## Design references

- ADR-034 and `ErrorEnvelope.statusOf` — **the single error-code-to-HTTP-status mapping in the
  system**. This module must not contain a second `match` on error codes producing statuses.
- ADR-027 (`status: Initializing | Online | Offline(lastError)`, `scrapedAt`, atomic
  replacement) and `ARCHITECTURE.md` §9's cluster row ("reads ≤ 30 s old; … store unreachable
  means last known state plus `Degraded`").
- ADR-033 (mapping lives in `api`), ADR-041 (this is the only layer that may see both an
  application type and a wire type), ADR-039 §6 (which failures are infrastructure).
- `research/kafka/admin-capabilities.md` §1 — per-key failures: `describeConfigs` on a managed
  service and `describeLogDirs` on an unauthorised cluster return *partial* results, which is
  what `BatchResult` carries and what a section must not turn into a 500.
- The M0 template this file extends: `services/cluster/api/src/kui/cluster/api/ClusterApi.scala`
  (`requestContext`, `failure`, `PrincipalVerification.secured`, the interceptor order).

## Files to create

```
services/cluster/api/src/kui/cluster/api/ClusterMapping.scala
services/cluster/api/src/kui/cluster/api/SectionMapping.scala
services/cluster/api/src/kui/cluster/api/ClusterRoutes.scala
services/cluster/api/src/kui/cluster/api/ProfileRoutes.scala
services/cluster/api/test/src/kui/cluster/api/ClusterRoutesSuite.scala
services/cluster/api/test/src/kui/cluster/api/ProfileRoutesSuite.scala
services/cluster/api/test/src/kui/cluster/api/SectionMappingSuite.scala
services/cluster/api/test/src/kui/cluster/api/ClusterMappingSuite.scala
services/cluster/api/test/resources/golden/clusters-response.json
```

## Files to change

```
services/cluster/api/src/kui/cluster/api/ClusterApi.scala        (routes, documented, ping removed)
services/cluster/api/src/kui/cluster/api/CapabilityMapping.scala (features, degraded)
services/cluster/api/src/kui/cluster/api/PingMapping.scala       (deleted)
services/cluster/api/test/src/kui/cluster/api/...                (ping suites deleted)
services/cluster/api/openapi.json                                (regenerated: ./mill services.cluster.api.openApi)
```

## Public Scala signatures to implement

```scala
package kui.cluster.api

object ClusterRoutes {

  /** Every cluster route, in match order. Takes use cases, never adapters (A9). */
  def apply[F[_]: Async](
      registry: ClusterRegistryUseCase[F],      // CLDOM-004
      topology: TopologySnapshotUseCase[F],     // CLDOM-005
      brokers: BrokerDetailUseCase[F],          // CLDOM-006
      principals: PrincipalCodec[F],
      rejections: Counter[F, Long],
      logger: StructuredLogger[F]
  ): List[ServerEndpoint[Any, F]]
}

object ProfileRoutes {
  def apply[F[_]: Async](
      registry: ClusterRegistryUseCase[F],
      principals: PrincipalCodec[F],
      rejections: Counter[F, Long],
      telemetry: Telemetry[F],
      logger: StructuredLogger[F],
      sse: SseConfig = SseConfig.default
  ): List[ServerEndpoint[Fs2Streams[F], F]]
}

object SectionMapping {

  /** A snapshot becomes a section. This is the whole staleness contract, in one function.
    *
    *   - `Online`   with data           -> `Section.Ok(data, scrapedAt)`
    *   - `Offline`  with previous data  -> `Section.Stale(data, scrapedAt, reasonOf(lastError))`
    *   - `Offline`  with no data        -> `Section.Unavailable(reason, message, Some(since))`
    *   - `Initializing` with no data    -> `Section.Unavailable(Starting, "...", Some(since))`
    *   - `Initializing` with data       -> `Section.Stale(...)`   (a forced refresh in flight)
    *
    * Age is deliberately **not** an input: a snapshot older than the refresh interval is not
    * stale, it is a snapshot from a service whose refresh loop is running normally. Only a
    * failing upstream makes data stale (ADR-027), and "how old" is `scrapedAt`, which the UI
    * shows so a user can judge for themselves (D10).
    */
  def of[A, B](snapshot: Snapshot[A], at: Instant)(render: A => B): Section[B]

  def reasonOf(error: KuiError): ReasonCode
}

object ClusterMapping {
  def row(profile: ClusterProfile, snapshot: Snapshot[ClusterTopology], at: Instant): ClusterRowDto
  def summary(topology: ClusterTopology): ClusterSummaryDto
  def broker(broker: Broker, topology: ClusterTopology, logDirs: Option[BrokerDiskUsage]): BrokerDto
  def configEntry(entry: BrokerConfigEntry): BrokerConfigEntryDto
  def logDir(dir: LogDir): LogDirDto
  def security(security: ClusterSecurity): ClusterSecurityDto
  def profile(profile: ClusterProfile, updatedAt: Instant): ClusterProfileDto

  /** Divergence from the mean, as a percentage, rounded to two decimals.
    *
    *   skew(v, mean) = if mean <= 0 then None else Some((v - mean) / mean * 100)
    *
    * Kafbat computes this in the browser; computing it here means one implementation and one
    * rounding rule for the table, the CSV export and any later alert
    * (`research/kafbat/ui-analysis.md` "Brokers list").
    */
  def skewPercent(value: Int, mean: Double): Option[Double]
}
```

**The domain type names above (`ClusterProfile`, `ClusterTopology`, `Broker`,
`BrokerConfigEntry`, `LogDir`, `Snapshot`, `BrokerDiskUsage`) are CLDOM-001/002/005's to fix.**
If a name differs when this task starts, the DTOs stay exactly as CLAPI-001 specified and this
mapping absorbs the difference: the wire shape is this area's decision, the domain shape is
CLDOM's, and the mapper is the seam that exists precisely so neither has to move.

**Error handling is not open to interpretation.** Reuse `ClusterApi.failure` and
`ErrorEnvelope.statusOf` exactly as the M0 ping route does. Do not write a status table.

## Decisions this task takes (no ADR covers them)

1. **A failure inside a section never fails the response; a failure to identify the cluster
   does.** `GET /clusters/{id}/brokers` on an unknown id is `404 KUI-CLUSTER-NOT-FOUND`, because
   the request names something that does not exist. On a *known* cluster that cannot be reached
   it is `200` with `brokers: {status: "unavailable", reason: "UPSTREAM_UNAVAILABLE", …}`,
   because the request named something real and the answer is "not right now". This is the same
   distinction ADR-039 §6 draws for capabilities, applied to a response body.
2. **`Section.Stale` requires data, and data alone is not enough — the reason must be
   present.** A section is never `Stale` without a `lastError` to name; a snapshot that is
   simply older than the interval is `Ok`. Otherwise every response in a healthy KUI would be
   marked stale within thirty seconds and the marking would stop meaning anything.
3. **The refresh route returns 202 without waiting.** It calls the use case's
   `forceRefresh(clusterId)`, which is idempotent under concurrency (CLDOM-005), and answers
   immediately. Waiting would make the button's latency the cluster's latency, which is the
   thing D10 removed from the page.
4. **`Initializing` maps to `Unavailable(ReasonCode.Starting)`, not to a 503.** A service that
   is up but has not finished its first scrape must answer, so the shell can render rows with a
   "starting" pill instead of a full-page error. The readiness endpoint is where "not ready" is
   expressed (CLAPI-005), and readiness is what the gateway polls.

## Library coordinates

None new. `services.cluster.api` already has tapir-server, tapir-cats-effect, chimney 2.0.0-RC1,
log4cats-core and otel4s-core (`build.mill`, `DEPENDENCY_MATRIX.md`).

## Acceptance criteria

```
$ ./mill services.cluster.api.test
$ ./mill services.cluster.api.openApi        # regenerates services/cluster/api/openapi.json
$ ./mill services.cluster.api.openApiCheck   # clean
$ ./mill __.compile                          # -Werror clean; no reference to Ping survives
$ ./mill checkArchitecture
$ grep -ri 'ping' services/cluster/ --include=*.scala | wc -l
0
```

## Tests required

`ClusterRoutesSuite` (MUnit + the Tapir stub interpreter, no socket, fake ports from
`libs/testkit` and CLDOM's fakes):

- One success case per endpoint, asserting the exact JSON body against a golden document.
- `anUnknownClusterIdIsFourOhFourWithClusterNotFound`.
- `aMalformedClusterIdIsFourHundredWithTheFieldNamed`.
- `anUnreachableClusterIsTwoHundredWithAnUnavailableSection` — the milestone's central promise
  at the service layer.
- `aFailingLogDirCallProducesAnUnavailableSectionAndTheBrokerListStillAnswers` — the partial
  case from `admin-capabilities.md` §1.
- `perKeyErrorsSurviveAsSkippedEntriesRatherThanDisappearing` — a `BatchResult` with one
  skipped broker renders the other brokers and does not silently shrink the list.
- `refreshAnswersTwoHundredAndTwoWithoutWaiting` — with `TestControl`, assert the response lands
  at virtual time 0 while the fake refresh takes 10 s.
- `noSecretAppearsInAnyResponseBody` — R-12's contract-test assertion: drive **every** endpoint
  with a profile whose every secret is `kui-secret-canary` and assert the token appears in no
  body. This test is the exit criterion "secret fields … unreadable"; it must iterate the
  endpoint list, so a seventh endpoint added later is covered automatically.
- `everyErrorResponseIsAnErrorEnvelopeWithTheStatusFromStatusOf` — property over a generated
  `KuiError`.

`SectionMappingSuite` (unit, exhaustive table): the five rows of the mapping above, plus
`aStaleSectionAlwaysCarriesAReason` and `ageAloneNeverProducesStale`.

`ClusterMappingSuite` (unit + property): `skewPercent` against hand-computed values, `None` for
a zero mean, rounding to two decimals; `security` never carries a credential (property over
generated profiles containing the canary token).

`ProfileRoutesSuite`: 200 with `ETag`; 304 for a matching `If-None-Match`; 200 for `*`; the
stream emits a `clusters` event when the registry publishes a change, and a heartbeat on an idle
connection (`TestControl`).

## Observability

- `kui.kafka.admin.duration{cluster, operation, outcome}` is emitted by the **adapter**
  (CLADP-002), not here; this module must not double-count it.
- `kui.section.status{endpoint, status}` — a counter incremented once per section rendered. It
  is what makes "how often does the dashboard degrade" answerable without parsing logs, and it
  is the metric CFGOP-007's E2E asserts moved.
- One WARN per transition into an unavailable section, rate-limited to one line per cluster per
  minute; never one per request, or a dead cluster fills the log at request rate.
- Every log line carries `cluster.id`; never a bootstrap string, never a property value.

## Degraded behaviour

Stated per surface in decisions 1–4. In addition: if the *registry* itself cannot be read (the
store never replayed, which CLAPI-005 makes impossible at startup but not forever), the list
endpoint answers with the statically configured clusters and every summary
`Unavailable(NotConfigured)`. The service never answers 500 for a cluster-shaped question.

## Docs to update

`services/cluster/api/openapi.json` (regenerated, committed in the same commit).
