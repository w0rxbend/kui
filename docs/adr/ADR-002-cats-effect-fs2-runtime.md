# ADR-002 — Cats Effect 3 and FS2 as the single runtime

- Status: Accepted
- Date: 2026-09-03

## Context

Kafbat runs on Spring WebFlux and Project Reactor with blocking Kafka calls pushed to a
bounded-elastic scheduler. KUI needs one effect system for servers, clients, Kafka streams,
background refresh and cancellation that propagates from the browser to a Kafka consumer.

## Decision

- Cats Effect **3.7.1** `IO` as the only runtime; Cats 2.13.0; FS2 **3.13.0** for every stream.
- Ports and shared libraries are written against `F[_]` with the weakest bound that works
  (`Async`, `Temporal`, `Concurrent`); `IO` appears only in `app` modules and tests.
- No monad transformers across module boundaries; `EitherT` may be used locally inside one
  use case. Application code returns `F[Either[KuiError, A]]`.
- Every client, server, exporter and refresh loop is a `Resource`; background work runs under
  a `Supervisor`; blocking Java calls go through `Sync[F].blocking`.
- Direct-style Scala (Ox/Gears) is not introduced as a second concurrency runtime. The
  `/direct-style-scala` permission in PLAN §11 is interpreted as "readable sequential code
  inside a single `IO` program", not as a second runtime.

## Evidence

- `research/scala/ecosystem-mapping.md` F2 (versions, CE3 compatibility of fs2-kafka 4,
  Tapir, otel4s, log4cats, Ciris).
- `research/kafbat/architecture.md` F3 item 5 and F5 (Reactor scheduler hopping; emitter
  cancellation via `sink.isCancelled`), which map naturally to fiber cancellation.
- `research/kafka/admin-capabilities.md` §0 "Single I/O thread" (never block the admin client
  thread; `KafkaFuture` bridging) and §4 "Cancellation".

## Consequences

- All Kafka, HTTP and telemetry libraries must be CE3-native or wrapped once in `libs/kafka`,
  `libs/http`, `libs/observability`.
- Testing uses `munit-cats-effect` (ADR-018).
- The Scala.js side does not use Cats Effect for UI state (Airstream, ADR-011); the shared
  `contract` modules depend only on Tapir core and Circe, not on CE.

## Alternatives rejected

- ZIO: a second effect ecosystem for the same job; Tapir supports both but fs2-kafka, otel4s
  and Ciris are Typelevel-first.
- Ox / direct style as the runtime: PLAN §11 forbids a second runtime; the ecosystem KUI
  relies on is CE-based.

## Reversibility

Costly. Effect choice permeates every module; this is a foundational decision.
