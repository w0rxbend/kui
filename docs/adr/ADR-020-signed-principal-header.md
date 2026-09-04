# ADR-020 — Signed principal header between gateway and services

- Status: Accepted
- Date: 2026-09-03

## Context

Services must re-check authorization (defence in depth) but must not trust a plain
`X-User` header that anyone reaching a service directly could forge. Kafbat is a monolith and
has no such boundary.

## Decision

- Header `X-Kui-Principal` = compact JWS (RFC 7515), **HS256** with a shared 256-bit key
  from `kui.gateway.principalKeys[]` (`kid`, `key: Secret`, `notBefore`); the gateway signs with
  the newest active key, services accept any listed `kid`. Rolling rotation: add key to
  services, then gateway, then remove the old key. `EdDSA` (Ed25519) is a config-only upgrade
  through the `SignerVerifier` abstraction for deployments where services must not be able
  to mint headers.
- Claims: `sub`, `roles`, `kind`, `iss = kui-gateway`, `aud = <service id>`, `iat`,
  `exp = iat + 60 s`, `jti`, `sid` (session hash for audit correlation),
  `req = sha256(METHOD \n PATH \n sha256(body))` binding the token to the exact call. Services
  verify signature, `aud`, `exp` (5 s skew) and `req`; `jti` is not tracked.
- Implemented in `libs/security-core` as `PrincipalCodec[F]` with the claim set as a Circe
  case class (nimbus `JWSObject`/`MACSigner`/`MACVerifier` are the only library use, kept in
  the JVM adapter so the core stays cross-platform).
- Services expose endpoints with `securityIn(header("X-Kui-Principal"))`; missing or invalid
  headers yield `401 KUI-UNAUTHENTICATED` regardless of network position. Streaming
  endpoints bind `req` to the request line only (no body).
- All-in-one: `PrincipalCodec.inProcess`; the key is ignored with a warning (ADR-005).
- Every `X-Kui-*` header from the browser is stripped at the gateway edge.

## Evidence

- `research/scala/security-research.md` §5 "Header injection / spoofed principal", §6.3,
  ADR-020 candidate.

## Consequences

- One HMAC per call plus one body hash pass at the gateway; negligible next to Kafka calls.
- Services are safe to run without a mesh; they still must not be exposed publicly.

## Alternatives rejected

- mTLS between gateway and services: infrastructure-dependent; does not carry the principal.
- Unsigned header with network policy: a policy misconfiguration becomes a privilege escalation.

## Reversibility

High. Algorithm and claims are behind one codec.

## Amendment 1 — how a request with a body is bound (2026-09-04)

- Status: Accepted
- Date: 2026-09-04

### What was wrong

The decision above says the `req` claim is `sha256(METHOD \n PATH \n sha256(body))` and that
streaming endpoints bind the request line only. It does not say *how* a service computes the body
half, and it turns out there is only one way to do it — which nobody had had to find, because every
endpoint shipped before M4 had an empty body.

The gateway signs the bytes it is about to send. A service must hash the same bytes. But Tapir runs
an endpoint's **security logic before it decodes the request body**, and the `ServerRequest` handed
to that stage does not expose the raw bytes: there is no `extractFromRequest` that can read them,
because at that point the body is still an unread stream the interpreter owns. So a service that
verified where ADR-020 implies it should verify can only ever hash the request line, and every
bodied call from the gateway is refused as `request_mismatch` — a `401` naming nothing.

That is what happened. The consumer service's offset-reset wizard was the first bodied endpoint in
KUI, and it failed the first time it ran end to end against a real cluster. It was fixed locally, in
`services/consumer/api`, with a comment warning that the message service's produce and resend
endpoints would hit the identical wall and that two services each inventing an answer is the drift
this project keeps paying for. This amendment is that warning being acted on.

### Decision

**A bodied `/internal/v1` endpoint verifies its principal one stage later than a body-less one, and
reconstructs the signed bytes by re-encoding the decoded input through the contract's own codec.**

Concretely, and in one place — `kui.http.principal.SecuredRoutes` in `libs/http`:

- `apply` binds an endpoint with no body. Verification stays in Tapir's security stage: an
  unauthenticated caller is refused without the service parsing a byte of what they sent.
- `stream` binds a streaming endpoint. Its digest is the request line, exactly as the original
  decision says.
- `withBody` binds an endpoint that carries a request body. It passes the token through the security
  stage unverified, then verifies inside the endpoint's own logic, where the decoded input is in
  hand, against a digest completed with `sha256(bodyBytes(input))`.
- `SecuredRoutes.bodyBytes` is the one printer: circe's `Printer.noSpaces` over the contract's
  hand-written encoder, which is what `jsonBody` uses on both sides of the hop.

The reconstruction is exact rather than approximate, and this is the property the whole amendment
rests on: **the gateway does not forward a caller's bytes.** `SttpServiceClient` decodes the
browser's request against the same contract endpoint value and re-encodes it through the same
codec before signing. Both sides therefore print the same fields in the same hand-written order
(ADR-007) with the same printer, and the two hashes agree by construction rather than by luck.

Every service's `api` module now takes its `Securing` from this class. The alternative — each
module keeping its own copy — is what produced the situation this amendment exists to end.

### The cost, stated plainly

The body of an *unauthenticated* request is decoded before the token is checked. That is real, and
it is the property `apply` has and `withBody` gives up. It is bounded: a few hundred bytes of JSON
through a hand-written codec that refuses every field it does not recognise, with no recursion and
no unbounded collection in any mutation request KUI serves. What it buys back is the binding that
stops a token minted for one call being replayed with a different body — publishing to a different
topic, resetting to a different offset — which is the property that actually matters on an endpoint
that changes a cluster.

### Alternative rejected: sign the request line only for bodied calls

One line in the gateway, and every existing test stays green, because every endpoint that had
shipped at the time had an empty body. That is precisely why it was rejected: it is invisible. It
would drop the body binding for **every service at once**, for every mutation KUI will ever add,
and the loss would show up in no test and no log line. A token intercepted on its way to
`POST …/messages` would then be replayable with any body at all against that path, which on a
produce endpoint means writing an attacker's record into the operator's topic under the operator's
identity.

The narrower variant — sign the request line only for endpoints explicitly marked as exempt — was
also rejected. It puts a security decision on the author of each new endpoint, and ADR-047 has just
finished arguing that a classification the author has to remember is a classification that will be
forgotten.

### Consequences

- `libs/http` gains `SecuredRoutes`; the cluster, topic, consumer and message `api` modules lose
  their private copies of it and keep only the name.
- The cluster service's `PUT /internal/v1/clusters/{id}` — a bodied endpoint that predates the
  problem and had never been called through the gateway — is bound correctly as a side effect of
  the same change, rather than becoming a third rediscovery of it.
- A new bodied endpoint has one thing to get right: pass the body field to `withBody`. Getting it
  wrong fails immediately and loudly, on the first call, with `request_mismatch` in the rejection
  counter.

### Reversibility

High. Everything is behind one class in `libs/http`, and the claim set is unchanged: this amendment
decides how a value already in the claim set is computed, not what travels on the wire.
