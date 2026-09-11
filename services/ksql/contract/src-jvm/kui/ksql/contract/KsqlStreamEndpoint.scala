package kui.ksql.contract

import java.nio.charset.StandardCharsets

import fs2.Stream
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.*

import kui.contracts.KernelSchemas.given
import kui.contracts.rbac.{EndpointAuthorization, ResourceRequirement}
import kui.contracts.{ErrorEnvelope, KuiEndpoint}
import kui.kernel.ClusterId
import kui.ksql.contract.dto.QueryRowDto
import kui.security.SignedPrincipal
import kui.security.rbac.{Action, Resource}

/** `GET /internal/v1/clusters/{clusterId}/ksql/stream?statement=…` — one frame per row of a push query.
  *
  * ==Why it is in `src-jvm` and not beside the other three endpoints==
  *
  * Describing an event-stream body needs `fs2` and a stream capability, and the shared sources of a contract
  * module link for the browser. `services/alerts`' change stream and `services/message`'s browse are in the
  * same position and this file is the shape they settled: the endpoint is in the contract module's **JVM
  * half**, so that the gateway — which may see a service only through its published contract (rules A4 and
  * A11) — can hold the endpoint value its relay is written against. An endpoint declared in the `api` module
  * would be invisible to the gateway by construction, and the browser would have no route to it however much
  * code was written. That is exactly how `services/alerts` shipped a stream nobody could reach.
  *
  * The browser loses nothing: it opens this address with `EventSource`, which takes a URL rather than a Tapir
  * endpoint, and `QueryRowDto` and `QueryHeaderDto` — the things inside the frames — are declared in the
  * shared sources and compiled into both halves.
  *
  * ==The frames, and what makes one arrive==
  *
  * `phase` carries the columns and arrives as soon as ksqlDB accepts the query, which is what makes an idle
  * push query a stream that has visibly started rather than a socket that has said nothing. `row` carries one
  * row and arrives when a record is produced to the topic behind the query. `heartbeat` keeps proxies from
  * closing an idle connection, `error` carries the standard envelope, and `done` ends it — a push query ends
  * only when the client goes away, when the server does, or when `kui.clusters.<n>.ksql.streamTimeout`
  * expires, and the reason says which (ADR-035, ADR-055 §5).
  *
  * ==Why the statement is a query parameter==
  *
  * `EventSource` issues a `GET` and carries no body, so there is nowhere else to put it. The cost is stated
  * rather than hidden: the statement appears in whatever logs the request line — KUI's own access log, and
  * any proxy in front of it — so a push query is as visible as the URL of any other page. ADR-020 puts the
  * query string outside the signed request digest, which is why the gateway's relay re-checks the permission
  * rather than trusting the token to have covered it.
  */
object KsqlStreamEndpoint {

  /** The SSE event name a row arrives under, and the one a consumer registers a listener for.
    *
    * Shared with the DTO, which takes it from `SseEventName.Row`, so that the encoder, the listener and the
    * golden document cannot be given three spellings of one word. That is the mistake the alerts wire makes
    * with `alerts`, which is declared in two places in that service and compared to the browser's third copy
    * by eye.
    */
  val EventName: String = QueryRowDto.EventName

  val StreamSegment: String = "stream"

  /** The query parameter the statement travels in. */
  val StatementParam: String = "statement"

  private val clusterIdPath: EndpointInput[ClusterId] =
    path[ClusterId](KsqlEndpoints.ClusterIdParam).description("The configured cluster's slug id")

  /** The event-stream body, declared here rather than taken from `libs/http`.
    *
    * It is the same body `kui.http.sse.Sse.body` produces — `text/event-stream`, UTF-8, framing left to the
    * caller — written out because a contract module may not depend on `libs/http`, which is a wire module a
    * service's published shape has no business reaching into. `AlertsStreamEndpoint` and `MessageEndpoints`
    * make the same trade for the same reason.
    */
  private def sseBody[F[_]] =
    streamTextBody(Fs2Streams[F])(CodecFormat.TextEventStream(), Some(StandardCharsets.UTF_8))

  def endpoint[F[_]]
      : Endpoint[SignedPrincipal, (ClusterId, String), ErrorEnvelope, Stream[F, Byte], Fs2Streams[F]] =
    KuiEndpoint.internal.get
      .in(
        "internal" / "v1" / KsqlEndpoints.ClustersSegment / clusterIdPath / KsqlEndpoints.KsqlSegment /
          StreamSegment
      )
      .in(
        query[String](StatementParam)
          .description("The push query to run. It must be a SELECT ... EMIT CHANGES and nothing else")
      )
      .out(sseBody[F])
      .attribute(
        EndpointAuthorization.Key,
        EndpointAuthorization.one(
          KsqlEndpoints.StreamOperation,
          // `EXECUTE`, not `VIEW`: a push query is a query ksqlDB runs and holds open, and it is refused
          // on a read-only cluster for the reason `Action.KsqlExecute.isAlter` already encodes.
          ResourceRequirement.unnamed(Resource.Ksql, Action.KsqlExecute)
        )
      )
      .name(KsqlEndpoints.StreamOperation)
      .summary("Rows of a push query, as they arrive")
      .description(
        "Named events: 'phase' carries {columns} once, as soon as the server accepts the query; 'row' " +
          "carries {values} in that column order; 'heartbeat' keeps proxies from closing an idle " +
          "connection; 'error' carries the standard envelope; 'done' says why it ended. A statement " +
          "that is not a push query is refused before the server is called, and the refusal names the " +
          "endpoint that does answer it."
      )
      .tag("ksql")

  /** Every endpoint of this file, so the OpenAPI merge documents the stream even though `KsqlEndpoints.all` —
    * which the browser compiles — cannot hold it.
    */
  def endpoints[F[_]]: List[AnyEndpoint] = List(endpoint[F])
}
