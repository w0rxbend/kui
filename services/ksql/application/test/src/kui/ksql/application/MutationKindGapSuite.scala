package kui.ksql.application

import munit.FunSuite

import kui.security.audit.MutationKind

/** The one line that would delete [[KsqlStatementRecord]], asserted rather than described.
  *
  * ADR-047 §3 wants one `MutationRecord` per mutation and `MutationKind` has no case for a ksqlDB statement.
  * Wave 8's partition gives `libs/security-core` to an adversarial packet rather than to this one, so this
  * service writes a second record type that shares the vocabulary — `ConnectorOperationRecord`'s answer to
  * the same shape, and `AuthenticationRecord`'s — rather than inventing a case locally or reusing the nearest
  * one, both of which ADR-051 §5 rejected for the schema service's registration.
  *
  * It goes **red** the day somebody adds
  *
  * `case RunKsqlStatement extends MutationKind("ksql.statement")`
  *
  * to `libs/security-core/src/kui/security/audit/AuditSink.scala`, which is the day `KsqlStatementRecord`,
  * `KsqlStatementSink` and `LoggingKsqlStatementSink` should be deleted and the guard given an `AuditSink`
  * instead. A comment saying so would be a comment; this is a failing build.
  */
final class MutationKindGapSuite extends FunSuite {

  test("no MutationKind names a ksql operation, which is why this service has its own record") {
    val ksql = MutationKind.values.filter(_.operation.startsWith("ksql.")).toList

    assertEquals(
      ksql,
      Nil,
      clue = "MutationKind now covers a ksql operation: delete KsqlStatementRecord, KsqlStatementSink " +
        "and LoggingKsqlStatementSink, and give MutationGuard an AuditSink"
    )
  }

  test("the operation name is spelled the way a MutationKind operation is spelled") {
    // Dotted, lower case, most general segment first — so the day it becomes a `MutationKind`, nothing
    // that reads the trail has to change.
    assertEquals(KsqlPlanToken.Operation, "ksql.statement")
    assertEquals(KsqlPlanToken.Operation, KsqlPlanToken.Operation.toLowerCase)
    assert(MutationKind.values.forall(kind => kind.operation == kind.operation.toLowerCase))
  }
}
