# Signing in to KUI

How to turn KUI's own login on, and what each mode does. This is about **who may use KUI**. How KUI
authenticates to a *Kafka cluster* is a completely separate thing with a completely separate section
of the configuration (`kui.clusters[].security`, see `docs/operations/configuration.md`), and the
two are deliberately kept apart: conflating them is how a product ends up handing a browser its
broker password.

## The default is no login, and that is deliberate

A KUI that has been told nothing about authentication asks nobody to sign in. Every request is
anonymous, every control is available, and the demonstration environment and the quickstart work as
they always have. That is not an oversight to be fixed later — a newcomer who meets a login screen
before they have seen a topic list has not seen the product.

Everything below is opt-in.

## Mode 1: a login form (`kui.auth.type: form`)

The smallest credible deployment: a shared instance with a few named people and no directory server
to ask.

```yaml
kui:
  auth:
    type: form
    users:
      - name: ada
        passwordHash: "env:KUI_ADA_HASH"
        groups: [platform]
      - name: grace
        passwordHash: "pbkdf2-sha256$210000$...$..."
        groups: [readers]
        mustChangePassword: true
```

### Writing a password hash

```
./mill services.identity.app.runMain kui.identity.app.HashPassword
```

It reads the password from standard input and prints one line: the encoded hash. The password is
deliberately **not** a command-line argument — an argument is visible in `ps` to every other user on
the machine and is written into the shell's history file.

The hash names its own algorithm, cost and salt (`pbkdf2-sha256$<iterations>$<salt>$<key>`), so
raising the cost later does not invalidate the hashes you already have. Put it in the file through
`env:NAME` or `file:/path` wherever you can: a hash is not a password, but it is the input to an
offline cracking attempt.

### Forced password change

`mustChangePassword: true` is enforced by the server, not suggested to the browser. Such an account
signs in successfully and is given a **single-use challenge instead of a session**; only completing
the change with that challenge produces one. The new password is written to KUI's metadata store, so
it survives a restart — configure `kui.store.dir` (or the Kafka store) or the change is refused with
a message saying exactly that, rather than appearing to work and vanishing at the next restart.

## Mode 2: an identity provider (`kui.auth.type: oidc`)

Any OpenID Connect provider — Keycloak, Okta, Entra, Google, Cognito, Auth0.

```yaml
kui:
  auth:
    type: oidc
    oidc:
      issuer: https://accounts.example.com
      clientId: kui
      clientSecret: "env:KUI_OIDC_SECRET"
      redirectUri: https://kui.example.com/api/v1/auth/oidc/callback
      scopes: [openid, profile, email]
      usernameClaim: email
      groupsClaim: groups
      label: "Example SSO"
```

`issuer` is the only address you normally have to write down: the authorization endpoint, the token
endpoint and the key set are read from `<issuer>/.well-known/openid-configuration`. `openid` is
added to `scopes` if you leave it out, because without it the provider runs a plain OAuth 2 flow and
returns no ID token.

`redirectUri` must be registered with the provider **and** must be an address this deployment
actually serves — normally `<your public base URL>/api/v1/auth/oidc/callback`.

The provider's tokens never leave the KUI process. The browser gets a session cookie and nothing
else.

**Not implemented yet:** the per-provider group sources — GitHub organisations and teams, Google's
`hd` domain claim, Cognito's `cognito:groups`. What works today is the generic case every compliant
provider can be configured for: one claim holding the username and one holding the groups.

## LDAP and Active Directory

Not implemented. `type: ldap` is refused at start-up with a message saying so, rather than being
silently treated as an unknown word.

## Roles (`kui.rbac`)

Groups come from the person; roles come from the deployment. A role names the clusters it applies
on, who is in it, and what it grants:

```yaml
kui:
  rbac:
    roles:
      - name: developers
        clusters: [local, staging]
        subjects:
          - provider: FORM        # or OAUTH, OAUTH_GITHUB, OAUTH_GOOGLE, ...
            kind: group           # user | group | domain | organization | team | role
            value: platform
        permissions:
          - resource: TOPIC
            value: ".*"           # a regular expression, full-match
            actions: [VIEW, MESSAGES_READ]
          - resource: CONSUMER
            value: ".*"
            actions: [ALL]
    defaultRole:
      permissions:
        - resource: TOPIC
          value: ".*"
          actions: [VIEW]
```

Things worth knowing before you write one:

- **No roles means no restrictions.** A deployment that configures neither `roles` nor
  `defaultRole` allows everything. Configuring no authorization is not the same as denying
  everything, and denying everything is not a useful default for a tool nobody has configured yet.
- **Actions expand.** Granting `DELETE` on a topic grants `VIEW` too, because a delete button on a
  list you cannot see is not a permission anybody wanted. The expansion happens once, when the file
  is read, so the server and the browser can never disagree about it.
- **A named resource needs a `value`.** `TOPIC` with no pattern grants nothing at all, so KUI
  refuses the file instead of starting with a role that silently does nothing. Write `".*"` if you
  mean all of them. `AUDIT`, `KSQL` and `ACL` are not named and must not carry one.
- **Mistakes are startup errors.** An action that does not exist on that resource, a regular
  expression that will not compile, two roles with one name, an unknown provider — each is reported
  with the key that is wrong, alongside every other problem in the file, before the process listens.

Roles are resolved **once**, when somebody signs in, and travel with their session. Changing the
file therefore takes effect at the next sign-in, not at the next request.

## What the browser sees

- `GET /api/v1/auth/settings` — which mode this deployment uses and what to put on the button.
  Answered by the gateway from its own configuration, so it still works when the identity service is
  down. It contains no credential and cannot be made to.
- `GET /api/v1/auth/me` — who you are, the CSRF token to put in `X-Csrf-Token` on every mutating
  request, and the full expanded permission list.
- `POST /api/v1/auth/login`, `POST /api/v1/auth/password`, `POST /api/v1/auth/oidc/start`,
  `GET /api/v1/auth/oidc/callback`, `POST /api/v1/auth/logout`.

Cross-site request forgery protection stays on in every mode. Every mutating request made with a
session cookie must echo the token from `/auth/me`, and one arriving with `Sec-Fetch-Site:
cross-site` is refused outright.

## What is recorded

Every sign-in, refusal, password change and sign-out is written to the audit trail as one structured
log line, with the same field names a cluster mutation uses — `audit.operation`, `audit.principal`,
`audit.outcome` — so one query answers "everything this person did today" across both.

A refusal records the name that was **attempted** and says `refused`. It does not say whether the
account exists: an audit log is read by more people than a password file is, and "no such user"
against "wrong password" in a searchable log is an account-enumeration oracle for everyone who can
read it.

Nothing in the trail is ever a password, a token, a session id or an authorization code.
