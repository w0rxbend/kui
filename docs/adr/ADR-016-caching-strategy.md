# ADR-016 — Caching strategy and staleness contracts

- Status: Accepted
- Date: 2026-09-03

## Context

Kafbat caches everything process-locally (statistics snapshot, AdminClient pool, cursors,
filters, SR subjects) with a mix of plain maps, Guava and Caffeine, mostly without TTL. The
project's caching rules allow caches only with TTL, invalidation, bounds, metrics and a staleness contract.

## Decision

- Two cache primitives in `libs/cache`:
  - `SnapshotCell[F, A]`: `Ref`-backed single value with `status`, `scrapedAt`, atomic
    replacement, `refresh` under a `Supervisor`, `Stale` reads while the upstream fails.
    Used for every per-cluster snapshot (ADR-027).
  - `BoundedCache[F, K, V]`: Caffeine 3.2.4 `AsyncCache` wrapped in `F` (≈40 lines; Scaffeine
    is not used) with max size and TTL. Used for schema-by-id, compiled CEL filters, gateway
    session cache, OAuth tokens for registries.
- Every cache declares: TTL, invalidation trigger, bound, `kui.cache.hits/misses{cache}` and
  its staleness contract in `ARCHITECTURE.md` §9. Adding a cache requires adding a row there.
- Never cached: secrets, message payloads, ACL lists (live with a bounded timeout).
- Cursors are not a cache (ADR-026); KSQL pipes are a single-use TTL store, not a cache.
- Caches are per process; nothing is replicated. Multi-replica correctness comes from
  stateless or signed tokens, not shared caches.

## Evidence

- `research/kafbat/architecture.md` F9 (inventory of Kafbat caches and their gaps), F4.
- `research/scala/ecosystem-mapping.md` F9 (Caffeine 3.2.4; Scaffeine dropped).

## Consequences

- Reads are fast and predictable; writes update snapshots incrementally where cheap.
- Operators get a documented "how old can this be" per screen.

## Alternatives rejected

- Distributed cache (Redis): a stateful dependency for data Kafka already holds.
- Scaffeine: two years without release; thin facade not worth a dependency.

## Reversibility

High. Both primitives sit behind small traits.
