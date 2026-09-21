package kui.alerts.infrastructure

import java.time.Instant

import cats.effect.IO
import munit.CatsEffectSuite

import kui.alerts.application.AcknowledgementRecord
import kui.kernel.{ClusterId, UserName}
import kui.observability.audit.LoggingAuditSink
import kui.security.audit.MutationOutcome
import kui.security.{Principal, PrincipalKind}
import kui.testkit.fakes.FakeStructuredLogger

/** That an acknowledgement lands in the **same** audit trail every other mutation lands in.
  *
  * ADR-053 §5's whole argument for a second record type rests on this: the record is second, the vocabulary
  * is not. If this sink wrote `alerts.who` where `LoggingAuditSink` writes `audit.principal`, KUI would have
  * two audit trails and the answer to "everything this person did today" would depend on which service did it
  * — which is exactly the drift ADR-051 §5 refused to create and this file exists to avoid.
  *
  * It is asserted against the field *constants* rather than against string literals, so the day somebody
  * renames a field in `LoggingAuditSink` both sinks move together and this suite still passes. What it
  * catches is this sink drifting away from that object, which is the failure that can actually happen.
  */
final class LoggingAcknowledgementSinkSuite extends CatsEffectSuite {

  private val record = AcknowledgementRecord(
    at = Instant.parse("2026-09-07T12:00:00Z"),
    principal = Principal(UserName.unsafe("ada"), Set.empty, PrincipalKind.Session),
    cluster = ClusterId.unsafe("prod-eu"),
    resource = "disk-usage.broker-1.1757246400000",
    outcome = MutationOutcome.Succeeded,
    detail = Map("reason" -> "the operator said so")
  )

  private def written(entry: AcknowledgementRecord): IO[Map[String, String]] =
    for {
      logger <- FakeStructuredLogger[IO]
      _ <- LoggingAcknowledgementSink.make[IO](logger).record(entry)
      entries <- logger.entries
    } yield entries.headOption.map(_.context).getOrElse(Map.empty)

  test("the line carries the audit trail's own field names and no invented ones") {
    written(record).map { fields =>
      assertEquals(fields.get(LoggingAuditSink.Field.Operation), Some("alerts.event.acknowledge"))
      assertEquals(fields.get(LoggingAuditSink.Field.Cluster), Some("prod-eu"))
      assertEquals(fields.get(LoggingAuditSink.Field.Resource), Some(record.resource))
      assertEquals(fields.get(LoggingAuditSink.Field.Principal), Some("ada"))
      assertEquals(fields.get(LoggingAuditSink.Field.PrincipalKind), Some("session"))
      assertEquals(fields.get(LoggingAuditSink.Field.Outcome), Some("succeeded"))

      // Every key is one this trail already uses. A key outside the `audit.` namespace is a second
      // vocabulary, and a second vocabulary is a second trail.
      assert(fields.keySet.forall(_.startsWith("audit.")), clue = fields.keySet)
    }
  }

  test("a detail is prefixed, so an operation's own fields cannot overwrite the record's") {
    written(record).map { fields =>
      assertEquals(
        fields.get(s"${LoggingAuditSink.DetailPrefix}reason"),
        Some("the operator said so")
      )
      assertEquals(fields.get("reason"), None)
    }
  }

  test("before and after are absent, because an acknowledgement moves no scalar") {
    // They hold the value an operation changed — the offsets a group was committed at, and the offsets it
    // is committed at now. Writing `open` and `closed` into them would put a constant in a field a reader
    // diffs.
    written(record).map { fields =>
      assertEquals(fields.get(LoggingAuditSink.Field.Before), None)
      assertEquals(fields.get(LoggingAuditSink.Field.After), None)
    }
  }

  test("a refusal is recorded with its own outcome, not dropped") {
    written(record.copy(outcome = MutationOutcome.Refused)).map { fields =>
      assertEquals(fields.get(LoggingAuditSink.Field.Outcome), Some("refused"))
    }
  }

  test("an anonymous principal is rendered with the reason attached, exactly as elsewhere") {
    // A bare `anonymous` in an audit record reads like a bug in the audit trail rather than like a fact
    // about the deployment, which is why `AuditPrincipal` has one spelling for it.
    written(record.copy(principal = Principal.Anonymous)).map { fields =>
      assertEquals(
        fields.get(LoggingAuditSink.Field.Principal),
        Some("anonymous (authentication is not enabled)")
      )
      assertEquals(fields.get(LoggingAuditSink.Field.PrincipalKind), Some("anonymous"))
    }
  }
}
