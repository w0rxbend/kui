# ADR-015 — Application authentication: form, OIDC and LDAP in the identity service

- Status: Accepted
- Date: 2026-09-03

## Context

Kafbat supports `DISABLED | LOGIN_FORM | OAUTH2 | LDAP` with Spring Security; Kouncil adds an
in-memory first-launch flow and GitHub/Okta SSO. KUI has no Spring and needs the same
coverage on Tapir with framework-free libraries.

## Decision

- `kui-identity-service` owns authentication. Config `kui.auth.type = disabled | form | oidc | ldap`
  (exactly one primary type) plus optional `kui.auth.bearer` for API clients.
- **OIDC/OAuth2**: hand-rolled relying party over **nimbus `oauth2-oidc-sdk` 11.38.2** and
  **nimbus-jose-jwt 10.9.1** behind `OidcProviderPort[F]`: discovery, authorization URL with
  `state`/`nonce`/PKCE, code exchange, ID token validation, userinfo, RP-initiated logout.
  Provider kinds: `generic`, `github`, `gitlab`, `google`, `cognito`, `azure`, `okta`; group
  resolution per provider as small `GroupResolver` adapters (GitHub orgs/teams, GitLab groups,
  Google `hd`, Cognito `cognito:groups`, generic `rolesField`). HTTP goes through sttp.
- **LDAP/AD**: **UnboundID LDAP SDK 7.0.5** behind `IdentityProviderPort[F]` (connection pool,
  user bind, group search, AD nested groups), wrapped in `Sync[F].blocking`.
- **Form**: users in config with bcrypt hashes (`at.favre.lib:bcrypt`), a `kui hash-password`
  CLI; Kouncil-style first-launch bootstrap admin with forced password rotation when no users
  are configured.
- **Bearer**: JWT via JWKS or opaque introspection; roles resolved by the same subject matcher.
- Role resolution happens once at login and is stored in the session; RBAC never calls the IdP.
- `GET /api/v1/auth/settings` (public) advertises the type and provider start URLs;
  `GET /api/v1/auth/me` returns the principal, expanded permissions and CSRF token.

## Evidence

- `research/scala/security-research.md` §1 (reference behaviour), §2.3 (subject matching per
  provider), §6.1, §6.4 (library comparison; pac4j rejected), ADR-015 candidate.
- `research/scala/ecosystem-mapping.md` F9 (pac4j reject, nimbus/UnboundID versions).
- `research/kouncil/architecture.md` D10 (bootstrap admin).

## Consequences

- ~400 lines of OIDC glue that KUI owns and tests (with a stub IdP in `libs/testkit`).
- SAML and CAS are out of scope.
- Session and CSRF behaviour are defined in ADR-019.

## Alternatives rejected

- pac4j 6.5.x: servlet-shaped `WebContext`/`SessionStore`, no Tapir adapter, large graph.
- jwt-scala: JWT only, no OIDC/JWKS.
- ldaptive: fine library, but UnboundID is more mature for AD and already named in PLAN §12.

## Reversibility

Medium. Ports isolate the libraries; the config vocabulary is a public contract.
