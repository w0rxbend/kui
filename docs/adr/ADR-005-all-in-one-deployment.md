# ADR-005 — All-in-one deployment shape

- Status: Accepted
- Date: 2026-09-03

## Context

The project's deployment constraints require the same code to run as containers and as one JVM. The risk is two code
paths in the gateway (HTTP client vs direct call) that drift apart.

## Decision

- `apps/allinone` is a Mill module depending on every service's `application`,
  `infrastructure` and `api` modules and the gateway modules. One MacWire root, one `IO`
  runtime, one Netty listener, one otel4s provider.
- The gateway calls services through a `ServiceClient[F]` abstraction with two
  implementations: `SttpServiceClient` (distributed) and `InProcessServiceClient`, which
  interprets the same Tapir endpoints against the service's server logic in memory. Routing,
  RBAC pre-check, aggregation and the capability registry are shared code.
- Principal propagation uses `PrincipalCodec.inProcess` (no signature); services still call
  `Rbac.decide`. Session store, `RbacPolicy`, audit sink, config store and capability
  registry are single in-memory instances.
- Services do not bind their own ports in this shape. The Docker image `kui-allinone` and the
  Compose dev environment use it; E2E and fault-injection tests run against both shapes.
- Capability state for an in-process service is derived from its use-case results
  (`InfrastructureError` → `Unavailable`) so the degraded UX is exercised locally.

## Evidence

- `research/scala/security-research.md` §6.6 (all-in-one differences: no header, no own
  listeners, `NoopSigner`).
- `research/kafbat/architecture.md` D2 ("the all-in-one shape hides the hop").

## Consequences

- Every service's `app` module must expose a `wire` function returning its server logic
  without starting a server, so both `apps/allinone` and the service's own `main` reuse it.
- Memory footprint of the single JVM includes every optional SDK (Confluent, CEL, cloud auth);
  optional runtime modules stay optional through classpath profiles in the Docker image.

## Alternatives rejected

- All-in-one as a Compose of containers only: fails the project's requirement to support laptops and small installs.
- Direct method calls bypassing Tapir in all-in-one: creates the second code path this ADR
  exists to prevent.

## Reversibility

High. The in-process client is one adapter.
