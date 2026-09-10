package kui.alerts.contract

import java.time.Instant

import io.circe.Json
import io.circe.syntax.*

import kui.alerts.contract.dto.*
import kui.contracts.Section
import kui.contracts.capability.ReasonCode

/** The instances every document in this module is rendered from.
  *
  * One set of values, encoded by the suite and decoded back by it, so that the golden files and the
  * round-trip assertions cannot describe two different documents. House rule 12: the case that binds the two
  * sides of a wire decodes the **encoder's own output** rather than a hand-written literal, which is exactly
  * what wave 5's metrics wire did not do — two packets, two hand-written shapes, both green, and the card
  * drawing nothing against a real broker.
  */
object AlertDocuments {

  val fetchedAt: Instant = Instant.parse("2026-09-03T10:11:12Z")

  val openedAt: Instant = Instant.parse("2026-09-03T09:11:12Z")

  /** An open critical row: the offline-partition rule firing on the cluster itself. */
  val offline: AlertEventDto = AlertEventDto(
    id = "offline-partitions.cluster.1756890672000",
    severity = "critical",
    tone = "danger",
    category = "partition",
    glyph = "partition",
    openedAt = openedAt,
    lastSeenAt = fetchedAt,
    title = "2 partitions offline",
    detail = "no leader · threshold 1",
    resolution = None
  )

  /** A resolved warning row. Its `tone` is `success` and its `severity` is still `warning`, which is the
    * pair `SCREENS-V4.md` §3.8's fourth dot needs and the reason the two fields are both on the wire.
    */
  val resolved: AlertEventDto = AlertEventDto(
    id = "disk-usage.broker-1-.var.lib.kafka.1756887072000",
    severity = "warning",
    tone = "success",
    category = "storage",
    glyph = "storage",
    openedAt = openedAt.minusSeconds(3600L),
    lastSeenAt = openedAt,
    title = "Log directory /var/lib/kafka is 82% full on broker 1",
    detail = "threshold 80% · broker-1:/var/lib/kafka",
    resolution = Some(AlertResolutionDto(fetchedAt, "acknowledged", Some("ada")))
  )

  private def evaluated(rule: String, category: String, open: Int, unmeasured: Int): AlertRuleReportDto =
    AlertRuleReportDto(rule, category, Section.Ok(RuleEvaluationDto(open, unmeasured), fetchedAt))

  /** A feed with something in it, and every rule reporting. */
  val feed: AlertFeedResponse = AlertFeedResponse(
    Section.Ok(
      AlertFeedDto(
        items = List(offline, resolved),
        total = 2,
        openCount = 1,
        unreadCount = 1,
        lastReadAt = Some(openedAt),
        evaluatedAt = Some(fetchedAt),
        rules = List(
          evaluated("offline-partitions", "partition", 1, 0),
          evaluated("under-replicated-partitions", "replication", 0, 0),
          evaluated("stuck-rebalance", "rebalance", 0, 0),
          // One directory whose broker reported no capacity: skipped rather than judged, and counted so
          // the row can say so.
          evaluated("disk-usage", "storage", 0, 1)
        )
      ),
      fetchedAt
    )
  )

  /** The same feed with one rule that could not run.
    *
    * The row is `unavailable` with a reason and a message, **not** `ok` with zero. That distinction is the
    * whole reason the sections are per rule, and this document is what a browser is written against.
    */
  val blindRule: AlertFeedResponse = AlertFeedResponse(
    Section.Ok(
      AlertFeedDto(
        items = Nil,
        total = 0,
        openCount = 0,
        unreadCount = 0,
        lastReadAt = None,
        evaluatedAt = Some(fetchedAt),
        rules = List(
          AlertRuleReportDto(
            "offline-partitions",
            "partition",
            Section.Unavailable(
              ReasonCode.UpstreamUnavailable,
              "kafka-admin could not be reached",
              Some(fetchedAt)
            )
          ),
          evaluated("under-replicated-partitions", "replication", 0, 0),
          evaluated("stuck-rebalance", "rebalance", 0, 0),
          evaluated("disk-usage", "storage", 0, 0)
        )
      ),
      fetchedAt
    )
  )

  /** A feed that has never been evaluated: `starting`, and not a clean bill of health. */
  val unevaluated: AlertFeedResponse = AlertFeedResponse(
    Section.Ok(
      AlertFeedDto(
        items = Nil,
        total = 0,
        openCount = 0,
        unreadCount = 0,
        lastReadAt = None,
        evaluatedAt = None,
        rules = List(
          AlertRuleReportDto(
            "offline-partitions",
            "partition",
            Section.Unavailable(ReasonCode.Starting, "KUI has not finished reading this cluster yet", None)
          )
        )
      ),
      fetchedAt
    )
  )

  val acknowledgement: AcknowledgementDto = AcknowledgementDto(resolved, 1)

  val change: AlertChangeDto = AlertChangeDto("prod-eu", 1, fetchedAt)

  /** One SSE frame, as `SseEvent.data` puts it on the wire: the event **name** and the payload.
    *
    * The name is here because it is the one part of this wire that nothing could check. `AlertsRoutes`
    * encodes frames under [[kui.alerts.contract.dto.AlertChangeDto.EventName]]; the browser registers its
    * listener under a `ALERTS_EVENT_NAME` constant typed into `frontend/packages/kernel`, and the two are
    * a hand-copied pair. Renaming the event server-side leaves every gate in this repository green and
    * stops the bell updating, because `tools/error-codes` writes the five SSE names by hand and there is
    * no `SseEventName.Alerts` for either side to read.
    *
    * So the name travels in a committed document instead. The browser's constant is asserted against this
    * file's `event` field, which makes the mirror a checked one — the same move that closed the metrics
    * wire in M7, applied to the one field of this wire that is not a DTO.
    */
  val streamFrame: Json =
    Json.obj("event" -> Json.fromString(AlertChangeDto.EventName), "data" -> change.asJson)

  /** Every committed document, by the file name it is committed under.
    *
    * The roster is here rather than repeated in each suite so that one list is what
    * `AlertResponsesSuite` asserts, what `GoldenFilesSuite` reconciles against the directory, and what a
    * reader consults to find out which files the browser is entitled to expect.
    */
  val all: List[(String, Json)] = List(
    "alerts-feed-response.json" -> feed.asJson,
    "alerts-feed-blind-rule.json" -> blindRule.asJson,
    "alerts-feed-unevaluated.json" -> unevaluated.asJson,
    "alerts-acknowledgement.json" -> acknowledgement.asJson,
    "alerts-change.json" -> change.asJson,
    "alerts-stream-frame.json" -> streamFrame
  )
}
