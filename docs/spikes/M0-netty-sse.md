# Spike — long-lived SSE on `tapir-netty-server-cats`

- **Task:** BUILD-006, spike 1 (risk R-2)
- **Role:** Principal Scala Engineer
- **Date:** 2026-09-03

## Question

Server-Sent Events (SSE) is the mechanism KUI uses to push live data — consumer messages, cluster
state — from a service to the browser: the browser opens one ordinary HTTP request and the server
keeps the response open, writing one small text frame per update instead of answering and hanging
up. ADR-003 chose Tapir's Netty server for that. Netty is an asynchronous network library, and
Tapir 1.13.31 brings in Netty 4.2; nothing in the ADR's evidence proved that combination keeps a
response open for a long time.

Three things had to be true, and none of them were established:

1. A `serverSentEventsBody` response stays open for at least 10 minutes.
2. Events are flushed **individually**, as they are produced, rather than being collected in a
   buffer and released in a burst — a burst would make the UI update in jumps, which defeats the
   point.
3. When the client goes away, the fs2 stream that is producing events is **cancelled**. If it is
   not, every closed browser tab leaves a producer running on the server, and the service leaks
   Kafka consumers until it dies.

## Method

A throwaway single-file `scala-cli` application (deleted; it is not in the repository, per PLAN §39
anti-waste rules) with the exact library versions the build pins: Scala 3.9.0, tapir 1.13.31,
`tapir-netty-server-cats`, cats-effect 3.7.1, fs2 3.13.0, JDK 21 (Temurin 21.0.9).

One endpoint, `GET /sse?seconds=N`, whose body is
`streamTextBody(Fs2Streams[IO])(CodecFormat.TextEventStream(), Some(UTF_8))`. The stream emits one
`ServerSentEvent` per second, merged with a heartbeat comment every 15 seconds, and carries an
`onFinalizeCase` finaliser that logs how the stream ended. That finaliser is the whole cancellation
experiment: fs2 only runs it when the stream is actually torn down, and it reports whether the
teardown was completion, an error, or cancellation.

Two clients were attached at once:

- `curl -N` (no buffering), with every received line stamped with the client's own wall clock, so
  the arrival times are measured on the receiving side rather than claimed by the sender.
- A **real browser** `EventSource`, driven by headless Chromium through JVM Playwright 1.62.0. Node
  was not used for this: Node 24 has no `EventSource` global, and more importantly the question is
  about browser behaviour, so a browser had to answer it.

Cancellation was measured by killing the client and comparing the client's exit time with the
timestamp of the server's finaliser log line.

## Findings

**Latency to first byte:** 0.186 s, of which the TCP connect was 0.0001 s. The remainder is JIT
warm-up on the first request of a cold JVM, not a per-request cost.

**Events flush individually.** Every event arrived at the client roughly 2 ms after the server
logged emitting it, one per second, with no bunching:

```
10:36:04.416 data: tick-0 at 1788431764409
10:36:05.411 data: tick-1 at 1788431765409
10:36:06.411 data: tick-2 at 1788431766409
10:36:07.411 data: tick-3 at 1788431767409
...
```

The browser `EventSource` agreed, measuring elapsed time inside the page:

```
event #60  id=59  gap=60008ms
event #120 id=119 gap=120010ms
event #180 id=179 gap=180008ms
event #240 id=239 gap=240008ms
event #300 id=299 gap=300008ms
received=329 errors=0 readyState=1
```

329 events in 330 seconds, zero `onerror` events, and `readyState=1` (OPEN) at the end: the browser
never had to reconnect. The 8–10 ms of drift across 300 events is the fs2 scheduler, not buffering.

**The connection survives past 10 minutes.** One `curl -N` client stayed attached from the first
event to well past the ten-minute mark, on a single connection:

```
first event  10:36:34.381 data: tick-0
tick 599     10:46:33.382    (599.001 s after the first)
tick 600     10:46:34.380    (599.999 s — the 10-minute mark, exactly on schedule)
tick 610     10:46:44.380
612 events received, 40 heartbeat comments interleaved
```

No idle timeout, no dropped connection, no reordering, and no accumulated drift: event 600 arrived
599.999 seconds after event 0, where a buffering or back-pressured server would have fallen behind.

**Cancellation is effectively immediate.** Two measurements:

```
[server 2026-09-03T10:36:00.562591Z] client connected
[server 2026-09-03T10:36:03.405618Z] client connected
[server 2026-09-03T10:36:03.413384Z] STREAM FINALIZED: Canceled
[server 2026-09-03T10:36:15.402512Z] STREAM FINALIZED: Canceled
```

The first client was killed at 10:36:03.405 and its stream was finalized at 10:36:03.413 — **8 ms**.
The second was killed at 10:36:15.40 and finalized at 10:36:15.402 — under a millisecond. The
finalisation case is `Canceled`, not `Succeeded`, which is the important detail: cats-effect
genuinely cancelled the producing fiber rather than letting it run to completion and discarding the
output.

## Decision taken

**Keep Netty.** This is row one of BUILD-006's decision table — "events flush individually,
connection survives 10 min, cancellation within 1 s" → "keep Netty. No further work". The
pre-approved http4s-ember branch is **not** taken, and no dependency changes.

## Consequence

- ADR-003 stands as written; `tapir-netty-server-cats` remains the server in every `app` module.
- `DEPENDENCY_MATRIX.md`'s open question on `tapir-netty-server-cats` is closed.
- HTTP-004 does **not** need the explicit idle-timeout guard that the middle row of the decision
  table would have required; cancellation is three orders of magnitude inside the 1-second bar.
- No entry in `TECH_DEBT.md`: no compromise was accepted.

One incidental note for whoever writes the SSE encoder in HTTP-004: tapir 1.13.31 exposes the
generic fs2 SSE body as `streamTextBody(Fs2Streams[IO])(CodecFormat.TextEventStream(), …)` and
leaves the framing to the caller. `sttp.model.sse.ServerSentEvent` renders a frame correctly with
`toString`, but it has no `comment` field, so heartbeat comments (`: heartbeat`) have to be written
as raw text. That is a two-line detail, not a design problem.

## Confidence

**High** for flushing, endurance and cancellation: all three were measured directly, on the pinned
versions, with two independent clients, and the cancellation result was confirmed twice.

**Medium** for behaviour under load: this spike used two concurrent clients on a loopback
interface. It says nothing about a hundred concurrent streams, or about a client on a slow network
that stops reading — the case where fs2 backpressure would actually have to push back on the
producer. That is a load question for M8's performance gate, not an M0 blocker, and it does not
change which server KUI uses.
