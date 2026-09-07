package kui.metrics.domain

import java.time.Instant

import munit.FunSuite

/** The three readings ADR-052 redrew, and the arithmetic behind the one of them that is a quotient.
  *
  * Every case here is a figure that would otherwise be drawn wrongly: a mean that is `Infinity`, a top-N that
  * is the exporter's line order, or a purgatory count read as a fraction.
  */
final class BrokerReadingsSuite extends FunSuite {

  private val at = Instant.parse("2026-09-06T12:00:00Z")

  test("a mean record size is bytes in over records in") {
    val reading = RecordSizeReading.from(Some(1100.0), Some(1.0))

    assertEquals(reading.meanBytes, Some(1100.0))
    // Both rates travel with it, so a card can say what the figure is rather than leaving a reader to
    // assume it is a median.
    assertEquals(reading.bytesInPerSecond, Some(1100.0))
    assertEquals(reading.recordsPerSecond, Some(1.0))
  }

  test("a record rate of zero has no mean size, and the answer is absent rather than infinite") {
    // `x / 0` is `Infinity`, which serialises to `null` by a route nobody chose and then reads as a gap
    // KUI never measured — a different and much weaker statement than "no record arrived to measure".
    val reading = RecordSizeReading.from(Some(1100.0), Some(0.0))

    assertEquals(reading.meanBytes, None)
    assertEquals(reading.recordsPerSecond, Some(0.0))
    assert(!reading.isEmpty, "the two rates were measured, so this is not an unread family")
  }

  test("a missing rate leaves the mean absent rather than assuming the other one") {
    assertEquals(RecordSizeReading.from(Some(1100.0), None).meanBytes, None)
    assertEquals(RecordSizeReading.from(None, Some(4.0)).meanBytes, None)
    assert(RecordSizeReading.from(None, None).isEmpty)
  }

  test("top producers are ranked by rate and cut to the count asked for") {
    // Ranked in the service and not in whichever card draws it, so two screens asking for three and for
    // ten cannot disagree about which topic is busiest.
    val topics = List(
      TopicProducer("audit.log.raw", 10.0),
      TopicProducer("orders.v1", 900.0),
      TopicProducer("payments.transactions", 500.0)
    )

    assertEquals(TopProducers.of(topics, 2).topics.map(_.topic), List("orders.v1", "payments.transactions"))
    assertEquals(TopProducers.of(topics, 10).topics.size, 3)
    assertEquals(TopProducers.of(topics, 0).topics, Nil)
  }

  test("two topics on the same rate are ordered by name rather than by the exporter's line order") {
    // A repeat request against unchanged traffic has to return the same card. An exporter's line order is
    // whatever its bean enumeration produced and is not stable across scrapes.
    val tied = List(TopicProducer("zeta", 5.0), TopicProducer("alpha", 5.0))

    assertEquals(TopProducers.of(tied, 2).topics.map(_.topic), List("alpha", "zeta"))
  }

  test("a purgatory queue is a count of parked requests and carries no denominator") {
    // ADR-052's first refusal, in the type system: there is nothing on this value to divide by, so no
    // caller can turn it into the percentage SCREENS-V4.md §3.4 draws.
    val queue = PurgatoryQueue("Fetch", 481L)

    assertEquals(queue.delayedRequests, 481L)
    assertEquals(queue.productArity, 2)
  }

  test("a broker sample knows when it recognised nothing at all") {
    // The one thing the adapter refuses on. A sample of nothing but `None`s filed into the window would
    // put a measured gap on five cards for an address that is not an exporter.
    assert(BrokerSample.empty(at).isEmpty)
    assert(!BrokerSample.empty(at).copy(purgatory = List(PurgatoryQueue("Fetch", 0L))).isEmpty)
    // A served-but-empty per-topic family counts as recognised: the exporter answered the question.
    assert(!BrokerSample.empty(at).copy(topicBytesInPerSecond = Some(Nil)).isEmpty)
  }

  test("the four views of one sample all carry the same instant and the same figures") {
    val sample = BrokerSample
      .empty(at)
      .copy(
        bytesInPerSecond = Some(1024.0),
        recordsPerSecond = Some(8.0),
        produceP99Millis = Some(9.0),
        requestHandlerIdleRatio = Some(0.64)
      )

    assertEquals(sample.throughput.at, at)
    assertEquals(sample.latency.at, at)
    assertEquals(sample.throughput.bytesInPerSecond, Some(1024.0))
    assertEquals(sample.latency.produceP99Millis, Some(9.0))
    assertEquals(sample.handlers.requestHandlerIdleRatio, Some(0.64))
    assertEquals(sample.recordSize.meanBytes, Some(128.0))
  }
}
