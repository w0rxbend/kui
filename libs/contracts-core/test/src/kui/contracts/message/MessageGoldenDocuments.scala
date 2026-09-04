package kui.contracts.message

/** The committed sample message fragments, as text.
  *
  * Same device, and same reason, as `kui.contracts.GoldenDocuments`: a browser has no filesystem, so a
  * Scala.js suite cannot read the files under `test/resources/golden`. Both platforms assert against these
  * constants, and the JVM-only `GoldenFilesSuite` asserts each constant is byte for byte the file committed
  * beside it, so the two copies cannot drift.
  */
object MessageGoldenDocuments {

  /** A value that decoded cleanly, with the schema the registry-backed serde used. */
  val decodedPayload: String =
    """{
      |  "text" : "{\"orderId\":\"A-1\"}",
      |  "kind" : "json",
      |  "serde" : "Avro",
      |  "properties" : {
      |    "schemaId" : "42",
      |    "subject" : "orders-value"
      |  }
      |}""".stripMargin

  /** A record with no key at all. It is a payload with `kind: "null"`, not a missing field: see
    * `DecodedPayloadDto.text`.
    */
  val absentPayload: String =
    """{
      |  "text" : "",
      |  "kind" : "null",
      |  "serde" : "String",
      |  "properties" : {
      |  }
      |}""".stripMargin

  /** The serde failed; the record was delivered anyway, by the fallback. */
  val decodeError: String =
    """{
      |  "target" : "value",
      |  "serde" : "Avro",
      |  "cause" : "Unknown magic byte 0x7b at position 0"
      |}""".stripMargin

  /** One header on the way in, with a value that is explicitly null rather than absent. */
  val header: String =
    """{
      |  "name" : "kafka_dlt-exception-fqcn",
      |  "value" : null
      |}""".stripMargin

  /** Every constant above, by the file name it is committed under. */
  val all: List[(String, String)] = List(
    "decoded-payload.json" -> decodedPayload,
    "decoded-payload-absent.json" -> absentPayload,
    "decode-error.json" -> decodeError,
    "message-header.json" -> header
  )
}
