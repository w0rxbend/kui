# ADR-022 — Kafka cluster authentication as typed configuration

- Status: Accepted (amended 2026-09-03, Amendment 1 — see below)
- Date: 2026-09-03

## Context

Kafbat passes free-form `properties` (including `sasl.jaas.config` with embedded passwords)
straight to the Kafka clients and returns them from `GET /api/config`; Kouncil has a typed
model but builds JAAS strings with `String.format`, so a quote in a password breaks or injects.

## Decision

- `kui.clusters[].security` is a typed ADT in `libs/config` / `ClusterProfile`:

```
security: plaintext
        | ssl { truststore?, keystore?, verifyHostname: Boolean }
        | sasl { protocol: saslPlaintext | saslSsl, ssl?,
                 mechanism: plain(username, password: Secret)
                          | scramSha256(...) | scramSha512(...)
                          | gssapi(serviceName, principal, keytab)
                          | oauthBearer(tokenEndpoint, clientId, clientSecret: Secret, scope?)
                          | awsMskIam(profile?) | azureEntra(namespace) | gcpManagedKafka }
properties: Map[String, String]   # override layer, applied last, secret-redacted by key pattern
```

- `libs/kafka-auth` renders `security.*`, `sasl.*`, `ssl.*` properties and JAAS strings with
  proper quoting/escaping; the same rendering serves admin, consumer and producer clients.
  Registry, Connect, ksqlDB and metrics endpoints have their own typed `auth`
  (`none | basic | oauth2ClientCredentials | mtls`).
- Cloud handlers (`aws-msk-iam-auth` 2.3.7, `azure-identity` 1.18.6,
  `managed-kafka-auth-login-handler` 1.0.6 + `google-oauth-client` 1.39.0) are optional runtime
  modules selected by config; the Azure path also supports the generic
  `OAuthBearerLoginCallbackHandler` against the Entra token endpoint without the Azure SDK.
- Keystores and truststores are `Secret[Bytes]` inline in the profile (or a path in
  single-process mode); adapters materialize them to a private tmpfs path when needed.
- Kafbat's `kafka.clusters[].properties` keys keep working through the override layer, and
  the migration tool lifts known keys into the typed model.

## Evidence

- `research/scala/security-research.md` §3 (mechanism table, Kafbat raw properties and JAAS
  leak, Kouncil JAAS injection), ADR-022 candidate.
- `research/kafbat/feature-matrix.md` D-7; `research/scala/ecosystem-mapping.md` F9 (handler
  versions and weight).

## Consequences

- More config surface to keep in step with Kafka mechanisms; the override layer covers gaps.
- Heavy cloud SDKs are not on the default classpath.

## Alternatives rejected

- Raw properties only (Kafbat): secrets leak, no validation, no redaction.
- Typed model without override layer: blocks new mechanisms until KUI ships a release.

## Reversibility

High.

## Amendment 1 — 2026-09-03 (M1 gate review)

**What changed.** The typed ADT does **not** live in `libs/config` / `ClusterProfile`. It lives
in `libs/kernel`, in a new pure `kui.kernel.cluster` package: `BootstrapServers`,
`ClusterSecurity`, `ClientProperties`, `AdminTuning`, `ClusterConnection`.

**Why.** As originally written the decision is unimplementable under ADR-041. Rule A5 forbids
`libs/kafka` depending on a service, and rule A1 forbids a `domain` module depending on
`libs/config` or `libs/kafka-auth`; but `ARCHITECTURE.md` §4.2 writes every admin port as taking
a `ClusterProfile`, and the cluster domain owns `ClusterProfile`. Something had to move. The
alternative — one ADT in `libs`, a second in the domain and a mapper in `infrastructure` — is
the duplication ADR-041 exists to prevent, and it would mean the redaction rule of this ADR was
implemented twice, in two files, exactly the defect the M0 review recorded.

`libs/kernel` is already the shared-kernel home of `Secret[A]` and every id type
(`ARCHITECTURE.md` §4.1). The ADT is pure data with no dependencies, so `libs/contracts-core` can
derive the redacted DTO from the same definition the renderer consumes — and that DTO is what
reaches the browser's generated types, one definition end to end.

**Consequences for the rest of this ADR.** `libs/config` decodes the ADT with Ciris rather than
declaring it; `libs/kafka-auth` renders it to `security.*` / `sasl.*` / `ssl.*` properties and
is the only place a JAAS string is assembled; the domain's `ClusterProfile` composes a
`ClusterConnection` field. Nothing about the mechanism table, the quoting rules, the
`properties` override layer or the optional cloud-handler coordinates changes.

**Tasks updated:** KAFKA-001 … KAFKA-004, CFGOP-001, STORE-004, CLDOM-001, CLAPI-001;
`DEVPLAN` §5.2 and §10 D1.
