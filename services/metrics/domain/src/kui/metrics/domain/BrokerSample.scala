package kui.metrics.domain

import java.time.Instant

/** Everything one scrape of one broker's exposition produced, as one value with one instant.
  *
  * ==One sample and not five==
  *
  * The five cards this service feeds are answered from one HTTP request: an exporter serves the throughput
  * rates, the request percentiles, the idle ratios and the per-topic rates in a single body, and splitting
  * them into five samples with five instants would make two cards on one screen disagree about when "now"
  * was. It is also what makes the retention window cheap — one window per cluster rather than five.
  *
  * ==Every figure is separately optional, and that is the whole design==
  *
  * A JMX exporter is configured with a whitelist and every deployment's is different. A body carrying the
  * byte rates and no percentiles is an ordinary configuration, not a broken one, and it must cost the latency
  * card and nothing else — which is the same rule the `Section` per endpoint carries one layer up. `None` is
  * *not measured* everywhere in this file and is never a zero.
  *
  * @param topicBytesInPerSecond
  *   `None` when the exporter published no `BytesInPerSec` line at all — the family is not whitelisted and
  *   nothing about topics can be said. `Some(Nil)` when it published the family and no line carrying a
  *   `topic` label, which has **two** causes the exposition cannot tell apart: no topic is receiving traffic,
  *   or the exporter's ruleset has a broker-wide rule and no per-topic one. Verified against both on a live
  *   broker; the card's sentence therefore names both rather than claiming the cluster is quiet.
  */
final case class BrokerSample(
    at: Instant,
    bytesInPerSecond: Option[Double],
    bytesOutPerSecond: Option[Double],
    recordsPerSecond: Option[Double],
    produceP99Millis: Option[Double],
    fetchP99Millis: Option[Double],
    requestHandlerIdleRatio: Option[Double],
    networkProcessorIdleRatio: Option[Double],
    purgatory: List[PurgatoryQueue],
    topicBytesInPerSecond: Option[List[TopicProducer]]
) {

  def throughput: ThroughputSample =
    ThroughputSample(at, bytesInPerSecond, bytesOutPerSecond, recordsPerSecond)

  def latency: LatencySample = LatencySample(at, produceP99Millis, fetchP99Millis)

  def handlers: RequestHandlerReading =
    RequestHandlerReading(requestHandlerIdleRatio, networkProcessorIdleRatio, purgatory)

  /** The busiest topics this scrape saw, or `None` when the family was not served at all. */
  def producers: Option[List[TopicProducer]] = topicBytesInPerSecond

  def recordSize: RecordSizeReading = RecordSizeReading.from(bytesInPerSecond, recordsPerSecond)

  /** True when the body carried nothing this service knows how to read.
    *
    * The one thing the adapter refuses on: a source publishing none of these is a misconfiguration an
    * operator has to be told about, and filing a sample of nothing but `None`s would put a measured gap on
    * every card instead.
    */
  def isEmpty: Boolean =
    throughput.isEmpty && latency.isEmpty && handlers.isEmpty && topicBytesInPerSecond.isEmpty
}

object BrokerSample {

  /** A scrape that recognised nothing, for a caller that fills in what it found. */
  def empty(at: Instant): BrokerSample =
    BrokerSample(at, None, None, None, None, None, None, None, Nil, None)

  given CanEqual[BrokerSample, BrokerSample] = CanEqual.derived
}
