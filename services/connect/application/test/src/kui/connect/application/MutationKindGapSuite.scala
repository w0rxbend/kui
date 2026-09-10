package kui.connect.application

import munit.FunSuite

import kui.connect.domain.ConnectorOperation
import kui.security.audit.MutationKind

/** The one line that would delete [[ConnectorOperationRecord]], asserted rather than described.
  *
  * ADR-047 §3 wants one `MutationRecord` per mutation and `MutationKind` has no case for a connector
  * operation. Wave 7's partition gives `libs/security-core` to nobody, so this service writes a second record
  * type that shares the vocabulary — `AuthenticationRecord`'s answer to the same shape, and the alerts
  * service's — rather than inventing three cases locally or reusing the nearest one, both of which ADR-051 §5
  * rejected for the schema service's registration.
  *
  * It goes **red** the day somebody adds
  *
  * `case RestartConnector extends MutationKind("connect.connector.restart")`
  *
  * to `libs/security-core/src/kui/security/audit/AuditSink.scala`, which is the day
  * `ConnectorOperationRecord`, `ConnectorOperationSink` and `LoggingConnectorOperationSink` should be deleted
  * and the guard given an `AuditSink` instead. A comment saying so would be a comment; this is a failing
  * build.
  */
final class MutationKindGapSuite extends FunSuite {

  test("no MutationKind names a connect operation, which is why this service has its own record") {
    val connect = MutationKind.values.filter(_.operation.startsWith("connect.")).toList

    assertEquals(
      connect,
      Nil,
      clue = "MutationKind now covers a connect operation: delete ConnectorOperationRecord, " +
        "ConnectorOperationSink and LoggingConnectorOperationSink, and give MutationGuard an AuditSink"
    )
  }

  test("the operation names are spelled the way a MutationKind operation is spelled") {
    // Dotted, lower case, most general segment first — so the day they become MutationKinds, nothing that
    // reads the trail has to change.
    assertEquals(
      ConnectorOperation.values.map(_.operation).toList,
      List("connect.connector.pause", "connect.connector.resume", "connect.connector.restart")
    )
    assert(MutationKind.values.forall(kind => kind.operation == kind.operation.toLowerCase))
    assert(ConnectorOperation.values.forall(op => op.operation == op.operation.toLowerCase))
  }
}
