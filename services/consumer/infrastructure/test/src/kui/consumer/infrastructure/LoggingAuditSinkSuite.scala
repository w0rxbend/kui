package kui.consumer.infrastructure

import java.time.Instant

import cats.effect.IO

import kui.consumer.domain.{MutationOutcome, MutationRecord}
import kui.kernel.ClusterId
import kui.testkit.KuiIOSuite
import kui.testkit.fakes.FakeStructuredLogger

/** The audit trail's first sink, and the one thing it must never do: fail the operation it is recording. */
final class LoggingAuditSinkSuite extends KuiIOSuite {

  private val record: MutationRecord = MutationRecord(
    at = Instant.parse("2026-01-01T12:00:00Z"),
    cluster = ClusterId.unsafe("prod"),
    operation = "consumer.group.offsets.reset",
    resource = "orders-consumer",
    principal = MutationRecord.AnonymousPrincipal,
    before = Map("orders-1" -> 40L, "orders-0" -> 10L),
    after = Map("orders-0" -> 0L, "orders-1" -> 0L),
    outcome = MutationOutcome.Succeeded,
    traceId = Some("trace-1")
  )

  test("a mutation is recorded as structured fields, not as a rendered sentence") {
    for {
      logger <- FakeStructuredLogger[IO]
      _ <- LoggingAuditSink.make[IO](logger).record(record)
      lines <- logger.entries
    } yield {
      val fields = lines.head.context
      assertEquals(fields.get(LoggingAuditSink.Field.Operation), Some("consumer.group.offsets.reset"))
      assertEquals(fields.get(LoggingAuditSink.Field.Cluster), Some("prod"))
      assertEquals(fields.get(LoggingAuditSink.Field.Resource), Some("orders-consumer"))
      assertEquals(fields.get(LoggingAuditSink.Field.Outcome), Some("succeeded"))
      assertEquals(fields.get(LoggingAuditSink.Field.TraceId), Some("trace-1"))
    }
  }

  test("offsets are rendered in a stable order, so two records of the same change are identical") {
    for {
      logger <- FakeStructuredLogger[IO]
      _ <- LoggingAuditSink.make[IO](logger).record(record)
      lines <- logger.entries
    } yield {
      assertEquals(lines.head.context.get(LoggingAuditSink.Field.Before), Some("orders-0=10,orders-1=40"))
      assertEquals(lines.head.context.get(LoggingAuditSink.Field.After), Some("orders-0=0,orders-1=0"))
    }
  }

  test("a refusal records the code and the reason an operator can act on") {
    for {
      logger <- FakeStructuredLogger[IO]
      _ <- LoggingAuditSink
        .make[IO](logger)
        .record(record.copy(outcome = MutationOutcome.Refused("KUI-READ-ONLY", "the cluster is read-only")))
      lines <- logger.entries
    } yield {
      assertEquals(lines.head.context.get(LoggingAuditSink.Field.Outcome), Some("refused"))
      assert(lines.head.context.get(LoggingAuditSink.Field.Reason).exists(_.contains("KUI-READ-ONLY")))
    }
  }

  test("a cancelled mutation is recorded as unknown, because Kafka does not say whether it landed") {
    for {
      logger <- FakeStructuredLogger[IO]
      _ <- LoggingAuditSink.make[IO](logger).record(record.copy(outcome = MutationOutcome.Unknown("cancelled")))
      lines <- logger.entries
    } yield assertEquals(lines.head.context.get(LoggingAuditSink.Field.Outcome), Some("unknown"))
  }
}
