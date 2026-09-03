# BUILD-002 — Compiler flags, scalafmt and scalafix gates

- **ID:** BUILD-002
- **Title:** Compiler flags, scalafmt and scalafix gates
- **Milestone / Feature:** M0 / KU-008
- **Owner role:** Principal Scala Engineer
- **Context / service:** build root
- **Size:** S
- **Dependencies / blocked by:** BUILD-001

## Goal (user value)

Every later task inherits the same strictness. A warning is an error from the first line of
real code, so no module accumulates a backlog of "we'll fix the warnings later".

## Scope

1. Compiler options on `KuiModule`: `-source:future`, `-Wunused:all`, `-Werror`,
   `-deprecation`, `-feature`, `-Xkind-projector` is **not** added (ADR-001 does not admit it),
   `-Ysafe-init` if it compiles cleanly.
2. `.scalafmt.conf` (scalafmt **3.11.5**, `runner.dialect = scala3`, `maxColumn = 110`,
   `rewrite.rules = [RedundantBraces, RedundantParens, SortModifiers]`, `align.preset = none`).
3. `.scalafix.conf` (scalafix **0.14.7**) with `OrganizeImports` configured for Scala 3
   (`removeUnused = false`, because `-Wunused` already covers it and scalafix's unused-import
   rule needs `-Ywarn-unused` semantics), `DisableSyntax` forbidding `null`, `throw`, `var` in
   `libs/**` and `services/*/domain/**`, `return`, and `asInstanceOf`.
4. Mill wiring so `./mill __.reformat`, `./mill __.checkFormat`, `./mill __.fix` and
   `./mill __.fix --check` exist on every module (`ScalafmtModule`, `ScalafixModule`).

## Non-goals

No CI (BUILD-004). No architecture rules (BUILD-005).

## Design references

ADR-001, PLAN §33 (code quality), PLAN §50 (quality gates), `CLAUDE.md`.

## Files to create or change

```
build.mill                (add scalacOptions, mix in ScalafmtModule/ScalafixModule)
.scalafmt.conf
.scalafix.conf
```

## Public Scala signatures to implement

```scala
trait KuiModule extends ScalaModule with ScalafmtModule with ScalafixModule {
  def scalaVersion = Versions.scala
  def scalacOptions = Seq(
    "-source:future", "-deprecation", "-feature", "-unchecked",
    "-Wunused:all", "-Wvalue-discard", "-Wnonunit-statement", "-Werror"
  )
}
```

## Library coordinates

`org.scalameta:scalafmt-core_2.13:3.11.5`, `ch.epfl.scala:scalafix-core_2.13:0.14.7`
(both resolved by Mill's own modules; no `mvnDeps` entry is needed).

## Acceptance criteria

```
$ ./mill __.compile                     # exits 0
$ ./mill __.checkFormat                 # exits 0
$ ./mill __.fix --check                 # exits 0
$ printf 'package kui.kernel\nobject X { val a = 1 }\n' > /tmp/x.scala  # unused val in a real
                                        # module makes ./mill __.compile FAIL with -Werror
```

Demonstrate the last point once by hand and record it in the Implementation Report; do not
commit the failing file.

## Tests required

None beyond the commands above.

## Observability / Degraded behavior

Not applicable.

## Docs to update

`README.md`: the four quality commands, and the rule that `-Werror` is not negotiable per module.
