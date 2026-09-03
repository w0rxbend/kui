package kui.cluster.contract

/** The committed sample documents of this contract, as text.
  *
  * They are constants rather than files for the same reason as in `libs/contracts-core`: a browser has no
  * filesystem, so the Scala.js half of a cross-compiled suite cannot read `test/resources/golden`. Both
  * platforms assert against these constants, and a JVM-only suite (`GoldenFilesSuite`) asserts that each
  * constant is exactly the file committed beside it — so a constant and a file cannot drift apart without
  * something failing.
  */
object GoldenDocuments {

  val pingResponse: String =
    """{
      |  "message" : "hello",
      |  "at" : "2026-09-03T10:11:12.000Z",
      |  "service" : "cluster"
      |}""".stripMargin

  /** Every sample, by file name, for the JVM suite to walk. */
  val all: Map[String, String] = Map("ping-response.json" -> pingResponse)
}
