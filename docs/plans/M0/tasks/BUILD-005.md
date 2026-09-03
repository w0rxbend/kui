# BUILD-005 — Module-dependency architecture test

- **ID:** BUILD-005
- **Title:** Module-dependency architecture test
- **Milestone / Feature:** M0 / KU-008
- **Owner role:** Principal Scala Engineer
- **Context / service:** build root
- **Size:** S
- **Dependencies / blocked by:** BUILD-003

## Goal (user value)

The hexagonal rules of PLAN §18 and ADR-004 are enforced by the build instead of by review
attention. A worker who adds a forbidden `moduleDeps` edge learns it from a failing test in
the same minute.

## Scope

A Mill task `./mill checkArchitecture` (implemented in `build.mill`, backed by a small
`ArchitectureSuite` in a `build-tests` module so the assertions are readable) that walks the
declared module graph and fails on any of these edges:

| Rule | Forbidden edge |
| --- | --- |
| A1 | `services/*/domain` → anything except `libs.kernel.jvm` and `cats-core` |
| A2 | `services/*/contract` → `services/*/domain` or `services/*/application` |
| A3 | `services/*/application` → `libs.http`, `libs.contractsCore`, tapir, circe, or any `infrastructure` module (ADR-041; the gateway is not an exception) |
| A4 | `services.gateway.*` → any `services.<other>.{domain,application,infrastructure,api,app}` (only `contract` is allowed) |
| A5 | `libs/*` → any `services/*` or `frontend/*` module |
| A6 | `libs.kernel`, `libs.contractsCore`, `libs.securityCore` → any JVM-only dependency in their shared source set |
| A7 | `frontend/ui-shell` → a *static* reference to a `kui.ui.<feature>` class (checked by BUILD-006's bundle shape, not here) |

Rules A1–A6 are checked from `moduleDeps` and `mvnDeps` metadata, which Mill exposes; no
bytecode scanning is needed.

## Non-goals

No package-level import checking inside a module (scalafix `DisableSyntax` covers the parts
that matter). No bundle shape (BUILD-006).

## Design references

ADR-041 (this task is its enforcement mechanism), PLAN §18, PLAN §19,
ADR-004 §3 ("the gateway has no domain logic"), `ARCHITECTURE.md` §3,
`docs/domain/context-map.md` ("No context imports another context's `domain` module").

## Files to create

```
build.mill                                   (the checkArchitecture task)
build-tests/src/ArchitectureSuite.scala      (the rule table and assertions)
```

## Public Scala signatures to implement

```scala
final case class ModuleFacts(id: String, moduleDeps: Set[String], mvnDeps: Set[String])
final case class Violation(rule: String, module: String, offendingDep: String, why: String)

object ArchitectureRules {
  def check(modules: List[ModuleFacts]): List[Violation]
}
```

## Acceptance criteria

```
$ ./mill checkArchitecture               # exits 0 on the current tree
```

Add a temporary forbidden edge (gateway → `services.cluster.application`), run the task, see
a violation naming rule A4 and both module ids, then revert. Record the message text in the
Implementation Report.

## Tests required

- `ArchitectureSuite` (unit): a table of synthetic `ModuleFacts` graphs, one per rule, each
  asserting exactly one `Violation` with the expected rule id; plus a clean graph asserting
  an empty result.

## Observability / Degraded behavior

Not applicable.

## Docs to update

`ARCHITECTURE.md` §3: add a sentence saying the dependency rule is machine-checked by
`./mill checkArchitecture`, with the rule ids.
