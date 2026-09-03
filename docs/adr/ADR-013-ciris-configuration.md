# ADR-013 — Configuration with Ciris (over PureConfig)

- Status: Accepted
- Date: 2026-09-03

## Context

PLAN §10 leaves the config library to research with the requirement: typed model, YAML file
plus environment override, Kafbat-compatible keys (`KAFKA_CLUSTERS_0_NAME`), secrets that
never print, accumulated validation errors.

## Decision

- **Ciris 3.15.0** with `ciris-circe-yaml` for YAML files and Iron (`iron-ciris`) for refined
  fields. Precedence: CLI flags → environment → YAML file(s) → defaults.
- The configuration model is an explicit Scala 3 model in `libs/config` (`KuiConfig` with
  `server`, `gateway`, `auth`, `rbac`, `clusters[]`, `telemetry`); every `ConfigValue` is
  written by hand, which is where the Kafbat env-key mapping lives (both `KUI_*` keys and the
  legacy `KAFKA_CLUSTERS_<i>_*` / `AUTH_*` / `RBAC_*` names, documented as accepted-and-forwarded).
- Secrets are `Secret[A]` (kernel type) with redacting `toString`, Circe encoder and log form;
  values may be resolved from `file://` and env references.
- Each process loads only its slice; dynamic sections have owners (ADR-036).
- Validation accumulates with `Validated`; startup fails with the full list.

## Evidence

- `research/scala/ecosystem-mapping.md` F6 (Ciris vs PureConfig comparison; relaxed env
  binding is a mapping, not a derivation), F8 (Iron 3.3.2).
- `research/provectus/diff.md` D4 (Kafbat config additions as the typed baseline).
- `research/scala/security-research.md` §5 "Secret leakage" (JAAS in raw properties).

## Consequences

- More hand-written loader code than a derivation-based library; in exchange, key mapping,
  defaults and redaction are visible in one place.
- The Kafbat → KUI migration tool (M8) is a key rename over the same mapping table.

## Alternatives rejected

- PureConfig 0.17.10: HOCON-first path model; Spring-style `[0]`/`_0_` env conventions need
  custom sources; no `Secret` equivalent.
- Typesafe Config directly: untyped, no effect integration.

## Reversibility

Medium. The model is explicit either way; the loader is one module.
