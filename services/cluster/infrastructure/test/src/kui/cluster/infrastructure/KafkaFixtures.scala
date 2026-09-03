package kui.cluster.infrastructure

import java.time.Instant

import kui.kafka.BatchResult
import kui.kafka.admin as adm
import kui.kernel.{BrokerId, KafkaClusterId, PartitionId, TopicName}

/** `libs/kafka` values shaped like a small, healthy, three-broker cluster.
  *
  * Every field a real broker can leave out is present here in its *populated* form; the tests that care
  * about absence build their own value from these by copying. That way a test named
  * "no controller is representable" reads as the one difference from a normal cluster.
  */
object KafkaFixtures {

  val probedAt: Instant = Instant.parse("2026-09-04T10:15:00Z")

  val nodeOne: adm.KafkaNode = adm.KafkaNode(BrokerId.unsafe(1), "broker-1", 9092, Some("rack-a"))
  val nodeTwo: adm.KafkaNode = adm.KafkaNode(BrokerId.unsafe(2), "broker-2", 9092, None)
  val nodeThree: adm.KafkaNode = adm.KafkaNode(BrokerId.unsafe(3), "broker-3", 9092, Some("  "))

  val description: adm.ClusterDescription = adm.ClusterDescription(
    kafkaClusterId = Some(KafkaClusterId.unsafe("MkU3OEVBNTcwNTJENDM2Qk")),
    controller = Some(nodeOne),
    nodes = List(nodeThree, nodeOne, nodeTwo),
    authorizedOperations = Some(Set(adm.ClusterOperation.Describe, adm.ClusterOperation.DescribeConfigs))
  )

  val version: adm.BrokerVersion =
    adm.BrokerVersion(Some(adm.KafkaVersion(3, 9, 0)), Some("3.9-IV0"), adm.VersionSource.Features)

  val quorum: adm.QuorumInfo = adm.QuorumInfo(
    leaderId = BrokerId.unsafe(1),
    leaderEpoch = 7L,
    highWatermark = 1000L,
    voters = List(
      adm.QuorumVoter(BrokerId.unsafe(1), 1000L, Some(1_700_000_000_000L), Some(1_700_000_000_000L)),
      adm.QuorumVoter(BrokerId.unsafe(2), 990L, Some(-1L), None)
    ),
    observers = Nil
  )

  val configs: List[adm.ConfigEntry] = List(
    adm.ConfigEntry(
      name = "log.retention.hours",
      value = Some("168"),
      source = adm.ConfigSource.StaticBrokerConfig,
      isSensitive = false,
      isReadOnly = false,
      isDefault = false,
      documentation = Some("How long a log segment is kept"),
      synonyms = List(adm.ConfigSynonym("log.retention.hours", Some("168"), adm.ConfigSource.StaticBrokerConfig))
    ),
    // Kafka sends `null` for the value of a sensitive setting; `None` is what that has to stay.
    adm.ConfigEntry(
      name = "listener.name.internal.ssl.keystore.password",
      value = None,
      source = adm.ConfigSource.DynamicBrokerConfig,
      isSensitive = true,
      isReadOnly = false,
      isDefault = false,
      documentation = None,
      synonyms = Nil
    )
  )

  val healthyDir: adm.LogDir = adm.LogDir(
    path = "/var/lib/kafka/data",
    error = None,
    totalBytes = Some(100_000L),
    usableBytes = Some(40_000L),
    replicas = List(
      adm.ReplicaInfo(TopicName.unsafe("orders"), PartitionId.unsafe(0), 512L, 0L, isFuture = false)
    )
  )

  val offlineDir: adm.LogDir = adm.LogDir(
    path = "/var/lib/kafka/data-2",
    error = Some(kui.kafka.SkipReason.Failed(kui.kernel.error.ErrorCode.UpstreamUnavailable, "")),
    totalBytes = None,
    usableBytes = None,
    replicas = Nil
  )

  val logDirs: BatchResult[BrokerId, List[adm.LogDir]] =
    BatchResult(
      values = Map(BrokerId.unsafe(1) -> List(healthyDir, offlineDir), BrokerId.unsafe(2) -> List(healthyDir)),
      skipped = Map(BrokerId.unsafe(3) -> kui.kafka.SkipReason.NotAuthorized("no DESCRIBE on the cluster"))
    )

  val features: adm.ClusterFeatures = adm.ClusterFeatures(
    present = Set(adm.ClusterFeature.LogDirs, adm.ClusterFeature.KRaftQuorum),
    absent = Set(adm.ClusterFeature.TieredStorage),
    unknown = adm.ClusterFeature.all -- Set(
      adm.ClusterFeature.LogDirs,
      adm.ClusterFeature.KRaftQuorum,
      adm.ClusterFeature.TieredStorage
    ),
    probedAt = probedAt
  )
}
