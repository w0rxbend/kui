package kui.consumer.application

import java.time.Instant

import cats.effect.kernel.Temporal
import cats.syntax.all.*

import kui.consumer.domain.GroupSummary
import kui.kernel.error.{ApplicationError, ErrorCode, KuiError}
import kui.kernel.{ClusterId, TopicName}

/** The consumer half of the topic page's Consumers tab.
  *
  * It is a *section* of the gateway's topic-overview aggregation rather than a call the browser makes to this
  * service directly (DEVPLAN §10 D13). `ui-topics` never learns this service's routes, and the tab is drawn
  * by `ui-consumers` inside a slot it does not import — which is the only way the split-bundle property of
  * ADR-012 is real rather than claimed.
  */
final case class TopicConsumersView(
    topic: TopicName,
    groups: List[GroupSummary],
    freshness: SnapshotFreshness,
    computedAt: Instant
)

trait GroupsForTopicUseCase[F[_]] {

  /** Every group consuming this topic, from the snapshot and with no extra call.
    *
    * A topic nobody consumes is an empty list, not a 404: "no consumer groups" is the answer, and it is a
    * common and healthy one.
    */
  def forTopic(cluster: ClusterId, topic: TopicName): F[Either[KuiError, TopicConsumersView]]
}

object GroupsForTopicUseCase {

  val Operation: String = "kui.consumer.for_topic"

  def make[F[_]: Temporal](snapshots: GroupSnapshots[F]): GroupsForTopicUseCase[F] =
    new GroupsForTopicUseCase[F] {

      def forTopic(cluster: ClusterId, topic: TopicName): F[Either[KuiError, TopicConsumersView]] =
        snapshots.of(cluster).flatMap {
          case None =>
            ApplicationError
              .NotFound("cluster", cluster.value, ErrorCode.ClusterNotFound)
              .asLeft[TopicConsumersView]
              .pure[F]

          case Some(cell) =>
            for {
              snapshot <- cell.get
              now <- Temporal[F].realTimeInstant
              held = snapshot.value.getOrElse(GroupSnapshot.empty(now))
              consuming = held.groupsOf(topic).map(_.groupId).toSet
            } yield TopicConsumersView(
              topic = topic,
              groups = held.summaries.filter(row => consuming.contains(row.groupId)).toList,
              freshness = SnapshotFreshness.of(
                snapshot,
                kui.kernel.error.InfrastructureError
                  .Unreachable("kafka", "the group snapshot has not loaded yet")
              ),
              computedAt = now
            ).asRight[KuiError]
        }
    }
}
