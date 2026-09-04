package kui.gateway.api

import cats.effect.kernel.{Async, Clock}
import cats.syntax.all.*
import fs2.Stream
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.{extractFromRequest, statusCode, AnyEndpoint, Endpoint}

import kui.contracts.ErrorEnvelope
import kui.gateway.api.auth.SessionMiddleware
import kui.gateway.api.routing.{ClusterScope, ContractRouting}
import kui.gateway.application.client.{CallContext, ServiceClient}
import kui.http.ErrorInterceptor
import kui.http.sse.Sse
import kui.kernel.error.InfrastructureError
import kui.message.contract.{BrowseStreamParams, MessageEndpoints}
import kui.security.Principal

/** `GET /api/v1/clusters/{clusterId}/topics/{topicName}/messages/stream`, relayed to the message service.
  *
  * ==Why this is written out and not derived==
  *
  * Every other proxied route in the gateway comes from `ContractRouting.derive`, which calls the upstream,
  * decodes its answer and re-encodes it. That is exactly the wrong thing to do to a stream: it would read the
  * whole browse into memory before the browser saw its first record, and it would break the one property this
  * milestone is about — a browser tab that goes away must close a Kafka consumer.
  *
  * So this route relays instead. It still writes no path: the endpoint value is the message service's own,
  * and `ContractRouting.rewritePrefix` turns `/internal/v1` into `/api/v1` the same way it does for every
  * derived route, so the public address and the upstream address cannot drift.
  *
  * ==The three things the relay guarantees==
  *
  *   1. **Cancellation travels.** The browser aborts, Tapir cancels this stream, `ServiceClient.stream`
  *      cancels the upstream call, the message service's fiber is cancelled, and its Kafka consumer's
  *      `Resource` finaliser closes it. Nothing in this file arranges that; it works because every link is a
  *      stream and none of them buffers the whole thing.
  *   1. **The stream always ends with a terminal event.** If the upstream process dies mid-body the browser
  *      would otherwise see a connection that simply stops, which it can only render as "the search finished,
  *      apparently". [[StreamProxy.withTerminalEvent]] appends an `error` event saying the message service
  *      went away (ADR-035).
  *   1. **The events are not rewritten.** They are re-encoded from the parsed form the client hands back,
  *      field for field, by the same `SseEvent.render` the service used — including the `id:` line that
  *      carries the continuation cursor.
  */
object MessageStreamRoutes {

  /** The upstream this relay reports as unreachable when a stream dies without saying why. */
  val Upstream: String = "message"

  def apply[F[_]: Async](client: ServiceClient[F]): List[ServerEndpoint[Fs2Streams[F], F]] =
    List(
      publicEndpoint[F]
        .errorOut(statusCode)
        .serverSecurityLogicSuccess[ServerRequest, F](request => Async[F].pure(request))
        .serverLogicSuccess(request => params => Async[F].pure(relay[F](client, request, params)))
    )

  /** The public shape of the message service's stream endpoint: its own inputs and outputs, with the internal
    * prefix rewritten and the signed principal replaced by the inbound request.
    */
  def publicEndpoint[F[_]]
      : Endpoint[ServerRequest, BrowseStreamParams, ErrorEnvelope, Stream[F, Byte], Fs2Streams[F]] = {
    val internal = MessageEndpoints.browseStream[F]

    Endpoint(
      securityInput = extractFromRequest[ServerRequest](identity),
      input = ContractRouting.rewritePrefix(internal.input),
      errorOutput = internal.errorOutput,
      output = internal.output,
      info = internal.info
    )
  }

  /** Every endpoint this file serves, for the merged OpenAPI document. */
  def endpoints[F[_]]: List[AnyEndpoint] = List(publicEndpoint[F])

  private def relay[F[_]: Async](
      client: ServiceClient[F],
      request: ServerRequest,
      params: BrowseStreamParams
  ): Stream[F, Byte] =
    Stream.eval(context[F](request, params)).flatMap { ctx =>
      val upstream = Sse.encode(client.stream(MessageEndpoints.browseStream[F], params)(ctx))

      Stream.eval(Clock[F].realTimeInstant).flatMap { now =>
        StreamProxy.withTerminalEvent(
          upstream,
          ErrorEnvelope.of(
            InfrastructureError.Unreachable(Upstream, "the stream ended without saying why"),
            ctx.correlationId,
            now
          )
        )
      }
    }

  /** Who is asking, under which correlation id, and about which cluster.
    *
    * The correlation id is read off the request and never minted here: `EdgeHeaders` has already attached the
    * authoritative one, and a second id would mean the value a user quotes from an error event matches no log
    * line anywhere.
    */
  private def context[F[_]: Async](request: ServerRequest, params: BrowseStreamParams): F[CallContext] =
    ErrorInterceptor.correlationIdOf[F](request).map { correlationId =>
      val principal = request
        .attribute(SessionMiddleware.Attribute)
        .map(_.principal)
        .getOrElse(Principal.Anonymous)

      CallContext(principal, correlationId, Some(params.cluster))
    }

  /** The cluster a request is about, for anything that keys on it. It comes from the path, never from an
    * inbound header, which the edge strips (ADR-040) so that a caller cannot label another cluster's metrics
    * as its own.
    */
  def clusterOf(request: ServerRequest): Option[kui.kernel.ClusterId] =
    ClusterScope.clusterOf(ClusterScope.of(request.uri.path.toList))
}
