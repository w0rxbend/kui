# ADR-040 — Edge header policy: the gateway generates correlation ids and trusts no inbound `X-Kui-*`

- Status: Accepted
- Date: 2026-09-03

## Context

KUI uses a family of internal HTTP headers, all named `X-Kui-*`, to carry things services are
entitled to believe: `X-Kui-Principal` is a signed statement of who the user is (ADR-020),
`X-Kui-Correlation-Id` is the request id that ties a log line to a span to an error envelope
(ADR-008, ADR-009), and `X-Kui-Cluster-Id` names the cluster a request is about (ADR-031).

Every one of those headers is set by the gateway on its way to a service. None of them is ever
legitimately set by a browser. But a browser can set any header it likes, and the gateway sits
directly on the public network — so unless something explicitly removes them, a request from a
user's browser arrives at a service carrying whatever `X-Kui-*` values that user typed.

`ARCHITECTURE.md` §5 already says the gateway "strips all inbound `X-Kui-*` headers from
browsers at the edge". What it does not say is what happens to the correlation id, which is
the one header where "just pass it through" looks helpful rather than dangerous — a client
that supplies its own id can correlate its logs with ours, which is a genuinely useful thing
that many systems allow. The M0 grooming plan settled the question in task GW-001 while
building the gateway skeleton. This ADR records it, because it is a security boundary and
because every service added in M1–M8 depends on it holding.

## Decision

### 1. One deny-list, applied before anything else sees the request

The gateway removes **every** header whose lowercased name begins `x-kui-` from every inbound
request, before routing, before authentication, before logging. The set is a deny-list by
prefix, not an enumeration of the three headers that exist today, so a header added in a later
milestone is protected the day it is introduced rather than the day someone remembers to add
it to a list. It is implemented in one place — `EdgeHeaders.strip` in the gateway's `api`
module — and tested by asserting that a forged header never reaches a handler.

### 2. The gateway generates the correlation id; it never accepts one

The gateway mints a fresh correlation id for every request. An inbound
`X-Kui-Correlation-Id` is discarded with the rest of the family, not honoured and not merged.

The convenience of letting a client supply its own id is real but small, and the cost is not.
A correlation id is the handle an operator uses to pull one request out of a day of logs, and
it only works if it is unique and if it means what it appears to mean. A client-supplied id
can be a duplicate — by accident, or on purpose — and once two unrelated requests share an id,
the investigation that needed it is the investigation that fails. Worse, an id is written into
log lines and span attributes across every service, so accepting one is accepting
attacker-controlled text into the observability pipeline: log injection through embedded
newlines, unbounded cardinality in metric labels, unbounded length in span attributes. None of
those risks buys anything the client cannot get another way: the gateway returns the id it
generated in the response header, so a caller that wants to correlate simply reads it back.

The generated id is echoed in the response header, written into the error envelope
(ADR-034), attached to every log line and span, and forwarded to upstreams as
`X-Kui-Correlation-Id` on the internal call.

### 3. W3C trace context is separate, and is handled by otel4s

`traceparent` and `tracestate` are not `X-Kui-*` headers and are not covered by this decision.
Distributed tracing has its own standard and its own sampling and validation rules; otel4s
handles them (ADR-009). Conflating the two would mean reimplementing a specification badly.

### 4. Services do not rely on the gateway alone

Defence in depth, unchanged from ADR-020: a service verifies the signature on
`X-Kui-Principal` and rejects the request when it does not verify. The edge strip is what
stops a forged header from reaching a service at all; signature verification is what stops it
from being believed if a future deployment mistake ever lets one through. Both, not either.

## Consequences

- A client cannot supply its own correlation id. Callers that want one read the response
  header instead. If a future integration genuinely needs client-supplied correlation, it gets
  a differently named header with its own validation, never this one.
- The strip runs on every request including health checks, so it must be cheap: a prefix test
  over header names, no allocation in the common case.
- The rule is a deny-list by prefix, so any new `X-Kui-*` header inherits the protection.
  Correspondingly, nothing that a browser *is* allowed to send may be named `X-Kui-*`.
- All-in-one deployment is unaffected: there are no HTTP headers between the gateway and a
  service in-process, and the principal is passed directly (ADR-005).

## Alternatives rejected

- **Honour an inbound correlation id when it matches a strict format.** Format validation stops
  log injection but not duplicate or forged ids, which are the failures that actually destroy
  the value of the field.
- **Enumerate the three known headers instead of matching the prefix.** Correct today and
  wrong on the day someone adds a fourth. The failure is silent and security-relevant.
- **Strip in each service instead of at the gateway.** Eleven implementations of one rule, with
  the failure mode that a service which forgets is a service that trusts the internet.

## Reversibility

High. The policy is one function at the edge and a test; nothing in the contracts depends on
where the id comes from.

## References

PLAN §17 (header propagation), §31; ADR-008, ADR-009 (where the id is consumed), ADR-019
(session and CSRF at the same edge), ADR-020 (signed principal, defence in depth), ADR-031
(`X-Kui-Cluster-Id`), ADR-034 (`correlationId` in the envelope); `ARCHITECTURE.md` §5, §13,
§14; tasks GW-001 (`EdgeHeaders.strip`), GW-009 (session and CSRF at the same boundary).
