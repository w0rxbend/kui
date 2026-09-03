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

## Evidence

- `research/scala/security-research.md` §1.1 "Session model", "CSRF", §1.2 (Kouncil), §5
  threat table (CSRF, session fixation, open redirect, wildcard CORS), §6.2, ADR-017 candidate.
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
