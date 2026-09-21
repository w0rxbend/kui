package kui.connect.application

import java.time.Instant

import cats.Applicative

import kui.connect.domain.ConnectorOperation
import kui.kernel.{ClusterId, ConnectName, ConnectorName}
import kui.security.Principal
import kui.security.audit.MutationOutcome

/** One pause, resume or restart, attempted or made.
  *
  * ==Why this is not a `MutationRecord`==
  *
  * ADR-047 §3 requires one audit record per mutation and `kui.security.audit.MutationRecord` is that record.
  * Its `kind` is a `MutationKind`, a sealed enum in `libs/security-core` with twelve cases and none for a
  * connector operation. Wave 7's partition gives `libs/security-core` to nobody, so this packet cannot add
  * the thirteenth, and ADR-051 §5 already settled what a service does in that position: it does not invent a
  * second vocabulary locally, and it does not reuse the nearest case, because either produces an audit trail
  * whose answer to "what changed today" depends on which service did it.
  *
  * What it does instead is `AcknowledgementRecord`'s answer, one service over, which is itself
  * `AuthenticationRecord`'s: a **second record type** that shares the vocabulary rather than forking it. This
  * record shares `MutationOutcome`, so "how it ended" is one enum across the whole trail; it renders its
  * principal through `AuditPrincipal`, so "who" has one spelling; and the sink that writes it writes
  * `LoggingAuditSink`'s own field names, imported from that object rather than retyped.
  *
  * The one line that would remove this type is asserted rather than described: `MutationKindGapSuite` fails
  * the day `MutationKind` grows a case whose operation starts with `connect.`, which is the day this record
  * collapses into `MutationRecord` and this file is deleted.
  */
final case class ConnectorOperationRecord(
    at: Instant,
    principal: Principal,
    cluster: ClusterId,
    connect: ConnectName,
    connector: ConnectorName,
    operation: ConnectorOperation,
    outcome: MutationOutcome,
    detail: Map[String, String]
) {

  /** `payments/orders-sink`, which is the name Kafbat's RBAC patterns are written against and the name
    * `ResourceAccess.connector` builds. An audit trail's `resource` has to be a value somebody can look up,
    * and a connector name alone is ambiguous across two Connect clusters against one Kafka cluster — which is
    * the arrangement `ConnectClusterSettings` exists for.
    */
  def resource: String = ConnectorOperationRecord.resourceOf(connect, connector)
}

object ConnectorOperationRecord {

  def resourceOf(connect: ConnectName, connector: ConnectorName): String =
    s"${connect.value}/${connector.value}"

  given CanEqual[ConnectorOperationRecord, ConnectorOperationRecord] = CanEqual.derived
}

/** Where connector-operation records go.
  *
  * The port has `AuditSink`'s contract, which is the part that matters: `record` must not fail the operation
  * it is describing. A sink that could refuse would make the audit trail an availability dependency of
  * restarting a dead connector, and an operator who cannot restart a sink because a log disk is full is worse
  * off than one whose bookkeeping is a minute late.
  */
trait ConnectorOperationSink[F[_]] {
  def record(entry: ConnectorOperationRecord): F[Unit]
}

object ConnectorOperationSink {

  /** For tests and for a deployment that has deliberately turned auditing off. Never the default. */
  def noop[F[_]: Applicative]: ConnectorOperationSink[F] = new ConnectorOperationSink[F] {
    def record(entry: ConnectorOperationRecord): F[Unit] = Applicative[F].unit
  }
}

/** What an accepted operation answers.
  *
  * It carries no connector state, and that absence is the type's whole content: Connect answers `202
  * Accepted` with an empty body, so the only state available to put here is the state *before* the call.
  * `acceptedAt` is KUI's own instant, which is what lets a screen say when it asked rather than leaving a
  * button that appears to have done nothing.
  */
final case class AcceptedOperation(
    connect: ConnectName,
    connector: ConnectorName,
    operation: ConnectorOperation,
    acceptedAt: Instant
)

object AcceptedOperation {
  given CanEqual[AcceptedOperation, AcceptedOperation] = CanEqual.derived
}
