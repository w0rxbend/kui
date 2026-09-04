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

  val capabilitiesSnapshot: String =
    """{
      |  "entries" : [
      |    {
      |      "key" : {
      |        "service" : "cluster",
      |        "cluster" : null
      |      },
      |      "state" : {
      |        "status" : "available"
      |      },
      |      "updatedAt" : "2026-09-03T10:11:12.000Z",
      |      "name" : null
      |    },
      |    {
      |      "key" : {
      |        "service" : "schema",
      |        "cluster" : "prod-eu"
      |      },
      |      "state" : {
      |        "status" : "not_configured"
      |      },
      |      "updatedAt" : "2026-09-03T10:11:12.000Z",
      |      "name" : null
      |    }
      |  ],
      |  "generatedAt" : "2026-09-03T10:11:13.000Z"
      |}""".stripMargin

  val capabilityChangeUnavailable: String =
    """{
      |  "entry" : {
      |    "key" : {
      |      "service" : "cluster",
      |      "cluster" : null
      |    },
      |    "state" : {
      |      "status" : "unavailable",
      |      "reason" : "UPSTREAM_UNAVAILABLE",
      |      "message" : "readiness probe failed",
      |      "since" : "2026-09-03T10:11:12.000Z"
      |    },
      |    "updatedAt" : "2026-09-03T10:11:13.000Z",
      |    "name" : null
      |  },
      |  "previous" : {
      |    "status" : "available"
      |  }
      |}""".stripMargin

  val serviceCapabilities: String =
    """{
      |  "service" : "schema",
      |  "clusters" : {
      |    "prod-eu" : {
      |      "configured" : true,
      |      "features" : [
      |        "SCHEMA_REGISTRY"
      |      ],
      |      "status" : "available",
      |      "name" : null,
      |      "reason" : null
      |    },
      |    "staging" : {
      |      "configured" : false,
      |      "features" : [
      |      ],
      |      "status" : "not_configured",
      |      "name" : null,
      |      "reason" : null
      |    }
      |  }
      |}""".stripMargin

  val sseDone: String =
    """{
      |  "reason" : "limit",
      |  "cursor" : "eyJ2IjoxfQ.c2ln"
      |}""".stripMargin

  val sseError: String =
    """{
      |  "code" : "KUI-UPSTREAM-UNAVAILABLE",
      |  "message" : "schema-registry could not be reached",
      |  "details" : [
      |  ],
      |  "correlationId" : "3b1fa9c2e4d54f0b",
      |  "timestamp" : "2026-09-03T10:11:12.000Z",
      |  "retryable" : true
      |}""".stripMargin

  val readinessReportDegraded: String =
    """{
      |  "ready" : false,
      |  "checks" : [
      |    {
      |      "name" : "config",
      |      "healthy" : true,
      |      "detail" : null
      |    },
      |    {
      |      "name" : "schema-registry",
      |      "healthy" : false,
      |      "detail" : "connection refused"
      |    },
      |    {
      |      "name" : "connect",
      |      "healthy" : false,
      |      "detail" : "timeout"
      |    }
      |  ],
      |  "at" : "2026-09-03T10:11:12.000Z"
      |}""".stripMargin

  /** Every constant above, by the file name it is committed under. */
  val all: List[(String, String)] = List(
    "error-envelope-validation.json"        -> errorEnvelopeValidation,
    "error-envelope-upstream.json"          -> errorEnvelopeUpstream,
    "capabilities-snapshot.json"            -> capabilitiesSnapshot,
    "capability-change-unavailable.json"    -> capabilityChangeUnavailable,
    "service-capabilities.json"             -> serviceCapabilities,
    "sse-done.json"                         -> sseDone,
    "sse-error.json"                        -> sseError,
    "readiness-report-degraded.json"        -> readinessReportDegraded
  )
}
