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

## Deviations

Recorded during implementation, 2026-09-03.

1. **scalafix needs a build plugin; the spec said it did not.** The spec states that scalafmt and
   scalafix are "both resolved by Mill's own modules; no `mvnDeps` entry is needed". That is true of
   scalafmt (`mill.scalalib.scalafmt.ScalafmtModule` ships with Mill 1.1.8) but not of scalafix:
   Mill 1.1.8 contains no scalafix support at all. The build therefore declares the third-party
   plugin `com.goyeau::mill-scalafix::0.6.2` in the `//|` build header, which is Mill 1.x's
   replacement for the old `import $ivy` syntax. `com.goyeau:mill-scalafix_mill1_3:0.6.2` was
   checked against `repo1.maven.org` before use. This is a new build-scope dependency and needs a
   `DEPENDENCY_MATRIX.md` row, which this commit adds.

2. **`-Ysafe-init` was not added.** Scala 3.9 spells the flag `-Wsafe-init`, and the spec's own
   "Public Scala signatures" block — the normative list — does not include it. The implemented flag
   set is exactly that block, so the two halves of the spec are reconciled in favour of the
   explicit one.

3. **`-no-indent` was added.** It is not in the spec's flag list. It forbids significant
   indentation, which is the braceful house style the `scala-lang` skill mandates and which
   `PLAN.md` §5 requires the project to apply. Enforcing a style at the compiler is worth more than
   enforcing it in review, and the flag is free.

4. **`var` is banned through a second config file, not a path filter.** The spec asks for
   `DisableSyntax` to forbid `var` only in `libs` and service `domain` modules. scalafix has no way
   to scope one rule to a path from a single config file, so there are two: `.scalafix.conf` (the
   project-wide rules) and `.scalafix-pure.conf` (the same rules plus `noVars`). The build trait
   `KuiPureModule` points at the stricter file, and `libs.kernel.jvm` extends it. Adding a service's
   `domain` module to the strict set is then a matter of extending `KuiPureModule` instead of
   `KuiModule`. The duplication between the two files is the price; it is noted in both.

5. **`removeUnused = false`** is set on `OrganizeImports` as the spec asks, for the reason the spec
   gives.

## Proof the gates fail

The spec asks for the `-Werror` failure to be demonstrated by hand. Both gates were, using a
temporary `libs/kernel/src/kui/kernel/Demo.scala` that was deleted afterwards and never committed.

`-Werror`, on an unused import:

```
[warn] libs/kernel/src/kui/kernel/Demo.scala:3:33
import scala.collection.mutable.ListBuffer
                                ^^^^^^^^^^
unused import

[error] No warnings can be incurred under -Werror
[error] libs.kernel.jvm.compile Compilation failed
```

`./mill libs.kernel.jvm.fix --check`, on a `var` and a `null` in a `KuiPureModule`:

```
Demo.scala:4:3: error: [DisableSyntax.var] mutable state should be avoided
  var counter: Int      = 0
  ^^^
Demo.scala:5:27: error: [DisableSyntax.null] null should be avoided, consider using Option instead
  val nothing: String   = null
                          ^^^^
[error] libs.kernel.jvm.fix A Scalafix linter error was reported
```
