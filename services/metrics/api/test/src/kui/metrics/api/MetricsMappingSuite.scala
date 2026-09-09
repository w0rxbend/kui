package kui.metrics.api

import java.time.Instant

import munit.FunSuite

import kui.contracts.Section
import kui.contracts.capability.ReasonCode
import kui.kernel.error.InfrastructureError
import kui.metrics.application.MetricsReading
import kui.metrics.contract.dto.{ThroughputRangeDto, TopProducersDto}
import kui.metrics.domain.*

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
    val section = MetricsMapping.sectionOf(MetricsReading.NotMeasured("no source"))(MetricsMapping.series)

    assertEquals(section.status, "not_configured")
    // Not `Ok` with an empty series: an empty axis is a chart claiming it looked and found nothing.
    assertEquals(section.toOption, None)
  }

  test("a source that refused becomes unavailable with the upstream's own reason") {
    val down = InfrastructureError.Unreachable("metrics-exporter", "connection refused")
    val section = MetricsMapping.sectionOf(MetricsReading.Unreadable(down, at))(MetricsMapping.series)

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
    val section = MetricsMapping.sectionOf(MetricsReading.Measured(series, at))(MetricsMapping.series)

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

  test("a latency series keeps the same axis and spells its window with the same three words") {
    // One range vocabulary across both charts. A `24h` that meant a different window on the latency card
    // would be two axes a reader compares without being able to see that they differ.
    val series = LatencySeries.absent(ThroughputRange.Last7Days, at)
    val section = MetricsMapping.sectionOf(MetricsReading.Measured(series, at))(MetricsMapping.latencySeries)

    section match {
      case Section.Ok(dto, _) =>
        assertEquals(dto.window, ThroughputRangeDto.Last7Days)
        assertEquals(dto.window.wire, ThroughputRange.Last7Days.wire)
        assertEquals(dto.stepSeconds, ThroughputRange.Last7Days.step.toSeconds)
        assertEquals(dto.buckets.size, ThroughputRange.Last7Days.bucketCount)
        assert(dto.buckets.forall(_.produceP99Millis.isEmpty))
      case other => fail(s"expected Ok, got $other")
    }
  }

  test("an idle ratio crosses the wire as a ratio and is not pre-formatted") {
    // The mapping is the last place a `0.8912` could become a `"89%"`, and a ring gauge cannot draw an arc
    // from a string. The DTO has no percent field at all, so this is asserted on the value that does exist.
    val dto = MetricsMapping.requestHandlers(RequestHandlerReading(Some(0.8912), Some(0.7104), Nil))

    assertEquals(dto.requestHandlerIdleRatio, Some(0.8912))
    assertEquals(dto.networkProcessorIdleRatio, Some(0.7104))
  }

  test("a purgatory queue crosses as a count, keeping the broker's own name for the operation") {
    val dto = MetricsMapping.requestHandlers(
      RequestHandlerReading(None, None, List(PurgatoryQueue("Fetch", 481L), PurgatoryQueue("Produce", 0L)))
    )

    assertEquals(dto.purgatory.map(_.operation), List("Fetch", "Produce"))
    assertEquals(dto.purgatory.map(_.delayedRequests), List(481L, 0L))
  }

  test("top producers are labelled as topics on the wire, not as clients") {
    // ADR-052's second refusal, at the only place a list of topics could acquire the design's word. The
    // browser reads `measuredBy` to choose the card's title, so a wrong value here is a mislabelled card.
    val dto = MetricsMapping.topProducers(TopProducers(List(TopicProducer("orders.v1", 900.0)), 0))

    assertEquals(dto.measuredBy, TopProducersDto.ByTopic)
    assertEquals(dto.topics.map(_.topic), List("orders.v1"))
  }

  test("the count of internal topics left out of the ranking crosses to the wire") {
    // The ranking drops Kafka's own topics, and a card that showed a shortened list with nothing saying so
    // would be one nobody could reconcile against the exporter. The figure travels; the browser prints it.
    val dto = MetricsMapping.topProducers(TopProducers(List(TopicProducer("orders.v1", 900.0)), 2))

    assertEquals(dto.internalTopicsExcluded, 2)
  }

  test("a record size crosses as a mean with the two rates it came from and no percentile") {
    val dto = MetricsMapping.recordSize(RecordSizeReading.from(Some(1024.0), Some(8.0)))

    assertEquals(dto.meanBytes, Some(128.0))
    assertEquals(dto.bytesInPerSecond, Some(1024.0))
    assertEquals(dto.recordsPerSecond, Some(8.0))
    // Three fields and no more. A `p50` here would be a percentile assembled from a mean, which is the
    // drawing of an assumption ADR-052 refuses.
    assertEquals(dto.productArity, 3)
  }

  test("every reading uses one section mapping, so a refusal reads the same on all five cards") {
    val down = InfrastructureError.Unreachable("metrics-exporter", "connection refused")
    val sections = List(
      MetricsMapping.sectionOf(MetricsReading.Unreadable(down, at))(MetricsMapping.series).status,
      MetricsMapping.sectionOf(MetricsReading.Unreadable(down, at))(MetricsMapping.latencySeries).status,
      MetricsMapping.sectionOf(MetricsReading.Unreadable(down, at))(MetricsMapping.requestHandlers).status,
      MetricsMapping.sectionOf(MetricsReading.Unreadable(down, at))(MetricsMapping.topProducers).status,
      MetricsMapping.sectionOf(MetricsReading.Unreadable(down, at))(MetricsMapping.recordSize).status
    )

    assertEquals(sections.distinct, List("unavailable"))
  }

  test("a stale reading keeps its figure and the instant it was taken, and is not an ok section") {
    // The fourth arm, and the whole of what makes it different from `Ok`: the number is true and it is not
    // current. Folded into `Ok` it would draw an hour-old gauge as the broker's present state; folded into
    // `Unavailable` it would throw away a reading the buffer still holds and offer a Retry instead of it.
    val reading = RequestHandlerReading(Some(0.8912), Some(0.7104), Nil)
    val section = MetricsMapping.sectionOf(MetricsReading.Stale(reading, at))(MetricsMapping.requestHandlers)

    assertEquals(section.status, "stale")
    section match {
      case Section.Stale(data, fetchedAt, reason) =>
        assertEquals(data.requestHandlerIdleRatio, Some(0.8912))
        assertEquals(fetchedAt, at)
        assertEquals(reason, ReasonCode.UpstreamUnavailable)
      case other => fail(s"expected a stale section, got $other")
    }
  }
}
