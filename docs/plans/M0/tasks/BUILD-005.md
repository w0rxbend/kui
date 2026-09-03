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
| A3 | `services/<name>/application` **of a service that owns a `domain`** → `libs.http`, `libs.contractsCore`, tapir, circe, or any `infrastructure` module. `services.gateway.application` is **outside** this rule: the gateway owns no `domain`, and the wire is its subject matter (ADR-041 §1a, Amendment 1) |
| A4 | `services.gateway.*` → any `services.<other>.{domain,application,infrastructure,api,app}` (only `contract` is allowed) |
| A5 | `libs/*` → any `services/*` or `frontend/*` module |
| A6 | `libs.kernel`, `libs.contractsCore`, `libs.securityCore` → any JVM-only dependency in their shared source set |
| A7 | `frontend/ui-shell` → a *static* reference to a `kui.ui.<feature>` class (checked by BUILD-006's bundle shape, not here) |
| A8 | `services.gateway.*` → `libs.kafka`, `libs.kafka-auth`, `fs2-kafka` or `kafka-clients` — the gateway holds no Kafka client (ADR-004 §3). This is the constraint that replaces the breadth A3 used to give the gateway, so it must be implemented and tested, not skipped because no Kafka module exists in M0 |

Rules A1–A6 and A8 are checked from `moduleDeps` and `mvnDeps` metadata, which Mill exposes;
no bytecode scanning is needed.

A3's scope test is mechanical, not a judgement call: a service is "domain-owning" when a
`services/<name>/domain` module is declared in the build. The gateway declares none, so it is
outside A3 by construction — if a `services/gateway/domain` module is ever added, A3 starts
applying to it automatically and the build says so. That is the intended behaviour: the day
the gateway grows a domain is the day ADR-004 has been violated, and two rules should fire.

## Non-goals

No package-level import checking inside a module (scalafix `DisableSyntax` covers the parts
that matter). No bundle shape (BUILD-006).

## Design references

ADR-041 (this task is its enforcement mechanism), PLAN §18, PLAN §19,
ADR-004 §3 ("the gateway has no domain logic, no Kafka client"), **ADR-041 including
Amendment 1** (the scope of A3 and the gateway's position), `ARCHITECTURE.md` §3 and §4.5,
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

Then assert the permitted case explicitly, because a rule that is too strict fails silently by
never being tried: `services.gateway.application → libs.contractsCore.jvm` and
`→ libs.http` are **legal** and must produce no violation.

## Tests required

- `ArchitectureSuite` (unit): a table of synthetic `ModuleFacts` graphs, one per rule, each
  asserting exactly one `Violation` with the expected rule id; plus a clean graph asserting
  an empty result; plus a **negative case per relaxed rule** — a gateway `application` module
  depending on `libs.contractsCore` and `libs.http` yields no violation, and the same edge from
  a domain-owning service's `application` yields an A3 violation. The pair is one test, so the
  scoping cannot be silently widened or narrowed later.

## Observability / Degraded behavior

Not applicable.

## Docs to update

`ARCHITECTURE.md` §3: add a sentence saying the dependency rule is machine-checked by
`./mill checkArchitecture`, with the rule ids.
