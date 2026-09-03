package kui.gateway.application.client

import fs2.Stream
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.Endpoint

import kui.contracts.ErrorEnvelope
import kui.http.sse.SseEvent
import kui.http.upstream.CircuitEvent
import kui.kernel.error.KuiError
import kui.kernel.{ClusterId, CorrelationId, ServiceId}
import kui.security.{Principal, SignedPrincipal}

/** Everything a call carries that is not part of the endpoint's own input.
  *
  * Who is asking, which request this belongs to, and — when the operation is about one Kafka cluster — which
  * cluster. The gateway holds these three things per inbound request and hands the same three to every
  * upstream it fans out to, which is what makes one browser request readable as one trace.
  */
final case class CallContext(
    principal: Principal,
    correlationId: CorrelationId,
    cluster: Option[ClusterId]
)

/** One service, reachable by calling the endpoint values it published.
  *
  * This is the seam that makes the all-in-one deployment honest (ADR-005). The routing, the capability
  * registry and the aggregation code above this port are byte-for-byte the same whether the service is a
  * process on another machine or an object in this JVM, because both shapes are this one trait. A bug that
  * only appears in one shape therefore has nowhere to hide: there is no second code path for it to live in.
  *
  * Two implementations exist. `SttpServiceClient` (in `api`) speaks HTTP over the resilient backend of
  * ADR-037. `InProcessServiceClient` (AIO-001, in `apps/allinone`) interprets the very same endpoint values
  * against the service's server logic without a socket.
  *
  * ==Why the input is an endpoint value and not a URL==
  *
  * A `ServiceClient` is never told a path. It is handed an `Endpoint` the owning service published in its
  * `contract` module, and derives method, path, query, headers and codecs from it. A path the gateway calls
  * therefore cannot drift from the path the service serves: there is only one definition, and both sides read
  * it. ADR-003 forbids hand-written path lists for exactly this reason, and ADR-041 rule A4 makes "only
  * through the contract" a build failure rather than a review convention.
  *
  * ==Why errors are values==
  *
  * Every failure comes back as `Left(KuiError)`. Nothing throws past this port. The route layer above has to
  * turn *both* outcomes into a response anyway, and the capability registry has to see the failures (ADR-039
  * §6 keys on whether the error is an `InfrastructureError` or an `ApplicationError`), so an exception
  * escaping here would mean one of the two forgot.
  */
trait ServiceClient[F[_]] {

  /** Which service this client talks to. Used as the audience of the signed principal, as the metric and span
    * label, and as the capability registry's key.
    */
  def service: ServiceId

  /** Calls a request/response endpoint and returns its output, or the error it failed with. */
  def call[I, O](endpoint: Endpoint[SignedPrincipal, I, ErrorEnvelope, O, Any], input: I)(
      ctx: CallContext
  ): F[Either[KuiError, O]]

  /** Calls a streaming endpoint and re-emits its Server-Sent Events one at a time.
    *
    * The output type is pinned to `Stream[F, Byte]` rather than left open, because that is what
    * `kui.http.sse.Sse.body` — the only way a KUI service declares an SSE endpoint — produces. Pinning it
    * keeps the wire-to-`SseEvent` parse in one place instead of once per caller.
    *
    * The result is lazy and unbuffered: an event reaches the browser as it arrives, and a consumer that stops
    * reading stops the upstream call.
    */
  def stream[I](
      endpoint: Endpoint[SignedPrincipal, I, ErrorEnvelope, Stream[F, Byte], Fs2Streams[F]],
      input: I
  )(ctx: CallContext): Stream[F, SseEvent]

  /** Every circuit-breaker transition of this client's upstream.
    *
    * On the port rather than only on the sttp implementation because `ServiceClients.circuitStates` has to
    * merge them, and the registry (GW-003/GW-004) consumes the merged stream without knowing which shape it
    * is deployed in. An in-process client has no circuit to open and returns an empty stream, which is the
    * truthful answer rather than a stub.
    */
  def circuitStates: Stream[F, CircuitEvent]
}
