package kui.ui.kernel.time

import java.time.Instant

import munit.FunSuite

class TimestampsSuite extends FunSuite {

  private val at = Instant.parse("2026-09-03T12:05:11Z")

  test("absoluteFormatsInTheGivenZone") {
    assertEquals(Timestamps.absolute(at, "UTC"), "2026-09-03 12:05:11 UTC+00:00")
    assertEquals(Timestamps.absolute(at, "Europe/Warsaw"), "2026-09-03 14:05:11 UTC+02:00")
    assertEquals(Timestamps.absolute(at, "America/New_York"), "2026-09-03 08:05:11 UTC-04:00")
  }

  test("anUnknownZoneFallsBackToUtcRatherThanThrowing") {
    assertEquals(Timestamps.absolute(at, "Mars/Olympus"), Timestamps.absolute(at, "UTC"))
  }

  test("relativeCoversEveryBoundary") {
    val now = Instant.parse("2026-09-03T12:00:00Z")
    val cases = List(
      0L -> "just now",
      30L -> "just now",
      59L -> "just now",
      60L -> "1 minute ago",
      119L -> "1 minute ago",
      120L -> "2 minutes ago",
      3599L -> "59 minutes ago",
      3600L -> "1 hour ago",
      86399L -> "23 hours ago",
      86400L -> "1 day ago",
      8L * 86400 -> "8 days ago"
    )
    cases.foreach { (ago, expected) =>
      assertEquals(Timestamps.relative(now.minusSeconds(ago), now), expected, s"$ago seconds ago")
    }
  }

  test("aFutureInstantReadsAsFutureNotNegative") {
    val now = Instant.parse("2026-09-03T12:00:00Z")
    assertEquals(Timestamps.relative(now.plusSeconds(5), now), "in 5 seconds")
    assertEquals(Timestamps.relative(now.plusSeconds(1), now), "in 1 second")
    assertEquals(Timestamps.relative(now.plusSeconds(600), now), "in 10 minutes")
  }

  test("lastUpdatedOfNoneIsNeverRefreshed") {
    val now = Instant.parse("2026-09-03T12:00:00Z")
    assertEquals(Timestamps.lastUpdated(None, now), "Never refreshed")
    assertEquals(Timestamps.lastUpdated(Some(now.minusSeconds(480)), now), "Last updated 8 minutes ago")
  }

  test("systemZoneAlwaysAnswersWithSomething") {
    assert(Timestamps.systemZone().nonEmpty)
  }
}
