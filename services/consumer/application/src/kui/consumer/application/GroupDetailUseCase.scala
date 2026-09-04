package kui.consumer.application

import java.time.Instant

import scala.concurrent.duration.FiniteDuration

import cats.effect.kernel.{Async, Resource}
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger

import kui.cache.{BoundedCache, CacheMetrics}
import kui.consumer.domain.*
import kui.kernel.error.{ApplicationError, ErrorCode, KuiError}
import kui.kernel.{ClusterId, GroupId}

/** Whether the assignments on screen are the ones the coordinator holds right now. */
enum AssignmentFreshness {

  case Current

  /** The group is rebalancing, and these are the assignments last seen, at this time.
    *
    * The screen greys them and shows a badge (DC-H10). The alternative — an empty table — is the failure this
    * exists to prevent: an operator watching a rebalance sees the page they were reading go blank, exactly
    * when they are trying to work out what is happening.
    */
  case LastSeen(at: Instant)

  /** Rebalancing, and nothing was ever seen. The table is genuinely empty and says so. */
  case Unknown
}

object AssignmentFreshness {
  given CanEqual[AssignmentFreshness, AssignmentFreshness] = CanEqual.derived
}

final case class GroupDetailView(
    group: ConsumerGroup,
    topics: List[TopicSubscription],
    total: LagMath.LagTotal,
    assignments: AssignmentFreshness,
    freshness: SnapshotFreshness,
    computedAt: Instant
)

/** The assignments a group last had while it was settled.
  *
  * Bounded and per cluster: a group's members are a few hundred bytes, two thousand of them are a megabyte,
  * and a cluster with more groups than that is one where an operator opening a detail page during a rebalance
  * is rarer than the memory is expensive.
  */
trait LastSeenAssignments[F[_]] {
  def put(cluster: ClusterId, group: GroupId, members: List[GroupMember], at: Instant): F[Unit]
  def get(cluster: ClusterId, group: GroupId): F[Option[(List[GroupMember], Instant)]]
  def invalidate(cluster: ClusterId, group: GroupId): F[Unit]
}

object LastSeenAssignments {

  val CacheName: String = "consumer.assignments"

  def resource[F[_]: Async](
      cluster: ClusterId,
      maxSize: Long,
      ttl: FiniteDuration,
      metrics: CacheMetrics[F]
  ): Resource[F, LastSeenAssignments[F]] =
    BoundedCache
      .make[F, String, (List[GroupMember], Instant)](CacheName, cluster, maxSize, Some(ttl), metrics)
      .map { cache =>
        new LastSeenAssignments[F] {
          private def key(cluster: ClusterId, group: GroupId): String = s"${cluster.value}/${group.value}"

          def put(cluster: ClusterId, group: GroupId, members: List[GroupMember], at: Instant): F[Unit] =
            cache.put(key(cluster, group), (members, at))

          def get(cluster: ClusterId, group: GroupId): F[Option[(List[GroupMember], Instant)]] =
            cache.get(key(cluster, group))

          def invalidate(cluster: ClusterId, group: GroupId): F[Unit] =
            cache.invalidate(key(cluster, group))
        }
      }
}

trait GroupDetailUseCase[F[_]] {

  /** Computed live, because a detail page an operator opened deliberately should not be thirty seconds old.
    *
    * `Left(NotFound)` with `KUI-CLUSTER-NOT-FOUND` for a cluster this service is not serving. An unknown
    * *group* is not a 404: the port answers with a fabricated dead group, and this returns it, so a stale
    * bookmark lands on an empty group page rather than an error. Where existence really matters — the
    * mutations — it is confirmed by listing.
    */
  def detail(cluster: ClusterId, group: GroupId): F[Either[KuiError, GroupDetailView]]
}

object GroupDetailUseCase {

  val Operation: String = "kui.consumer.detail"

  def make[F[_]: Async](
      snapshots: GroupSnapshots[F],
      admin: ClusterId => GroupAdminPort[F],
      lastSeen: LastSeenAssignments[F],
      logger: StructuredLogger[F]
  ): GroupDetailUseCase[F] =
    new GroupDetailUseCase[F] {

      def detail(cluster: ClusterId, group: GroupId): F[Either[KuiError, GroupDetailView]] =
        snapshots.of(cluster).flatMap {
          case None =>
            ApplicationError
              .NotFound("cluster", cluster.value, ErrorCode.ClusterNotFound)
              .asLeft[GroupDetailView]
              .pure[F]

          case Some(cell) =>
            admin(cluster).describe(List(group)).flatMap {
              case Left(error) => error.asLeft[GroupDetailView].pure[F]
              case Right(described) =>
                for {
                  now <- Async[F].realTimeInstant
                  snapshot <- cell.get
                  // A group the describe did not answer for is a group that is not there. The port
                  // fabricates a dead one, so this fallback is only for an empty map, and it holds
                  // the same shape rather than an error.
                  live = described.getOrElse(group, emptyGroup(group, now))
                  view <- withAssignments(cluster, live, now).map { (shown, freshness) =>
                    GroupDetailView(
                      group = shown,
                      topics = shown.subscriptions.sortBy(_.topic.value),
                      total = shown.lagTotal,
                      assignments = freshness,
                      freshness = SnapshotFreshness.of(
                        snapshot,
                        kui.kernel.error.InfrastructureError
                          .Unreachable("kafka", "the group has not been scraped yet")
                      ),
                      computedAt = now
                    )
                  }
                } yield view.asRight[KuiError]
            }
        }

      /** The rebalance rule (DC-H10).
        *
        * A rebalancing group whose members hold nothing is rendered from the last settled assignment, greyed
        * and stamped. A settled group with members *writes* that record. Nothing is ever written during a
        * rebalance: that would overwrite the good data with the empty data the badge exists to hide.
        */
      private def withAssignments(
          cluster: ClusterId,
          group: ConsumerGroup,
          now: Instant
      ): F[(ConsumerGroup, AssignmentFreshness)] = {
        val holdsNothing = group.members.forall(_.partitions.isEmpty)

        if group.isRebalancing && holdsNothing then
          lastSeen.get(cluster, group.groupId).flatMap {
            case Some((members, at)) =>
              logger
                .debug(Map("cluster.id" -> cluster.value, "group.id" -> group.groupId.value))(
                  "the group is rebalancing; rendering the assignments last seen"
                )
                .as((group.copy(members = members), AssignmentFreshness.LastSeen(at)))
            case None => (group, AssignmentFreshness.Unknown: AssignmentFreshness).pure[F]
          }
        else if group.members.nonEmpty && !group.isRebalancing then
          lastSeen
            .put(cluster, group.groupId, group.members, now)
            .as((group, AssignmentFreshness.Current: AssignmentFreshness))
        else (group, AssignmentFreshness.Current: AssignmentFreshness).pure[F]
      }
    }

  /** The shape a group that is not there has: dead, no members, nothing assigned. Same as the port's
    * fabricated one, so a caller cannot tell which produced it.
    */
  private def emptyGroup(group: GroupId, now: Instant): ConsumerGroup =
    ConsumerGroup(
      groupId = group,
      state = kui.kernel.group.GroupState.Dead,
      protocol = kui.kernel.group.GroupProtocol.Unknown,
      isSimple = false,
      partitionAssignor = "",
      members = Nil,
      coordinator = None,
      subscriptions = Nil,
      completeness = GroupCompleteness.Complete,
      observedAt = now
    )
}
