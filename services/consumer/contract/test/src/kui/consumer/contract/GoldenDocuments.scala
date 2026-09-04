package kui.consumer.contract

/** The committed sample documents of this contract, as text.
  *
  * They are constants rather than files for the reason `services/cluster/contract` gives: a browser has no
  * filesystem, so the Scala.js half of a cross-compiled suite cannot read `test/resources/golden`. Both
  * platforms assert against these constants, and a JVM-only suite (`GoldenFilesSuite`) asserts that each
  * constant is exactly the file committed beside it — so a constant and a file cannot drift apart without
  * something failing.
  *
  * Every one of them was produced by encoding `ConsumerSamples`, never typed by hand. A golden file written
  * by hand is a second opinion about the wire format, and the point of a golden file is that there is only
  * one.
  */
object GoldenDocuments {

  val groupPage: String =
    """{
      |  "items" : [
      |    {
      |      "groupId" : "orders-indexer",
      |      "state" : "STABLE",
      |      "protocol" : "CONSUMER",
      |      "isSimple" : false,
      |      "members" : 3,
      |      "topics" : 1,
      |      "partitions" : 12,
      |      "coordinatorId" : 2,
      |      "totalLag" : 1240,
      |      "pace" : 415.5,
      |      "excludedPartitions" : 0,
      |      "incomplete" : null
      |    },
      |    {
      |      "groupId" : "billing-replay",
      |      "state" : "EMPTY",
      |      "protocol" : "CLASSIC",
      |      "isSimple" : false,
      |      "members" : 0,
      |      "topics" : 1,
      |      "partitions" : 12,
      |      "coordinatorId" : 2,
      |      "totalLag" : null,
      |      "pace" : null,
      |      "excludedPartitions" : 3,
      |      "incomplete" : {
      |        "membersKnown" : true,
      |        "offsetsKnown" : true,
      |        "endOffsetsKnown" : false,
      |        "note" : "3 of 12 partitions have no leader, so their end offsets could not be read"
      |      }
      |    }
      |  ],
      |  "page" : {
      |    "page" : 1,
      |    "pageSize" : 25,
      |    "totalItems" : 2,
      |    "pageCount" : 1,
      |    "nextPageToken" : null
      |  }
      |}""".stripMargin

  val groupDetail: String =
    """{
      |  "groupId" : "orders-indexer",
      |  "state" : "PREPARING_REBALANCE",
      |  "protocol" : "CONSUMER",
      |  "isSimple" : false,
      |  "partitionAssignor" : "cooperative-sticky",
      |  "coordinatorId" : 2,
      |  "members" : [
      |    {
      |      "memberId" : "consumer-orders-indexer-1-6f1c",
      |      "groupInstanceId" : "indexer-a",
      |      "clientId" : "orders-indexer",
      |      "host" : "/10.1.4.7",
      |      "partitions" : [
      |        "orders-0"
      |      ],
      |      "rebalancing" : true
      |    }
      |  ],
      |  "topics" : [
      |    {
      |      "topic" : "orders",
      |      "lag" : 1240,
      |      "excludedPartitions" : 2,
      |      "partitions" : [
      |        {
      |          "partition" : 0,
      |          "committed" : 41200,
      |          "begin" : 0,
      |          "end" : 42440,
      |          "lag" : 1240,
      |          "anomalies" : [
      |          ],
      |          "memberId" : "consumer-orders-indexer-1-6f1c",
      |          "host" : "/10.1.4.7"
      |        },
      |        {
      |          "partition" : 1,
      |          "committed" : null,
      |          "begin" : 0,
      |          "end" : 9001,
      |          "lag" : null,
      |          "anomalies" : [
      |            "NO_COMMIT"
      |          ],
      |          "memberId" : "consumer-orders-indexer-2-9ab3",
      |          "host" : "/10.1.4.8"
      |        },
      |        {
      |          "partition" : 2,
      |          "committed" : 51000,
      |          "begin" : 0,
      |          "end" : 50000,
      |          "lag" : null,
      |          "anomalies" : [
      |            "COMMITTED_BEYOND_END"
      |          ],
      |          "memberId" : null,
      |          "host" : null
      |        }
      |      ]
      |    }
      |  ],
      |  "totalLag" : 1240,
      |  "excludedPartitions" : 2,
      |  "assignments" : {
      |    "status" : "LAST_SEEN",
      |    "observedAt" : "2026-09-04T09:14:30.000Z"
      |  },
      |  "observedAt" : "2026-09-04T09:15:00.000Z",
      |  "stale" : {
      |    "fetchedAt" : "2026-09-04T09:14:30.000Z",
      |    "reason" : "UPSTREAM_TIMEOUT"
      |  }
      |}""".stripMargin

  val lagDelta: String =
    """{
      |  "changed" : [
      |    {
      |      "groupId" : "orders-indexer",
      |      "totalLag" : 980,
      |      "pace" : 415.5,
      |      "state" : "STABLE",
      |      "members" : 3
      |    },
      |    {
      |      "groupId" : "billing-replay",
      |      "totalLag" : null,
      |      "pace" : null,
      |      "state" : "EMPTY",
      |      "members" : 0
      |    }
      |  ],
      |  "gone" : [
      |    "retired-consumer"
      |  ],
      |  "token" : "v7:1a2b3c4d",
      |  "nextPollMs" : 5000,
      |  "full" : false
      |}""".stripMargin

  val topicConsumers: String =
    """{
      |  "rows" : [
      |    {
      |      "group" : {
      |        "groupId" : "orders-indexer",
      |        "state" : "STABLE",
      |        "protocol" : "CONSUMER",
      |        "isSimple" : false,
      |        "members" : 3,
      |        "topics" : 1,
      |        "partitions" : 12,
      |        "coordinatorId" : 2,
      |        "totalLag" : 1240,
      |        "pace" : 415.5,
      |        "excludedPartitions" : 0,
      |        "incomplete" : null
      |      },
      |      "topicLag" : 1240,
      |      "partitions" : 12,
      |      "dormant" : false
      |    },
      |    {
      |      "group" : {
      |        "groupId" : "billing-replay",
      |        "state" : "EMPTY",
      |        "protocol" : "CLASSIC",
      |        "isSimple" : false,
      |        "members" : 0,
      |        "topics" : 1,
      |        "partitions" : 12,
      |        "coordinatorId" : 2,
      |        "totalLag" : null,
      |        "pace" : null,
      |        "excludedPartitions" : 3,
      |        "incomplete" : {
      |          "membersKnown" : true,
      |          "offsetsKnown" : true,
      |          "endOffsetsKnown" : false,
      |          "note" : "3 of 12 partitions have no leader, so their end offsets could not be read"
      |        }
      |      },
      |      "topicLag" : null,
      |      "partitions" : 12,
      |      "dormant" : true
      |    }
      |  ]
      |}""".stripMargin

  val resetPlan: String =
    """{
      |  "groupId" : "orders-indexer",
      |  "topic" : "orders",
      |  "target" : "OFFSET",
      |  "partitions" : [
      |    {
      |      "partition" : 0,
      |      "current" : 41200,
      |      "proposed" : 42440,
      |      "delta" : 1240
      |    },
      |    {
      |      "partition" : 1,
      |      "current" : null,
      |      "proposed" : 0,
      |      "delta" : null
      |    }
      |  ],
      |  "warnings" : [
      |    {
      |      "kind" : "CLAMPED",
      |      "partition" : 0,
      |      "message" : "9000000 is past the end of partition 0; it was clamped to 42440"
      |    }
      |  ],
      |  "noOp" : false,
      |  "token" : "plan.v1.e30.4f6a9c",
      |  "expiresAt" : "2026-09-04T09:20:00.000Z"
      |}""".stripMargin

  val resetPlanRequest: String =
    """{
      |  "topic" : "orders",
      |  "partitions" : [
      |    0,
      |    1
      |  ],
      |  "target" : "OFFSET",
      |  "timestamp" : null,
      |  "offsets" : {
      |    "0" : 42440,
      |    "1" : 0
      |  },
      |  "shiftBy" : null,
      |  "durationMs" : null
      |}""".stripMargin

  val resetApplyRequest: String =
    """{
      |  "token" : "plan.v1.e30.4f6a9c"
      |}""".stripMargin

  val deletedOffsets: String =
    """{
      |  "groupId" : "billing-replay",
      |  "topic" : "orders",
      |  "partitions" : [
      |    0,
      |    1,
      |    2
      |  ]
      |}""".stripMargin

  val incomplete: String =
    """{
      |  "membersKnown" : true,
      |  "offsetsKnown" : true,
      |  "endOffsetsKnown" : false,
      |  "note" : "3 of 12 partitions have no leader, so their end offsets could not be read"
      |}""".stripMargin


  /** The list as it goes out when the cluster is answering. */
  val groupsResponse: String =
    """{
      |  "groups" : {
      |    "status" : "ok",
      |    "data" : {
      |      "items" : [
      |        {
      |          "groupId" : "orders-indexer",
      |          "state" : "STABLE",
      |          "protocol" : "CONSUMER",
      |          "isSimple" : false,
      |          "members" : 3,
      |          "topics" : 1,
      |          "partitions" : 12,
      |          "coordinatorId" : 2,
      |          "totalLag" : 1240,
      |          "pace" : 415.5,
      |          "excludedPartitions" : 0,
      |          "incomplete" : null
      |        },
      |        {
      |          "groupId" : "billing-replay",
      |          "state" : "EMPTY",
      |          "protocol" : "CLASSIC",
      |          "isSimple" : false,
      |          "members" : 0,
      |          "topics" : 1,
      |          "partitions" : 12,
      |          "coordinatorId" : 2,
      |          "totalLag" : null,
      |          "pace" : null,
      |          "excludedPartitions" : 3,
      |          "incomplete" : {
      |            "membersKnown" : true,
      |            "offsetsKnown" : true,
      |            "endOffsetsKnown" : false,
      |            "note" : "3 of 12 partitions have no leader, so their end offsets could not be read"
      |          }
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
      |    "fetchedAt" : "2026-09-04T09:00:00.000Z"
      |  },
      |  "incompleteCoordinators" : 0
      |}""".stripMargin

  /** The same rows, from a cluster that has stopped answering.
    *
    * This is the document the whole freshness envelope exists for. The rows are identical to the ones above
    * — that is the point: without `status` and `reason` a browser cannot tell this answer from that one, and
    * the lag figures in it are from before the broker died.
    */
  val groupsResponseStale: String =
    """{
      |  "groups" : {
      |    "status" : "stale",
      |    "data" : {
      |      "items" : [
      |        {
      |          "groupId" : "orders-indexer",
      |          "state" : "STABLE",
      |          "protocol" : "CONSUMER",
      |          "isSimple" : false,
      |          "members" : 3,
      |          "topics" : 1,
      |          "partitions" : 12,
      |          "coordinatorId" : 2,
      |          "totalLag" : 1240,
      |          "pace" : 415.5,
      |          "excludedPartitions" : 0,
      |          "incomplete" : null
      |        },
      |        {
      |          "groupId" : "billing-replay",
      |          "state" : "EMPTY",
      |          "protocol" : "CLASSIC",
      |          "isSimple" : false,
      |          "members" : 0,
      |          "topics" : 1,
      |          "partitions" : 12,
      |          "coordinatorId" : 2,
      |          "totalLag" : null,
      |          "pace" : null,
      |          "excludedPartitions" : 3,
      |          "incomplete" : {
      |            "membersKnown" : true,
      |            "offsetsKnown" : true,
      |            "endOffsetsKnown" : false,
      |            "note" : "3 of 12 partitions have no leader, so their end offsets could not be read"
      |          }
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
      |    "fetchedAt" : "2026-09-04T09:00:00.000Z",
      |    "reason" : "UPSTREAM_UNAVAILABLE"
      |  },
      |  "incompleteCoordinators" : 0
      |}""".stripMargin

  /** Every sample, by file name, for the JVM suite to walk. */
  val all: Map[String, String] = Map(
    "group-page.json" -> groupPage,
    "groups-response.json" -> groupsResponse,
    "groups-response-stale.json" -> groupsResponseStale,
    "group-detail.json" -> groupDetail,
    "lag-delta.json" -> lagDelta,
    "topic-consumers.json" -> topicConsumers,
    "reset-plan.json" -> resetPlan,
    "reset-plan-request.json" -> resetPlanRequest,
    "reset-apply-request.json" -> resetApplyRequest,
    "deleted-offsets.json" -> deletedOffsets,
    "incomplete.json" -> incomplete
  )
}
