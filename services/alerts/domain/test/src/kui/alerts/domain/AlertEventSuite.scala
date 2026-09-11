package kui.alerts.domain

import munit.FunSuite

/** The event's identity, its tone and the order a feed draws it in. */
final class AlertEventSuite extends FunSuite {

  import AlertFixtures.*

  private val key = AlertKey(AlertRule.DiskUsage, "broker-1:/var/lib/kafka/data")

  test("an id is derived from the key and the opening instant, so two passes name one event") {
    assertEquals(AlertEventId.of(key, now), AlertEventId.of(key, now))
  }

  test("subjects that share a sanitised spelling still have distinct ids") {
    val nested = AlertKey(AlertRule.DiskUsage, "broker-1:/a/b")
    val dashed = AlertKey(AlertRule.DiskUsage, "broker-1:/a-b")

    assertNotEquals(AlertEventId.of(nested, now), AlertEventId.of(dashed, now))
  }

  test("a long subject produces a bounded id that the acknowledgement path accepts") {
    val long = AlertKey(AlertRule.DiskUsage, s"broker-1:/${"segment/" * 100}")
    val id = AlertEventId.of(long, now).value

    assert(id.length <= AlertEventId.MaxLength, clue = s"${id.length}: $id")
    assertEquals(AlertEventId.from(id).map(_.value), Some(id))
  }

  test("short path-safe subjects keep their existing wire representation") {
    val cluster = AlertEventId.of(AlertKey.cluster(AlertRule.OfflinePartitions), now).value
    val group = AlertEventId.of(AlertKey(AlertRule.StuckRebalance, "payments-consumer"), now).value

    assertEquals(cluster, s"offline-partitions.cluster.${now.toEpochMilli}")
    assertEquals(group, s"stuck-rebalance.payments-consumer.${now.toEpochMilli}")
  }

  test("the parser still accepts ids issued by the earlier sanitised representation") {
    val legacy = s"disk-usage.broker-1--var-lib-kafka-data.${now.toEpochMilli}"

    assertEquals(AlertEventId.from(legacy).map(_.value), Some(legacy))
  }

  test("a condition that clears and returns is a second event with its own age") {
    assertNotEquals(AlertEventId.of(key, now), AlertEventId.of(key, now.plusSeconds(1)))
  }

  test("an id is one path segment, so a log directory's slashes cannot become a 404") {
    val id = AlertEventId.of(key, now).value

    assert(!id.contains('/'), clue = id)
    assert(!id.contains(':'), clue = id)
    assertEquals(AlertEventId.from(id).map(_.value), Some(id))
  }

  test("an id with a character this service never issues is refused rather than looked up") {
    assertEquals(AlertEventId.from("disk-usage/../../etc/passwd"), None)
    assertEquals(AlertEventId.from(""), None)
    assertEquals(AlertEventId.from("x" * (AlertEventId.MaxLength + 1)), None)
  }

  test("a resolved row is drawn in the success tone whatever severity it opened at") {
    // The fourth dot in SCREENS-V4 §3.8, and the reason `AlertSeverity` has no `Success` case: the row is
    // telling the reader it is over, which is a fact about the resolution and not about the severity.
    val critical = AlertEvent.open(key, AlertSeverity.Critical, now, "full", "detail")

    assertEquals(critical.tone, "danger")
    assertEquals(critical.resolvedBy(now, AlertResolutionKind.Cleared, None).tone, AlertEvent.ResolvedTone)
    assertEquals(AlertEvent.ResolvedTone, "success")
  }

  test("an open event has no resolution and a resolved one names how it closed") {
    val event = AlertEvent.open(key, AlertSeverity.Warning, now, "full", "detail")

    assert(event.isOpen)

    val acknowledged = event.resolvedBy(now, AlertResolutionKind.Acknowledged, Some("ada"))

    assert(!acknowledged.isOpen)
    assertEquals(acknowledged.resolution.map(_.kind), Some(AlertResolutionKind.Acknowledged))
    assertEquals(acknowledged.resolution.flatMap(_.by), Some("ada"))
  }

  test("a cleared resolution names nobody, because nobody did it") {
    val cleared =
      AlertEvent
        .open(key, AlertSeverity.Warning, now, "full", "detail")
        .resolvedBy(now, AlertResolutionKind.Cleared, None)

    assertEquals(cleared.resolution.flatMap(_.by), None)
  }

  test("the feed's order is newest first and is stable for two events opened in one millisecond") {
    val older =
      AlertEvent.open(AlertKey.cluster(AlertRule.OfflinePartitions), AlertSeverity.Critical, now, "a", "")
    val newer = AlertEvent.open(key, AlertSeverity.Warning, now.plusSeconds(30), "b", "")
    val twin = AlertEvent.open(
      AlertKey(AlertRule.StuckRebalance, "payments"),
      AlertSeverity.Warning,
      now,
      "c",
      ""
    )

    assertEquals(List(older, newer, twin).sorted.head, newer)
    assertEquals(List(older, twin).sorted, List(twin, older).sorted)
  }

  test("every severity maps to a design tone and every category to a glyph, with no two the same") {
    assertEquals(AlertSeverity.All.map(_.tone).distinct.size, AlertSeverity.All.size)
    assertEquals(AlertCategory.All.map(_.glyph).distinct.size, AlertCategory.All.size)
    assertEquals(AlertSeverity.All.map(_.wire), List("warning", "critical"))
  }

  test("two rules share a severity and differ by category, which is what SCREENS-V4 §3.9 corrects") {
    // The notifications panel draws a rebalance arrow and a disk in the same warning tone. A product that
    // picked the glyph from the severity could not draw that capture, and this is the pair that proves
    // the two fields are independent here.
    val rebalance = firstPassRebalance()
    val disk = firstPass(directories(directory("/var/lib/kafka", Some(diskWarningPercent)))).opened.head

    assertEquals(rebalance.severity, disk.severity)
    assertNotEquals(rebalance.category.glyph, disk.category.glyph)
  }

  test("every wire spelling round-trips, so a stored event decodes to the case it was written from") {
    assertEquals(AlertSeverity.All.flatMap(s => AlertSeverity.fromWire(s.wire)), AlertSeverity.All)
    assertEquals(AlertCategory.All.flatMap(c => AlertCategory.fromWire(c.wire)), AlertCategory.All)
    assertEquals(AlertRule.All.flatMap(r => AlertRule.fromWire(r.wire)), AlertRule.All)
    assertEquals(AlertSeverity.fromWire("nonsense"), None)
  }

  private def firstPassRebalance(): AlertEvent = {
    val seen = firstPass(rebalancing(payments))

    AlertRules
      .evaluate(now.plus(java.time.Duration.ofMinutes(6)), limits, rebalancing(payments), Nil, seen.state)
      .opened
      .head
  }
}
