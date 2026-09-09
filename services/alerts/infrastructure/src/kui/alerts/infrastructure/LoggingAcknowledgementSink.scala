package kui.alerts.infrastructure

import cats.effect.kernel.Sync
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger

import kui.alerts.application.{AcknowledgementRecord, AcknowledgementSink}
import kui.observability.audit.LoggingAuditSink
import kui.security.audit.AuditPrincipal

/** One structured log line per acknowledgement, in the same trail every other mutation writes to.
  *
  * ==The field names are imported, not retyped==
  *
  * `LoggingAuditSink.Field` is the vocabulary of KUI's audit trail — `audit.operation`, `audit.cluster`,
  * `audit.resource`, `audit.principal`, `audit.principal_kind`, `audit.outcome` — and this sink writes those
  * exact keys by referring to that object rather than by spelling the strings again. That is the whole
  * defence against the drift ADR-051 §5 refuses to create: an acknowledgement is a second record *type*, and
  * a second record type that wrote `alert.who` instead of `audit.principal` would be a second audit trail,
  * which is the thing nobody can query.
  *
  * `audit.before` and `audit.after` are absent, and their absence is a fact rather than an omission. The two
  * fields hold a scalar the operation moved — the offsets a group was committed at, and the offsets it is
  * committed at now — and an acknowledgement moves no scalar: the event was open and now it is closed, which
  * `audit.outcome` and `audit.operation` already say between them. Writing `open` and `closed` into them
  * would put a constant in a field a reader diffs.
  *
  * ==It never fails the operation it is recording==
  *
  * Every failure inside it is caught and dropped, for `LoggingAuditSink`'s stated reason: a sink that could
  * refuse would one day stop an operator silencing an alert because a log disk was full.
  */
object LoggingAcknowledgementSink {

  def make[F[_]: Sync](logger: StructuredLogger[F]): AcknowledgementSink[F] =
    new AcknowledgementSink[F] {

      def record(entry: AcknowledgementRecord): F[Unit] = {
        val fields =
          Map(
            LoggingAuditSink.Field.Operation -> AcknowledgementRecord.Operation,
            LoggingAuditSink.Field.Cluster -> entry.cluster.value,
            LoggingAuditSink.Field.Resource -> entry.resource,
            LoggingAuditSink.Field.Principal -> AuditPrincipal.render(entry.principal),
            LoggingAuditSink.Field.PrincipalKind -> AuditPrincipal.kindOf(entry.principal),
            LoggingAuditSink.Field.Outcome -> entry.outcome.label
          ) ++
            entry.detail.map((key, value) => s"${LoggingAuditSink.DetailPrefix}$key" -> value)

        logger
          .info(fields)(
            s"${AcknowledgementRecord.Operation} on ${entry.resource}: ${entry.outcome.label}"
          )
          .handleError(_ => ())
      }
    }
}
