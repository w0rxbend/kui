package kui.contracts.cluster

import java.time.Instant

import io.circe.parser.parse
import io.circe.syntax.*
import munit.ScalaCheckSuite
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll

import kui.contracts.Section
import kui.contracts.capability.ReasonCode
import kui.kernel.{BrokerId, ClusterId, KafkaClusterId}

/** That the cluster wire shapes are the same on both platforms, decode what a real cluster produces, and
  * carry no secret.
  *
  * The suite is cross-compiled: it runs unchanged under Node, which is the only way to be sure the browser
  * decodes what the server encodes rather than something that merely looks similar.
  */
final class ClusterDtosSuite extends ScalaCheckSuite {

  private val at = Instant.parse("2026-09-03T10:11:12Z")

  private val security = ClusterSecurityDto(
    protocol = "SASL_SSL",
    mechanism = Some("SCRAM-SHA-512"),
    truststoreConfigured = true,
    keystoreConfigured = false
  )

  private val summary = ClusterSummaryDto(
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
  )

  private val row = ClusterRowDto(
    id = ClusterId.unsafe("prod-eu"),
    name = "Production EU",
    readOnly = false,
    bootstrapServers = "broker-1.example.com:9093,broker-2.example.com:9093",
    security = security,
    summary = Section.Ok(summary, at)
  )

  private val broker = BrokerDto(
    id = BrokerId.unsafe(1),
    host = "broker-1.example.com",
    port = 9093,
    rack = Some("eu-west-1a"),
    isController = true,
    partitionCount = None,
    leaderCount = None,
    replicaCount = Some(42),
    replicaSkewPercent = Some(3.5d),
    leaderSkewPercent = None,
    diskUsageBytes = Some(183251937970L),
    segmentCount = Some(128)
  )

  private val configEntry = BrokerConfigEntryDto(
    name = "log.retention.hours",
    value = Some("168"),
    source = "STATIC_BROKER_CONFIG",
    isSensitive = false,
    isReadOnly = true,
    documentation = Some("The number of hours to keep a log file before deleting it"),
    synonyms = List("log.retention.hours", "log.retention.ms")
  )

  private val logDir = LogDirDto(
    brokerId = BrokerId.unsafe(1),
    path = "/var/lib/kafka/data",
    error = None,
    totalBytes = Some(549755813888L),
    usableBytes = Some(366503875925L),
    topicCount = 12,
    partitionCount = 48
  )

  private def normalised(raw: String): String =
    parse(raw).fold(failure => fail(s"the golden document is not JSON: ${failure.message}"), _.spaces2)

  // `everyGoldenDocumentDecodes`, one case per file: a failure names the file it came from.
  List(
    "cluster-security.json" -> (ClusterGoldenDocuments.clusterSecurity, security.asJson),
    "cluster-summary.json" -> (ClusterGoldenDocuments.clusterSummary, summary.asJson),
    "cluster-row.json" -> (ClusterGoldenDocuments.clusterRow, row.asJson),
    "broker.json" -> (ClusterGoldenDocuments.broker, broker.asJson),
    "broker-config-entry.json" -> (ClusterGoldenDocuments.brokerConfigEntry, configEntry.asJson),
    "log-dir.json" -> (ClusterGoldenDocuments.logDir, logDir.asJson)
  ).foreach { case (file, (document, encoded)) =>
    test(s"everyGoldenDocumentDecodes: $file is exactly what the encoder writes") {
      assertNoDiff(encoded.spaces2, normalised(document))
    }
  }

  test("everyGoldenDocumentDecodes: each document decodes back to the value it was written from") {
    assertEquals(
      parse(ClusterGoldenDocuments.clusterSecurity).flatMap(_.as[ClusterSecurityDto]),
      Right(security)
    )
    assertEquals(
      parse(ClusterGoldenDocuments.clusterSummary).flatMap(_.as[ClusterSummaryDto]),
      Right(summary)
    )
    assertEquals(parse(ClusterGoldenDocuments.clusterRow).flatMap(_.as[ClusterRowDto]), Right(row))
    assertEquals(parse(ClusterGoldenDocuments.broker).flatMap(_.as[BrokerDto]), Right(broker))
    assertEquals(
      parse(ClusterGoldenDocuments.brokerConfigEntry).flatMap(_.as[BrokerConfigEntryDto]),
      Right(configEntry)
    )
    assertEquals(parse(ClusterGoldenDocuments.logDir).flatMap(_.as[LogDirDto]), Right(logDir))
  }

  private val instants: Gen[Instant] =
    Gen.choose(0L, 4102444800000L).map(millis => Instant.ofEpochMilli(millis))

  private val securities: Gen[ClusterSecurityDto] = for {
    protocol <- Gen.oneOf("PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL")
    mechanism <- Gen.option(Gen.oneOf("PLAIN", "SCRAM-SHA-256", "SCRAM-SHA-512", "OAUTHBEARER", "GSSAPI"))
    truststore <- Arbitrary.arbitrary[Boolean]
    keystore <- Arbitrary.arbitrary[Boolean]
  } yield ClusterSecurityDto(protocol, mechanism, truststore, keystore)

  private val summaries: Gen[ClusterSummaryDto] = for {
    kafkaClusterId <- Gen.option(Gen.identifier.map(KafkaClusterId.unsafe(_)))
    version <- Gen.option(Gen.oneOf("2.8.2", "3.7.0", "4.0.0"))
    controllerId <- Gen.option(Gen.choose(0, 5000).map(BrokerId.unsafe(_)))
    kind <- Gen
      .oneOf(ClusterSummaryDto.KRaft, ClusterSummaryDto.ZooKeeper, ClusterSummaryDto.UnknownController)
    brokerCount <- Gen.choose(0, 500)
    online <- Gen.option(Gen.choose(0, 100000))
    offline <- Gen.option(Gen.choose(0, 100000))
    underReplicated <- Gen.option(Gen.choose(0, 100000))
    disk <- Gen.option(Gen.choose(0L, 1L << 46))
    features <- Gen.listOf(Gen.identifier).map(_.distinct.sorted)
    scrapedAt <- instants
  } yield ClusterSummaryDto(
    kafkaClusterId,
    version,
    controllerId,
    kind,
    brokerCount,
    online,
    offline,
    underReplicated,
    disk,
    features,
    scrapedAt
  )

  private val sections: Gen[Section[ClusterSummaryDto]] = Gen.oneOf(
    for {
      summary <- summaries
      fetchedAt <- instants
    } yield Section.Ok(summary, fetchedAt),
    for {
      summary <- summaries
      fetchedAt <- instants
    } yield Section.Stale(summary, fetchedAt, ReasonCode.UpstreamTimeout),
    for {
      message <- Gen.alphaNumStr
      since <- Gen.option(instants)
    } yield Section.Unavailable(ReasonCode.UpstreamUnavailable, message, since),
    Gen.const(Section.Forbidden),
    Gen.const(Section.NotConfigured)
  )

  private val rows: Gen[ClusterRowDto] = for {
    id <- Gen.oneOf("prod-eu", "staging", "local")
    name <- Gen.alphaNumStr
    readOnly <- Arbitrary.arbitrary[Boolean]
    bootstrap <- Gen.oneOf("localhost:9092", "a.example.com:9093,b.example.com:9093")
    security <- securities
    summary <- sections
  } yield ClusterRowDto(ClusterId.unsafe(id), name, readOnly, bootstrap, security, summary)

  private val brokers: Gen[BrokerDto] = for {
    id <- Gen.choose(0, 5000)
    host <- Gen.oneOf("localhost", "broker-1.example.com", "10.0.0.7")
    port <- Gen.choose(1, 65535)
    rack <- Gen.option(Gen.identifier)
    isController <- Arbitrary.arbitrary[Boolean]
    partitions <- Gen.option(Gen.choose(0, 100000))
    leaders <- Gen.option(Gen.choose(0, 100000))
    isr <- Gen.option(Gen.choose(0, 100000))
    replicaSkew <- Gen.option(Gen.choose(-100.0d, 100.0d))
    leaderSkew <- Gen.option(Gen.choose(-100.0d, 100.0d))
    disk <- Gen.option(Gen.choose(0L, 1L << 46))
    segments <- Gen.option(Gen.choose(0, 1000000))
  } yield BrokerDto(
    BrokerId.unsafe(id),
    host,
    port,
    rack,
    isController,
    partitions,
    leaders,
    isr,
    replicaSkew,
    leaderSkew,
    disk,
    segments
  )

  private val configEntries: Gen[BrokerConfigEntryDto] = for {
    name <- Gen.identifier
    value <- Gen.option(Gen.alphaNumStr)
    source <- Gen.oneOf("DYNAMIC_BROKER_CONFIG", "STATIC_BROKER_CONFIG", "DEFAULT_CONFIG")
    sensitive <- Arbitrary.arbitrary[Boolean]
    readOnly <- Arbitrary.arbitrary[Boolean]
    documentation <- Gen.option(Gen.alphaNumStr)
    synonyms <- Gen.listOf(Gen.identifier)
  } yield BrokerConfigEntryDto(name, value, source, sensitive, readOnly, documentation, synonyms)

  private val logDirs: Gen[LogDirDto] = for {
    brokerId <- Gen.choose(0, 5000)
    path <- Gen.oneOf("/var/lib/kafka/data", "/mnt/disk1", "/mnt/disk2")
    error <- Gen.option(Gen.oneOf("KafkaStorageException", "ClusterAuthorizationException"))
    total <- Gen.option(Gen.choose(0L, 1L << 46))
    usable <- Gen.option(Gen.choose(0L, 1L << 46))
    topics <- Gen.choose(0, 10000)
    partitions <- Gen.choose(0, 100000)
  } yield LogDirDto(BrokerId.unsafe(brokerId), path, error, total, usable, topics, partitions)

  property("everyDtoRoundTrips: a security shape survives the wire") {
    forAll(securities)(dto => assertEquals(dto.asJson.as[ClusterSecurityDto], Right(dto)))
  }

  property("everyDtoRoundTrips: a summary survives the wire") {
    forAll(summaries)(dto => assertEquals(dto.asJson.as[ClusterSummaryDto], Right(dto)))
  }

  property("everyDtoRoundTrips: a row and its section survive the wire") {
    forAll(rows)(dto => assertEquals(dto.asJson.as[ClusterRowDto], Right(dto)))
  }

  property("everyDtoRoundTrips: a broker survives the wire") {
    forAll(brokers)(dto => assertEquals(dto.asJson.as[BrokerDto], Right(dto)))
  }

  property("everyDtoRoundTrips: a config entry survives the wire") {
    forAll(configEntries)(dto => assertEquals(dto.asJson.as[BrokerConfigEntryDto], Right(dto)))
  }

  property("everyDtoRoundTrips: a log directory survives the wire") {
    forAll(logDirs)(dto => assertEquals(dto.asJson.as[LogDirDto], Right(dto)))
  }

  test("anUnknownControllerKindDecodesRatherThanFailing") {
    // A browser meeting a controller kind a newer KUI invented must render the word it was given. A
    // decode failure here would blank the whole dashboard over one unfamiliar string.
    val decoded = parse(ClusterGoldenDocuments.clusterSummary)
      .map(_.deepMerge(io.circe.Json.obj("controllerKind" -> "quorum-thing".asJson)))
      .flatMap(_.as[ClusterSummaryDto])

    assertEquals(decoded.map(_.controllerKind), Right("quorum-thing"))
  }

  test("absentOptionalFieldsDecodeAsNone") {
    val minimal = parse(
      """{"controllerKind":"unknown","brokerCount":0,"scrapedAt":"2026-09-03T10:11:12.000Z"}"""
    ).flatMap(_.as[ClusterSummaryDto])

    assertEquals(
      minimal,
      Right(
        ClusterSummaryDto(None, None, None, "unknown", 0, None, None, None, None, Nil, at)
      )
    )
  }

  test("absentOptionalFieldsDecodeAsNone: a broker with no rack is None, never an empty string") {
    val minimal = parse("""{"id":1,"host":"h","port":9092}""").flatMap(_.as[BrokerDto])

    assertEquals(minimal.map(_.rack), Right(None))
    assertEquals(minimal.map(_.isController), Right(false))
  }

  test("sectionOkAndSectionUnavailableBothDecodeInAClusterRow") {
    val unavailable = row.copy(
      summary = Section.Unavailable(ReasonCode.UpstreamUnavailable, "connection refused", Some(at))
    )

    assertEquals(row.asJson.as[ClusterRowDto], Right(row))
    assertEquals(unavailable.asJson.as[ClusterRowDto], Right(unavailable))

    // The identity half is outside the section, which is what lets an unavailable row still be a link.
    val encoded = unavailable.asJson
    assertEquals(encoded.hcursor.get[String]("name"), Right("Production EU"))
    assertEquals(encoded.hcursor.downField("summary").get[String]("status"), Right("unavailable"))
  }

  test("noSecretFieldExistsOnAnyClusterDto") {
    // R-12's first of three assertions. Every string a profile could carry a credential in is set to one
    // distinctive token; if any DTO grows a field that can hold one, this fails.
    val canary = "kui-secret-canary"
    val documents = List(
      ClusterSecurityDto(canary, Some(canary), truststoreConfigured = true, keystoreConfigured = true).asJson,
      row.copy(security = security.copy(protocol = "SASL_SSL", mechanism = Some("PLAIN"))).asJson,
      summary.asJson,
      broker.asJson,
      logDir.asJson
    )

    // The shape fields are the only strings `ClusterSecurityDto` has, so the canary can appear exactly
    // where a protocol and a mechanism name go and nowhere else.
    assertEquals(documents.head.hcursor.get[String]("protocol"), Right(canary))
    assertEquals(
      documents.head.asObject.map(_.keys.toList),
      Some(List("protocol", "mechanism", "truststoreConfigured", "keystoreConfigured"))
    )
    documents.tail.foreach(document => assert(!document.noSpaces.contains(canary), document.noSpaces))
  }

  test("noSecretFieldExistsOnAnyClusterDto: no DTO has a field whose name suggests a credential") {
    val forbidden = List("password", "jaas", "keystore\"", "truststore\"", "secret", "credential", "username")
    val encoded = List(row.asJson, summary.asJson, broker.asJson, configEntry.asJson, logDir.asJson)
      .map(_.noSpaces.toLowerCase)

    encoded.foreach(document =>
      forbidden.foreach(word => assert(!document.contains(word), s"'$word' in $document"))
    )
  }

  test("instantsAreRfc3339WithExactlyThreeFractionalDigits") {
    // The golden files are only reproducible because the formatter is fixed: `Instant.toString` drops
    // trailing zeros, so a whole second would otherwise render differently from a millisecond.
    val whole = summary.copy(scrapedAt = Instant.parse("2026-09-03T10:11:12Z"))
    val fractional = summary.copy(scrapedAt = Instant.parse("2026-09-03T10:11:12.500Z"))

    assertEquals(whole.asJson.hcursor.get[String]("scrapedAt"), Right("2026-09-03T10:11:12.000Z"))
    assertEquals(fractional.asJson.hcursor.get[String]("scrapedAt"), Right("2026-09-03T10:11:12.500Z"))
  }
}
