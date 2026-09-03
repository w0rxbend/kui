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

## Deviations

Recorded during implementation, 2026-09-03.

1. **`./mill libs.kernel.js.test` could not be run: Node.js is not installed.** Scala.js tests
   execute by handing the linked JavaScript to a JavaScript engine, and Mill's default engine is
   `node`. The command fails with
   `org.scalajs.jsenv.ExternalJSRun$FailedToStartException: failed to start command List(node)`.
   Everything up to the point of starting the engine works: `libs.kernel.js.compile`,
   `libs.kernel.js.test.compile` and `libs.kernel.js.test.fastLinkJS` all succeed, so the module
   really does cross-compile and link. Node was deliberately not installed here; the requirement is
   recorded as **B-002** in `BLOCKERS.md` and belongs to the frontend lane and to BUILD-004's CI
   image. The other four acceptance commands pass.

2. **There is no single `KuiCrossModule` trait.** A Mill trait cannot conjure two submodules, so
   cross-compilation uses Mill's own idiom: a `Shared` trait mixing `PlatformScalaModule`, and two
   nested objects `jvm` and `js` that extend it. `PlatformScalaModule` is what supplies the
   `src` / `src-jvm` / `src-js` split the spec asks for. The three-lines-per-module goal is met —
   a new cross module is a `Shared` trait plus two one-line objects.

3. **Test sources come from `KuiCrossTests`, and the platform is read from the module id.** The
   spec puts the cross module's tests in `libs/kernel/test/src`, shared by both platforms. Mill's
   default would have used `libs/kernel/jvm/test/src` and `libs/kernel/js/test/src` — two
   near-identical directories. `KuiCrossTests` overrides `sources` to `<module>/test/src` plus
   `<module>/test/src-<platform>`. The platform name is taken from the module id
   (`libs.kernel.jvm.test` gives `jvm`) rather than from the directory, because a
   `PlatformScalaModule`'s nested test module already shares one `moduleDir` across both platforms.

4. **`KuiJsTests` does not extend `ScalaJSTests`.** `ScalaJSTests` is an inner trait of
   `ScalaJSModule`, so no top-level trait can extend it. `KuiJsTests`, `KuiJsDomTests` and
   `KuiBrowserTests` are therefore mixins that carry the MUnit dependencies and the JavaScript
   environment, combined at the use site as `object test extends ScalaJSTests with KuiJsTests`.

5. **`ModuleSplitStyle.SmallModulesFor` takes varargs, not a `List`.** The spec's
   `SmallModulesFor(List("kui.ui.clusters"))` does not compile under Mill 1.1.8; it is
   `SmallModulesFor("kui.ui.clusters")`.

6. **`domtestutils` and `munit-cats-effect` are not yet on any test module's classpath.** They are
   still resolved by `resolveAll`, so the versions are proven. They are added when a module actually
   needs them — `domtestutils` by the first Laminar suite, `munit-cats-effect` by the first suite
   that tests an `IO` — rather than being put on every test classpath now.
