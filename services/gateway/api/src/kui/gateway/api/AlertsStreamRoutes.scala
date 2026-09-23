package kui.gateway.api

import cats.effect.kernel.{Async, Clock}
import fs2.Stream
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.{extractFromRequest, statusCode, AnyEndpoint, Endpoint}

import kui.alerts.contract.AlertsStreamEndpoint
import kui.contracts.ErrorEnvelope
import kui.gateway.api.routing.{ContractRouting, RbacPreCheck}
import kui.gateway.application.client.{CallContext, ServiceClient}
import kui.http.sse.Sse
import kui.kernel.ClusterId
import kui.kernel.error.InfrastructureError

/** `GET /api/v1/clusters/{clusterId}/alerts/stream`, relayed to the alerts service.
  *
  * A streaming response cannot go through `ContractRouting.derive`: that path waits for a complete response
  * value and then re-encodes it. This route keeps the alerts service's endpoint as the single contract,
  * rewrites only its prefix, and leaves the body as a stream all the way to the browser.
  *
  * Authorization stays at the gateway edge. It runs in Tapir's security stage, before the response stream is
  * constructed or pulled, so a denied caller cannot open a subscription in the alerts service.
  */
object AlertsStreamRoutes {

  val Upstream: String = "alerts"

  def apply[F[_]: Async](
      client: ServiceClient[F],
      rbac: RbacPreCheck[F]
  ): List[ServerEndpoint[Fs2Streams[F], F]] =
    List(
      publicEndpoint[F]
        .errorOut(statusCode)
        .serverSecurityLogic[StreamAuthorization.Authorized, F](request =>
          StreamAuthorization.authorize[F](request, AlertsStreamEndpoint.endpoint[F], rbac)
        )
        .serverLogicSuccess(authorized => cluster => Async[F].pure(relay[F](client, authorized, cluster)))
    )

  /** The service's own stream contract with `/internal/v1` changed to `/api/v1` and the signed principal
    * replaced by the browser request from which the gateway derives one.
    */
  def publicEndpoint[F[_]]
      : Endpoint[ServerRequest, ClusterId, ErrorEnvelope, Stream[F, Byte], Fs2Streams[F]] = {
    val internal = AlertsStreamEndpoint.endpoint[F]

    Endpoint(
      securityInput = extractFromRequest[ServerRequest](identity),
      input = ContractRouting.rewritePrefix(internal.input),
      errorOutput = internal.errorOutput,
      output = internal.output,
      info = internal.info
    )
  }

  /** Every endpoint this relay serves, for the merged OpenAPI document. */
  def endpoints[F[_]]: List[AnyEndpoint] = List(publicEndpoint[F])

  /** The upstream events are rendered with the same encoder the alerts service uses. `StreamProxy` forwards
    * those bytes unchanged and supplies an error event only if the upstream disappears without one.
    */
  private[api] def relay[F[_]: Async](
      client: ServiceClient[F],
      authorized: StreamAuthorization.Authorized,
      cluster: ClusterId
  ): Stream[F, Byte] = {
    val context = CallContext(authorized.principal, authorized.correlationId, Some(cluster))
    val upstream = Sse.encode(client.stream(AlertsStreamEndpoint.endpoint[F], cluster)(context))

    Stream.eval(Clock[F].realTimeInstant).flatMap { now =>
      StreamProxy.withTerminalEvent(
        upstream,
        ErrorEnvelope.of(
          InfrastructureError.Unreachable(Upstream, "the stream ended without saying why"),
          authorized.correlationId,
          now
        )
      )
    }
  }
}
