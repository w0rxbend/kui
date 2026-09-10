package kui.connect.api

import cats.effect.kernel.{Async, Clock}
import cats.syntax.all.*
import sttp.tapir.Endpoint
import sttp.tapir.server.ServerEndpoint

import kui.connect.application.ConnectUseCases
import kui.connect.contract.ConnectEndpoints
import kui.connect.contract.dto.*
import kui.connect.domain.ConnectorOperation
import kui.contracts.ErrorEnvelope
import kui.security.SignedPrincipal

/** The four routes, bound to use cases.
  *
  * ==One rule shapes the read and the opposite rule shapes the writes==
  *
  * A read naming a cluster KUI has never heard of **fails** with `404 KUI-CLUSTER-NOT-FOUND`, because the
  * caller followed a link to something that does not exist. Everything else about a read **succeeds**,
  * carrying sections that say what happened — a Connect cluster that is down, rebalancing or half-answering
  * is a row on a working screen, and a 4xx would make a worker behaving exactly as designed indistinguishable
  * from a broken KUI.
  *
  * A write is the other way round. A pause that did not happen must not answer 200 with a document saying so:
  * the browser would show a success toast that is true about the response and false about the connector.
  *
  * ==Nothing here decides anything==
  *
  * The permission is `SecuredRoutes`', the read-only refusal and the audit record are `MutationGuard`'s, the
  * classification of a rebalance is `ConnectHttp`'s, and the choice of section per worker is
  * `ConnectMapping`'s. This module validates two path parameters and turns one instant into a `fetchedAt`.
  */
object ConnectRoutes {

  def apply[F[_]: Async](
      connect: ConnectUseCases[F],
      secured: ConnectApi.Securing[F]
  ): List[ServerEndpoint[Any, F]] =
    List(
      connectorsRoute(connect, secured),
      operationRoute(ConnectEndpoints.pause, ConnectorOperation.Pause, connect, secured),
      operationRoute(ConnectEndpoints.resume, ConnectorOperation.Resume, connect, secured),
      operationRoute(ConnectEndpoints.restart, ConnectorOperation.Restart, connect, secured)
    )

  /** `GET /internal/v1/clusters/{clusterId}/connect/connectors`. */
  private def connectorsRoute[F[_]: Async](
      connect: ConnectUseCases[F],
      secured: ConnectApi.Securing[F]
  ): ServerEndpoint[Any, F] =
    secured(ConnectEndpoints.connectors) { principal => cluster =>
      for {
        now <- Clock[F].realTimeInstant
        answer <- connect.connectors(principal, cluster)
      } yield answer.map(ConnectMapping.response(_, now))
    }

  /** `POST /internal/v1/clusters/{clusterId}/connect/{connectName}/connectors/{connectorName}/{operation}`.
    *
    * Bodiless, so it is verified in Tapir's *security* stage: an unauthenticated caller is refused before
    * this service parses a byte of what they sent, and `SecuredRoutes.withBody`'s one-stage-later
    * reconstruction is not needed because there are no bytes to reconstruct (ADR-020 Amendment 1).
    *
    * All three are built by one function, so a fourth operation cannot arrive with a subtly different order
    * of validation, refusal and audit — the property `ConnectRoutesSuite` asserts by counting what the worker
    * was asked.
    */
  private def operationRoute[F[_]: Async](
      endpoint: Endpoint[
        SignedPrincipal,
        (String, kui.kernel.ClusterId, String, String),
        ErrorEnvelope,
        ConnectorOperationDto,
        Any
      ],
      operation: ConnectorOperation,
      connect: ConnectUseCases[F],
      secured: ConnectApi.Securing[F]
  ): ServerEndpoint[Any, F] =
    secured(endpoint) { principal => (_, cluster, rawConnect, rawConnector) =>
      val names = for {
        connectName <- ConnectMapping.connectName(rawConnect)
        connectorName <- ConnectMapping.connectorName(rawConnector)
      } yield (connectName, connectorName)

      names match {
        case Left(invalid) => invalid.asLeft[ConnectorOperationDto].pure[F]
        case Right((connectName, connectorName)) =>
          connect
            .operate(principal, cluster, connectName, connectorName, operation)
            .map(_.map(ConnectMapping.accepted))
      }
    }
}
