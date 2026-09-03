# ADR-022 — Kafka cluster authentication as typed configuration

- Status: Accepted
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
