package kui.contracts.cluster

import java.time.Instant

import io.circe.syntax.*
import io.circe.{Codec, HCursor, Json}
import sttp.tapir.Schema

import kui.contracts.KernelCodecs.given
import kui.contracts.KernelSchemas.given
import kui.kernel.BrokerId

/** One node of a cluster, as the brokers list page shows it.
  *
  * `rack` is `Option` because `Node.rack()` is nullable and a broker with no rack must read as "none", not as
  * an empty string that sorts between two real racks. `partitionCount` and `leaderCount` have no source in M1
  * for the reason `ClusterSummaryDto` records, so they are always `None`; replica counts and the skew
  * percentages *are* derivable from `describeLogDirs` and do ship (BR-001).
  *
  * @param replicaCount
  *   how many partition replicas this broker holds, counted from the log directories it reports. This is the
  *   *total*, in-sync and lagging alike. It was called `inSyncReplicaCount` until 2026-09-04 and was filled
  *   from this same total, which is the same number on a healthy cluster and a false one exactly when a
  *   broker falls behind: with one broker of three stopped, the survivors kept reporting their pre-failure
  *   figure as if every replica were still caught up. The in-sync count cannot be had from the calls this
  *   service makes — `describeCluster` and `describeLogDirs` know nothing about ISR, and the only source is
  *   `describeTopics`, one call per batch of topics, which the cluster service does not sweep
  *   (`research/kafka/admin-capabilities.md`). So the field says what it holds instead of claiming a number
  *   nobody computed
  * @param replicaSkewPercent
  *   how far this broker's replica count is from the cluster's mean, as a percentage computed server-side so
  *   that the table, a CSV export and any other client round the same way. `None` means "not computable" — a
  *   cluster with no partitions — which renders `—`, never `0.00%`
  */
final case class BrokerDto(
    id: BrokerId,
    host: String,
    port: Int,
    rack: Option[String],
    isController: Boolean,
    partitionCount: Option[Int],
    leaderCount: Option[Int],
    replicaCount: Option[Int],
    replicaSkewPercent: Option[Double],
    leaderSkewPercent: Option[Double],
    diskUsageBytes: Option[Long],
    segmentCount: Option[Int]
)

object BrokerDto {

  given Codec[BrokerDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        id <- cursor.get[BrokerId]("id")
        host <- cursor.get[String]("host")
        port <- cursor.get[Int]("port")
        rack <- cursor.get[Option[String]]("rack")
        isController <- cursor.getOrElse[Boolean]("isController")(false)
        partitionCount <- cursor.get[Option[Int]]("partitionCount")
        leaderCount <- cursor.get[Option[Int]]("leaderCount")
        replicaCount <- cursor.get[Option[Int]]("replicaCount")
        replicaSkew <- cursor.get[Option[Double]]("replicaSkewPercent")
        leaderSkew <- cursor.get[Option[Double]]("leaderSkewPercent")
        diskUsageBytes <- cursor.get[Option[Long]]("diskUsageBytes")
        segmentCount <- cursor.get[Option[Int]]("segmentCount")
      } yield BrokerDto(
        id,
        host,
        port,
        rack,
        isController,
        partitionCount,
        leaderCount,
        replicaCount,
        replicaSkew,
        leaderSkew,
        diskUsageBytes,
        segmentCount
      ),
    (dto: BrokerDto) =>
      Json.obj(
        "id" -> dto.id.asJson,
        "host" -> dto.host.asJson,
        "port" -> dto.port.asJson,
        "rack" -> dto.rack.asJson,
        "isController" -> dto.isController.asJson,
        "partitionCount" -> dto.partitionCount.asJson,
        "leaderCount" -> dto.leaderCount.asJson,
        "replicaCount" -> dto.replicaCount.asJson,
        "replicaSkewPercent" -> dto.replicaSkewPercent.asJson,
        "leaderSkewPercent" -> dto.leaderSkewPercent.asJson,
        "diskUsageBytes" -> dto.diskUsageBytes.asJson,
        "segmentCount" -> dto.segmentCount.asJson
      )
  )

  given Schema[BrokerDto] = Schema.derived[BrokerDto].description("One broker node and what is on it")

  given CanEqual[BrokerDto, BrokerDto] = CanEqual.derived
}

/** One setting of one broker, as `describeConfigs` reports it.
  *
  * @param value
  *   `None` when the broker marks the setting sensitive — Kafka returns `null` for those, and KUI does not
  *   invent a placeholder. `isSensitive` is carried separately so a client can say "hidden by the broker"
  *   rather than "not set"
  * @param documentation
  *   the broker's own description of the setting, available from Kafka 2.6. `None` on an older broker
  */
final case class BrokerConfigEntryDto(
    name: String,
    value: Option[String],
    source: String,
    isSensitive: Boolean,
    isReadOnly: Boolean,
    documentation: Option[String],
    synonyms: List[String]
)

object BrokerConfigEntryDto {

  given Codec[BrokerConfigEntryDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        name <- cursor.get[String]("name")
        value <- cursor.get[Option[String]]("value")
        source <- cursor.get[String]("source")
        isSensitive <- cursor.getOrElse[Boolean]("isSensitive")(false)
        isReadOnly <- cursor.getOrElse[Boolean]("isReadOnly")(false)
        documentation <- cursor.get[Option[String]]("documentation")
        synonyms <- cursor.getOrElse[List[String]]("synonyms")(Nil)
      } yield BrokerConfigEntryDto(name, value, source, isSensitive, isReadOnly, documentation, synonyms),
    (dto: BrokerConfigEntryDto) =>
      Json.obj(
        "name" -> dto.name.asJson,
        "value" -> dto.value.asJson,
        "source" -> dto.source.asJson,
        "isSensitive" -> dto.isSensitive.asJson,
        "isReadOnly" -> dto.isReadOnly.asJson,
        "documentation" -> dto.documentation.asJson,
        "synonyms" -> dto.synonyms.asJson
      )
  )

  given Schema[BrokerConfigEntryDto] =
    Schema.derived[BrokerConfigEntryDto].description("One broker setting, its value and where it came from")

  given CanEqual[BrokerConfigEntryDto, BrokerConfigEntryDto] = CanEqual.derived
}

/** One partition replica living in one log directory, as `describeLogDirs` reports it.
  *
  * This is a *disk* fact rather than a topic fact, which is why it carries a size and a lag and nothing else:
  * it says how much of this disk one partition of one topic is using, which is the only way to answer "which
  * topic is filling this broker". The topic service's partition view answers a different question — where the
  * leader is and which replicas are in sync — and the two are deliberately not merged.
  *
  * @param offsetLag
  *   how far this copy is behind the partition's log end offset. Non-zero on a replica that is catching up,
  *   and on a `isFuture` replica it is how much of a reassignment is still to be copied.
  * @param isFuture
  *   true while this is the *incoming* copy of a partition being moved onto this disk. Both copies are
  *   reported during the move, so a consumer that sums every entry without checking this flag double-counts
  *   the partition.
  */
final case class LogDirReplicaDto(
    topic: String,
    partition: Int,
    sizeBytes: Long,
    offsetLag: Long,
    isFuture: Boolean
)

object LogDirReplicaDto {

  given Codec[LogDirReplicaDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        topic <- cursor.get[String]("topic")
        partition <- cursor.get[Int]("partition")
        sizeBytes <- cursor.get[Long]("sizeBytes")
        offsetLag <- cursor.getOrElse[Long]("offsetLag")(0L)
        isFuture <- cursor.getOrElse[Boolean]("isFuture")(false)
      } yield LogDirReplicaDto(topic, partition, sizeBytes, offsetLag, isFuture),
    (dto: LogDirReplicaDto) =>
      Json.obj(
        "topic" -> dto.topic.asJson,
        "partition" -> dto.partition.asJson,
        "sizeBytes" -> dto.sizeBytes.asJson,
        "offsetLag" -> dto.offsetLag.asJson,
        "isFuture" -> dto.isFuture.asJson
      )
  )

  given Schema[LogDirReplicaDto] =
    Schema
      .derived[LogDirReplicaDto]
      .description("One partition replica in one log directory, with the space it occupies there")

  given CanEqual[LogDirReplicaDto, LogDirReplicaDto] = CanEqual.derived
}

/** One log directory of one broker.
  *
  * `error` is per directory, not per request: `describeLogDirs` reports a failed disk by attaching an error
  * to that directory while the rest of the response is good, and a page that dropped the whole broker because
  * one disk is offline would hide exactly the fact the operator opened the page to find.
  *
  * @param totalBytes
  *   the disk's size, available from Kafka 3.3. `None` on an older broker, and `None` for an offline
  *   directory, which reports no sizes at all
  */
final case class LogDirDto(
    brokerId: BrokerId,
    path: String,
    error: Option[String],
    totalBytes: Option[Long],
    usableBytes: Option[Long],
    topicCount: Int,
    partitionCount: Int,
    replicas: List[LogDirReplicaDto]
)

object LogDirDto {

  given Codec[LogDirDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        brokerId <- cursor.get[BrokerId]("brokerId")
        path <- cursor.get[String]("path")
        error <- cursor.get[Option[String]]("error")
        totalBytes <- cursor.get[Option[Long]]("totalBytes")
        usableBytes <- cursor.get[Option[Long]]("usableBytes")
        topicCount <- cursor.getOrElse[Int]("topicCount")(0)
        partitionCount <- cursor.getOrElse[Int]("partitionCount")(0)
        // Defaulted rather than required, so a client reading a response from a KUI that predates this field
        // decodes it as "no breakdown reported" instead of failing the whole broker page.
        replicas <- cursor.getOrElse[List[LogDirReplicaDto]]("replicas")(Nil)
      } yield LogDirDto(brokerId, path, error, totalBytes, usableBytes, topicCount, partitionCount, replicas),
    (dto: LogDirDto) =>
      Json.obj(
        "brokerId" -> dto.brokerId.asJson,
        "path" -> dto.path.asJson,
        "error" -> dto.error.asJson,
        "totalBytes" -> dto.totalBytes.asJson,
        "usableBytes" -> dto.usableBytes.asJson,
        "topicCount" -> dto.topicCount.asJson,
        "partitionCount" -> dto.partitionCount.asJson,
        "replicas" -> dto.replicas.asJson
      )
  )

  given Schema[LogDirDto] =
    Schema.derived[LogDirDto].description("One log directory of one broker, with its own error if it failed")

  given CanEqual[LogDirDto, LogDirDto] = CanEqual.derived
}

/** One member of the KRaft metadata quorum, as the leader last saw it.
  *
  * @param lag
  *   how far behind the leader's high watermark this member is, computed **server-side** and never negative.
  *   It is computed there and not here because a lag is a subtraction against the high watermark, and a
  *   client doing that arithmetic itself would be free to pair one snapshot's watermark with another
  *   snapshot's offsets — which produces a negative lag, or a spike that resolves itself the next time
  *   anybody looks
  * @param lastFetch
  *   when the leader last heard a fetch from this member. `None` for the leader itself, which does not fetch
  *   from anyone, and for a member no fetch has ever arrived from
  * @param lastCaughtUp
  *   when this member was last level with the leader. The pair of times is the whole diagnosis: a member
  *   fetching but never catching up is a slow follower, and one that has stopped fetching is a dead one
  */
final case class QuorumMemberDto(
    replicaId: BrokerId,
    logEndOffset: Long,
    lag: Long,
    isLeader: Boolean,
    isVoter: Boolean,
    lastFetch: Option[Instant],
    lastCaughtUp: Option[Instant]
)

object QuorumMemberDto {

  given Codec[QuorumMemberDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        replicaId <- cursor.get[BrokerId]("replicaId")
        logEndOffset <- cursor.get[Long]("logEndOffset")
        lag <- cursor.get[Long]("lag")
        isLeader <- cursor.getOrElse[Boolean]("isLeader")(false)
        isVoter <- cursor.getOrElse[Boolean]("isVoter")(true)
        lastFetch <- cursor.get[Option[Instant]]("lastFetch")
        lastCaughtUp <- cursor.get[Option[Instant]]("lastCaughtUp")
      } yield QuorumMemberDto(replicaId, logEndOffset, lag, isLeader, isVoter, lastFetch, lastCaughtUp),
    (dto: QuorumMemberDto) =>
      Json.obj(
        "replicaId" -> dto.replicaId.asJson,
        "logEndOffset" -> dto.logEndOffset.asJson,
        "lag" -> dto.lag.asJson,
        "isLeader" -> dto.isLeader.asJson,
        "isVoter" -> dto.isVoter.asJson,
        "lastFetch" -> dto.lastFetch.asJson,
        "lastCaughtUp" -> dto.lastCaughtUp.asJson
      )
  )

  given Schema[QuorumMemberDto] =
    Schema.derived[QuorumMemberDto].description("One voter or observer of the KRaft metadata quorum")

  given CanEqual[QuorumMemberDto, QuorumMemberDto] = CanEqual.derived
}

/** The KRaft metadata quorum: who leads it, how far the log has been committed, and who is keeping up.
  *
  * ==Why this is worth a panel==
  *
  * The metadata quorum is the part of a KRaft cluster whose failure is least visible from anywhere else. A
  * controller that has fallen behind still answers, topics still list, messages still flow — and every
  * *administrative* change is being decided by a shrinking set of nodes. The first symptom on any other
  * screen is a create that times out.
  *
  * `describeMetadataQuorum` has been called on every snapshot pass since M1, its answer has been carried on
  * `ClusterTopology.quorum` since M1, and it has never reached a wire or a screen.
  *
  * @param voters
  *   the members whose acknowledgement a metadata write needs. Their count and their lag decide whether the
  *   cluster can still commit metadata at all
  * @param observers
  *   nodes replicating the metadata log without voting — brokers, in a cluster with dedicated controllers. A
  *   lagging observer is a broker with a stale view of the cluster, which is a different and smaller problem
  *   from a lagging voter
  */
final case class QuorumDto(
    leaderId: BrokerId,
    leaderEpoch: Long,
    highWatermark: Long,
    voters: List[QuorumMemberDto],
    observers: List[QuorumMemberDto]
)

object QuorumDto {

  given Codec[QuorumDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        leaderId <- cursor.get[BrokerId]("leaderId")
        leaderEpoch <- cursor.get[Long]("leaderEpoch")
        highWatermark <- cursor.get[Long]("highWatermark")
        voters <- cursor.getOrElse[List[QuorumMemberDto]]("voters")(Nil)
        observers <- cursor.getOrElse[List[QuorumMemberDto]]("observers")(Nil)
      } yield QuorumDto(leaderId, leaderEpoch, highWatermark, voters, observers),
    (dto: QuorumDto) =>
      Json.obj(
        "leaderId" -> dto.leaderId.asJson,
        "leaderEpoch" -> dto.leaderEpoch.asJson,
        "highWatermark" -> dto.highWatermark.asJson,
        "voters" -> dto.voters.asJson,
        "observers" -> dto.observers.asJson
      )
  )

  given Schema[QuorumDto] =
    Schema.derived[QuorumDto].description("The KRaft metadata quorum, as the leader last reported it")

  given CanEqual[QuorumDto, QuorumDto] = CanEqual.derived
}
