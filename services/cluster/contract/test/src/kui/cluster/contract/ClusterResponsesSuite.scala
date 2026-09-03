package kui.cluster.contract

import java.time.Instant

import io.circe.parser.parse
import io.circe.syntax.*
import munit.FunSuite

import kui.cluster.contract.dto.*
import kui.contracts.Section
import kui.contracts.capability.ReasonCode
import kui.contracts.cluster.*
import kui.kernel.{BrokerId, ClusterId, KafkaClusterId}

/** That each response envelope is exactly the document committed beside it, and that the failure shapes a
  * real cluster produces are among those documents rather than only the happy one.
  *
  * Cross-compiled: the same assertions run under Node, which is what makes "the browser decodes what the
  * server encodes" a fact rather than a hope.
  */
final class ClusterResponsesSuite extends FunSuite {

  private val at = Instant.parse("2026-09-03T10:11:12Z")

  private val healthyRow = ClusterRowDto(
    id = ClusterId.unsafe("prod-eu"),
    name = "Production EU",
    readOnly = false,
    bootstrapServers = "broker-1.example.com:9093,broker-2.example.com:9093",
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
        features = List("DESCRIBE_LOG_DIRS", "DESCRIBE_QUORUM"),
        scrapedAt = at
      ),
      at
    )
  )

  private val deadRow = ClusterRowDto(
    id = ClusterId.unsafe("dead-cluster"),
    name = "Decommissioned",
    readOnly = true,
    bootstrapServers = "gone.example.com:9092",
    security = ClusterSecurityDto("PLAINTEXT", None, false, false),
    summary = Section.Unavailable(
      ReasonCode.UpstreamUnavailable,
      "connection refused",
      Some(Instant.parse("2026-09-03T10:10:00Z"))
    )
  )

  private val clusters = ClustersResponse(List(healthyRow, deadRow), Instant.parse("2026-09-03T10:11:13Z"))

  private val brokers = BrokersResponse(
    Section.Stale(
      List(
        BrokerDto(
          id = BrokerId.unsafe(1),
          host = "broker-1.example.com",
          port = 9093,
          rack = Some("eu-west-1a"),
          isController = true,
          partitionCount = None,
          leaderCount = None,
          inSyncReplicaCount = Some(42),
          replicaSkewPercent = Some(3.5d),
          leaderSkewPercent = None,
          diskUsageBytes = Some(183251937970L),
          segmentCount = Some(128)
        )
      ),
      at,
      ReasonCode.UpstreamTimeout
    )
  )

  private val configs = BrokerConfigsResponse(
    Section.Ok(
      List(
        BrokerConfigEntryDto(
          name = "log.retention.hours",
          value = Some("168"),
          source = "STATIC_BROKER_CONFIG",
          isSensitive = false,
          isReadOnly = true,
          documentation = None,
          synonyms = List("log.retention.hours")
        ),
        BrokerConfigEntryDto(
          name = "listener.name.internal.ssl.key.password",
          value = None,
          source = "STATIC_BROKER_CONFIG",
          isSensitive = true,
          isReadOnly = true,
          documentation = None,
          synonyms = Nil
        )
      ),
      at
    )
  )

  private val logDirs = LogDirsResponse(
    Section.Unavailable(
      ReasonCode.UpstreamAuth,
      "the cluster refused describeLogDirs: ClusterAuthorizationException",
      Some(at)
    )
  )

  private val refresh = RefreshAcceptedDto(ClusterId.unsafe("prod-eu"), at)

  private def assertGolden(name: String, document: String, encoded: io.circe.Json): Unit =
    assertNoDiff(
      encoded.spaces2,
      parse(document).fold(failure => fail(s"$name is not JSON: ${failure.message}"), _.spaces2)
    )

  test("the clusters response is exactly its golden document, dead row and all") {
    assertGolden("clusters-response.json", GoldenDocuments.clustersResponse, clusters.asJson)
    assertEquals(parse(GoldenDocuments.clustersResponse).flatMap(_.as[ClustersResponse]), Right(clusters))
  }

  test("a stale brokers section carries the data, the time it was fetched and why it is old") {
    assertGolden("brokers-response.json", GoldenDocuments.brokersResponse, brokers.asJson)
    assertEquals(parse(GoldenDocuments.brokersResponse).flatMap(_.as[BrokersResponse]), Right(brokers))
    assertEquals(brokers.brokers.toOption.map(_.size), Some(1))
  }

  test("a sensitive broker setting has no value and says the broker withheld it") {
    assertGolden("broker-configs-response.json", GoldenDocuments.brokerConfigsResponse, configs.asJson)
    assertEquals(
      parse(GoldenDocuments.brokerConfigsResponse).flatMap(_.as[BrokerConfigsResponse]),
      Right(configs)
    )

    val withheld = configs.configs.toOption.toList.flatten.filter(_.isSensitive)
    assertEquals(withheld.map(_.value), List(None))
  }

  test("an unavailable log-dirs section is a 200 with a reason, not an empty list") {
    // An empty list would read as "this broker has no disks", which is the one thing it never means.
    assertGolden("log-dirs-response.json", GoldenDocuments.logDirsResponse, logDirs.asJson)
    assertEquals(parse(GoldenDocuments.logDirsResponse).flatMap(_.as[LogDirsResponse]), Right(logDirs))
    assertEquals(logDirs.logDirs.toOption, None)
  }

  test("a refresh acceptance carries the time the request was taken") {
    assertGolden("refresh-accepted.json", GoldenDocuments.refreshAccepted, refresh.asJson)
    assertEquals(parse(GoldenDocuments.refreshAccepted).flatMap(_.as[RefreshAcceptedDto]), Right(refresh))
  }

  test("every response round-trips") {
    assertEquals(clusters.asJson.as[ClustersResponse], Right(clusters))
    val detail = ClusterDetailResponse(deadRow)
    assertEquals(detail.asJson.as[ClusterDetailResponse], Right(detail))
    assertEquals(brokers.asJson.as[BrokersResponse], Right(brokers))
    assertEquals(configs.asJson.as[BrokerConfigsResponse], Right(configs))
    assertEquals(logDirs.asJson.as[LogDirsResponse], Right(logDirs))
    assertEquals(refresh.asJson.as[RefreshAcceptedDto], Right(refresh))
  }

  test("a cluster list with an unreachable row is still a full list") {
    // The milestone's dashboard criterion, at the wire level: the failing row is present, named and
    // linkable, and only its summary is missing.
    val decoded = parse(GoldenDocuments.clustersResponse).flatMap(_.as[ClustersResponse])

    assertEquals(decoded.map(_.items.map(_.id.value)), Right(List("prod-eu", "dead-cluster")))
    assertEquals(decoded.map(_.items.map(_.summary.status)), Right(List("ok", "unavailable")))
  }
}
