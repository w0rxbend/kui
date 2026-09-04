package kui.gateway.contract

import java.time.Instant

import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import munit.FunSuite

import kui.contracts.Section
import kui.contracts.capability.ReasonCode
import kui.contracts.topic.{PartitionDto, ReplicaDto, TopicDetailDto, TopicRowDto}
import kui.gateway.contract.dto.TopicOverviewDto
import kui.kernel.{BrokerId, PartitionId, TopicName}

/** That the topic page's document is exactly what is committed, and that the browser decodes it.
  *
  * Cross-compiled deliberately. M1's second integration defect was a browser and a server disagreeing about
  * an aggregation's shape, and the browser's half was never run against the server's document. These
  * assertions run on the JVM and under Node, over one committed file.
  */
final class TopicOverviewDtoSuite extends FunSuite {

  private val at = Instant.parse("2026-09-03T10:11:12Z")
  private val generatedAt = Instant.parse("2026-09-03T10:11:13Z")

  private val detail = TopicDetailDto(
    row = TopicRowDto(
      name = TopicName.unsafe("orders"),
      internal = false,
      partitionCount = 1,
      replicationFactor = Some(3),
      outOfSyncReplicas = 0,
      offlinePartitions = 0,
      messageCount = Some(617283L),
      sizeBytes = Some(4741632L)
    ),
    partitions = List(
      PartitionDto(
        partition = PartitionId.unsafe(0),
        leader = Some(BrokerId.unsafe(1)),
        replicas = List(ReplicaDto(BrokerId.unsafe(1), leader = true, inSync = true)),
        earliestOffset = Some(0L),
        latestOffset = Some(617283L),
        messageCount = Some(617283L),
        sizeBytes = Some(4741632L)
      )
    ),
    cleanupPolicy = Some("delete"),
    segmentCount = Some(24)
  )

  /** What an M2 deployment really answers: one filled section, four saying there is no such service. */
  private val document = TopicOverviewDto(
    topic = Section.Ok(detail, at),
    consumerGroups = Section.NotConfigured,
    connectors = Section.NotConfigured,
    acls = Section.NotConfigured,
    schemas = Section.NotConfigured,
    generatedAt = generatedAt
  )

  test("theGoldenDocumentDecodesOnBothPlatforms") {
    assertNoDiff(
      document.asJson.spaces2,
      parse(GoldenDocuments.topicOverview).fold(failure => fail(failure.message), _.spaces2)
    )
    assertEquals(parse(GoldenDocuments.topicOverview).flatMap(_.as[TopicOverviewDto]), Right(document))
  }

  test("everySectionWhoseServiceIsAbsentIsNotConfigured") {
    // By name, so that a section quietly switched to `unavailable` fails here rather than becoming four
    // permanent red panels on every topic page.
    assertEquals(
      TopicOverviewDto.statuses(document),
      Map(
        "topic" -> "ok",
        "consumerGroups" -> "not_configured",
        "connectors" -> "not_configured",
        "acls" -> "not_configured",
        "schemas" -> "not_configured"
      )
    )
  }

  test("the five section names are the tabs, in order") {
    // Three things key on these names — the aggregation's fillable set, a span attribute and a metric
    // label — so a rename is a change to a dashboard, not only to a field.
    assertEquals(
      TopicOverviewDto.sections,
      List("topic", "consumerGroups", "connectors", "acls", "schemas")
    )
    assertEquals(TopicOverviewDto.statuses(document).keySet, TopicOverviewDto.sections.toSet)
  }

  test("a document with every section failing still round-trips") {
    // The "everything is down except the gateway" case. It is a 200 that still tells the user which topic
    // they are looking at, which is more than a 503 does.
    val allDown = TopicOverviewDto(
      topic = Section.Unavailable(ReasonCode.UpstreamUnavailable, "kui-topic: connection refused", Some(at)),
      consumerGroups = Section.Unavailable(ReasonCode.CircuitOpen, "breaker open", Some(at)),
      connectors = Section.Forbidden,
      acls = Section.Stale(List(Json.obj("principal" -> Json.fromString("User:kui"))), at, ReasonCode.UpstreamTimeout),
      schemas = Section.NotConfigured,
      generatedAt = generatedAt
    )

    assertEquals(allDown.asJson.as[TopicOverviewDto], Right(allDown))
    assertEquals(
      TopicOverviewDto.statuses(allDown).values.toSet,
      Set("unavailable", "forbidden", "stale", "not_configured")
    )
  }

  test("a stale topic section keeps its data, its time and its reason through a round trip") {
    val stale = document.copy(topic = Section.Stale(detail, at, ReasonCode.UpstreamTimeout))

    assertEquals(stale.asJson.as[TopicOverviewDto], Right(stale))
    assertEquals(stale.topic.toOption.map(_.row.name), Some(TopicName.unsafe("orders")))
  }

  test("a missing section is a decode failure, not a section that defaults to empty") {
    // The failure M1 shipped, read from the other side: a browser that defaulted a missing field to nothing
    // rendered "no rows" with no error anywhere. Every section here is required.
    val truncated = parse(GoldenDocuments.topicOverview)
      .map(_.hcursor.downField("consumerGroups").delete.top.getOrElse(Json.Null))
      .getOrElse(fail("the golden document must parse"))

    assert(truncated.as[TopicOverviewDto].isLeft, truncated.as[TopicOverviewDto].toString)
  }
}
