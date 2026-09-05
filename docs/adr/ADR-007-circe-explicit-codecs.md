# ADR-007 — Circe with explicit codecs at the contract layer

- Status: Accepted
- Date: 2026-09-03

## Context

KUI needs one JSON library for contracts (JVM and JS), configuration files, dynamic message
payloads and structured log values. The project's data-modeling rules forbid `Json` in domain modules.

## Decision

- **Circe 0.14.16** (`core`, `parser`, `generic` for semi-automatic derivation only) in every
  `contract` module and in `libs/serde`, `libs/config`, `libs/contracts-core`.
- Codecs are explicit `given Codec[A]` values in the contract module next to the DTO; enum
  wire forms are Kafka's own strings (`read_committed`, `PREPARING_REBALANCE`); opaque
  kernel ids get one codec each in `libs/contracts-core`. No `auto` derivation.
- Iron 3.3.2 (`iron-circe`, `tapir-iron`) refines DTO fields where the constraint is
  structural (non-empty, ranges); domain invariants stay in domain smart constructors.
- Circe `Json` is the only dynamic payload type: serde results, masking, config wizard
  payloads, connector configs. It is forbidden in `domain` modules.
- Numbers keep 64-bit precision on the server, because Circe's `JsonNumber` is string-backed.
  This guarantee stopped at the browser with ADR-048: the browser is TypeScript, it parses with
  `JSON.parse`, and a `Long` therefore arrives as a double. Offsets are typed `number` in the
  generated schema, so a value above 2^53 would lose precision. This is recorded rather than
  resolved: whether any KUI field can reach that magnitude has not been audited since the change.

## Evidence

- `research/scala/ecosystem-mapping.md` F4 (0.14.16, Tapir built against it; 0.15.0-M1 exists),
  F5 (single JSON AST rule; Fabric dropped).
- `research/scala/frontend-research.md` §5 (`lossless-json` motivation in Kafbat and why
  Circe on JS removes the need).

## Consequences

- Circe 0.15 migration is a later task; contracts do not use 0.15-only features.
- DTO ↔ domain mapping is a separate step (Chimney, ADR-033), so DTO codecs never touch domain
  types.

## Alternatives rejected

- uPickle / jsoniter-scala: faster, but Tapir, Ciris-yaml and the ecosystem KUI uses are
  Circe-first; two JSON ASTs violate the project's rule against two libraries for the same responsibility.
- Automatic derivation: hides codec changes in diffs and breaks the "wire form is explicit" rule.

## Reversibility

Medium. Codecs are localized in contract modules; changing the AST used by serdes and config
would touch several libraries.
