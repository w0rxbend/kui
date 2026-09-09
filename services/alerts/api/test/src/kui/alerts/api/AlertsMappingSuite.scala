package kui.alerts.api

import java.time.Instant

import munit.FunSuite

import kui.alerts.application.AlertFeed
import kui.alerts.domain.*
import kui.contracts.Section
import kui.contracts.capability.ReasonCode
import kui.kernel.error.InfrastructureError

/** Application types onto the wire: the derived fields, and the three sections a rule's row can carry. */
final class AlertsMappingSuite extends FunSuite {

  private val at = Instant.parse("2026-09-07T12:00:00Z")

  private def event(severity: AlertSeverity, rule: AlertRule): AlertEvent =
    AlertEvent.open(AlertKey(rule, "broker-1:/var"), severity, at, "full", "threshold 80%")

  private def feed(
      events: List[AlertEvent] = Nil,
      reports: List[RuleReport] = AlertRule.All.map(RuleReport(_, RuleOutcome.evaluated)),
      evaluatedAt: Option[Instant] = Some(at)
  ): AlertFeed =
    AlertFeed(
      events = events,
      total = events.size,
      openCount = events.count(_.isOpen),
      openByRule = events.filter(_.isOpen).groupBy(_.key.rule).view.mapValues(_.size).toMap,
      unreadCount = events.size,
      lastReadAt = None,
      evaluatedAt = evaluatedAt,
      reports = reports
    )

  test("an event carries its tone and its glyph, so the browser derives neither") {
    val warning = AlertsMapping.event(event(AlertSeverity.Warning, AlertRule.DiskUsage))

    assertEquals(warning.severity, "warning")
    assertEquals(warning.tone, "warning")
    assertEquals(warning.category, "storage")
    assertEquals(warning.glyph, "storage")
  }

  test("a resolved event is drawn in the success tone whatever severity it opened at") {
    val closed = event(AlertSeverity.Critical, AlertRule.OfflinePartitions)
      .resolvedBy(at, AlertResolutionKind.Acknowledged, Some("ada"))

    val mapped = AlertsMapping.event(closed)

    assertEquals(mapped.severity, "critical")
    assertEquals(mapped.tone, "success")
    assertEquals(mapped.resolution.map(_.kind), Some("acknowledged"))
    assertEquals(mapped.resolution.flatMap(_.by), Some("ada"))
  }

  test("a rule that ran carries ok with its own open count") {
    val open = event(AlertSeverity.Warning, AlertRule.DiskUsage)
    val mapped = AlertsMapping.feed(feed(events = List(open)), at)

    val disk = mapped.rules.find(_.rule == "disk-usage").get

    assertEquals(disk.evaluation.status, "ok")
    assertEquals(disk.evaluation.toOption.map(_.openEvents), Some(1))
    // And a rule with nothing open says zero, which is a measurement rather than an absence.
    assertEquals(mapped.rules.find(_.rule == "offline-partitions").get.evaluation.toOption.map(_.openEvents), Some(0))
  }

  test("a rule that could not run carries unavailable with the failure's own reason") {
    val refused = List(
      RuleReport(
        AlertRule.DiskUsage,
        RuleOutcome.NotEvaluated(InfrastructureError.Unreachable("kafka-admin", "connection refused"))
      )
    )

    val mapped = AlertsMapping.feed(feed(reports = refused), at)
    val disk = mapped.rules.find(_.rule == "disk-usage").get

    assertEquals(disk.evaluation.status, "unavailable")
    disk.evaluation match {
      case Section.Unavailable(reason, message, _) =>
        assertEquals(reason, ReasonCode.UpstreamUnavailable)
        assert(message.contains("kafka-admin"), message)
      case other => fail(s"expected an unavailable section, got $other")
    }
  }

  test("a cluster the rules have never run for is starting, not ok with zero") {
    val mapped = AlertsMapping.feed(feed(reports = Nil, evaluatedAt = None), at)

    assert(mapped.rules.forall(_.evaluation.status == "unavailable"), clue = mapped.rules)
    assertEquals(mapped.evaluatedAt, None)
    mapped.rules.head.evaluation match {
      case Section.Unavailable(reason, message, _) =>
        assertEquals(reason, ReasonCode.Starting)
        assertEquals(message, "KUI has not finished reading this cluster yet")
      case other => fail(s"expected a starting section, got $other")
    }
  }

  test("the unmeasured count travels, so a disk row can say how many it did not judge") {
    val mapped = AlertsMapping.feed(
      feed(reports = List(RuleReport(AlertRule.DiskUsage, RuleOutcome.Evaluated(3)))),
      at
    )

    assertEquals(
      mapped.rules.find(_.rule == "disk-usage").get.evaluation.toOption.map(_.unmeasuredSubjects),
      Some(3)
    )
  }

  test("every rule has a row whether or not it has anything to say") {
    val mapped = AlertsMapping.feed(feed(reports = List(RuleReport(AlertRule.DiskUsage, RuleOutcome.evaluated))), at)

    assertEquals(mapped.rules.map(_.rule), AlertRule.All.map(_.wire))
  }

  test("an id off the URL is refused when it is not the shape this service issues") {
    assert(AlertsMapping.eventId("disk-usage.broker-1.123").isRight)
    assertEquals(AlertsMapping.eventId("../../etc/passwd").left.map(_.code.wire), Left("KUI-VALIDATION"))
    assert(AlertsMapping.eventId("a b").left.exists(_.details.exists(_.field.contains("eventId"))))
  }
}
