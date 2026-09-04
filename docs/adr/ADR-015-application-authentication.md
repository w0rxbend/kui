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
- ldaptive: fine library, but UnboundID is more mature for AD and already the project's chosen library.

## Reversibility

Medium. Ports isolate the libraries; the config vocabulary is a public contract.

---

## Amendment 1 — what the identity service actually shipped (2026-09-04)

- Status: Accepted
- Supersedes: the library choices in the Decision above, on three points.

### 1. Password hashing is PBKDF2 from the JDK, not bcrypt

The Decision named `at.favre.lib:bcrypt`. A new third-party dependency needs the PLAN §13
checklist and an approval, and this is one of the few places where the platform already ships an
adequate answer. `PBKDF2WithHmacSHA256` is in `javax.crypto`, is NIST's recommended password-based
key derivation function (SP 800-132), and at **210,000 iterations** — OWASP's published parameter
for this exact construction — costs about the right amount of time per attempt.

Hashes are self-describing:

```
pbkdf2-sha256$210000$<salt, base64url>$<derived key, base64url>
```

Everything needed to verify a password is in the string, so raising the cost later leaves every
existing hash verifiable and each is rewritten at the owner's next password change. Adding bcrypt
or Argon2id later is a new prefix, not a migration: the algorithm travels with the hash.

**Tradeoff, stated plainly.** bcrypt has the better memory-hardness story and Argon2id better
still. PBKDF2 with a high iteration count is weaker against an attacker with GPUs than either.
It is not weaker than what a KUI deployment had before this milestone, which was no login at all,
and the format makes the upgrade cheap when a dependency is worth adding.

### 2. LDAP and Active Directory are not implemented

`kui.auth.type` accepts `disabled`, `form` and `oidc`. `ldap` is refused **by name**, with a
message saying it is not implemented rather than that it is not a word, so an operator migrating a
Kafbat configuration is told the truth at start-up instead of at the first sign-in.

It needs UnboundID (a new dependency), a directory server to test against, and nested-group
resolution that has no honest fake. It is the one authentication mode of the original Decision that
this milestone does not ship.

### 3. `GET /api/v1/auth/settings` is answered by the gateway, from configuration

The Decision put the settings endpoint on the identity service. It is served by the gateway
instead, from the gateway's own `kui.auth` section, and calls nothing. The reason is availability:
a login screen that cannot render because the service behind the login is down leaves an operator
with nothing to look at during exactly the outage they are trying to diagnose.

The document has three fields — the mode, the label for the provider's button, and whether any role
is configured — and that narrowness is the control, not the care. `research/scala/security-research.md`
records a reference product serving its own configuration, Kafka credentials included, from an
endpoint like this one; a type with three fields cannot do that however it is wired.

### What else the milestone landed, for the record

- OpenID Connect is a hand-rolled relying party over sttp and nimbus-jose-jwt as the Decision says:
  discovery, PKCE, a nonce, and ID-token validation on signature, issuer, audience, expiry and
  nonce. Provider-specific group extractors (GitHub organisations, Google `hd`, Cognito groups) are
  RB-002 and are not here; the generic named-claim case is.
- Bearer-token API access (`kui.auth.bearer`) is not implemented. `PrincipalKind.Bearer` exists and
  the CSRF check already exempts it, so the shape is ready; the JWKS verification is not written.
- Role resolution happens once, at sign-in, and the result travels in the session and then in the
  signed principal — exactly as the Decision requires. No service ever asks an identity provider
  anything.
