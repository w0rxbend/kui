package kui.metrics.api

import java.time.Instant

import kui.contracts.Section
import kui.contracts.capability.ReasonCode
import kui.metrics.application.MetricsReading
import kui.metrics.contract.dto.*
import kui.metrics.domain.{ThroughputBucket, ThroughputRange, ThroughputSeries}

/** The two vocabularies this service holds, and the total translation between them.
  *
  * `services/metrics/contract` may not see `services/metrics/domain` (rule A2) and the domain may not see the
  * wire (rule A1), so the range exists twice — once as the browser spells it, once with the window and step
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

  /** A reading as the section a card renders.
    *
    * The three cases are the three renderings, and keeping the mapping here — rather than letting each route
    * decide — is what stops "no source configured" reaching one screen as an empty chart and another as an
    * error. `NotMeasured` deliberately loses its sentence into the section's `not_configured` status, which
    * carries no message: the sentence a card shows is the product's own copy (`SCREENS-V4.md` §6), not a
    * string a service wrote, and the *reason* is still available per cluster on the capability document.
    */
  def sectionOf(reading: MetricsReading[ThroughputSeries]): Section[ThroughputSeriesDto] = reading match {
    case MetricsReading.Measured(value, at) => Section.Ok(series(value), at)
    case MetricsReading.NotMeasured(_) => Section.NotConfigured
    case MetricsReading.Unreadable(failure, at) => unavailable(failure.message, ReasonCode.of(failure), at)
  }

  private def unavailable(message: String, reason: ReasonCode, at: Instant): Section[ThroughputSeriesDto] =
    Section.Unavailable(reason, message, Some(at))
}
