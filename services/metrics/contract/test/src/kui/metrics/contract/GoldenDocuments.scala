package kui.metrics.contract

/** The committed sample documents of the metrics wire, as text.
  *
  * ==Why this file exists at all, and why it exists two milestones late==
  *
  * Every other contract module in this repository has one — `libs/contracts-core`, cluster, topic, consumer,
  * message and gateway — and this one did not. Four DTOs went onto the wire with nothing recording what an
  * encoded instance of them looks like, and two of them were read by a browser that had been written against
  * a different shape: the top-level `Section` key matched on both sides, so the decode succeeded, answered an
  * empty array, and the card drew "the metrics source answered and named no producers" over a source that
  * had named five. Both sides were unit-tested. Both sides were green. What neither side had was a document
  * they were both asserted against, which is what these are.
  *
  * ==Constants rather than files==
  *
  * A browser has no filesystem, so the Scala.js half of a cross-compiled suite cannot read
  * `test/resources/golden`. Both platforms assert against these constants and a JVM-only suite
  * (`GoldenFilesSuite`) asserts that each constant is exactly the file committed beside it, so a constant and
  * a file cannot drift apart without something failing.
  *
  * ==And the files matter beyond this module==
  *
  * `frontend/packages/shell/src/overview/wire.golden.test.ts` reads three of them off disk and runs them
  * through the browser's own fetchers. That is the assertion M7's exit criterion was rewritten to require:
  * a document rendered by the **server's own encoder** decoding in the **browser**, rather than two
  * hand-written literals that agree with the code beside them and with nothing else.
  */
object GoldenDocuments {

  /** A throughput answer with a gap in it, which is the shape the whole screen is built around.
    *
    * Three buckets of an axis: a measured rate, a step nobody sampled, and a measured **zero**. The middle
    * one is `null` and the third is `0.0`, and they are different claims — "KUI was not looking" against
    * "the cluster was idle" — which no reader can invent from a number.
    */
  val throughputResponse: String =
    """{
      |  "throughput" : {
      |    "status" : "ok",
      |    "data" : {
      |      "range" : "24h",
      |      "from" : "2026-09-06T11:45:00Z",
      |      "to" : "2026-09-06T12:00:00Z",
      |      "stepSeconds" : 300,
      |      "buckets" : [
      |        {
      |          "startingAt" : "2026-09-06T11:45:00Z",
      |          "bytesInPerSecond" : 124800.5,
      |          "bytesOutPerSecond" : 249600.75,
      |          "recordsPerSecond" : 1420.75
      |        },
      |        {
      |          "startingAt" : "2026-09-06T11:50:00Z",
      |          "bytesInPerSecond" : null,
      |          "bytesOutPerSecond" : null,
      |          "recordsPerSecond" : null
      |        },
      |        {
      |          "startingAt" : "2026-09-06T11:55:00Z",
      |          "bytesInPerSecond" : 0.0,
      |          "bytesOutPerSecond" : 0.0,
      |          "recordsPerSecond" : 0.0
      |        }
      |      ]
      |    },
      |    "fetchedAt" : "2026-09-06T12:00:00.000Z"
      |  }
      |}""".stripMargin

  /** The same axis for the latency card, with one step whose produce percentile arrived and whose fetch
    * percentile did not — an ordinary exporter whitelist, and a step that is not "unmeasured".
    */
  val latencyResponse: String =
    """{
      |  "latency" : {
      |    "status" : "ok",
      |    "data" : {
      |      "window" : "24h",
      |      "from" : "2026-09-06T11:45:00Z",
      |      "to" : "2026-09-06T12:00:00Z",
      |      "stepSeconds" : 300,
      |      "buckets" : [
      |        {
      |          "startingAt" : "2026-09-06T11:45:00Z",
      |          "produceP99Millis" : 9.0,
      |          "fetchP99Millis" : 502.0
      |        },
      |        {
      |          "startingAt" : "2026-09-06T11:50:00Z",
      |          "produceP99Millis" : 7.5,
      |          "fetchP99Millis" : null
      |        },
      |        {
      |          "startingAt" : "2026-09-06T11:55:00Z",
      |          "produceP99Millis" : null,
      |          "fetchP99Millis" : null
      |        }
      |      ]
      |    },
      |    "fetchedAt" : "2026-09-06T12:00:00.000Z"
      |  }
      |}""".stripMargin

  /** The request-handlers document, and the one the browser was reading wrongly.
    *
    * Two ratios in `0..1` and a list of queue lengths — not a `readings` array of `{id, label, ratio}`,
    * which is what `metrics.ts` decoded for a whole milestone while every suite on both sides stayed green.
    */
  val requestHandlersResponse: String =
    """{
      |  "requestHandlers" : {
      |    "status" : "ok",
      |    "data" : {
      |      "requestHandlerIdleRatio" : 0.8912,
      |      "networkProcessorIdleRatio" : 0.7104,
      |      "purgatory" : [
      |        {
      |          "operation" : "Fetch",
      |          "delayedRequests" : 481
      |        },
      |        {
      |          "operation" : "Produce",
      |          "delayedRequests" : 0
      |        }
      |      ]
      |    },
      |    "fetchedAt" : "2026-09-06T12:00:00.000Z"
      |  }
      |}""".stripMargin

  /** The same reading an hour after the exporter stopped answering.
    *
    * `stale` is the fourth section state and this service is the reason it is not dead render code: the
    * figures are true, `fetchedAt` is when they were true, and the card draws the sentence rather than a
    * current-looking gauge. `UPSTREAM_UNAVAILABLE` rather than a timeout: nothing answered at all.
    */
  val requestHandlersResponseStale: String =
    """{
      |  "requestHandlers" : {
      |    "status" : "stale",
      |    "data" : {
      |      "requestHandlerIdleRatio" : 0.8912,
      |      "networkProcessorIdleRatio" : 0.7104,
      |      "purgatory" : [
      |      ]
      |    },
      |    "fetchedAt" : "2026-09-06T11:00:00.000Z",
      |    "reason" : "UPSTREAM_UNAVAILABLE"
      |  }
      |}""".stripMargin

  /** A ratio the exporter served the name of and not the value.
    *
    * `null` and not `0.0`, and the difference is a saturated broker against a broker nobody measured. The
    * purgatory list is empty for the other reason the same document can be thin: the whitelist has the two
    * idle percentages and no `DelayedOperationPurgatory` rule.
    */
  val requestHandlersOneAbsent: String =
    """{
      |  "requestHandlers" : {
      |    "status" : "ok",
      |    "data" : {
      |      "requestHandlerIdleRatio" : null,
      |      "networkProcessorIdleRatio" : 0.7104,
      |      "purgatory" : [
      |      ]
      |    },
      |    "fetchedAt" : "2026-09-06T12:00:00.000Z"
      |  }
      |}""".stripMargin

  /** The top-producers document, and the other one the browser was reading wrongly.
    *
    * `measuredBy` says what the rows are — the card's title is drawn from it rather than from the design's
    * word — and the rows are `{topic, bytesInPerSecond}`, not `entries` of `{clientId, bytesPerSecond}`.
    * `internalTopicsExcluded` is two: `__consumer_offsets` and `__transaction_state` were served by the
    * exporter and are not producers, and a list that dropped them silently would be one nobody could
    * reconcile against the exporter it came from.
    */
  val topProducersResponse: String =
    """{
      |  "producers" : {
      |    "status" : "ok",
      |    "data" : {
      |      "measuredBy" : "topic",
      |      "topics" : [
      |        {
      |          "topic" : "orders.payments",
      |          "bytesInPerSecond" : 5400000.0
      |        },
      |        {
      |          "topic" : "analytics.clicks",
      |          "bytesInPerSecond" : 3100000.0
      |        },
      |        {
      |          "topic" : "audit.trail",
      |          "bytesInPerSecond" : 0.0
      |        }
      |      ],
      |      "internalTopicsExcluded" : 2
      |    },
      |    "fetchedAt" : "2026-09-06T12:00:00.000Z"
      |  }
      |}""".stripMargin

  /** The exporter published the family and no line carrying a topic.
    *
    * An answer and not a failure, and the two causes an exposition cannot tell apart — nothing is producing,
    * or the ruleset has a broker-wide rule and no per-topic one — are both named by the card's sentence
    * rather than one of them being picked here.
    */
  val topProducersEmpty: String =
    """{
      |  "producers" : {
      |    "status" : "ok",
      |    "data" : {
      |      "measuredBy" : "topic",
      |      "topics" : [
      |      ],
      |      "internalTopicsExcluded" : 0
      |    },
      |    "fetchedAt" : "2026-09-06T12:00:00.000Z"
      |  }
      |}""".stripMargin

  /** A deployment that named no metrics source. Not a failure, and drawn as the product's own sentence. */
  val topProducersNotConfigured: String =
    """{
      |  "producers" : {
      |    "status" : "not_configured"
      |  }
      |}""".stripMargin

  /** The record-size document: a mean and the two rates it was divided from, and no percentile anywhere. */
  val recordSizeResponse: String =
    """{
      |  "recordSize" : {
      |    "status" : "ok",
      |    "data" : {
      |      "meanBytes" : 128.0,
      |      "bytesInPerSecond" : 1024.0,
      |      "recordsPerSecond" : 8.0
      |    },
      |    "fetchedAt" : "2026-09-06T12:00:00.000Z"
      |  }
      |}""".stripMargin

  /** An exporter that answers and serves no `BytesInPerSec` at all: a whitelist to widen, not a wait. */
  val recordSizeUnavailable: String =
    """{
      |  "recordSize" : {
      |    "status" : "unavailable",
      |    "reason" : "UPSTREAM_UNAVAILABLE",
      |    "message" : "the metrics exporter answered and 3 reading(s) of it carry no bytes-in and records-in rate to divide",
      |    "since" : "2026-09-06T12:00:00.000Z"
      |  }
      |}""".stripMargin

  val all: Map[String, String] = Map(
    "throughput-response.json" -> throughputResponse,
    "latency-response.json" -> latencyResponse,
    "request-handlers-response.json" -> requestHandlersResponse,
    "request-handlers-response-stale.json" -> requestHandlersResponseStale,
    "request-handlers-one-absent.json" -> requestHandlersOneAbsent,
    "top-producers-response.json" -> topProducersResponse,
    "top-producers-empty.json" -> topProducersEmpty,
    "top-producers-not-configured.json" -> topProducersNotConfigured,
    "record-size-response.json" -> recordSizeResponse,
    "record-size-unavailable.json" -> recordSizeUnavailable
  )
}
