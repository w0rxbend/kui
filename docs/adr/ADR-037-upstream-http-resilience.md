# ADR-037 — Upstream HTTP clients: failover, retry, circuit breaker and bulkheads in one place

- Status: Accepted
- Date: 2026-09-03

## Context

Kafbat wraps every external HTTP client (Schema Registry, Connect, ksqlDB, Prometheus) in
`ReactiveFailover` (rotate on connection refused, 5 s grace) and Connect in a retrying client
(409/rebalance, 5 × 200 ms). The gateway additionally needs per-service timeouts, bulkheads
and circuit breakers that feed the capability registry.

## Decision

- `libs/http` provides one sttp-4 client factory `UpstreamClient.make(config)` with:
  failover over a URL list (mark failed for a grace period on connection errors, raise
  `Unreachable` when all are down), per-call timeout, retry only for idempotent reads or
  explicitly retryable statuses (Connect 409 "rebalance in progress"), bounded concurrency
  (bulkhead) per upstream, a circuit breaker with half-open probing, TLS (truststore,
  keystore, `verify=false` opt-in), basic auth or OAuth2 client-credentials with a token cache,
  lenient JSON decoding, vendor media types, and `traceparent` propagation. Circuit state is
  exposed as a stream consumed by the capability registry in the gateway and by services'
  `/capabilities`.
- Typed clients per upstream live in the owning service's `infrastructure` module with a
  sealed `UpstreamError` covering the documented error tables plus `Unknown(status, code, body)`.
- Connect uses `GET /connectors?expand=status&expand=info` (no N+1). ksqlDB uses
  `POST /query-stream` (HTTP/2, typed columns) first and falls back to `/query` when `/info`
  reports an old version or the HTTP/2 connection fails; `POST /close-query` on cancellation.
- Outbound URL policy from `ARCHITECTURE.md` §14 (scheme allow-list, no metadata ranges, no
  cross-host redirects) is enforced in the factory.

## Evidence

- `research/kafbat/architecture.md` F13, D11; `research/kafka/admin-capabilities.md` §6–§8,
  DC-D10; `research/scala/security-research.md` §5 (SSRF).

## Consequences

- One resilience implementation to test, with a fault-injection suite in `libs/testkit`.
- ksqlDB support needs an HTTP/2-capable sttp backend (the JDK `HttpClient` backend supports it).

## Alternatives rejected

- Per-service ad hoc retry logic: the reference's copy-paste problem.
- resilience4j: Java, thread-based; CE has the primitives.

## Reversibility

High.
