package kui.cluster.api

import java.time.Instant

import kui.cluster.application.{BrokerListRow, SnapshotFreshness}
import kui.cluster.contract.dto.ClusterProfileDto
import kui.cluster.domain.*
import kui.contracts.Section
import kui.contracts.cluster.*
import kui.kernel.ClusterId
import kui.kernel.cluster.*

/** Turning what this service knows into what a browser reads.
  *
  * ADR-041 makes this the only layer that may see both an application type and a wire type, and ADR-033 puts
  * the mapping here rather than on either side of it. The point is not ceremony: a domain type that is also a
  * DTO grows a field the first time the wire needs one, and from then on the model is shaped by JSON.
  *
  * The mapping is also the seam that absorbs a difference in *shape*. `ClusterTopology` holds a
  * `Map[BrokerId, BrokerLoad]` because that is how the domain reasons about a cluster; a browser wants a flat
  * row per broker. Neither has to move to accommodate the other.
  *
  * **Nothing here can leak a credential, and that is a property of the types rather than of this code.** The
  * only value in the domain that holds a secret is `ClusterProfile`, whose secrets are `Secret[String]`; the
  * two functions that take one ([[row]] and [[profile]]) read its shape and never its contents, and
  * everything else in this file starts from a `ClusterTopology`, which holds a `ClusterRef` and has no field
  * a secret could be in.
  */
object ClusterMapping {

  /** One dashboard row: identity from configuration, live data from the snapshot.
    *
    * The identity half is outside the section on purpose. The milestone promises that a cluster KUI cannot
    * reach still renders a row a user can click, and a row whose name lived inside the failed section would
    * have nothing to draw.
    */
  def row(
      profile: ClusterProfile,
      topology: Option[ClusterTopology],
      freshness: SnapshotFreshness,
      at: Instant
  ): ClusterRowDto =
    ClusterRowDto(
      id = profile.id,
      name = profile.label,
      readOnly = profile.readOnly,
      bootstrapServers = profile.bootstrap.value,
      security = security(profile.security),
      summary =
        SectionMapping.of(topology, freshness, at)(summary(_, freshness.scrapedAtOption.getOrElse(at)))
    )

  /** What one scrape found. The three partition counts have no source in M1 and are `None` by construction:
    * the domain models them as `Option` for exactly this reason (DEVPLAN D5).
    */
  def summary(topology: ClusterTopology, scrapedAt: Instant): ClusterSummaryDto =
    ClusterSummaryDto(
      kafkaClusterId = topology.description.kafkaClusterId,
      version = topology.version.map(_.raw),
      controllerId = topology.description.controller.map(_.id),
      controllerKind = controllerKind(topology.description.controllerMode),
      brokerCount = topology.brokerCount,
      onlinePartitionCount = topology.partitions.map(_.online),
      offlinePartitionCount = topology.partitions.map(_.offline),
      underReplicatedPartitionCount = topology.partitions.map(_.underReplicated),
      // The field is named for *usage*, so it carries what Kafka's data occupies, summed over every broker's
      // log directories. It used to carry `totalDiskBytes`, the size of the filesystem the log directories sit
      // on, so the cluster list reported the quickstart's broker - which holds about a hundred records - as
      // using 468.8 GiB of disk. How full the underlying filesystem is remains worth showing and is not shown
      // anywhere; that is a column somebody has to add, not a reason to keep answering the wrong question.
      totalDiskUsageBytes = topology.usedByKafkaBytes,
      features = topology.features.tokens.toList.sorted,
      scrapedAt = scrapedAt
    )

  /** The wire spelling of how a cluster is controlled. Lowercase words rather than the enum's own names,
    * because they are a contract a browser matches on and the enum's names are Scala's.
    */
  def controllerKind(mode: ControllerMode): String = mode match {
    case ControllerMode.KRaft => ClusterSummaryDto.KRaft
    case ControllerMode.ZooKeeper => ClusterSummaryDto.ZooKeeper
    case ControllerMode.Unknown => ClusterSummaryDto.UnknownController
  }

  /** One broker row.
    *
    * `replicaSkewPercent` is computed in the domain across the whole broker set rather than here, because a
    * skew computed one broker at a time divides by a different denominator than its neighbour and the two
    * numbers do not add up on the page they are shown on together.
    */
  def broker(row: BrokerListRow): BrokerDto =
    BrokerDto(
      id = row.broker.id,
      host = row.broker.host.value,
      port = row.broker.port.value,
      rack = row.broker.rack.map(_.value),
      isController = row.isController,
      partitionCount = None,
      leaderCount = row.leaders,
      // Every replica this broker holds, in-sync or not. Until 2026-09-04 this same number was sent as
      // `inSyncReplicaCount`, which is true only while nothing is broken: stopping one broker of three left
      // both survivors reporting their old figure as an in-sync count. Nothing in `describeCluster` or
      // `describeLogDirs` knows which replicas are in the ISR - only `describeTopics` does, and this service
      // does not sweep topics - so the field is named for what it actually holds.
      replicaCount = row.replicas,
      replicaSkewPercent = row.skewPercent,
      leaderSkewPercent = None,
      // What Kafka's own data occupies on this broker: the sum of the replica sizes its log directories
      // report. It used to be `totalBytes - usableBytes`, the filesystem's used space, which on any shared
      // disk is mostly other people's files - the quickstart's broker holds about a hundred records and that
      // subtraction read 184 GiB. The column is labelled "Disk" on a Kafka broker list, so it has to be the
      // Kafka number. `None` when the broker reported no log directories, which is what a broker older than
      // Kafka 3.3 looks like and is different from a broker holding nothing.
      diskUsageBytes = row.usedByKafkaBytes,
      segmentCount = None
    )

  def configEntry(entry: ConfigEntry): BrokerConfigEntryDto =
    BrokerConfigEntryDto(
      name = entry.name,
      value = entry.value,
      source = entry.source.token,
      isSensitive = entry.isSensitive,
      isReadOnly = entry.isReadOnly,
      documentation = entry.documentation,
      synonyms = entry.synonyms.map(_.name)
    )

  /** One log directory. The per-directory error is carried across as its own description rather than folded
    * into the section: a broker with one offline disk and three healthy ones is a good answer with a warning
    * in it, not a failure.
    */
  def logDir(brokerId: kui.kernel.BrokerId, dir: LogDir): LogDirDto =
    LogDirDto(
      brokerId = brokerId,
      path = dir.path.value,
      error = dir.error.map(_.describe),
      totalBytes = dir.totalBytes,
      usableBytes = dir.usableBytes,
      topicCount = dir.replicas.map(_.partition.topic).distinct.size,
      partitionCount = dir.replicas.size
    )

  /** A connection's *shape*: which protocol, which mechanism, whether stores were configured.
    *
    * Every branch reads a boolean or a documented wire name off the ADT. There is no branch that reads a
    * username or unwraps a `Secret`, which is what makes "no credential reaches the wire" checkable by
    * reading twenty lines rather than by auditing a call graph.
    */
  def security(security: ClusterSecurity): ClusterSecurityDto = {
    val tls = security.tlsConfig

    ClusterSecurityDto(
      protocol = security.securityProtocol,
      mechanism = security.saslMechanism.map(_.wireName),
      truststoreConfigured = tls.exists(_.truststore.isDefined),
      keystoreConfigured = tls.exists(_.keystore.isDefined)
    )
  }

  /** The profile another KUI service fetches, with every credential removed and the override map reduced to
    * its keys (CLAPI-003 explains why the values do not travel in M1).
    */
  /** The internal profile, credentials and all (ADR-046).
    *
    * This is the one mapping in the cluster service that does not redact. `ClusterRowDto` and its friends
    * still map through `security` below, which produces the four-field shape with nothing secret in it, and
    * `SecretLeakSuite` asserts that the two cannot be confused: the sentinel it plants reaches exactly one
    * response body.
    */
  def profile(profile: ClusterProfile, updatedAt: Instant): ClusterProfileDto =
    ClusterProfileDto(
      id = profile.id,
      name = profile.label,
      version = profile.version.value,
      readOnly = profile.readOnly,
      bootstrapServers = profile.bootstrap,
      security = profile.security,
      properties = profile.properties,
      admin = profile.admin,
      updatedAt = updatedAt
    )

  /** Divergence from the mean, as a percentage rounded to two decimals, or `None` when there is no mean to
    * diverge from.
    *
    * Kafbat computes this in the browser. Computing it server-side means one implementation and one rounding
    * rule for the table, a CSV export and any later alert — and `None` renders as an em dash rather than as
    * `0.00%`, which would claim a perfectly balanced cluster where there is simply nothing to measure.
    */
  def skewPercent(value: Int, mean: Double): Option[Double] =
    if mean <= 0.0d then None
    else Some(math.round((value.toDouble - mean) / mean * 100.0d * 100.0d).toDouble / 100.0d)

  /** Wraps a list that came from a live call rather than from a snapshot.
    *
    * A live call's answer is current by definition, so it is `Ok` at the instant it returned. A failure has a
    * `KuiError` in hand and goes through `Section.fromEither`, which classifies by failure case.
    */
  def liveSection[A, B](result: Either[kui.kernel.error.KuiError, A], at: Instant)(
      render: A => B
  ): Section[B] =
    Section.fromEither(result.map(render), at)

  /** The cluster ids of a registry listing, for a log line that must not carry a bootstrap string. */
  def idsOf(profiles: List[ClusterProfile]): List[ClusterId] = profiles.map(_.id)
}
