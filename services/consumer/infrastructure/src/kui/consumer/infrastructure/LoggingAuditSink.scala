package kui.consumer.infrastructure

import cats.effect.kernel.Sync
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger

import kui.consumer.domain.{AuditSink, MutationOutcome, MutationRecord}

/** M4's audit sink: one structured log line per mutation (ADR-047).
  *
  * A log line rather than the `__kui_audit` topic, which is M5's behind this same port. The point of shipping
  * it now is that the *record* exists from the product's first destructive operation: a mutation trail that
  * begins one milestone after mutations do has a hole in it that nothing can fill later.
  *
  * Two properties this sink must have, and both are tested. It never fails the operation it is recording —
  * every failure is caught and logged, because a sink that could fail a mutation would one day refuse an
  * offset reset over a full disk. And it writes structured fields rather than a rendered sentence, so that
  * M5's Kafka sink is a second implementation of the same record rather than a parser of this one's prose.
  */
object LoggingAuditSink {

  /** The log field names. Fixed here so that M5's sink, the E2E that greps the log and any dashboard built on
    * it all read the same keys.
    */
  object Field {
    val Operation: String = "audit.operation"
    val Cluster: String = "audit.cluster"
    val Resource: String = "audit.resource"
    val Principal: String = "audit.principal"
    val Outcome: String = "audit.outcome"
    val Reason: String = "audit.reason"
    val Before: String = "audit.before"
    val After: String = "audit.after"
    val TraceId: String = "audit.trace_id"
  }

  def make[F[_]: Sync](logger: StructuredLogger[F]): AuditSink[F] = new AuditSink[F] {

    def record(record: MutationRecord): F[Unit] = {
      val (outcome, reason) = describe(record.outcome)

      val fields = Map(
        Field.Operation -> record.operation,
        Field.Cluster -> record.cluster.value,
        Field.Resource -> record.resource,
        Field.Principal -> record.principal,
        Field.Outcome -> outcome,
        Field.Before -> render(record.before),
        Field.After -> render(record.after)
      ) ++ reason.map(Field.Reason -> _) ++ record.traceId.map(Field.TraceId -> _)

      logger
        .info(fields)(s"${record.operation} on ${record.resource}: $outcome")
        .handleError(_ => ())
    }
  }

  /** `topic-partition=offset`, sorted, so two records of the same change render identically and a diff
    * between them means something.
    */
  private def render(offsets: Map[String, Long]): String =
    offsets.toList.sortBy(_._1).map((partition, offset) => s"$partition=$offset").mkString(",")

  private def describe(outcome: MutationOutcome): (String, Option[String]) = outcome match {
    case MutationOutcome.Succeeded => ("succeeded", None)
    case MutationOutcome.Refused(code, reason) => ("refused", Some(s"$code: $reason"))
    case MutationOutcome.Failed(code, reason) => ("failed", Some(s"$code: $reason"))
    // Kafka gives no guarantee that a cancelled write was not applied, so this says exactly that
    // rather than guessing at either answer.
    case MutationOutcome.Unknown(reason) => ("unknown", Some(reason))
  }
}
