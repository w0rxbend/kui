package kui.contracts.message

import io.circe.parser.parse
import io.circe.syntax.*
import munit.FunSuite

/** That the record fragments are the same document on both platforms, and that the two decisions which stop
  * a bad record from hiding a good one — an absent payload is a payload, a decode failure travels beside the
  * record — are asserted on the JSON text rather than on a decoded value.
  */
final class MessageFragmentsSuite extends FunSuite {

  private val decoded = DecodedPayloadDto(
    text = """{"orderId":"A-1"}""",
    kind = DecodedPayloadDto.Kind.Json,
    serde = "Avro",
    properties = Map("schemaId" -> "42", "subject" -> "orders-value")
  )

  private val absent = DecodedPayloadDto.absent("String")

  private val failure = DecodeErrorDto(
    target = DecodeErrorDto.Target.Value,
    serde = "Avro",
    cause = "Unknown magic byte 0x7b at position 0"
  )

  private val header = HeaderDto("kafka_dlt-exception-fqcn", None)

  private def normalised(raw: String): String =
    parse(raw).fold(failure => fail(s"the golden document is not JSON: ${failure.message}"), _.spaces2)

  List(
    "decoded-payload.json" -> (MessageGoldenDocuments.decodedPayload, decoded.asJson),
    "decoded-payload-absent.json" -> (MessageGoldenDocuments.absentPayload, absent.asJson),
    "decode-error.json" -> (MessageGoldenDocuments.decodeError, failure.asJson),
    "message-header.json" -> (MessageGoldenDocuments.header, header.asJson)
  ).foreach { case (file, (document, encoded)) =>
    test(s"goldenFilePerDto: $file") {
      assertNoDiff(encoded.spaces2, normalised(document))
    }
  }

  test("decodesItsOwnGoldenFile") {
    assertEquals(parse(MessageGoldenDocuments.decodedPayload).flatMap(_.as[DecodedPayloadDto]), Right(decoded))
    assertEquals(parse(MessageGoldenDocuments.absentPayload).flatMap(_.as[DecodedPayloadDto]), Right(absent))
    assertEquals(parse(MessageGoldenDocuments.decodeError).flatMap(_.as[DecodeErrorDto]), Right(failure))
    assertEquals(parse(MessageGoldenDocuments.header).flatMap(_.as[HeaderDto]), Right(header))
  }

  test("anAbsentKeyIsAPayloadAndNotAMissingField") {
    // The alternative — `"key": null` on the record — makes every client write two code paths for one
    // concept, and the second of them is the one that is never tested.
    val json = absent.asJson.noSpaces
    assertEquals(json, """{"text":"","kind":"null","serde":"String","properties":{}}""")
  }

  test("aMissingTextIsADecodeFailureNotAnEmptyPayload") {
    // M1's second integration defect in miniature. A decoder that defaulted `text` would turn a document
    // that never arrived into a record that renders as blank, with the error going nowhere.
    val truncated = """{"kind":"string","serde":"String","properties":{}}"""
    assert(parse(truncated).flatMap(_.as[DecodedPayloadDto]).isLeft)
  }

  test("unknownFieldsAreIgnored") {
    // Forward compatibility in the direction that matters: a newer service may add a field, and an older
    // browser must render the record rather than fail the screen.
    val extended = """{"text":"x","kind":"string","serde":"String","properties":{},"tomorrow":1}"""
    assertEquals(
      parse(extended).flatMap(_.as[DecodedPayloadDto]).map(_.text),
      Right("x")
    )
  }

  test("anUnknownKindDecodesRatherThanFailing") {
    // `kind` is a rendering hint, not an enum: a client that meets one it does not know shows plain text.
    val exotic = """{"text":"x","kind":"protobuf-text","serde":"Protobuf","properties":{}}"""
    assertEquals(parse(exotic).flatMap(_.as[DecodedPayloadDto]).map(_.kind), Right("protobuf-text"))
  }

  test("aHeaderValueThatIsNullIsKeptAsNullAndNotAsAnEmptyString") {
    // Kafka distinguishes the two and Spring's DLT headers rely on the distinction.
    assertEquals(header.asJson.noSpaces, """{"name":"kafka_dlt-exception-fqcn","value":null}""")
    assertEquals(parse(header.asJson.noSpaces).flatMap(_.as[HeaderDto]).map(_.value), Right(None))
  }

  test("theJvmAndJsEncodersAgree") {
    MessageGoldenDocuments.all.foreach { case (file, document) =>
      assert(parse(document).isRight, s"$file is not JSON on this platform")
    }
  }
}
