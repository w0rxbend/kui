# ADR-038 — Name search: in-memory index first, Lucene deferred

- Status: Accepted
- Date: 2026-09-03

## Context

Kafbat offers full-text (n-gram) search over topic, consumer group, schema, connector and
ACL names through Lucene 10 (JDK 21+) with a per-resource feature flag, falling back to
substring matching. The data sets are hundreds to low tens of thousands of names held in
memory.

## Decision

- `libs/kernel` provides `NameIndex` (prefix, substring and trigram scoring, case-insensitive)
  built inside each per-context snapshot (ADR-027). List endpoints accept `q` and
  `mode = plain | fts`; `fts` uses trigram scoring, `plain` uses substring. The cluster
  capability set advertises `FTS_ENABLED` per resource family so the UI can render the same
  toggle as Kafbat; defaults follow Kafbat's `fts.enabled`/`fts.defaultEnabled` keys.
- Lucene is **deferred**: it is adopted only if a benchmark in `docs/benchmarks/` on ≥ 50 000
  names shows p95 > 50 ms for the in-memory index, and then behind the same `NameIndex` trait.
- Property tests cover ranking stability and the equivalence of `plain` with a naive
  substring filter.

## Evidence

- `research/scala/ecosystem-mapping.md` F9 (Lucene 10 JDK 21, data size, recommendation).
- `research/kafbat/architecture.md` F14; `research/kafbat/api-analysis.md` Finding 2
  "Full-text search"; `research/kafbat/feature-matrix.md` TP-2/SF-2 deferred.

## Consequences

- No Lucene dependency and no JDK coupling from search in M0–M5.
- Search quality is "good enough" by construction and measured before changing.

## Alternatives rejected

- Lucene from the start: a heavy dependency for a few thousand strings.

## Reversibility

High.
