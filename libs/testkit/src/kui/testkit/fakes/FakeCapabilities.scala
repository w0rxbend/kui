package kui.testkit.fakes

import cats.effect.kernel.{Ref, Sync}
import cats.syntax.all.*

import kui.contracts.capability.{ClusterCapability, ServiceCapabilities}
import kui.kernel.{ClusterId, ServiceId}

/** A service's `/capabilities` answer, under a test's control.
  *
  * Every service supplies `HealthEndpoints.make` with an `F[ServiceCapabilities]`, and every suite that
  * exercises the endpoint needs to change what that answers between requests — a cluster that was available
  * becoming degraded is the whole behaviour the gateway's registry is built on. This is that, plus a count of
  * how many times it was asked, because "the gateway polls rather than caches" is itself something worth
  * asserting.
  */
trait FakeCapabilities[F[_]] {

  /** What the endpoint will answer next. */
  def current: F[ServiceCapabilities]

  /** Replaces the answer, as a service would when an upstream came back. */
  def set(capabilities: ServiceCapabilities): F[Unit]

  /** Replaces one cluster's entry and leaves the rest alone. */
  def setCluster(cluster: ClusterId, capability: ClusterCapability): F[Unit]

  /** How many times the answer has been asked for since the last [[reset]]. */
  def calls: F[Int]

  def reset: F[Unit]
}

object FakeCapabilities {

  /** Available on every cluster it is given. The shape a healthy service reports. */
  def available[F[_]: Sync](
      service: ServiceId,
      clusters: List[ClusterId],
      features: List[String]
  ): F[FakeCapabilities[F]] =
    apply(
      ServiceCapabilities(
        service,
        clusters.map(cluster => cluster -> ClusterCapability(configured = true, features, "available")).toMap
      )
    )

  def apply[F[_]: Sync](initial: ServiceCapabilities): F[FakeCapabilities[F]] =
    for {
      state <- Ref.of[F, ServiceCapabilities](initial)
      counter <- Ref.of[F, Int](0)
    } yield new FakeCapabilities[F] {
      def current: F[ServiceCapabilities] = counter.update(_ + 1) *> state.get
      def set(capabilities: ServiceCapabilities): F[Unit] = state.set(capabilities)

      def setCluster(cluster: ClusterId, capability: ClusterCapability): F[Unit] =
        state.update(existing => existing.copy(clusters = existing.clusters.updated(cluster, capability)))

      def calls: F[Int] = counter.get
      def reset: F[Unit] = counter.set(0) *> state.set(initial)
    }
}
