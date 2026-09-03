# ADR-001 — Build toolchain: Scala 3.9.0 LTS, JDK 21, Mill 1.1.8, Scala.js 1.22.0

- Status: Accepted
- Date: 2026-09-03

## Context

PLAN §10 fixes "Scala 3 latest stable" and "Mill latest stable" but leaves exact versions
to research. The JDK baseline decides which versions of several Java libraries are usable
(datasketches, Lucene) and which container base images exist.

## Decision

- Scala **3.9.0** (LTS line) for every module; compiler flags `-source:future`,
  `-Wunused:all`, `-Werror` in CI. Fallback if 3.9.0 shows ecosystem breakage in the first two
  weeks of M0: 3.3.8 LTS (every dependency cross-publishes).
- **JDK 21** (LTS) as runtime, CI and container baseline.
- **Mill 1.1.8**; Docker images through `mill-contrib-docker` 1.1.8; scalafmt 3.11.5 and
  scalafix 0.14.7 run from Mill; JS modules through the built-in `ScalaJSModule`.
- **Scala.js 1.22.0** with `ModuleKind.ESModule` for all frontend modules.
- Repository layout as in `ARCHITECTURE.md` §16; module ids follow `libs/<name>`,
  `services/<name>/<layer>`, `frontend/<name>`, `apps/allinone`.

## Evidence

- `research/scala/ecosystem-mapping.md` F1 (Scala 3.9.0 artifacts on Central 2026-08-26,
  Mill 1.1.8, Scala.js 1.22.0, Jib plugins archived, `mill-contrib-docker` first-party).
- `research/scala/ecosystem-mapping.md` F9 "JDK baseline consequence" (Scala 3.9 needs 17+,
  datasketches 8.0.0 needs 21, 9.0.0 needs 25, Lucene 10 needs 21).
- `research/scala/frontend-research.md` §3.1 (Scala.js 1.21+ requires JDK 17+, GCC not
  applicable to ESModule output).

## Consequences

- Chimney must be 2.0.0-RC1 on Scala 3.9 (ADR-033).
- datasketches is pinned to 8.0.0 (not 9.0.0) until the JDK baseline moves to 25.
- Container images are `eclipse-temurin:21-jre` based; JDK 25 is revisited after Scala 3.9 and
  Mill are validated on it.

## Alternatives rejected

- Scala 3.3.8 LTS as the primary: loses one year of language fixes; kept only as fallback.
- JDK 25: young base images; Scala 3.9.0/Mill 1.1.8 not validated on it at decision time.
- sbt: PLAN §10 mandates Mill; no reason to reopen.
- Jib-based Docker plugins: archived or without Mill 1.x artifacts.

## Reversibility

High. Version bumps are mechanical; the JDK baseline can move up without code changes.
