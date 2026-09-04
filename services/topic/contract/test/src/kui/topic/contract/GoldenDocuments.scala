package kui.topic.contract

/** The committed sample documents of this contract, as text.
  *
  * They are constants rather than files for the same reason as in `libs/contracts-core` and in the cluster
  * service's contract: a browser has no filesystem, so the Scala.js half of a cross-compiled suite cannot
  * read `test/resources/golden`. Both platforms assert against these constants, and a JVM-only suite
  * (`GoldenFilesSuite`) asserts that each constant is exactly the file committed beside it — so a constant
  * and a file cannot drift apart without something failing.
  *
  * The files matter beyond this module: the gateway's proxy suite replays `topics-response.json` through its
  * own client to prove that what this service encodes is what the browser receives (TOP-023). M1's second
  * integration defect was a browser decoding a document nobody sends, and a document committed in one place
  * and replayed in three is the artefact that failure lacked.
  */
object GoldenDocuments {

  val topicsResponse: String =
    """{
      |  "topics" : {
      |    "status" : "ok",
      |    "data" : {
      |      "items" : [
      |        {
      |          "name" : "orders",
      |          "internal" : false,
      |          "partitionCount" : 12,
      |          "replicationFactor" : 3,
      |          "outOfSyncReplicas" : 0,
      |          "offlinePartitions" : 0,
      |          "messageCount" : 1234567,
      |          "sizeBytes" : 9483264
      |        },
      |        {
      |          "name" : "payments.dlq",
      |          "internal" : false,
      |          "partitionCount" : 3,
      |          "replicationFactor" : 3,
      |          "outOfSyncReplicas" : 2,
      |          "offlinePartitions" : 1,
      |          "messageCount" : null,
      |          "sizeBytes" : 41984
      |        }
      |      ],
      |      "page" : {
      |        "page" : 1,
      |        "pageSize" : 25,
      |        "totalItems" : 2,
      |        "pageCount" : 1,
      |        "nextPageToken" : null
      |      }
      |    },
      |    "fetchedAt" : "2026-09-03T10:11:12.000Z"
      |  },
      |  "incompleteTopics" : 1
      |}""".stripMargin

  /** The same list, from a snapshot that is older than it should be and could not be renewed.
    *
    * The rows are still here and still true as of `fetchedAt`; the screen greys them and shows the time. A
    * `reason` of `UPSTREAM_TIMEOUT` and not `UPSTREAM_UNAVAILABLE`: an operator does different things about a
    * cluster that is slow and one that is gone, and M1's cluster service collapsed the two (CLAPI-004
    * deviation 2).
    */
  val topicsResponseStale: String =
    """{
      |  "topics" : {
      |    "status" : "stale",
      |    "data" : {
      |      "items" : [
      |        {
      |          "name" : "orders",
      |          "internal" : false,
      |          "partitionCount" : 12,
      |          "replicationFactor" : 3,
      |          "outOfSyncReplicas" : 0,
      |          "offlinePartitions" : 0,
      |          "messageCount" : 1234567,
      |          "sizeBytes" : 9483264
      |        }
      |      ],
      |      "page" : {
      |        "page" : 1,
      |        "pageSize" : 25,
      |        "totalItems" : 1,
      |        "pageCount" : 1,
      |        "nextPageToken" : null
      |      }
      |    },
      |    "fetchedAt" : "2026-09-03T10:11:12.000Z",
      |    "reason" : "UPSTREAM_TIMEOUT"
      |  },
      |  "incompleteTopics" : 0
      |}""".stripMargin

  /** A cluster that has never been scraped. Not an empty page: an empty page from a cluster with ten
    * thousand topics is a lie that looks like data.
    */
  val topicsResponseUnavailable: String =
    """{
      |  "topics" : {
      |    "status" : "unavailable",
      |    "reason" : "UPSTREAM_UNAVAILABLE",
      |    "message" : "no snapshot of prod-eu has been taken yet",
      |    "since" : "2026-09-03T10:10:00.000Z"
      |  },
      |  "incompleteTopics" : 0
      |}""".stripMargin

  val topicDetailResponse: String =
    """{
      |  "topic" : {
      |    "status" : "ok",
      |    "data" : {
      |      "row" : {
      |        "name" : "orders",
      |        "internal" : false,
      |        "partitionCount" : 2,
      |        "replicationFactor" : 3,
      |        "outOfSyncReplicas" : 1,
      |        "offlinePartitions" : 1,
      |        "messageCount" : null,
      |        "sizeBytes" : 9483264
      |      },
      |      "partitions" : [
      |        {
      |          "partition" : 0,
      |          "leader" : 1,
      |          "replicas" : [
      |            {
      |              "broker" : 1,
      |              "leader" : true,
      |              "inSync" : true
      |            },
      |            {
      |              "broker" : 2,
      |              "leader" : false,
      |              "inSync" : true
      |            }
      |          ],
      |          "earliestOffset" : 0,
      |          "latestOffset" : 617283,
      |          "messageCount" : 617283,
      |          "sizeBytes" : 4741632
      |        },
      |        {
      |          "partition" : 1,
      |          "leader" : null,
      |          "replicas" : [
      |            {
      |              "broker" : 3,
      |              "leader" : false,
      |              "inSync" : false
      |            }
      |          ],
      |          "earliestOffset" : null,
      |          "latestOffset" : null,
      |          "messageCount" : null,
      |          "sizeBytes" : 4741632
      |        }
      |      ],
      |      "cleanupPolicy" : "delete",
      |      "segmentCount" : 24
      |    },
      |    "fetchedAt" : "2026-09-03T10:11:12.000Z"
      |  },
      |  "partitionsTruncated" : false
      |}""".stripMargin

  val topicConfigResponse: String =
    """{
      |  "config" : {
      |    "status" : "ok",
      |    "data" : {
      |      "status" : "entries",
      |      "values" : [
      |        {
      |          "name" : "cleanup.policy",
      |          "value" : "delete",
      |          "defaultValue" : "delete",
      |          "source" : "default_config",
      |          "sensitive" : false,
      |          "readOnly" : false,
      |          "documentation" : null
      |        },
      |        {
      |          "name" : "retention.ms",
      |          "value" : "604800000",
      |          "defaultValue" : "-1",
      |          "source" : "dynamic_topic_config",
      |          "sensitive" : false,
      |          "readOnly" : false,
      |          "documentation" : "How long a log segment is kept before being discarded"
      |        }
      |      ]
      |    },
      |    "fetchedAt" : "2026-09-03T10:11:12.000Z"
      |  }
      |}""".stripMargin

  /** The caller may see the topic and not its settings. A `not_permitted` view and not a 403, so the
    * partitions they *are* allowed to see stay on the screen.
    */
  val topicConfigNotPermitted: String =
    """{
      |  "config" : {
      |    "status" : "ok",
      |    "data" : {
      |      "status" : "not_permitted",
      |      "detail" : "the cluster refused describeConfigs for topic 'orders': TopicAuthorizationException"
      |    },
      |    "fetchedAt" : "2026-09-03T10:11:12.000Z"
      |  }
      |}""".stripMargin

  val partitionsResponse: String =
    """{
      |  "partitions" : {
      |    "status" : "ok",
      |    "data" : [
      |      {
      |        "partition" : 0,
      |        "leader" : 1,
      |        "replicas" : [
      |          {
      |            "broker" : 1,
      |            "leader" : true,
      |            "inSync" : true
      |          },
      |          {
      |            "broker" : 2,
      |            "leader" : false,
      |            "inSync" : true
      |          }
      |        ],
      |        "earliestOffset" : 0,
      |        "latestOffset" : 617283,
      |        "messageCount" : 617283,
      |        "sizeBytes" : 4741632
      |      }
      |    ],
      |    "fetchedAt" : "2026-09-03T10:11:12.000Z"
      |  }
      |}""".stripMargin

  val refreshAccepted: String =
    """{
      |  "clusterId" : "prod-eu",
      |  "requestedAt" : "2026-09-03T10:11:12.000Z"
      |}""".stripMargin

  /** Every sample, by file name, for the JVM suite to walk. */
  val all: Map[String, String] = Map(
    "topics-response.json" -> topicsResponse,
    "topics-response-stale.json" -> topicsResponseStale,
    "topics-response-unavailable.json" -> topicsResponseUnavailable,
    "topic-detail-response.json" -> topicDetailResponse,
    "topic-config-response.json" -> topicConfigResponse,
    "topic-config-not-permitted.json" -> topicConfigNotPermitted,
    "partitions-response.json" -> partitionsResponse,
    "refresh-accepted.json" -> refreshAccepted
  )
}
