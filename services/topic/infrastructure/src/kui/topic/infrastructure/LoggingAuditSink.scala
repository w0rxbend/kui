package kui.topic.infrastructure

import cats.effect.kernel.Sync
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger

import kui.security.audit.{AuditSink, MutationRecord}

/** M5's audit sink for the topic service: one structured log line per mutation (ADR-047).
  *
  * A log line rather than the `__kui_audit` topic, which arrives behind this same port. The point of shipping
  * it with the first topic mutation is that the *record* exists from the product's first write: a mutation
  * trail that begins one milestone after mutations do has a hole in it that nothing can fill later. A topic
  * that was deleted and is now simply absent leaves no other trace at all.
  *
  * Two properties, and both are tested. It never fails the operation it is recording — every failure is
  * caught, because a sink that could fail would one day refuse a topic delete over a full disk. And it writes
  * structured fields rather than a rendered sentence, so that the Kafka sink is a second implementation of
  * the same record rather than a parser of this one's prose.
  *
  * The field names are the message and consumer services', deliberately: three services writing the same
  * trail with different key names would make "everything that changed this cluster today" an unanswerable
  * question.
  */
object LoggingAuditSink {

  object Field {
    val Operation: String = "audit.operation"
    val Cluster: String = "audit.cluster"
    val Resource: String = "audit.resource"
    val Principal: String = "audit.principal"
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
          Field.Principal -> entry.principal,
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
