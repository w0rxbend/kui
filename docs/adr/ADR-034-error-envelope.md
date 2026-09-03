# ADR-034 — Error model and HTTP error envelope

- Status: Accepted
- Date: 2026-09-03

## Context

Kafbat maps 22 exception classes to numeric codes (`4007`, `5000`, …) with an HTTP status
each, includes stack traces unless disabled, and its frontend switches on the numbers.
Kouncil returns raw exception text with status 500. PLAN §26 requires typed errors and a
single envelope with stable machine-readable codes.

## Decision

- `KuiError` hierarchy in `libs/kernel` (`DomainError`, `ApplicationError`, `InfrastructureError`,
  `ARCHITECTURE.md` §4.1); business layers return `F[Either[KuiError, A]]`; adapters translate
  exceptions at the boundary; fatal errors propagate.
- One envelope in `libs/contracts-core`:
  `{ code: String, message: String, details: [{field?, restrictions[]}], correlationId, timestamp, retryable: Boolean }`.
- Codes are stable strings `KUI-<AREA>-<NAME>`; the initial table maps Kafbat's numbers:
  `KUI-CLUSTER-NOT-FOUND` (404), `KUI-TOPIC-NOT-FOUND` (404), `KUI-SCHEMA-NOT-FOUND` (404),
  `KUI-VALIDATION` (400, with `details`), `KUI-READ-ONLY` (405), `KUI-CONNECT-REBALANCING` (409),
  `KUI-INVALID-STATE` (409), `KUI-TIMEOUT` (408), `KUI-FILTER-COMPILE` (400),
  `KUI-CONNECTOR-OFFSETS` (400), `KUI-UPSTREAM-KSQL` (502), `KUI-UPSTREAM-AUTH` (502),
  `KUI-UPSTREAM-UNAVAILABLE` (503, circuit open), `KUI-UNSUPPORTED` (501, capability absent),
  `KUI-FORBIDDEN` (403), `KUI-UNAUTHENTICATED` (401), `KUI-CURSOR-EXPIRED` / `KUI-CURSOR-INVALID`
  (400), `KUI-CONFIG-VERSION-CONFLICT` (409), `KUI-INTERNAL` (500). The full table lives in
  `docs/api/error-codes.md` and is generated from the `ErrorCode` enum.
- Stack traces never leave a service; `message` is user-facing and never echoes upstream
  response bodies; `correlationId` is the gateway request id.
- Streams use the same shape inside the `error` SSE event (ADR-035); aggregations carry
  errors per `Section` (ADR-004).
- The frontend renders by `code`; messages are display text only.

## Evidence

- `research/kafbat/architecture.md` F11, D10; `research/kafbat/api-analysis.md` Finding 6 and
  the error-code mapping proposal; `research/kouncil/architecture.md` D11.
- `research/kafka/admin-capabilities.md` DC-D5 (exhaustive Kafka error mapper).

## Consequences

- A duplicate or renamed code is a contract change reviewed like any other.
- `Section.Unavailable` and the envelope share `ReasonCode` values.

## Alternatives rejected

- Numeric codes (Kafbat): opaque, collide, unreadable in logs.
- RFC 9457 problem details: close in spirit; the KUI envelope keeps `code` first-class and
  adds `correlationId`/`retryable`; a `application/problem+json` rendering can be added later.

## Reversibility

High (additive codes); the envelope shape is a contract.
