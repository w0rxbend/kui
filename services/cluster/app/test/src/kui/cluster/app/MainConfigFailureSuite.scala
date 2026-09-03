package kui.cluster.app

import java.time.Instant

import scala.concurrent.duration.DurationInt

import cats.effect.IO
import cats.effect.testkit.TestControl
import munit.CatsEffectSuite

import kui.config.{LogFormat, PrincipalKeyConfig}
import kui.kernel.Secret
import kui.testkit.fakes.FakeStructuredLogger

/** The four ways this process refuses to start, and the one it starts unsafely on purpose.
  *
  * Every case here is a failure path, and failure paths are the ones that go untested and then turn out, on
  * the day they matter, to print a stack trace or — far worse — to start anyway.
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

  test("missingPrincipalKeysInDistributedModeIsAStartupFailure") {
    FakeStructuredLogger[IO].map { logger =>
      // No keys and no escape hatch. A service that started here would trust an identity header
      // from anyone who could reach its port, silently.
      val built = PrincipalCodecs.make[IO](Nil, Map.empty, logger)
      assertEquals(built.left.toOption, Some(PrincipalCodecs.MissingKeys))
    }
  }

  test("aKeyTooShortForHs256IsAStartupFailure") {
    FakeStructuredLogger[IO].map { logger =>
      // Sixteen bytes is 128 bits. HS256 accepts it and produces a weaker signature, which is the
      // failure worth refusing: nothing would look wrong afterwards.
      val short = PrincipalKeyConfig("short", Secret("x" * 16), Instant.EPOCH)
      val built = PrincipalCodecs.make[IO](List(short), Map.empty, logger)
      assert(built.left.toOption.exists(_.contains("128 bits")), built.left.toOption.toString)
    }
  }

  test("aLongEnoughKeyBuildsACodec") {
    FakeStructuredLogger[IO].map { logger =>
      val key = PrincipalKeyConfig("ok", Secret("x" * 32), Instant.EPOCH)
      assert(PrincipalCodecs.make[IO](List(key), Map.empty, logger).isRight)
    }
  }

  test("theUnsignedEscapeHatchLogsAWarning") {
    val hatch = Map(PrincipalCodecs.AllowUnsignedVariable -> "true")

    TestControl.executeEmbed {
      FakeStructuredLogger[IO].flatMap { logger =>
        PrincipalCodecs.make[IO](Nil, hatch, logger) match {
          case Left(problem) => IO(fail(s"the escape hatch should have been accepted: $problem"))
          case Right(codec) =>
            codec
              .use(_ => IO.sleep(PrincipalCodecs.WarningInterval * 2 + 1.second))
              .flatMap(_ => logger.entries)
              .map { entries =>
                val warnings = entries.filter(entry =>
                  entry.level == "warn" && entry.message == PrincipalCodecs.UnsignedWarning
                )

                // One as the process starts, so it is on the first screen of the log, and one per
                // minute afterwards for as long as the setting is in effect. A single line at
                // startup scrolls away; a line every minute does not.
                assertEquals(warnings.size, 3, entries.toString)
              }
        }
      }
    }
  }

  test("logbackSelectionOnlyOverridesForText") {
    // `json` is Logback's own default lookup, so the property is left unset: one fewer thing that
    // can be pointed at a file nobody expects.
    assertEquals(LogbackSelection.resourceFor(LogFormat.Json), None)
    assertEquals(LogbackSelection.resourceFor(LogFormat.Text), Some("logback-text.xml"))
  }
}
