# ADR-020 — Signed principal header between gateway and services

- Status: Accepted
- Date: 2026-09-03

## Context

Services must re-check authorization (defence in depth, PLAN §20) but must not trust a plain
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
  ADR-020 candidate; PLAN §31.

## Consequences

- One HMAC per call plus one body hash pass at the gateway; negligible next to Kafka calls.
- Services are safe to run without a mesh; they still must not be exposed publicly.

## Alternatives rejected

- mTLS between gateway and services: infrastructure-dependent; does not carry the principal.
- Unsigned header with network policy: a policy misconfiguration becomes a privilege escalation.

## Reversibility

High. Algorithm and claims are behind one codec.
