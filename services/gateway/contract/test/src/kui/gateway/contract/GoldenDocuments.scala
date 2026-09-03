package kui.gateway.contract

/** The committed sample documents of the gateway's contract, as text.
  *
  * They are constants rather than files for the same reason as in `libs/contracts-core`: a browser has no
  * filesystem, so the Scala.js half of a cross-compiled suite cannot read `test/resources/golden`. Both
  * platforms assert against these constants, and a JVM-only suite (`GoldenFilesSuite`) asserts that each
  * constant is exactly the file committed beside it — so a constant and a file cannot drift apart without
  * something failing.
  */
object GoldenDocuments {

  /** `GET /api/v1/info` for a deployment with one configured service and nothing switched on.
    *
    * The build fields are placeholders a real build never produces — a hash of all zeroes, the epoch — so
    * that the file pins the document's *shape* and cannot go stale every time someone commits.
    */
  val appInfo: String =
    """{
      |  "build" : {
      |    "version" : "0.1.0-SNAPSHOT",
      |    "gitCommit" : "0000000000000000000000000000000000000000",
      |    "gitCommitShort" : "0000000",
      |    "gitDirty" : false,
      |    "builtAt" : "1970-01-01T00:00:00.000Z",
      |    "scalaVersion" : "3.9.0",
      |    "jdkVersion" : "21"
      |  },
      |  "authType" : "disabled",
      |  "basePath" : "",
      |  "services" : [
      |    "cluster"
      |  ],
      |  "features" : {
      |    "cors" : false
      |  }
      |}""".stripMargin

  /** Every sample, by file name, for the JVM suite to walk. */
  val all: Map[String, String] = Map("app-info.json" -> appInfo)
}
