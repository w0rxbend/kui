# HTTP-004 — `libs/http`: SSE helpers and heartbeat discipline

- **ID:** HTTP-004
- **Title:** `libs/http`: SSE helpers and heartbeat discipline
- **Milestone / Feature:** M0 / KU-001, KU-014 (kernel half)
- **Owner role:** Principal Scala Engineer
- **Context / service:** `libs/http`
- **Size:** M
- **Dependencies / blocked by:** HTTP-001, KERN-005, BUILD-006 (spike 1 must have answered)

## Goal (user value)

Streams behave identically everywhere: they say why they ended, they survive an idle proxy,
they stop consuming resources the moment the browser goes away, and a failure halfway through
is an `error` event rather than a truncated connection.

## Scope

1. `Sse.stream(events, heartbeat)` — wraps an `fs2.Stream[F, SseEvent]` into the Tapir
   `serverSentEventsBody` shape with: a heartbeat comment/event every 15 seconds while idle,
   at-most-one terminal event enforcement, and `id:` passthrough for cursors.
2. `SseEvent` server-side model plus encoders for the shared events of KERN-005
   (`done`, `error`, `heartbeat`) and a generic `data` case for domain events.
3. Cancellation guarantees: when the client disconnects, the source stream is cancelled within
   one element; a `guarantee` hook lets the caller close resources (in M3 a Kafka consumer, in
   M0 a registry subscription).
4. Backpressure: a bounded queue (default 256 events) between producer and writer; on overflow
   the policy is **drop-oldest with a counter**, never unbounded buffering (PLAN §28).
5. `kui.stream.active` and `kui.stream.events` metrics wired here so every stream in every
   milestone is measured without extra code.

## Non-goals

No client-side SSE (UI-006 owns the browser wrappers). No message/phase/consumed events (M3).
No `Last-Event-ID` cursor validation (M3 owns cursors; M0 only passes the header through to
the caller).

## Design references

ADR-035 (named events, exactly one terminal event, heartbeat every 15 s idle, cancellation
chain), ADR-003 (`serverSentEventsBody` with fs2), `ARCHITECTURE.md` §7, PLAN §28,
`docs/spikes/M0-netty-sse.md` (BUILD-006 spike 1 — if the spike failed, this task implements
the same API over the documented http4s-ember fallback and the change is confined to
`KuiServer`).

## Files to create

```
libs/http/src/kui/http/sse/Sse.scala
libs/http/src/kui/http/sse/SseEvent.scala
libs/http/test/src/kui/http/sse/SseSuite.scala
libs/http/test/src/kui/http/sse/SseCancellationSuite.scala
```

## Public Scala signatures to implement

```scala
package kui.http.sse

final case class SseEvent(
    name: String,                 // SseEventName.* from contracts-core
    data: io.circe.Json,
    id: Option[String] = None     // the signed cursor, when the stream has one
)

object SseEvent:
  def done(reason: DoneReason, cursor: Option[String]): SseEvent
  def error(envelope: ErrorEnvelope): SseEvent
  val heartbeat: SseEvent

final case class SseConfig(
    heartbeatInterval: FiniteDuration = 15.seconds,
    bufferSize: Int = 256,
    rateLimit: Option[Int] = None            // events per second; used by tailing in M3
)

object Sse:
  /** Adds heartbeats while idle, enforces at most one terminal event, applies the bounded
    * buffer and records the stream metrics. The returned stream is what a Tapir endpoint's
    * `serverSentEventsBody` output receives. */
  def stream[F[_]: Temporal](
      source: Stream[F, SseEvent],
      config: SseConfig,
      streamName: String,
      telemetry: Telemetry[F]
  ): Stream[F, SseEvent]

  /** Turns a failed effect into a terminal `error` event instead of a broken connection. */
  def withErrorEvent[F[_]: Sync](
      source: Stream[F, SseEvent],
      correlationId: CorrelationId
  ): Stream[F, SseEvent]
```

## Library coordinates

None new (fs2, tapir-netty-server-cats, circe already present).

## Acceptance criteria

```
$ ./mill libs.http.test.testOnly 'kui.http.sse.*'
$ curl -N localhost:8080/api/v1/capabilities/stream    # (after GW-005) shows named events
                                                       # and a heartbeat every 15 seconds
```

Wire format, asserted byte-for-byte in a golden test:

```
event: heartbeat
data: {}

event: done
id: eyJ2IjoxfQ.abc
data: {"reason":"exhausted","cursor":"eyJ2IjoxfQ.abc"}

```

(each event terminated by a blank line, `data` last, UTF-8, no BOM)

## Tests required

- `SseSuite` (unit, `TestControl`):
  - `emitsAHeartbeatAfterFifteenIdleSecondsAndNoneWhileBusy`.
  - `emitsExactlyOneTerminalEventEvenWhenTheSourceEmitsTwo` — the second is dropped and a
    WARN is logged.
  - `aFailedSourceBecomesAnErrorEventThenEnds`.
  - `idFieldIsRenderedOnlyWhenPresent`.
  - `goldenWireFormat` — the byte-for-byte assertion above.
  - `bufferOverflowDropsOldestAndIncrementsTheCounter` — a slow consumer with 1000 events.
- `SseCancellationSuite` (concurrency, integration on a bound port):
  - `clientDisconnectCancelsTheSourceWithinOneElement` — the source registers a `guarantee`
    that sets a flag; the test asserts the flag within 1 second of closing the connection.
  - `serverShutdownEndsOpenStreamsCleanly`.
  - `streamActiveGaugeReturnsToZeroAfterDisconnect` — the leak detector.

## Observability

`kui.stream.active {service, stream}` incremented on subscribe and decremented in a
`guarantee` (so a leak shows as a gauge that never returns to zero);
`kui.stream.events {service, stream, event}` counted per emitted event name.

## Degraded behavior

A stream that cannot start (validation or permission failure) never opens: it returns an
ordinary HTTP error envelope (ADR-035). A stream that fails after starting ends with a
terminal `error` event carrying a retryable flag, so the browser can decide whether to
reconnect.

## Docs to update

`ARCHITECTURE.md` §7: link the golden wire-format test as the normative example.

## Deviations

1. **`Sse.stream` takes an explicit `StructuredLogger[F]`.** The spec's signature omits it, but the
   at-most-one-terminal-event rule needs somewhere to say "a second terminal event was dropped", and
   a silent drop would be a stream that quietly loses data with no trace of why. Passing the logger
   explicitly, rather than reaching for a global, keeps the module free of any assumption about how
   a caller constructs its logger.

2. **`atMostOneTerminal` runs before the bounded buffer, not after it.** The rule reads whole chunks
   to decide whether a caller emitted two terminal events in the same batch; after the buffer, events
   arrive one at a time and that batch boundary is gone. Buffering absorbs a slow reader and its
   drop-oldest policy is safe in this order specifically because the terminal event, once produced,
   is always the newest thing in the queue — it is never the one thrown away.

3. **A dropped terminal-event duplicate is reported as a WARN naming a count, not the event
   name.** `atMostOneTerminal` operates on whole `fs2.Chunk`s for efficiency, so what it can cheaply
   report is "N events after the terminal one", not each one individually. The spec's wording ("the
   second is dropped and a WARN is logged") is satisfied; the log field is `dropped`, not `event`.

4. **`errorFor`'s `KuiError` construction for `withErrorEvent` needs no upstream detail**, matching
   ADR-034's rule that a stack trace never leaves the process: the fixed message `Internal error` is
   used, exactly as `ErrorInterceptor` uses it for an ordinary HTTP 500.

5. **`Sse.body[F]` returns `StreamBodyIO[Stream[F, Byte], Stream[F, Byte], Fs2Streams[F]]`, not
   `EndpointOutput[Stream[F, Byte]]`** as sketched. That is what `streamTextBody` actually produces
   in tapir 1.13.31, and it composes with `.errorOut(...)` the same way an `EndpointOutput` would; a
   caller never notices the difference.

6. **The cancellation suite measures four things, one more than the spec's three** (in addition to
   `clientDisconnectCancelsTheSourceWithinOneElement`, `serverShutdownEndsOpenStreamsCleanly` and
   `streamActiveGaugeReturnsToZeroAfterDisconnect`): a fourth case serves a real, finite stream
   end-to-end and asserts the bytes match the golden wire format produced by a live server, not only
   by `SseEvent.render` in isolation.
