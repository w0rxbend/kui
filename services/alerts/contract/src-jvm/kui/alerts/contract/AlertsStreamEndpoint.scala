package kui.alerts.contract

import java.nio.charset.StandardCharsets

import fs2.Stream
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.*

import kui.alerts.contract.dto.AlertChangeDto
import kui.contracts.KernelSchemas.given
import kui.contracts.rbac.{EndpointAuthorization, ResourceRequirement}
import kui.contracts.{ErrorEnvelope, KuiEndpoint}
import kui.kernel.ClusterId
import kui.security.SignedPrincipal
import kui.security.rbac.{Action, Resource}

/** `GET /internal/v1/clusters/{clusterId}/alerts/stream` — one frame whenever this cluster's feed changes.
  *
  * ==Why it is in `src-jvm` and not beside the other two endpoints==
  *
  * Describing an event-stream body needs `fs2` and a stream capability, and the shared sources of a contract
  * module link for the browser. `services/message`'s browse stream is in the same position and this file is
  * the shape it settled: the endpoint is in the contract module's **JVM half**, so that the gateway — which
  * may see a service only through its published contract (rule A4, rule A11) — can hold the endpoint value
  * its relay is written against. An endpoint declared in the `api` module would be invisible to the gateway
  * by construction, and the browser would have no route to it however much code was written.
  *
  * The browser loses nothing: it opens this address with `EventSource`, which takes a URL rather than a Tapir
  * endpoint, and `AlertChangeDto` — the thing inside the frames — is declared in the shared sources and
  * compiled into both halves.
  *
  * ==Why it exists==
  *
  * The card draws an open count and the bell draws an unread dot, and they are two components on two
  * different parts of one screen. Polled independently they drift by a poll interval, and the one screen
  * whose entire job is to say how many things are wrong is the worst place in the product for two numbers
  * that disagree. One stream, one store behind it, and a frame that carries the open count itself so a
  * subscriber does not have to race a re-read to find out what changed.
  *
  * ==It is not a proxied route, and that is W6-03's line of work==
  *
  * `ContractRouting.derive` decodes and re-encodes an upstream's JSON, which is exactly the wrong thing to do
  * to a stream, so every stream the browser reads has a hand-written relay in the gateway —
  * `MessageStreamRoutes` is the worked example. Until that relay lands this endpoint is reachable
  * service-to-service with a signed principal and not from a browser; the card and the bell fall back to
  * polling `…/alerts/events`, which is one request for both of them, so they still cannot disagree.
  */
object AlertsStreamEndpoint {

  /** The SSE event name a consumer registers a listener for. Shared with the DTO so the encoder and the
    * listener cannot be given two spellings of one word.
    */
  val EventName: String = AlertChangeDto.EventName

  val StreamSegment: String = "stream"

  private val clusterIdPath: EndpointInput[ClusterId] =
    path[ClusterId](AlertsEndpoints.ClusterIdParam).description("The configured cluster's slug id")

  /** The event-stream body, declared here rather than taken from `libs/http`.
    *
    * It is the same body `kui.http.sse.Sse.body` produces — `text/event-stream`, UTF-8, framing left to the
    * caller — written out because a contract module may not depend on `libs/http`, which is a wire module a
    * service's published shape has no business reaching into. `MessageEndpoints` makes the same trade for the
    * same reason, and `AlertsCapabilitiesSuite` pins the media type on the other side of it.
    */
  private def sseBody[F[_]] =
    streamTextBody(Fs2Streams[F])(CodecFormat.TextEventStream(), Some(StandardCharsets.UTF_8))

  def endpoint[F[_]]: Endpoint[SignedPrincipal, ClusterId, ErrorEnvelope, Stream[F, Byte], Fs2Streams[F]] =
    KuiEndpoint.internal.get
      .in(
        "internal" / "v1" / AlertsEndpoints.ClustersSegment / clusterIdPath /
          AlertsEndpoints.AlertsSegment / StreamSegment
      )
      .out(sseBody[F])
      .attribute(
        EndpointAuthorization.Key,
        EndpointAuthorization.one(
          "alerts.stream",
          ResourceRequirement.unnamed(Resource.Alerts, Action.AlertsView)
        )
      )
      .name("alerts.stream")
      .summary("One event whenever this cluster's alert feed changes")
      .description(
        "Named events: 'alerts' carries {cluster, openCount, at}; 'heartbeat' keeps proxies from closing " +
          "an idle connection; 'error' carries the standard envelope. A frame carries the open count and " +
          "not the events, so one subscriber's unread count never reaches another's socket and a dropped " +
          "frame costs a fetch rather than a stale screen."
      )
      .tag("alerts")

  /** Every endpoint of this file, so the OpenAPI merge documents the stream even though `AlertsEndpoints.all`
    * — which the browser compiles — cannot hold it.
    */
  def endpoints[F[_]]: List[AnyEndpoint] = List(endpoint[F])
}
