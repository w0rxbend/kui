# BUILD-003 — Cross-platform, Scala.js and test module traits

- **ID:** BUILD-003
- **Title:** Cross-platform, Scala.js and test module traits
- **Milestone / Feature:** M0 / KU-004, KU-008
- **Owner role:** Principal Scala Engineer
- **Context / service:** build root
- **Size:** M
- **Dependencies / blocked by:** BUILD-002

## Goal (user value)

One place decides how a module is cross-compiled to the JVM and the browser, how Scala.js is
linked, and how tests run. Every subsequent module is three lines of build code.

## Scope

1. `trait KuiJvmModule extends KuiModule` — JVM defaults.
2. `trait KuiJsModule extends KuiModule with ScalaJSModule` with
   `def scalaJSVersion = Versions.scalaJs`, `def moduleKind = ModuleKind.ESModule`,
   `def scalaJSUseWebAssembly = false`, source maps on.
3. `trait KuiCrossModule` producing `.jvm` (a `KuiJvmModule`) and `.js` (a `KuiJsModule`) that
   share `src/` and add `src-jvm/` and `src-js/` for platform-specific sources.
4. Test traits: `trait KuiTests extends TestModule.Munit` (JVM) and
   `trait KuiJsTests extends ScalaJSTests with TestModule.Munit`, plus a `KuiJsDomTests`
   variant setting `def jsEnvConfig = Task { JsEnvConfig.JsDom() }` and a `KuiBrowserTests`
   variant setting `JsEnvConfig.Playwright(...)` for the two SSE wrapper suites only.
5. A `trait KuiFrontendModule extends KuiJsModule` that additionally sets
   `def moduleSplitStyle = ModuleSplitStyle.SmallModulesFor(List("kui.ui.clusters"))` — the
   list is extended by one entry per feature package in every later milestone.
6. Convert `libs/kernel` to a cross module and add its (still empty) test module so
   `./mill libs.kernel.js.test` runs.

## Non-goals

No CSS pipeline (UI-001). No bundle-shape check (BUILD-006). No feature modules.

## Design references

ADR-001, ADR-011, ADR-012, ADR-018,
`research/scala/frontend-research.md` §4 (Mill `ScalaJSModule` settings, `SmallModulesFor`,
`js.dynamicImport` as the split border) and §7 (jsdom and Playwright js environments).

## Files to change

```
build.mill
libs/kernel/src/...            (moved under the cross layout)
libs/kernel/test/src/kui/kernel/PlaceholderSuite.scala
```

## Public Scala signatures to implement

```scala
trait KuiJvmModule extends KuiModule

trait KuiJsModule extends KuiModule with ScalaJSModule {
  def scalaJSVersion = Versions.scalaJs
  def moduleKind     = ModuleKind.ESModule
}

trait KuiTests   extends TestModule.Munit { def mvnDeps = Seq(mvn"org.scalameta::munit::1.3.6") }
trait KuiJsTests extends ScalaJSTests with KuiTests
```

## Library coordinates

- `org.scala-js:scalajs-library_2.13:1.22.0` (supplied by `ScalaJSModule`)
- `org.scalameta::munit::1.3.6`, `org.scalameta::munit-scalacheck::1.3.1`,
  `org.scalacheck::scalacheck::1.20.0`, `org.typelevel::munit-cats-effect::2.2.0`
- `com.raquo::domtestutils::19.0.0` (JS test scope only)
- jsdom is an npm package: document `npm install -g jsdom` (or a local `package.json`) as a
  prerequisite of `KuiJsDomTests` in `README.md`.

## Acceptance criteria

```
$ ./mill libs.kernel.jvm.compile        # exits 0
$ ./mill libs.kernel.js.compile         # exits 0
$ ./mill libs.kernel.jvm.test           # 1 placeholder test, green
$ ./mill libs.kernel.js.test            # same test, green, under Node
$ ./mill show libs.kernel.js.moduleKind # ESModule
```

## Tests required

- `PlaceholderSuite` (unit, cross-compiled): asserts `1 + 1 == 2`. Its only job is to prove
  both test runners work; KERN-001 replaces it.

## Observability / Degraded behavior

Not applicable.

## Docs to update

`README.md`: how to add a module (JVM, JS, cross) in three lines.
