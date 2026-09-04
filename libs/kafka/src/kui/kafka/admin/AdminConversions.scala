package kui.kafka.admin

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

import org.apache.kafka.clients.admin as jadmin
import org.apache.kafka.common.acl.AclOperation
import org.apache.kafka.common.{GroupState as KafkaGroupState, GroupType, Node, TopicPartition}

import kui.kafka.{KafkaErrorMapper, SkipReason}
import kui.kernel.group.{GroupProtocol, GroupState}
import kui.kernel.{BrokerId, GroupId, KafkaClusterId, PartitionId, TopicName}

/** Kafka's objects, turned into KUI's.
  *
  * These are pure functions in their own object rather than private methods on the adapter for one reason:
  * almost every rule this milestone cares about at the Kafka boundary is a *conversion* rule — a `null` that
  * means "electing", a `null` that means "ACLs are off", a `-1` that means "not reported", a sensitive value
  * that arrives as `null` and must stay absent. Testing those through a live broker would need a cluster in
  * every state; testing them here needs a constructor call.
  *
  * The adapter above is then only plumbing: issue the call, hand the result to one of these.
  */
object AdminConversions {

  /** Kafka's "no controller" is expressed two ways — a `null` node, and a node whose id is the negative
    * sentinel — and both happen during a KRaft controller failover.
    */
  private val NoNodeId: Int = -1

  /** Brokers that do not report a size use an empty `OptionalLong`; some report `-1` instead. */
  private val NotReported: Long = -1L

  def node(raw: Node): KafkaNode = KafkaNode(
    id = BrokerId.unsafe(raw.id),
    host = Option(raw.host).getOrElse(""),
    port = raw.port,
    // Never the empty string. The domain asserts the same thing one layer up, deliberately: a rack
    // rendered as "" looks like a rack named "" rather than like a broker without one.
    rack = Option(raw.rack).filter(_.nonEmpty)
  )

  /** `null`, and the negative sentinel, both mean "there is no controller right now". */
  def controller(raw: Node | Null): Option[KafkaNode] =
    Option(raw).filter(_.id != NoNodeId).map(node)

  def clusterId(raw: String | Null): Option[KafkaClusterId] =
    Option(raw).filter(_.nonEmpty).map(KafkaClusterId.unsafe)

  /** `null` means the cluster has no authorizer configured — ACLs are off — which is not the same statement
    * as "you are allowed nothing". Collapsing the two into an empty set would make KUI hide every action on a
    * cluster where the user may in fact do everything.
    */
  def authorizedOperations(raw: java.util.Set[AclOperation] | Null): Option[Set[ClusterOperation]] =
    Option(raw).map(_.asScala.toSet.map(operation))

  def operation(raw: AclOperation): ClusterOperation = raw match {
    case AclOperation.DESCRIBE => ClusterOperation.Describe
    case AclOperation.DESCRIBE_CONFIGS => ClusterOperation.DescribeConfigs
    case AclOperation.ALTER => ClusterOperation.Alter
    case AclOperation.ALTER_CONFIGS => ClusterOperation.AlterConfigs
    case AclOperation.CLUSTER_ACTION => ClusterOperation.ClusterAction
    case AclOperation.CREATE => ClusterOperation.Create
    case AclOperation.DELETE => ClusterOperation.Delete
    case AclOperation.IDEMPOTENT_WRITE => ClusterOperation.IdempotentWrite
    case AclOperation.ALL => ClusterOperation.All
    // A broker newer than KUI can name an operation this enum does not have. That is a value, not
    // an exception: the alternative is a page that fails because a cluster was upgraded.
    case _ => ClusterOperation.Unknown
  }

  /** The description, with its nodes sorted by broker id.
    *
    * The broker list is rendered in this order, and an order that depends on the broker's response is an
    * order that reshuffles between refreshes while an operator is reading it.
    */
  def clusterDescription(
      rawClusterId: String | Null,
      rawController: Node | Null,
      rawNodes: java.util.Collection[Node],
      rawOperations: java.util.Set[AclOperation] | Null
  ): ClusterDescription = ClusterDescription(
    kafkaClusterId = clusterId(rawClusterId),
    controller = controller(rawController),
    nodes = rawNodes.asScala.toList.map(node).sortBy(_.id.value),
    authorizedOperations = authorizedOperations(rawOperations)
  )

  // ------------------------------------------------------------------ configuration

  def configSource(raw: jadmin.ConfigEntry.ConfigSource): ConfigSource = raw match {
    case jadmin.ConfigEntry.ConfigSource.DYNAMIC_BROKER_CONFIG => ConfigSource.DynamicBrokerConfig
    case jadmin.ConfigEntry.ConfigSource.DYNAMIC_DEFAULT_BROKER_CONFIG =>
      ConfigSource.DynamicDefaultBrokerConfig
    case jadmin.ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG => ConfigSource.DynamicTopicConfig
    case jadmin.ConfigEntry.ConfigSource.DYNAMIC_BROKER_LOGGER_CONFIG =>
      ConfigSource.DynamicBrokerLoggerConfig
    case jadmin.ConfigEntry.ConfigSource.STATIC_BROKER_CONFIG => ConfigSource.StaticBrokerConfig
    case jadmin.ConfigEntry.ConfigSource.DEFAULT_CONFIG => ConfigSource.DefaultConfig
    case _ => ConfigSource.Unknown
  }

  def synonym(raw: jadmin.ConfigEntry.ConfigSynonym): ConfigSynonym = ConfigSynonym(
    name = raw.name,
    value = Option(raw.value),
    source = configSource(raw.source)
  )

  /** One entry.
    *
    * A sensitive entry keeps `isSensitive = true` and `value = None`, always: Kafka sends `null` for the
    * value of a sensitive setting, and there is no path here that fabricates a placeholder.
    */
  def configEntry(raw: jadmin.ConfigEntry): ConfigEntry = ConfigEntry(
    name = raw.name,
    value = Option(raw.value),
    source = configSource(raw.source),
    isSensitive = raw.isSensitive,
    isReadOnly = raw.isReadOnly,
    isDefault = raw.isDefault,
    documentation = Option(raw.documentation).filter(_.nonEmpty),
    synonyms = Option(raw.synonyms).fold(List.empty[ConfigSynonym])(_.asScala.toList.map(synonym))
  )

  /** A whole broker configuration, sorted by name. A table that reorders between refreshes is unusable, and
    * Kafka does not promise an order.
    */
  def config(raw: jadmin.Config): List[ConfigEntry] =
    raw.entries.asScala.toList.map(configEntry).sortBy(_.name)

  // ------------------------------------------------------------------ log directories

  def replicaInfo(partition: TopicPartition, raw: jadmin.ReplicaInfo): ReplicaInfo = ReplicaInfo(
    topic = TopicName.unsafe(partition.topic),
    partition = PartitionId.unsafe(partition.partition),
    sizeBytes = raw.size,
    offsetLag = raw.offsetLag,
    isFuture = raw.isFuture
  )

  /** An `OptionalLong` that is empty, and the `-1` some brokers send instead, are both "not reported". A `-1`
    * rendered as a size is a table cell reading "-1 B".
    */
  def reportedSize(raw: java.util.OptionalLong): Option[Long] =
    raw.toScala.filter(_ != NotReported)

  /** One directory.
    *
    * A directory with an error is still listed. "This disk is down" is the single most important thing this
    * screen can say, and a directory that vanished from the list says nothing at all.
    */
  def logDir(path: String, raw: jadmin.LogDirDescription): LogDir = LogDir(
    path = path,
    error = Option(raw.error).flatMap(failure =>
      KafkaErrorMapper
        .suppressible(failure)
        .orElse(Some(SkipReason.Failed(KafkaErrorMapper.map("describeLogDirs", failure).code, "")))
    ),
    totalBytes = reportedSize(raw.totalBytes),
    usableBytes = reportedSize(raw.usableBytes),
    replicas = raw.replicaInfos.asScala.toList
      .map((partition, info) => replicaInfo(partition, info))
      .sortBy(replica => (replica.topic.value, replica.partition.value))
  )

  def logDirs(raw: java.util.Map[String, jadmin.LogDirDescription]): List[LogDir] =
    raw.asScala.toList.map((path, description) => logDir(path, description)).sortBy(_.path)
  // ------------------------------------------------------------------ consumer groups

  /** The only place in KUI that reads a Kafka group-state value.
    *
    * Total by construction. A state this build of KUI has never heard of — Kafka adds them, and `ASSIGNING`,
    * `RECONCILING` and `NOT_READY` arrived with KIP-848 — maps to `GroupState.Unknown` rather than throwing.
    * A broker newer than KUI is not an error.
    *
    * `None` maps to `Unknown` and emphatically not to `Dead`: `groupState()` is an `Optional` because a
    * broker older than 2.6 does not report one, and telling an operator that a perfectly healthy group is
    * being removed is the single most consequential mistake this function could make.
    *
    * The KIP-848 rebalance states are folded onto the classic pair an operator already knows: `ASSIGNING` is
    * the coordinator computing a new assignment (`PreparingRebalance`) and `RECONCILING` is members moving
    * onto it (`CompletingRebalance`). Two vocabularies on one screen would mean an operator has to know which
    * protocol a group speaks before they can read its chip.
    */
  private[kafka] def toGroupState(raw: Option[KafkaGroupState]): GroupState = raw match {
    case Some(KafkaGroupState.STABLE) => GroupState.Stable
    case Some(KafkaGroupState.EMPTY) => GroupState.Empty
    case Some(KafkaGroupState.DEAD) => GroupState.Dead
    case Some(KafkaGroupState.PREPARING_REBALANCE) => GroupState.PreparingRebalance
    case Some(KafkaGroupState.COMPLETING_REBALANCE) => GroupState.CompletingRebalance
    case Some(KafkaGroupState.ASSIGNING) => GroupState.PreparingRebalance
    case Some(KafkaGroupState.RECONCILING) => GroupState.CompletingRebalance
    case Some(KafkaGroupState.NOT_READY) => GroupState.PreparingRebalance
    case Some(KafkaGroupState.UNKNOWN) => GroupState.Unknown
    case None => GroupState.Unknown
  }

  /** Which protocol a group speaks, as far as the broker will say.
    *
    * `STREAMS` maps to `Unknown` rather than gaining a case: KUI does not list Kafka Streams groups as
    * consumer groups, and a name in this enum that no screen renders is decoration.
    */
  private[kafka] def toGroupProtocol(raw: Option[GroupType]): GroupProtocol = raw match {
    case Some(GroupType.CLASSIC) => GroupProtocol.Classic
    case Some(GroupType.CONSUMER) => GroupProtocol.Consumer
    case _ => GroupProtocol.Unknown
  }

  /** Whether a listing is a consumer group at all.
    *
    * Kafka 4's `listGroups` sees share groups and Streams groups too (KIP-932, KIP-1071). KUI lists consumer
    * groups — classic and KIP-848 — and nothing else, so anything else is dropped here, counted by the
    * caller, and never rendered as a consumer group with no members.
    */
  private[kafka] def isConsumerGroup(raw: Option[GroupType]): Boolean = raw match {
    case Some(GroupType.CLASSIC) => true
    case Some(GroupType.CONSUMER) => true
    // A broker that reports no type at all is a broker older than 3.8, which had only consumer groups.
    case None => true
    case _ => false
  }

  private[kafka] def groupListing(raw: jadmin.GroupListing): GroupListing =
    GroupListing(
      groupId = GroupId.unsafe(raw.groupId),
      isSimple = raw.isSimpleConsumerGroup,
      state = toGroupState(raw.groupState.toScala),
      protocol = toGroupProtocol(raw.`type`.toScala)
    )

  private[kafka] def groupDescription(
      id: GroupId,
      raw: jadmin.ConsumerGroupDescription
  ): GroupDescription = {
    val protocol = toGroupProtocol(Option(raw.`type`))

    GroupDescription(
      groupId = id,
      isSimple = raw.isSimpleConsumerGroup,
      state = toGroupState(Option(raw.groupState)),
      protocol = protocol,
      partitionAssignor = Option(raw.partitionAssignor).getOrElse(""),
      members = raw.members.asScala.toList.map(member => groupMember(member, protocol)).sorted,
      coordinator = Option(raw.coordinator)
        .filter(_.id != NoNodeId)
        .map(node => GroupCoordinator(BrokerId.unsafe(node.id), node.host, node.port)),
      // `null` means the cluster has no authorizer: ACLs are off, which is not an empty permission set.
      authorizedOperations = Option(raw.authorizedOperations).map(_.asScala.toSet.map(groupOperation))
    )
  }

  /** One member, and where the coordinator is moving it to.
    *
    * `targetAssignment()` is a KIP-848 field: a classic member never reports one, and a consumer-protocol
    * member reports one on every describe, equal to its assignment when it is settled. `GroupMember.of`
    * collapses that equal case, so a member is "rebalancing" only when it really is.
    */
  private[kafka] def groupMember(
      raw: jadmin.MemberDescription,
      protocol: GroupProtocol
  ): GroupMember =
    GroupMember.of(
      memberId = raw.consumerId,
      groupInstanceId = raw.groupInstanceId.toScala,
      clientId = raw.clientId,
      host = raw.host,
      assignment = MemberAssignment(memberAssignment(raw.assignment)),
      target = Option
        .when(protocol == GroupProtocol.Consumer)(raw.targetAssignment.toScala)
        .flatten
        .map(target => MemberAssignment(memberAssignment(target)))
    )

  private def memberAssignment(raw: jadmin.MemberAssignment | Null): Set[kui.kernel.TopicPartition] =
    Option(raw).toSet.flatMap((assignment: jadmin.MemberAssignment) =>
      assignment.topicPartitions.asScala.toSet.map(partition)
    )

  private[kafka] def partition(raw: TopicPartition): kui.kernel.TopicPartition =
    kui.kernel.TopicPartition(TopicName.unsafe(raw.topic), PartitionId.unsafe(raw.partition))

  private[kafka] def groupOperation(raw: AclOperation): GroupOperation = raw match {
    case AclOperation.DESCRIBE => GroupOperation.Describe
    case AclOperation.READ => GroupOperation.Read
    case AclOperation.DELETE => GroupOperation.Delete
    case AclOperation.ALL => GroupOperation.All
    case _ => GroupOperation.Unknown
  }
}
