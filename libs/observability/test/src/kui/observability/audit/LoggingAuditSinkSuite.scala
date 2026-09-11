package kui.observability.audit

import java.time.Instant

import cats.effect.IO
import cats.effect.kernel.Ref
import munit.CatsEffectSuite
import org.typelevel.log4cats.StructuredLogger

import kui.kernel.{ClusterId, UserName}
import kui.security.audit.{MutationKind, MutationOutcome, MutationRecord}
import kui.security.{Principal, PrincipalKind}
import kui.testkit.fakes.FakeStructuredLogger

/** The sink's own scaladoc says *"the two properties, both tested"*. Neither was.
  *
  * `LoggingAuditSink` is constructed by four services' wirings and by no suite in this module, so both of the
  * properties it names were executed by nothing here: deleting `.handleError(_ => ())` left every module in
  * `./mill libs.__.test + services.*` green while making a full log disk able to fail the topic delete it was
  * describing. This file is the missing half.
  *
  * The field names are pinned in the same place, because the scaladoc calls renaming one *"a breaking change
  * for whatever is reading the trail"* and the `__kui_audit` Kafka sink (AD-001) is written against them.
  */
final class LoggingAuditSinkSuite extends CatsEffectSuite {

  private val record: MutationRecord = MutationRecord(
    at = Instant.parse("2026-02-01T10:00:00Z"),
    principal = Principal.Anonymous,
    cluster = ClusterId.unsafe("local"),
    kind = MutationKind.DeleteTopic,
    resource = "payments.orders",
    before = Some("12 partitions"),
    after = None,
    outcome = MutationOutcome.Succeeded,
    detail = Map("resource" -> "a detail key that collides with a field name")
  )

  /** A logger whose every write fails, which is what a full disk or a broken appender looks like from here.
    */
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

  test("a sink whose log write fails does not fail the mutation it is recording") {
    Ref.of[IO, Int](0).flatMap { attempts =>
      LoggingAuditSink
        .make[IO](failingLogger(attempts))
        .record(record)
        .attempt
        .flatMap(outcome => attempts.get.map(tried => (outcome, tried)))
        .map { case (outcome, tried) =>
          // The write was attempted — otherwise this passes for a sink that records nothing — and its
          // failure did not reach the caller.
          assertEquals(tried, 1)
          assert(outcome.isRight, s"the sink propagated a failure: $outcome")
        }
    }
  }

  test("a mutation is one INFO line carrying every field the trail is queried by") {
    FakeStructuredLogger[IO].flatMap { logger =>
      LoggingAuditSink.make[IO](logger).record(record) >> logger.entries.map { entries =>
        assertEquals(entries.map(_.level), List("info"))
        val fields = entries.head.context

        assertEquals(fields.get(LoggingAuditSink.Field.Operation), Some("topic.delete"))
        assertEquals(fields.get(LoggingAuditSink.Field.Cluster), Some("local"))
        assertEquals(fields.get(LoggingAuditSink.Field.Resource), Some("payments.orders"))
        assertEquals(fields.get(LoggingAuditSink.Field.Outcome), Some("succeeded"))
        assertEquals(fields.get(LoggingAuditSink.Field.Before), Some("12 partitions"))
        // `after` was `None` on this record, and an absent value is an absent field rather than an
        // empty string: a reader filtering on it must not find every delete.
        assertEquals(fields.get(LoggingAuditSink.Field.After), None)
      }
    }
  }

  test("a detail key that collides with a field name cannot overwrite the field") {
    FakeStructuredLogger[IO].flatMap { logger =>
      LoggingAuditSink.make[IO](logger).record(record) >> logger.entries.map { entries =>
        val fields = entries.head.context

        assertEquals(fields.get(LoggingAuditSink.Field.Resource), Some("payments.orders"))
        assertEquals(
          fields.get(s"${LoggingAuditSink.DetailPrefix}resource"),
          Some("a detail key that collides with a field name")
        )
      }
    }
  }

  test("an anonymous principal is written with the one spelling, beside its kind") {
    FakeStructuredLogger[IO].flatMap { logger =>
      LoggingAuditSink.make[IO](logger).record(record) >> logger.entries.map { entries =>
        val fields = entries.head.context

        assertEquals(
          fields.get(LoggingAuditSink.Field.Principal),
          Some("anonymous (authentication is not enabled)")
        )
        assertEquals(fields.get(LoggingAuditSink.Field.PrincipalKind), Some("anonymous"))
      }
    }
  }

  test("a named principal is written as the name alone, and the kind is what tells them apart") {
    val session = Principal(UserName.unsafe("anonymous"), Set.empty, PrincipalKind.Session)

    FakeStructuredLogger[IO].flatMap { logger =>
      LoggingAuditSink.make[IO](logger).record(record.copy(principal = session)) >> logger.entries.map {
        entries =>
          val fields = entries.head.context

          // A person really called `anonymous` and nobody-was-signed-in are two different facts, and the
          // trail has to answer "who changed this cluster" without them collapsing into one row.
          assertEquals(fields.get(LoggingAuditSink.Field.Principal), Some("anonymous"))
          assertEquals(fields.get(LoggingAuditSink.Field.PrincipalKind), Some("session"))
      }
    }
  }
}
