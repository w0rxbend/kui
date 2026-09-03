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

## Deviations

Recorded during implementation, 2026-09-03.

1. **`ArchitectureRules` is compiled twice, from one file.** The spec puts the rules in a
   `build-tests` module and the `checkArchitecture` task in `build.mill`. Those are two different
   compilations — Mill's build file is compiled by the *meta-build*, which cannot see project
   modules. Rather than duplicating the rules or shelling out to a jar,
   `mill-build/build.mill` adds `build-tests/src` to the meta-build's own sources. There is exactly
   one definition of the rules; `build-tests` compiles it for the unit tests, the meta-build
   compiles it for the task, and the tests therefore cover the code the build actually runs.

2. **`mvnDeps` are normalised to `group:artifact`.** Versions and Scala suffixes are stripped, so
   `mvn"org.typelevel::cats-core::2.13.0"` becomes `org.typelevel:cats-core` and
   `cats-core_sjs1_3` matches `cats-core_3`. The rules are about which library, not which release.

3. **A6 is checked against an explicit list of JVM-only artifacts.** "Any JVM-only dependency" is
   not something module metadata can decide on its own, so `ArchitectureRules.JvmOnlyArtifacts`
   names the JVM-only libraries `DEPENDENCY_MATRIX.md` actually lists. The list has to grow when a
   new JVM-only dependency is added; that is noted in a comment on the list itself. The backstop is
   that a genuinely JVM-only artifact also fails to resolve for Scala.js, so A6 catches the mistake
   earlier and with a clearer message rather than being the only thing that catches it.

4. **`depsSmoke` is excluded from the graph.** It declares every coordinate in the project on
   purpose (BUILD-001) and is not a real module; including it would make A6 and A8 fire on a module
   that has no sources and is on nobody's classpath.

5. **`-source:future` forbids `if (cond)`.** Not a deviation from this spec, but discovered here:
   the flag set BUILD-002 mandates implies `-new-syntax`, so conditionals are written
   `if cond then { ... } else { ... }`. Braces still surround the branches, per `-no-indent`.

## Proof the check works

Both directions were exercised against the **real** module graph, by temporarily adding modules to
`build.mill` and reverting them afterwards. Nothing temporary was committed.

The forbidden edge (`services.gateway.application → services.cluster.application`):

```
$ ./mill checkArchitecture
[error] checkArchitecture 1 layering violation(s):
  [A4] services.gateway.application -> services.cluster.application: the gateway sees every
  other service only through that service's published contract module; reaching into its
  internals couples the two and defeats the service split (ADR-004 §3, ADR-041 A4)
```

The permitted edges the spec asks to be asserted explicitly, because a rule that is too strict
fails silently by never being tried — `services.gateway.application → libs.contractsCore.jvm` and
`→ libs.http`, alongside `→ services.cluster.contract`:

```
$ ./mill checkArchitecture
checkArchitecture: 10 modules, no layering violations
```

The same pair is also asserted as one unit test (`A3 is scoped to services that own a domain`), so
the scoping cannot later be widened or narrowed without a test failing. A second test proves the
spec's other claim: adding a `services/gateway/domain` module makes A3 start applying to the
gateway automatically.

On the current tree:

```
$ ./mill checkArchitecture
checkArchitecture: 6 modules, no layering violations
```
