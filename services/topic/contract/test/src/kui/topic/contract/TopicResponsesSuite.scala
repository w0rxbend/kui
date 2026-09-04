package kui.topic.contract

import java.time.Instant

import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import munit.FunSuite

import kui.contracts.ErrorEnvelope.given
import kui.contracts.Section
import kui.contracts.capability.ReasonCode
import kui.contracts.paging.{PageDto, PageInfo}
import kui.contracts.topic.*
import kui.kernel.{BrokerId, ClusterId, PartitionId, TopicName}
import kui.topic.contract.dto.*

/** That each response envelope is exactly the document committed beside it, and that the failure shapes a
  * real cluster produces are among those documents rather than only the happy one.
  *
  * Cross-compiled: the same assertions run under Node, which is what makes "the browser decodes what the
  * service encodes" a fact rather than a hope.
  */
final class TopicResponsesSuite extends FunSuite {

  private val at = Instant.parse("2026-09-03T10:11:12Z")

  private val ordersRow = TopicRowDto(
    name = TopicName.unsafe("orders"),
    internal = false,
    partitionCount = 12,
    replicationFactor = Some(3),
    outOfSyncReplicas = 0,
    offlinePartitions = 0,
    messageCount = Some(1234567L),
    sizeBytes = Some(9483264L)
  )

  /** A topic with a partition that has no leader. Its message count is absent, not zero and not the sum of
    * the partitions that did answer: "empty" ends an investigation and "unknown" starts one.
    */
  private val dlqRow = TopicRowDto(
    name = TopicName.unsafe("payments.dlq"),
    internal = false,
    partitionCount = 3,
    replicationFactor = Some(3),
    outOfSyncReplicas = 2,
    offlinePartitions = 1,
    messageCount = None,
    sizeBytes = Some(41984L)
  )

  private val topics =
    TopicsResponse(
      Section.Ok(PageDto(List(ordersRow, dlqRow), PageInfo(1, 25, Some(2L), None)), at),
      incompleteTopics = 1
    )

  private val staleTopics =
    TopicsResponse(
      Section.Stale(
        PageDto(List(ordersRow), PageInfo(1, 25, Some(1L), None)),
        at,
        ReasonCode.UpstreamTimeout
      ),
      incompleteTopics = 0
    )

  private val unavailableTopics =
    TopicsResponse(
      Section.Unavailable(
        ReasonCode.UpstreamUnavailable,
        "no snapshot of prod-eu has been taken yet",
        Some(Instant.parse("2026-09-03T10:10:00Z"))
      ),
      incompleteTopics = 0
    )

  private val healthyPartition = PartitionDto(
    partition = PartitionId.unsafe(0),
    leader = Some(BrokerId.unsafe(1)),
    replicas = List(
      ReplicaDto(BrokerId.unsafe(1), leader = true, inSync = true),
      ReplicaDto(BrokerId.unsafe(2), leader = false, inSync = true)
    ),
    earliestOffset = Some(0L),
    latestOffset = Some(617283L),
    messageCount = Some(617283L),
    sizeBytes = Some(4741632L)
  )

  private val offlinePartition = PartitionDto(
    partition = PartitionId.unsafe(1),
    leader = None,
    replicas = List(ReplicaDto(BrokerId.unsafe(3), leader = false, inSync = false)),
    earliestOffset = None,
    latestOffset = None,
    messageCount = None,
    sizeBytes = Some(4741632L)
  )

  private val detail = TopicDetailResponse(
    Section.Ok(
      TopicDetailDto(
        row = ordersRow.copy(
          partitionCount = 2,
          outOfSyncReplicas = 1,
          offlinePartitions = 1,
          messageCount = None
        ),
        partitions = List(healthyPartition, offlinePartition),
        cleanupPolicy = Some("delete"),
        segmentCount = Some(24)
      ),
      at
    ),
    partitionsTruncated = false
  )

  private val config = TopicConfigResponse(
    Section.Ok(
      TopicConfigViewDto.Entries(
        List(
          TopicConfigEntryDto(
            name = "cleanup.policy",
            value = Some("delete"),
            defaultValue = Some("delete"),
            source = "default_config",
            sensitive = false,
            readOnly = false,
            documentation = None
          ),
          TopicConfigEntryDto(
            name = "retention.ms",
            value = Some("604800000"),
            defaultValue = Some("-1"),
            source = "dynamic_topic_config",
            sensitive = false,
            readOnly = false,
            documentation = Some("How long a log segment is kept before being discarded")
          )
        )
      ),
      at
    )
  )

  private val configNotPermitted = TopicConfigResponse(
    Section.Ok(
      TopicConfigViewDto.NotPermitted(
        "the cluster refused describeConfigs for topic 'orders': TopicAuthorizationException"
      ),
      at
    )
  )

  private val partitions = PartitionsResponse(Section.Ok(List(healthyPartition), at))

  private val refresh = RefreshAcceptedDto(ClusterId.unsafe("prod-eu"), at)

  private def assertGolden(name: String, document: String, encoded: Json): Unit =
    assertNoDiff(
      encoded.spaces2,
      parse(document).fold(failure => fail(s"$name is not JSON: ${failure.message}"), _.spaces2)
    )

  test("the topic list is exactly its golden document, unknown message count and all") {
    assertGolden("topics-response.json", GoldenDocuments.topicsResponse, topics.asJson)
    assertEquals(parse(GoldenDocuments.topicsResponse).flatMap(_.as[TopicsResponse]), Right(topics))
  }

  test("a stale list carries its rows, the time they were fetched and the real reason") {
    // TIMEOUT, not UPSTREAM_UNAVAILABLE. A slow cluster and a gone cluster get different remedies, and
    // M1's cluster service collapsed the two into one code (CLAPI-004 deviation 2).
    assertGolden("topics-response-stale.json", GoldenDocuments.topicsResponseStale, staleTopics.asJson)
    assertEquals(
      parse(GoldenDocuments.topicsResponseStale).flatMap(_.as[TopicsResponse]),
      Right(staleTopics)
    )
    assertEquals(staleTopics.topics.toOption.map(_.items.size), Some(1))
  }

  test("a never-scraped cluster is an unavailable section, not an empty page") {
    assertGolden(
      "topics-response-unavailable.json",
      GoldenDocuments.topicsResponseUnavailable,
      unavailableTopics.asJson
    )
    assertEquals(
      parse(GoldenDocuments.topicsResponseUnavailable).flatMap(_.as[TopicsResponse]),
      Right(unavailableTopics)
    )
    assertEquals(unavailableTopics.topics.toOption, None)
  }

  test("incompleteTopics is outside the section, so a stale page still reports it") {
    // It describes the data being shown, and the data being shown is the stale snapshot.
    val stale = staleTopics.copy(incompleteTopics = 7)

    assertEquals(stale.asJson.hcursor.get[Int]("incompleteTopics"), Right(7))
    assertEquals(stale.asJson.as[TopicsResponse], Right(stale))
  }

  test("a leaderless partition has a null leader and no count, on the row and on the partition") {
    assertGolden("topic-detail-response.json", GoldenDocuments.topicDetailResponse, detail.asJson)
    assertEquals(parse(GoldenDocuments.topicDetailResponse).flatMap(_.as[TopicDetailResponse]), Right(detail))

    val decoded = detail.topic.toOption.getOrElse(fail("the detail section should carry data"))
    assertEquals(decoded.partitions.map(_.leader), List(Some(BrokerId.unsafe(1)), None))
    assertEquals(decoded.partitions.map(_.messageCount), List(Some(617283L), None))
    assertEquals(decoded.row.messageCount, None)
  }

  test("partitionsTruncated is sent, not derived from the number of partitions") {
    // A topic with exactly the embedded limit is not truncated, and a reader deriving the flag from the
    // list's length would say that it was.
    val truncated = detail.copy(partitionsTruncated = true)

    assertEquals(truncated.asJson.hcursor.get[Boolean]("partitionsTruncated"), Right(true))
    assertEquals(truncated.asJson.as[TopicDetailResponse], Right(truncated))
    assertEquals(TopicDetailResponse.EmbeddedPartitionLimit, 500)
  }

  test("a settings view carries its entries") {
    assertGolden("topic-config-response.json", GoldenDocuments.topicConfigResponse, config.asJson)
    assertEquals(parse(GoldenDocuments.topicConfigResponse).flatMap(_.as[TopicConfigResponse]), Right(config))
  }

  test("a caller who may not read the settings gets a not_permitted view, not an empty table") {
    assertGolden(
      "topic-config-not-permitted.json",
      GoldenDocuments.topicConfigNotPermitted,
      configNotPermitted.asJson
    )
    assertEquals(
      parse(GoldenDocuments.topicConfigNotPermitted).flatMap(_.as[TopicConfigResponse]),
      Right(configNotPermitted)
    )

    // The distinction the type exists for: an empty entries view and a refusal are different documents.
    val empty = TopicConfigResponse(Section.Ok(TopicConfigViewDto.Entries(Nil), at))
    assertNotEquals(empty.asJson, configNotPermitted.asJson)
    assertEquals(empty.config.toOption.map(_.status), Some("entries"))
    assertEquals(configNotPermitted.config.toOption.map(_.status), Some("not_permitted"))
  }

  test("an unknown settings status is a decode failure, not an empty table") {
    val unknown = Json.obj(
      "config" -> Json.obj(
        "status" -> Json.fromString("ok"),
        "data" -> Json.obj("status" -> Json.fromString("someday")),
        "fetchedAt" -> at.asJson
      )
    )

    assert(unknown.as[TopicConfigResponse].isLeft, unknown.as[TopicConfigResponse].toString)
  }

  test("a sensitive settings key never carries its value, whatever the producer put in the field") {
    // Enforced by `TopicConfigEntryDto`'s own encoder, asserted here on the text because this is the
    // document that leaves the process.
    val secret = TopicConfigResponse(
      Section.Ok(
        TopicConfigViewDto.Entries(
          List(
            TopicConfigEntryDto(
              name = "ssl.key.password",
              value = Some("SENTINEL-c0ffee"),
              defaultValue = None,
              source = "dynamic_topic_config",
              sensitive = true,
              readOnly = true,
              documentation = None
            )
          )
        ),
        at
      )
    )

    assert(!secret.asJson.noSpaces.contains("SENTINEL-c0ffee"), secret.asJson.noSpaces)
  }

  test("every partition of a topic is one section, so a failed read is not an empty table") {
    assertGolden("partitions-response.json", GoldenDocuments.partitionsResponse, partitions.asJson)
    assertEquals(
      parse(GoldenDocuments.partitionsResponse).flatMap(_.as[PartitionsResponse]),
      Right(partitions)
    )
  }

  test("a refresh acceptance carries the time the request was taken") {
    assertGolden("refresh-accepted.json", GoldenDocuments.refreshAccepted, refresh.asJson)
    assertEquals(parse(GoldenDocuments.refreshAccepted).flatMap(_.as[RefreshAcceptedDto]), Right(refresh))
  }

  test("every response round-trips") {
    assertEquals(topics.asJson.as[TopicsResponse], Right(topics))
    assertEquals(staleTopics.asJson.as[TopicsResponse], Right(staleTopics))
    assertEquals(unavailableTopics.asJson.as[TopicsResponse], Right(unavailableTopics))
    assertEquals(detail.asJson.as[TopicDetailResponse], Right(detail))
    assertEquals(config.asJson.as[TopicConfigResponse], Right(config))
    assertEquals(configNotPermitted.asJson.as[TopicConfigResponse], Right(configNotPermitted))
    assertEquals(partitions.asJson.as[PartitionsResponse], Right(partitions))
    assertEquals(refresh.asJson.as[RefreshAcceptedDto], Right(refresh))
  }

  test("the page total is what the producer counted, and pageCount is derived from it") {
    // The reference product's defect, at the wire level: it computes its page count before its
    // internal-topic filter, so hiding internal topics leaves "Page 1 of 3" over one page of rows.
    // `PageInfo` has no pageCount field to disagree with the total (`research/kafbat/api-analysis.md` §3.3).
    val page = topics.topics.toOption.getOrElse(fail("the list section should carry a page")).page

    assertEquals(page.totalItems, Some(2L))
    assertEquals(page.pageCount, Some(1))
    assertEquals(topics.asJson.hcursor.downField("topics").downField("data").downField("page").keys.map(_.toList),
      Some(List("page", "pageSize", "totalItems", "pageCount", "nextPageToken"))
    )
  }
}
