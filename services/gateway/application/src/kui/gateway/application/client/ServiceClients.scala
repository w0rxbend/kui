package kui.gateway.application.client

import cats.effect.kernel.Concurrent
import fs2.Stream

import kui.http.upstream.CircuitEvent
import kui.kernel.ServiceId

/** Every service this deployment is configured to reach, keyed by id.
  *
  * Built once from `GatewayConfig.services`, so "configured" means exactly one thing everywhere: the id has
  * an entry here. A service that is not configured is absent, and `get` returns `None` — which the capability
  * registry turns into `NotConfigured` rather than `Unavailable`, because "you did not set this up" and "this
  * is broken" are different sentences to show an operator (ADR-039 §2).
  */
trait ServiceClients[F[_]] {

  def get(id: ServiceId): Option[ServiceClient[F]]

  /** Every configured client, ordered by service id so that logs, snapshots and tests are stable. */
  def all: List[ServiceClient[F]]

  /** The circuit transitions of every upstream, merged into one stream.
    *
    * `CircuitFeed` (GW-004) subscribes to this and reports each transition to the registry. Merging here
    * rather than in the feed keeps the "one bulkhead and one breaker per service" arrangement of PLAN §16.4
    * invisible above this port: a caller sees a deployment-wide stream of events that each name their own
    * upstream.
    */
  def circuitStates: Stream[F, CircuitEvent]
}

object ServiceClients {

  /** Wraps a list of clients. The list is the deployment's configuration, already resolved. */
  def of[F[_]: Concurrent](clients: List[ServiceClient[F]]): ServiceClients[F] = {
    val byId: Map[ServiceId, ServiceClient[F]] = clients.map(client => client.service -> client).toMap
    val ordered: List[ServiceClient[F]] = clients.sortBy(_.service.value)

    new ServiceClients[F] {
      def get(id: ServiceId): Option[ServiceClient[F]] = byId.get(id)
      def all: List[ServiceClient[F]] = ordered
      def circuitStates: Stream[F, CircuitEvent] =
        // `parJoinUnbounded` and not `flatMap`: the streams are concurrent sources that each run until
        // the gateway stops, so concatenating them would mean only the first upstream was ever heard.
        Stream.emits(ordered.map(_.circuitStates)).parJoinUnbounded
    }
  }

  /** No services configured. A legitimate deployment shape, not an error: the browser must be able to tell
    * "nothing is configured" from "the gateway is not answering" (GW-005).
    */
  def empty[F[_]: Concurrent]: ServiceClients[F] = of(Nil)
}
