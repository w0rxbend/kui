# ADR-034 — Error model and HTTP error envelope

- Status: Accepted
- Date: 2026-09-03

## Context

Kafbat maps 22 exception classes to numeric codes (`4007`, `5000`, …) with an HTTP status
each, includes stack traces unless disabled, and its frontend switches on the numbers.
Kouncil returns raw exception text with status 500. The project's error-handling rules require typed errors and a
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
  (400), `KUI-CURSOR-TOO-LARGE` (400), `KUI-CONFIG-VERSION-CONFLICT` (409),
  `KUI-ROUTE-NOT-FOUND` (404), `KUI-INTERNAL` (500). The full table lives in
  `docs/api/error-codes.md` and is generated from the `ErrorCode` enum.
- Stack traces never leave a service; `message` is user-facing and never echoes upstream
  response bodies; `correlationId` is the gateway request id.
- Streams use the same shape inside the `error` SSE event (ADR-035); aggregations carry
  errors per `Section` (ADR-004).
- The frontend renders by `code`; messages are display text only.

## Amendments

Both were settled and reviewed as part of the M0 architecture review, 2026-09-03. They are
additive and do not change the envelope shape, so this ADR is amended rather than superseded.

**Amendment 1 — two codes the original table missed.**

- `KUI-ROUTE-NOT-FOUND` (404). Needed because a request for a path no endpoint matches is a
  distinct failure from anything the original table covered. `KUI-INTERNAL` would tell the
  caller the server broke when nothing broke; `KUI-VALIDATION` would point at a request body
  that was never read; and the resource-specific 404s (`KUI-CLUSTER-NOT-FOUND` and friends)
  claim a known resource was looked up and missing. Without a code of its own, Tapir's default
  404 body escapes the envelope entirely, and `ARCHITECTURE.md` §15's "one mapping point" rule
  would have a hole in it. Implemented by task HTTP-001 in `libs/http`'s error interceptor.
- `KUI-CURSOR-TOO-LARGE` (400). ADR-026 already referred to this code (and `TECH_DEBT.md`
  TD-005 tracks the underlying limit), but it was never added here, so the enum that generates
  `docs/api/error-codes.md` did not contain it. Declared in `libs/kernel` from M0; first
  returned in M3 when cursors are implemented.

**Amendment 2 — `ErrorCode` carries a `description` field.**

`docs/api/error-codes.md` is generated from the `ErrorCode` enum, so each case needs one
sentence of operator-facing prose. That sentence lives in a `description: String` constructor
parameter on the enum, not in a lookup table beside it and not in a Scaladoc comment scraped
at build time. A constructor parameter is what makes the guarantee hold: the compiler rejects
a new case that does not supply a description, so the generated document can never fall behind
the enum. A side table or a comment can both silently go missing. Declared by task KERN-002,
consumed by the generator in task KERN-008.

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
