package kui.metrics.domain

import java.time.Instant

/** One reading of how long a broker took to answer, at the moment the source was scraped.
  *
  * Two figures and not one, because the Traffic screen draws two lines: a produce request and a consumer
  * fetch are different journeys through a broker and a slow one says something different about the cluster.
  * Kafka publishes them as separate `RequestMetrics` beans and KUI keeps them separate all the way to the
  * legend chip.
  *
  * ==Percentiles the broker computed, never percentiles KUI computed==
  *
  * `kafka.network:type=RequestMetrics,name=TotalTimeMs` is a Yammer histogram and the broker publishes its
  * own `99thPercentile`. Nothing here re-derives one: a p99 assembled from a mean and a maximum is a number
  * with no relationship to any request, and this service exists to refuse exactly that kind of figure
  * (ADR-052).
  *
  * Each percentile is separately optional for the reason each throughput rate is: an exporter whitelist that
  * publishes `Produce` and not `FetchConsumer` is an ordinary configuration, and `None` means *not measured*
  * rather than "answered in no time at all".
  */
final case class LatencySample(
    at: Instant,
    produceP99Millis: Option[Double],
    fetchP99Millis: Option[Double]
) {

  /** True when the scrape carried no percentile at all — a source answering this is answering nothing. */
  def isEmpty: Boolean = produceP99Millis.isEmpty && fetchP99Millis.isEmpty
}

object LatencySample {
  given CanEqual[LatencySample, LatencySample] = CanEqual.derived
}

/** One step of the latency axis, and the worst p99 seen over it — or nothing, when nothing was measured. */
final case class LatencyBucket(
    startingAt: Instant,
    produceP99Millis: Option[Double],
    fetchP99Millis: Option[Double]
) {

  /** True when nothing at all was measured over this step. A bucket of measured zeroes is not absent. */
  def isAbsent: Boolean = produceP99Millis.isEmpty && fetchP99Millis.isEmpty
}

object LatencyBucket {
  given CanEqual[LatencyBucket, LatencyBucket] = CanEqual.derived
}

/** A cluster's p99 request latency over one range, bucket by bucket, ending at a stated instant.
  *
  * The same range vocabulary as throughput, deliberately: the two charts are stacked on one screen and a
  * `24h` that meant twenty-four hours in one and seven days in the other would be two axes a reader compares
  * without being able to see that they differ. [[ThroughputRange]] therefore keeps its name and is the
  * service's one range vocabulary; renaming it now would move a wire spelling a browser has already shipped
  * against, which is the one thing the range's own scaladoc forbids.
  */
final case class LatencySeries(
    range: ThroughputRange,
    from: Instant,
    to: Instant,
    buckets: List[LatencyBucket]
)

object LatencySeries {

  given CanEqual[LatencySeries, LatencySeries] = CanEqual.derived

  /** Folds raw samples into the range's buckets, ending at `endingAt`.
    *
    * The axis rule is throughput's, shared through [[Bucketing]]: `bucketCount` buckets whatever was sampled,
    * boundaries floored against the epoch, a bucket nobody sampled absent rather than zero.
    *
    * **The fold inside a bucket is not throughput's.** Several scrapes land in one step and each carries a
    * p99; the value drawn is the largest of them, not their mean. The mean of four p99s is a p99 of nothing —
    * percentiles do not average — while the largest is a statement that stays true of the data it came from:
    * somewhere in this step, one produce request in a hundred took at least this long. Averaging would hide a
    * spike inside a quiet five minutes, which is precisely the reading the card is drawn for.
    */
  def over(range: ThroughputRange, endingAt: Instant, samples: List[LatencySample]): LatencySeries = {
    val (from, to, buckets) = Bucketing.over(range, endingAt, samples, _.at) { (start, inBucket) =>
      LatencyBucket(
        startingAt = start,
        produceP99Millis = Bucketing.worst(inBucket.flatMap(_.produceP99Millis)),
        fetchP99Millis = Bucketing.worst(inBucket.flatMap(_.fetchP99Millis))
      )
    }

    LatencySeries(range, from, to, buckets)
  }

  /** The full axis with nothing on it: every bucket present, every percentile absent. */
  def absent(range: ThroughputRange, endingAt: Instant): LatencySeries = over(range, endingAt, Nil)
}
