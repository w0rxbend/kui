# ADR-008 — Structured logging with log4cats over Logback; Fabric dropped

- Status: Accepted
- Date: 2026-09-03

## Context

The original technology plan named "log4cats + Fabric structured logger". Research established that
Fabric is a JSON AST library (the value model of the `scribe` logger), not a logging model,
and would be a second JSON AST next to Circe.

## Decision

- `log4cats-core` 2.8.0 `StructuredLogger[F]` as the only logging API in Scala code;
  `log4cats-slf4j` in deployables over **Logback 1.6.3** with **logstash-logback-encoder 9.0**
  producing JSON lines.
- Standard context keys (`correlation.id`, `user.id`, `cluster.id`, `service.name`,
  `operation`) are set through `StructuredLogger` context maps, which become SLF4J MDC;
  trace and span ids are bridged into MDC from the otel4s span context by a small wrapper in
  `libs/observability`.
- Richer structured values are Circe-encoded at the call site (`json.noSpaces`).
- **Fabric is dropped** and must not be added.
- Log format is configurable (`kui.telemetry.logFormat = json | text`), JSON by default in
  containers.

## Evidence

- `research/scala/ecosystem-mapping.md` F5 (what Fabric is; recommendation), F4 (versions).
- The project's rule against two libraries for the same responsibility.

## Consequences

- One logging abstraction and one JSON AST across the codebase.
- Secret redaction is enforced by `Secret[A]`'s `toString`, not by the log encoder.

## Alternatives rejected

- scribe (with Fabric): a second logging ecosystem, not SLF4J-native for Java libraries' logs.
- Direct SLF4J calls: not effect-aware; log4cats gives `F[_]` logging with context.

## Reversibility

High. Logging calls are behind one trait; the backend is a runtime dependency.
