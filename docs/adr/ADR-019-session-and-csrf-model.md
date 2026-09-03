# ADR-019 — Gateway session and CSRF model

- Status: Accepted
- Date: 2026-09-03

## Context

Kafbat disables CSRF in every auth mode, logs out over `GET`, and stores sessions in memory
per instance. Kouncil uses a double-submit CSRF cookie. KUI's gateway is the only
browser-facing process and must be safe by default and scalable behind a load balancer.

## Decision

- **Browser sessions**: opaque id (32 random bytes, base64url) in cookie `kui_session`
  (`HttpOnly; Secure; SameSite=Lax; Path=/`), issued fresh at login (fixation defence) and on
  privilege change; idle timeout 30 min, absolute 12 h. Session state (principal, role names,
  expiry, CSRF secret, OIDC tokens needed for logout) lives behind the identity service's
  `SessionStore[F]` port; the gateway caches `sessionId → Principal` for 30 s.
- `SessionStore` adapters: in-memory (default, single gateway replica) and a compacted Kafka
  topic adapter (M6+) for multiple replicas; Redis is not introduced.
- **CSRF**: every non-GET request authenticated by cookie must carry `X-Kui-Csrf` equal to the
  session's CSRF secret (obtained from `GET /api/v1/auth/me`); the gateway also rejects
  cookie-authenticated mutations whose `Sec-Fetch-Site` is `cross-site`. Bearer-token
  requests are exempt.
- Logout is `POST /api/v1/auth/logout`; OIDC logout redirects to the provider's
  `end_session_endpoint` (or Cognito's logout URL) with a same-origin `post_logout_redirect_uri`.
- OIDC `state`, `nonce` and PKCE verifier are single-use server-side entries with a 5 min TTL.
- `returnTo` after login must be a same-origin relative path.
- CORS is off by default; the gateway serves the SPA from the same origin.

## Amendments

**Amendment 1 — the CSRF header is `X-Csrf-Token`, not `X-Kui-Csrf`.**

Settled during M0 implementation (tasks GW-009 and UI-010). The original name is incompatible
with ADR-040, which has the gateway strip every inbound header in the `X-Kui-*` family at the
edge — that family is how the gateway talks to itself, and no browser ever legitimately sets
one. A CSRF header inside it would be deleted before the check that needs it ever ran, so the
mechanism could not work under its own name. The header is therefore `X-Csrf-Token`, and the
name lives in one place, `kui.contracts.HttpHeaders.Csrf`, which both the gateway and the
browser's `ApiClient` compile against. Nothing else about the decision changes: the token is
still the session's secret, still obtained from `GET /api/v1/auth/me`, still required on every
cookie-authenticated non-`GET`, and still paired with the `Sec-Fetch-Site` check.

The shared constant is the point of the amendment rather than an implementation detail. The two
halves originally spelled the name out independently, they drifted, and nothing failed to
compile: the browser sent a header the gateway did not read, every mutation came back `403`, and
the only evidence was in production.

## Evidence

- `research/scala/security-research.md` §1.1 "Session model", "CSRF", §1.2 (Kouncil), §5
  threat table (CSRF, session fixation, open redirect, wildcard CORS), §6.2, ADR-019 candidate.
- `research/kafbat/api-analysis.md` Finding 7 (single-replica sessions).

## Consequences

- The SPA sends one extra header on mutations; the kernel `ApiClient` does it.
- Horizontal scaling of the gateway waits for the Kafka-backed session adapter (tracked in
  TECH_DEBT).

## Alternatives rejected

- Stateless JWT sessions in the cookie: no server-side revocation, larger cookies, secrets in
  the browser.
- CSRF by `Content-Type: application/json` alone: weaker; double submit plus fetch metadata
  costs nothing.

## Reversibility

High. Store is a port; CSRF is gateway middleware.
