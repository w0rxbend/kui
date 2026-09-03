# HTTP-003 — `libs/http`: `UpstreamClient` resilience factory

- **ID:** HTTP-003
- **Title:** `libs/http`: `UpstreamClient` resilience factory
- **Milestone / Feature:** M0 / KU-001 (feeds the registry), OT-005
- **Owner role:** Principal Scala Engineer
- **Context / service:** `libs/http`
- **Size:** L
- **Dependencies / blocked by:** HTTP-001, KERN-002

## Goal (user value)

One slow or dead upstream can never take down a KUI process, starve another upstream's
requests, or hide the fact that it is failing. This is the mechanical half of the product's
central promise (PLAN §2.1).

## Scope

`UpstreamClient.make(config)` returning an sttp 4 backend wrapper with, in this order per call:

1. **URL policy** — the request URL must be a `SafeUrl` (CFG-001); redirects are followed only
   to the same host; non-http(s) schemes are refused.
2. **Bulkhead** — a `Semaphore` bounded by `maxConcurrent`; when full, the call fails fast with
   `InfrastructureError.Timeout(op, 0)` rather than queueing unboundedly.
3. **Circuit breaker** — closed → open after `failureThreshold` consecutive failures; open for
   `resetTimeout`; half-open admits one probe; a success closes it. While open, calls fail
   immediately with `InfrastructureError.CircuitOpen`.
4. **Failover** — an ordered URL list; a connection-level failure marks that URL failed for
   `graceperiod` and the next is tried; when all are failed, `InfrastructureError.Unreachable`.
5. **Retry** — only for idempotent methods (GET, HEAD, OPTIONS) or explicitly retryable
   statuses supplied by the caller (Connect's 409 rebalance in M7); at most `maxRetries` with
   full-jitter backoff; never for a request that has already streamed a response body.
6. **Timeout** — per call, from config.
7. **Instrumentation** — `UpstreamInstrumentation.wrap` from OBS-002.

Plus `circuitStates: Stream[F, CircuitEvent]`, the feed GW-004 subscribes to.

## Non-goals

No OAuth2 token cache, no TLS truststore/keystore configuration, no vendor media types — those
are ADR-037 features needed by the M7 upstreams (Schema Registry, Connect, ksqlDB) and are
added by the milestone that needs them. **Leave named extension points, do not implement them.**
No caching. No SSE (HTTP-004).

## Design references

ADR-037 (the whole decision), `ARCHITECTURE.md` §5 (per-service timeout, bulkhead, breaker) and
§14 (outbound URL policy), PLAN §16.4, `research/kafbat/architecture.md` F13 as the reference
behaviour being improved on.

## Files to create

```
libs/http/src/kui/http/upstream/UpstreamClient.scala
libs/http/src/kui/http/upstream/CircuitBreaker.scala
libs/http/src/kui/http/upstream/Failover.scala
libs/http/src/kui/http/upstream/Bulkhead.scala
libs/http/src/kui/http/upstream/RetryPolicy.scala
libs/http/test/src/kui/http/upstream/CircuitBreakerSuite.scala
libs/http/test/src/kui/http/upstream/FailoverSuite.scala
libs/http/test/src/kui/http/upstream/UpstreamClientSuite.scala
libs/http/test/src/kui/http/upstream/UrlPolicySuite.scala
```

## Public Scala signatures to implement

```scala
package kui.http.upstream

final case class UpstreamConfig(
    name: String,                       // the label used in metrics and errors
    urls: NonEmptyList[SafeUrl],
    callTimeout: FiniteDuration,        // default 10s
    maxConcurrent: PositiveInt,         // default 32
    maxRetries: Int,                    // default 2, GET-like only
    failureThreshold: PositiveInt,      // default 5
    resetTimeout: FiniteDuration,       // default 30s
    failoverGrace: FiniteDuration       // default 5s
)

enum CircuitState { case Closed, Open, HalfOpen }
final case class CircuitEvent(upstream: String, state: CircuitState, at: Instant, lastError: Option[String])

trait UpstreamClient[F[_]]:
  def backend: Backend[F]                     // hand to any Tapir sttp client interpreter
  def circuitStates: Stream[F, CircuitEvent]
  def currentState: F[CircuitState]

object UpstreamClient:
  def resource[F[_]: Async: Tracer](
      config: UpstreamConfig,
      underlying: Backend[F],
      telemetry: Telemetry[F]
  ): Resource[F, UpstreamClient[F]]

object RetryPolicy:
  def isIdempotent(method: Method): Boolean
  def shouldRetry(method: Method, outcome: Either[Throwable, Int], retryableStatuses: Set[Int]): Boolean
  def backoff(attempt: Int, base: FiniteDuration): FiniteDuration    // full jitter
```

**Error mapping**, binding on every caller:

| Condition | `KuiError` |
| --- | --- |
| all URLs failed / connection refused | `InfrastructureError.Unreachable(name, cause)` |
| call exceeded `callTimeout` | `InfrastructureError.Timeout(name, ms)` |
| breaker open | `InfrastructureError.CircuitOpen(name, since)` |
| bulkhead full | `InfrastructureError.Timeout(name, 0)` with detail `"bulkhead full"` |
| 401/403 from upstream | `InfrastructureError.AuthFailed(name)` |
| any other non-2xx | `InfrastructureError.Upstream(name, status)` — **body is discarded** |

## Library coordinates

```
com.softwaremill.sttp.client4::core::4.0.26
com.softwaremill.sttp.client4::fs2::4.0.26
com.softwaremill.sttp.client4::circe::4.0.26
com.softwaremill.sttp.tapir::tapir-sttp-client4::1.13.31
com.softwaremill.sttp.tapir::tapir-sttp-stub4-server::1.13.31    (test)
```

## Acceptance criteria

```
$ ./mill libs.http.test.testOnly 'kui.http.upstream.*'
```

Every test below uses `TestControl` (deterministic virtual time) so there is no `sleep` in the
suite and no flakiness in CI.

## Tests required

- `CircuitBreakerSuite` (unit + property):
  - `opensAfterConsecutiveFailuresAndNotAfterInterleavedSuccesses`.
  - `openRejectsImmediatelyWithoutCallingTheBackend` — asserts zero requests reach the stub.
  - `halfOpenAdmitsExactlyOneProbe` — concurrent callers, one probe.
  - `successInHalfOpenCloses`, `failureInHalfOpenReopensAndResetsTheTimer`.
  - `emitsOneCircuitEventPerTransitionAndNoneForRepeats`.
- `FailoverSuite` (unit): rotates on connection refusal; respects the grace period; returns
  `Unreachable` when all are down; a recovered URL is used again after the grace period.
- `UpstreamClientSuite` (unit + failure + concurrency):
  - `retriesGetButNotPost` — table over methods.
  - `doesNotRetryAFourHundredResponse`, `retriesAConfiguredRetryableStatus`.
  - `bulkheadCapsConcurrency` — 100 concurrent calls against a stub that counts peak
    in-flight; asserts peak ≤ `maxConcurrent`.
  - `slowUpstreamDoesNotStarveAnother` — two clients, one hung; the second's latency is
    unaffected (the property PLAN §16.4 asks for).
  - `timeoutCancelsTheUnderlyingCall` — the stub observes cancellation.
  - `upstreamBodyIsNotIncludedInTheError`.
  - `everyOutcomeRecordsKuiUpstreamDurationWithTheRightOutcomeLabel`.
- `UrlPolicySuite`: cross-host redirect refused; `file://` refused; metadata IP refused.

## Observability

`kui.upstream.duration {service, upstream, outcome}` on every call;
`kui.upstream.circuit.state {upstream}` as a gauge updated on each transition; one INFO log per
transition naming the upstream and the last error; **no log per failed call** (a dead upstream
must not flood the log — this is the flooding footgun the reference implementation has).

## Degraded behavior

This is the degraded-behavior engine. Its own contract: it always fails fast with a typed
`KuiError` and never blocks the caller longer than `callTimeout`; a caller may therefore treat
every upstream call as bounded.

## Docs to update

`docs/operations/observability.md`: what an open circuit looks like in metrics and logs, and
how it reaches the UI through the capability registry.

## Deviations

1. **The backend returns non-2xx responses; it does not turn them into failures.**
   `UpstreamClient.errorFor(config, outcome)` is the public mapping from an outcome to the
   `KuiError` in the spec's table, and a caller applies it. Converting a `500` into a raised error
   inside the backend would break every Tapir client interpreter, which decides for itself what a
   status means for its own endpoint — a `404` from `GET /subjects/{name}` is an ordinary "no such
   subject", not an infrastructure failure. What the backend *does* raise is everything that stops
   a response existing at all: circuit open, bulkhead full, timeout, unreachable.

2. **`CircuitBreaker.protect` takes a success predicate.** The spec's breaker only counts thrown
   failures, which would mean an upstream answering `503` to everything never opens the circuit —
   and something that answers `503` to everything is as down as something that refuses connections.
   `protect(call)(succeeded)` lets the client count a `5xx` response as a failure; `protect(call)`
   without the predicate is the convenience that treats any value as a success.

3. **Every failure leaves the backend wrapped in `UpstreamFailure(error: KuiError)`.** An sttp
   backend can only fail with a `Throwable`, so the typed error travels inside one and a caller
   unwraps it rather than reconstructing it from a message.

4. **The bulkhead rejection is `InfrastructureError.Timeout(s"$name (bulkhead full)", 0)`.** The
   spec asks for `Timeout(name, 0)` "with detail `bulkhead full`", and `InfrastructureError.Timeout`
   has no detail field — it is `(operation, afterMs)`. Putting the reason in the operation keeps it
   visible in the rendered message without changing a kernel type from this task.

5. **`UpstreamClient.resource` takes `Telemetry[F]`, a `ServiceId` and a `StructuredLogger[F]`
   rather than an implicit `Tracer`.** `service` is one of the three labels PLAN §30 puts on
   `kui.upstream.duration`, the tracer comes from the telemetry the process already has (OBS-002's
   `UpstreamInstrumentation.wrap` does the wrapping), and the logger is needed for the
   one-line-per-transition rule.

6. **Failover rotates only on a connection-level failure.** An address that answered `500` is
   reachable and is answering; trying the next machine would give the same answer and would hide
   from the operator that the cluster is unwell rather than unreachable. A URL-policy refusal does
   not fail over either, because the next address would be refused for the same reason.

7. **The URL policy is enforced per call, on the rebased address.** Checking only at configuration
   time would leave the case that matters open: a redirect is chosen by the upstream, so a
   compromised or misconfigured one could otherwise send KUI to `http://169.254.169.254/` and have
   it fetch the cloud instance's credentials from a network position no outsider has.

8. **`UpstreamConfig` gained `retryableStatuses`, `retryBase` and `urlPolicy`.** The first is the
   caller-supplied set the spec's retry rule refers to (Kafka Connect's 409, in M7); the second
   makes the backoff testable without a global; the third is how a deployment chooses strict or
   development address rules, consistent with `KuiConfigSource.load`.

9. **The named extension points ADR-037 lists are not implemented, as the spec requires.** There is
   no OAuth2 token cache, no TLS truststore or keystore configuration and no vendor media types.
   `UpstreamConfig` is where each of them will be a field, and the M7 milestone that needs them
   adds them.
