package kui.config

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import kui.kernel.ClusterId

/** Where one cluster's broker metrics are read from, and how.
  *
  * KUI reads no broker metric today, and M7's adapter has two shapes to choose between: a Prometheus endpoint
  * — a JMX exporter, or anything else that serves the same text format — or JMX itself. Which one a
  * deployment has is a fact only the deployment knows, so it is declared here rather than guessed by probing
  * an address and hoping the wrong answer is a clean failure.
  *
  * @param url
  *   the address the metrics are served at. A `SafeUrl`, so the same SSRF rule that guards the registry and
  *   the OTLP collector guards this one: an operator who mistypes it cannot make KUI read a cloud instance's
  *   credential endpoint
  * @param kind
  *   which protocol lives at that address
  * @param callTimeout
  *   the whole-call budget for one scrape. Bounded below the scrape interval by the loader, because a scrape
  *   that outlives its interval overlaps the next one — the rule `kui.topics.scrapeTimeout` already follows
  */
final case class MetricsSourceSettings(
    url: SafeUrl,
    kind: MetricsSourceKind = MetricsSourceKind.Prometheus,
    callTimeout: FiniteDuration = MetricsSourceSettings.DefaultCallTimeout
)

object MetricsSourceSettings {

  val DefaultCallTimeout: FiniteDuration = 10.seconds

  val MinCallTimeout: FiniteDuration = 1.second
  val MaxCallTimeout: FiniteDuration = 60.seconds

  given CanEqual[MetricsSourceSettings, MetricsSourceSettings] = CanEqual.derived
}

/** The two shapes a metrics source can have. */
enum MetricsSourceKind {
  case Prometheus
  case Jmx

  def wireName: String = this match {
    case Prometheus => "prometheus"
    case Jmx => "jmx"
  }
}

object MetricsSourceKind {

  val All: List[MetricsSourceKind] = List(Prometheus, Jmx)

  def fromWire(raw: String): Option[MetricsSourceKind] =
    All.find(_.wireName == raw.trim.toLowerCase)

  given CanEqual[MetricsSourceKind, MetricsSourceKind] = CanEqual.derived
}

/** The metrics service's own dials: the `kui.metrics.*` slice.
  *
  * The section exists before the service does, and deliberately: `kui.metrics.sources` is the key that says
  * whether a cluster can be measured at all, and every card the metrics service feeds has to answer
  * `not_configured` for a cluster that has no entry here. That is not a failure mode, it is the design
  * (ADR-032) — so the *absence* of configuration is a value the product reads, and it needs a shape before
  * the service that reads it exists.
  *
  * Nothing about *what* is measured belongs here. Metric names, JMX object names and bucket boundaries are
  * the adapter's business; a deployment that had to name `kafka.server:type=BrokerTopicMetrics` in YAML would
  * be configuring KUI's source code.
  *
  * @param scrapeInterval
  *   how often each configured source is read. It is also the step of the series the samples land in, so
  *   changing it changes the resolution of every chart the metrics service draws
  * @param retention
  *   how far back the samples are kept. A range the browser asks for that is longer than this cannot be
  *   answered honestly, and the series primitive answers `None` rather than computing a percentage over four
  *   minutes and printing it as "over the last 24h"
  * @param maxSamplesPerSeries
  *   the hard bound on one series, whatever the other two keys multiply out to. Retention of thirty days at a
  *   five-second interval is half a million samples per series per cluster, and the operator who typed those
  *   two numbers did not ask for that much resident memory; this is the key that says so before the process
  *   runs out
  * @param sources
  *   the per-cluster addresses, keyed by the `ClusterId` in `kui.clusters[]`. A cluster with no entry has no
  *   source, which is the honest, common and fully supported case
  */
final case class MetricsConfig(
    scrapeInterval: FiniteDuration,
    retention: FiniteDuration,
    maxSamplesPerSeries: Int,
    sources: Map[ClusterId, MetricsSourceSettings]
) {

  /** The source for one cluster, or `None` when the deployment did not configure one.
    *
    * Given as a method so that no caller reads the map its own way and gets "not configured" subtly different
    * from the next caller's — the difference between `not_configured` and `unavailable` is a difference the
    * screens draw.
    */
  def sourceFor(cluster: ClusterId): Option[MetricsSourceSettings] = sources.get(cluster)

  /** Whether any cluster at all can be measured, for a readiness line or a startup log. */
  def isConfigured: Boolean = sources.nonEmpty
}

object MetricsConfig {

  val DefaultScrapeInterval: FiniteDuration = 30.seconds
  val DefaultRetention: FiniteDuration = 24.hours
  val DefaultMaxSamplesPerSeries: Int = 5000

  /** Below five seconds a scrape never finishes before the next one starts on a cluster worth scraping; above
    * an hour the charts are a historical record rather than a monitoring signal — the same bounds
    * `kui.topics.refreshInterval` is held to, for the same reasons.
    */
  val MinScrapeInterval: FiniteDuration = 5.seconds
  val MaxScrapeInterval: FiniteDuration = 1.hour

  /** The longest range any screen offers is thirty days, so retaining more than that would cost memory for
    * samples nothing can ask for.
    */
  val MinRetention: FiniteDuration = 1.minute
  val MaxRetention: FiniteDuration = 30.days

  val MinSamplesPerSeries: Int = 60
  val MaxSamplesPerSeries: Int = 100000

  /** What a process gets when nothing under `kui.metrics` is configured: the cadence and the window it would
    * use, and not one cluster it may measure. Every field here is also the default used per key, so
    * configuring one key never changes another.
    */
  val Default: MetricsConfig = MetricsConfig(
    scrapeInterval = DefaultScrapeInterval,
    retention = DefaultRetention,
    maxSamplesPerSeries = DefaultMaxSamplesPerSeries,
    sources = Map.empty
  )

  given CanEqual[MetricsConfig, MetricsConfig] = CanEqual.derived
}
