package kui.message.application.produce

import cats.effect.kernel.{Outcome, Temporal}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger

import kui.kernel.ClusterId
import kui.kernel.error.{ApplicationError, ErrorCode, KuiError}
import kui.message.domain.ports.ClusterProfileSource
import kui.security.audit.{AuditSink, MutationKind, MutationOutcome, MutationRecord}

/** The only way this service changes a cluster (ADR-047).
  *
  * KUI's first mutations ship before global read-only mode, RBAC and the audit topic exist. ADR-047 says they
  * may not ship *bare*, and names the three things that have to arrive with them. All three are here:
  *
  *   1. the per-cluster `readOnly` refusal — `KUI-READ-ONLY`, decided **before any Kafka client is touched**,
  *      which on this path means before a producer is opened, before a serde runs and before a byte is read;
  *   2. the audit record — exactly one per attempt, successful or not, through `AuditSink[F]`;
  *   3. the `MutationKind` marker, which is a parameter of [[guard]] rather than a convention, so a mutation
  *      added without a classification does not compile.
  *
  * The consumer service has the same guard over the same port, and the two are deliberately separate objects
  * rather than one shared one: rule A11 forbids either service seeing the other's application layer, and the
  * thing that must not drift — the record, the outcome vocabulary and the port — is declared once in
  * `libs/security-core`, which is where both of them read it from.
  *
  * ==Why the audit sink can never fail a mutation==
  *
  * Because an operator's cluster matters more than KUI's bookkeeping. A sink that could refuse would one day
  * refuse a produce because a log disk was full. Every failure inside it is caught, logged, and dropped.
  */
trait MutationGuard[F[_]] {

  /** Wraps one mutation. The order is fixed and tested:
    *
    *   1. resolve the cluster's profile — a cluster this deployment does not have is `KUI-CLUSTER-NOT-FOUND`
    *      and is recorded, because an attempt to write to a cluster that is not there is worth having
    *      noticed;
    *   2. if the profile says read-only, refuse and record the refusal, without running `op` at all;
    *   3. run `op`;
    *   4. record the outcome — always, including on failure and on cancellation.
    *
    * @param resource
    *   what was operated on, in the shape an operator recognises: `orders.v1`, `orders.v1:3`.
    * @param detail
    *   short strings worth recording. **Never a payload and never a credential**: an audit log is routinely
    *   more widely readable than the data it describes, which is exactly why it must not contain the data.
    */
  def guard[A](
      cluster: ClusterId,
      kind: MutationKind,
      resource: String,
      detail: Map[String, String] = Map.empty
  )(op: F[Either[KuiError, A]]): F[Either[KuiError, A]]
}

object MutationGuard {

  /** The log context every line this guard writes carries, so an operator can grep one operation out of a
    * busy service.
    */
  val ServiceName: String = "kui-message"

  def make[F[_]: Temporal](
      profiles: ClusterProfileSource[F],
      audit: AuditSink[F],
      logger: StructuredLogger[F],
      /** Who is doing this. `MutationRecord.SystemPrincipal` until M6 gives this service a real identity; an
        * effect rather than a value because that is the shape it will have when it comes from the verified
        * principal of the request in flight.
        */
      principal: F[String]
  ): MutationGuard[F] =
    new MutationGuard[F] {

      def guard[A](
          cluster: ClusterId,
          kind: MutationKind,
          resource: String,
          detail: Map[String, String]
      )(op: F[Either[KuiError, A]]): F[Either[KuiError, A]] = {
        val context = Map(
          "service.name" -> ServiceName,
          "cluster.id" -> cluster.value,
          "operation" -> kind.operation,
          "resource" -> resource
        )

        def write(outcome: MutationOutcome, extra: Map[String, String]): F[Unit] =
          for {
            at <- Temporal[F].realTimeInstant
            subject <- principal
            _ <- audit
              .record(
                MutationRecord(
                  at = at,
                  principal = subject,
                  cluster = cluster,
                  kind = kind,
                  resource = resource,
                  // A produce has no "before": there was no record there to overwrite. Saying `None` is
                  // the honest answer, and it is different from saying the before value was empty.
                  before = None,
                  after = None,
                  outcome = outcome,
                  detail = detail ++ extra
                )
              )
              .handleErrorWith(failure =>
                logger.error(context)(s"the audit record could not be written: ${failure.getMessage}")
              )
          } yield ()

        profiles.cluster(cluster).flatMap {
          case Left(error) =>
            write(MutationOutcome.Failed, Map("reason" -> s"${error.code.wire}: ${error.message}"))
              .as(error.asLeft[A])

          case Right(profile) if profile.readOnly =>
            val refusal: KuiError = ApplicationError.Refused(
              ErrorCode.ReadOnly,
              s"cluster ${profile.name} is configured read-only, so ${kind.operation} is not accepted"
            )

            logger.info(context)("refused: the cluster is read-only") >>
              write(MutationOutcome.Refused, Map("reason" -> refusal.message)).as(refusal.asLeft[A])

          case Right(_) =>
            op.guaranteeCase {
              // `guaranteeCase`, so a cancelled or errored mutation is recorded too. Kafka gives no
              // guarantee that a cancelled write was *not* applied, and a record claiming either way
              // would be a lie; this one says the operation was cancelled, which tells an operator to
              // go and look.
              case Outcome.Succeeded(_) => Temporal[F].unit
              case Outcome.Errored(failure) =>
                write(MutationOutcome.Failed, Map("reason" -> Option(failure.getMessage).getOrElse("")))
              case Outcome.Canceled() =>
                write(
                  MutationOutcome.Failed,
                  Map("reason" -> "the operation was cancelled after the request was sent")
                )
            }.flatMap {
              case Right(value) =>
                // No log line of its own here. The audit sink writes exactly one, with the same sentence
                // and the structured fields an operator greps on; a second one from the guard put every
                // successful produce in the log twice, which was visible the first time this ran against
                // a real broker.
                write(MutationOutcome.Succeeded, Map.empty).as(value.asRight[KuiError])

              case Left(error) =>
                val outcome =
                  if error.code.httpStatus < 500 then MutationOutcome.Refused else MutationOutcome.Failed

                write(outcome, Map("reason" -> s"${error.code.wire}: ${error.message}")).as(error.asLeft[A])
            }
        }
      }
    }
}
