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

  /** The dashboard with the cluster service gone: the outer section is stale and carries the rows the
    * gateway last saw, one of which was already unreachable when they arrived. Two levels of failure in one
    * document, which is the shape the whole endpoint exists to produce.
    */
  val clusterOverview: String =
    """{
      |  "clusters" : {
      |    "status" : "stale",
      |    "data" : [
      |      {
      |        "cluster" : {
      |          "id" : "prod-eu",
      |          "name" : "Production EU",
      |          "readOnly" : false,
      |          "bootstrapServers" : "broker-1.example.com:9093",
      |          "security" : {
      |            "protocol" : "SASL_SSL",
      |            "mechanism" : "SCRAM-SHA-512",
      |            "truststoreConfigured" : true,
      |            "keystoreConfigured" : false
      |          },
      |          "summary" : {
      |            "status" : "ok",
      |            "data" : {
      |              "kafkaClusterId" : "MkU3OEVBNTcwNTJENDM2Qk",
      |              "version" : "4.0.0",
      |              "controllerId" : 1,
      |              "controllerKind" : "kraft",
      |              "brokerCount" : 3,
      |              "onlinePartitionCount" : null,
      |              "offlinePartitionCount" : null,
      |              "underReplicatedPartitionCount" : null,
      |              "totalDiskUsageBytes" : 549755813888,
      |              "features" : [
      |              ],
      |              "scrapedAt" : "2026-09-03T10:11:12.000Z"
      |            },
      |            "fetchedAt" : "2026-09-03T10:11:12.000Z"
      |          },
      |          "version" : null,
      |          "origin" : "static"
      |        },
      |        "capability" : {
      |          "status" : "available"
      |        },
      |        "topics" : {
      |          "status" : "ok",
      |          "data" : {
      |            "topicCount" : 2,
      |            "countedTopics" : 2,
      |            "partitionCount" : 9,
      |            "largest" : [
      |              {
      |                "name" : "orders.v1",
      |                "partitionCount" : 6
      |              },
      |              {
      |                "name" : "payments.v1",
      |                "partitionCount" : 3
      |              }
      |            ]
      |          },
      |          "fetchedAt" : "2026-09-03T10:11:12.000Z"
      |        },
      |        "consumerGroups" : {
      |          "status" : "ok",
      |          "data" : {
      |            "groupCount" : 2,
      |            "byState" : [
      |              {
      |                "state" : "STABLE",
      |                "count" : 1
      |              },
      |              {
      |                "state" : "EMPTY",
      |                "count" : 1
      |              }
      |            ],
      |            "totalLag" : 9,
      |            "groupsWithoutLag" : 0
      |          },
      |          "fetchedAt" : "2026-09-03T10:11:12.000Z"
      |        }
      |      },
      |      {
      |        "cluster" : {
      |          "id" : "dead",
      |          "name" : "Decommissioned",
      |          "readOnly" : true,
      |          "bootstrapServers" : "gone.example.com:9092",
      |          "security" : {
      |            "protocol" : "PLAINTEXT",
      |            "mechanism" : null,
      |            "truststoreConfigured" : false,
      |            "keystoreConfigured" : false
      |          },
      |          "summary" : {
      |            "status" : "unavailable",
      |            "reason" : "UPSTREAM_UNAVAILABLE",
      |            "message" : "connection refused",
      |            "since" : "2026-09-03T10:11:12.000Z"
      |          },
      |          "version" : null,
      |          "origin" : "static"
      |        },
      |        "capability" : {
      |          "status" : "degraded",
      |          "reason" : {
      |            "code" : "STARTING",
      |            "message" : "this cluster has not been scraped yet",
      |            "suggestedPollIntervalMs" : null,
      |            "p95Ms" : null
      |          }
      |        },
      |        "topics" : {
      |          "status" : "unavailable",
      |          "reason" : "UPSTREAM_UNAVAILABLE",
      |          "message" : "connection refused",
      |          "since" : "2026-09-03T10:11:12.000Z"
      |        },
      |        "consumerGroups" : {
      |          "status" : "not_configured"
      |        }
      |      }
      |    ],
      |    "fetchedAt" : "2026-09-03T10:11:12.000Z",
      |    "reason" : "UPSTREAM_UNAVAILABLE"
      |  },
      |  "generatedAt" : "2026-09-03T10:11:13.000Z"
      |}""".stripMargin

  /** The topic page's document, as an M2 deployment really answers it: one filled section and four saying
    * this deployment has no such service.
    *
    * `not_configured` and not `unavailable`, for all four. `unavailable` would put four permanent red panels
    * on every topic page of every installation, and an operator shown four errors that never change stops
    * reading errors (DEVPLAN §10 D10, ADR-032).
    */
  val topicOverview: String =
    """{
      |  "topic" : {
      |    "status" : "ok",
      |    "data" : {
      |      "row" : {
      |        "name" : "orders",
      |        "internal" : false,
      |        "partitionCount" : 1,
      |        "replicationFactor" : 3,
      |        "outOfSyncReplicas" : 0,
      |        "offlinePartitions" : 0,
      |        "messageCount" : 617283,
      |        "sizeBytes" : 4741632
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
      |            }
      |          ],
      |          "earliestOffset" : 0,
      |          "latestOffset" : 617283,
      |          "messageCount" : 617283,
      |          "sizeBytes" : 4741632
      |        }
      |      ],
      |      "cleanupPolicy" : "delete",
      |      "segmentCount" : 24
      |    },
      |    "fetchedAt" : "2026-09-03T10:11:12.000Z"
      |  },
      |  "consumerGroups" : {
      |    "status" : "not_configured"
      |  },
      |  "connectors" : {
      |    "status" : "not_configured"
      |  },
      |  "acls" : {
      |    "status" : "not_configured"
      |  },
      |  "schemas" : {
      |    "status" : "not_configured"
      |  },
      |  "generatedAt" : "2026-09-03T10:11:13.000Z"
      |}""".stripMargin

  /** Every sample, by file name, for the JVM suite to walk. */
  val all: Map[String, String] =
    Map(
      "app-info.json" -> appInfo,
      "cluster-overview.json" -> clusterOverview,
      "topic-overview.json" -> topicOverview
    )
}
