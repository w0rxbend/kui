# ADR-017 — CEL as the only user-programmable message predicate

- Status: Accepted
- Date: 2026-09-03

## Context

Provectus evaluated Groovy scripts from users (arbitrary JVM code); Kafbat replaced them
with sandboxed CEL programs registered by id in a process-local cache. KUI must keep the
feature, keep it safe, and make it work across replicas.

## Decision

- **cel-java 0.14.0** behind `MessageFilterPort[F]` in `libs/filter`; no Groovy, no JS engine,
  no ANTLR grammar of our own.
- The CEL environment exposes Kafbat's `record` variable (`partition`, `offset`,
  `timestampMs`, `keyAsText`, `valueAsText`, `headers: map<string,string>`, `key`/`value` as
  `dyn` parsed from JSON when possible). Non-boolean results and runtime errors count as
  `filterErrors` in the `consumed` event and never abort the stream.
- Filter id = first 16 hex chars of `sha256(source)` (no process salt, so ids are stable
  across replicas). `POST /clusters/{id}/message-filters` compiles and returns the id; the
  browse and tracking requests accept `filterId` **and** `filterSource`; a replica that misses
  the id compiles the source on demand. Compiled programs live in a `BoundedCache`
  (10 000, TTL 1 h).
- `POST /clusters/{id}/message-filters/test` requires `TOPIC:MESSAGES_READ` on a topic of that
  cluster (Kafbat's endpoint has no check); it is a pure function over a synthetic record.
- Filter registration and testing are non-alter actions and allowed on read-only clusters.
- Compilation has a size and complexity limit; evaluation has a per-record time budget.

## Evidence

- `research/kafbat/architecture.md` F5 "Filters", D5; `research/provectus/diff.md` D2, F7.
- `research/kafbat/api-analysis.md` Finding 5.2 "Filters", decision "smart-filter test
  endpoint requires a cluster + topic permission".
- `research/scala/ecosystem-mapping.md` F9 (cel-java 0.14.0, ~10 MB transitive graph).

## Consequences

- `kui-message-service` carries guava/protobuf-java/re2j transitively through CEL; contained in
  `libs/filter`.
- Users migrating from Provectus must rewrite Groovy filters (already true for Kafbat).

## Alternatives rejected

- A Scala expression DSL: later research item; not a replacement until it matches CEL's
  semantics that users already know.
- Server-side stored filters: filters remain client-stored (localStorage) as in Kafbat.

## Reversibility

High. The port hides the engine; the id scheme is the only contract.
