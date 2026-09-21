package kui.metrics.domain

import java.time.Instant

import kui.kernel.error.KuiError

/** A point-in-time reading and the instant of the scrape it was taken from.
  *
  * ==Why the instant travels with the value rather than being taken from a clock==
  *
  * A gauge is a claim about *now*. The buffer keeps samples for `kui.metrics.retention`, which is hours, and
  * hands out the newest one it still holds — so a reading answered at noon may have been scraped at eleven,
  * and until this type existed the layer above stamped it `now` and every card said it was current. Carrying
  * the scrape's own instant is what lets [[kui.metrics.application.MetricsReading]] tell a fresh reading from
  * a last-known-good one, which is the difference between an `ok` section and a `stale` one (ADR-052).
  *
  * The three range answers do not need it: a series carries its own axis, and a window nobody fed for an hour
  * already draws that hour as gaps.
  */
final case class Observed[+A](value: A, at: Instant)

object Observed {
  given [A] => CanEqual[Observed[A], Observed[A]] = CanEqual.derived
}

/** One cluster's source of broker metrics, reduced to the questions this service asks.
  *
  * Stated in domain terms and implemented in `infrastructure`, which is the dependency direction rule A1
  * requires: nothing in this file knows whether the numbers arrive over JMX or as Prometheus text, and
  * nothing in this file can be broken by that choice changing.
  *
  * ==The implementation is a buffer, not an exporter==
  *
  * `kui.metrics.infrastructure.MetricsBuffer` is what a use case holds. A range question — twenty-four hours
  * at a five-minute step — is one an exporter cannot answer at all, because an exporter knows only about
  * *now*; a scrape loop fills the buffer in the background and every method here is answered from memory
  * without a network call on the path of a repaint. A cluster with no source configured answers before this
  * port is ever reached, so "not configured" is not something an implementation of it has to model.
  *
  * ==This port never throws==
  *
  * Everything that went wrong is a `Left[KuiError]`: unreachable, timed out, refused, or an answer KUI could
  * not parse. The caller is a use case whose job is to keep the rest of a dashboard working while one
  * exporter is down, and it cannot do that against a port that raises.
  *
  * ==Why five methods and not one document==
  *
  * One dead metric family costs one card. An exporter whitelist that serves the byte rates and not the
  * request percentiles is an ordinary configuration, and a single method returning everything would have to
  * decide whether that whole answer was a failure. Each of these can refuse on its own, and each refusal
  * becomes one `Section` (ADR-052).
  */
trait MetricsSourcePort[F[_]] {

  /** The cluster's throughput over `range`, ending at `endingAt`.
    *
    * The instant is a parameter rather than read from a clock inside the adapter so that the series a test
    * asserts against is the series a fixed clock produces, and so that two metrics answered for one screen
    * can be asked for the same moment rather than for two moments a few milliseconds apart.
    *
    * A source that answers but holds nothing for this window returns a series of absent buckets, not an empty
    * list of buckets: the axis is a property of the range, and a chart with no axis cannot show that the gap
    * covers a whole day.
    */
  def throughput(range: ThroughputRange, endingAt: Instant): F[Either[KuiError, ThroughputSeries]]

  /** The cluster's p99 request latency over `range`, produce and consumer fetch as two lines.
    *
    * The same axis rule as [[throughput]] and the same range vocabulary, because the two charts are stacked
    * on one screen. `Left` here means something stronger than a gap: the source answered and served no
    * request-latency family at all, which is a whitelist an operator has to widen rather than a quiet minute.
    */
  def latency(range: ThroughputRange, endingAt: Instant): F[Either[KuiError, LatencySeries]]

  /** The broker's idle ratios and purgatory depths as of the last scrape at or before `asOf`.
    *
    * A moment rather than a range: the card is three gauges and a gauge shows now. `asOf` is still a
    * parameter, because a reading older than the retention window has to be dropped rather than shown as
    * current, and because the [[Observed]] instant that comes back is what decides whether the section is
    * `ok` or `stale`.
    */
  def requestHandlers(asOf: Instant): F[Either[KuiError, Observed[RequestHandlerReading]]]

  /** The `count` topics receiving the most bytes as of the last scrape at or before `asOf`.
    *
    * Topics and not clients: a broker publishes no per-`client.id` byte rate unless quotas are configured
    * (ADR-052), and the field is named for what it holds.
    */
  def producers(count: Int, asOf: Instant): F[Either[KuiError, Observed[TopProducers]]]

  /** The mean size of a record on this cluster as of the last scrape at or before `asOf`.
    *
    * A mean and never a distribution: Kafka publishes no record-size histogram (ADR-052).
    */
  def recordSize(asOf: Instant): F[Either[KuiError, Observed[RecordSizeReading]]]
}
