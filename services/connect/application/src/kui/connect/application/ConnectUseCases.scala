package kui.connect.application

import cats.Monad
import cats.effect.kernel.Clock
import cats.syntax.all.*

import kui.connect.domain.{ConnectorFacts, ConnectorOperation}
import kui.kernel.error.{ApplicationError, KuiError}
import kui.kernel.{ClusterId, ConnectName, ConnectorName}
import kui.security.Principal

/** What one configured Connect cluster answered.
  *
  * The failure is kept beside the name rather than thrown away, because "the Connect cluster called
  * `payments` did not answer" is a row on the screen and a `Left` that reached the route would be a screen.
  */
final case class WorkerReport(connect: ConnectName, facts: Either[KuiError, ConnectorFacts])

object WorkerReport {
  given CanEqual[WorkerReport, WorkerReport] = CanEqual.derived
}

/** What the connector list read produced for one Kafka cluster.
  *
  * Two cases and not a list that might be empty. `NotConfigured` is a deployment that configured no Kafka
  * Connect for this cluster, which is the ordinary case and reaches the browser as `not_configured` with a
  * 200; `Workers(Nil)` cannot occur and an empty list of reports would otherwise be read as either fact by
  * whoever looked at it next. Rule A3 keeps `Section` out of this layer, so the distinction is made in this
  * vocabulary and `ConnectMapping` turns it into the wire's.
  */
enum ConnectListing {
  case NotConfigured
  case Workers(reports: List[WorkerReport])
}

object ConnectListing {
  given CanEqual[ConnectListing, ConnectListing] = CanEqual.derived
}

/** The four things a caller can do to this service.
  *
  * ==`Left` on the read is only ever a wrong request==
  *
  * A cluster KUI has never heard of is a 404, because the caller followed a link to something that does not
  * exist. Everything else answers 200 with a document that says what it knows, exactly as the metrics and
  * alerts services do: the Connect screen sits in a product where the rest of the screens work, and a 4xx
  * would make a worker behaving exactly as designed — down, rebalancing, half-answering — indistinguishable
  * from a broken KUI.
  *
  * ==The writes are the other way round==
  *
  * A pause is a request to change something, so its failure is the caller's answer: a refused operation must
  * not answer 200 with a document saying it did not happen, or a browser that showed a success toast would be
  * telling the truth about the response and lying about the connector.
  */
trait ConnectUseCases[F[_]] {

  def connectors(principal: Principal, cluster: ClusterId): F[Either[KuiError, ConnectListing]]

  def operate(
      principal: Principal,
      cluster: ClusterId,
      connect: ConnectName,
      connector: ConnectorName,
      operation: ConnectorOperation
  ): F[Either[KuiError, AcceptedOperation]]
}

object ConnectUseCases {

  def make[F[_]: {Monad, Clock}](
      profiles: ClusterConnectSource[F],
      guard: MutationGuard[F]
  ): ConnectUseCases[F] =
    new ConnectUseCases[F] {

      /** Every configured worker, asked in configuration order.
        *
        * Sequentially and not in parallel, deliberately: this layer has `Monad` rather than `Parallel` for
        * rule A3's sake, and the cost is bounded by a number an operator typed into a list — two, in every
        * deployment anybody has described. Each worker's own call already has a timeout and a circuit breaker
        * in front of it (`UpstreamClient`), so a wedged worker delays the row after it by its own budget
        * rather than for ever.
        */
      def connectors(principal: Principal, cluster: ClusterId): F[Either[KuiError, ConnectListing]] =
        profiles.profileOf(cluster).flatMap {
          case Left(error) => error.asLeft[ConnectListing].pure[F]
          case Right(profile) if !profile.configured =>
            ConnectListing.NotConfigured.asRight[KuiError].pure[F]
          case Right(profile) =>
            profile.connects
              .traverse(connect => reportOf(cluster, connect))
              .map(reports => ConnectListing.Workers(reports).asRight[KuiError])
        }

      /** One worker's row. A Connect cluster that is configured and has no client is a wiring failure rather
        * than a deployment choice, and it says so: reporting it as "not configured" would hide a KUI defect
        * behind a row that looks deliberately switched off.
        */
      private def reportOf(cluster: ClusterId, connect: ConnectName): F[WorkerReport] =
        profiles.worker(cluster, connect).flatMap {
          case None =>
            WorkerReport(
              connect,
              ApplicationError
                .InvalidState(
                  s"the Kafka Connect cluster '${connect.value}' is configured and this process could " +
                    "not build a client for it"
                )
                .asLeft[ConnectorFacts]
            ).pure[F]
          case Some(port) => port.connectors.map(WorkerReport(connect, _))
        }

      /** The write, and every part of it that is not the worker's is the guard's.
        *
        * The permission check is not here and must not be: `SecuredRoutes` runs `RbacGuard` before this
        * method is called at all, so a principal without `CONNECT:OPERATE` on this Connect cluster never
        * reaches the worker. A second check here would be a second place for the rule to be written and the
        * one that can disagree — and it would move the refusal to *after* the route had decided to run the
        * logic, which is exactly the property `ConnectRoutesSuite` asserts by counting the calls the worker
        * received.
        */
      def operate(
          principal: Principal,
          cluster: ClusterId,
          connect: ConnectName,
          connector: ConnectorName,
          operation: ConnectorOperation
      ): F[Either[KuiError, AcceptedOperation]] =
        guard.guard(principal, cluster, connect, connector, operation) {
          profiles.profileOf(cluster).flatMap {
            case Left(error) => error.asLeft[AcceptedOperation].pure[F]

            // A Connect cluster this deployment did not configure is `KUI-UNSUPPORTED`, which is the same
            // classification the read gives a cluster with no Connect at all: one fact, one code, whether
            // it arrives as a section or as a status. It is checked here rather than left to `worker`
            // answering `None`, so that "you named something that is not here" and "KUI could not build a
            // client for something that is" stay two different sentences.
            case Right(profile) if !profile.has(connect) =>
              ApplicationError
                .Unsupported(
                  s"the Kafka Connect cluster '${connect.value}' is not configured for cluster " +
                    s"'${cluster.value}'"
                )
                .asLeft[AcceptedOperation]
                .pure[F]

            case Right(_) =>
              profiles.worker(cluster, connect).flatMap {
                case None =>
                  ApplicationError
                    .InvalidState(
                      s"the Kafka Connect cluster '${connect.value}' is configured and this process " +
                        "could not build a client for it"
                    )
                    .asLeft[AcceptedOperation]
                    .pure[F]

                case Some(port) =>
                  port.operate(connector, operation).flatMap {
                    case Left(error) => error.asLeft[AcceptedOperation].pure[F]
                    case Right(_) =>
                      Clock[F].realTimeInstant
                        .map(now => AcceptedOperation(connect, connector, operation, now).asRight[KuiError])
                  }
              }
          }
        }
    }
}
