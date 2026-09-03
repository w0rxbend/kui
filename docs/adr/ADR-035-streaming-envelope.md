# ADR-035 — Streaming envelope: named SSE events with `error` and `heartbeat`

- Status: Accepted
- Date: 2026-09-03

## Context

Kafbat streams `TopicMessageEvent{type: PHASE | MESSAGE | CONSUMING | DONE}` as unnamed SSE
`data:` lines; there is no error event — failures after headers are sent close the connection
abruptly — and no heartbeat; cursors travel inside `DONE`. KSQL results use a two-step
pipe because `EventSource` cannot POST.

## Decision

- All KUI streams (message browse, per-partition page is not a stream, event tracking, KSQL
  query results, capability changes, live metrics, topic-analysis progress) use
  `text/event-stream` with named events and JSON `data`:
  `phase`, `message`, `consumed`, `done{reason, cursor?}`, `error{code, message, retryable,
  correlationId}`, `heartbeat` (every 15 s idle). Domain-specific streams add their own
  data events (`capabilities`, `row`, `progress`) but reuse `error`, `done`, `heartbeat`.
- Exactly one terminal event (`done` or `error`) unless the client cancels; validation and
  permission failures before the stream starts are ordinary HTTP error responses (ADR-034).
- The SSE `id:` field carries the signed cursor (ADR-026); `Last-Event-ID` on reconnect is
  accepted where a cursor makes sense.
- Message events carry per-target decode results (`key{text, kind, serde, properties}`,
  `value{...}`) and `deserializeErrors[]`; a failed decode falls back to the String serde and
  never aborts the stream. `consumed` carries cumulative bytes/records/elapsed/filterErrors
  and remaining budget.
- Tailing has no `done`; UI-facing tailing is rate-limited (default 20 events/s) in the service.
- Cancellation: browser abort → gateway stream cancellation → service fiber cancellation →
  consumer close.
- KSQL keeps the two-step design (`POST /ksql/queries` → `queryId`, then SSE) with a 1 min
  single-use pipe; ksqlDB statement errors are `error` rows inside the table plus an `error`
  event on transport failure.
- Service → gateway transport is the same SSE format over HTTP chunked; the gateway re-streams
  without buffering beyond a bounded queue and honours backpressure.

## Evidence

- `research/kafbat/api-analysis.md` Finding 5.1 (no `ERROR` event; abrupt close), 5.4 (KSQL
  pipe), decision row "SSE with named events including `error` and `heartbeat`".
- `research/kafbat/architecture.md` F5, D4; PLAN §22, §28.
- `research/scala/frontend-research.md` §1.6, §3.6 (why Kafbat used `fetch-event-source`;
  kernel `Sse` wrappers).

## Consequences

- Native `EventSource` works for GET streams with cookie auth; the kernel's abortable fetch
  parser covers cancellation.
- `DeserializeResult.kind` reaches the client so the table view can flatten JSON.

## Alternatives rejected

- WebSocket for streams: bidirectionality is not needed; SSE reconnect and proxies are simpler.
- Unnamed events with a `type` field: loses `EventSource.addEventListener` routing and `id:`.

## Reversibility

High (additive events); the base envelope is a contract.
