package kui.metrics.domain

/** One delayed-operation purgatory and how many requests are parked in it.
  *
  * ==A length, and the design asked for a percentage==
  *
  * `SCREENS-V4.md` §3.4 draws a third ring gauge reading "38% PURGATORY". A broker publishes
  * `kafka.server:type=DelayedOperationPurgatory,name=PurgatorySize` and it is a **queue length** — 481 on the
  * quickstart broker, counted in
  * `services/metrics/infrastructure/test/resources/exposition/kafka-broker-stock-ruleset.txt:54`, all of it
  * long-polling consumer fetches. There is no ceiling to divide it by, so there is no percentage: any
  * denominator KUI chose would be invented, and the ring would be a picture of that invention. ADR-052
  * redraws the sub-tile as the count it is, with its own unit, and this type is that decision in the type
  * system — nothing here can be read as a fraction.
  *
  * Per operation rather than summed, because the sum is not a thing an operator acts on: `Fetch` is deep by
  * design on any cluster with consumers, and `Produce` being deep at all means acknowledgements are waiting
  * on replicas. Adding them makes the normal number hide the interesting one.
  *
  * @param operation
  *   the broker's own `delayedOperation` key — `Fetch`, `Produce`, `DeleteRecords` and the rest — carried
  *   verbatim rather than mapped to a KUI vocabulary, so a reader can find it in the broker's own JMX tree
  * @param delayedRequests
  *   how many requests are parked. A measured zero is a fact and is not the same as no reading at all.
  */
final case class PurgatoryQueue(operation: String, delayedRequests: Long)

object PurgatoryQueue {
  given CanEqual[PurgatoryQueue, PurgatoryQueue] = CanEqual.derived
}

/** What the broker's request-handling machinery was doing at the moment of the scrape.
  *
  * Two ratios and a set of queue lengths, which is what `SCREENS-V4.md` §3.4's card can honestly be drawn
  * from. Both ratios are in `0..1` and are carried as ratios rather than as pre-formatted percentages: a
  * service that shipped "64%" would be deciding a rounding and a locale on the browser's behalf, and the ring
  * gauge needs the fraction in order to draw an arc at all.
  *
  * @param requestHandlerIdleRatio
  *   `kafka.server:type=KafkaRequestHandlerPool,name=RequestHandlerAvgIdlePercent`, the one-minute rate of a
  *   meter whose value is already a fraction of one. It is the pool that does the disk and replication work,
  *   so the design's "IO IDLE"
  * @param networkProcessorIdleRatio
  *   `kafka.network:type=SocketServer,name=NetworkProcessorAvgIdlePercent`, a plain gauge in `0..1` — the
  *   design's "NETWORK IDLE"
  * @param purgatory
  *   every delayed-operation queue the exporter published, in the broker's own order of naming. Empty means
  *   the family was not served, which the adapter turns into a stated refusal rather than into three zeroes.
  */
final case class RequestHandlerReading(
    requestHandlerIdleRatio: Option[Double],
    networkProcessorIdleRatio: Option[Double],
    purgatory: List[PurgatoryQueue]
) {

  /** True when the scrape carried none of the three. */
  def isEmpty: Boolean =
    requestHandlerIdleRatio.isEmpty && networkProcessorIdleRatio.isEmpty && purgatory.isEmpty
}

object RequestHandlerReading {

  val Empty: RequestHandlerReading = RequestHandlerReading(None, None, Nil)

  given CanEqual[RequestHandlerReading, RequestHandlerReading] = CanEqual.derived
}

/** One topic and the rate at which bytes are arriving into it.
  *
  * ==A topic, and the design asked for a client==
  *
  * `SCREENS-V4.md` §4 draws "Top producers" by `client.id`. A Kafka broker publishes no per-`client.id` byte
  * rate unless client quotas are configured — checked against the quickstart broker, whose stock-ruleset
  * exposition carries no quota family at all — and it does publish `BytesInPerSec` dimensioned by `topic`.
  * That is a real number and it is not the number the design named, so it travels under the name of what it
  * holds. A field called `clientId` carrying a topic name would be the defect ADR-052 exists to prevent.
  */
final case class TopicProducer(topic: String, bytesInPerSecond: Double)

object TopicProducer {
  given CanEqual[TopicProducer, TopicProducer] = CanEqual.derived
}

/** The topics receiving the most traffic, largest first.
  *
  * A whole reading rather than a bare list, so that "the exporter served this family and published no topic
  * line" has somewhere to live that is not an empty list meaning "the family was not served at all". The
  * adapter tells those two apart before it builds one of these.
  *
  * What it cannot tell apart, because an exposition does not carry it, is *why* there is no topic line: a
  * cluster where nothing is producing and an exporter whose ruleset has no per-topic rule serve the same
  * bytes. Both were produced against a live broker while this was being written. The empty list is therefore
  * an honest "nothing to rank" and the card's sentence names both causes rather than picking one.
  *
  * @param topics
  *   the ranking, largest rate first, with Kafka's own internal topics already taken out — see
  *   [[TopProducers.InternalTopicPrefix]] for why the exclusion is here and not on the screen
  * @param internalTopicsExcluded
  *   how many topic lines the exposition carried that this ranking left out. It travels rather than being
  *   dropped because a list that quietly omits rows is a list nobody can check: on the quickstart broker
  *   `__consumer_offsets` outruns every application topic by three orders of magnitude, and an operator
  *   comparing this card against the exporter has to be able to see that it was removed on purpose.
  */
final case class TopProducers(topics: List[TopicProducer], internalTopicsExcluded: Int)

object TopProducers {

  val Empty: TopProducers = TopProducers(Nil, 0)

  /** The two characters Kafka names its own topics with, and the reason this service picks them rather than
    * `kui.topics.internalPrefix`.
    *
    * Kafka's internal topics are `__consumer_offsets` and `__transaction_state`, both created by the broker
    * itself and neither of them anybody's application traffic. That is a fact about Kafka rather than a
    * deployment's preference, which is what makes it safe to write down in a domain that may not read
    * configuration (ADR-041 rule A1). `kui.topics.internalPrefix` is the deployment's *own*, broader rule —
    * shipped as `"_"` in `deployment/quickstart/kui-quickstart.yaml:49` and defaulting to `"__"` in
    * `libs/config/src/kui/config/TopicsConfig.scala:135` — and it belongs to the topics screen, where an
    * operator can turn it off with `?showInternal=1`. Borrowing the wider one here would drop a customer's
    * `_audit` topic out of a producer ranking with nothing on the screen saying so, and losing a real
    * producer is a worse answer than keeping an internal one.
    *
    * The evidence for the exclusion mattering is measured and committed:
    * `services/metrics/infrastructure/test/resources/exposition/kafka-broker-stock-ruleset.txt:38-40` is a
    * real capture in which `__consumer_offsets` carries 156.97 bytes/s against `orders.v1`'s 5.95e-20.
    */
  val InternalTopicPrefix: String = "__"

  /** The `count` busiest application topics, largest rate first and ties broken by name.
    *
    * Ordered here rather than by whichever card draws it, so that two screens asking for three and for ten
    * cannot disagree about which topic is busiest — and so that a repeat request with unchanged traffic
    * returns the same order rather than the exporter's.
    *
    * The internal topics are removed **before** the `take`, which is the only order that works: removed after
    * it, a five-row card on an idle cluster would show one application topic and four blanks.
    */
  def of(topics: List[TopicProducer], count: Int): TopProducers = {
    val (internal, application) = topics.partition(_.topic.startsWith(InternalTopicPrefix))
    TopProducers(
      topics = application.sortBy(topic => (-topic.bytesInPerSecond, topic.topic)).take(Math.max(count, 0)),
      internalTopicsExcluded = internal.size
    )
  }

  given CanEqual[TopProducers, TopProducers] = CanEqual.derived
}

/** How large a record on this cluster is, on average, and the two rates that says so.
  *
  * ==A mean, and the design asked for a distribution==
  *
  * `SCREENS-V4.md` §3.5 draws a twelve-bucket histogram with `p50 · 1.1 KB`, `p99 · 18 KB` and `max · 0.9 MB`
  * chips. **Kafka publishes no record-size distribution of any kind** — verified against a stock-ruleset
  * exposition of the quickstart broker, 670 Kafka families — the count is in that capture's own header,
  * `services/metrics/infrastructure/test/resources/exposition/kafka-broker-stock-ruleset.txt:7` — and not one
  * histogram or bucket among them. What can be measured is the quotient of two rates the broker does publish,
  * which is a mean and is labelled as one (ADR-052). Twelve buckets assembled from a mean would be a drawing
  * of an assumption.
  *
  * The two rates it was computed from travel with it, so a card can say what the figure is rather than
  * leaving a reader to assume it is a median.
  *
  * @param meanBytes
  *   bytes in per second divided by records in per second. `None` when either rate is absent **and** when the
  *   record rate is zero: a broker receiving no records has no mean record size, and `x / 0` would put
  *   `Infinity` on a card.
  */
final case class RecordSizeReading(
    meanBytes: Option[Double],
    bytesInPerSecond: Option[Double],
    recordsPerSecond: Option[Double]
) {

  /** True when neither rate was measured, so there is nothing to divide and nothing to say it with. */
  def isEmpty: Boolean = bytesInPerSecond.isEmpty && recordsPerSecond.isEmpty
}

object RecordSizeReading {

  val Empty: RecordSizeReading = RecordSizeReading(None, None, None)

  def from(bytesInPerSecond: Option[Double], recordsPerSecond: Option[Double]): RecordSizeReading =
    RecordSizeReading(
      meanBytes = for {
        bytes <- bytesInPerSecond
        records <- recordsPerSecond
        if records > 0.0d
      } yield bytes / records,
      bytesInPerSecond = bytesInPerSecond,
      recordsPerSecond = recordsPerSecond
    )

  given CanEqual[RecordSizeReading, RecordSizeReading] = CanEqual.derived
}
