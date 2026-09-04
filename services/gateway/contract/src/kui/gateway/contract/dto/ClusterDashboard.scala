package kui.gateway.contract.dto

import io.circe.syntax.*
import io.circe.{Codec, HCursor, Json}
import sttp.tapir.Schema

import kui.contracts.KernelCodecs.given
import kui.contracts.KernelSchemas.given
import kui.contracts.consumer.GroupCodecs.given
import kui.kernel.TopicName
import kui.kernel.group.GroupState

/** One topic, reduced to the one number the dashboard draws a bar for.
  *
  * The dashboard shows the few largest topics on a cluster so that "where are this cluster's partitions" is
  * answerable at a glance. Partition count and not size: size comes from `describeLogDirs`, which is a
  * per-broker sweep over every partition, and the topic list does not have it. A bar drawn against a number
  * KUI does not have would be an invented picture, which is worse than no picture.
  */
final case class TopicMagnitudeDto(name: TopicName, partitionCount: Int)

object TopicMagnitudeDto {

  given Codec[TopicMagnitudeDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        name <- cursor.get[TopicName]("name")
        partitionCount <- cursor.get[Int]("partitionCount")
      } yield TopicMagnitudeDto(name, partitionCount),
    (dto: TopicMagnitudeDto) =>
      Json.obj("name" -> dto.name.asJson, "partitionCount" -> dto.partitionCount.asJson)
  )

  given Schema[TopicMagnitudeDto] =
    Schema.derived[TopicMagnitudeDto].description("One topic and how many partitions it has")

  given CanEqual[TopicMagnitudeDto, TopicMagnitudeDto] = CanEqual.derived
}

/** How many topics a cluster has, how many partitions they hold, and the biggest few.
  *
  * ==Why `partitionCount` is optional==
  *
  * It is a sum over every topic, and the gateway computes it from one page of the topic list. When a cluster
  * has more topics than that page holds, the sum would be a sum over *some* of them — a number that looks
  * exact, is not, and gets smaller when somebody creates a topic. It is `None` in that case and the screen
  * renders a dash. `topicCount` stays, because it is the list's own total and is exact however many pages
  * there are.
  *
  * @param topicCount
  *   every topic on the cluster, internal ones included. The dashboard is an operator's view of what the
  *   broker is actually holding, and `__consumer_offsets` with fifty partitions is part of that
  * @param countedTopics
  *   how many topics the sums above were computed over. Equal to `topicCount` whenever `partitionCount` is
  *   present; it is published so that a client can say *why* the figure is missing rather than only that it
  *   is
  */
final case class TopicTotalsDto(
    topicCount: Long,
    countedTopics: Int,
    partitionCount: Option[Int],
    largest: List[TopicMagnitudeDto]
)

object TopicTotalsDto {

  /** How many topics the dashboard draws a bar for. Five is what fits beside the figures without the card
    * turning into a second topic list; the whole list is one click away on the topics screen.
    */
  val LargestShown: Int = 5

  /** The totals over one page of the topic list.
    *
    * `partitionCount` is present only when the page held every topic there is, which is the rule this whole
    * document exists to keep: a figure nobody can compute is absent, never estimated.
    */
  def of(rows: List[TopicMagnitudeDto], topicCount: Long): TopicTotalsDto =
    TopicTotalsDto(
      topicCount = topicCount,
      countedTopics = rows.size,
      partitionCount = Option.when(rows.size.toLong == topicCount)(rows.map(_.partitionCount).sum),
      largest = rows.sortBy(row => (-row.partitionCount, row.name.value)).take(LargestShown)
    )

  given Codec[TopicTotalsDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        topicCount <- cursor.get[Long]("topicCount")
        countedTopics <- cursor.get[Int]("countedTopics")
        partitionCount <- cursor.get[Option[Int]]("partitionCount")
        largest <- cursor.getOrElse[List[TopicMagnitudeDto]]("largest")(Nil)
      } yield TopicTotalsDto(topicCount, countedTopics, partitionCount, largest),
    (dto: TopicTotalsDto) =>
      Json.obj(
        "topicCount" -> dto.topicCount.asJson,
        "countedTopics" -> dto.countedTopics.asJson,
        "partitionCount" -> dto.partitionCount.asJson,
        "largest" -> dto.largest.asJson
      )
  )

  given Schema[TopicTotalsDto] = Schema
    .derived[TopicTotalsDto]
    .description("A cluster's topic and partition totals; partitionCount is null when it could not be summed")

  given CanEqual[TopicTotalsDto, TopicTotalsDto] = CanEqual.derived
}

/** How many of a cluster's consumer groups are in one state.
  *
  * A list of pairs and not a map, so that the order the dashboard draws the chips in is the server's and is
  * the same on every load. A JSON object's member order is not something a client may rely on.
  */
final case class GroupStateCountDto(state: GroupState, count: Int)

object GroupStateCountDto {

  given Codec[GroupStateCountDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        state <- cursor.get[GroupState]("state")
        count <- cursor.get[Int]("count")
      } yield GroupStateCountDto(state, count),
    (dto: GroupStateCountDto) => Json.obj("state" -> dto.state.asJson, "count" -> dto.count.asJson)
  )

  given Schema[GroupStateCountDto] =
    Schema.derived[GroupStateCountDto].description("How many consumer groups are in one lifecycle state")

  given CanEqual[GroupStateCountDto, GroupStateCountDto] = CanEqual.derived
}

/** A cluster's consumer groups, as the dashboard shows them.
  *
  * @param groupCount
  *   every group the cluster reported
  * @param byState
  *   the states that actually occur, in [[kui.kernel.group.GroupState.All]]'s order, with the states that do
  *   not occur left out. A chip reading "Dead 0" on every dashboard in the world teaches an operator to stop
  *   reading the chips
  * @param totalLag
  *   the sum of every group's lag, or `None` when any group's own lag could not be computed. Kafka reports no
  *   lag for a partition whose group has never committed and for one whose leader is unreachable, and a total
  *   that silently treated those as zero would say a cluster is keeping up at the exact moment it is not
  * @param groupsWithoutLag
  *   how many groups reported no lag, which is what makes `totalLag` absent. Published so the screen can say
  *   why rather than only showing a dash
  */
final case class GroupTotalsDto(
    groupCount: Long,
    byState: List[GroupStateCountDto],
    totalLag: Option[Long],
    groupsWithoutLag: Int
)

object GroupTotalsDto {

  /** The totals over one page of the group list.
    *
    * @param lags
    *   each group's own `totalLag`, in the same order as `states`, `None` where the group had none
    */
  def of(states: List[GroupState], lags: List[Option[Long]], groupCount: Long): GroupTotalsDto = {
    val counted = states.groupBy(identity).view.mapValues(_.size).toMap
    val missing = lags.count(_.isEmpty)
    GroupTotalsDto(
      groupCount = groupCount,
      byState = GroupState.All.flatMap(state => counted.get(state).map(GroupStateCountDto(state, _))),
      // Absent, not zero, and absent for two different reasons that a screen renders the same way: a group
      // whose lag Kafka would not report, and a list longer than the page this was summed over.
      totalLag = Option.when(missing == 0 && states.size.toLong == groupCount)(lags.flatten.sum),
      groupsWithoutLag = missing
    )
  }

  given Codec[GroupTotalsDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        groupCount <- cursor.get[Long]("groupCount")
        byState <- cursor.getOrElse[List[GroupStateCountDto]]("byState")(Nil)
        totalLag <- cursor.get[Option[Long]]("totalLag")
        groupsWithoutLag <- cursor.getOrElse[Int]("groupsWithoutLag")(0)
      } yield GroupTotalsDto(groupCount, byState, totalLag, groupsWithoutLag),
    (dto: GroupTotalsDto) =>
      Json.obj(
        "groupCount" -> dto.groupCount.asJson,
        "byState" -> dto.byState.asJson,
        "totalLag" -> dto.totalLag.asJson,
        "groupsWithoutLag" -> dto.groupsWithoutLag.asJson
      )
  )

  given Schema[GroupTotalsDto] = Schema
    .derived[GroupTotalsDto]
    .description("A cluster's consumer groups by state, with the total lag when every group reported one")

  given CanEqual[GroupTotalsDto, GroupTotalsDto] = CanEqual.derived
}
