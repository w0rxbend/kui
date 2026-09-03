# Spike — `mill-scalablytyped` on Mill 1.1.x

- **Task:** BUILD-006, spike 2 (risk R-6)
- **Role:** Frontend Architect / Principal Scala Engineer
- **Date:** 2026-09-03

## Question

KUI's code editor is CodeMirror 6, a JavaScript library. Scala.js code cannot call a JavaScript
library directly; it needs a **facade** — a set of Scala declarations that describe the library's
shape (its classes, methods and types) so the compiler can type-check calls against it. Writing one
by hand is careful, tedious work.

ScalablyTyped is a generator that reads a library's TypeScript type declarations (`.d.ts` files,
the same files a TypeScript programmer's editor reads) and emits that facade as Scala source.
`lolgab/mill-scalablytyped` is the plugin that runs it from a Mill build. ADR-025 planned to use it
**once**, to produce a first draft that is then trimmed by hand and vendored into
`frontend/ui-kernel`.

The open question in `DEPENDENCY_MATRIX.md`: does the plugin load and generate under **Mill 1.1.8**
and **Scala 3.9 / Scala.js 1.22**? Mill 1.x changed its plugin API, and a plugin that has not caught
up will not even load.

## Method

A scratch Mill project in the agent scratchpad (deleted; not in the repository), using the
repository's own `./mill` launcher and `.mill-version` of 1.1.8:

```scala
//| mvnDeps:
//| - com.github.lolgab::mill-scalablytyped::0.4.1

object facade extends ScalaJSModule with ScalablyTyped {
  def scalaVersion   = "3.9.0"
  def scalaJSVersion = "1.22.0"
}
```

with a `package.json` naming exactly one dependency, `@codemirror/state` 6.5.2, and `npm install`
run before the build. Node 24.18.0.

## Findings

**The plugin resolves and runs.** `mill-scalablytyped` publishes a `_mill1_3` artifact — built
against Mill 1.x for Scala 3 — with 0.4.1 as the current release (published 2026-03-20). It loads
into a Mill 1.1.8 meta-build without complaint.

**Generation and compilation both succeed.** `./mill facade.compile` produced and then compiled the
facade:

```
Successfully converted @codemirror/state
facade.compile compiling 43 Scala sources to out/facade/compile.dest/classes ...
done compiling
78/78, SUCCESS] ./mill facade.compile 14s
```

42 generated Scala files, 3163 lines, under `out/facade/scalablyTypedImportTask.dest/src/typings/codemirrorState/`.

Three practical notes for whoever runs this in M2:

1. **`typescript` must be installed as an npm package first.** Without it the task fails with
   `requirement failed: must install typescript` — an accurate message, but not an obvious one.
   `npm install typescript` in the directory holding `package.json` fixes it.
2. **There is no `scalablyTyped` task to invoke.** Generation is wired into `generatedSources`, so
   it happens as part of `compile`. `./mill facade.scalablyTyped` fails with "Cannot resolve"; the
   settings tasks (`scalablyTypedWantedLibs`, `scalablyTypedOutputPackage`, …) are the only ones
   with that prefix.
3. **The generated code does not compile under `-Werror`.** It emits identifiers such as
   ``inline def `-1`: `-1` = -1.asInstanceOf[`-1`]``, which the compiler reports as
   `Illegal literal` — a warning in the scratch project, an error under KUI's settings. This does
   not matter for the plan of record, because ADR-025 only ever used the generator for a first
   draft that is then trimmed by hand; it does matter if anyone is tempted to wire the generator
   into the routine build, which they should not.

## Decision taken

**Keep `mill-scalablytyped`, pinned at 0.4.1**, as ADR-025 planned: a one-off generator, run by
hand in M2, whose output is trimmed and vendored into `frontend/ui-kernel`. It is not added to
`build.mill` and is not part of any routine build.

The BUILD-006 decision rule described what to do on a "no" — drop the dependency row and hand-write
the facades. The answer was "yes", so that branch does not apply and the
`DEPENDENCY_MATRIX.md` row **stays**, with its version filled in and its open question closed.

## Consequence

- `DEPENDENCY_MATRIX.md`: the `mill-scalablytyped` row gains the version `0.4.1`, and its open
  question row is closed.
- ADR-025 needs no consequence note: its stated plan — generate once, trim, vendor — is exactly
  what was proven to work.
- The M2 facade task inherits three prerequisites: an `npm install` including `typescript`, running
  generation through `compile` rather than a named task, and the expectation that the output is
  edited before it is committed.

## Confidence

**High.** The whole path was executed end to end on the pinned toolchain: the real `./mill`
launcher, the real Mill version, the real Scala and Scala.js versions, and the actual npm package
ADR-025 names first. The result is generated Scala that compiled.

The only untested part is the remaining CodeMirror packages (`view`, `lang-json`, `lang-sql`,
`legacy-modes`, `lint`, `search`) and uPlot. Those are more `.d.ts` files through the same working
pipeline; a failure there would be a per-library quirk, not a toolchain incompatibility, and ADR-025
already accepts hand-writing as the fallback for any library the generator chokes on.
