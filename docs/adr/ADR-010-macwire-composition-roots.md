# ADR-010 — MacWire composition roots

- Status: Accepted
- Date: 2026-09-03

## Context

Kafbat relies on Spring's runtime dependency injection. PLAN §11 forbids reflection-based DI.
KUI needs one place per deployable where concrete adapters are chosen, plus a way to share
wiring between a service's own `main` and the all-in-one root.

## Decision

- **MacWire 2.6.7** (`macros` provided-scope, `util`) in `app` modules and `apps/allinone`
  only. No DI annotations, no runtime container.
- Each service's `app` module exposes `object <Name>Wiring { def make(config, deps): Resource[IO, <Name>Server] }`
  returning wired server logic without starting a listener; the service `main` and the
  all-in-one root both call it.
- Constructor injection everywhere; ports are trait parameters; `wire[...]` builds the
  graph; `Resource` composition handles lifecycle order.
- Test wiring uses hand-written fakes over ports (no mocking framework, ADR-018).

## Evidence

- `research/scala/ecosystem-mapping.md` F8 (MacWire 2.6.7, Scala 3 support, low churn expected).
- PLAN §10, §11, §14 ("kui-allinone wires every service's application layer").

## Consequences

- Wiring compile errors surface missing dependencies at build time.
- Optional adapters (cloud SASL handlers, Confluent serdes) are selected in `app` by config,
  not by classpath scanning.

## Alternatives rejected

- Manual wiring without MacWire: acceptable but verbose for ~10 services; MacWire is a
  compile-time macro with no runtime cost.
- Guice/Spring-style runtime DI: reflection, forbidden by PLAN §11.

## Reversibility

High. MacWire can be replaced by explicit constructor calls file by file.
