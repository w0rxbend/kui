package kui.contracts

/** The committed sample documents, as text.
  *
  * They are duplicated here as string constants for one reason: a browser has no filesystem, so a
  * Scala.js suite cannot read the JSON files under `test/resources/golden`. Both platforms therefore assert against
  * these constants, and a JVM-only suite (`GoldenFilesSuite`) asserts that each constant is exactly
  * the file that is committed. Nothing can drift: if someone edits a golden file, the JVM suite
  * fails, and if someone edits a constant without the file, the same suite fails.
  *
  * KERN-007 gives `libs/testkit` a `Golden.assertJson` helper with a `KUI_UPDATE_GOLDEN=1` mode that
  * rewrites the files; that helper is JVM-only for the same reason.
  */
object GoldenDocuments {

  val errorEnvelopeValidation: String =
    """{
      |  "code" : "KUI-VALIDATION",
      |  "message" : "Request is not valid",
      |  "details" : [
      |    {
      |      "field" : "partitions",
      |      "restrictions" : [
      |        "must be > 0"
      |      ]
      |    }
      |  ],
      |  "correlationId" : "3b1fa9c2e4d54f0b",
      |  "timestamp" : "2026-09-03T10:11:12.000Z",
      |  "retryable" : false
      |}""".stripMargin

  val errorEnvelopeUpstream: String =
    """{
      |  "code" : "KUI-UPSTREAM-UNAVAILABLE",
      |  "message" : "schema-registry could not be reached",
      |  "details" : [
      |  ],
      |  "correlationId" : "3b1fa9c2e4d54f0b",
      |  "timestamp" : "2026-09-03T10:11:12.000Z",
      |  "retryable" : true
      |}""".stripMargin

  /** Every constant above, by the file name it is committed under. */
  val all: List[(String, String)] = List(
    "error-envelope-validation.json" -> errorEnvelopeValidation,
    "error-envelope-upstream.json"   -> errorEnvelopeUpstream
  )
}
