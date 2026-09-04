package kui.ui.topics.detail

import munit.FunSuite

import kui.contracts.topic.{PartitionDto, ReplicaDto, TopicDetailDto, TopicRowDto}
import kui.kernel.{BrokerId, PartitionId, TopicName}
import kui.ui.kernel.component.{DataTable, Tone}

/** The indicator strip's threshold rules, as a table.
  *
  * Pure, and nothing here mounts a DOM: the strip is a function from the document to nine labelled values,
  * which is what makes each rule a row here rather than a rendering to inspect.
  */
final class TopicIndicatorsSuite extends FunSuite {

  private def replica(broker: Int, leader: Boolean = false, inSync: Boolean = true): ReplicaDto =
    ReplicaDto(BrokerId.unsafe(broker), leader, inSync)

  private def partition(id: Int, replicas: List[ReplicaDto]): PartitionDto =
    PartitionDto(PartitionId.unsafe(id), replicas.find(_.leader).map(_.broker), replicas, None, None, None, None)

  private def detail(
      internal: Boolean = false,
      outOfSync: Int = 0,
      offlinePartitions: Int = 0,
      messages: Option[Long] = Some(1234L),
      sizeBytes: Option[Long] = Some(1048576L),
      segmentCount: Option[Int] = Some(24),
      cleanupPolicy: Option[String] = Some("delete"),
      partitions: List[PartitionDto] = Nil
  ): TopicDetailDto =
    TopicDetailDto(
      row = TopicRowDto(
        TopicName.unsafe("orders"),
        internal,
        partitions.size.max(1),
        Some(3),
        outOfSync,
        offlinePartitions,
        messages,
        sizeBytes
      ),
      partitions = partitions,
      cleanupPolicy = cleanupPolicy,
      segmentCount = segmentCount
    )

  private def valueOf(dto: TopicDetailDto, label: String): String =
    TopicIndicators
      .of(dto)
      .find(_.label == label)
      .map(_.value)
      .getOrElse(fail(s"no indicator labelled '$label'"))

  private def toneOf(dto: TopicDetailDto, label: String): Tone =
    TopicIndicators
      .of(dto)
      .find(_.label == label)
      .map(_.tone)
      .getOrElse(fail(s"no indicator labelled '$label'"))

  test("underReplicatedIsWarningToneAboveZeroAndNormalAtZero") {
    // A healthy zero is drawn like every other quiet number. Colouring it teaches the eye to ignore the
    // colour, which is the one thing the colour has to do.
    assertEquals(toneOf(detail(outOfSync = 0), "Out of sync replicas"), Tone.Neutral)
    assertEquals(toneOf(detail(outOfSync = 1), "Out of sync replicas"), Tone.Warning)
  }

  test("offlinePartitionsAreDangerAndNotMerelyWarning") {
    // A partition with no leader is not producing and not consuming. It is a different severity from a
    // replica that has fallen behind.
    assertEquals(toneOf(detail(offlinePartitions = 0), "Offline partitions"), Tone.Neutral)
    assertEquals(toneOf(detail(offlinePartitions = 2), "Offline partitions"), Tone.Danger)
  }

  test("inSyncReplicasIsUnknownWhenThePartitionListIsMissingRatherThanZeroOfZero") {
    // What a topic page looks like while the cluster is unreachable: the last scrape's row survives, and the
    // partition assignment does not, because it is not part of what the topic service keeps between scrapes.
    // Both halves of this figure are summed from that list, so an empty one used to read "0 of 0" — which
    // says every replica of this topic is out of sync, the most alarming statement the strip can make, made
    // from no data, two lines above a table correctly saying the partitions are not available.
    val unreachable = detail(partitions = Nil)

    assertEquals(unreachable.row.partitionCount, 1)
    assertEquals(valueOf(unreachable, "In sync replicas"), DataTable.missing)
  }

  test("inSyncReplicasReadsNOfMAndIsWarningWhenFewer") {
    val healthy = detail(partitions =
      List(partition(0, List(replica(1, leader = true), replica(2))), partition(1, List(replica(3))))
    )
    assertEquals(valueOf(healthy, "In sync replicas"), "3 of 3")
    assertEquals(toneOf(healthy, "In sync replicas"), Tone.Neutral)

    val lagging = detail(partitions =
      List(partition(0, List(replica(1, leader = true), replica(2, inSync = false))))
    )
    assertEquals(valueOf(lagging, "In sync replicas"), "1 of 2")
    assertEquals(toneOf(lagging, "In sync replicas"), Tone.Warning)
  }

  test("anAbsentMessageCountIsAnEmDash") {
    // The same refusal as the list and as every partition row. Summing the partitions that did answer would
    // produce a number smaller than the truth and present it as the truth.
    assertEquals(valueOf(detail(messages = None), "Messages"), DataTable.missing)
    assertEquals(valueOf(detail(messages = Some(0L)), "Messages"), "0")
  }

  test("everyOtherAbsenceIsAnEmDashToo") {
    val bare = detail(sizeBytes = None, segmentCount = None, cleanupPolicy = None)
    assertEquals(valueOf(bare, "Segments"), DataTable.missing)
    assertEquals(valueOf(bare, "Cleanup policy"), DataTable.missing)
    assertEquals(valueOf(bare, "Size"), DataTable.missing)
  }

  test("internalTopicsShowTheTypeIndicator") {
    assertEquals(valueOf(detail(internal = true), "Type"), "Internal")
    assertEquals(valueOf(detail(internal = false), "Type"), "Normal")
  }

  test("everyIndicatorHasADistinctLabel") {
    // The strip is keyed by label, so two indicators sharing one would silently drop a figure.
    val labels = TopicIndicators.of(detail()).map(_.label)
    assertEquals(labels.distinct.size, labels.size, labels.toString)
    assertEquals(labels.size, 10)
  }

  test("theTestIdSlugIsDerivedFromTheLabel") {
    assertEquals(TopicIndicators.slug("Out of sync replicas"), "out-of-sync-replicas")
    assertEquals(TopicIndicators.slug("Type"), "type")
  }
}
