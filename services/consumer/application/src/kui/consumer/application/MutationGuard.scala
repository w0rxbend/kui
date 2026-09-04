package kui.consumer.application

import cats.effect.kernel.Temporal
import cats.effect.syntax.all.*
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger

import kui.consumer.domain.{AuditSink, MutationOutcome, MutationRecord}
import kui.kernel.ClusterId
import kui.kernel.error.{ApplicationError, ErrorCode, KuiError}

/** Every mutation this service can perform.
  *
  * M5's "enumerate every endpoint and assert each one is classified" test reads this set. A mutation added
  * without a case here does not compile, because `MutationGuard.guard` takes one — which is the enforcement
  * that stops the classification from being a documented rule nothing checks.
  */
enum MutationKind(val operation: String) {
  case ResetOffsets extends MutationKind("consumer.group.offsets.reset")
  case DeleteOffsets extends MutationKind("consumer.group.offsets.delete")
  case DeleteGroup extends MutationKind("consumer.group.delete")
}

object MutationKind {
  val All: List[MutationKind] = values.toList
  given CanEqual[MutationKind, MutationKind] = CanEqual.derived
}

/** The only way a mutation happens in this service (ADR-047).
  *
  * M4 ships an offset reset before read-only mode, RBAC and the audit topic exist — M5 and M6 build those.
  * This is the substitute that ships *with* the operation rather than after it, and it is three things at
  * once: the read-only refusal, the audit record, and the snapshot invalidation that stops the next read from
  * serving the pre-mutation state.
  */
trait MutationGuard[F[_]] {

  /** Wraps one mutation.
    *
    * The order matters and is tested:
    *
    *   1. resolve the cluster's profile;
    *   2. if it is read-only, refuse with `KUI-READ-ONLY` **without touching the Kafka client**, and record
    *      the refusal — an attempt to change a read-only cluster is exactly the kind of thing an audit trail
    *      exists to have noticed;
    *   3. run the operation;
    *   4. record the outcome — always, including on failure and on cancellation;
    *   5. invalidate the snapshot on success.
    */
  def guard[A](
      cluster: ClusterId,
      kind: MutationKind,
      resource: String,
      before: Map[String, Long],
      after: Map[String, Long]
  )(op: F[Either[KuiError, A]]): F[Either[KuiError, A]]
}

object MutationGuard {

  def make[F[_]: Temporal](
      profiles: ClusterProfileSource[F],
      audit: AuditSink[F],
      snapshots: GroupSnapshots[F],
      logger: StructuredLogger[F],
      principal: F[String]
  ): MutationGuard[F] =
    new MutationGuard[F] {

      def guard[A](
          cluster: ClusterId,
          kind: MutationKind,
          resource: String,
          before: Map[String, Long],
          after: Map[String, Long]
      )(op: F[Either[KuiError, A]]): F[Either[KuiError, A]] = {
        val context = Map(
          "service.name" -> ConsumerService.Id.value,
          "cluster.id" -> cluster.value,
          "operation" -> kind.operation,
          "resource" -> resource
        )

        def write(outcome: MutationOutcome): F[Unit] =
          for {
            at <- Temporal[F].realTimeInstant
            who <- principal
            _ <- audit
              .record(
                MutationRecord(at, cluster, kind.operation, resource, who, before, after, outcome, None)
              )
              // The sink never fails the operation it is recording. A sink that could would one day
              // refuse an offset reset because a log disk was full, and the operator's cluster
              // matters more than KUI's bookkeeping.
              .handleErrorWith(failure =>
                logger.error(context)(s"the audit record could not be written: ${failure.getMessage}")
              )
          } yield ()

        profiles.profileOf(cluster).flatMap {
          case Left(error) =>
            write(MutationOutcome.Failed(error.code.wire, error.message)).as(error.asLeft[A])

          case Right(profile) if profile.readOnly =>
            val refusal = ApplicationError.Refused(
              ErrorCode.ReadOnly,
              s"cluster ${profile.displayName} is configured read-only, so ${kind.operation} is not accepted"
            )

            logger.info(context)("refused: the cluster is read-only") >>
              write(MutationOutcome.Refused(refusal.code.wire, refusal.message)).as(refusal.asLeft[A])

          case Right(_) =>
            op.guaranteeCase {
              // `guaranteeCase`, so a cancelled or errored mutation is recorded too. Kafka gives no
              // guarantee that a cancelled write was *not* applied, and a record that claimed either
              // would be a lie; `Unknown` tells an operator to go and look.
              case cats.effect.kernel.Outcome.Succeeded(_) => Temporal[F].unit
              case cats.effect.kernel.Outcome.Errored(failure) =>
                write(MutationOutcome.Failed(ErrorCode.Internal.wire, failure.getMessage))
              case cats.effect.kernel.Outcome.Canceled() =>
                write(MutationOutcome.Unknown("the operation was cancelled after the request was sent"))
            }.flatMap {
              case Right(value) =>
                write(MutationOutcome.Succeeded) >>
                  snapshots.invalidate(cluster, s"${kind.operation} on $resource").as(value.asRight[KuiError])
              case Left(error) =>
                val outcome =
                  if error.code.httpStatus < 500 then MutationOutcome.Refused(error.code.wire, error.message)
                  else MutationOutcome.Failed(error.code.wire, error.message)

                write(outcome).as(error.asLeft[A])
            }
        }
      }
    }
}
