package kui.alerts.application

import cats.effect.kernel.{Outcome, Temporal}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger

import kui.kernel.ClusterId
import kui.kernel.error.{ApplicationError, ErrorCode, KuiError}
import kui.security.Principal
import kui.security.audit.MutationOutcome

/** The only way a mutation happens in this service (ADR-047).
  *
  * ==It is the consumer service's guard and not the topic or message service's==
  *
  * All three implement one classification and they disagree about one case, which is the case this service
  * copies deliberately: a **cancelled** mutation is `MutationOutcome.Unknown`. `AuditSink.scala`'s own words
  * for why — "Kafka gives no guarantee that it was not applied, so a record claiming either would be a lie" —
  * are about a Kafka write, and the argument survives the move to KUI's own store intact: a cancellation
  * lands between the store's compare and its swap, and a record saying `Failed` would tell an incident review
  * that the bell was still ringing when it may not have been. The other two services write `Failed` here and
  * W6-A1 repairs them.
  *
  * ==The order, and it is asserted rather than described==
  *
  *   1. resolve the cluster's profile;
  *   2. if it is read-only, refuse with `KUI-READ-ONLY` **without touching the store**, and record the
  *      refusal — an attempt to change a read-only cluster is exactly what an audit trail exists to notice;
  *   3. run the operation;
  *   4. record the outcome — always, including on failure and on cancellation.
  *
  * There is no step 5. The consumer service's guard invalidates a snapshot at the end; this service's store
  * *is* the state, so there is nothing downstream of it holding a stale copy.
  *
  * ==On refusing an acknowledgement on a read-only cluster==
  *
  * An acknowledgement writes to KUI's store and never to Kafka, so this refusal is stricter than the
  * operation needs. It is the answer `Action.AlertsAcknowledge` already encodes — `isAlter = true` decides
  * the audit question and the read-only question with one field — and ADR-053 §1 argues it rather than
  * inheriting it. `RbacLawsSuite` asserts the vocabulary half; this guard is the half that runs.
  */
trait MutationGuard[F[_]] {

  def guard[A](principal: Principal, cluster: ClusterId, resource: String)(
      op: F[Either[KuiError, A]]
  ): F[Either[KuiError, A]]
}

object MutationGuard {

  def make[F[_]: Temporal](
      profiles: ClusterProfileSource[F],
      audit: AcknowledgementSink[F],
      logger: StructuredLogger[F]
  ): MutationGuard[F] =
    new MutationGuard[F] {

      def guard[A](principal: Principal, cluster: ClusterId, resource: String)(
          op: F[Either[KuiError, A]]
      ): F[Either[KuiError, A]] = {
        val context = Map(
          "service.name" -> AlertsService.Id.value,
          "cluster.id" -> cluster.value,
          "operation" -> AcknowledgementRecord.Operation,
          "resource" -> resource
        )

        def write(outcome: MutationOutcome, reason: Option[String]): F[Unit] =
          for {
            at <- Temporal[F].realTimeInstant
            _ <- audit
              .record(
                AcknowledgementRecord(
                  at = at,
                  principal = principal,
                  cluster = cluster,
                  resource = resource,
                  outcome = outcome,
                  detail = reason.map("reason" -> _).toMap
                )
              )
              // The sink never fails the operation it is recording, for `LoggingAuditSink`'s reason.
              .handleErrorWith(failure =>
                logger.error(context)(s"the audit record could not be written: ${failure.getMessage}")
              )
          } yield ()

        profiles.profileOf(cluster).flatMap {
          case Left(error) =>
            write(MutationOutcome.Failed, Some(s"${error.code.wire}: ${error.message}")).as(error.asLeft[A])

          case Right(profile) if profile.readOnly =>
            val refusal = ApplicationError.Refused(
              ErrorCode.ReadOnly,
              s"cluster ${profile.displayName} is configured read-only, so " +
                s"${AcknowledgementRecord.Operation} is not accepted"
            )

            logger.info(context)("refused: the cluster is read-only") >>
              write(MutationOutcome.Refused, Some(s"${refusal.code.wire}: ${refusal.message}"))
                .as(refusal.asLeft[A])

          case Right(_) =>
            op.guaranteeCase {
              case Outcome.Succeeded(_) => Temporal[F].unit
              case Outcome.Errored(failure) =>
                write(
                  MutationOutcome.Failed,
                  Some(s"${ErrorCode.Internal.wire}: ${Option(failure.getMessage).getOrElse("")}")
                )
              case Outcome.Canceled() =>
                write(
                  MutationOutcome.Unknown,
                  Some("the operation was cancelled after the store was asked to close the event")
                )
            }.flatMap {
              case Right(value) => write(MutationOutcome.Succeeded, None).as(value.asRight[KuiError])
              case Left(error) =>
                val outcome =
                  if error.code.httpStatus < 500 then MutationOutcome.Refused else MutationOutcome.Failed

                write(outcome, Some(s"${error.code.wire}: ${error.message}")).as(error.asLeft[A])
            }
        }
      }
    }
}
