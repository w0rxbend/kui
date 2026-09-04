package kui.cluster.infrastructure

import java.time.Instant

import cats.data.NonEmptyList
import cats.syntax.all.*

import kui.cluster.domain as dom
import kui.kafka.{admin as adm, SkipReason as KafkaSkipReason}
import kui.kernel.error.{ApplicationError, FieldError, KuiError}
import kui.kernel.{BrokerId, Host, Port, TopicPartition}

/** The translation between `libs/kafka`'s vocabulary and the cluster domain's.
  *
  * It is pure, total and in its own file so that the nineteen shapes a real cluster can produce — a null
  * controller, a broker with no rack, a sensitive config with no value, an offline log directory, a quorum
  * whose leader is not among its voters — are unit-testable without a broker. The adapter around it is then
  * only plumbing.
  *
  * Three of the types on either side are deliberate duplicates (`ClusterFeature`, `BatchResult` versus
  * `PartialResult`, `SkipReason` versus `LogDirError`): layering rule A5 forbids `libs/kafka` depending on a
  * service and rule A1 forbids the domain depending on `libs/kafka`, so one definition is not available
  * without breaking the layering the milestone exists to prove. The mitigation the gate review chose is that
  * every pair is bridged by an *exhaustive match*, which the compiler checks, and that is what this file is.
  */
object KafkaToDomain {

  // ------------------------------------------------------------------ cluster description

  /** A cluster the broker described in a shape the domain refuses is an `Invalid`, not a crash.
    *
    * It should never happen — Kafka does not invent hosts with spaces in them — but "should never happen" is
    * how a `NoSuchElementException` reaches a user, so the failure is a value with the offending field named
    * in it.
    */
  def description(
      raw: adm.ClusterDescription,
      mode: dom.ControllerMode
  ): Either[KuiError, dom.ClusterDescription] =
    for {
      brokers <- NonEmptyList
        .fromList(raw.nodes.map(broker).sortBy(_.id.value))
        .toRight(
          invalid("the cluster reported no brokers", "brokers", "a cluster must have at least one broker")
        )
      controller = raw.controller.map(broker)
      described <- dom.ClusterDescription
        .from(
          kafkaClusterId = raw.kafkaClusterId,
          controller = controller,
          controllerMode = mode,
          brokers = brokers,
          authorizedOperations = raw.authorizedOperations.map(_.map(operationToken))
        )
        .leftMap(identity[KuiError])
    } yield described

  /** `Node.rack()` is nullable and, on some managed services, blank. Both are "this cluster is not
    * rack-aware", and both must be `None`: an empty string renders as a blank table cell that reads as a bug
    * in KUI rather than as a fact about the cluster.
    */
  def broker(raw: adm.KafkaNode): dom.Broker =
    dom.Broker(
      id = raw.id,
      // The host and port came from the broker's own metadata, so they are already as valid as they will
      // ever be; refusing to render a cluster because a hostname has an unusual character in it would be a
      // worse answer than showing it.
      host = Host.from(raw.host).getOrElse(Host.unsafe(raw.host)),
      port = Port.from(raw.port).getOrElse(Port.unsafe(raw.port)),
      rack = raw.rack.flatMap(text => dom.BrokerRack.from(text).toOption)
    )

  /** The wire token for a cluster-scoped ACL operation: the enum name, lowercase-hyphenated, exactly as
    * `ClusterFeature.token` is built, so that the two vocabularies read alike on one screen.
    */
  def operationToken(operation: adm.ClusterOperation): String =
    operation.toString
      .replaceAll("([a-z0-9])([A-Z])", "$1-$2")
      .toLowerCase(java.util.Locale.ROOT)

  /** KRaft when the cluster answered `describeMetadataQuorum` with a quorum, and `Unknown` otherwise.
    *
    * Never `ZooKeeper`. `libs/kafka` reports both "this is a ZooKeeper cluster" and "KUI is not allowed to
    * ask" as `Right(None)`, and announcing ZooKeeper because a call was refused would be a guess printed as a
    * fact — which is the case the domain's `ControllerMode.Unknown` exists for.
    */
  def controllerMode(quorum: Either[KuiError, Option[adm.QuorumInfo]]): dom.ControllerMode =
    quorum match {
      case Right(Some(_)) => dom.ControllerMode.KRaft
      case _ => dom.ControllerMode.Unknown
    }

  // ------------------------------------------------------------------ version

  /** `Right(None)` whenever the version could not be established, which is a legitimate answer on a managed
    * service and never a failure. A version string the domain refuses to parse is also `None` rather than a
    * `Left`: "we could not tell" is the truth in both cases, and a cluster does not stop working because KUI
    * cannot read its version number.
    */
  def version(raw: adm.BrokerVersion): Option[dom.KafkaVersion] =
    for {
      source <- versionSource(raw.source)
      // The numbers come from the value `libs/kafka` already resolved, never from re-parsing `raw.raw`.
      // For the feature-level sources those two are different strings — the numbers are `4.3` and the
      // broker's own words are `level 30` — and re-parsing the words is why a correctly detected version
      // still arrived at the browser as null.
      resolved <- raw.version
      text = raw.raw.getOrElse(resolved.render)
      parsed <- dom.KafkaVersion.resolved(resolved.major, resolved.minor, text, source).toOption
    } yield parsed

  def versionSource(raw: adm.VersionSource): Option[dom.VersionSource] = raw match {
    case adm.VersionSource.Features => Some(dom.VersionSource.MetadataVersion)
    case adm.VersionSource.FeaturesAtLeast => Some(dom.VersionSource.MetadataVersionAtLeast)
    case adm.VersionSource.InterBrokerProtocol => Some(dom.VersionSource.InterBrokerProtocol)
    case adm.VersionSource.Unknown => None
  }

  // ------------------------------------------------------------------ configuration

  def configEntry(raw: adm.ConfigEntry): dom.ConfigEntry =
    dom.ConfigEntry(
      name = raw.name,
      value = raw.value,
      source = configSource(raw.source),
      isSensitive = raw.isSensitive,
      isReadOnly = raw.isReadOnly,
      isDefault = raw.isDefault,
      documentation = raw.documentation,
      synonyms = raw.synonyms.map(synonym)
    )

  def synonym(raw: adm.ConfigSynonym): dom.ConfigSynonym =
    dom.ConfigSynonym(raw.name, raw.value, configSource(raw.source))

  /** `DynamicBrokerLoggerConfig` has no domain counterpart because nothing in M1 shows log levels; it becomes
    * `Unknown`, which renders as "source not known" rather than mislabelling a logger override as a broker
    * setting.
    */
  def configSource(raw: adm.ConfigSource): dom.ConfigSource = raw match {
    case adm.ConfigSource.DynamicBrokerConfig => dom.ConfigSource.DynamicBroker
    case adm.ConfigSource.DynamicDefaultBrokerConfig => dom.ConfigSource.DynamicDefaultBroker
    case adm.ConfigSource.DynamicTopicConfig => dom.ConfigSource.DynamicTopic
    case adm.ConfigSource.StaticBrokerConfig => dom.ConfigSource.StaticBroker
    case adm.ConfigSource.DefaultConfig => dom.ConfigSource.Default
    case adm.ConfigSource.DynamicBrokerLoggerConfig => dom.ConfigSource.Unknown
    case adm.ConfigSource.Unknown => dom.ConfigSource.Unknown
  }

  // ------------------------------------------------------------------ log directories

  /** One directory. A directory the domain refuses — a negative size, more free space than total — is
    * reported as a `Left` for that directory alone, so one impossible number costs one row rather than the
    * broker's whole disk table.
    */
  def logDir(raw: adm.LogDir): Either[KuiError, dom.LogDir] =
    dom.LogDir
      .from(
        path = dom.LogDirPath.from(raw.path).getOrElse(dom.LogDirPath.unsafe(raw.path)),
        error = raw.error.map(logDirError),
        totalBytes = raw.totalBytes,
        usableBytes = raw.usableBytes,
        replicas = raw.replicas.map(replica)
      )
      .leftMap(identity[KuiError])

  /** Every per-directory error a broker actually reports is a storage failure.
    *
    * `LogDirDescription.error()` is populated by `KafkaStorageException` and by nothing else in practice
    * (`research/kafka/admin-capabilities.md` §1, "Log dirs"), so "the broker has taken this directory
    * offline" is the honest rendering. The class name that would let this be more specific does not survive
    * `libs/kafka`'s `SkipReason`, which carries an error *code*; `Other` refuses anything that is not a Java
    * class name, so passing a wire code through it would render "unknown" and say less than `Offline` does.
    */
  def logDirError(raw: KafkaSkipReason): dom.LogDirError = raw match {
    case KafkaSkipReason.Failed(_, _) => dom.LogDirError.Offline
    case KafkaSkipReason.NotAuthorized(_) => dom.LogDirError.Offline
    case KafkaSkipReason.NotFound(_) => dom.LogDirError.Offline
    case KafkaSkipReason.Unsupported(_) => dom.LogDirError.Offline
    case KafkaSkipReason.NoLeader => dom.LogDirError.Offline
  }

  def replica(raw: adm.ReplicaInfo): dom.ReplicaInfo =
    dom.ReplicaInfo(
      partition = TopicPartition(raw.topic, raw.partition),
      sizeBytes = raw.sizeBytes,
      offsetLag = raw.offsetLag,
      isFuture = raw.isFuture
    )

  /** `libs/kafka`'s per-key skip reason becomes the domain's, case for case. */
  def skipReason(raw: KafkaSkipReason): dom.SkipReason = raw match {
    case KafkaSkipReason.NotFound(_) => dom.SkipReason.NotFound
    case KafkaSkipReason.NotAuthorized(_) => dom.SkipReason.Unauthorized
    case KafkaSkipReason.Unsupported(_) => dom.SkipReason.Unsupported
    case KafkaSkipReason.NoLeader => dom.SkipReason.Unsupported
    case failed @ KafkaSkipReason.Failed(code, _) =>
      dom.SkipReason.Failed(
        ApplicationError.Remote(code, failed.message, Nil)
      )
  }

  /** The batch answer, built through `PartialResult.from` so that a broker which is in neither map is
    * accounted for rather than silently absent (DC-D5).
    */
  def logDirsByBroker(
      requested: Set[BrokerId],
      raw: kui.kafka.BatchResult[BrokerId, List[adm.LogDir]]
  ): dom.PartialResult[BrokerId, List[dom.LogDir]] = {
    val converted = raw.values.map { (brokerId, dirs) =>
      brokerId -> dirs.map(logDir).collect { case Right(dir) => dir }
    }

    dom.PartialResult.from(
      requested = requested,
      values = converted,
      skipped = raw.skipped.map((brokerId, reason) => brokerId -> skipReason(reason))
    )
  }

  // ------------------------------------------------------------------ quorum

  def quorum(raw: adm.QuorumInfo): Either[KuiError, dom.QuorumInfo] =
    dom.QuorumInfo
      .from(
        leaderId = raw.leaderId,
        leaderEpoch = raw.leaderEpoch,
        highWatermark = raw.highWatermark,
        voters = raw.voters.map(voter),
        observers = raw.observers.map(voter)
      )
      .leftMap(identity[KuiError])

  def voter(raw: adm.QuorumVoter): dom.ReplicaState =
    dom.ReplicaState(
      replicaId = raw.replicaId,
      logEndOffset = raw.logEndOffset,
      lastFetch = timestamp(raw.lastFetchTimestamp),
      lastCaughtUp = timestamp(raw.lastCaughtUpTimestamp)
    )

  /** Kafka sends `-1` for "never", and some brokers send `0`. Neither is an instant an operator should be
    * shown as 1970.
    */
  def timestamp(raw: Option[Long]): Option[Instant] =
    raw.filter(_ > 0L).map(Instant.ofEpochMilli)

  // ------------------------------------------------------------------ features

  /** All three sets, preserved.
    *
    * `libs/kafka` knows twelve features and the cluster domain models six; the six it does not model are
    * dropped, and the one the domain has that the probe does not test (`BrokerConfigs`) lands in `unknown` by
    * construction, because `ClusterFeatures.of` puts everything undecided there. That is the correct answer —
    * KUI has established nothing about it — and it is why the mapping goes through `of` rather than
    * assembling the three sets by hand, where a forgotten feature would appear in none of them.
    */
  def features(raw: adm.ClusterFeatures): dom.ClusterFeatures =
    dom.ClusterFeatures.of(
      present = raw.present.flatMap(feature),
      absent = raw.absent.flatMap(feature),
      at = raw.probedAt
    )

  def feature(raw: adm.ClusterFeature): Option[dom.ClusterFeature] = raw match {
    case adm.ClusterFeature.IncrementalAlterConfigs => Some(dom.ClusterFeature.IncrementalAlterConfigs)
    case adm.ClusterFeature.ConfigDocumentation => Some(dom.ClusterFeature.ConfigDocumentation)
    case adm.ClusterFeature.AuthorizedOperations => Some(dom.ClusterFeature.AuthorizedOperations)
    case adm.ClusterFeature.LogDirs => Some(dom.ClusterFeature.LogDirs)
    case adm.ClusterFeature.KRaftQuorum => Some(dom.ClusterFeature.KRaftQuorum)
    case adm.ClusterFeature.AclManagement => None
    case adm.ClusterFeature.AclEdit => None
    case adm.ClusterFeature.ClientQuotas => None
    case adm.ClusterFeature.TopicDeletion => None
    case adm.ClusterFeature.ProducersAndTransactions => None
    case adm.ClusterFeature.TieredStorage => None
    case adm.ClusterFeature.NewGroupProtocol => None
  }

  private def invalid(message: String, field: String, restriction: String): KuiError =
    ApplicationError.Invalid(message, List(FieldError.of(field, restriction)))
}
