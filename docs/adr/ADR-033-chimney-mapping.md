# ADR-033 — Chimney 2.0.0-RC1 for DTO ↔ domain mapping on Scala 3.9

- Status: Accepted
- Date: 2026-09-03

## Context

Contract DTOs and domain types are distinct by rule (PLAN §18); mapping between them is
mechanical and voluminous. Chimney 1.x emits ambiguous-given warnings on Scala ≥ 3.7, which
`-Werror` turns into errors; the 2.x line targets Scala 3.9 LTS and is at RC1.

## Decision

- **Chimney 2.0.0-RC1** in `application` modules for DTO ↔ domain and profile ↔ local
  value-object mapping; bump to 2.0.0 final when released.
- Usage rules: only `into[...].transform` with explicit field renames/overrides where names
  differ; no partial transformers for validation (domain smart constructors validate; Chimney
  maps already-validated data); opaque-type mappings are provided as explicit `Transformer`
  givens in `libs/contracts-core`.
- Hand-written mappers are the sanctioned fallback for any type where the macro output is
  unclear or where `-Werror -Wunused:all` reports noise from the `hearth` macros; such cases
  are listed in `TECH_DEBT.md`.
- If 2.0.0 final slips beyond M1 close or RC1 breaks under `-Werror`, `application` modules
  switch to hand-written mappers for the affected types without changing contracts.

## Evidence

- `research/scala/ecosystem-mapping.md` F8 (Chimney 1.x on Scala 3.7+, 2.0.0-RC1 targets 3.9.0
  LTS and JDK 17+, macros moved to `hearth`), decision candidate 8, open question on `-Werror`.

## Consequences

- One RC dependency in the matrix, tracked in `TECH_DEBT.md` with an exit condition.
- Mapping code stays declarative and reviewable.

## Alternatives rejected

- Chimney 1.11.0: needs a documented workaround for ambiguous givens on Scala 3.9; parked line.
- Hand-written mappers everywhere: acceptable but ~10 services × dozens of DTOs of boilerplate.
- Ducktape: less mature on Scala 3.9 at decision time; not evaluated in research.

## Reversibility

High. Mapping code is mechanical either way.
