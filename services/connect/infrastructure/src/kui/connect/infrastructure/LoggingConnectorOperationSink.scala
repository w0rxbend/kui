package kui.connect.infrastructure

import cats.effect.kernel.Sync
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger

import kui.connect.application.{ConnectorOperationRecord, ConnectorOperationSink}
import kui.observability.audit.LoggingAuditSink
import kui.security.audit.AuditPrincipal

/** One structured log line per connector operation, in the same trail every other mutation writes to.
  *
  * ==The field names are imported, not retyped==
  *
  * `LoggingAuditSink.Field` is the vocabulary of KUI's audit trail — `audit.operation`, `audit.cluster`,
  * `audit.resource`, `audit.principal`, `audit.principal_kind`, `audit.outcome` — and this sink writes those
  * exact keys by referring to that object rather than by spelling the strings again. That is the whole
  * defence against the drift ADR-051 §5 refuses to create: a connector operation is a second record *type*,
  * and a second record type that wrote `connector.who` instead of `audit.principal` would be a second audit
  * trail, which is the thing nobody can query.
  *
  * `audit.before` and `audit.after` are absent, and their absence is a fact rather than an omission. The two
  * fields hold a scalar the operation moved, and none of these three moves one: Connect answers `202
  * Accepted` and applies the change later, so the state after this line was written is not a state KUI knows.
  * Writing the state *before* into `audit.after` would put a wrong value in the field a reader diffs, which
  * is worse than leaving it out.
  *
  * ==It never fails the operation it is recording==
  *
  * Every failure inside it is caught and dropped, for `LoggingAuditSink`'s stated reason: a sink that could
  * refuse would one day stop an operator restarting a dead sink connector because a log disk was full.
  */
object LoggingConnectorOperationSink {

  def make[F[_]: Sync](logger: StructuredLogger[F]): ConnectorOperationSink[F] =
    new ConnectorOperationSink[F] {

      def record(entry: ConnectorOperationRecord): F[Unit] = {
        val fields =
          Map(
            LoggingAuditSink.Field.Operation -> entry.operation.operation,
            LoggingAuditSink.Field.Cluster -> entry.cluster.value,
            LoggingAuditSink.Field.Resource -> entry.resource,
            LoggingAuditSink.Field.Principal -> AuditPrincipal.render(entry.principal),
            LoggingAuditSink.Field.PrincipalKind -> AuditPrincipal.kindOf(entry.principal),
            LoggingAuditSink.Field.Outcome -> entry.outcome.label
          ) ++
            entry.detail.map((key, value) => s"${LoggingAuditSink.DetailPrefix}$key" -> value)

        logger
          .info(fields)(
            s"${entry.operation.operation} on ${entry.resource}: ${entry.outcome.label}"
          )
          .handleError(_ => ())
      }
    }
}
