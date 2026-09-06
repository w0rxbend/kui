# ADR-016 — Caching strategy and staleness contracts

- Status: Accepted
- Date: 2026-09-03

## Context

Kafbat caches everything process-locally (statistics snapshot, AdminClient pool, cursors,
filters, SR subjects) with a mix of plain maps, Guava and Caffeine, mostly without TTL. The
project's caching rules allow caches only with TTL, invalidation, bounds, metrics and a staleness contract.

## Decision

- Three cache primitives in `libs/cache`:
  - `SnapshotCell[F, A]`: `Ref`-backed single value with `status`, `scrapedAt`, atomic
    replacement, `refresh` under a `Supervisor`, `Stale` reads while the upstream fails.
    Used for every per-cluster snapshot (ADR-027).
  - `BoundedCache[F, K, V]`: Caffeine 3.2.4 `AsyncCache` wrapped in `F` (≈40 lines; Scaffeine
    is not used) with max size and TTL. Used for schema-by-id, compiled CEL filters, gateway
    session cache, OAuth tokens for registries.
  - `SeriesWindow[A]` and its `Ref`-backed holder `SeriesWindowCell[F, A]`: many samples of
    one value over time, filed into buckets of `step` and bounded by both `maxAge` and
    `maxSamples`, with the same `kui.cache.*` counters as the other two. It retains every
    history this product draws — the throughput and p99 charts, the stat-card sparklines,
    the controller-uptime window — and it refuses three ways, because each of those figures
    would otherwise be printed wrongly: a bucket nobody sampled is absent rather than `0`,
    since a `0` is a measurement; a window collecting for less than the period asked for
    answers `None` rather than computing four minutes and labelling it a day; and a window
    whose samples have all been evicted counts a miss, because an answer that is nothing
    but gaps is a refusal spelt as a vector, and counting it as a hit is how a collector
    dead for hours reads as a healthy cache.
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

High. Each primitive sits behind a small trait; `SeriesWindow`'s arithmetic is an
immutable value that `SeriesWindowCell` only holds and instruments.
