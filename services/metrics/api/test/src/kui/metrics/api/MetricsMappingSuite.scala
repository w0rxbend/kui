package kui.metrics.api

import java.time.Instant

import munit.FunSuite

import kui.contracts.Section
import kui.contracts.capability.ReasonCode
import kui.kernel.error.InfrastructureError
import kui.metrics.application.MetricsReading
import kui.metrics.contract.dto.ThroughputRangeDto
import kui.metrics.domain.{ThroughputRange, ThroughputSeries}

/** The translation between the two vocabularies, and the section a card is drawn from.
  *
  * The range exists twice — once as the browser spells it, once with the window and step behind it — because
  * the layering rules forbid either module seeing the other. Two spellings kept equal by everyone
  * remembering is exactly the kind of rule this repository has learned to make a test instead.
  */
final class MetricsMappingSuite extends FunSuite {

  private val at = Instant.parse("2026-01-01T00:00:00Z")

  test("every wire range maps to a domain range and back to itself") {
    ThroughputRangeDto.All.foreach { dto =>
      assertEquals(MetricsMapping.rangeDto(MetricsMapping.range(dto)), dto)
    }
  }

  test("every domain range maps to a wire range and back to itself") {
    ThroughputRange.All.foreach { range =>
      assertEquals(MetricsMapping.range(MetricsMapping.rangeDto(range)), range)
    }
  }

  test("the two vocabularies spell the ranges identically") {
    // The pairing that would otherwise drift: a `7d` on the wire that meant thirty days in the service
    // would be a chart nothing on the screen could contradict.
    ThroughputRange.All.foreach { range =>
      assertEquals(MetricsMapping.rangeDto(range).wire, range.wire)
    }
  }

  test("a cluster with nothing to measure becomes not_configured, never an empty chart") {
    val section = MetricsMapping.sectionOf(MetricsReading.NotMeasured("no source"))

    assertEquals(section.status, "not_configured")
    // Not `Ok` with an empty series: an empty axis is a chart claiming it looked and found nothing.
    assertEquals(section.toOption, None)
  }

  test("a source that refused becomes unavailable with the upstream's own reason") {
    val down = InfrastructureError.Unreachable("metrics-exporter", "connection refused")
    val section = MetricsMapping.sectionOf(MetricsReading.Unreadable(down, at))

    assertEquals(section.status, "unavailable")
    section match {
      case Section.Unavailable(reason, _, since) =>
        assertEquals(reason, ReasonCode.UpstreamUnavailable)
        assertEquals(since, Some(at))
      case other => fail(s"expected Unavailable, got $other")
    }
  }

  test("a measured series keeps its axis and its gaps") {
    val series = ThroughputSeries.absent(ThroughputRange.Last24Hours, at)
    val section = MetricsMapping.sectionOf(MetricsReading.Measured(series, at))

    section match {
      case Section.Ok(dto, fetchedAt) =>
        assertEquals(fetchedAt, at)
        assertEquals(dto.range, ThroughputRangeDto.Last24Hours)
        assertEquals(dto.stepSeconds, ThroughputRange.Last24Hours.step.toSeconds)
        assertEquals(dto.buckets.size, ThroughputRange.Last24Hours.bucketCount)
        // Every bucket is on the wire as `null`, which is what breaks the bar. A zero here would be the
        // service claiming a measured, quiet day.
        assert(dto.buckets.forall(_.bytesInPerSecond.isEmpty))
      case other => fail(s"expected Ok, got $other")
    }
  }
}
