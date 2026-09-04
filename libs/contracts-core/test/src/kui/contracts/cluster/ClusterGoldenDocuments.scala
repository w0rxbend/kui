package kui.contracts.cluster

/** The committed sample cluster documents, as text.
  *
  * Same device, and same reason, as `kui.contracts.GoldenDocuments`: a browser has no filesystem, so a
  * Scala.js suite cannot read the files under `test/resources/golden`. Both platforms assert against these
  * constants, and the JVM-only `GoldenFilesSuite` asserts each constant is byte for byte the file committed
  * beside it, so the two copies cannot drift.
  */
object ClusterGoldenDocuments {

  val clusterSecurity: String =
    """{
      |  "protocol" : "SASL_SSL",
      |  "mechanism" : "SCRAM-SHA-512",
      |  "truststoreConfigured" : true,
      |  "keystoreConfigured" : false
      |}""".stripMargin

  val clusterSummary: String =
    """{
      |  "kafkaClusterId" : "MkU3OEVBNTcwNTJENDM2Qk",
      |  "version" : "4.0.0",
      |  "controllerId" : 1,
      |  "controllerKind" : "kraft",
      |  "brokerCount" : 3,
      |  "onlinePartitionCount" : null,
      |  "offlinePartitionCount" : null,
      |  "underReplicatedPartitionCount" : null,
      |  "totalDiskUsageBytes" : 549755813888,
      |  "features" : [
      |    "DESCRIBE_LOG_DIRS",
      |    "DESCRIBE_QUORUM"
      |  ],
      |  "scrapedAt" : "2026-09-03T10:11:12.000Z"
      |}""".stripMargin

  val clusterRow: String =
    """{
      |  "id" : "prod-eu",
      |  "name" : "Production EU",
      |  "readOnly" : false,
      |  "bootstrapServers" : "broker-1.example.com:9093,broker-2.example.com:9093",
      |  "security" : {
      |    "protocol" : "SASL_SSL",
      |    "mechanism" : "SCRAM-SHA-512",
      |    "truststoreConfigured" : true,
      |    "keystoreConfigured" : false
      |  },
      |  "summary" : {
      |    "status" : "ok",
      |    "data" : {
      |      "kafkaClusterId" : "MkU3OEVBNTcwNTJENDM2Qk",
      |      "version" : "4.0.0",
      |      "controllerId" : 1,
      |      "controllerKind" : "kraft",
      |      "brokerCount" : 3,
      |      "onlinePartitionCount" : null,
      |      "offlinePartitionCount" : null,
      |      "underReplicatedPartitionCount" : null,
      |      "totalDiskUsageBytes" : 549755813888,
      |      "features" : [
      |        "DESCRIBE_LOG_DIRS",
      |        "DESCRIBE_QUORUM"
      |      ],
      |      "scrapedAt" : "2026-09-03T10:11:12.000Z"
      |    },
      |    "fetchedAt" : "2026-09-03T10:11:12.000Z"
      |  }
      |}""".stripMargin

  val broker: String =
    """{
      |  "id" : 1,
      |  "host" : "broker-1.example.com",
      |  "port" : 9093,
      |  "rack" : "eu-west-1a",
      |  "isController" : true,
      |  "partitionCount" : null,
      |  "leaderCount" : null,
      |  "replicaCount" : 42,
      |  "replicaSkewPercent" : 3.5,
      |  "leaderSkewPercent" : null,
      |  "diskUsageBytes" : 183251937970,
      |  "segmentCount" : 128
      |}""".stripMargin

  val brokerConfigEntry: String =
    """{
      |  "name" : "log.retention.hours",
      |  "value" : "168",
      |  "source" : "STATIC_BROKER_CONFIG",
      |  "isSensitive" : false,
      |  "isReadOnly" : true,
      |  "documentation" : "The number of hours to keep a log file before deleting it",
      |  "synonyms" : [
      |    "log.retention.hours",
      |    "log.retention.ms"
      |  ]
      |}""".stripMargin

  val logDir: String =
    """{
      |  "brokerId" : 1,
      |  "path" : "/var/lib/kafka/data",
      |  "error" : null,
      |  "totalBytes" : 549755813888,
      |  "usableBytes" : 366503875925,
      |  "topicCount" : 12,
      |  "partitionCount" : 48
      |}""".stripMargin

  /** Every constant above, by the file name it is committed under. */
  val all: List[(String, String)] = List(
    "cluster-security.json" -> clusterSecurity,
    "cluster-summary.json" -> clusterSummary,
    "cluster-row.json" -> clusterRow,
    "broker.json" -> broker,
    "broker-config-entry.json" -> brokerConfigEntry,
    "log-dir.json" -> logDir
  )
}
