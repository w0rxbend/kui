package kui.observability.audit

import cats.effect.kernel.Sync
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger

import kui.security.audit.{AuditPrincipal, AuditSink, MutationRecord}

/** One structured log line per mutation, for every service that mutates (ADR-047).
  *
  * ==Why there is one of these and not three==
  *
  * There were three: the message, topic and consumer services each carried their own copy, two of them
  * character-for-character identical and the third writing different field names for the same facts. That is
  * a failure with a shape worth naming: an audit trail assembled from three implementations of one idea
  * cannot answer "everything that changed this cluster today", because the answer depends on which
  * service happened to do it.
  *
  * It lives in `libs/observability` rather than in `libs/security-core` for a mechanical reason: the record
  * and the port are cross-compiled for the browser, and log4cats is not. `security-core` states what an audit
  * record *is*; this module knows how one is written to a log.
  *
  * ==The two properties, both tested==
  *
  * It never fails the operation it is recording. Every failure inside it is caught and dropped, because a
  * sink that could refuse would one day refuse a topic delete because a log disk was full, and an operator's
  * cluster matters more than KUI's bookkeeping.
  *
  * It writes structured fields rather than a rendered sentence, so that the `__kui_audit` Kafka sink (AD-001)
  * is a second implementation of the same record rather than a parser of this one's prose.
  */
object LoggingAuditSink {

  /** The log field names.
    *
    * Fixed here so that the Kafka sink, an E2E that greps the log and any dashboard built on it all read the
    * same keys. Renaming one of these is a breaking change for whatever is reading the trail.
    */
  object Field {
    val Operation: String = "audit.operation"
    val Cluster: String = "audit.cluster"
    val Resource: String = "audit.resource"

    /** Who did it, rendered by `AuditPrincipal.render`. */
    val Principal: String = "audit.principal"

    /** How KUI came to believe that identity: `anonymous`, `session`, `bearer`, `system`. It is a separate
      * field from the name because "nobody was signed in" is a different fact from "somebody called anonymous
      * was", and a reader filtering the trail needs to tell them apart without parsing prose.
      */
    val PrincipalKind: String = "audit.principal_kind"

    val Outcome: String = "audit.outcome"
    val Before: String = "audit.before"
    val After: String = "audit.after"
  }

  /** Detail keys are prefixed so that a record's own fields and the operation's cannot collide — a create
    * whose detail happened to be called `resource` would otherwise silently overwrite the resource.
    */
  val DetailPrefix: String = "audit.detail."

  def make[F[_]: Sync](logger: StructuredLogger[F]): AuditSink[F] = new AuditSink[F] {

    def record(entry: MutationRecord): F[Unit] = {
      val fields =
        Map(
          Field.Operation -> entry.kind.operation,
          Field.Cluster -> entry.cluster.value,
          Field.Resource -> entry.resource,
          Field.Principal -> AuditPrincipal.render(entry.principal),
          Field.PrincipalKind -> AuditPrincipal.kindOf(entry.principal),
          Field.Outcome -> entry.outcome.label
        ) ++
          entry.before.map(Field.Before -> _) ++
          entry.after.map(Field.After -> _) ++
          entry.detail.map((key, value) => s"$DetailPrefix$key" -> value)

      logger
        .info(fields)(s"${entry.kind.operation} on ${entry.resource}: ${entry.outcome.label}")
        .handleError(_ => ())
    }
  }
}
