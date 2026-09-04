package kui.topic.application

import cats.effect.IO

import kui.kernel.{ClusterId, PartitionId, TopicName}
import kui.topic.domain.*

/** The application layer's fake, held to the port's own contract.
  *
  * This is the seam the M1 review's lesson is about. Every suite in this module is written against
  * `FakeTopicAdmin`, and a fake that answers differently from the live adapter would make all of them pass
  * while the product is broken. Running `PortContractSuite` — the same suite the live adapter runs in
  * `services/topic/infrastructure` — against the fake is what makes the two answerable to one definition.
  */
final class FakeTopicAdminContractSuite extends PortContractSuite {

  private val leaderless: TopicName = TopicName.unsafe("offline")
  private val secret: TopicName = TopicName.unsafe("locked")

  private def partition(id: Int, leader: Option[Int]): PartitionView =
    PartitionView
      .from(
        partition = PartitionId.unsafe(id),
        leader = leader.map(kui.kernel.BrokerId.unsafe),
        replicas = List(kui.kernel.BrokerId.unsafe(1)),
        inSync = leader.toList.map(kui.kernel.BrokerId.unsafe),
        earliestOffset = leader.map(_ => 0L),
        latestOffset = leader.map(_ => 10L),
        sizeBytes = Some(64L)
      )
      .fold(error => throw new AssertionError(error.message), identity)

  private val topics: List[TopicDetail] = List(
    TopicDetail.of(TopicName.unsafe("orders"), isInternal = false, List(partition(0, Some(1)))),
    TopicDetail.of(TopicName.unsafe("__consumer_offsets"), isInternal = true, List(partition(0, Some(1)))),
    TopicDetail.of(leaderless, isInternal = false, List(partition(0, Some(1)), partition(1, None))),
    TopicDetail.of(secret, isInternal = false, List(partition(0, Some(1))))
  )

  def admin: IO[TopicAdmin[IO]] =
    FakeTopicAdmin
      .of(
        topics = topics,
        configs = Map(secret -> TopicConfigView.NotPermitted("KUI may not read this topic's configuration")),
        incomplete = Map(TopicName.unsafe("vanished") -> "it no longer exists")
      )
      .map(fake => fake: TopicAdmin[IO])

  def knownCluster: ClusterId = FakeTopicAdmin.cluster
  def knownTopic: TopicName = TopicName.unsafe("orders")

  override def topicWithoutConfigPermission: Option[TopicName] = Some(secret)
  override def leaderlessTopic: Option[TopicName] = Some(leaderless)
  override def clusterWithAnIncompleteScrape: Option[ClusterId] = Some(FakeTopicAdmin.cluster)
}
