package kui.config

import java.nio.file.Path

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import kui.kernel.cluster.{BootstrapServers, ClusterSecurity}
import kui.kernel.{PositiveInt, Secret}

/** How KUI reaches the cluster that holds its own metadata.
  *
  * Its presence is the on/off switch for the Kafka store, and there is deliberately no separate `enabled`
  * flag: two settings that have to agree are two settings that will eventually disagree, and the disagreement
  * would show up as "why is nothing being saved" rather than as a startup error.
  */
final case class StoreKafkaConfig(
    bootstrapServers: BootstrapServers,
    security: ClusterSecurity,
    properties: Map[String, String]
) {

  /** Never the properties' values: one of them may be a password, and this type is printed at startup. */
  override def toString: String =
    s"StoreKafkaConfig(bootstrapServers=$bootstrapServers, protocol=${security.securityProtocol}, " +
      s"properties={${ClientPropertyOverrides.render(properties)}})"
}

/** The keys KUI can decrypt stored secrets with, and the one it encrypts new writes with.
  *
  * Material is still base64 text here, and still a `Secret`. Decoding it and checking its length is
  * `EncryptionKeyring`'s job (STORE-002); this type is the configuration, not the keyring.
  */
final case class StoreEncryptionConfig(keys: Map[String, Secret[String]], activeKeyId: String) {

  /** Ids yes, material never. */
  override def toString: String =
    s"StoreEncryptionConfig(keys=[${keys.keys.toList.sorted.mkString(",")}], active=$activeKeyId)"
}

/** The `kui.store.*` slice: where KUI keeps its own state and how it protects it.
  *
  * Nothing here opens a socket. An unreachable store cluster is not a configuration error — reachability is
  * not knowable at load time — so this type is purely what the operator wrote down, validated.
  */
final case class StoreConfig(
    topicPrefix: String,
    replicationFactor: Short,
    minInSyncReplicas: PositiveInt,
    maxFileBytes: Long,
    replayTimeout: FiniteDuration,
    writeTimeout: FiniteDuration,
    dir: Option[Path],
    kafka: Option[StoreKafkaConfig],
    encryption: Option[StoreEncryptionConfig]
) {

  /** Whether the Kafka adapter should be built.
    *
    * Exactly `kafka.isDefined`, given a name so that no caller re-derives the rule and gets it subtly
    * different — "and an encryption key is configured", say, which would silently fall back to the file
    * adapter on a deployment that meant to use Kafka.
    */
  def kafkaEnabled: Boolean = kafka.isDefined

  /** The topic names this prefix produces. `__kui_audit` is named for completeness and is not created in M1:
    * the milestone that first writes an audit record creates it (DEVPLAN §10 D7).
    */
  def configTopic: String = s"${topicPrefix}config"
  def filesTopic: String = s"${topicPrefix}files"
  def auditTopic: String = s"${topicPrefix}audit"
}

object StoreConfig {

  val DefaultTopicPrefix: String = "__kui_"
  val DefaultReplicationFactor: Short = 3
  val DefaultMinInSyncReplicas: PositiveInt = PositiveInt.unsafe(2)
  val DefaultMaxFileBytes: Long = 4194304L
  val DefaultReplayTimeout: FiniteDuration = 30.seconds
  val DefaultWriteTimeout: FiniteDuration = 10.seconds

  /** The id given to the single key of the `kui.store.encryptionKey` shorthand. */
  val DefaultKeyId: String = "k1"

  val TopicPrefixPattern: String = "^[a-z0-9_.-]{1,64}$"

  val MinFileBytes: Long = 1024L
  val MaxFileBytes: Long = 64L * 1024L * 1024L

  val MinReplayTimeout: FiniteDuration = 1.second
  val MaxReplayTimeout: FiniteDuration = 10.minutes
  val MinWriteTimeout: FiniteDuration = 1.second
  val MaxWriteTimeout: FiniteDuration = 2.minutes

  /** No Kafka store, no directory: `ConfigStore.empty` is used and every store-backed write reports
    * `NotConfigured`. This is what a first run gets, and what every M0-era configuration file still loads to.
    */
  val Default: StoreConfig = StoreConfig(
    topicPrefix = DefaultTopicPrefix,
    replicationFactor = DefaultReplicationFactor,
    minInSyncReplicas = DefaultMinInSyncReplicas,
    maxFileBytes = DefaultMaxFileBytes,
    replayTimeout = DefaultReplayTimeout,
    writeTimeout = DefaultWriteTimeout,
    dir = None,
    kafka = None,
    encryption = None
  )

  given CanEqual[StoreConfig, StoreConfig] = CanEqual.derived
}
