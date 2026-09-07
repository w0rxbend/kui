package kui.metrics.domain

/** One delayed-operation purgatory and how many requests are parked in it.
  *
  * ==A length, and the design asked for a percentage==
  *
  * `SCREENS-V4.md` §3.4 draws a third ring gauge reading "38% PURGATORY". A broker publishes
  * `kafka.server:type=DelayedOperationPurgatory,name=PurgatorySize` and it is a **queue length** — 961 on the
  * quickstart broker, all of it long-polling consumer fetches. There is no ceiling to divide it by, so there
  * is no percentage: any denominator KUI chose would be invented, and the ring would be a picture of that
  * invention. ADR-052 redraws the sub-tile as the count it is, with its own unit, and this type is that
  * decision in the type system — nothing here can be read as a fraction.
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
  */
final case class TopProducers(topics: List[TopicProducer])

object TopProducers {

  val Empty: TopProducers = TopProducers(Nil)

  /** The `count` busiest, largest rate first and ties broken by name.
    *
    * Ordered here rather than by whichever card draws it, so that two screens asking for three and for ten
    * cannot disagree about which topic is busiest — and so that a repeat request with unchanged traffic
    * returns the same order rather than the exporter's.
    */
  def of(topics: List[TopicProducer], count: Int): TopProducers =
    TopProducers(topics.sortBy(topic => (-topic.bytesInPerSecond, topic.topic)).take(Math.max(count, 0)))

  given CanEqual[TopProducers, TopProducers] = CanEqual.derived
}

/** How large a record on this cluster is, on average, and the two rates that says so.
  *
  * ==A mean, and the design asked for a distribution==
  *
  * `SCREENS-V4.md` §3.5 draws a twelve-bucket histogram with `p50 · 1.1 KB`, `p99 · 18 KB` and `max · 0.9 MB`
  * chips. **Kafka publishes no record-size distribution of any kind** — verified against a stock-ruleset
  * exposition of the quickstart broker, 680 Kafka families and not one histogram or bucket among them. What
  * can be measured is the quotient of two rates the broker does publish, which is a mean and is labelled as
  * one (ADR-052). Twelve buckets assembled from a mean would be a drawing of an assumption.
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
