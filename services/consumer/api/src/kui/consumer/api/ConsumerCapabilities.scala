package kui.consumer.api

import cats.effect.kernel.Sync
import cats.syntax.all.*

import kui.cache.SnapshotStatus
import kui.consumer.application.{ClusterProfileSource, GroupSnapshots}
import kui.contracts.capability.{CapabilityState, ClusterCapability, DegradedReason, ReasonCode}
import kui.kernel.ClusterId

/** What the consumer service can currently do, per cluster, as the gateway reads it.
  *
  * ==What a service may and may not say about itself==
  *
  * `available` or `degraded`, never `unavailable`. A service answering this request is reachable by
  * definition; `unavailable` is the *gateway's* verdict when it gets no answer at all. A Kafka cluster whose
  * group snapshot has never loaded is reported as `degraded` with the reason, so that one unreachable broker
  * greys one row rather than dimming the Consumers entry in the sidebar (ADR-039 §6, DEVPLAN §10 D11).
  */
trait ConsumerCapabilities[F[_]] {
  def report: F[Map[ClusterId, ClusterCapability]]
}

object ConsumerCapabilities {

  /** The `degraded` discriminator, read off the enum rather than typed out a second time. */
  private val DegradedStatus: String =
    CapabilityState.Degraded(DegradedReason(ReasonCode.Starting, "", None, None)).status

  /** What a row says while the first pass is still running. */
  val StartingMessage: String =
    "the first pass over this cluster's consumer groups has not completed yet"

  def make[F[_]: Sync](
      profiles: ClusterProfileSource[F],
      snapshots: GroupSnapshots[F]
  ): ConsumerCapabilities[F] =
    new ConsumerCapabilities[F] {

      def report: F[Map[ClusterId, ClusterCapability]] =
        profiles.all.flatMap(
          _.traverse(profile =>
            snapshots
              .of(profile.cluster)
              .flatMap {
                // A cluster with no cell at all is one the snapshot registry has not caught up with.
                // It is configured — it came from the profile source — so it is degraded and starting,
                // never absent from the map, which would read as "not configured".
                case None => starting.pure[F]
                case Some(cell) =>
                  cell.get.map(snapshot => capabilityOf(snapshot.value.isDefined, snapshot.status))
              }
              .map(profile.cluster -> _)
          ).map(_.toMap)
        )
    }

  private def starting: ClusterCapability =
    ClusterCapability(
      configured = true,
      features = Nil,
      status = DegradedStatus,
      name = None,
      reason = Some(StartingMessage)
    )

  /** The whole translation, in one place, from "is there a snapshot and is the scrape working" to a row. */
  def capabilityOf(hasValue: Boolean, status: SnapshotStatus): ClusterCapability =
    (hasValue, status) match {
      case (true, SnapshotStatus.Online) =>
        ClusterCapability(
          configured = true,
          features = Nil,
          status = CapabilityState.Available.status,
          name = None,
          reason = None
        )

      case (_, SnapshotStatus.Offline(error, _)) =>
        ClusterCapability(
          configured = true,
          features = Nil,
          status = DegradedStatus,
          name = None,
          reason = Some(error.message)
        )

      case _ => starting
    }
}
