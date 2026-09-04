package kui.contracts.topic

/** The committed sample topic documents, as text.
  *
  * Same device, and same reason, as `kui.contracts.GoldenDocuments`: a browser has no filesystem, so a
  * Scala.js suite cannot read the files under `test/resources/golden`. Both platforms assert against these
  * constants, and the JVM-only `GoldenFilesSuite` asserts each constant is byte for byte the file committed
  * beside it, so the two copies cannot drift.
  */
object TopicGoldenDocuments {

  /** An empty page. It is page 1 of 1, not page 1 of 0 — see `PageInfo.pageCount`. */
  val page: String =
    """{
      |  "items" : [
      |  ],
      |  "page" : {
      |    "page" : 1,
      |    "pageSize" : 25,
      |    "totalItems" : 0,
      |    "pageCount" : 1,
      |    "nextPageToken" : null
      |  }
      |}""".stripMargin

  /** A healthy topic: every number is knowable and none of them is an em dash on screen. */
  val topicRow: String =
    """{
      |  "name" : "orders",
      |  "internal" : false,
      |  "partitionCount" : 6,
      |  "replicationFactor" : 3,
      |  "outOfSyncReplicas" : 0,
      |  "offlinePartitions" : 0,
      |  "messageCount" : 1048576,
      |  "sizeBytes" : 734003200
      |}""".stripMargin

  /** A topic with one offline partition: `leader` is null, and every count that would have had to
    * include that partition is null too, on the partition row and on the topic row alike.
    */
  val topicDetail: String =
    """{
      |  "row" : {
      |    "name" : "payments",
      |    "internal" : false,
      |    "partitionCount" : 2,
      |    "replicationFactor" : 3,
      |    "outOfSyncReplicas" : 1,
      |    "offlinePartitions" : 1,
      |    "messageCount" : null,
      |    "sizeBytes" : null
      |  },
      |  "partitions" : [
      |    {
      |      "partition" : 0,
      |      "leader" : 1,
      |      "replicas" : [
      |        {
      |          "broker" : 1,
      |          "leader" : true,
      |          "inSync" : true
      |        },
      |        {
      |          "broker" : 2,
      |          "leader" : false,
      |          "inSync" : true
      |        }
      |      ],
      |      "earliestOffset" : 0,
      |      "latestOffset" : 512,
      |      "messageCount" : 512,
      |      "sizeBytes" : 65536
      |    },
      |    {
      |      "partition" : 1,
      |      "leader" : null,
      |      "replicas" : [
      |        {
      |          "broker" : 2,
      |          "leader" : false,
      |          "inSync" : false
      |        }
      |      ],
      |      "earliestOffset" : null,
      |      "latestOffset" : null,
      |      "messageCount" : null,
      |      "sizeBytes" : null
      |    }
      |  ],
      |  "cleanupPolicy" : "delete",
      |  "segmentCount" : 12
      |}""".stripMargin

  val topicConfig: String =
    """{
      |  "name" : "retention.ms",
      |  "value" : "604800000",
      |  "defaultValue" : "604800000",
      |  "source" : "default_config",
      |  "sensitive" : false,
      |  "readOnly" : false,
      |  "documentation" : "How long a log segment is kept before it is discarded"
      |}""".stripMargin

  /** Every constant above, by the file name it is committed under. */
  val all: List[(String, String)] = List(
    "page.json" -> page,
    "topic-row.json" -> topicRow,
    "topic-detail.json" -> topicDetail,
    "topic-config.json" -> topicConfig
  )
}
