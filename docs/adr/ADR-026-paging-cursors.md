# ADR-026 — Paging: offset pages for sorted lists, opaque signed cursors for streams

- Status: Accepted
- Date: 2026-09-03

## Context

Kafbat pages messages with a random 8-character cursor id stored in a process-local Guava
cache (10 000 entries, no TTL); an evicted or foreign-instance cursor fails the request.
Sorted lists (topics, groups, schemas, connectors) are computed fully in memory per request
and return `pageCount` only, with a known off-by-filter bug.

## Decision

- **Sorted lists**: offset paging `page`/`pageSize` (default 25, max 500) with `sort=<field>:<asc|desc>`,
  `q` and `mode=plain|fts`; response `Page{items, page, pageSize, totalItems}` computed
  after all filters. Sources are the per-context snapshots, so a page costs no admin call.
  Connectors gain paging (Kafbat has none).
- **Message browsing, event tracking, audit**: opaque **signed cursors** carried in the SSE
  `done` event and `id:` field and accepted as `cursor` or `Last-Event-ID`:
  `base64url(payload) "." base64url(HMAC-SHA256(payload, cursorKey))` with payload
  `{v, cluster, topic, direction, perPartitionNextOffset, filterId?, keySerde?, valueSerde?,
  limit, isolation, exp}`. Forward cursors carry `lastOffset + 1`, backward carry the next
  `until`. Default expiry 1 h; bound to cluster and topic; rejected with
  `KUI-CURSOR-EXPIRED` or `KUI-CURSOR-INVALID`.
- `cursorKey` comes from `kui.streaming.cursorKey` (`Secret`), shared by all replicas of a
  service; generated at startup when absent (single-replica and all-in-one).
- A cursor request may still carry `filterSource` so a replica can recompile a smart filter
  (ADR-017); everything else is taken from the cursor.
- Kouncil's per-partition page mode needs no cursor: `page`, `pageSizePerPartition`, optional
  window, computed from current end offsets (documented as shifting while producers write).

## Evidence

- `research/kafbat/architecture.md` F5 "Paging cursors", D4; `research/kafbat/api-analysis.md`
  Finding 5.2 "Cursor paging", Finding 4, decision rows on cursors and offset paging.
- `research/kouncil/architecture.md` D2 (page model without server state).

## Consequences

- Cursor size grows ~20 B per partition; a 1 000-partition topic yields a ~20 KB cursor,
  still acceptable in a query string; above that the service falls back to `KUI-CURSOR-TOO-LARGE`
  with a per-partition-subset hint (tracked in TECH_DEBT).
- No sticky sessions, no shared store.

## Alternatives rejected

- Sticky routing at the gateway: couples deployment to a feature; fails on restart.
- Shared cursor store: a stateful dependency for ephemeral data.

## Reversibility

Medium. Cursor format is versioned (`v`); the contract exposes it as opaque.
