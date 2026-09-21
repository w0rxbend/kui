package kui.observability

import cats.effect.IO
import munit.CatsEffectSuite

import kui.config.LogFormat

/** The one rule this object has, asserted for the first time.
  *
  * `LogbackSelection.apply` is called by every KUI process's `Main` and by nothing that has a suite, so its
  * guard was executed by no test: deleting the `if Option(System.getProperty(Property)).isEmpty` clause left
  * every module green while making KUI overwrite an operator's own `-Dlogback.configurationFile`. The comment
  * on the method states the rule — *"someone who passed `-Dlogback.configurationFile=...` on the command line
  * has said something more specific than a configuration file did"* — and this is where it is checked.
  *
  * The property is process-global, so each case restores whatever it found. `munit` runs the cases in one JVM
  * and a leaked property would decide the outcome of the next one.
  */
final class LogbackSelectionSuite extends CatsEffectSuite {

  private def withProperty[A](value: Option[String])(body: IO[A]): IO[A] = {
    val read = IO(Option(System.getProperty(LogbackSelection.Property)))

    def set(to: Option[String]): IO[Unit] =
      IO {
        to match {
          case Some(found) => val _ = System.setProperty(LogbackSelection.Property, found)
          case None => val _ = System.clearProperty(LogbackSelection.Property)
        }
      }

    read.flatMap(original => set(value).bracket(_ => body)(_ => set(original)))
  }

  test("an operator's own logback file survives a configuration that asks for text") {
    withProperty(Some("/etc/kui/my-logback.xml")) {
      LogbackSelection[IO](LogFormat.Text) >>
        IO(System.getProperty(LogbackSelection.Property)).map { chosen =>
          assertEquals(chosen, "/etc/kui/my-logback.xml")
        }
    }
  }

  test("text with nothing already set selects the shipped human-readable configuration") {
    // The positive half. Without it the case above is satisfied by an `apply` that does nothing at all.
    withProperty(None) {
      LogbackSelection[IO](LogFormat.Text) >>
        IO(System.getProperty(LogbackSelection.Property)).map { chosen =>
          assertEquals(chosen, LogbackSelection.TextConfiguration)
        }
    }
  }

  test("json sets nothing, so Logback's own default lookup is what runs") {
    withProperty(None) {
      LogbackSelection[IO](LogFormat.Json) >>
        IO(Option(System.getProperty(LogbackSelection.Property))).map { chosen =>
          assertEquals(chosen, None)
        }
    }
  }

  test("json does not clear an operator's file either") {
    withProperty(Some("/etc/kui/my-logback.xml")) {
      LogbackSelection[IO](LogFormat.Json) >>
        IO(System.getProperty(LogbackSelection.Property)).map { chosen =>
          assertEquals(chosen, "/etc/kui/my-logback.xml")
        }
    }
  }

  test("the file name for a format is the shipped resource, and json names none") {
    assertEquals(LogbackSelection.resourceFor(LogFormat.Text), Some(LogbackSelection.TextConfiguration))
    assertEquals(LogbackSelection.resourceFor(LogFormat.Json), None)
  }
}
