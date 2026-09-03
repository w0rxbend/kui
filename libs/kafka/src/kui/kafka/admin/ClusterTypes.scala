package kui.kafka.admin

import kui.kafka.SkipReason
import kui.kernel.{BrokerId, KafkaClusterId, PartitionId, TopicName}

/** One broker, as `describeCluster` reports it. */
final case class KafkaNode(id: BrokerId, host: String, port: Int, rack: Option[String])

/** The cluster-scoped ACL operations, mapped from `org.apache.kafka.common.acl.AclOperation` so that nothing
  * above `libs/kafka` imports a Kafka enum.
  *
  * `Unknown` exists because a broker newer than KUI can name an operation this enum does not have, and an
  * unhandled value must be a value rather than an exception.
  */
enum ClusterOperation {
  case Describe
  case DescribeConfigs
  case Alter
  case AlterConfigs
  case ClusterAction
  case Create
  case Delete
  case IdempotentWrite
  case All
  case Unknown
}

object ClusterOperation {
  given CanEqual[ClusterOperation, ClusterOperation] = CanEqual.derived
}

/** What `describeCluster` reports.
  *
  * Three fields are `Option` and each for its own reason, none of them cosmetic:
  *
  *   - `controller`, because Kafka returns `null` during a controller failover and because a KRaft controller
  *     need not be a broker at all. "Electing" is a normal state of a healthy cluster.
  *   - `kafkaClusterId`, because some managed services do not report one (ADR-031).
  *   - `authorizedOperations`, because Kafka returns `null` when the cluster has no authorizer configured.
  *     That means "ACLs are off", not "you may do nothing", and an empty set would confuse the two — with the
  *     consequence that KUI would hide every action on a cluster where the user is in fact allowed to do
  *     everything.
  */
final case class ClusterDescription(
    kafkaClusterId: Option[KafkaClusterId],
    controller: Option[KafkaNode],
    nodes: List[KafkaNode],
    authorizedOperations: Option[Set[ClusterOperation]]
)

/** How KUI learned the broker version, so that an operator can tell a detected number from a guessed one.
  */
enum VersionSource {
  case Features
  case InterBrokerProtocol
  case Unknown
}

object VersionSource {
  given CanEqual[VersionSource, VersionSource] = CanEqual.derived
}

final case class BrokerVersion(
    version: Option[KafkaVersion],
    /** Exactly what the broker said — `level 21`, `3.9-IV0`, a raw config value — kept so that an operator
      * can see it when the parse produced nothing.
      */
    raw: Option[String],
    source: VersionSource
) {

  /** ADR-030's minimum, as a value rather than a refusal.
    *
    * `None` when the version could not be detected at all: "we could not tell" is not "too old", and refusing
    * to serve a cluster because a managed service hides its version would break clusters that work perfectly
    * well.
    */
  def meetsMinimum: Option[Boolean] = version.map(_ >= KafkaVersion.minimumSupported)
}

/** Where a configuration value came from, mapped from Kafka's own `ConfigEntry.ConfigSource`.
  *
  * The distinction is what lets the UI show an operator which settings were actually changed on this cluster
  * and which are the defaults they have never touched.
  */
enum ConfigSource {
  case DynamicBrokerConfig
  case DynamicDefaultBrokerConfig
  case DynamicTopicConfig
  case DynamicBrokerLoggerConfig
  case StaticBrokerConfig
  case DefaultConfig
  case Unknown
}

object ConfigSource {
  given CanEqual[ConfigSource, ConfigSource] = CanEqual.derived
}

final case class ConfigSynonym(name: String, value: Option[String], source: ConfigSource)

/** One configuration entry.
  *
  * `value` is an `Option` and it is `None` for a sensitive setting, because that is exactly what the broker
  * sends: Kafka returns `null` for the value of a sensitive config rather than the value itself. Modelling it
  * as an empty string would render a password field that looks unset, and an operator would conclude the
  * setting is missing.
  */
final case class ConfigEntry(
    name: String,
    value: Option[String],
    source: ConfigSource,
    isSensitive: Boolean,
    isReadOnly: Boolean,
    isDefault: Boolean,
    documentation: Option[String],
    synonyms: List[ConfigSynonym]
)

final case class ReplicaInfo(
    topic: TopicName,
    partition: PartitionId,
    sizeBytes: Long,
    offsetLag: Long,
    isFuture: Boolean
)

/** One log directory on one broker.
  *
  * `error` is per directory, not per broker: a single offline disk answers `KafkaStorageException` for itself
  * while the broker's other directories answer normally, and a model that could not express that would have
  * to discard a healthy broker's data because one of its disks is down.
  *
  * `totalBytes` and `usableBytes` are `Option` because brokers before 3.3 do not report them.
  */
final case class LogDir(
    path: String,
    error: Option[SkipReason],
    totalBytes: Option[Long],
    usableBytes: Option[Long],
    replicas: List[ReplicaInfo]
) {

  /** What the broker actually holds here, as distinct from what the filesystem reports.
    *
    * Both numbers are shown. They differ — by the index files, by segments pending deletion, by whatever else
    * shares the disk — and an operator chasing a full disk needs to see the difference rather than one number
    * chosen for them.
    */
  def usedByReplicasBytes: Long = replicas.map(_.sizeBytes).sum
}

final case class QuorumVoter(
    replicaId: BrokerId,
    logEndOffset: Long,
    lastFetchTimestamp: Option[Long],
    lastCaughtUpTimestamp: Option[Long]
) {

  /** `highWatermark - logEndOffset`, floored at zero.
    *
    * The floor is the part people forget: an observer can briefly report an offset ahead of the high
    * watermark it was told about, and a negative lag rendered in a table reads as a bug in KUI rather than as
    * the millisecond of skew it is.
    */
  def lagFrom(highWatermark: Long): Long = math.max(0L, highWatermark - logEndOffset)
}

final case class QuorumInfo(
    leaderId: BrokerId,
    leaderEpoch: Long,
    highWatermark: Long,
    voters: List[QuorumVoter],
    observers: List[QuorumVoter]
)
