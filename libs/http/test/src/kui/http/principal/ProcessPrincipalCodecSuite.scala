package kui.http.principal

import java.time.Instant

import scala.concurrent.duration.DurationInt

import cats.effect.IO
import cats.effect.testkit.TestControl
import munit.CatsEffectSuite

import kui.config.PrincipalKeyConfig
import kui.kernel.Secret
import kui.testkit.fakes.FakeStructuredLogger

/** The three answers a starting service process can give about who it will believe, and the refusal.
  *
  * Every case here is a failure path, and failure paths are the ones that go untested and then turn out, on
  * the day they matter, to print a stack trace or — far worse — to start anyway. These lived in the cluster
  * service's own suite while the code did; both moved to `libs/http` when the topic, message and consumer
  * services grew processes of their own and stopped being able to share it by copying.
  */
final class ProcessPrincipalCodecSuite extends CatsEffectSuite {

  test("missingPrincipalKeysInDistributedModeIsAStartupFailure") {
    FakeStructuredLogger[IO].map { logger =>
      // No keys and no escape hatch. A service that started here would trust an identity header
      // from anyone who could reach its port, silently.
      val built = ProcessPrincipalCodec.make[IO](Nil, Map.empty, logger)
      assertEquals(built.left.toOption, Some(ProcessPrincipalCodec.MissingKeys))
    }
  }

  test("aKeyTooShortForHs256IsAStartupFailure") {
    FakeStructuredLogger[IO].map { logger =>
      // Sixteen bytes is 128 bits. HS256 accepts it and produces a weaker signature, which is the
      // failure worth refusing: nothing would look wrong afterwards.
      val short = PrincipalKeyConfig("short", Secret("x" * 16), Instant.EPOCH)
      val built = ProcessPrincipalCodec.make[IO](List(short), Map.empty, logger)
      assert(built.left.toOption.exists(_.contains("128 bits")), built.left.toOption.toString)
    }
  }

  test("aLongEnoughKeyBuildsACodec") {
    FakeStructuredLogger[IO].map { logger =>
      val key = PrincipalKeyConfig("ok", Secret("x" * 32), Instant.EPOCH)
      assert(ProcessPrincipalCodec.make[IO](List(key), Map.empty, logger).isRight)
    }
  }

  test("theUnsignedEscapeHatchLogsAWarning") {
    val hatch = Map(ProcessPrincipalCodec.AllowUnsignedVariable -> "true")

    TestControl.executeEmbed {
      FakeStructuredLogger[IO].flatMap { logger =>
        ProcessPrincipalCodec.make[IO](Nil, hatch, logger) match {
          case Left(problem) => IO(fail(s"the escape hatch should have been accepted: $problem"))
          case Right(codec) =>
            codec
              .use(_ => IO.sleep(ProcessPrincipalCodec.WarningInterval * 2 + 1.second))
              .flatMap(_ => logger.entries)
              .map { entries =>
                val warnings = entries.filter(entry =>
                  entry.level == "warn" && entry.message == ProcessPrincipalCodec.UnsignedWarning
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
}
