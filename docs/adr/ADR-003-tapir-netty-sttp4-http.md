# ADR-003 — Tapir 1.13.31 with Netty (cats) server and sttp 4 clients

- Status: Accepted
- Date: 2026-09-03

## Context

Every service, the gateway and the browser share one HTTP contract. Kafbat generates server
stubs and a TypeScript client from TypeSpec; KUI wants the contract to be Scala code, written
once and used by every JVM caller directly. (When this was decided the browser was Scala.js and
read the same code; since ADR-048 the browser is TypeScript and gets its types generated from the
committed OpenAPI documents these same endpoint definitions produce.)

## Decision

- **Tapir 1.13.31**: `tapir-core` + `tapir-json-circe` + `tapir-iron` in every
  `contract` module; `tapir-netty-server-cats` in every `app`; `tapir-openapi-docs` per
  service and merged at the gateway with `tapir-swagger-ui-bundle`; `tapir-otel4s-tracing` and
  `tapir-opentelemetry-metrics` as server interceptors; `tapir-files` for static assets.
- **sttp client 4.0.26** with `tapir-sttp-client4` (not the sttp 3 binding): `HttpClientFs2Backend`
  on the JVM (gateway → services, services → registries). There is no browser-side sttp backend
  any more: since ADR-048 the browser calls the API with `fetch` through `@kui/api`.
- One `contract` module per service, one Tapir endpoint group per resource family, mirroring
  Kafbat's TypeSpec file organisation; an OpenAPI style check (camelCase, naming rules) runs
  on the merged document in CI.
- Streaming endpoints use `serverSentEventsBody` with fs2 streams (ADR-035).
- Health, readiness and `/capabilities` endpoints come from `libs/http` and are mounted by
  every service.

## Evidence

- `research/scala/ecosystem-mapping.md` F3 (1.13.31 confirmed latest; `tapir-sttp-client4`
  vs `tapir-sttp-client`; Netty 4.2 transitive; module list).
- `research/kafbat/architecture.md` D1 and F1 (controller-implements-contract invariant).
- `research/provectus/diff.md` D3 (contract per resource family + style check).
- `research/scala/frontend-research.md` §3.6 (sttp4 `FetchBackend`, `scala-java-time` on JS).

## Consequences

- No hand-written route in the gateway; routes are derived from contract endpoint metadata.
- `contract` modules must stay free of JVM-only dependencies.
- A spike in M0 validates long-lived SSE responses on `tapir-netty-server-cats` (open question
  in `research/scala/ecosystem-mapping.md`); http4s-ember is the documented fallback server if
  Netty SSE proves unreliable, reachable by swapping only `app` modules.

## Alternatives rejected

- http4s-ember as the primary server: Tapir supports it equally, but Netty was chosen for
  throughput; kept as fallback.
- OpenAPI codegen (TypeSpec or YAML first): duplicates the contract outside Scala and loses
  cross-compilation.

## Reversibility

Medium. Server/client backends swap at `app` level; leaving Tapir itself would be a rewrite of
all contracts.
