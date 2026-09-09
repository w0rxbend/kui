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

  test("a mean record size keeps the fraction it was divided to, because the browser does the rounding") {
    // 2049 bytes over 2 records is 1024.5 bytes a record, and rounding it here would be this service
    // choosing a precision for every client that will ever read the field — the same argument the
    // idle ratios travel as ratios for. Every other fixture in this service divides to a whole
    // number, so the rounding was ungated until this case: `Math.round` in `from` was invisible.
    assertEquals(RecordSizeReading.from(Some(2049.0), Some(2.0)).meanBytes, Some(1024.5))
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

  test("Kafka's own topics are not ranked as producers, and the count of them travels") {
    // Measured, not supposed: the committed capture
    // `services/metrics/infrastructure/test/resources/exposition/kafka-broker-stock-ruleset.txt:38-40` has
    // `__consumer_offsets` at 156.97 bytes/s against `orders.v1` at 5.95e-20 on an idle quickstart broker.
    // Ranked, the card would report the consumer-group protocol as the busiest producer on the cluster by a
    // factor of a thousand. The count of what was removed travels so the browser can say so rather than
    // showing a silently shortened list.
    val mixed = List(
      TopicProducer("__consumer_offsets", 156.9725527287428),
      TopicProducer("__transaction_state", 3.0),
      TopicProducer("orders.v1", 5.950733067299049e-20)
    )

    assertEquals(TopProducers.of(mixed, 5).topics.map(_.topic), List("orders.v1"))
    assertEquals(TopProducers.of(mixed, 5).internalTopicsExcluded, 2)
  }

  test("a topic with one leading underscore is a customer's and stays in the ranking") {
    // The narrow prefix is the point. `kui.topics.internalPrefix` ships as `"_"`, which is the topics
    // screen's own broader rule and an operator can turn it off there with `?showInternal=1`; borrowing it
    // here would drop a customer's `_audit` topic out of a producer ranking with nothing on the screen
    // saying so, and losing a real producer is a worse answer than keeping an internal one.
    val topics = List(TopicProducer("_audit", 40.0), TopicProducer("__consumer_offsets", 90.0))

    assertEquals(TopProducers.of(topics, 5).topics.map(_.topic), List("_audit"))
    assertEquals(TopProducers.of(topics, 5).internalTopicsExcluded, 1)
    assertEquals(TopProducers.InternalTopicPrefix, "__")
  }

  test("the internal topics are removed before the count is cut, not after") {
    // Removed after the `take`, a five-row card on a cluster whose two busiest topics are Kafka's own would
    // show three rows and nothing would say why the other two are missing.
    val topics = List(
      TopicProducer("__consumer_offsets", 900.0),
      TopicProducer("__transaction_state", 800.0),
      TopicProducer("orders.v1", 70.0),
      TopicProducer("payments.v1", 60.0)
    )

    assertEquals(TopProducers.of(topics, 2).topics.map(_.topic), List("orders.v1", "payments.v1"))
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
