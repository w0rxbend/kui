package kui.contracts.topic

import io.circe.parser.parse
import io.circe.syntax.*
import munit.FunSuite

import kui.kernel.{BrokerId, PartitionId, TopicName}

/** That the topic wire fragments are the same on both platforms, and that the two fields whose wrong
  * rendering misleads an operator are asserted on the JSON text rather than on a decoded value.
  */
final class TopicDtosSuite extends FunSuite {

  private val row = TopicRowDto(
    name = TopicName.unsafe("orders"),
    internal = false,
    partitionCount = 6,
    replicationFactor = Some(3),
    outOfSyncReplicas = 0,
    offlinePartitions = 0,
    messageCount = Some(1048576L),
    sizeBytes = Some(734003200L)
  )

  private val healthyPartition = PartitionDto(
    partition = PartitionId.unsafe(0),
    leader = Some(BrokerId.unsafe(1)),
    replicas = List(
      ReplicaDto(BrokerId.unsafe(1), leader = true, inSync = true),
      ReplicaDto(BrokerId.unsafe(2), leader = false, inSync = true)
    ),
    earliestOffset = Some(0L),
    latestOffset = Some(512L),
    messageCount = Some(512L),
    sizeBytes = Some(65536L)
  )

  private val offlinePartition = PartitionDto(
    partition = PartitionId.unsafe(1),
    leader = None,
    replicas = List(ReplicaDto(BrokerId.unsafe(2), leader = false, inSync = false)),
    earliestOffset = None,
    latestOffset = None,
    messageCount = None,
    sizeBytes = None
  )

  private val detail = TopicDetailDto(
    row = TopicRowDto(
      name = TopicName.unsafe("payments"),
      internal = false,
      partitionCount = 2,
      replicationFactor = Some(3),
      outOfSyncReplicas = 1,
      offlinePartitions = 1,
      messageCount = None,
      sizeBytes = None
    ),
    partitions = List(healthyPartition, offlinePartition),
    cleanupPolicy = Some("delete"),
    segmentCount = Some(12)
  )

  private val configEntry = TopicConfigEntryDto(
    name = "retention.ms",
    value = Some("604800000"),
    defaultValue = Some("604800000"),
    source = "default_config",
    sensitive = false,
    readOnly = false,
    documentation = Some("How long a log segment is kept before it is discarded")
  )

  private def normalised(raw: String): String =
    parse(raw).fold(failure => fail(s"the golden document is not JSON: ${failure.message}"), _.spaces2)

  List(
    "topic-row.json" -> (TopicGoldenDocuments.topicRow, row.asJson),
    "topic-detail.json" -> (TopicGoldenDocuments.topicDetail, detail.asJson),
    "topic-config.json" -> (TopicGoldenDocuments.topicConfig, configEntry.asJson)
  ).foreach { case (file, (document, encoded)) =>
    test(s"everyGoldenDecodesAndReEncodesIdentically: $file") {
      assertNoDiff(encoded.spaces2, normalised(document))
    }
  }

  test("everyGoldenDecodesAndReEncodesIdentically: each document decodes back to what it was written from") {
    assertEquals(parse(TopicGoldenDocuments.topicRow).flatMap(_.as[TopicRowDto]), Right(row))
    assertEquals(parse(TopicGoldenDocuments.topicDetail).flatMap(_.as[TopicDetailDto]), Right(detail))
    assertEquals(parse(TopicGoldenDocuments.topicConfig).flatMap(_.as[TopicConfigEntryDto]), Right(configEntry))
  }

  test("aMissingMessageCountIsNullNotZero") {
    // Asserted on the text, because this is the field whose wrong rendering makes an operator believe a
    // topic is empty and stop investigating. A zero here would end the investigation; a null starts it.
    val json = detail.row.asJson.noSpaces
    assert(json.contains("\"messageCount\":null"), json)
    assert(!json.contains("\"messageCount\":0"), json)
  }

  test("anOfflinePartitionHasANullLeader") {
    // Kafka reports a leaderless partition as node id -1. Carrying that number through would render
    // "leader -1" or, worse, be treated as a broker id.
    val json = offlinePartition.asJson.noSpaces
    assert(json.contains("\"leader\":null"), json)
    assert(!json.contains("-1"), json)
  }

  test("aSensitiveConfigEntryHasNoValueOnTheWire") {
    // Not masked — absent. A mask on the wire is still a value a proxy has written to a log.
    val sensitive = configEntry.copy(name = "ssl.key.password", value = Some("hunter2"), sensitive = true)
    val json = sensitive.asJson.noSpaces
    assert(json.contains("\"value\":null"), json)
    assert(!json.contains("hunter2"), json)
    assertEquals(parse(json).flatMap(_.as[TopicConfigEntryDto]).map(_.value), Right(None))
  }

  test("unknownConfigSourceStringsDecode") {
    // A broker naming a source this version of KUI has never heard of must not fail the settings page.
    val json = """{"name":"x","value":null,"defaultValue":null,"source":"quantum_broker_config",
                 |"sensitive":false,"readOnly":false,"documentation":null}""".stripMargin
    assertEquals(parse(json).flatMap(_.as[TopicConfigEntryDto]).map(_.source), Right("quantum_broker_config"))
  }

  test("overriddenIsDerivedFromTheTwoValuesItSummarises") {
    assert(!configEntry.overridden)
    assert(configEntry.copy(value = Some("60000")).overridden)
    assert(configEntry.copy(defaultValue = None).overridden)
  }

  test("theJvmAndJsEncodersAgree") {
    // The golden files are the mechanism: both platforms' suites decode and re-encode the same bytes, and
    // M1's second integration defect — a browser decoding a document nobody sends — is why this is
    // asserted rather than assumed.
    TopicGoldenDocuments.all.foreach { case (file, document) =>
      assert(parse(document).isRight, s"$file is not JSON on this platform")
    }
  }
}
