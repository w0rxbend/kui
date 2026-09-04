package kui.contracts.topic

import io.circe.syntax.*
import io.circe.{Codec, HCursor, Json}
import sttp.tapir.Schema

import kui.contracts.KernelCodecs.given
import kui.contracts.KernelSchemas.given
import kui.kernel.{BrokerId, PartitionId}

/** One copy of one partition, on one broker.
  *
  * @param leader
  *   whether this replica is the partition's leader. Exactly one replica of a healthy partition has it, and
  *   none of an offline partition does
  * @param inSync
  *   whether the broker holding this replica is in the partition's in-sync set. A replica that is not is
  *   behind, and a partition with several of them is one broker failure away from data loss
  */
final case class ReplicaDto(broker: BrokerId, leader: Boolean, inSync: Boolean)

object ReplicaDto {

  given Codec[ReplicaDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        broker <- cursor.get[BrokerId]("broker")
        leader <- cursor.getOrElse[Boolean]("leader")(false)
        inSync <- cursor.getOrElse[Boolean]("inSync")(false)
      } yield ReplicaDto(broker, leader, inSync),
    (dto: ReplicaDto) =>
      Json.obj(
        "broker" -> dto.broker.asJson,
        "leader" -> dto.leader.asJson,
        "inSync" -> dto.inSync.asJson
      )
  )

  given Schema[ReplicaDto] = Schema.derived[ReplicaDto].description("One replica of one partition")

  given CanEqual[ReplicaDto, ReplicaDto] = CanEqual.derived
}

/** One partition of a topic.
  *
  * `leader` is an `Option`, and a `null` leader is the wire form of **offline**. Kafka's own API reports a
  * leaderless partition as node id `-1`, and a client that carried that number through would render "leader
  * -1" or, worse, treat it as a broker id. The absence is the honest shape, and it is what makes the four
  * consistent statements on the detail screen possible: no leader, no count for the row, no count on the
  * strip, and the offline indicator lit.
  *
  * @param earliestOffset
  *   the first offset still retained. `None` when `listOffsets` could not answer for this partition
  * @param latestOffset
  *   the offset the next record will get — Kafka's half-open convention, so `latest - earliest` is the number
  *   of retained records with no `+ 1` to remember
  * @param messageCount
  *   `latest - earliest`, or `None` when either end is unknown. Never a partial answer
  * @param sizeBytes
  *   the partition's on-disk size, or `None` without `describeLogDirs`
  */
final case class PartitionDto(
    partition: PartitionId,
    leader: Option[BrokerId],
    replicas: List[ReplicaDto],
    earliestOffset: Option[Long],
    latestOffset: Option[Long],
    messageCount: Option[Long],
    sizeBytes: Option[Long]
)

object PartitionDto {

  given Codec[PartitionDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        partition <- cursor.get[PartitionId]("partition")
        leader <- cursor.get[Option[BrokerId]]("leader")
        replicas <- cursor.getOrElse[List[ReplicaDto]]("replicas")(Nil)
        earliestOffset <- cursor.get[Option[Long]]("earliestOffset")
        latestOffset <- cursor.get[Option[Long]]("latestOffset")
        messageCount <- cursor.get[Option[Long]]("messageCount")
        sizeBytes <- cursor.get[Option[Long]]("sizeBytes")
      } yield PartitionDto(
        partition,
        leader,
        replicas,
        earliestOffset,
        latestOffset,
        messageCount,
        sizeBytes
      ),
    (dto: PartitionDto) =>
      Json.obj(
        "partition" -> dto.partition.asJson,
        "leader" -> dto.leader.asJson,
        "replicas" -> dto.replicas.asJson,
        "earliestOffset" -> dto.earliestOffset.asJson,
        "latestOffset" -> dto.latestOffset.asJson,
        "messageCount" -> dto.messageCount.asJson,
        "sizeBytes" -> dto.sizeBytes.asJson
      )
  )

  given Schema[PartitionDto] =
    Schema.derived[PartitionDto].description("One partition; a null leader means the partition is offline")

  given CanEqual[PartitionDto, PartitionDto] = CanEqual.derived
}
