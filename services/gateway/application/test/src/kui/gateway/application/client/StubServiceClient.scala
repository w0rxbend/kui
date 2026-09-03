package kui.gateway.application.client

import java.time.Instant

import cats.effect.kernel.{Async, Ref}
import cats.syntax.all.*
import fs2.Stream
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.{Endpoint, PublicEndpoint}

import kui.contracts.ErrorEnvelope
import kui.contracts.capability.{ClusterCapability, ServiceCapabilities}
import kui.contracts.health.{CheckResult, ReadinessReport}
import kui.http.sse.SseEvent
import kui.http.upstream.{CircuitEvent, CircuitState}
import kui.kernel.error.{InfrastructureError, KuiError}
import kui.kernel.{ClusterId, ServiceId}
import kui.security.SignedPrincipal

/** How a stubbed service answers the gateway.
  *
  * Named by what the *service* is doing rather than by what the client returns, because that is how the
  * assertions read: "a service that is down", not "a client that returns a left".
  */
enum ServiceHealth {
  case Healthy
  case NotReady(check: String)
  case Down
  case Hanging
  case Slow(by: scala.concurrent.duration.FiniteDuration)
}

/** A `ServiceClient` that answers health probes from a `Ref` and counts them.
  *
  * The readiness poller's promises are almost all about calls that did or did not happen — polls that did
  * not overlap, polls that stopped when the resource was released, one service's poll not blocking
  * another's — so the fixture has to record calls rather than only answer them.
  */
trait StubServiceClient[F[_]] extends ServiceClient[F] {
  def health: Ref[F, ServiceHealth]
  def polls: F[Int]
  def capabilityCalls: F[Int]
  def circuit(event: CircuitEvent): F[Unit]
}

object StubServiceClient {

  def apply[F[_]: Async](
      id: ServiceId,
      initial: ServiceHealth = ServiceHealth.Healthy,
      clusters: Map[ClusterId, ClusterCapability] = Map.empty
  ): F[StubServiceClient[F]] =
    for {
      state <- Ref.of[F, ServiceHealth](initial)
      readyCount <- Ref.of[F, Int](0)
      capabilityCount <- Ref.of[F, Int](0)
      events <- cats.effect.std.Queue.unbounded[F, CircuitEvent]
    } yield new StubServiceClient[F] {

      val service: ServiceId = id
      val health: Ref[F, ServiceHealth] = state

      def polls: F[Int] = readyCount.get
      def capabilityCalls: F[Int] = capabilityCount.get
      def circuit(event: CircuitEvent): F[Unit] = events.offer(event)
      def circuitStates: Stream[F, CircuitEvent] = Stream.fromQueueUnterminated(events)

      def call[I, O](endpoint: Endpoint[SignedPrincipal, I, ErrorEnvelope, O, Any], input: I)(
          ctx: CallContext
      ): F[Either[KuiError, O]] =
        Async[F].raiseError(new UnsupportedOperationException("this stub only answers health probes"))

      def callPublic[I, O](endpoint: PublicEndpoint[I, ErrorEnvelope, O, Any], input: I)(
          ctx: CallContext
      ): F[Either[KuiError, O]] = {
        val isReadiness = endpoint.info.name.contains("health.ready")
        val counted = if isReadiness then readyCount else capabilityCount
        counted.update(_ + 1) *> state.get.flatMap(answer(_, isReadiness)).map(_.asInstanceOf[Either[KuiError, O]])
      }

      private def answer(health: ServiceHealth, isReadiness: Boolean): F[Either[KuiError, Any]] =
        health match {
          case ServiceHealth.Healthy => Async[F].pure(Right(body(ready = true, Nil)(isReadiness)))
          case ServiceHealth.NotReady(check) =>
            Async[F].pure(Right(body(ready = false, List(CheckResult(check, false, None)))(isReadiness)))
          case ServiceHealth.Down =>
            Async[F].pure(Left(InfrastructureError.Unreachable(id.value, "connection refused")))
          case ServiceHealth.Hanging => Async[F].never
          case ServiceHealth.Slow(by) =>
            Async[F].sleep(by) *> Async[F].pure(Right(body(ready = true, Nil)(isReadiness)))
        }

      private def body(ready: Boolean, checks: List[CheckResult])(isReadiness: Boolean): Any =
        if isReadiness then ReadinessReport(ready, checks, Instant.EPOCH)
        else ServiceCapabilities(id, clusters)

      def stream[I](
          endpoint: Endpoint[SignedPrincipal, I, ErrorEnvelope, Stream[F, Byte], Fs2Streams[F]],
          input: I
      )(ctx: CallContext): Stream[F, SseEvent] =
        Stream.raiseError[F](new UnsupportedOperationException("this stub does not stream"))
    }

  val Closed: CircuitState = CircuitState.Closed
}
