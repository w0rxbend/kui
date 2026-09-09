package kui.alerts.application

import munit.FunSuite

import kui.security.audit.MutationKind

/** The one line that would delete [[AcknowledgementRecord]], asserted rather than described.
  *
  * ADR-047 §3 wants one `MutationRecord` per mutation and `MutationKind` has no case for an
  * acknowledgement. Wave 6's partition gives `libs/security-core` to nobody, so this service writes a second
  * record type that shares the vocabulary — `AuthenticationRecord`'s answer to the same shape — rather than
  * inventing a thirteenth case locally or reusing the nearest one, both of which ADR-051 §5 rejected for the
  * schema service's registration.
  *
  * ADR-051 asserted its gap instead of writing it down, and this is the same mechanism pointed the other
  * way. It goes **red** the day somebody adds
  *
  * `case AcknowledgeAlert extends MutationKind("alerts.event.acknowledge")`
  *
  * to `libs/security-core/src/kui/security/audit/AuditSink.scala`, which is the day
  * `AcknowledgementRecord`, `AcknowledgementSink` and `LoggingAcknowledgementSink` should be deleted and the
  * guard given an `AuditSink` instead. A comment saying so would be a comment; this is a failing build.
  */
final class MutationKindGapSuite extends FunSuite {

  test("no MutationKind names an alerts operation, which is why this service has its own record") {
    val alerts = MutationKind.values.filter(_.operation.startsWith("alerts.")).toList

    assertEquals(
      alerts,
      Nil,
      clue = "MutationKind now covers an alerts operation: delete AcknowledgementRecord, " +
        "AcknowledgementSink and LoggingAcknowledgementSink, and give MutationGuard an AuditSink"
    )
  }

  test("the operation name is spelled the way a MutationKind operation is spelled") {
    // Dotted, lower case, most general segment first — so the day it becomes one, nothing that reads the
    // trail has to change.
    assertEquals(AcknowledgementRecord.Operation, "alerts.event.acknowledge")
    assert(MutationKind.values.forall(kind => kind.operation == kind.operation.toLowerCase))
    assert(AcknowledgementRecord.Operation == AcknowledgementRecord.Operation.toLowerCase)
  }
}
