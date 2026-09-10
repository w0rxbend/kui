package kui.connect.infrastructure

import java.time.Instant

import cats.effect.IO
import cats.syntax.all.*

import kui.connect.application.ConnectorOperationRecord
import kui.connect.domain.ConnectorOperation
import kui.kernel.{ClusterId, ConnectName, ConnectorName}
import kui.observability.audit.LoggingAuditSink
import kui.security.Principal
import kui.security.audit.MutationOutcome
import kui.testkit.KuiIOSuite
import kui.testkit.fakes.FakeStructuredLogger

/** What one connector operation leaves in the audit trail.
  *
  * The keys are the assertion. A second record type that wrote `connector.who` instead of `audit.principal`
  * would be a second audit trail, which is the thing nobody can query — so this suite reads
  * `LoggingAuditSink.Field` and compares against the same object the sink writes from, and the case fails the
  * day somebody retypes a key here or there.
  */
final class LoggingConnectorOperationSinkSuite extends KuiIOSuite {

  private val record = ConnectorOperationRecord(
    at = Instant.parse("2026-09-03T10:11:12Z"),
    principal = Principal.Anonymous,
    cluster = ClusterId.unsafe("prod-eu"),
    connect = ConnectName.unsafe("payments"),
    connector = ConnectorName.unsafe("elastic-sink"),
    operation = ConnectorOperation.Restart,
    outcome = MutationOutcome.Succeeded,
    detail = Map.empty
  )

  test("one line carries the trail's own six keys, and the resource is <connect>/<connector>") {
    for {
      logger <- FakeStructuredLogger[IO]
      _ <- LoggingConnectorOperationSink.make[IO](logger).record(record)
      entries <- logger.entries
    } yield {
      assertEquals(entries.size, 1)

      val context = entries.head.context

      assertEquals(context.get(LoggingAuditSink.Field.Operation), Some("connect.connector.restart"))
      assertEquals(context.get(LoggingAuditSink.Field.Cluster), Some("prod-eu"))
      assertEquals(context.get(LoggingAuditSink.Field.Resource), Some("payments/elastic-sink"))
      assertEquals(context.get(LoggingAuditSink.Field.Outcome), Some("succeeded"))
      assert(context.contains(LoggingAuditSink.Field.Principal))
      assert(context.contains(LoggingAuditSink.Field.PrincipalKind))
    }
  }

  test("before and after are absent, because none of these three moves a scalar") {
    // The two fields hold a value the operation moved, and Connect answers `202 Accepted` and applies the
    // change later — so the state after this line was written is not a state KUI knows. Writing the state
    // *before* into `audit.after` would put a wrong value in the field a reader diffs.
    for {
      logger <- FakeStructuredLogger[IO]
      _ <- LoggingConnectorOperationSink.make[IO](logger).record(record)
      entries <- logger.entries
    } yield {
      assert(!entries.head.context.keys.exists(_.endsWith(".before")), clue = entries.head.context)
      assert(!entries.head.context.keys.exists(_.endsWith(".after")), clue = entries.head.context)
    }
  }

  test("a refusal's reason is carried under the trail's detail prefix rather than in the message alone") {
    for {
      logger <- FakeStructuredLogger[IO]
      _ <- LoggingConnectorOperationSink
        .make[IO](logger)
        .record(
          record.copy(
            outcome = MutationOutcome.Refused,
            detail = Map("reason" -> "KUI-READ-ONLY: cluster Production US is configured read-only")
          )
        )
      entries <- logger.entries
    } yield {
      assertEquals(
        entries.head.context.get(s"${LoggingAuditSink.DetailPrefix}reason"),
        Some("KUI-READ-ONLY: cluster Production US is configured read-only")
      )
      assertEquals(entries.head.context.get(LoggingAuditSink.Field.Outcome), Some("refused"))
    }
  }

  test("the outcome word is the shared enum's own label, so one trail has one vocabulary") {
    for {
      logger <- FakeStructuredLogger[IO]
      sink = LoggingConnectorOperationSink.make[IO](logger)
      _ <- MutationOutcome.values.toList.traverse(outcome => sink.record(record.copy(outcome = outcome)))
      entries <- logger.entries
    } yield assertEquals(
      entries.flatMap(_.context.get(LoggingAuditSink.Field.Outcome)),
      MutationOutcome.values.toList.map(_.label)
    )
  }
}
