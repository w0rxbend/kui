# ADR-023 — Audit records and data masking rules

- Status: Accepted
- Date: 2026-09-03

## Context

Kafbat audits every controller pipeline (console and/or a Kafka topic, `ALL | ALTER_ONLY`)
and masks deserialized payloads with `REMOVE | MASK | REPLACE` rules scoped by topic
patterns. Kouncil stores masking policies in a database, scopes them by user group and
offers `ALL | FIRST_5 | LAST_5`.

## Decision

- **Audit**: record shape kept from Kafbat (`timestamp, username, clusterName, resources[]
  {type, id, alter, accessType[]}, operation, operationParams, result{success, error}`) plus
  `correlationId`, `principalKind` and `sessionRef`. Levels `all | alterOnly` (default
  `alterOnly`); sinks `console` and a Kafka topic (`__kui-audit-log` by default, created with
  90-day retention, gzip producer, degrades to console when topic init fails unless
  `requireAuditTopic`). Audit records are produced from the same `AccessRequest` and `Decision`
  the authorization used, in the service that executed the operation; the gateway forwards
  edge-only events (login, logout, denied pre-checks) to the identity service's `AuditSink`.
  Browsing or deleting the audit topic of the same cluster is refused.
- **Masking**: Kafbat's rule model (`type: remove | mask | replace`, `fields[]` xor
  `fieldsNamePattern`, `maskingCharsReplacement`, `replacement`, `topicKeysPattern`,
  `topicValuesPattern`) in `libs/security-core` as pure functions over Circe `Json`; JSON
  values get all matching policies in order, non-JSON values get the first policy's string
  form. Kouncil's `FIRST_5 | LAST_5` become a `keep: { prefix?, suffix? }` option of `mask`.
  Masking runs in `kui-message-service` after deserialization and before any DTO leaves the
  service, including `originalValue` fields for the table view. Masking is never applied on
  produce.
- Group-scoped policies (Kouncil) are an M5+ extension: `subjects[]` on a rule, evaluated
  against the principal's roles from the signed header; policies editable in the UI live in
  the `ConfigStore` with the file configuration as the canonical base.

## Evidence

- `research/scala/security-research.md` §4 (audit and masking behaviour, rule by rule), §5
  "Audit topic tampering", ADR-023 candidate.
- `research/kouncil/architecture.md` D9; `research/kafbat/feature-matrix.md` D-6.
- `research/provectus/diff.md` open question 2 (default `ALTER_ONLY` kept).

## Consequences

- Authorization, read-only enforcement and audit cannot disagree about what was accessed.
- Masking needs the principal only when group-scoped rules exist; the message service
  already receives it (ADR-020).

## Alternatives rejected

- Database-backed policies (Kouncil): a relational store for a handful of rules; rejected
  with ADR-036.
- Masking in the gateway: payloads would cross a process boundary unmasked.

## Reversibility

High.
