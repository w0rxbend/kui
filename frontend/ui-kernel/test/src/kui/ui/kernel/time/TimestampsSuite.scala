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

  test("theOffsetDoesNotDependOnTheSubSecondPartOfTheInstant") {
    // The offset is derived by subtracting the instant from the wall clock the zone shows, and the
    // wall clock `Intl` hands back is whole seconds. Subtracting an instant that carries
    // milliseconds therefore produced an offset a fraction of a second short of the real one --
    // 10799.4 seconds instead of 10800 -- which the label renders as `UTC+02:59`. Two screens
    // formatting the same zone from two instants a few hundred milliseconds apart then disagreed
    // about what the offset was.
    val zone = "Europe/Warsaw"
    val whole = Instant.parse("2026-09-03T12:05:11Z")

    List(0, 1, 250, 499, 500, 501, 750, 999).foreach { millis =>
      val withMillis = whole.plusMillis(millis.toLong)
      assertEquals(
        Timestamps.offsetSeconds(zone, withMillis),
        Some(2 * 3600),
        s"offsetSeconds at +${millis}ms"
      )
      assertEquals(
        Timestamps.offsetLabel(zone, withMillis),
        "UTC+02:00",
        s"offsetLabel at +${millis}ms"
      )
      assertEquals(
        Timestamps.absolute(withMillis, zone),
        "2026-09-03 14:05:11 UTC+02:00",
        s"absolute at +${millis}ms"
      )
    }
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
