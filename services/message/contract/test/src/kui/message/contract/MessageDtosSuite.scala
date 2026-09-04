package kui.message.contract

import java.time.Instant

import io.circe.parser.parse
import io.circe.syntax.*
import io.circe.Json
import munit.FunSuite

import kui.contracts.message.{DecodeErrorDto, DecodedPayloadDto, HeaderDto}
import kui.kernel.{Offset, PartitionId, TopicName}

/** That every message document is the same document on both platforms, and that the four decisions which
  * decide whether a wrong document shows as an error or as an empty screen are asserted on the JSON text.
  *
  * This suite runs on the JVM *and* under Node (`KuiCrossTests`). The JS run is what makes the seam real
  * rather than intended: M1's second integration defect was a browser decoding a document nobody sent, with
  * both suites green, and the only thing that would have caught it is one suite that runs where the browser
  * runs.
  */
final class MessageDtosSuite extends FunSuite {

  private val at: Instant = Instant.parse("2026-09-03T10:11:12Z")

  private val cleanRecord = MessageDto(
    partition = PartitionId.unsafe(3),
    offset = Offset.unsafe(41284L),
    timestamp = at,
    timestampType = MessageDto.TimestampType.CreateTime,
    key = DecodedPayloadDto("A-1", DecodedPayloadDto.Kind.Text, "String", Map.empty),
    value = DecodedPayloadDto(
      """{"orderId":"A-1"}""",
      DecodedPayloadDto.Kind.Json,
      "Avro",
      Map("schemaId" -> "42")
    ),
    headers = Map("traceparent" -> "00-0af7651916cd43dd-b7ad6b7169203331-01"),
    keySize = 3,
    valueSize = 128,
    headersSize = 66,
    deserializeErrors = Nil
  )

  private val undecodableRecord = MessageDto(
    partition = PartitionId.unsafe(0),
    offset = Offset.unsafe(7L),
    timestamp = at,
    timestampType = MessageDto.TimestampType.LogAppendTime,
    key = DecodedPayloadDto.absent("String"),
    value = DecodedPayloadDto("7b 22 6f 72 64", DecodedPayloadDto.Kind.Binary, "Hex", Map.empty),
    headers = Map.empty,
    keySize = 0,
    valueSize = 5,
    headersSize = 0,
    deserializeErrors = List(
      DecodeErrorDto(DecodeErrorDto.Target.Value, "Avro", "Unknown magic byte 0x7b at position 0")
    )
  )

  private val phase = PhaseDto("Seeking to offset 41284 on 3 partitions")

  private val consumed = ConsumedDto(
    bytes = 1048576L,
    records = 4096L,
    elapsedMs = 2310L,
    filterErrors = 0L,
    budget = BudgetDto(recordsLeft = 96, bytesLeft = 51380224L, millisLeft = 57690L)
  )

  private val produced = ProduceResultDto(
    List(ProducedRecordDto(PartitionId.unsafe(3), Offset.unsafe(41285L), at))
  )

  private val resent = ResendResultDto(TopicName.unsafe("orders-replay"), read = 512L, written = 512L)

  private val purged = PurgeResultDto(
    purged = List(PurgedPartitionDto(PartitionId.unsafe(0), Offset.unsafe(1024L))),
    failed = List(PurgeFailureDto(PartitionId.unsafe(1), "the partition has no leader"))
  )

  private val track = TrackQueryDto(
    topics = List(TopicName.unsafe("orders"), TopicName.unsafe("payments"), TopicName.unsafe("shipments")),
    `match` = TrackMatchDto(
      TrackMatchDto.Source.Header,
      Some("correlationId"),
      TrackMatchDto.Operator.Equals,
      "A-1"
    ),
    from = at,
    to = at.plusSeconds(3600),
    limit = Some(100)
  )

  private val suggestion = SerdeSuggestionDto(
    name = "Avro",
    target = "value",
    preferred = true,
    reason = "the schema registry has a subject named orders-value"
  )

  private val filterResult = FilterTestResultDto(matched = false, error = Some("no such field: value.orderID"))

  private def normalised(raw: String): String =
    parse(raw).fold(failure => fail(s"the golden document is not JSON: ${failure.message}"), _.spaces2)

  List(
    "message.json" -> (GoldenDocuments.message, cleanRecord.asJson),
    "message-decode-error.json" -> (GoldenDocuments.messageWithDecodeError, undecodableRecord.asJson),
    "phase-event.json" -> (GoldenDocuments.phaseEvent, phase.asJson),
    "consumed-event.json" -> (GoldenDocuments.consumedEvent, consumed.asJson),
    "produce-result.json" -> (GoldenDocuments.produceResult, produced.asJson),
    "resend-result.json" -> (GoldenDocuments.resendResult, resent.asJson),
    "purge-result.json" -> (GoldenDocuments.purgeResult, purged.asJson),
    "track-query.json" -> (GoldenDocuments.trackQuery, track.asJson),
    "serde-suggestion.json" -> (GoldenDocuments.serdeSuggestion, suggestion.asJson),
    "filter-test-result.json" -> (GoldenDocuments.filterTestResult, filterResult.asJson)
  ).foreach { case (file, (document, encoded)) =>
    test(s"goldenFilePerDto: $file") {
      assertNoDiff(encoded.spaces2, normalised(document))
    }
  }

  test("decodesItsOwnGoldenFile") {
    assertEquals(parse(GoldenDocuments.message).flatMap(_.as[MessageDto]), Right(cleanRecord))
    assertEquals(parse(GoldenDocuments.messageWithDecodeError).flatMap(_.as[MessageDto]), Right(undecodableRecord))
    assertEquals(parse(GoldenDocuments.phaseEvent).flatMap(_.as[PhaseDto]), Right(phase))
    assertEquals(parse(GoldenDocuments.consumedEvent).flatMap(_.as[ConsumedDto]), Right(consumed))
    assertEquals(parse(GoldenDocuments.produceResult).flatMap(_.as[ProduceResultDto]), Right(produced))
    assertEquals(parse(GoldenDocuments.resendResult).flatMap(_.as[ResendResultDto]), Right(resent))
    assertEquals(parse(GoldenDocuments.purgeResult).flatMap(_.as[PurgeResultDto]), Right(purged))
    assertEquals(parse(GoldenDocuments.trackQuery).flatMap(_.as[TrackQueryDto]), Right(track))
    assertEquals(parse(GoldenDocuments.serdeSuggestion).flatMap(_.as[SerdeSuggestionDto]), Right(suggestion))
    assertEquals(parse(GoldenDocuments.filterTestResult).flatMap(_.as[FilterTestResultDto]), Right(filterResult))
  }

  test("aMissingDeserializeErrorsListIsADecodeFailure") {
    // The M1 defect in miniature. A decoder that defaulted this list to empty would read a truncated or
    // foreign document as "this record decoded perfectly", and the failure that should have been on screen
    // would be nowhere at all.
    val withoutIt = cleanRecord.asJson.mapObject(_.remove("deserializeErrors"))
    assert(withoutIt.as[MessageDto].isLeft, withoutIt.noSpaces)
  }

  test("unknownFieldsAreIgnored") {
    // The other direction, which must not fail: a newer service adding a field must not blank an older
    // browser's screen.
    val extended = cleanRecord.asJson.mapObject(_.add("tomorrow", Json.fromInt(1)))
    assertEquals(extended.as[MessageDto], Right(cleanRecord))
  }

  test("timestampsAreIso8601WithMillis") {
    // Fixed to three fractional digits by `ErrorEnvelope`'s Instant codec, which every KUI document shares.
    // `Instant.toString` drops trailing zeros, so a record written on the stroke of a second would otherwise
    // be a different length from one written a millisecond later, and a client parsing by position would be
    // wrong once a second.
    List(cleanRecord.asJson, produced.asJson, track.asJson).foreach { document =>
      val timestamps = "\"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z\"".r
      val quoted = "\"[0-9]{4}-[0-9]{2}-[0-9]{2}T[^\"]*\"".r
      assertEquals(
        quoted.findAllIn(document.noSpaces).toList,
        timestamps.findAllIn(document.noSpaces).toList,
        document.noSpaces
      )
    }
  }

  test("noFieldIsPermanentlyNull") {
    // Enumerated: for every document this contract publishes, there is at least one legal value that fills
    // every field. A field no value can fill is a field every client learns to ignore, and CLAPI-001 is the
    // decision that keeps it off the wire.
    val fullyPopulated: List[(String, Json)] = List(
      "MessageDto" -> undecodableRecord.asJson,
      "PhaseDto" -> phase.asJson,
      "ConsumedDto" -> consumed.asJson,
      "ProduceResultDto" -> produced.asJson,
      "ResendResultDto" -> resent.asJson,
      "PurgeResultDto" -> purged.asJson,
      "TrackQueryDto" -> track.asJson,
      "SerdeSuggestionDto" -> suggestion.asJson,
      "FilterTestResultDto" -> filterResult.asJson,
      "ProduceRequestDto" -> ProduceRequestDto(
        partition = Some(PartitionId.unsafe(0)),
        key = Some("A-1"),
        value = Some("{}"),
        headers = List(HeaderDto("trace", Some("1"))),
        keySerde = Some("String"),
        valueSerde = Some("Json"),
        count = 1
      ).asJson,
      "ResendRequestDto" -> ResendRequestDto(
        TopicName.unsafe("orders-replay"),
        List(OffsetRangeDto(PartitionId.unsafe(0), Offset.unsafe(0L), Offset.unsafe(10L)))
      ).asJson,
      "FilterRegistrationDto" -> FilterRegistrationDto("value.orderId == 'A-1'", Some("order A-1")).asJson,
      "TrackHitDto" -> TrackHitDto(TopicName.unsafe("orders"), cleanRecord).asJson
    )

    fullyPopulated.foreach { case (name, document) =>
      val nulls = document.hcursor.keys.getOrElse(Nil).filter(key => document.hcursor.get[Json](key).exists(_.isNull))
      assertEquals(nulls.toList, Nil, s"$name has a field nothing can fill: $document")
    }
  }

  test("aTrackWithSourceHeaderAndNoHeaderNameIsRefusedRatherThanReinterpreted") {
    // The reference product's defect, stated as a test. Silently searching the value instead produces
    // results, and they are the wrong results, which is worse than an error.
    val bad = """{"source":"header","header":null,"operator":"equals","value":"A-1"}"""
    assert(parse(bad).flatMap(_.as[TrackMatchDto]).isLeft)
  }

  test("aHeaderNameWithAValueSearchIsRefused") {
    val bad = """{"source":"value","header":"correlationId","operator":"equals","value":"A-1"}"""
    assert(parse(bad).flatMap(_.as[TrackMatchDto]).isLeft)
  }

  test("anUnknownTrackSourceOrOperatorIsRefused") {
    val badSource = """{"source":"partition","header":null,"operator":"equals","value":"x"}"""
    val badOperator = """{"source":"value","header":null,"operator":"startsWith","value":"x"}"""
    assert(parse(badSource).flatMap(_.as[TrackMatchDto]).isLeft)
    assert(parse(badOperator).flatMap(_.as[TrackMatchDto]).isLeft)
  }

  test("aBackwardsOffsetRangeIsRefused") {
    val bad = """{"partition":0,"from":10,"until":3}"""
    assert(parse(bad).flatMap(_.as[OffsetRangeDto]).isLeft)
    // The empty range is legal: it is a range that selects nothing, which a caller may compute.
    assertEquals(
      parse("""{"partition":0,"from":10,"until":10}""").flatMap(_.as[OffsetRangeDto]).map(_.from),
      Right(Offset.unsafe(10L))
    )
  }

  test("aTrackWindowThatEndsBeforeItStartsIsRefused") {
    val bad = track.copy(to = at.minusSeconds(1)).asJson.noSpaces
    assert(parse(bad).flatMap(_.as[TrackQueryDto]).isLeft)
  }

  test("aTombstoneIsAnAbsentValueAndNotAnEmptyOne") {
    // Two different records on a compacted topic, and confusing them deletes data that should have been kept
    // or keeps data that should have been deleted.
    val tombstone = ProduceRequestDto(None, Some("A-1"), None, Nil, None, None, 1)
    val empty = tombstone.copy(value = Some(""))
    assert(tombstone.asJson.noSpaces.contains("\"value\":null"))
    assert(empty.asJson.noSpaces.contains("\"value\":\"\""))
    assertNotEquals(tombstone.asJson, empty.asJson)
  }

  test("theJvmAndJsEncodersAgree") {
    GoldenDocuments.all.foreach { case (file, document) =>
      assert(parse(document).isRight, s"$file is not JSON on this platform")
    }
  }
}
