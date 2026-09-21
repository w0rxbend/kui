package kui.gateway.api

import cats.effect.kernel.{Async, Clock}
import cats.syntax.all.*
import fs2.Stream
import sttp.capabilities.fs2.Fs2Streams
import sttp.model.StatusCode
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.{extractFromRequest, statusCode, AnyEndpoint, Endpoint}

import kui.contracts.ErrorEnvelope
import kui.gateway.api.routing.{ContractRouting, RbacPreCheck}
import kui.gateway.application.client.{CallContext, ServiceClient}
import kui.http.sse.Sse
import kui.kernel.error.{InfrastructureError, KuiError}
import kui.kernel.{ClusterId, CorrelationId}
import kui.ksql.contract.KsqlStreamEndpoint
import kui.security.Principal

/** `GET /api/v1/clusters/{clusterId}/ksql/stream?statement=…`, relayed to the ksql service.
  *
  * The eleventh service's push query, and the third hand-written relay in this module after the message
  * browse and the alerts change feed. It exists for the reason both of those do and which house rule 16
  * states: `ContractRouting.derive` waits for a complete response value, decodes it and re-encodes it, so a
  * derived route over a push query would answer nothing until the query ended — and a push query ends only
  * when somebody goes away. `services/alerts` shipped complete and unreachable in wave 6 for want of this
  * file's equivalent, so it is written in the same shape rather than in a better one.
  *
  * Three things it does that the derivation would not:
  *
  *   1. **Authorization happens at the edge, before the upstream is opened.** It runs in Tapir's security
  *      stage, so a caller without `KSQL:EXECUTE` costs the ksql service no connection and ksqlDB no query.
  *      The permission is re-decided here rather than inherited: ADR-020 leaves the query string out of the
  *      signed request digest and the statement travels in the query string, so the token cannot have covered
  *      it.
  *   1. **The bytes are not rewritten.** `StreamProxy` moves them through a bounded queue; the `phase`,
  *      `row`, `heartbeat`, `error` and `done` frames arrive at the browser exactly as the ksql service
  *      rendered them, which is what stops the gateway becoming a second place the frame grammar is defined.
  *   1. **The stream always ends with a terminal event.** A ksql process that is killed mid-query would
  *      otherwise leave a browser with a connection that simply stops, which it can only draw as "the query
  *      finished, apparently" — and for a push query over a live topic that is the most misleading thing the
  *      screen could say. [[StreamProxy.withTerminalEvent]] appends ADR-035's `error` when the upstream ended
  *      without one.
  */
object KsqlStreamRoutes {

  /** The upstream this relay reports as unreachable when a stream dies without saying why. */
  val Upstream: String = "ksql"

  def apply[F[_]: Async](
      client: ServiceClient[F],
      rbac: RbacPreCheck[F]
  ): List[ServerEndpoint[Fs2Streams[F], F]] =
    List(
      publicEndpoint[F]
        .errorOut(statusCode)
        .serverSecurityLogic[Authorized, F](request => authorize[F](request, rbac))
        .serverLogicSuccess(authorized => input => Async[F].pure(relay[F](client, authorized, input)))
    )

  /** The service's own stream contract with `/internal/v1` changed to `/api/v1` and the signed principal
    * replaced by the browser request from which the gateway derives one.
    *
    * The statement parameter travels through untouched. The gateway does not classify it — deciding that a
    * statement is a push query is the ksql service's job and is asserted there, and a second classifier here
    * would be a second answer to "is this a push query" with nothing keeping the two equal.
    */
  def publicEndpoint[F[_]]
      : Endpoint[ServerRequest, (ClusterId, String), ErrorEnvelope, Stream[F, Byte], Fs2Streams[F]] = {
    val internal = KsqlStreamEndpoint.endpoint[F]

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

  private def authorize[F[_]: Async](
      request: ServerRequest,
      rbac: RbacPreCheck[F]
  ): F[Either[(ErrorEnvelope, StatusCode), Authorized]] =
    ContractRouting.callerOf[F](request).flatMap {
      case Left(error) => error.asLeft[Authorized].pure[F]
      case Right((principal, correlationId, cluster, segments)) =>
        rbac
          .check(principal, KsqlStreamEndpoint.endpoint[F], cluster, segments)
          .flatMap {
            case Right(_) => Authorized(principal, correlationId).asRight.pure[F]
            case Left(error) => failure[F](error, correlationId)
          }
    }

  /** The upstream rows are rendered with the same encoder the ksql service uses. `StreamProxy` forwards those
    * bytes unchanged and supplies an error event only if the upstream disappears without one.
    */
  private[api] def relay[F[_]: Async](
      client: ServiceClient[F],
      authorized: Authorized,
      input: (ClusterId, String)
  ): Stream[F, Byte] = {
    val (cluster, _) = input
    val context = CallContext(authorized.principal, authorized.correlationId, Some(cluster))
    val upstream = Sse.encode(client.stream(KsqlStreamEndpoint.endpoint[F], input)(context))

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

  private def failure[F[_]: Async](
      error: KuiError,
      correlationId: CorrelationId
  ): F[Either[(ErrorEnvelope, StatusCode), Authorized]] =
    Clock[F].realTimeInstant.map { now =>
      val envelope = ErrorEnvelope.of(error, correlationId, now)
      Left((envelope, StatusCode(ErrorEnvelope.statusOf(error))))
    }

  final private[api] case class Authorized(principal: Principal, correlationId: CorrelationId)
}
