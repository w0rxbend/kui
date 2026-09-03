package kui.ui.clusters.dashboard

import java.time.Instant

import kui.cluster.contract.dto.ClustersResponse
import kui.contracts.Section
import kui.contracts.capability.ReasonCode
import kui.contracts.cluster.{ClusterRowDto, ClusterSecurityDto, ClusterSummaryDto}
import kui.kernel.{BrokerId, ClusterId}

/** Responses shaped like the ones the gateway sends, built by hand so that a test says what it is about.
  *
  * Every figure here is invented. The three partition counts are `None` on purpose and not by omission: no
  * Kafka call produces them in M1, and a fixture that filled them would let a screen pass a test it would
  * fail against a real cluster.
  */
object ClusterFixtures {

  val scrapedAt: Instant = Instant.parse("2026-09-03T12:00:00Z")

  def clusterId(raw: String): ClusterId =
    ClusterId.from(raw).getOrElse(throw new IllegalArgumentException(s"'$raw' is not a cluster id"))

  def brokerId(raw: Int): BrokerId =
    BrokerId.from(raw).getOrElse(throw new IllegalArgumentException(s"$raw is not a broker id"))

  private val security = ClusterSecurityDto("PLAINTEXT", None, truststoreConfigured = false, keystoreConfigured = false)

  def summary(
      brokers: Int = 3,
      version: Option[String] = Some("4.0.0"),
      controller: Option[BrokerId] = Some(brokerId(1)),
      disk: Option[Long] = Some(1024L * 1024 * 1024)
  ): ClusterSummaryDto =
    ClusterSummaryDto(
      kafkaClusterId = None,
      version = version,
      controllerId = controller,
      controllerKind = ClusterSummaryDto.KRaft,
      brokerCount = brokers,
      onlinePartitionCount = None,
      offlinePartitionCount = None,
      underReplicatedPartitionCount = None,
      totalDiskUsageBytes = disk,
      features = Nil,
      scrapedAt = scrapedAt
    )

  def row(
      id: String,
      name: String = "",
      readOnly: Boolean = false,
      section: Section[ClusterSummaryDto] = Section.Ok(summary(), scrapedAt)
  ): ClusterRowDto =
    ClusterRowDto(
      id = clusterId(id),
      name = if name.isEmpty then id else name,
      readOnly = readOnly,
      bootstrapServers = s"$id.example:9092",
      security = security,
      summary = section
    )

  def unavailable(message: String): Section[ClusterSummaryDto] =
    Section.Unavailable(ReasonCode.UpstreamUnavailable, message, Some(scrapedAt))

  def stale(reason: ReasonCode = ReasonCode.UpstreamTimeout): Section[ClusterSummaryDto] =
    Section.Stale(summary(), scrapedAt, reason)

  def response(rows: ClusterRowDto*): ClustersResponse =
    ClustersResponse(rows.toList, generatedAt = scrapedAt)
}
