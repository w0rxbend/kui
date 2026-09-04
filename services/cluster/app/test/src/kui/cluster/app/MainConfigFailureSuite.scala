package kui.cluster.app

import cats.effect.IO
import munit.CatsEffectSuite

import kui.config.LogFormat

/** How this process refuses to start on a configuration it cannot accept.
  *
  * Every case here is a failure path, and failure paths are the ones that go untested and then turn out, on
  * the day they matter, to print a stack trace or — far worse — to start anyway.
  *
  * The principal-codec cases used to be here too. They moved to
  * `kui.http.principal.ProcessPrincipalCodecSuite` along with the code, when the topic, message and consumer
  * services grew processes of their own and the decision stopped belonging to one service.
  */
final class MainConfigFailureSuite extends CatsEffectSuite {

  /** The file `libs/config`'s own suite uses: three independent mistakes, so a load that stopped at the first
    * would be visible.
    */
  private val multipleErrors: String =
    workspaceRoot.resolve("libs/config/test/resources/config/multiple-errors.yaml").toString

  /** The repository root, found by walking up from wherever the test runner started us.
    *
    * A suite's working directory is the build tool's business and not something to assume: Mill forks test
    * JVMs with their own, and a relative path that happens to work from a shell would then quietly resolve to
    * nothing and turn "three problems were reported" into "the file could not be read" — a passing-looking
    * failure test that proves nothing.
    */
  private lazy val workspaceRoot: java.nio.file.Path = {
    def ascend(from: java.nio.file.Path): java.nio.file.Path =
      if java.nio.file.Files.exists(from.resolve("build.mill")) then from
      else Option(from.getParent).map(ascend).getOrElse(fail("no build.mill above the working directory"))

    ascend(java.nio.file.Path.of("").toAbsolutePath)
  }

  test("invalidConfigExitsWithCodeOneAndPrintsEveryProblem") {
    Main
      .run(List("--config", multipleErrors))
      .map(exit =>
        // Non-zero, so an orchestrator and a CI job both notice. The three problems themselves go
        // to standard error, which is asserted below through the loader that produces them —
        // `Main` prints exactly `ConfigErrors.render` and adds nothing.
        assertEquals(exit.code, 1)
      )
  }

  test("everyProblemIsReportedRatherThanOnlyTheFirst") {
    kui.config.KuiConfigSource
      .load[IO](List("--config", multipleErrors), files = Nil)
      .map {
        case Right(config) => fail(s"the file should not have loaded: $config")
        case Left(errors) =>
          // Fixing configuration one message per restart is miserable, so all three are reported.
          assertEquals(errors.problems.length, 3, errors.render)
          assert(errors.render.contains("kui.server.port"), errors.render)
          assert(errors.render.contains("kui.telemetry.logFormat"), errors.render)
      }
  }

  test("logbackSelectionOnlyOverridesForText") {
    // `json` is Logback's own default lookup, so the property is left unset: one fewer thing that
    // can be pointed at a file nobody expects.
    assertEquals(kui.observability.LogbackSelection.resourceFor(LogFormat.Json), None)
    assertEquals(kui.observability.LogbackSelection.resourceFor(LogFormat.Text), Some("logback-text.xml"))
  }
}
