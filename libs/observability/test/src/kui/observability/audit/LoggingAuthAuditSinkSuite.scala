package kui.observability.audit

import java.time.Instant

import cats.effect.IO
import cats.effect.kernel.Ref
import munit.CatsEffectSuite
import org.typelevel.log4cats.StructuredLogger

import kui.kernel.UserName
import kui.security.audit.{AuthenticationEvent, AuthenticationRecord, MutationOutcome}
import kui.security.{Principal, PrincipalKind}
import kui.testkit.fakes.FakeStructuredLogger

/** `LoggingAuditSink`'s twin, which had a suite for neither of the two properties its neighbour's has.
  *
  * This is the shape wave 5 named and did not finish: `LoggingAuditSinkSuite` was written and the sink beside
  * it, holding the same two rules in the same seven lines, was left alone. It is constructed by
  * `IdentityWiring` and by nothing else, and `services/identity/app` ships no test source at all, so both
  * rules were executed by no case anywhere — `./mill libs.__.test` and `./mill services.identity.__.test`
  * were 835 and 67 green with `.handleError(_ => ())` deleted, and 835 green with the shared field names
  * replaced by ones only this sink writes.
  *
  * The consequence of the first is that a refused sign-in would fail *because the audit log was full*, which
  * this file's own scaladoc calls a worse outcome than a sign-in nobody wrote down. The consequence of the
  * second is quieter and lasts longer: the trail stops answering "everything this person did today", because
  * half of it is under a different key and nothing in the tree would say so.
  */
final class LoggingAuthAuditSinkSuite extends CatsEffectSuite {

  private val refusal: AuthenticationRecord =
    AuthenticationRecord.refused(
      at = Instant.parse("2026-02-01T10:00:00Z"),
      event = AuthenticationEvent.Login,
      subject = "root",
      detail = Map("provider" -> "local")
    )

  private val success: AuthenticationRecord =
    AuthenticationRecord.succeeded(
      at = Instant.parse("2026-02-01T10:00:01Z"),
      event = AuthenticationEvent.OidcCallback,
      principal = Principal(UserName.unsafe("alice"), Set.empty, PrincipalKind.Session)
    )

  /** A logger whose every write fails: a full disk, or an appender that has stopped. */
  private def failingLogger(attempts: Ref[IO, Int]): StructuredLogger[IO] =
    new StructuredLogger[IO] {
      private def boom: IO[Unit] =
        attempts.update(_ + 1) >> IO.raiseError(new RuntimeException("no space left on device"))

      def trace(message: => String): IO[Unit] = boom
      def debug(message: => String): IO[Unit] = boom
      def info(message: => String): IO[Unit] = boom
      def warn(message: => String): IO[Unit] = boom
      def error(message: => String): IO[Unit] = boom

      def trace(t: Throwable)(message: => String): IO[Unit] = boom
      def debug(t: Throwable)(message: => String): IO[Unit] = boom
      def info(t: Throwable)(message: => String): IO[Unit] = boom
      def warn(t: Throwable)(message: => String): IO[Unit] = boom
      def error(t: Throwable)(message: => String): IO[Unit] = boom

      def trace(ctx: Map[String, String])(message: => String): IO[Unit] = boom
      def debug(ctx: Map[String, String])(message: => String): IO[Unit] = boom
      def info(ctx: Map[String, String])(message: => String): IO[Unit] = boom
      def warn(ctx: Map[String, String])(message: => String): IO[Unit] = boom
      def error(ctx: Map[String, String])(message: => String): IO[Unit] = boom

      def trace(ctx: Map[String, String], t: Throwable)(message: => String): IO[Unit] = boom
      def debug(ctx: Map[String, String], t: Throwable)(message: => String): IO[Unit] = boom
      def info(ctx: Map[String, String], t: Throwable)(message: => String): IO[Unit] = boom
      def warn(ctx: Map[String, String], t: Throwable)(message: => String): IO[Unit] = boom
      def error(ctx: Map[String, String], t: Throwable)(message: => String): IO[Unit] = boom
    }

  test("a sink whose log write fails does not fail the sign-in it is recording") {
    Ref.of[IO, Int](0).flatMap { attempts =>
      LoggingAuthAuditSink
        .make[IO](failingLogger(attempts))
        .record(refusal)
        .attempt
        .flatMap(outcome => attempts.get.map(tried => (outcome, tried)))
        .map { (outcome, tried) =>
          // The write was attempted — otherwise this passes for a sink that records nothing — and its
          // failure did not reach the caller.
          assertEquals(tried, 1)
          assert(outcome.isRight, s"the sink propagated a failure: $outcome")
        }
    }
  }

  test("an authentication line is written under the same field names a mutation line uses") {
    // The rule the file argues for: one query over the trail answers "everything this person did
    // today" without knowing which of the two record shapes each line came from. Four of these five
    // keys are `LoggingAuditSink`'s own constants, read from there rather than retyped, so a rename
    // on either side is a red here rather than a silent divergence between two log writers.
    FakeStructuredLogger[IO].flatMap { logger =>
      LoggingAuthAuditSink.make[IO](logger).record(refusal) >> logger.entries.map { entries =>
        assertEquals(entries.map(_.level), List("info"))
        val fields = entries.head.context

        assertEquals(fields.get(LoggingAuditSink.Field.Operation), Some("auth.login"))
        assertEquals(fields.get(LoggingAuditSink.Field.Outcome), Some("refused"))
        assertEquals(
          fields.get(LoggingAuditSink.Field.Principal),
          Some("anonymous (authentication is not enabled)")
        )
        assertEquals(fields.get(LoggingAuditSink.Field.PrincipalKind), Some("anonymous"))
        // The one field only this sink writes, because a refused login has a name that was *attempted*
        // and no principal at all.
        assertEquals(fields.get(LoggingAuthAuditSink.Field.Subject), Some("root"))
        assertEquals(fields.get(s"${LoggingAuditSink.DetailPrefix}provider"), Some("local"))
      }
    }
  }

  test("the two sinks spell the same four facts with the same four keys") {
    // Stated as an equality between the two writers rather than as five string literals, so that the
    // property is "they agree" and not "they happen to match what somebody typed here today".
    val mutationKeys = Set(
      LoggingAuditSink.Field.Operation,
      LoggingAuditSink.Field.Principal,
      LoggingAuditSink.Field.PrincipalKind,
      LoggingAuditSink.Field.Outcome
    )

    FakeStructuredLogger[IO].flatMap { logger =>
      LoggingAuthAuditSink.make[IO](logger).record(success) >> logger.entries.map { entries =>
        val written = entries.head.context.keySet

        assertEquals(mutationKeys.diff(written), Set.empty[String], "a shared audit field went missing")
        assertEquals(entries.head.context.get(LoggingAuditSink.Field.Outcome), Some("succeeded"))
        assertEquals(entries.head.context.get(LoggingAuthAuditSink.Field.Subject), Some("alice"))
      }
    }
  }

  test("a refusal records the name that was attempted and no reason for the refusal") {
    // The account-enumeration rule from `AuthenticationRecord`'s own scaladoc: "no such user" and
    // "wrong password" in a searchable log is an oracle for anybody who can read the log. The trail
    // says `refused` and the service's debug log says which.
    FakeStructuredLogger[IO].flatMap { logger =>
      LoggingAuthAuditSink.make[IO](logger).record(refusal) >> logger.entries.map { entries =>
        val entry = entries.head
        assertEquals(entry.context.get(LoggingAuditSink.Field.Outcome), Some(MutationOutcome.Refused.label))
        assert(entry.message.contains("root"), entry.message)
        assert(!entry.message.toLowerCase.contains("password"), entry.message)
        assert(!entry.context.values.exists(_.toLowerCase.contains("no such user")), entry.context.toString)
      }
    }
  }
}
