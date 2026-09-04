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

  val clustersResponse: String =
    """{
      |  "items" : [
      |    {
      |      "id" : "prod-eu",
      |      "name" : "Production EU",
      |      "readOnly" : false,
      |      "bootstrapServers" : "broker-1.example.com:9093,broker-2.example.com:9093",
      |      "security" : {
      |        "protocol" : "SASL_SSL",
      |        "mechanism" : "SCRAM-SHA-512",
      |        "truststoreConfigured" : true,
      |        "keystoreConfigured" : false
      |      },
      |      "summary" : {
      |        "status" : "ok",
      |        "data" : {
      |          "kafkaClusterId" : "MkU3OEVBNTcwNTJENDM2Qk",
      |          "version" : "4.0.0",
      |          "controllerId" : 1,
      |          "controllerKind" : "kraft",
      |          "brokerCount" : 3,
      |          "onlinePartitionCount" : null,
      |          "offlinePartitionCount" : null,
      |          "underReplicatedPartitionCount" : null,
      |          "totalDiskUsageBytes" : 549755813888,
      |          "features" : [
      |            "DESCRIBE_LOG_DIRS",
      |            "DESCRIBE_QUORUM"
      |          ],
      |          "scrapedAt" : "2026-09-03T10:11:12.000Z"
      |        },
      |        "fetchedAt" : "2026-09-03T10:11:12.000Z"
      |      }
      |    },
      |    {
      |      "id" : "dead-cluster",
      |      "name" : "Decommissioned",
      |      "readOnly" : true,
      |      "bootstrapServers" : "gone.example.com:9092",
      |      "security" : {
      |        "protocol" : "PLAINTEXT",
      |        "mechanism" : null,
      |        "truststoreConfigured" : false,
      |        "keystoreConfigured" : false
      |      },
      |      "summary" : {
      |        "status" : "unavailable",
      |        "reason" : "UPSTREAM_UNAVAILABLE",
      |        "message" : "connection refused",
      |        "since" : "2026-09-03T10:10:00.000Z"
      |      }
      |    }
      |  ],
      |  "generatedAt" : "2026-09-03T10:11:13.000Z"
      |}""".stripMargin

  val brokersResponse: String =
    """{
      |  "brokers" : {
      |    "status" : "stale",
      |    "data" : [
      |      {
      |        "id" : 1,
      |        "host" : "broker-1.example.com",
      |        "port" : 9093,
      |        "rack" : "eu-west-1a",
      |        "isController" : true,
      |        "partitionCount" : null,
      |        "leaderCount" : null,
      |        "replicaCount" : 42,
      |        "replicaSkewPercent" : 3.5,
      |        "leaderSkewPercent" : null,
      |        "diskUsageBytes" : 183251937970,
      |        "segmentCount" : 128
      |      }
      |    ],
      |    "fetchedAt" : "2026-09-03T10:11:12.000Z",
      |    "reason" : "UPSTREAM_TIMEOUT"
      |  }
      |}""".stripMargin

  val brokerConfigsResponse: String =
    """{
      |  "configs" : {
      |    "status" : "ok",
      |    "data" : [
      |      {
      |        "name" : "log.retention.hours",
      |        "value" : "168",
      |        "source" : "STATIC_BROKER_CONFIG",
      |        "isSensitive" : false,
      |        "isReadOnly" : true,
      |        "documentation" : null,
      |        "synonyms" : [
      |          "log.retention.hours"
      |        ]
      |      },
      |      {
      |        "name" : "listener.name.internal.ssl.key.password",
      |        "value" : null,
      |        "source" : "STATIC_BROKER_CONFIG",
      |        "isSensitive" : true,
      |        "isReadOnly" : true,
      |        "documentation" : null,
      |        "synonyms" : [
      |        ]
      |      }
      |    ],
      |    "fetchedAt" : "2026-09-03T10:11:12.000Z"
      |  }
      |}""".stripMargin

  val logDirsResponse: String =
    """{
      |  "logDirs" : {
      |    "status" : "unavailable",
      |    "reason" : "UPSTREAM_AUTH",
      |    "message" : "the cluster refused describeLogDirs: ClusterAuthorizationException",
      |    "since" : "2026-09-03T10:11:12.000Z"
      |  }
      |}""".stripMargin

  val refreshAccepted: String =
    """{
      |  "clusterId" : "prod-eu",
      |  "requestedAt" : "2026-09-03T10:11:12.000Z"
      |}""".stripMargin

  val clusterProfile: String =
    """{
      |  "id" : "prod-eu",
      |  "name" : "Production EU",
      |  "version" : 7,
      |  "readOnly" : false,
      |  "bootstrapServers" : "broker-1.example.com:9093,broker-2.example.com:9093",
      |  "security" : {
      |    "kind" : "sasl",
      |    "protocol" : "SASL_SSL",
      |    "mechanism" : {
      |      "kind" : "scram-sha-512",
      |      "username" : "kui-service",
      |      "password" : "hunter2"
      |    },
      |    "tls" : {
      |      "truststore" : {
      |        "source" : {
      |          "kind" : "path",
      |          "path" : "/etc/kui/truststore.p12"
      |        },
      |        "password" : "truststore-pass",
      |        "storeType" : "PKCS12"
      |      },
      |      "keystore" : null,
      |      "verifyHostname" : true,
      |      "enabledProtocols" : null,
      |      "cipherSuites" : null
      |    }
      |  },
      |  "properties" : {
      |    "ssl.endpoint.identification.algorithm" : {
      |      "sensitive" : false,
      |      "value" : "https"
      |    },
      |    "ssl.truststore.password" : {
      |      "sensitive" : true,
      |      "value" : "truststore-pass"
      |    }
      |  },
      |  "admin" : {
      |    "requestTimeoutMs" : 30000,
      |    "apiTimeoutMs" : 60000,
      |    "topicChunkSize" : 200,
      |    "partitionChunkSize" : 200,
      |    "groupChunkSize" : 50,
      |    "parallelism" : 4,
      |    "metadataRefreshMs" : 30000,
      |    "capabilityRefreshMs" : 3600000
      |  },
      |  "updatedAt" : "2026-09-03T10:11:12.000Z"
      |}""".stripMargin

  val clusterChange: String =
    """{
      |  "id" : "prod-eu",
      |  "version" : 8,
      |  "change" : "updated",
      |  "at" : "2026-09-03T10:11:12.000Z"
      |}""".stripMargin

  /** The one request shape in KUI that carries credentials.
    *
    * The golden file holds them in the clear because that is what a caller sends; the point the suite makes
    * is that nothing ever encodes them back out, and that a `Secret` on a log line prints as `****`.
    */
  val clusterWriteRequest: String =
    """{
      |  "name" : "Production EU",
      |  "readOnly" : false,
      |  "bootstrapServers" : "broker-1.example.com:9093,broker-2.example.com:9093",
      |  "security" : {
      |    "protocol" : "SASL_SSL",
      |    "mechanism" : "SCRAM-SHA-512",
      |    "username" : "kui",
      |    "password" : "hunter2",
      |    "truststore" : {
      |      "base64" : "MIIB...",
      |      "password" : "truststore-secret"
      |    },
      |    "keystore" : null,
      |    "verifyHostname" : true
      |  },
      |  "properties" : {
      |    "ssl.endpoint.identification.algorithm" : "https"
      |  },
      |  "admin" : {
      |    "timeoutMs" : 15000,
      |    "batchSize" : 200,
      |    "parallelism" : 4
      |  }
      |}""".stripMargin

  /** Every sample, by file name, for the JVM suite to walk. */
  val all: Map[String, String] = Map(
    "clusters-response.json" -> clustersResponse,
    "brokers-response.json" -> brokersResponse,
    "broker-configs-response.json" -> brokerConfigsResponse,
    "log-dirs-response.json" -> logDirsResponse,
    "refresh-accepted.json" -> refreshAccepted,
    "cluster-profile.json" -> clusterProfile,
    "cluster-change.json" -> clusterChange,
    "cluster-write-request.json" -> clusterWriteRequest
  )
}
