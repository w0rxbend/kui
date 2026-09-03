package kui.gateway.application.capability

import cats.effect.kernel.{Async, Resource}
import cats.effect.syntax.all.*
import cats.syntax.all.*

import kui.gateway.application.client.ServiceClients
import kui.http.upstream.CircuitEvent
import kui.kernel.ServiceId

/** Turns every circuit-breaker transition into a capability signal.
  *
  * Without this, an open breaker would be invisible to the user. The breaker's whole purpose is to stop
  * calling a service that is failing, which means the readiness poll stops reaching it too — so the gateway
  * would know perfectly well that a service was unusable and the sidebar would still say it was fine, until
  * the next poll timed out. The feed closes that gap in the moment the breaker trips.
  */
object CircuitFeed {

  def resource[F[_]: Async](
      clients: ServiceClients[F],
      signals: CapabilitySignals[F]
  ): Resource[F, Unit] =
    clients.circuitStates
      .evalMap(event => report(event, signals))
      .compile
      .drain
      .background
      .void

  /** The breaker names its upstream by the service id, which is how `SttpServiceClient` configures it. An
    * event for a name that is not a service id is dropped rather than guessed at.
    */
  def report[F[_]](event: CircuitEvent, signals: CapabilitySignals[F]): F[Unit] =
    // The service key, always: a breaker is about the connection to a service, not about a cluster.
    signals.updateService(ServiceId.unsafe(event.upstream))(_.copy(circuit = Some(event.state)))
}
