# HTTP-002 — `libs/http`: health, readiness and capabilities endpoints

- **ID:** HTTP-002
- **Title:** `libs/http`: health, readiness and capabilities endpoints
- **Milestone / Feature:** M0 / KU-006
- **Owner role:** Principal Scala Engineer
- **Context / service:** `libs/http`
- **Size:** M
- **Dependencies / blocked by:** HTTP-001, KERN-005

## Goal (user value)

Kubernetes, Docker Compose and the gateway's capability registry all learn the health of a
service through the same three endpoints, implemented once, so no service can get them subtly
wrong.

## Scope

1. Endpoint definitions in `libs/http` (they are infrastructure, not domain, so they live here
   rather than in each service's `contract`): `GET /health/live`, `GET /health/ready`,
   `GET /capabilities`.
2. `HealthEndpoints.make(checks, capabilities)` producing the `ServerEndpoint`s from two
   supplied functions, so a service provides its own readiness checks and capability report
   without reimplementing the wire format.
3. Semantics, decided here and binding on every service:
   - `/health/live` — the process is running and its runtime is not wedged. It returns 200
     unless the process should be restarted. It **never** depends on an upstream.
   - `/health/ready` — the service can serve requests now: configuration loaded, background
     schedulers started, mandatory upstreams reachable at least once. Returns 503 with a
     `ReadinessReport` body listing each failing check when not ready.
   - `/capabilities` — the `ServiceCapabilities` DTO from KERN-005, per cluster.
4. These three endpoints are unauthenticated and exempt from the principal requirement
   (`ARCHITECTURE.md` §13: "Health endpoints are unauthenticated and allow-listed").

## Non-goals

No gateway-side registry (GW-003). No probing of upstreams (each service decides its own
checks; M0's sample service has one trivial check).

## Design references

`ARCHITECTURE.md` §6 (the `/capabilities` example) and §13, ADR-004 §4, PLAN §16.1,
feature matrix KU-006.

## Files to create

```
libs/http/src/kui/http/health/HealthEndpoints.scala
libs/http/src/kui/http/health/ReadinessCheck.scala
libs/http/test/src/kui/http/health/HealthEndpointsSuite.scala
libs/contracts-core/src/kui/contracts/health/HealthDtos.scala
libs/contracts-core/test/resources/golden/readiness-report-degraded.json
```

## Public Scala signatures to implement

```scala
package kui.contracts.health

final case class CheckResult(name: String, healthy: Boolean, detail: Option[String])
final case class ReadinessReport(ready: Boolean, checks: List[CheckResult], at: Instant)
final case class LivenessReport(alive: Boolean, at: Instant)
```

```scala
package kui.http.health

final case class ReadinessCheck[F[_]](name: String, run: F[CheckResult]):
  /** A check that fails must never hang: `timeout` is mandatory and defaults to 2 seconds. */
  def withTimeout(d: FiniteDuration): ReadinessCheck[F]

object HealthEndpoints:
  def make[F[_]: Temporal](
      checks: List[ReadinessCheck[F]],
      capabilities: F[ServiceCapabilities]
  ): List[ServerEndpoint[Any, F]]
```

Readiness runs all checks in parallel with a total budget of 3 seconds; a check that exceeds
its own timeout is reported `healthy = false` with `detail = "timeout"` rather than failing
the endpoint.

## Library coordinates

None new.

## Acceptance criteria

```
$ ./mill libs.http.test
$ curl -s localhost:8080/health/live      # {"alive":true,"at":"..."}                200
$ curl -s localhost:8080/health/ready     # {"ready":true,"checks":[...],"at":"..."}  200
$ curl -s localhost:8080/capabilities     # matches golden/service-capabilities.json  200
```

With one check failing, `/health/ready` returns 503 and its body names the failing check;
`/health/live` still returns 200.

## Tests required

- `HealthEndpointsSuite` (unit + integration on a bound port):
  - `liveIsTwoHundredEvenWhenEveryReadinessCheckFails` — the distinction that keeps a
    degraded service from being restart-looped.
  - `readyIsFiveOhThreeWhenAnyCheckFails` and the body lists exactly the failing checks.
  - `checksRunInParallelAndRespectTheTotalBudget` — with three 1-second checks the endpoint
    answers in about 1 second, not 3 (asserted with `TestControl`).
  - `aHangingCheckIsReportedAsTimeoutNotAsAFailedRequest`.
  - `capabilitiesMatchesTheGoldenDocument`.
  - `healthEndpointsRequireNoPrincipalHeader` — even when the service is configured with a
    principal codec.

## Observability

Health endpoints are excluded from `kui.http.server.duration` (they would dominate the
histogram) and from request logging; readiness transitions (ready → not ready and back) are
logged once at INFO with the failing check names.

## Degraded behavior

This task *defines* the degraded signal every other component consumes. A service whose
readiness flips must not flap: a check that fails once is reported immediately, but the
gateway's registry (GW-004) applies the debounce, not the service.

## Docs to update

`docs/operations/observability.md`: a health-endpoint section for operators (what to probe,
what a 503 means, why liveness never depends on upstreams).

## Deviations

1. **`ReadinessCheck` is a case class with `timeout` as a field, not a `withTimeout` that adds one.**
   The spec's shape implies a check can exist without a timeout and acquire one later; making it a
   constructor parameter with a two-second default means a check without a bound is not
   representable. `withTimeout` still exists, as the copy method.

2. **`HealthEndpoints.make` needs `Parallel[F]` as well as `Temporal[F]`.** The checks run at once,
   which is the whole reason three one-second checks answer in one second, and `parTraverse` needs
   it.

3. **`HealthEndpoints.report` and `statusOf` are public.** `report` is the part with the timing
   behaviour, and testing it through a bound port would mean sleeping for real; exposed, it is
   asserted under `TestControl` with deterministic virtual time and no sleeping at all.

4. **`ReadinessCheck.bounded` handles a raised error as well as a timeout.** A check that throws
   would otherwise fail the whole endpoint, turning "one upstream is down" into "the probe is
   broken" — which is worse, because a broken probe is indistinguishable from a broken service.

5. **`HealthEndpoints.paths` was added**, so a server can exclude the three from request metrics and
   logging without spelling the paths again. `KuiInterceptors.UnmeasuredRoutes` in
   `libs/observability` holds the two health paths for the same reason; they are separate because
   `libs/observability` cannot see `libs/http`.

6. **The capability fake is `FakeCapabilities` in `libs/testkit`** (the fake this task was expected
   to add). It also counts how many times it was asked, which is what lets a suite assert that the
   endpoint recomputes rather than caches — the property the gateway's polling depends on.

7. **The golden document is asserted in two places for two different things.**
   `libs/contracts-core` owns `readiness-report-degraded.json` and asserts the *encoder* against it
   on both platforms; `HealthEndpointsSuite` asserts that a real endpoint on a real port serves
   exactly the committed capabilities document. The second needed the JSON inline, because a test
   constant in another module's test sources is not on this module's classpath.

8. **A test asserts none of the three declares a security input**, which is the mechanical form of
   "health endpoints are unauthenticated and allow-listed" (`ARCHITECTURE.md` §13): no later
   authentication wiring can start demanding a principal on an endpoint that has nowhere to put one.
