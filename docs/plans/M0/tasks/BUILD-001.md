# BUILD-001 — Repository skeleton and Mill root build

- **ID:** BUILD-001
- **Title:** Repository skeleton and Mill root build
- **Milestone / Feature:** M0 / KU-008
- **Owner role:** Principal Scala Engineer
- **Context / service:** build root
- **Size:** S
- **Dependencies / blocked by:** none. This is the first commit of the codebase.

## Goal (user value)

Nothing user-visible. This task creates the ground every other task stands on: a Mill build
that resolves every dependency in `DEPENDENCY_MATRIX.md`, compiles one trivial module, and
proves the pinned versions actually exist on Maven Central before anyone writes real code.

## Scope

1. `.mill-version` pinning Mill **1.1.8** and a committed `./mill` bootstrap script.
2. `build.mill` with a single `object Versions` holding every version string from
   `DEPENDENCY_MATRIX.md` that M0 uses, and a `trait KuiModule extends ScalaModule` setting
   `def scalaVersion = Versions.scala`.
3. One throwaway-but-permanent module `libs/kernel` with an empty `package object`-free
   source file so `./mill libs.kernel.jvm.compile` has something to do. (KERN-001 fills it.)
4. A dependency-resolution smoke task `./mill resolveAll` that calls `resolvedIvyDeps` on a
   module declaring **every** M0 coordinate, so a version typo fails in minute one rather
   than in week three.
5. `.gitignore` for `out/`, `.bsp/`, `.metals/`, `.bloop/`, `node_modules/`, `frontend/dist/`;
   `.git/info/exclude` entries for `.agent/` and `/tmp/kui-ref` per PLAN §51.

## Non-goals

No compiler flags yet (BUILD-002), no Scala.js (BUILD-003), no CI (BUILD-004), no service or
frontend modules.

## Design references

ADR-001 (Scala 3.9.0, JDK 21, Mill 1.1.8, layout), `ARCHITECTURE.md` §16, PLAN §48,
`DEPENDENCY_MATRIX.md` (all rows), `research/scala/ecosystem-mapping.md` F1.

## Files to create

```
.mill-version
mill                              (Mill bootstrap script, executable)
build.mill
.gitignore
libs/kernel/src/kui/kernel/package.scala      (placeholder: `package kui.kernel` + a doc comment)
```

## Public Scala signatures to implement

```scala
// build.mill
object Versions {
  val scala        = "3.9.0"
  val scalaJs      = "1.22.0"
  val jdk          = "21"
  val cats         = "2.13.0"
  val catsEffect   = "3.7.1"
  val fs2          = "3.13.0"
  val tapir        = "1.13.31"
  val sttp         = "4.0.26"
  val circe        = "0.14.16"
  val circeYaml    = "0.16.1"
  val ciris        = "3.15.0"
  val iron         = "3.3.2"
  val macwire      = "2.6.7"
  val log4cats     = "2.8.0"
  val slf4j        = "2.0.18"
  val logback      = "1.6.3"
  val logstash     = "9.0"
  val otel4s       = "1.1.0"
  val otelSdk      = "1.65.0"
  val laminar      = "17.2.1"
  val waypoint     = "9.0.0"
  val scalajsDom   = "2.8.1"
  val scalaJavaTime = "2.7.0"
  val munit        = "1.3.6"
  val munitScalacheck = "1.3.1"
  val scalacheck   = "1.20.0"
  val munitCatsEffect = "2.2.0"
  val disciplineMunit = "2.0.0"
  val testcontainers  = "0.44.1"
  val domtestutils = "19.0.0"
}
```

## Library coordinates

Only what is needed to resolve: the full list above, expressed as `mvn"group::artifact::version"`
(`::` for Scala 3 artifacts, `:` for Java ones) in the smoke module.

## Acceptance criteria

```
$ ./mill --version                      # prints Mill 1.1.8
$ ./mill resolveAll                     # exits 0; every coordinate downloads
$ ./mill libs.kernel.jvm.compile        # exits 0
$ java -version                         # 21.x  (documented prerequisite)
$ git status --porcelain                # only the files listed above
```

If `org.scala-lang:scala3-library_3:3.9.0` does not resolve, set `Versions.scala = "3.3.8"`,
record the fallback in the Implementation Report and open a TECH_DEBT row referencing ADR-001's
documented fallback. Do **not** invent a third version.

## Tests required

None (there is no code yet). The resolution task is the test.

## Observability

None.

## Degraded behavior

Not applicable.

## Docs to update

`README.md`: prerequisites (JDK 21, Docker) and the three commands above.
