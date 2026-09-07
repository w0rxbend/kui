package kui.metrics.api

import java.time.Instant

import kui.contracts.Section
import kui.contracts.capability.ReasonCode
import kui.metrics.application.MetricsReading
import kui.metrics.contract.dto.*
import kui.metrics.domain.*

/** The two vocabularies this service holds, and the total translation between them.
  *
  * `services/metrics/contract` may not see `services/metrics/domain` (rule A2) and the domain may not see the
  * wire (rule A1), so every type exists twice — once as the browser spells it, once with the arithmetic
  * behind it. This file is the only place the two meet, which is what makes them pinnable by a test rather
  * than kept equal by everybody remembering.
  */
object MetricsMapping {

  /** The wire range as the domain's. Total in both directions, which is why neither has a fallback: a range
    * this mapping could not translate would be a query parameter the contract had accepted and the service
    * could not honour.
    */
  def range(dto: ThroughputRangeDto): ThroughputRange = dto match {
    case ThroughputRangeDto.Last24Hours => ThroughputRange.Last24Hours
    case ThroughputRangeDto.Last7Days => ThroughputRange.Last7Days
    case ThroughputRangeDto.Last30Days => ThroughputRange.Last30Days
  }

  def rangeDto(range: ThroughputRange): ThroughputRangeDto = range match {
    case ThroughputRange.Last24Hours => ThroughputRangeDto.Last24Hours
    case ThroughputRange.Last7Days => ThroughputRangeDto.Last7Days
    case ThroughputRange.Last30Days => ThroughputRangeDto.Last30Days
  }

  def bucket(bucket: ThroughputBucket): ThroughputBucketDto =
    ThroughputBucketDto(
      startingAt = bucket.startingAt,
      bytesInPerSecond = bucket.bytesInPerSecond,
      bytesOutPerSecond = bucket.bytesOutPerSecond,
      recordsPerSecond = bucket.recordsPerSecond
    )

  def series(series: ThroughputSeries): ThroughputSeriesDto =
    ThroughputSeriesDto(
      range = rangeDto(series.range),
      from = series.from,
      to = series.to,
      // Seconds and not a duration string: JSON has no duration type, and a chart's step is arithmetic the
      // browser does rather than prose it prints.
      stepSeconds = series.range.step.toSeconds,
      buckets = series.buckets.map(bucket)
    )

  def latencyBucket(bucket: LatencyBucket): LatencyBucketDto =
    LatencyBucketDto(
      startingAt = bucket.startingAt,
      produceP99Millis = bucket.produceP99Millis,
      fetchP99Millis = bucket.fetchP99Millis
    )

  def latencySeries(series: LatencySeries): LatencySeriesDto =
    LatencySeriesDto(
      window = rangeDto(series.range),
      from = series.from,
      to = series.to,
      stepSeconds = series.range.step.toSeconds,
      buckets = series.buckets.map(latencyBucket)
    )

  def purgatoryQueue(queue: PurgatoryQueue): PurgatoryQueueDto =
    PurgatoryQueueDto(operation = queue.operation, delayedRequests = queue.delayedRequests)

  /** The ratios travel as ratios. A `Some(0.64)` here becoming `"64%"` on the wire would be this service
    * choosing a rounding and a locale for every client that will ever read it.
    */
  def requestHandlers(reading: RequestHandlerReading): RequestHandlersDto =
    RequestHandlersDto(
      requestHandlerIdleRatio = reading.requestHandlerIdleRatio,
      networkProcessorIdleRatio = reading.networkProcessorIdleRatio,
      purgatory = reading.purgatory.map(purgatoryQueue)
    )

  def topProducers(producers: TopProducers): TopProducersDto =
    TopProducersDto(
      // Stated rather than assumed: the card's title is drawn from this field, and a list of topics
      // labelled "top producers by client id" is exactly the mislabelling ADR-052 forbids.
      measuredBy = TopProducersDto.ByTopic,
      topics = producers.topics.map(topic => TopicProducerDto(topic.topic, topic.bytesInPerSecond))
    )

  def recordSize(reading: RecordSizeReading): RecordSizeDto =
    RecordSizeDto(
      meanBytes = reading.meanBytes,
      bytesInPerSecond = reading.bytesInPerSecond,
      recordsPerSecond = reading.recordsPerSecond
    )

  /** A reading as the section a card renders.
    *
    * The three cases are the three renderings, and keeping the mapping here — rather than letting each route
    * decide — is what stops "no source configured" reaching one screen as an empty chart and another as an
    * error. It is written once for all five endpoints so that a sixth cannot spell them differently.
    *
    * `NotMeasured` deliberately loses its sentence into the section's `not_configured` status, which carries
    * no message: the sentence a card shows is the product's own copy (`SCREENS-V4.md` §6), not a string a
    * service wrote, and the *reason* is still available per cluster on the capability document. `Unreadable`
    * keeps its message, because that one is not a deployment choice — it names an exporter that is down or a
    * whitelist that is missing a family, and no card copy could know which.
    */
  def sectionOf[A, B](reading: MetricsReading[A])(toDto: A => B): Section[B] = reading match {
    case MetricsReading.Measured(value, at) => Section.Ok(toDto(value), at)
    case MetricsReading.NotMeasured(_) => Section.NotConfigured
    case MetricsReading.Unreadable(failure, at) => unavailable(failure.message, ReasonCode.of(failure), at)
  }

  private def unavailable[B](message: String, reason: ReasonCode, at: Instant): Section[B] =
    Section.Unavailable(reason, message, Some(at))
}
