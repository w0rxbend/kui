package kui.metrics.domain

import java.time.Instant

import kui.kernel.error.KuiError

/** One cluster's source of broker metrics, reduced to the questions this service asks.
  *
  * Stated in domain terms and implemented in `infrastructure`, which is the dependency direction rule A1
  * requires: nothing in this file knows whether the numbers arrive over JMX or as Prometheus text, and
  * nothing in this file can be broken by that choice changing.
  *
  * ==There is no implementation of this port yet, and that is the point==
  *
  * KUI reads no broker metric today. This service ships the shape — the endpoint, the layering, the wiring,
  * the capability report — with the collector left out, so that M7 adds one adapter rather than a service. A
  * cluster with no source answers before this port is ever reached, so "not configured" is not something an
  * implementation of it has to model.
  *
  * ==This port never throws==
  *
  * Everything that went wrong is a `Left[KuiError]`: unreachable, timed out, refused, or an answer KUI could
  * not parse. The caller is a use case whose job is to keep the rest of a dashboard working while one
  * exporter is down, and it cannot do that against a port that raises.
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
}
