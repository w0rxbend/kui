# ADR-029 — Event tracking semantics and the table-browse page mode

- Status: Accepted
- Date: 2026-09-03

## Context

Kouncil's "track" is a time-bounded multi-topic scan with one predicate (header or value,
`contains | notContains | equals | notEquals | regex`), streamed over STOMP with a 1 000-event
cap and an empty array as end marker. The parity target makes it a first-class KUI feature; the
project's streaming rules forbid unbounded consumption and standardize on SSE.

## Decision

- Endpoint `GET /api/v1/clusters/{id}/events/track/stream` (SSE, ADR-035) and a synchronous
  capped variant `GET .../events/track`. Parameters: `topics[]` (1..N), mandatory time window
  `from`/`to`, `match{source: value | header(name) | key, operator: contains | notContains |
  equals | notEquals | regex, value}` **or** `filterId`+`filterSource` (CEL), `limit`
  (default 1 000, server max), `isolation`.
- Execution in `kui-message-service`: resolve `[offsetForTime(from), offsetForTime(to))` per
  partition per topic, scan topics smallest-range-first with one fs2 stream, emit `message`
  events sorted by timestamp per batch, `consumed` progress, and a terminal `done{reason}`;
  the same `PollBudget` (records, bytes, deadline, throttle) as browsing applies; `regex`
  runs with a match timeout. Cancellation on client disconnect.
- KUI extension: optional `correlationKey` (header name or JSON path) adds a `group` field to
  each event so the UI can group a business event across topics; no server-side join.
- Permission: `Topic.MessagesRead` on every listed topic; audit-topic rule applies.
- Companion decision — **per-partition page mode** for the table view:
  `GET /topics/{topic}/messages/page?partitions&page&pageSizePerPartition&from&to&offset`
  returns `{items, partitionOffsets, partitionEndOffsets, totalItems}` computed newest-first
  from current end offsets (Kouncil semantics), non-streaming, no server state. JSON
  flattening (`H[]/K[]/V[]`, depth 3, collapse thresholds, 1 000-row cap) is client-side in
  `frontend/ui-messages`.
- Resend (`POST /topics/{topic}/messages/resend`) copies an offset range of one partition to a
  destination topic with header filtering, validated against begin/end offsets; placeholder
  templating for bulk produce is a frontend feature.

## Evidence

- `research/kouncil/architecture.md` F3, F4, D1–D4; `research/kouncil/ui-analysis.md`
  DC-H8, DC-H11; `research/kafbat/api-analysis.md` Finding 10 and mapping rows;
  `research/kafbat/feature-matrix.md` D-1, D-2, D-4; `research/kafbat/ui-analysis.md` DC-H4.

## Consequences

- Long scans hold an SSE connection; heartbeats and budgets bound them.
- Two read paths in the message service (stream and page) sharing seek resolution.

## Alternatives rejected

- STOMP/WebSocket transport: one-directional push does not need it; SSE fan-in already exists.
- Server-side JSON flattening: keeps the service format-agnostic; revisit only for column
  projection on very large payloads.

## Reversibility

High.
