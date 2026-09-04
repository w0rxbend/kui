package kui.gateway.contract

import java.time.Instant

import io.circe.parser.parse
import io.circe.syntax.*
import munit.FunSuite

import kui.contracts.Section
import kui.contracts.capability.{CapabilityState, DegradedReason, ReasonCode}
import kui.contracts.cluster.{ClusterRowDto, ClusterSecurityDto, ClusterSummaryDto}
import kui.gateway.contract.dto.{
  ClusterOverviewDto,
  ClusterOverviewRow,
  GroupTotalsDto,
  TopicMagnitudeDto,
  TopicTotalsDto
}
import kui.kernel.group.GroupState
import kui.kernel.{BrokerId, ClusterId, KafkaClusterId, TopicName}

/** That the dashboard document says both of the things it has to say at once.
  *
  * The golden document is the degraded case rather than the happy one on purpose: it is the shape the
  * milestone's exit criteria are written about, and the shape a suite is least likely to produce by accident.
  */
final class ClusterOverviewSuite extends FunSuite {

  private val at = Instant.parse("2026-09-03T10:11:12Z")

  private val healthy = ClusterOverviewRow(
    ClusterRowDto(
      id = ClusterId.unsafe("prod-eu"),
      name = "Production EU",
      readOnly = false,
      bootstrapServers = "broker-1.example.com:9093",
      security = ClusterSecurityDto("SASL_SSL", Some("SCRAM-SHA-512"), true, false),
      summary = Section.Ok(
        ClusterSummaryDto(
          kafkaClusterId = Some(KafkaClusterId.unsafe("MkU3OEVBNTcwNTJENDM2Qk")),
          version = Some("4.0.0"),
          controllerId = Some(BrokerId.unsafe(1)),
          controllerKind = ClusterSummaryDto.KRaft,
          brokerCount = 3,
          onlinePartitionCount = None,
          offlinePartitionCount = None,
          underReplicatedPartitionCount = None,
          totalDiskUsageBytes = Some(549755813888L),
          features = Nil,
          scrapedAt = at
        ),
        at
      )
    ),
    CapabilityState.Available,
    // The two totals the dashboard draws, both `Ok`, both on the healthy row.
    topics = Section.Ok(
      TopicTotalsDto.of(
        List(
          TopicMagnitudeDto(TopicName.unsafe("orders.v1"), 6),
          TopicMagnitudeDto(TopicName.unsafe("payments.v1"), 3)
        ),
        topicCount = 2L
      ),
      at
    ),
    consumerGroups = Section.Ok(
      GroupTotalsDto.of(
        List(GroupState.Stable, GroupState.Empty),
        List(Some(9L), Some(0L)),
        groupCount = 2L
      ),
      at
    )
  )

  private val dead = ClusterOverviewRow(
    ClusterRowDto(
      id = ClusterId.unsafe("dead"),
      name = "Decommissioned",
      readOnly = true,
      bootstrapServers = "gone.example.com:9092",
      security = ClusterSecurityDto("PLAINTEXT", None, false, false),
      summary = Section.Unavailable(ReasonCode.UpstreamUnavailable, "connection refused", Some(at))
    ),
    CapabilityState.Degraded(
      DegradedReason(ReasonCode.Starting, "this cluster has not been scraped yet", None, None)
    ),
    // The dead row's three sections disagree with one another on purpose, which is the whole point of
    // keeping them apart: its Kafka cluster is unreachable, its topic totals failed for that reason, and
    // this deployment has no consumer service at all — three facts with three different remedies.
    topics = Section.Unavailable(ReasonCode.UpstreamUnavailable, "connection refused", Some(at)),
    consumerGroups = Section.NotConfigured
  )

  private val overview = ClusterOverviewDto(
    Section.Stale(List(healthy, dead), at, ReasonCode.UpstreamUnavailable),
    Instant.parse("2026-09-03T10:11:13Z")
  )

  test("the golden document is exactly what the encoder writes") {
    assertNoDiff(
      overview.asJson.spaces2,
      parse(GoldenDocuments.clusterOverview).fold(failure => fail(failure.message), _.spaces2)
    )
  }

  test("the golden document decodes back to what it was written from") {
    assertEquals(parse(GoldenDocuments.clusterOverview).flatMap(_.as[ClusterOverviewDto]), Right(overview))
    assertEquals(overview.asJson.as[ClusterOverviewDto], Right(overview))
  }

  test("the two levels of failure are readable independently") {
    // Which of the two happened decides what a screen says and what an operator does next: "KUI's cluster
    // service is down" and "this Kafka cluster is unreachable" have different fixes.
    val decoded = parse(GoldenDocuments.clusterOverview)
      .flatMap(_.as[ClusterOverviewDto])
      .fold(failure => fail(failure.getMessage), identity)

    assertEquals(decoded.clusters.status, "stale")
    assertEquals(
      decoded.clusters.toOption.getOrElse(Nil).map(_.cluster.summary.status),
      List("ok", "unavailable")
    )
  }

  test("a stale outer section carries the time its rows were fetched, not the time of this response") {
    // A stale marker whose timestamp is the current time tells a reader nothing about how old the data is,
    // which is the only question the marker exists to answer.
    overview.clusters match {
      case Section.Stale(_, fetchedAt, _) => assert(fetchedAt.isBefore(overview.generatedAt), fetchedAt.toString)
      case other => fail(s"expected a stale section: $other")
    }
  }

  test("an unavailable row is still fully identified") {
    // "Remains clickable" as a property of the document: id, name and address survive the failure of the
    // section that holds the live data.
    assertEquals(dead.cluster.id.value, "dead")
    assertEquals(dead.cluster.name, "Decommissioned")
    assertEquals(dead.cluster.bootstrapServers, "gone.example.com:9092")
    assertEquals(dead.cluster.summary.toOption, None)
  }

  test("the counts a span reports are derived rather than recomputed by each caller") {
    assertEquals(ClusterOverviewDto.totalRows(overview), 2)
    assertEquals(ClusterOverviewDto.unavailableRows(overview), 1)
  }

  test("no credential appears anywhere in the document") {
    val rendered = overview.asJson.noSpaces

    List("password", "jaas", "secret", "username")
      .foreach(word => assert(!rendered.toLowerCase.contains(word), s"'$word' in $rendered"))
  }
}
