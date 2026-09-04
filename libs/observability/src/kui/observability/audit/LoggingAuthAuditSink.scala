package kui.observability.audit

import cats.effect.kernel.Sync
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger

import kui.security.audit.{AuditPrincipal, AuthAuditSink, AuthenticationRecord}

/** One structured log line per authentication event, in the same fields [[LoggingAuditSink]] uses.
  *
  * The field names are shared deliberately and not by coincidence: `audit.operation`, `audit.principal`,
  * `audit.principal_kind` and `audit.outcome` mean the same things here as they do for a cluster mutation, so
  * one query over the trail answers "everything this person did today" without having to know which of the
  * two record shapes each line came from. `audit.subject` is the one field only this sink writes, and it
  * exists because a refused login has a name that was *attempted* and no principal at all.
  *
  * It never fails the operation it is recording, for the reason its neighbour gives: a sign-in that failed
  * because a log disk was full would be a worse outcome than a sign-in nobody wrote down.
  */
object LoggingAuthAuditSink {

  object Field {

    /** The login name that was attempted, which for a refusal is not necessarily an account that exists. */
    val Subject: String = "audit.subject"
  }

  def make[F[_]: Sync](logger: StructuredLogger[F]): AuthAuditSink[F] = new AuthAuditSink[F] {

    def record(entry: AuthenticationRecord): F[Unit] = {
      val fields =
        Map(
          LoggingAuditSink.Field.Operation -> entry.event.operation,
          Field.Subject -> entry.subject,
          LoggingAuditSink.Field.Principal -> AuditPrincipal.render(entry.principal),
          LoggingAuditSink.Field.PrincipalKind -> AuditPrincipal.kindOf(entry.principal),
          LoggingAuditSink.Field.Outcome -> entry.outcome.label
        ) ++ entry.detail.map((key, value) => s"${LoggingAuditSink.DetailPrefix}$key" -> value)

      logger
        .info(fields)(s"${entry.event.operation} for ${entry.subject}: ${entry.outcome.label}")
        .handleError(_ => ())
    }
  }
}
